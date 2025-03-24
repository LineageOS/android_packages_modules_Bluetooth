/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMetricsProto.BluetoothRemoteDeviceInformation;
import com.android.bluetooth.BluetoothStatsLog;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** Test cases for {@link MetricsLogger}. */
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
        mTestableMetricsLogger = new TestableMetricsLogger();
        mTestableMetricsLogger.init(mAdapterService, mRemoteDevices);
    }

    @After
    public void tearDown() {
        mTestableMetricsLogger.close();
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

    @Test
    public void testUpdateHearingDeviceActiveTime() {
        BluetoothDevice bluetoothDevice = getTestDevice(0);
        int day = BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__TIME_PERIOD__DAY;
        int week = BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__TIME_PERIOD__WEEK;
        int month = BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__TIME_PERIOD__MONTH;
        doReturn(ApplicationProvider.getApplicationContext().getContentResolver())
                .when(mAdapterService)
                .getContentResolver();

        // last active time is 2 days ago, should update last active day
        TestableMetricsLogger logger = spy(mTestableMetricsLogger);
        prepareLastActiveTimeDaysAgo(2);
        logger.updateHearingDeviceActiveTime(bluetoothDevice, 1);
        verify(logger).logHearingDeviceActiveEvent(any(), anyInt(), eq(day));
        verify(logger, never()).logHearingDeviceActiveEvent(any(), anyInt(), eq(week));
        verify(logger, never()).logHearingDeviceActiveEvent(any(), anyInt(), eq(month));

        // last active time is 8 days ago, should update last active day and week
        Mockito.reset(logger);
        prepareLastActiveTimeDaysAgo(8);
        logger.updateHearingDeviceActiveTime(bluetoothDevice, 1);
        verify(logger).logHearingDeviceActiveEvent(any(), anyInt(), eq(day));
        verify(logger).logHearingDeviceActiveEvent(any(), anyInt(), eq(week));
        verify(logger, never()).logHearingDeviceActiveEvent(any(), anyInt(), eq(month));

        // last active time is 60 days ago, should update last active day, week and month
        Mockito.reset(logger);
        prepareLastActiveTimeDaysAgo(60);
        logger.updateHearingDeviceActiveTime(bluetoothDevice, 1);
        verify(logger).logHearingDeviceActiveEvent(any(), anyInt(), eq(day));
        verify(logger).logHearingDeviceActiveEvent(any(), anyInt(), eq(week));
        verify(logger).logHearingDeviceActiveEvent(any(), anyInt(), eq(month));
    }

    private static void prepareLastActiveTimeDaysAgo(int days) {
        final ContentResolver contentResolver =
                ApplicationProvider.getApplicationContext().getContentResolver();
        final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        final String lastActive = now.minusDays(days).toString();
        Settings.Secure.putString(contentResolver, "last_active_day", lastActive);
        Settings.Secure.putString(contentResolver, "last_active_week", lastActive);
        Settings.Secure.putString(contentResolver, "last_active_month", lastActive);
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
