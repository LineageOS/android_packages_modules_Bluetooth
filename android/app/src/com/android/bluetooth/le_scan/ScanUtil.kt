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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.util.Log
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

private const val TAG = "ScanUtil"

object ScanUtil {

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

    // The default floor value for LE batch scan report delays greater than 0
    const val DEFAULT_REPORT_DELAY_FLOOR_MS = 5000L

    const val ACTION_REFRESH_BATCHED_SCAN = "com.android.bluetooth.gatt.REFRESH_BATCHED_SCAN"

    // Weights representing the duty cycle of each scan mode
    const val WEIGHT_OPPORTUNISTIC = 0
    const val WEIGHT_SCREEN_OFF_LOW_POWER = 5
    const val WEIGHT_LOW_POWER = 10
    const val WEIGHT_AMBIENT_DISCOVERY = 25
    const val WEIGHT_BALANCED = 25
    const val WEIGHT_LOW_LATENCY = 100

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
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> 0
            ScanSettings.SCAN_MODE_SCREEN_OFF -> 1
            ScanSettings.SCAN_MODE_LOW_POWER -> 2
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED -> 3
            // BALANCED and AMBIENT_DISCOVERY have the same settings and priority
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> 4
            ScanSettings.SCAN_MODE_LOW_LATENCY -> 5
            else -> -1
        }

    @JvmStatic
    fun weightForScanMode(scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> WEIGHT_OPPORTUNISTIC
            ScanSettings.SCAN_MODE_SCREEN_OFF -> WEIGHT_SCREEN_OFF_LOW_POWER
            ScanSettings.SCAN_MODE_LOW_POWER -> WEIGHT_LOW_POWER
            ScanSettings.SCAN_MODE_LOW_LATENCY -> WEIGHT_LOW_LATENCY
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY,
            ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED -> WEIGHT_BALANCED
            else -> WEIGHT_LOW_POWER
        }

    @JvmStatic
    fun scanModeToString(scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> "OPPORTUNISTIC"
            ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
            ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
            ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY -> "AMBIENT_DISCOVERY"
            else -> "UNKNOWN($scanMode)"
        }

    @JvmStatic
    fun callbackTypeToString(callbackType: Int) =
        when (callbackType) {
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES -> "ALL_MATCHES"
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> "FIRST_MATCH"
            ScanSettings.CALLBACK_TYPE_MATCH_LOST -> "LOST"
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH -> "ALL_MATCHES_AUTO_BATCH"
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST ->
                "[FIRST_MATCH | LOST]"
            else -> "UNKNOWN($callbackType)"
        }

    @JvmStatic
    fun requiresScreenOn(client: ScanClient) =
        !isOpportunisticScanClient(client) && !isFilteredScan(client)

    @JvmStatic
    fun requiresLocationOn(client: ScanClient) =
        !client.hasDisavowedLocation && !isFilteredScan(client)

    // A valid filter need at least one field not empty
    private fun isFilteredScan(client: ScanClient) = client.filters.any { !it.isAllFieldsEmpty }

    @JvmStatic
    fun isBackgroundScan(settings: ScanSettings) =
        (settings.callbackType and ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0

    @JvmStatic
    fun isBatchScan(settings: ScanSettings) =
        settings.callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES &&
            settings.reportDelayMillis != 0L

    @JvmStatic
    fun isOpportunisticScan(settings: ScanSettings) =
        settings.scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC

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

    @JvmStatic
    fun isAllMatchesAutoBatchScanClient(client: ScanClient) =
        client.settings.callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH

    @JvmStatic
    fun isBatchClient(client: ScanClient?) = client != null && isBatchScan(client.settings)

    @JvmStatic
    fun isForceDowngradedScanClient(client: ScanClient) =
        isTimeoutScanClient(client) || isDowngradedScanClient(client)

    private fun isTimeoutScanClient(client: ScanClient) =
        client.appScanStats.map { it.isScanTimeout(client.scannerId) }.orElse(false)

    @JvmStatic
    fun isDowngradedScanClient(client: ScanClient) =
        client.appScanStats.map { it.isScanDowngraded(client.scannerId) }.orElse(false)

    @JvmStatic
    fun isAutoBatchScanClientEnabled(client: ScanClient) =
        client.appScanStats.map { it.isAutoBatchScan(client.scannerId) }.orElse(false)

    @JvmStatic
    fun isPhyConfigured(client: ScanClient, use1mPhy: Boolean) =
        client.settings.phy == ScanSettings.PHY_LE_ALL_SUPPORTED ||
            client.settings.phy ==
                if (use1mPhy) BluetoothDevice.PHY_LE_1M else BluetoothDevice.PHY_LE_CODED

    @JvmStatic
    fun shouldUpdateScan(newScanSetting: Int, oldScanSetting: Int) =
        newScanSetting != Int.MIN_VALUE &&
            newScanSetting != ScanSettings.SCAN_MODE_OPPORTUNISTIC &&
            newScanSetting != oldScanSetting

    @JvmStatic
    fun upgradeScanModeByOneLevel(client: ScanClient) =
        when (client.scanModeApp) {
            ScanSettings.SCAN_MODE_LOW_POWER ->
                client.updateScanMode(ScanSettings.SCAN_MODE_BALANCED)
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY ->
                client.updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            else -> false
        }

    @JvmStatic
    fun setOpportunisticScanClient(client: ScanClient) {
        val existingSettings = client.settings
        client.settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
                .setCallbackType(existingSettings.callbackType)
                .setScanResultType(existingSettings.scanResultType)
                .setReportDelay(existingSettings.reportDelayMillis)
                .setNumOfMatches(existingSettings.numOfMatches)
                .build()
    }

    @JvmStatic
    fun setAutoBatchScanClient(client: ScanClient) {
        if (isAutoBatchScanClientEnabled(client)) {
            return
        }
        client.updateScanMode(ScanSettings.SCAN_MODE_SCREEN_OFF)
        val scanModeString = ScanSettings.getScanModeString(client.scanModeApp)
        Log.d(TAG, "Scan mode update during setAutoBatchScanClient() to $scanModeString")
        client.appScanStats.ifPresent { appScanStats ->
            appScanStats.setAutoBatchScan(client.scannerId, true)
        }
    }

    @JvmStatic
    fun clearAutoBatchScanClient(client: ScanClient) {
        if (!isAutoBatchScanClientEnabled(client)) {
            return
        }
        client.updateScanMode(client.scanModeApp)
        val scanModeString = ScanSettings.getScanModeString(client.scanModeApp)
        Log.d(TAG, "Scan mode update during clearAutoBatchScanClient() to $scanModeString")
        client.appScanStats.ifPresent { appScanStats ->
            appScanStats.setAutoBatchScan(client.scannerId, false)
        }
    }

    @JvmStatic
    fun scanFilterToStringWithoutNullParam(filter: ScanFilter): String {
        return buildString {
            append("BluetoothLeScanFilter [")
            filter.deviceName?.let { append(" DeviceName=").append(it) }
            filter.deviceAddress?.let { append(" DeviceAddress=").append(it) }
            filter.serviceUuid?.let { append(" ServiceUuid=").append(it) }
            filter.serviceUuidMask?.let { append(" ServiceUuidMask=").append(it) }
            filter.serviceSolicitationUuid?.let { append(" ServiceSolicitationUuid=").append(it) }
            filter.serviceSolicitationUuidMask?.let {
                append(" ServiceSolicitationUuidMask=").append(it)
            }
            filter.serviceDataUuid?.let { append(" ServiceDataUuid=").append(it) }
            filter.serviceData?.let { append(" ServiceData=").append(it.contentToString()) }
            filter.serviceDataMask?.let { append(" ServiceDataMask=").append(it.contentToString()) }
            if (filter.manufacturerId >= 0) {
                append(" ManufacturerId=").append(filter.manufacturerId)
            }
            filter.manufacturerData?.let {
                append(" ManufacturerData=").append(it.contentToString())
            }
            filter.manufacturerDataMask?.let {
                append(" ManufacturerDataMask=").append(it.contentToString())
            }
            append(" ]")
        }
    }
}
