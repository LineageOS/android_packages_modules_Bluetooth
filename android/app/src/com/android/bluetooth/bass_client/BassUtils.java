/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client;

import static com.android.bluetooth.flags.Flags.leaudioBassScanWithInternalScanController;

import android.bluetooth.BluetoothUtils;
import android.bluetooth.BluetoothUtils.TypeValueEntry;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Bass Utility functions */
class BassUtils {
    private static final String TAG = BassUtils.class.getSimpleName();

    static boolean containUuid(List<ScanFilter> filters, ParcelUuid uuid) {
        for (ScanFilter filter : filters) {
            if (leaudioBassScanWithInternalScanController()) {
                if (filter.getServiceDataUuid().equals(uuid)) {
                    return true;
                }
            } else {
                if (filter.getServiceUuid().equals(uuid)) {
                    return true;
                }
            }
        }
        return false;
    }

    static int parseBroadcastId(byte[] broadcastIdBytes) {
        int broadcastId;
        broadcastId = (0x00FF0000 & (broadcastIdBytes[2] << 16));
        broadcastId |= (0x0000FF00 & (broadcastIdBytes[1] << 8));
        broadcastId |= (0x000000FF & broadcastIdBytes[0]);
        return broadcastId;
    }

    static Integer getBroadcastId(ScanResult scanResult) {
        if (scanResult == null) {
            Log.e(TAG, "Null scan result");
            return BassConstants.INVALID_BROADCAST_ID;
        }

        return getBroadcastId(scanResult.getScanRecord());
    }

    static Integer getBroadcastId(ScanRecord scanRecord) {
        if (scanRecord == null) {
            Log.e(TAG, "Null scan record");
            return BassConstants.INVALID_BROADCAST_ID;
        }

        Map<ParcelUuid, byte[]> listOfUuids = scanRecord.getServiceData();
        if (listOfUuids == null) {
            Log.e(TAG, "Null service data");
            return BassConstants.INVALID_BROADCAST_ID;
        }

        if (listOfUuids.containsKey(BassConstants.BAAS_UUID)) {
            byte[] bId = listOfUuids.get(BassConstants.BAAS_UUID);
            return BassUtils.parseBroadcastId(bId);
        } else {
            Log.e(TAG, "No broadcast Id in service data");
        }

        return BassConstants.INVALID_BROADCAST_ID;
    }

    static PublicBroadcastData getPublicBroadcastData(ScanRecord scanRecord) {
        if (scanRecord == null) {
            Log.e(TAG, "Null scan record");
            return null;
        }

        Map<ParcelUuid, byte[]> listOfUuids = scanRecord.getServiceData();
        if (listOfUuids == null) {
            Log.e(TAG, "Null service data");
            return null;
        }

        if (listOfUuids.containsKey(BassConstants.PUBLIC_BROADCAST_UUID)) {
            byte[] pbAnnouncement = listOfUuids.get(BassConstants.PUBLIC_BROADCAST_UUID);
            return PublicBroadcastData.parsePublicBroadcastData(pbAnnouncement);
        } else {
            log("No public broadcast data in service data");
        }

        return null;
    }

    static String getBroadcastName(ScanRecord scanRecord) {
        if (scanRecord == null) {
            Log.e(TAG, "Null scan record");
            return null;
        }
        byte[] rawBytes = scanRecord.getBytes();
        List<TypeValueEntry> entries = BluetoothUtils.parseLengthTypeValueBytes(rawBytes);
        if (rawBytes.length > 0 && rawBytes[0] > 0 && entries.isEmpty()) {
            Log.e(TAG, "Invalid LTV entries in Scan record");
            return null;
        }

        String broadcastName = null;
        for (TypeValueEntry entry : entries) {
            // Only use the first value of each type
            if (broadcastName == null && entry.getType() == BassConstants.BCAST_NAME_AD_TYPE) {
                byte[] bytes = entry.getValue();
                int len = bytes.length;
                if (len < BassConstants.BCAST_NAME_LEN_MIN
                        || len > BassConstants.BCAST_NAME_LEN_MAX) {
                    Log.e(TAG, "Invalid broadcast name length in Scan record" + len);
                    return null;
                }
                broadcastName = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return broadcastName;
    }

    static void log(String msg) {
        Log.d(TAG, msg);
    }

    static void printByteArray(byte[] array) {
        log("Entire byte Array as string: " + Arrays.toString(array));
    }
}
