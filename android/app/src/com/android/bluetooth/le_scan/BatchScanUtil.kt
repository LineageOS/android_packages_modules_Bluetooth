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
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_ANONYMOUS
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_UNKNOWN
import android.bluetooth.BluetoothUtils.extractBytes
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Binder
import android.os.SystemClock
import android.provider.DeviceConfig
import android.provider.Settings
import android.util.Log
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_INTERVAL_MS
import com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_WINDOW_MS
import com.android.bluetooth.le_scan.ScanUtil.toBuilder
import com.android.bluetooth.util.NumberUtils
import java.util.concurrent.TimeUnit

private const val TAG = ScanUtil.TAG_PREFIX + "BatchScanUtil"

data class BatchScanParams(
    val scanMode: Int,
    val fullScanScannerId: Int,
    val truncatedScanScannerId: Int,
)

object BatchScanUtil {

    // The default floor value for LE batch scan report delays greater than 0
    const val DEFAULT_REPORT_DELAY_FLOOR_MS = 5000L

    const val ACTION_REFRESH_BATCHED_SCAN = "com.android.bluetooth.gatt.REFRESH_BATCHED_SCAN"

    private const val TRUNCATED_RESULT_SIZE = 11

    /** Return batch scan result type value defined in bt stack. */
    @JvmStatic
    fun resultType(params: BatchScanParams) =
        when {
            params.fullScanScannerId != -1 && params.truncatedScanScannerId != -1 ->
                ScanUtil.SCAN_RESULT_TYPE_BOTH
            params.truncatedScanScannerId != -1 -> ScanUtil.SCAN_RESULT_TYPE_TRUNCATED
            params.fullScanScannerId != -1 -> ScanUtil.SCAN_RESULT_TYPE_FULL
            else -> -1
        }

    @JvmStatic
    fun fullScanStoragePercent(resultType: Int) =
        when (resultType) {
            ScanUtil.SCAN_RESULT_TYPE_FULL -> 100
            ScanUtil.SCAN_RESULT_TYPE_TRUNCATED -> 0
            ScanUtil.SCAN_RESULT_TYPE_BOTH -> 50
            else -> 50
        }

