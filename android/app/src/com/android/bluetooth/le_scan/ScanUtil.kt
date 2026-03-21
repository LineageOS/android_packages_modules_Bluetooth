/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.le_scan

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY
import android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
import android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
import android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER
import android.bluetooth.le.ScanSettings.SCAN_MODE_OPPORTUNISTIC
import android.bluetooth.le.ScanSettings.SCAN_MODE_SCREEN_OFF
import android.bluetooth.le.ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import com.android.bluetooth.Util.blockedByLocationOff
import com.android.bluetooth.Utils.millsToUnit
import com.android.bluetooth.btservice.AdapterService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import libcore.util.HexEncoding

private const val TAG = ScanUtil.TAG_PREFIX + "ScanUtil"

object ScanUtil {
    const val TAG_PREFIX = "BtScan."

    const val DEFAULT_SCAN_QUOTA_COUNT = 5
    @JvmField val DEFAULT_SCAN_QUOTA_WINDOW = 30.seconds.toJavaDuration()
    @JvmField val DEFAULT_SCAN_TIMEOUT = 10.minutes.toJavaDuration()
    @JvmField val DEFAULT_SCAN_UPGRADE_DURATION = 6.seconds.toJavaDuration()
    @JvmField val DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING = 6.seconds.toJavaDuration()
    // TODO(b/478349128): tune the value of DEFAULT_SCAN_THROTTLE_DELAY
    val DEFAULT_SCAN_THROTTLE_DELAY = 2.seconds

    // Scan params corresponding to regular scan setting
    const val SCAN_MODE_LOW_POWER_WINDOW_MS = 140
    const val SCAN_MODE_LOW_POWER_INTERVAL_MS = 1400
    const val SCAN_MODE_BALANCED_WINDOW_MS = 183
    const val SCAN_MODE_BALANCED_INTERVAL_MS = 730
    const val SCAN_MODE_LOW_LATENCY_WINDOW_MS = 100
    const val SCAN_MODE_LOW_LATENCY_INTERVAL_MS = 100

    @JvmField val SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW = 512.milliseconds.toJavaDuration()
    @JvmField val SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL = 10240.milliseconds.toJavaDuration()
    @JvmField val SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW = 183.milliseconds.toJavaDuration()
    @JvmField val SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL = 730.milliseconds.toJavaDuration()

    // Result types defined in bt stack
    const val SCAN_RESULT_TYPE_TRUNCATED = 1
    const val SCAN_RESULT_TYPE_FULL = 2
    const val SCAN_RESULT_TYPE_BOTH = 3

    private const val ONFOUND_SIGHTINGS_AGGRESSIVE = 1
    private const val ONFOUND_SIGHTINGS_STICKY = 4

    // Onfound/onlost for scan settings
    private const val MATCH_MODE_AGGRESSIVE_TIMEOUT_FACTOR = 1

    private const val MATCH_MODE_STICKY_TIMEOUT_FACTOR = 3
    private const val ONLOST_FACTOR = 2
    private const val ONLOST_ONFOUND_BASE_TIMEOUT_MS = 500

    // Delivery mode defined in bt stack.
    private const val DELIVERY_MODE_IMMEDIATE = 0
    const val DELIVERY_MODE_ON_FOUND_LOST = 1
    const val DELIVERY_MODE_BATCH = 2

    // Weights representing the duty cycle of each scan mode
    const val WEIGHT_OPPORTUNISTIC = 0
    const val WEIGHT_SCREEN_OFF_LOW_POWER = 5
    const val WEIGHT_LOW_POWER = 10
    const val WEIGHT_AMBIENT_DISCOVERY = 25
    const val WEIGHT_BALANCED = 25
    const val WEIGHT_LOW_LATENCY = 100

    const val MIN_OFFLOADED_FILTERS = 10
    const val MIN_OFFLOADED_SCAN_STORAGE_BYTES = 1024

    const val SCAN_ALLOWANCE_SECONDS_PROPERTY = "bluetooth.ble.scan.scan_allowance_seconds.config"
    private val DEFAULT_SCAN_ALLOWANCE_SECONDS = 6.minutes.inWholeSeconds.toInt()

    fun getScanAllowance() =
        SystemProperties.getInt(SCAN_ALLOWANCE_SECONDS_PROPERTY, DEFAULT_SCAN_ALLOWANCE_SECONDS)
            .seconds

