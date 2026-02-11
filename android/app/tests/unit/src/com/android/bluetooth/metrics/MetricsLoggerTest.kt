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

package com.android.bluetooth.metrics

import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothUuid
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.BluetoothMetricsProto
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__ASHA_DUAL
import com.android.bluetooth.BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__ASHA_ONLY
import com.android.bluetooth.BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__CLASSIC
import com.android.bluetooth.BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__LE_AUDIO_DUAL
import com.android.bluetooth.BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__LE_AUDIO_ONLY
import com.android.bluetooth.TestUtils
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.RemoteDevices
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.InvalidProtocolBufferException
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Test cases for [MetricsLogger]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class MetricsLoggerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var remoteDevices: RemoteDevices

    private val device = getTestDevice(0)

    private val SANITIZED_DEVICE_NAME_MAP =
        mapOf(
            "AirpoDspro" to "airpodspro",
            "AirpoDs-pro" to "airpodspro",
            "Someone's AirpoDs" to "airpods",
            "Galaxy Buds pro" to "galaxybudspro",
            "Someone's AirpoDs" to "airpods",
            "My BMW X5" to "bmwx5",
            "Jane Doe's Tesla Model--X" to "teslamodelx",
            "TESLA of Jane DOE" to "tesla",
            "SONY WH-1000XM4" to "sonywh1000xm4",
            "Amazon Echo Dot" to "amazonechodot",
            "Chevy my link" to "chevymylink",
            "Dad's Hyundai i10" to "hyundai",
            "Mike's new Galaxy Buds 2" to "galaxybuds2",
            "My third Ford F-150" to "fordf150",
            "Bose QuietComfort 35 Series 2" to "bosequietcomfort35",
            "Fitbit versa 3 band" to "fitbitversa3",
            "my vw bt" to "myvw",
            "SomeDevice1" to "",
            "My traverse" to "traverse",
            "My Xbox wireless" to "xboxwireless",
            "Your buds3 lite NC" to "buds3lite",
            "MC's razer" to "razer",
            "Tim's Google Pixel Watch" to "googlepixelwatch",
            "lexus is connected" to "lexusis",
            "My wireless flash x earbuds" to "wirelessflashx",
        )

    private lateinit var testableMetricsLogger: TestableMetricsLogger

    private class TestableMetricsLogger : MetricsLogger() {
        val testableCounters = mutableMapOf<Int, Long>()
        val testableDeviceNames = mutableMapOf<String, Int>()

        override fun count(key: Int, count: Long): Boolean {
            testableCounters[key] = count
            return true
        }

        override fun scheduleDrains() {}

        override fun cancelPendingDrain() {}

        override fun statslogBluetoothDeviceNames(metricId: Int, matchedString: String) {
            testableDeviceNames.merge(matchedString, 1, Int::plus)
        }
    }

    @Before
    fun setUp() {
        testableMetricsLogger = TestableMetricsLogger()
        testableMetricsLogger.init(adapterService, remoteDevices)
    }

    @After
    fun tearDown() {
        testableMetricsLogger.close()
    }

    /** Test add counters and send them to statsd */
    @Test
    fun testAddAndSendCountersNormalCases() {
        testableMetricsLogger.cacheCount(1, 10)
        testableMetricsLogger.cacheCount(1, 10)
        testableMetricsLogger.cacheCount(2, 5)
        testableMetricsLogger.drainBufferedCounters()

        assertThat(testableMetricsLogger.testableCounters[1]).isEqualTo(20L)
        assertThat(testableMetricsLogger.testableCounters[2]).isEqualTo(5L)

        testableMetricsLogger.cacheCount(1, 3)
        testableMetricsLogger.cacheCount(2, 5)
        testableMetricsLogger.cacheCount(2, 5)
        testableMetricsLogger.cacheCount(3, 1)
        testableMetricsLogger.drainBufferedCounters()
        assertThat(testableMetricsLogger.testableCounters[1]).isEqualTo(3L)
        assertThat(testableMetricsLogger.testableCounters[2]).isEqualTo(10L)
        assertThat(testableMetricsLogger.testableCounters[3]).isEqualTo(1L)
    }

    @Test
    fun testAddAndSendCountersCornerCases() {
        assertThat(testableMetricsLogger.isInitialized).isTrue()
        testableMetricsLogger.cacheCount(1, -1)
        testableMetricsLogger.cacheCount(3, 0)
        testableMetricsLogger.cacheCount(2, 10)
        testableMetricsLogger.cacheCount(2, Long.MAX_VALUE - 8L)
        testableMetricsLogger.drainBufferedCounters()

        assertThat(testableMetricsLogger.testableCounters).doesNotContainKey(1)
        assertThat(testableMetricsLogger.testableCounters).doesNotContainKey(3)
        assertThat(testableMetricsLogger.testableCounters[2]).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun testMetricsLoggerClose() {
        testableMetricsLogger.cacheCount(1, 1)
        testableMetricsLogger.cacheCount(2, 10)
        testableMetricsLogger.cacheCount(2, Long.MAX_VALUE)
        testableMetricsLogger.close()

        assertThat(testableMetricsLogger.testableCounters[1]).isEqualTo(1)
        assertThat(testableMetricsLogger.testableCounters[2]).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun testMetricsLoggerNotInit() {
        testableMetricsLogger.close()
        assertThat(testableMetricsLogger.cacheCount(1, 1)).isFalse()
        testableMetricsLogger.drainBufferedCounters()
        assertThat(testableMetricsLogger.testableCounters).doesNotContainKey(1)
    }

    @Test
    fun testAddAndSendCountersDoubleInit() {
        assertThat(testableMetricsLogger.isInitialized).isTrue()
        // sending a null adapterService will crash in case the double init no longer works
        testableMetricsLogger.init(null, remoteDevices)
    }

    @Test
    fun testDeviceNameToSha() {
        initTestingBloomfilter()
        for (entry in SANITIZED_DEVICE_NAME_MAP.entries) {
            val deviceName = entry.key
            val sha256 = MetricsLogger.getSha256String(entry.value)
            assertThat(testableMetricsLogger.logAllowlistedDeviceNameHash(1, deviceName))
                .isEqualTo(sha256)
        }
    }

    @Test
    fun testOuiFromBluetoothDevice() {
        val bluetoothDevice = TestUtils.getTestDevice(0)

        val remoteDeviceInformationBytes =
            testableMetricsLogger.getRemoteDeviceInfoProto(bluetoothDevice)

        try {
            val bluetoothRemoteDeviceInformation =
                BluetoothMetricsProto.BluetoothRemoteDeviceInformation.parseFrom(
                    remoteDeviceInformationBytes
                )
            val oui = (0 shl 16) or (1 shl 8) or 2 // OUI from the above mac address
            assertThat(bluetoothRemoteDeviceInformation.oui).isEqualTo(oui)
        } catch (e: InvalidProtocolBufferException) {
            assertThat(e.message).isNull() // test failure here
        }
    }

    @Test
    fun testGetAllowlistedDeviceNameHashForMedicalDevice() {
        val deviceName = "Sam's rphonak hearing aid"
        val expectMedicalDeviceSha256 = MetricsLogger.getSha256String("rphonakhearingaid")

        val actualMedicalDeviceSha256 =
            testableMetricsLogger.getAllowlistedDeviceNameHash(deviceName, true)

        assertThat(actualMedicalDeviceSha256).isEqualTo(expectMedicalDeviceSha256)
    }

    @Test
    fun testGetAllowlistedDeviceNameHashForMedicalDeviceIdentifiedLogging() {
        val deviceName = "Sam's rphonak hearing aid"
        val expectMedicalDeviceSha256 = ""

        val actualMedicalDeviceSha256 =
            testableMetricsLogger.getAllowlistedDeviceNameHash(deviceName, false)

        assertThat(actualMedicalDeviceSha256).isEqualTo(expectMedicalDeviceSha256)
    }

    @Test
    fun uploadEmptyDeviceName() {
        initTestingBloomfilter()
        assertThat(testableMetricsLogger.logAllowlistedDeviceNameHash(1, "")).isEmpty()
    }

    @Test
    fun testUpdateHearingDeviceActiveTime() {
        val day = BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__TIME_PERIOD__DAY
        val week = BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__TIME_PERIOD__WEEK
        val month = BluetoothStatsLog.HEARING_DEVICE_ACTIVE_EVENT_REPORTED__TIME_PERIOD__MONTH
        doReturn(InstrumentationRegistry.getInstrumentation().context.contentResolver)
            .whenever(adapterService)
            .contentResolver

        // last active time is 2 days ago, should update last active day
        val logger = spy(testableMetricsLogger)
        prepareLastActiveTimeDaysAgo(2)
        logger.updateHearingDeviceActiveTime(device, 1)
        verify(logger).logHearingDeviceActiveEvent(any(), any<Int>(), eq(day))
        verify(logger, never()).logHearingDeviceActiveEvent(any(), any<Int>(), eq(week))
        verify(logger, never()).logHearingDeviceActiveEvent(any(), any<Int>(), eq(month))

        // last active time is 8 days ago, should update last active day and week
        Mockito.reset(logger)
        prepareLastActiveTimeDaysAgo(8)
        logger.updateHearingDeviceActiveTime(device, 1)
        verify(logger).logHearingDeviceActiveEvent(any(), any<Int>(), eq(day))
        verify(logger).logHearingDeviceActiveEvent(any(), any<Int>(), eq(week))
        verify(logger, never()).logHearingDeviceActiveEvent(any(), any<Int>(), eq(month))

        // last active time is 60 days ago, should update last active day, week and month
        Mockito.reset(logger)
        prepareLastActiveTimeDaysAgo(60)
        logger.updateHearingDeviceActiveTime(device, 1)
        verify(logger).logHearingDeviceActiveEvent(any(), any<Int>(), eq(day))
        verify(logger).logHearingDeviceActiveEvent(any(), any<Int>(), eq(week))
        verify(logger).logHearingDeviceActiveEvent(any(), any<Int>(), eq(month))
    }

    @Test
    fun logDeviceConnectionStateChanges_connecting_logsDeviceName() {
        val logger = spy(testableMetricsLogger)
        doReturn("").whenever(logger).logAllowlistedDeviceNameHash(any<Int>(), any())

        val metricId = 1234
        val deviceName = "Test Device"
        doReturn(metricId).whenever(adapterService).getMetricId(device)
        doReturn(deviceName).whenever(remoteDevices).getName(device)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.A2DP, STATE_CONNECTING)

        verify(logger).logAllowlistedDeviceNameHash(metricId, deviceName)
    }

    @Test
    fun logDeviceConnectionStateChanges_notConnected_doesNotLogHearingDeviceActiveTime() {
        val logger = spy(testableMetricsLogger)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.A2DP, STATE_DISCONNECTED)

        verify(logger, never()).updateHearingDeviceActiveTime(any(), any<Int>())
    }

    @Test
    fun logDeviceConnectionStateChanges_a2dpConnected_medicalDevice_logsClassic() {
        initTestingMedicalBloomfilter()
        val logger = spy(testableMetricsLogger)
        doNothing().whenever(logger).updateHearingDeviceActiveTime(any(), any<Int>())

        // "rphonak" is in the default medical device bloom filter
        doReturn("rphonak hearing aid").whenever(adapterService).getRemoteName(device)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.A2DP, STATE_CONNECTED)

        verify(logger)
            .updateHearingDeviceActiveTime(
                eq(device),
                eq(HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__CLASSIC),
            )
    }

    @Test
    fun logDeviceConnectionStateChanges_a2dpConnected_notMedicalDevice_doesNotLog() {
        initTestingMedicalBloomfilter()
        val logger = spy(testableMetricsLogger)

        doReturn("not a medical device").whenever(adapterService).getRemoteName(device)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.A2DP, STATE_CONNECTED)

        verify(logger, never()).updateHearingDeviceActiveTime(any(), any<Int>())
    }

    @Test
    fun logDeviceConnectionStateChanges_headsetConnected_medicalDevice_logsClassic() {
        initTestingMedicalBloomfilter()
        val logger = spy(testableMetricsLogger)
        doNothing().whenever(logger).updateHearingDeviceActiveTime(any(), any<Int>())

        // "rphonak" is in the default medical device bloom filter
        doReturn("rphonak hearing aid").whenever(adapterService).getRemoteName(device)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.HEADSET, STATE_CONNECTED)

        verify(logger)
            .updateHearingDeviceActiveTime(
                eq(device),
                eq(HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__CLASSIC),
            )
    }

    @Test
    fun logDeviceConnectionStateChanges_hearingAidConnected_dualMode_logsAshaDual() {
        val logger = spy(testableMetricsLogger)
        doNothing().whenever(logger).updateHearingDeviceActiveTime(any(), any<Int>())

        doReturn(arrayOf(BluetoothUuid.HEARING_AID, BluetoothUuid.LE_AUDIO))
            .whenever(remoteDevices)
            .getUuids(device)

        logger.logDeviceConnectionStateChanges(
            device,
            BluetoothProfile.HEARING_AID,
            STATE_CONNECTED,
        )

        verify(logger)
            .updateHearingDeviceActiveTime(
                eq(device),
                eq(HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__ASHA_DUAL),
            )
    }

    @Test
    fun logDeviceConnectionStateChanges_hearingAidConnected_singleMode_logsAshaOnly() {
        val logger = spy(testableMetricsLogger)
        doNothing().whenever(logger).updateHearingDeviceActiveTime(any(), any<Int>())

        doReturn(arrayOf(BluetoothUuid.HEARING_AID)).whenever(remoteDevices).getUuids(device)

        logger.logDeviceConnectionStateChanges(
            device,
            BluetoothProfile.HEARING_AID,
            STATE_CONNECTED,
        )

        verify(logger)
            .updateHearingDeviceActiveTime(
                eq(device),
                eq(HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__ASHA_ONLY),
            )
    }

    @Test
    fun logDeviceConnectionStateChanges_hapClientConnected_dualMode_logsLeAudioDual() {
        val logger = spy(testableMetricsLogger)
        doNothing().whenever(logger).updateHearingDeviceActiveTime(any(), any<Int>())

        doReturn(arrayOf(BluetoothUuid.HEARING_AID, BluetoothUuid.LE_AUDIO))
            .whenever(remoteDevices)
            .getUuids(device)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.HAP_CLIENT, STATE_CONNECTED)

        verify(logger)
            .updateHearingDeviceActiveTime(
                eq(device),
                eq(HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__LE_AUDIO_DUAL),
            )
    }

    @Test
    fun logDeviceConnectionStateChanges_hapClientConnected_singleMode_logsLeAudioOnly() {
        val logger = spy(testableMetricsLogger)
        doNothing().whenever(logger).updateHearingDeviceActiveTime(any(), any<Int>())

        doReturn(arrayOf(BluetoothUuid.LE_AUDIO)).whenever(remoteDevices).getUuids(device)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.HAP_CLIENT, STATE_CONNECTED)

        verify(logger)
            .updateHearingDeviceActiveTime(
                eq(device),
                eq(HEARING_DEVICE_ACTIVE_EVENT_REPORTED__DEVICE_TYPE__LE_AUDIO_ONLY),
            )
    }

    @Test
    fun logDeviceConnectionStateChanges_otherProfileConnected_doesNotLog() {
        val logger = spy(testableMetricsLogger)

        logger.logDeviceConnectionStateChanges(device, BluetoothProfile.PAN, STATE_CONNECTED)

        verify(logger, never()).updateHearingDeviceActiveTime(any(), any<Int>())
    }

    private fun prepareLastActiveTimeDaysAgo(days: Int) {
        val contentResolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val lastActive = now.minusDays(days.toLong()).toString()
        Settings.Secure.putString(contentResolver, "last_active_day", lastActive)
        Settings.Secure.putString(contentResolver, "last_active_week", lastActive)
        Settings.Secure.putString(contentResolver, "last_active_month", lastActive)
    }

    private fun initTestingBloomfilter() {
        val bloomfilterData =
            DeviceBloomfilterGenerator.hexStringToByteArray(
                DeviceBloomfilterGenerator.BLOOM_FILTER_DEFAULT
            )
        testableMetricsLogger.setBloomfilter(
            BloomFilter.readFrom(ByteArrayInputStream(bloomfilterData), Funnels.byteArrayFunnel())
        )
    }

    private fun initTestingMedicalBloomfilter() {
        val bloomfilterData =
            MedicalDeviceBloomfilterGenerator.hexStringToByteArray(
                MedicalDeviceBloomfilterGenerator.BLOOM_FILTER_DEFAULT
            )
        testableMetricsLogger.setMedicalDeviceBloomfilter(
            BloomFilter.readFrom(ByteArrayInputStream(bloomfilterData), Funnels.byteArrayFunnel())
        )
        testableMetricsLogger.mMedicalDeviceBloomFilterInitialized = true
    }
}