    // Batched scan doesn't require high duty cycle scan because scan result is reported
    // infrequently anyway. To avoid redefining parameter sets, map to the low duty cycle parameter
    // set as follows.
    @JvmStatic
    fun windowMillis(adapterService: AdapterService, scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                    SCAN_MODE_BALANCED_WINDOW_MS,
                )
            ScanSettings.SCAN_MODE_SCREEN_OFF ->
                adapterService.screenOffLowPowerWindow.toMillis().toInt()
            else ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                    SCAN_MODE_LOW_POWER_WINDOW_MS,
                )
        }

    @JvmStatic
    fun intervalMillis(adapterService: AdapterService, scanMode: Int) =
        when (scanMode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                    SCAN_MODE_BALANCED_INTERVAL_MS,
                )
            ScanSettings.SCAN_MODE_SCREEN_OFF ->
                adapterService.screenOffLowPowerInterval.toMillis().toInt()
            else ->
                Settings.Global.getInt(
                    adapterService.contentResolver,
                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                    SCAN_MODE_LOW_POWER_INTERVAL_MS,
                )
        }

    /**
     * Ensures the report delay is either 0 or at least the floor value.
     *
     * @param settings are the scan settings passed into a request to start le scanning
     * @return the passed in ScanSettings object if the report delay is 0 or above the floor value;
     *   a new ScanSettings object with the report delay being the floor value if the original
     *   report delay was between 0 and the floor value (exclusive of both)
     * @see DEFAULT_REPORT_DELAY_FLOOR_MS
     */
    @JvmStatic
    fun enforceReportDelayFloor(settings: ScanSettings): ScanSettings {
        val originalDelay = settings.reportDelayMillis
        val header = "enforceReportDelayFloor():"

        if (originalDelay == 0L) {
            Log.d(TAG, "$header Report delay is 0, skipping floor enforcement")
            return settings
        }

        // Need to clear identity to pass device config permission check
        val callerToken = Binder.clearCallingIdentity()
        try {
            val floor =
                DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_BLUETOOTH,
                    "report_delay",
                    DEFAULT_REPORT_DELAY_FLOOR_MS,
                )
            return if (originalDelay >= floor) {
                Log.d(TAG, "$header Delay ${originalDelay}ms >= floor ${floor}ms, no changes")
                settings
            } else {
                Log.d(TAG, "$header Delay ${originalDelay}ms < floor, setting to ${floor}ms")
                settings.toBuilder().setReportDelay(floor).build()
            }
        } finally {
            Binder.restoreCallingIdentity(callerToken)
        }
    }

    @JvmStatic
    fun parseResults(
        adapterService: AdapterService,
        numRecords: Int,
        reportType: Int,
        batchRecord: ByteArray,
    ): Set<ScanResult> {
        if (numRecords == 0) return emptySet()
        val now = Utils.getLocalTimeString()
        val elapsed = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "Parsing $numRecords results (${batchRecord.size} bytes) at $now (elapsed: ${elapsed}ms)",
        )
        return when (reportType) {
            ScanUtil.SCAN_RESULT_TYPE_TRUNCATED ->
                truncatedResults(adapterService, numRecords, batchRecord)
            else -> fullResults(adapterService, numRecords, batchRecord)
        }
    }

    private fun truncatedResults(
        adapterService: AdapterService,
        numRecords: Int,
        batchRecord: ByteArray,
    ): Set<ScanResult> {
        val now = SystemClock.elapsedRealtimeNanos()
        val results: MutableSet<ScanResult> = HashSet(numRecords)
        for (i in 0..<numRecords) {
            val record = extractBytes(batchRecord, i * TRUNCATED_RESULT_SIZE, TRUNCATED_RESULT_SIZE)
            val address = extractBytes(record, 0, 6)
            Utils.reverse(address)
            val device = adapterService.getRemoteDevice(Utils.getAddressStringFromByte(address))
            val rssi = record[8].toInt()
            val nanos = now - parseTimestampNanos(extractBytes(record, 9, 2))
            @Suppress("DEPRECATION")
            results.add(ScanResult(device, ScanRecord.parseFromBytes(byteArrayOf()), rssi, nanos))
        }
        return results
    }

    private fun fullResults(
        adapterService: AdapterService,
        numRecords: Int,
        batchRecord: ByteArray,
    ): Set<ScanResult> {
        val now = SystemClock.elapsedRealtimeNanos()
        val results: MutableSet<ScanResult> = HashSet(numRecords)
        var position = 0
        while (position < batchRecord.size) {
            val address = extractBytes(batchRecord, position, 6)
            // TODO: remove temp hack.
            Utils.reverse(address)
            val addressStr = Utils.getAddressStringFromByte(address)
            position += 6

            var device: BluetoothDevice? = null
            if (Flags.useAddressTypeFromBatchScanResult()) {
                val addressType = batchRecord[position++].toUnsignedInt()

                val convertedAddressType =
                    when (addressType) {
                        ADDRESS_TYPE_PUBLIC,
                        ADDRESS_TYPE_RANDOM,
                        ADDRESS_TYPE_ANONYMOUS -> addressType
                        2 -> ADDRESS_TYPE_PUBLIC // AddressType::PUBLIC_IDENTITY_ADDRESS
                        3 -> ADDRESS_TYPE_RANDOM // AddressType::RANDOM_IDENTITY_ADDRESS
                        else -> {
                            Log.w(TAG, "${addressType} is not a valid Bluetooth address type.")
                            ADDRESS_TYPE_UNKNOWN
                        }
                    }

                try {
                    device = adapterService.getRemoteDevice(addressStr, convertedAddressType)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Could not get BluetoothDevice.", e)
                }
            } else {
                device = adapterService.getRemoteDevice(addressStr)
                // Skip address type.
                position++
            }

            // Skip tx power level
            position++
            val rssi = batchRecord[position++].toInt()
            val nanos = now - parseTimestampNanos(extractBytes(batchRecord, position, 2))
            position += 2

            // Combine advertise packet and scan response packet.
            val advertisePacketLen = batchRecord[position++].toUnsignedInt()
            val advertiseBytes = extractBytes(batchRecord, position, advertisePacketLen)
            position += advertisePacketLen
            val scanResponsePacketLen = batchRecord[position++].toUnsignedInt()
            val scanResponseBytes = extractBytes(batchRecord, position, scanResponsePacketLen)
            position += scanResponsePacketLen
            val scanRecordBytes = ByteArray(advertisePacketLen + scanResponsePacketLen)
            System.arraycopy(advertiseBytes, 0, scanRecordBytes, 0, advertisePacketLen)
            System.arraycopy(
                scanResponseBytes,
                0,
                scanRecordBytes,
                advertisePacketLen,
                scanResponsePacketLen,
            )
            if (Flags.useAddressTypeFromBatchScanResult() && device == null) {
                Log.w(TAG, "Dropping scan result due to invalid address / type")
                continue
            }

            @Suppress("DEPRECATION")
            results.add(ScanResult(device, ScanRecord.parseFromBytes(scanRecordBytes), rssi, nanos))
        }
        return results
    }

    @JvmStatic
    fun parseTimestampNanos(data: ByteArray): Long {
        val timestampUnit = NumberUtils.littleEndianByteArrayToInt(data).toLong()
        // Timestamp is in every 50 ms.
        return TimeUnit.MILLISECONDS.toNanos(timestampUnit * 50)
    }

    @JvmStatic
    fun permittedResults(
        adapterService: AdapterService,
        client: ScanClient,
        results: Set<ScanResult>,
    ): List<ScanResult> {
        if (ScanUtil.hasScanResultPermission(adapterService, client)) {
            return results.toList()
        }

        return results.filter { result ->
            client.associatedDevices.any { associatedDevice ->
                associatedDevice.equals(result.device.address, ignoreCase = true)
            }
        }
    }

    /** Converts a [Byte] to an unsigned [Int]. */
    private fun Byte.toUnsignedInt() = this.toInt() and 0xFF
}
