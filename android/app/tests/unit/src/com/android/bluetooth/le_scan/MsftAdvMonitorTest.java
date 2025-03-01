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

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class MsftAdvMonitorTest {
    private static final String TAG = MsftAdvMonitorTest.class.getSimpleName();

    // Hardcoded values taken from CrOS defaults
    private static final byte RSSI_THRESHOLD_HIGH = (byte) 0xBF; // 191
    private static final byte RSSI_THRESHOLD_LOW = (byte) 0xB0; // 176
    private static final byte RSSI_THRESHOLD_LOW_TIME_INTERVAL = (byte) 0x28; // 40s
    private static final byte RSSI_SAMPLING_PERIOD = (byte) 0x05; // 500ms
    private static final byte CONDITION_TYPE = (byte) 0x01; // MSFT condition type - patterns
    private static final byte FILTER_PATTERN_START_POSITION = (byte) 0x00;

    // Retrieved from real Fastpair filter data
    private static final String FAST_PAIR_UUID = "0000fe2c-0000-1000-8000-00805f9b34fb";
    private static final ParcelUuid FAST_PAIR_SERVICE_DATA_UUID =
            ParcelUuid.fromString(FAST_PAIR_UUID);
    private static final byte[] FAST_PAIR_SERVICE_DATA =
            new byte[] {(byte) 0xfc, (byte) 0x12, (byte) 0x8e};

    private static void assertMonitorConstants(MsftAdvMonitor monitor) {
        MsftAdvMonitor.Monitor mMonitor = monitor.getMonitor();
        assertThat(mMonitor.rssi_threshold_high).isEqualTo(RSSI_THRESHOLD_HIGH);
        assertThat(mMonitor.rssi_threshold_low).isEqualTo(RSSI_THRESHOLD_LOW);
        assertThat(mMonitor.rssi_threshold_low_time_interval)
                .isEqualTo(RSSI_THRESHOLD_LOW_TIME_INTERVAL);
        assertThat(mMonitor.rssi_sampling_period).isEqualTo(RSSI_SAMPLING_PERIOD);
        assertThat(mMonitor.condition_type).isEqualTo(CONDITION_TYPE);
    }

    @Test
    public void testFastPairScanFilter() {
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceData(FAST_PAIR_SERVICE_DATA_UUID, FAST_PAIR_SERVICE_DATA)
                        .build();
        MsftAdvMonitor monitor = new MsftAdvMonitor(filter);

        assertMonitorConstants(monitor);
        assertThat(monitor.getPatterns()).hasLength(1);
        MsftAdvMonitor.Pattern mPattern = monitor.getPatterns()[0];
        assertThat(mPattern.ad_type)
                .isEqualTo((byte) 0x16); // Bluetooth Core Spec Part A, Section 1
        assertThat(mPattern.start_byte).isEqualTo(FILTER_PATTERN_START_POSITION);
        assertThat(mPattern.pattern).isEqualTo(new byte[] {(byte) 0x2c, (byte) 0xfe});
    }
}
