/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import android.bluetooth.BluetoothUuid;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Helper class used to manage MSFT Advertisement Monitors. */
public class MsftAdvMonitor {
    private static final String TAG = ScanUtil.TAG_PREFIX + MsftAdvMonitor.class.getSimpleName();

    /* IRK filtering is not yet supported */
    public static final int MSFT_CONDITION_TYPE_INVALID = 0x00;
    public static final int MSFT_CONDITION_TYPE_PATTERNS = 0x01;
    public static final int MSFT_CONDITION_TYPE_UUID = 0x02;
    // public static final int MSFT_CONDITION_TYPE_IRK = 0x03;
    public static final int MSFT_CONDITION_TYPE_ADDRESS = 0x04;

    // Hardcoded values taken from CrOS defaults
    private static final byte RSSI_THRESHOLD_HIGH = (byte) 0xBF; // 191
    private static final byte RSSI_THRESHOLD_LOW = (byte) 0xB0; // 176
    private static final byte RSSI_THRESHOLD_LOW_TIME_INTERVAL = (byte) 0x28; // 40s
    private static final byte RSSI_SAMPLING_PERIOD = (byte) 0x05; // 500ms
    private static final byte FILTER_PATTERN_START_POSITION = (byte) 0x00;

    public static class Monitor {
        public byte rssi_threshold_high;
        public byte rssi_threshold_low;
        public byte rssi_threshold_low_time_interval;
        public byte rssi_sampling_period;
        public byte condition_type;
    }

    public static class Pattern {
        public byte ad_type;
        public byte start_byte;
        public byte[] pattern;

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Pattern other)) {
                return false;
            }

            return other.ad_type == this.ad_type
                    && other.start_byte == this.start_byte
                    && Arrays.equals(other.pattern, this.pattern);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ad_type, start_byte, Arrays.hashCode(pattern));
        }
    }

    public static class Uuid {
        public byte[] uuid;
    }

    public static class Address {
        byte addr_type;
        String bd_addr;
    }

    private final Monitor mMonitor = new Monitor();
    private final ArrayList<Pattern> mPatterns = new ArrayList<>();
    private final Uuid mUuid = new Uuid();
    private final Address mAddress = new Address();

    // Constructor that converts an APCF-friendly filter to an MSFT-friendly format
    MsftAdvMonitor(ScanFilter filter) {
        mMonitor.condition_type = MSFT_CONDITION_TYPE_INVALID;

        // Hardcoded values taken from CrOS defaults
        mMonitor.rssi_threshold_high = RSSI_THRESHOLD_HIGH;
        mMonitor.rssi_threshold_low = RSSI_THRESHOLD_LOW;
        mMonitor.rssi_threshold_low_time_interval = RSSI_THRESHOLD_LOW_TIME_INTERVAL;
        mMonitor.rssi_sampling_period = RSSI_SAMPLING_PERIOD;

        if (filter.getDeviceAddress() != null) {
            mMonitor.condition_type = MSFT_CONDITION_TYPE_ADDRESS;

            mAddress.addr_type = (byte) filter.getAddressType();
            mAddress.bd_addr = filter.getDeviceAddress();
            return;
        }

        if (filter.getServiceUuid() != null) {
            mMonitor.condition_type = MSFT_CONDITION_TYPE_UUID;

            mUuid.uuid =
                    BluetoothUuid.uuidToBytes(new ParcelUuid(filter.getServiceUuid().getUuid()));
            return;
        }

        if (filter.getServiceDataUuid() != null) {
            Pattern pattern = new Pattern();
            final ParcelUuid uuid = new ParcelUuid(filter.getServiceDataUuid().getUuid());
            final byte[] uuid_bytes = BluetoothUuid.uuidToBytes(uuid);

            if (BluetoothUuid.is16BitUuid(uuid)) {
                pattern.ad_type = (byte) ScanRecord.DATA_TYPE_SERVICE_DATA_16_BIT;
            } else if (BluetoothUuid.is32BitUuid(uuid)) {
                pattern.ad_type = (byte) ScanRecord.DATA_TYPE_SERVICE_DATA_32_BIT;
            } else { // if 128-bit UUID
                pattern.ad_type = (byte) ScanRecord.DATA_TYPE_SERVICE_DATA_128_BIT;
            }
            pattern.start_byte = FILTER_PATTERN_START_POSITION;

            byte[] data_bytes = filter.getServiceData();
            if (!dataIsEmpty(filter.getServiceDataMask())) {
                // MSFT does not support data masks
                Log.w(TAG, "MSFT: Ignoring data mask for filter: " + filter);
                pattern.pattern = uuid_bytes;
            } else if (dataIsEmpty(data_bytes)) {
                pattern.pattern = uuid_bytes;
            } else {
                pattern.pattern =
                        java.nio.ByteBuffer.allocate(uuid_bytes.length + data_bytes.length)
                                .put(uuid_bytes)
                                .put(data_bytes)
                                .array();
            }

            mPatterns.add(pattern);
        }

        if (filter.getAdvertisingData() != null
                && filter.getAdvertisingData().length != 0
                && dataIsEmpty(filter.getAdvertisingDataMask())) {
            Pattern pattern = new Pattern();
            pattern.ad_type = (byte) filter.getAdvertisingDataType();
            pattern.start_byte = FILTER_PATTERN_START_POSITION;
            pattern.pattern = filter.getAdvertisingData();

            mPatterns.add(pattern);
        }

        if (filter.getDeviceName() != null) {
            Pattern pattern = new Pattern();
            pattern.ad_type = (byte) 0x09; // Assigned Numbers Document, Section 2.3
            pattern.start_byte = FILTER_PATTERN_START_POSITION;
            pattern.pattern = filter.getDeviceName().getBytes();

            mPatterns.add(pattern);
        }

        if (mPatterns.size() > 0) {
            mMonitor.condition_type = MSFT_CONDITION_TYPE_PATTERNS;
        }
    }

    Monitor getMonitor() {
        return mMonitor;
    }

    Pattern[] getPatterns() {
        return mPatterns.toArray(new Pattern[mPatterns.size()]);
    }

    Uuid getUuid() {
        return mUuid;
    }

    Address getAddress() {
        return mAddress;
    }

    private static boolean dataIsEmpty(byte[] data) {
        if (data == null || data.length == 0) return true;
        if (data.length == 1 && data[0] == 0) return true;
        return false;
    }
}
