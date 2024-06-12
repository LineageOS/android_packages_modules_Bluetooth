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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMetricsProto.BluetoothLog;
import com.android.bluetooth.BluetoothMetricsProto.BluetoothRemoteDeviceInformation;
import com.android.bluetooth.BluetoothMetricsProto.ProfileConnectionStats;
import com.android.bluetooth.BluetoothMetricsProto.ProfileId;
import com.android.bluetooth.TestUtils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

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
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mMockAdapterService;

    private static class TestableMetricsLogger extends MetricsLogger {
        public HashMap<Integer, Long> mTestableCounters = new HashMap<>();
        public HashMap<String, Integer> mTestableDeviceNames = new HashMap<>();

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
        // Dump metrics to clean up internal states
        MetricsLogger.dumpProto(BluetoothLog.newBuilder());
        mTestableMetricsLogger = new TestableMetricsLogger();
        mTestableMetricsLogger.mBloomFilterInitialized = true;
        doReturn(null).when(mMockAdapterService).registerReceiver(any(), any());
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
        Assert.assertEquals(1, metricsProto.getProfileConnectionStatsCount());
        ProfileConnectionStats profileUsageStatsAvrcp = metricsProto.getProfileConnectionStats(0);
        Assert.assertEquals(ProfileId.AVRCP, profileUsageStatsAvrcp.getProfileId());
        Assert.assertEquals(1, profileUsageStatsAvrcp.getNumTimesConnected());
        // Verify that MetricsLogger's internal state is cleared after a dump
        BluetoothLog.Builder metricsBuilderAfterDump = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilderAfterDump);
        BluetoothLog metricsProtoAfterDump = metricsBuilderAfterDump.build();
        Assert.assertEquals(0, metricsProtoAfterDump.getProfileConnectionStatsCount());
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
        Assert.assertEquals(2, metricsProto.getProfileConnectionStatsCount());
        Map<ProfileId, ProfileConnectionStats> profileConnectionCountMap =
                getProfileUsageStatsMap(metricsProto.getProfileConnectionStatsList());
        Assert.assertTrue(profileConnectionCountMap.containsKey(ProfileId.AVRCP));
        Assert.assertEquals(
                2, profileConnectionCountMap.get(ProfileId.AVRCP).getNumTimesConnected());
        Assert.assertTrue(profileConnectionCountMap.containsKey(ProfileId.HEADSET));
        Assert.assertEquals(
                1, profileConnectionCountMap.get(ProfileId.HEADSET).getNumTimesConnected());
        // Verify that MetricsLogger's internal state is cleared after a dump
        BluetoothLog.Builder metricsBuilderAfterDump = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilderAfterDump);
        BluetoothLog metricsProtoAfterDump = metricsBuilderAfterDump.build();
        Assert.assertEquals(0, metricsProtoAfterDump.getProfileConnectionStatsCount());
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
        mTestableMetricsLogger.init(mMockAdapterService);
        mTestableMetricsLogger.cacheCount(1, 10);
        mTestableMetricsLogger.cacheCount(1, 10);
        mTestableMetricsLogger.cacheCount(2, 5);
        mTestableMetricsLogger.drainBufferedCounters();

        Assert.assertEquals(20L, mTestableMetricsLogger.mTestableCounters.get(1).longValue());
        Assert.assertEquals(5L, mTestableMetricsLogger.mTestableCounters.get(2).longValue());

        mTestableMetricsLogger.cacheCount(1, 3);
        mTestableMetricsLogger.cacheCount(2, 5);
        mTestableMetricsLogger.cacheCount(2, 5);
        mTestableMetricsLogger.cacheCount(3, 1);
        mTestableMetricsLogger.drainBufferedCounters();
        Assert.assertEquals(3L, mTestableMetricsLogger.mTestableCounters.get(1).longValue());
        Assert.assertEquals(10L, mTestableMetricsLogger.mTestableCounters.get(2).longValue());
        Assert.assertEquals(1L, mTestableMetricsLogger.mTestableCounters.get(3).longValue());
    }

    @Test
    public void testAddAndSendCountersCornerCases() {
        mTestableMetricsLogger.init(mMockAdapterService);
        Assert.assertTrue(mTestableMetricsLogger.isInitialized());
        mTestableMetricsLogger.cacheCount(1, -1);
        mTestableMetricsLogger.cacheCount(3, 0);
        mTestableMetricsLogger.cacheCount(2, 10);
        mTestableMetricsLogger.cacheCount(2, Long.MAX_VALUE - 8L);
        mTestableMetricsLogger.drainBufferedCounters();

        Assert.assertFalse(mTestableMetricsLogger.mTestableCounters.containsKey(1));
        Assert.assertFalse(mTestableMetricsLogger.mTestableCounters.containsKey(3));
        Assert.assertEquals(
                Long.MAX_VALUE, mTestableMetricsLogger.mTestableCounters.get(2).longValue());
    }

    @Test
    public void testMetricsLoggerClose() {
        mTestableMetricsLogger.init(mMockAdapterService);
        mTestableMetricsLogger.cacheCount(1, 1);
        mTestableMetricsLogger.cacheCount(2, 10);
        mTestableMetricsLogger.cacheCount(2, Long.MAX_VALUE);
        mTestableMetricsLogger.close();

        Assert.assertEquals(1, mTestableMetricsLogger.mTestableCounters.get(1).longValue());
        Assert.assertEquals(
                Long.MAX_VALUE, mTestableMetricsLogger.mTestableCounters.get(2).longValue());
    }

    @Test
    public void testMetricsLoggerNotInit() {
        Assert.assertFalse(mTestableMetricsLogger.cacheCount(1, 1));
        mTestableMetricsLogger.drainBufferedCounters();
        Assert.assertFalse(mTestableMetricsLogger.mTestableCounters.containsKey(1));
        Assert.assertFalse(mTestableMetricsLogger.close());
    }

    @Test
    public void testAddAndSendCountersDoubleInit() {
        Assert.assertTrue(mTestableMetricsLogger.init(mMockAdapterService));
        Assert.assertTrue(mTestableMetricsLogger.isInitialized());
        Assert.assertFalse(mTestableMetricsLogger.init(mMockAdapterService));
    }

    @Test
    public void testDeviceNameToSha() {
        initTestingBloomfilter();
        for (Map.Entry<String, String> entry : SANITIZED_DEVICE_NAME_MAP.entrySet()) {
            String deviceName = entry.getKey();
            String sha256 = MetricsLogger.getSha256String(entry.getValue());
            Assert.assertEquals(
                    deviceName,
                    sha256,
                    mTestableMetricsLogger.logAllowlistedDeviceNameHash(1, deviceName, true));
        }
    }

    @Test
    public void testOuiFromBluetoothDevice() {
        BluetoothDevice bluetoothDevice =
                TestUtils.getTestDevice(BluetoothAdapter.getDefaultAdapter(), 0);

        byte[] remoteDeviceInformationBytes =
                MetricsLogger.getInstance().getRemoteDeviceInfoProto(bluetoothDevice);

        try {
            BluetoothRemoteDeviceInformation bluetoothRemoteDeviceInformation =
                    BluetoothRemoteDeviceInformation.parseFrom(remoteDeviceInformationBytes);
            int oui = (0 << 16) | (1 << 8) | 2; // OUI from the above mac address
            Assert.assertEquals(bluetoothRemoteDeviceInformation.getOui(), oui);

        } catch (InvalidProtocolBufferException e) {
            Assert.assertNull(e.getMessage()); // test failure here
        }
    }

    @Test
    public void uploadEmptyDeviceName() {
        initTestingBloomfilter();
        Assert.assertEquals("", mTestableMetricsLogger.logAllowlistedDeviceNameHash(1, "", true));
    }

    private void initTestingBloomfilter() {
        byte[] bloomfilterData =
                DeviceBloomfilterGenerator.hexStringToByteArray(
                        DeviceBloomfilterGenerator.BLOOM_FILTER_DEFAULT);
        try {
            mTestableMetricsLogger.setBloomfilter(
                    BloomFilter.readFrom(
                            new ByteArrayInputStream(bloomfilterData), Funnels.byteArrayFunnel()));
        } catch (IOException e) {
            Assert.assertTrue(false);
        }
    }
}
