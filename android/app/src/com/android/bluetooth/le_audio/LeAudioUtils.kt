/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.le_audio

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.util.Log

/** Utility functions for LE Audio Service. */
object LeAudioUtils {
    private const val TAG = "LeAudioUtils"

    /**
     * Parse the broadcast ID from the raw bytes.
     *
     * @param broadcastIdBytes the raw bytes
     * @return the broadcast ID
     */
    @JvmStatic
    fun parseBroadcastId(broadcastIdBytes: ByteArray): Int {
        var broadcastId = (0x00FF0000 and (broadcastIdBytes[2].toInt() shl 16))
        broadcastId = broadcastId or (0x0000FF00 and (broadcastIdBytes[1].toInt() shl 8))
        broadcastId = broadcastId or (0x000000FF and broadcastIdBytes[0].toInt())
        return broadcastId
    }

    /**
     * Get the broadcast ID from the scan result.
     *
     * @param scanResult the scan result
     * @return the broadcast ID
     */
    @JvmStatic
    fun getBroadcastId(scanResult: ScanResult?): Int {
        if (scanResult == null) {
            Log.e(TAG, "Null scan result")
            return LeAudioConstants.INVALID_BROADCAST_ID
        }
        return getBroadcastId(scanResult.scanRecord)
    }

    /**
     * Get the broadcast ID from the scan record.
     *
     * @param scanRecord the scan record
     * @return the broadcast ID
     */
    @JvmStatic
    fun getBroadcastId(scanRecord: ScanRecord?): Int {
        if (scanRecord == null) {
            Log.e(TAG, "Null scan record")
            return LeAudioConstants.INVALID_BROADCAST_ID
        }

        val serviceData = scanRecord.serviceData
        if (serviceData == null) {
            Log.e(TAG, "Null service data")
            return LeAudioConstants.INVALID_BROADCAST_ID
        }

        return if (serviceData.containsKey(LeAudioConstants.BAAS_UUID)) {
            val bId = serviceData[LeAudioConstants.BAAS_UUID]
            if (bId != null) parseBroadcastId(bId) else LeAudioConstants.INVALID_BROADCAST_ID
        } else {
            Log.d(TAG, "No broadcast Id in service data")
            LeAudioConstants.INVALID_BROADCAST_ID
        }
    }
}
