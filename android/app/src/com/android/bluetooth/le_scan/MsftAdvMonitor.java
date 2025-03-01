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

import android.bluetooth.le.ScanFilter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Helper class used to manage MSFT Advertisement Monitors. */
class MsftAdvMonitor {
    /* Only pattern and address filtering are supported currently */
    // private static final int MSFT_CONDITION_TYPE_ALL = 0x00;
    private static final int MSFT_CONDITION_TYPE_PATTERNS = 0x01;
    // private static final int MSFT_CONDITION_TYPE_UUID = 0x02;
    // private static final int MSFT_CONDITION_TYPE_IRK = 0x03;
    private static final int MSFT_CONDITION_TYPE_ADDRESS = 0x04;

    // Hardcoded values taken from CrOS defaults
    private static final byte RSSI_THRESHOLD_HIGH = (byte) 0xBF; // 191
    private static final byte RSSI_THRESHOLD_LOW = (byte) 0xB0; // 176
    private static final byte RSSI_THRESHOLD_LOW_TIME_INTERVAL = (byte) 0x28; // 40s
    private static final byte RSSI_SAMPLING_PERIOD = (byte) 0x05; // 500ms
    private static final int FILTER_PATTERN_START_POSITION = (byte) 0x00;

    static class Monitor {
        public byte rssi_threshold_high;
        public byte rssi_threshold_low;
        public byte rssi_threshold_low_time_interval;
        public byte rssi_sampling_period;
        public byte condition_type;
    }

    static class Pattern {
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

    static class Address {
        byte addr_type;
        String bd_addr;
    }

    private final Monitor mMonitor = new Monitor();
    private final ArrayList<Pattern> mPatterns = new ArrayList<>();
    private final Address mAddress = new Address();

    // Constructor that converts an APCF-friendly filter to an MSFT-friendly format
    MsftAdvMonitor(ScanFilter filter) {
        // Hardcoded values taken from CrOS defaults
        mMonitor.rssi_threshold_high = RSSI_THRESHOLD_HIGH;
        mMonitor.rssi_threshold_low = RSSI_THRESHOLD_LOW;
        mMonitor.rssi_threshold_low_time_interval = RSSI_THRESHOLD_LOW_TIME_INTERVAL;
        mMonitor.rssi_sampling_period = RSSI_SAMPLING_PERIOD;
        mMonitor.condition_type = MSFT_CONDITION_TYPE_PATTERNS;

        if (filter.getDeviceAddress() != null) {
            mMonitor.condition_type = MSFT_CONDITION_TYPE_ADDRESS;
            mAddress.addr_type = (byte) filter.getAddressType();
            mAddress.bd_addr = filter.getDeviceAddress();
            return;
        }

        if (filter.getServiceDataUuid() != null && dataMaskIsEmpty(filter.getServiceDataMask())) {
            Pattern pattern = new Pattern();
            pattern.ad_type = (byte) 0x16; // Bluetooth Core Spec Part A, Section 1
            pattern.start_byte = FILTER_PATTERN_START_POSITION;

            // Extract the 16-bit UUID (third and fourth bytes) from the 128-bit
            // UUID in reverse endianness
            UUID uuid = filter.getServiceDataUuid().getUuid();
            ByteBuffer bb = ByteBuffer.allocate(16); // 16 byte (128 bit) UUID
            bb.putLong(uuid.getMostSignificantBits());
            bb.putLong(uuid.getLeastSignificantBits());
            pattern.pattern = new byte[] {bb.get(3), bb.get(2)};

            mPatterns.add(pattern);
        } else if (filter.getAdvertisingData() != null
                && filter.getAdvertisingData().length != 0
                && dataMaskIsEmpty(filter.getAdvertisingDataMask())) {
            Pattern pattern = new Pattern();
            pattern.ad_type = (byte) filter.getAdvertisingDataType();
            pattern.start_byte = FILTER_PATTERN_START_POSITION;
            pattern.pattern = filter.getAdvertisingData();
            mPatterns.add(pattern);
        }
    }

    Monitor getMonitor() {
        return mMonitor;
    }

    Pattern[] getPatterns() {
        return mPatterns.toArray(new Pattern[mPatterns.size()]);
    }

    Address getAddress() {
        return mAddress;
    }

    private static boolean dataMaskIsEmpty(byte[] mask) {
        if (mask == null || mask.length == 0) return true;
        if (mask.length == 1 && mask[0] == 0) return true;
        return false;
    }
}