    @JvmStatic
    fun isOffloadedFilteringSupported(adapterService: AdapterService) =
        adapterService.numOfOffloadedScanFilterSupported >= MIN_OFFLOADED_FILTERS

    @JvmStatic
    fun isOffloadedScanBatchingSupported(adapterService: AdapterService) =
        adapterService.offloadedScanResultStorage >= MIN_OFFLOADED_SCAN_STORAGE_BYTES

    @JvmStatic fun findById(clients: Set<ScanClient>, id: Int) = clients.find { it.scannerId == id }

    @JvmStatic
    fun hasScanResultPermission(adapterService: AdapterService, client: ScanClient) =
        when {
            // Bypass permission check for internal clients
            client.isInternal ||
                client.hasNetworkSettingsPermission ||
                client.hasNetworkSetupWizardPermission ||
                client.hasScanWithoutLocationPermission ||
                client.hasDisavowedLocation -> true
            else ->
                client.hasLocationPermission &&
                    !adapterService.blockedByLocationOff(client.userHandle!!)
        }

    // Convert scanWindow and scanInterval from ms to LE scan units(0.625ms)
    @JvmStatic
    fun scanWindow(adapterService: AdapterService, client: ScanClient?) =
        if (client == null) 0 else millsToUnit(windowMillis(adapterService, client.settings))

    @JvmStatic
    fun scanInterval(adapterService: AdapterService, client: ScanClient?) =
        if (client == null) 0 else millsToUnit(intervalMillis(adapterService, client.settings))

