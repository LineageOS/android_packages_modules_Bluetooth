/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.bluetooth.btservice;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothDevice;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMetricsProto.BluetoothLog;
import com.android.bluetooth.BluetoothMetricsProto.BluetoothRemoteDeviceInformation;
import com.android.bluetooth.BluetoothMetricsProto.ProfileConnectionStats;
import com.android.bluetooth.BluetoothMetricsProto.ProfileId;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link MetricsLogger} */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class MetricsLoggerTest {
    private static final HashMap<String, String> SANITIZED_DEVICE_NAME_MAP = new HashMap<>();

    static {
        SANITIZED_DEVICE_NAME_MAP.put("AirpoDspro", "airpodspro");
        SANITIZED_DEVICE_NAME_MAP.put("AirpoDs-pro", "airpodspro");
        SANITIZED_DEVICE_NAME_MAP.put("Someone's AirpoDs", "airpods");
        SANITIZED_DEVICE_NAME_MAP.put("Galaxy Buds pro", "galaxybudspro");
        SANITIZED_DEVICE_NAME_MAP.put("Someone's AirpoDs", "airpods");
        SANITIZED_DEVICE_NAME_MAP.put("My BMW X5", "bmwx5");
        SANITIZED_DEVICE_NAME_MAP.put("Jane Doe's Tesla Model--X", "teslamodelx");
        SANITIZED_DEVICE_NAME_MAP.put("TESLA of Jane DOE", "tesla");
        SANITIZED_DEVICE_NAME_MAP.put("SONY WH-1000XM4", "sonywh1000xm4");
        SANITIZED_DEVICE_NAME_MAP.put("Amazon Echo Dot", "amazonechodot");
        SANITIZED_DEVICE_NAME_MAP.put("Chevy my link", "chevymylink");
        SANITIZED_DEVICE_NAME_MAP.put("Dad's Hyundai i10", "hyundai");
        SANITIZED_DEVICE_NAME_MAP.put("Mike's new Galaxy Buds 2", "galaxybuds2");
        SANITIZED_DEVICE_NAME_MAP.put("My third Ford F-150", "fordf150");
        SANITIZED_DEVICE_NAME_MAP.put("Bose QuietComfort 35 Series 2", "bosequietcomfort35");
        SANITIZED_DEVICE_NAME_MAP.put("Fitbit versa 3 band", "fitbitversa3");
        SANITIZED_DEVICE_NAME_MAP.put("my vw bt", "myvw");
        SANITIZED_DEVICE_NAME_MAP.put("SomeDevice1", "");
        SANITIZED_DEVICE_NAME_MAP.put("My traverse", "traverse");
        SANITIZED_DEVICE_NAME_MAP.put("My Xbox wireless", "xboxwireless");
        SANITIZED_DEVICE_NAME_MAP.put("Your buds3 lite NC", "buds3lite");
        SANITIZED_DEVICE_NAME_MAP.put("MC's razer", "razer");
        SANITIZED_DEVICE_NAME_MAP.put("Tim's Google Pixel Watch", "googlepixelwatch");
        SANITIZED_DEVICE_NAME_MAP.put("lexus is connected", "lexusis");
        SANITIZED_DEVICE_NAME_MAP.put("My wireless flash x earbuds", "wirelessflashx");
    }

    private TestableMetricsLogger mTestableMetricsLogger;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private RemoteDevices mRemoteDevices;

    private static class TestableMetricsLogger extends MetricsLogger {
        public final HashMap<Integer, Long> mTestableCounters = new HashMap<>();
        public final HashMap<String, Integer> mTestableDeviceNames = new HashMap<>();

        @Override
        public boolean count(int key, long count) {
            mTestableCounters.put(key, count);
            return true;
        }

        @Override
        protected void scheduleDrains() {}

        @Override
        protected void cancelPendingDrain() {}

        @Override
        protected void statslogBluetoothDeviceNames(int metricId, String matchedString) {
            mTestableDeviceNames.merge(matchedString, 1, Integer::sum);
        }
    }

    @Before
    public void setUp() {
        MetricsLogger.dumpProto(BluetoothLog.newBuilder());
        mTestableMetricsLogger = new TestableMetricsLogger();
        mTestableMetricsLogger.init(mAdapterService, mRemoteDevices);
    }

    @After
    public void tearDown() {
        // Dump metrics to clean up internal states
        MetricsLogger.dumpProto(BluetoothLog.newBuilder());
        mTestableMetricsLogger.close();
    }

    /** Simple test to verify that profile connection event can be logged, dumped, and cleaned */
    @Test
    public void testLogProfileConnectionEvent() {
        MetricsLogger.logProfileConnectionEvent(ProfileId.AVRCP);
        BluetoothLog.Builder metricsBuilder = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilder);
        BluetoothLog metricsProto = metricsBuilder.build();
        assertThat(metricsProto.getProfileConnectionStatsCount()).isEqualTo(1);
        ProfileConnectionStats profileUsageStatsAvrcp = metricsProto.getProfileConnectionStats(0);
        assertThat(profileUsageStatsAvrcp.getProfileId()).isEqualTo(ProfileId.AVRCP);
        assertThat(profileUsageStatsAvrcp.getNumTimesConnected()).isEqualTo(1);
        // Verify that MetricsLogger's internal state is cleared after a dump
        BluetoothLog.Builder metricsBuilderAfterDump = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilderAfterDump);
        BluetoothLog metricsProtoAfterDump = metricsBuilderAfterDump.build();
        assertThat(metricsProtoAfterDump.getProfileConnectionStatsCount()).isEqualTo(0);
    }

    /** Test whether multiple profile's connection events can be logged interleaving */
    @Test
    public void testLogProfileConnectionEventMultipleProfile() {
        MetricsLogger.logProfileConnectionEvent(ProfileId.AVRCP);
        MetricsLogger.logProfileConnectionEvent(ProfileId.HEADSET);
        MetricsLogger.logProfileConnectionEvent(ProfileId.AVRCP);
        BluetoothLog.Builder metricsBuilder = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilder);
        BluetoothLog metricsProto = metricsBuilder.build();
        assertThat(metricsProto.getProfileConnectionStatsCount()).isEqualTo(2);
        Map<ProfileId, ProfileConnectionStats> profileConnectionCountMap =
                getProfileUsageStatsMap(metricsProto.getProfileConnectionStatsList());
        assertThat(profileConnectionCountMap).containsKey(ProfileId.AVRCP);
        assertThat(profileConnectionCountMap.get(ProfileId.AVRCP).getNumTimesConnected())
                .isEqualTo(2);
        assertThat(profileConnectionCountMap).containsKey(ProfileId.HEADSET);
        assertThat(profileConnectionCountMap.get(ProfileId.HEADSET).getNumTimesConnected())
                .isEqualTo(1);
        // Verify that MetricsLogger's internal state is cleared after a dump
        BluetoothLog.Builder metricsBuilderAfterDump = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilderAfterDump);
        BluetoothLog metricsProtoAfterDump = metricsBuilderAfterDump.build();
        assertThat(metricsProtoAfterDump.getProfileConnectionStatsCount()).isEqualTo(0);
    }

    private static Map<ProfileId, ProfileConnectionStats> getProfileUsageStatsMap(
            List<ProfileConnectionStats> profileUsageStats) {
        HashMap<ProfileId, ProfileConnectionStats> profileUsageStatsMap = new HashMap<>();
        profileUsageStats.forEach(item -> profileUsageStatsMap.put(item.getProfileId(), item));
        return profileUsageStatsMap;
    }

    /** Test add counters and send them to statsd */
    @Test
    public void testAddAndSendCountersNormalCases() {
        mTestableMetricsLogger.cacheCount(1, 10);
        mTestableMetricsLogger.cacheCount(1, 10);
        mTestableMetricsLogger.cacheCount(2, 5);
        mTestableMetricsLogger.drainBufferedCounters();

        assertThat(mTestableMetricsLogger.mTestableCounters.get(1).longValue()).isEqualTo(20L);
        assertThat(mTestableMetricsLogger.mTestableCounters.get(2).longValue()).isEqualTo(5L);

        mTestableMetricsLogger.cacheCount(1, 3);
        mTestableMetricsLogger.cacheCount(2, 5);
        mTestableMetricsLogger.cacheCount(2, 5);
        mTestableMetricsLogger.cacheCount(3, 1);
        mTestableMetricsLogger.drainBufferedCounters();
        assertThat(mTestableMetricsLogger.mTestableCounters.get(1).longValue()).isEqualTo(3L);
        assertThat(mTestableMetricsLogger.mTestableCounters.get(2).longValue()).isEqualTo(10L);
        assertThat(mTestableMetricsLogger.mTestableCounters.get(3).longValue()).isEqualTo(1L);
    }

    @Test
    public void testAddAndSendCountersCornerCases() {
        assertThat(mTestableMetricsLogger.isInitialized()).isTrue();
        mTestableMetricsLogger.cacheCount(1, -1);
        mTestableMetricsLogger.cacheCount(3, 0);
        mTestableMetricsLogger.cacheCount(2, 10);
        mTestableMetricsLogger.cacheCount(2, Long.MAX_VALUE - 8L);
        mTestableMetricsLogger.drainBufferedCounters();

        assertThat(mTestableMetricsLogger.mTestableCounters).doesNotContainKey(1);
        assertThat(mTestableMetricsLogger.mTestableCounters).doesNotContainKey(3);
        assertThat(mTestableMetricsLogger.mTestableCounters.get(2).longValue())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    public void testMetricsLoggerClose() {
        mTestableMetricsLogger.cacheCount(1, 1);
        mTestableMetricsLogger.cacheCount(2, 10);
        mTestableMetricsLogger.cacheCount(2, Long.MAX_VALUE);
        mTestableMetricsLogger.close();

        assertThat(mTestableMetricsLogger.mTestableCounters.get(1).longValue()).isEqualTo(1);
        assertThat(mTestableMetricsLogger.mTestableCounters.get(2).longValue())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    public void testMetricsLoggerNotInit() {
        mTestableMetricsLogger.close();
        assertThat(mTestableMetricsLogger.cacheCount(1, 1)).isFalse();
        mTestableMetricsLogger.drainBufferedCounters();
        assertThat(mTestableMetricsLogger.mTestableCounters).doesNotContainKey(1);
    }

    @Test
    public void testAddAndSendCountersDoubleInit() {
        assertThat(mTestableMetricsLogger.isInitialized()).isTrue();
        // sending a null adapterService will crash in case the double init no longer works
        mTestableMetricsLogger.init(null, mRemoteDevices);
    }

    @Test
    public void testDeviceNameToSha() throws IOException {
        initTestingBloomfilter();
        for (Map.Entry<String, String> entry : SANITIZED_DEVICE_NAME_MAP.entrySet()) {
            String deviceName = entry.getKey();
            String sha256 = MetricsLogger.getSha256String(entry.getValue());
            assertThat(mTestableMetricsLogger.logAllowlistedDeviceNameHash(1, deviceName))
                    .isEqualTo(sha256);
        }
    }

    @Test
    public void testOuiFromBluetoothDevice() {
        BluetoothDevice bluetoothDevice = getTestDevice(0);

        byte[] remoteDeviceInformationBytes =
                mTestableMetricsLogger.getRemoteDeviceInfoProto(bluetoothDevice);

        try {
            BluetoothRemoteDeviceInformation bluetoothRemoteDeviceInformation =
                    BluetoothRemoteDeviceInformation.parseFrom(remoteDeviceInformationBytes);
            int oui = (0 << 16) | (1 << 8) | 2; // OUI from the above mac address
            assertThat(bluetoothRemoteDeviceInformation.getOui()).isEqualTo(oui);

        } catch (InvalidProtocolBufferException e) {
            assertThat(e.getMessage()).isNull(); // test failure here
        }
    }

    @Test
    public void testGetAllowlistedDeviceNameHashForMedicalDevice() {
        String deviceName = "Sam's rphonak hearing aid";
        String expectMedicalDeviceSha256 = MetricsLogger.getSha256String("rphonakhearingaid");

        String actualMedicalDeviceSha256 =
                mTestableMetricsLogger.getAllowlistedDeviceNameHash(deviceName, true);

        assertThat(actualMedicalDeviceSha256).isEqualTo(expectMedicalDeviceSha256);
    }

    @Test
    public void testGetAllowlistedDeviceNameHashForMedicalDeviceIdentifiedLogging() {
        String deviceName = "Sam's rphonak hearing aid";
        String expectMedicalDeviceSha256 = "";

        String actualMedicalDeviceSha256 =
                mTestableMetricsLogger.getAllowlistedDeviceNameHash(deviceName, false);

        assertThat(actualMedicalDeviceSha256).isEqualTo(expectMedicalDeviceSha256);
    }

    @Test
    public void uploadEmptyDeviceName() throws IOException {
        initTestingBloomfilter();
        assertThat(mTestableMetricsLogger.logAllowlistedDeviceNameHash(1, "")).isEmpty();
    }

    private void initTestingBloomfilter() throws IOException {
        byte[] bloomfilterData =
                DeviceBloomfilterGenerator.hexStringToByteArray(
                        DeviceBloomfilterGenerator.BLOOM_FILTER_DEFAULT);
        mTestableMetricsLogger.setBloomfilter(
                BloomFilter.readFrom(
                        new ByteArrayInputStream(bloomfilterData), Funnels.byteArrayFunnel()));
    }
}