    @JvmStatic
    fun windowMillis(adapterService: AdapterService, settings: ScanSettings) =
        when (settings.scanMode) {
            SCAN_MODE_LOW_LATENCY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_LATENCY_WINDOW_MS,
                    SCAN_MODE_LOW_LATENCY_WINDOW_MS,
                )
            SCAN_MODE_BALANCED,
            SCAN_MODE_AMBIENT_DISCOVERY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                    SCAN_MODE_BALANCED_WINDOW_MS,
                )
            SCAN_MODE_LOW_POWER ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                    SCAN_MODE_LOW_POWER_WINDOW_MS,
                )
            SCAN_MODE_SCREEN_OFF -> adapterService.screenOffLowPowerWindow.toMillis().toInt()
            SCAN_MODE_SCREEN_OFF_BALANCED ->
                adapterService.screenOffBalancedWindow.toMillis().toInt()
            else ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                    SCAN_MODE_LOW_POWER_WINDOW_MS,
                )
        }

    @JvmStatic
    fun intervalMillis(adapterService: AdapterService, settings: ScanSettings) =
        when (settings.scanMode) {
            SCAN_MODE_LOW_LATENCY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_LATENCY_INTERVAL_MS,
                    SCAN_MODE_LOW_LATENCY_INTERVAL_MS,
                )

            SCAN_MODE_BALANCED,
            SCAN_MODE_AMBIENT_DISCOVERY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                    SCAN_MODE_BALANCED_INTERVAL_MS,
                )
            SCAN_MODE_LOW_POWER ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                    SCAN_MODE_LOW_POWER_INTERVAL_MS,
                )
            SCAN_MODE_SCREEN_OFF -> adapterService.screenOffLowPowerInterval.toMillis().toInt()
            SCAN_MODE_SCREEN_OFF_BALANCED ->
                adapterService.screenOffBalancedInterval.toMillis().toInt()
            else ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                    SCAN_MODE_LOW_POWER_INTERVAL_MS,
                )
        }

    @JvmStatic
    fun scanPhyMask(usePhy1m: Boolean, usePhyCoded: Boolean): Int {
        var phy = 0
        if (usePhy1m) {
            phy = phy or BluetoothDevice.PHY_LE_1M_MASK
        }
        if (usePhyCoded) {
            phy = phy or BluetoothDevice.PHY_LE_CODED_MASK
        }
        return phy
    }

    @JvmStatic
    fun onFoundOnLostTimeoutMillis(settings: ScanSettings, onFound: Boolean): Int {
        val timeout = ONLOST_ONFOUND_BASE_TIMEOUT_MS

        var factor =
            if (settings.matchMode == ScanSettings.MATCH_MODE_AGGRESSIVE) {
                MATCH_MODE_AGGRESSIVE_TIMEOUT_FACTOR
            } else {
                MATCH_MODE_STICKY_TIMEOUT_FACTOR
            }

        if (!onFound) {
            factor *= ONLOST_FACTOR
        }

        return timeout * factor
    }

    @JvmStatic
    fun onFoundOnLostSightings(settings: ScanSettings) =
        when (settings.matchMode) {
            ScanSettings.MATCH_MODE_AGGRESSIVE -> ONFOUND_SIGHTINGS_AGGRESSIVE
            else -> ONFOUND_SIGHTINGS_STICKY
        }

    @JvmStatic
    fun deliveryMode(client: ScanClient?): Int {
        val header = "deliveryMode($client):"
        return when {
            client == null -> {
                Log.d(TAG, "$header Client is null, defaulting to DELIVERY_MODE_IMMEDIATE")
                DELIVERY_MODE_IMMEDIATE
            }
            (client.settings.callbackType and ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0 ||
                (client.settings.callbackType and ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0 -> {
                val types = "CALLBACK_TYPE_FIRST_MATCH OR CALLBACK_TYPE_MATCH_LOST"
                Log.d(TAG, "$header Callback type is $types, using DELIVERY_MODE_ON_FOUND_LOST")
                DELIVERY_MODE_ON_FOUND_LOST
            }
            isAllMatchesAutoBatchScanClient(client) -> {
                val enabled = isAutoBatchScanClientEnabled(client)
                val mode = if (enabled) "DELIVERY_MODE_BATCH" else "DELIVERY_MODE_IMMEDIATE"
                Log.d(TAG, "$header Client is auto-batch (enabled=$enabled), using $mode")
                if (enabled) DELIVERY_MODE_BATCH else DELIVERY_MODE_IMMEDIATE
            }
            else -> {
                val delay = client.settings.reportDelayMillis
                val mode = if (delay == 0L) "DELIVERY_MODE_IMMEDIATE" else "DELIVERY_MODE_BATCH"
                Log.d(TAG, "$header Using report delay=${delay}ms to set delivery mode to $mode")
                if (delay == 0L) DELIVERY_MODE_IMMEDIATE else DELIVERY_MODE_BATCH
            }
        }
    }

    @JvmStatic
    fun minScanMode(oldScanMode: Int, newScanMode: Int) =
        if (priorityForScanMode(oldScanMode) <= priorityForScanMode(newScanMode)) {
            oldScanMode
        } else {
            newScanMode
        }

    @JvmStatic
    fun priorityForScanMode(scanMode: Int) =
        when (scanMode) {
            SCAN_MODE_OPPORTUNISTIC -> 0
            SCAN_MODE_SCREEN_OFF -> 1
            SCAN_MODE_LOW_POWER -> 2
            SCAN_MODE_SCREEN_OFF_BALANCED -> 3
            // BALANCED and AMBIENT_DISCOVERY have the same settings and priority
            SCAN_MODE_BALANCED,
            SCAN_MODE_AMBIENT_DISCOVERY -> 4
            SCAN_MODE_LOW_LATENCY -> 5
            else -> -1
        }

    @JvmStatic
    fun weightForScanMode(scanMode: Int) =
        when (scanMode) {
            SCAN_MODE_OPPORTUNISTIC -> WEIGHT_OPPORTUNISTIC
            SCAN_MODE_SCREEN_OFF -> WEIGHT_SCREEN_OFF_LOW_POWER
            SCAN_MODE_LOW_POWER -> WEIGHT_LOW_POWER
            SCAN_MODE_LOW_LATENCY -> WEIGHT_LOW_LATENCY
            SCAN_MODE_BALANCED,
            SCAN_MODE_AMBIENT_DISCOVERY,
            SCAN_MODE_SCREEN_OFF_BALANCED -> WEIGHT_BALANCED
            else -> WEIGHT_LOW_POWER
        }

    @JvmStatic
    fun statusToString(status: Int) =
        when (status) {
            ScanCallback.NO_ERROR -> "SUCCESS"
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "OUT_OF_HARDWARE_RESOURCES"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCANNING_TOO_FREQUENTLY"
            else -> "UNKNOWN($status)"
        }

    @JvmStatic fun scanModeToString(scanMode: Int) = ScanMode(scanMode).toString()

    @JvmStatic
    fun requiresScreenOn(client: ScanClient) =
        !isOpportunisticScanClient(client) && !client.hasNonEmptyFilters

    @JvmStatic
    fun requiresLocationOn(client: ScanClient) =
        !client.hasDisavowedLocation && !client.hasNonEmptyFilters

    fun isBackgroundScan(settings: ScanSettings) =
        (settings.callbackType and ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0

    fun isBatchScan(settings: ScanSettings) =
        settings.callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES &&
            settings.reportDelayMillis != 0L

    fun isOpportunisticScan(settings: ScanSettings) = settings.scanMode == SCAN_MODE_OPPORTUNISTIC

    @JvmStatic
    fun isExemptFromScanTimeout(client: ScanClient) =
        isOpportunisticScanClient(client) || isFirstMatchScanClient(client)

    @JvmStatic
    fun isExemptFromAutoBatchScanUpdate(client: ScanClient) =
        isOpportunisticScanClient(client) || !isAllMatchesAutoBatchScanClient(client)

    @JvmStatic
    fun isOpportunisticScanClient(client: ScanClient) = isOpportunisticScan(client.settings)

    private fun isFirstMatchScanClient(client: ScanClient) =
        (client.settings.callbackType and ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0

    fun isAllMatchesAutoBatchScanClient(client: ScanClient) =
        client.settings.callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH

    @JvmStatic
    fun isBatchClient(client: ScanClient?) = client != null && isBatchScan(client.settings)

    @JvmStatic
    fun isForceDowngradedScanClient(client: ScanClient) =
        isTimeoutScanClient(client) || isDowngradedScanClient(client)

    private fun isTimeoutScanClient(client: ScanClient) =
        client.appScanStats.isScanTimeout(client.scannerId)

    @JvmStatic
    fun isDowngradedScanClient(client: ScanClient) =
        client.appScanStats.isScanDowngraded(client.scannerId)

    @JvmStatic
    fun isAutoBatchScanClientEnabled(client: ScanClient) =
        client.appScanStats.isAutoBatchScan(client.scannerId)

    @JvmStatic
    fun getAggressiveClient(
        clients: Set<ScanClient>,
        use1mPhy: Boolean,
        isBatch: Boolean,
    ): ScanClient? {
        var result: ScanClient? = null
        var currentScanModePriority = Int.MIN_VALUE
        for (client in clients) {
            // Batch is only done on the 1M PHY and the client PHY setting is ignored
            if (!isBatch && !isPhyConfigured(client, use1mPhy)) {
                continue
            }
            if (isOpportunisticScanClient(client)) {
                continue
            }
            val priority = priorityForScanMode(client.settings.scanMode)
            if (priority > currentScanModePriority) {
                result = client
                currentScanModePriority = priority
            }
        }
        return result
    }

    private fun isPhyConfigured(client: ScanClient, use1mPhy: Boolean) =
        client.settings.phy == ScanSettings.PHY_LE_ALL_SUPPORTED ||
            client.settings.phy ==
                if (use1mPhy) BluetoothDevice.PHY_LE_1M else BluetoothDevice.PHY_LE_CODED

    @JvmStatic
    fun shouldUpdateScan(newScanSetting: Int, oldScanSetting: Int) =
        newScanSetting != Int.MIN_VALUE &&
            newScanSetting != SCAN_MODE_OPPORTUNISTIC &&
            newScanSetting != oldScanSetting

    @JvmStatic
    fun upgradeScanModeByOneLevel(client: ScanClient) =
        when (client.scanModeApp) {
            SCAN_MODE_LOW_POWER -> client.updateScanMode(SCAN_MODE_BALANCED)
            SCAN_MODE_BALANCED,
            SCAN_MODE_AMBIENT_DISCOVERY -> client.updateScanMode(SCAN_MODE_LOW_LATENCY)
            else -> false
        }

    @JvmStatic
    fun setOpportunisticScanClient(client: ScanClient) {
        client.settings = client.settings.toBuilder().setScanMode(SCAN_MODE_OPPORTUNISTIC).build()
    }

    @JvmStatic
    fun setAutoBatchScanClient(client: ScanClient) {
        if (isAutoBatchScanClientEnabled(client)) {
            return
        }
        val scanMode = ScanMode(SCAN_MODE_SCREEN_OFF)
        Log.d(TAG, "setAutoBatchScanClient($client): Update scan mode to $scanMode")
        client.updateScanMode(SCAN_MODE_SCREEN_OFF)
        client.appScanStats.setAutoBatchScan(client.scannerId, true)
    }

    @JvmStatic
    fun clearAutoBatchScanClient(client: ScanClient) {
        if (!isAutoBatchScanClientEnabled(client)) {
            return
        }
        val scanMode = ScanMode(client.scanModeApp)
        Log.d(TAG, "clearAutoBatchScanClient($client): Update scan mode to $scanMode")
        client.updateScanMode(client.scanModeApp)
        client.appScanStats.setAutoBatchScan(client.scannerId, false)
    }

    // EN format defined here:
    // https://blog.google/documents/70/Exposure_Notification_-_Bluetooth_Specification_v1.2.2.pdf
    private val EXPOSURE_NOTIFICATION_FLAGS_PREAMBLE =
        // size 2, flag field, flags byte (value is not important)
        byteArrayOf(0x02.toByte(), 0x01.toByte())

    private const val EXPOSURE_NOTIFICATION_FLAGS_LENGTH = 0x2 + 1
    private val EXPOSURE_NOTIFICATION_PAYLOAD_PREAMBLE =
        byteArrayOf(
            // size 3, complete 16 bit UUID, EN UUID -> (0x03, 0x03, 0x6F, 0xFD)
            0x03.toByte(),
            0x03.toByte(),
            0x6F.toByte(),
            0xFD.toByte(),
            // size 23, data for 16 bit UUID, EN UUID -> (0x17, 0x16, 0x6F, 0xFD)
            0x17.toByte(),
            0x16.toByte(),
            0x6F.toByte(),
            0xFD.toByte(),
            // ...payload
        )
    private const val EXPOSURE_NOTIFICATION_PAYLOAD_LENGTH = 0x03 + 0x17 + 2

    @JvmStatic
    fun getSanitizedExposureNotification(scanRecord: ScanRecord, rssi: Int): ScanResult? {
        // Remove the flags part of the payload, if present
        val record =
            if (
                scanRecord.bytes.size > EXPOSURE_NOTIFICATION_FLAGS_LENGTH &&
                    scanRecord.bytes.startsWith(EXPOSURE_NOTIFICATION_FLAGS_PREAMBLE)
            ) {
                ScanRecord.parseFromBytes(
                    scanRecord.bytes.copyOfRange(
                        EXPOSURE_NOTIFICATION_FLAGS_LENGTH,
                        scanRecord.bytes.size,
                    )
                )
            } else {
                scanRecord
            }

        if (record.bytes.size != EXPOSURE_NOTIFICATION_PAYLOAD_LENGTH) {
            return null
        }
        if (!record.bytes.startsWith(EXPOSURE_NOTIFICATION_PAYLOAD_PREAMBLE)) {
            return null
        }

        return ScanResult(null, 0, 0, 0, 0, 0, rssi, 0, record, 0)
    }

    @JvmStatic
    fun convertAllowanceToRemainingTime(allowance: Duration, scanMode: Int) =
        allowance * WEIGHT_LOW_LATENCY / weightForScanMode(scanMode)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) {
            return false
        }
        for (i in prefix.indices) {
            if (prefix[i] != this[i]) {
                return false
            }
        }
        return true
    }

    fun ScanSettings.toBuilder() =
        ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(callbackType)
            .setScanResultType(scanResultType)
            .setReportDelay(reportDelayMillis)
            .setMatchMode(matchMode)
            .setNumOfMatches(numOfMatches)
            .setLegacy(legacy)
            .setPhy(phy)
            .setRssiThreshold(rssiThreshold)
            .setScanType(scanType)

    @JvmStatic
    fun ScanSettings.toStringShort() =
        "ScanSettings(mode=${ScanMode(scanMode)}" +
            ", reportDelayMs=$reportDelayMillis" +
            ", callbackType=${CallbackType(callbackType)}" +
            ", resultType=${ResultType(scanResultType)})"

    fun ScanFilter.toStringWithoutNullParam() = buildString {
        append("Filter: [")
        deviceName?.let { append(" DeviceName=").append(it) }
        deviceAddress?.let { append(" DeviceAddress=").append(it) }
        serviceUuid?.let { append(" ServiceUuid=").append(it) }
        serviceUuidMask?.let { append(" ServiceUuidMask=").append(it) }
        serviceSolicitationUuid?.let { append(" ServiceSolicitationUuid=").append(it) }
        serviceSolicitationUuidMask?.let { append(" ServiceSolicitationUuidMask=").append(it) }
        serviceDataUuid?.let { append(" ServiceDataUuid=").append(it) }
        serviceData?.let { append(" ServiceData=").append(it.contentToString()) }
        serviceDataMask?.let { append(" ServiceDataMask=").append(it.contentToString()) }
        if (manufacturerId >= 0) {
            append(" ManufacturerId=").append(manufacturerId)
        }
        manufacturerData?.let { append(" ManufacturerData=").append(it.contentToString()) }
        manufacturerDataMask?.let { append(" ManufacturerDataMask=").append(it.contentToString()) }
        append(" ]")
    }
}

@JvmInline
value class CallbackType(val value: Int) {
    override fun toString() =
        when (value) {
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES -> "ALL_MATCHES"
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> "FIRST_MATCH"
            ScanSettings.CALLBACK_TYPE_MATCH_LOST -> "LOST"
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH -> "ALL_MATCHES_AUTO_BATCH"
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST ->
                "[FIRST_MATCH | LOST]"
            else -> "UNKNOWN($value)"
        }
}

@JvmInline
value class MatchMode(val value: Int) {
    override fun toString() =
        when (value) {
            ScanSettings.MATCH_MODE_AGGRESSIVE -> "AGGRESSIVE"
            ScanSettings.MATCH_MODE_STICKY -> "STICKY"
            else -> "UNKNOWN($value)"
        }
}

@JvmInline
value class NumberOfMatches(val value: Int) {
    override fun toString() =
        when (value) {
            ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT -> "ONE"
            ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT -> "FEW"
            ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT -> "MAX"
            else -> "UNKNOWN($value)"
        }
}

@JvmInline
value class Phy(val value: Int) {
    override fun toString() =
        when (value) {
            ScanSettings.PHY_LE_ALL_SUPPORTED -> "ALL"
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_CODED -> "CODED"
            else -> "UNKNOWN($value)"
        }
}

@JvmInline
value class ResultType(val value: Int) {
    override fun toString() =
        when (value) {
            ScanSettings.SCAN_RESULT_TYPE_FULL -> "FULL"
            ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED -> "ABBREVIATED"
            else -> "UNKNOWN($value)"
        }
}

@JvmInline
value class ScanMode(val value: Int) {
    override fun toString() =
        when (value) {
            SCAN_MODE_OPPORTUNISTIC -> "OPPORTUNISTIC"
            SCAN_MODE_LOW_POWER -> "LOW_POWER"
            SCAN_MODE_BALANCED -> "BALANCED"
            SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
            SCAN_MODE_AMBIENT_DISCOVERY -> "AMBIENT_DISCOVERY"
            SCAN_MODE_SCREEN_OFF -> "SCREEN_OFF"
            SCAN_MODE_SCREEN_OFF_BALANCED -> "SCREEN_OFF_BALANCED"
            else -> "UNKNOWN($value)"
        }
}

@JvmInline
value class Type(val value: Int) {
    override fun toString() =
        when (value) {
            ScanSettings.SCAN_TYPE_UNKNOWN -> "UNKNOWN"
            ScanSettings.SCAN_TYPE_PASSIVE -> "PASSIVE"
            ScanSettings.SCAN_TYPE_ACTIVE -> "ACTIVE"
            else -> "UNKNOWN($value)"
        }
}

object ScanTestUtil {
    /** Example raw beacons captured from a Blue Charm BC011 */
    private val TEST_MODE_BEACONS =
        arrayOf(
            "020106",
            "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
            "0201060303AAFE1716AAFE00EE626C7565636861726D31000000000001000009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
            "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080EFE13551109426C7565436861726D5F313639363835000000000000000000",
            "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C509168020691E0EFE13551109426C7565436861726D5F31363936383500000000",
        )

    @JvmStatic
    fun ScanController.runTestCycle() = TEST_MODE_BEACONS.forEach { test ->
        onScanResultInternal(
            0x1b,
            0x1,
            "DD:34:02:05:5C:4D",
            1,
            0,
            0xff,
            127,
            -54,
            0x0,
            HexEncoding.decode(test),
            "DD:34:02:05:5C:4E",
        )
    }
}
