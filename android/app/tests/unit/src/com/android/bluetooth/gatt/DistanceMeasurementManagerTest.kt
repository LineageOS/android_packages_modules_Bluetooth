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

package com.android.bluetooth.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BondStatus
import android.bluetooth.EncryptionStatus
import android.bluetooth.le.ChannelSoundingParams
import android.bluetooth.le.DistanceMeasurementMethod
import android.bluetooth.le.DistanceMeasurementParams
import android.bluetooth.le.DistanceMeasurementResult
import android.bluetooth.le.IDistanceMeasurementCallback
import android.content.pm.PackageManager
import android.os.HandlerThread
import android.os.TestLooperManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.metrics.MetricsLogger
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.after
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [DistanceMeasurementManager]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class DistanceMeasurementManagerTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var nativeInterface: DistanceMeasurementNativeInterface
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var gattService: GattService
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var callback: IDistanceMeasurementCallback
    @Mock private lateinit var mockMetricsLogger: MetricsLogger

    private val device = getTestDevice(57)

    private lateinit var distanceMeasurementManager: DistanceMeasurementManager
    private lateinit var uuid: UUID
    private lateinit var handlerThread: HandlerThread
    private lateinit var testLooperManager: TestLooperManager

    @Before
    fun setUp() {
        adapterService.mockPackageManager(packageManager)
        doReturn(true).whenever(packageManager).hasSystemFeature(any())
        doReturn(true).whenever(adapterService).isLeChannelSoundingSupported
        val address = device.address
        doReturn(address).whenever(adapterService).getIdentityAddress(address)
        doReturn(true).whenever(adapterService).isConnected(any<BluetoothDevice>())
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())

        val bondStatus = mock<BondStatus>()
        doReturn(BluetoothDevice.PAIRING_ALGORITHM_SC).whenever(bondStatus).pairingAlgorithm
        doReturn(bondStatus)
            .whenever(adapterService)
            .getBondStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val encryptionStatus = mock<EncryptionStatus>()
        doReturn(BluetoothDevice.ENCRYPTION_ALGORITHM_AES).whenever(encryptionStatus).algorithm
        doReturn(16).whenever(encryptionStatus).keySize
        doReturn(encryptionStatus)
            .whenever(adapterService)
            .getEncryptionStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        handlerThread = HandlerThread("DistanceMeasurementManagerTest")
        handlerThread.start()
        val looper = handlerThread.looper
        testLooperManager =
            InstrumentationRegistry.getInstrumentation().acquireLooperManager(looper)

        MetricsLogger.setInstanceForTesting(mockMetricsLogger)

        distanceMeasurementManager =
            DistanceMeasurementManager(adapterService, gattService, nativeInterface, looper)
        val msg = testLooperManager.next()
        testLooperManager.execute(msg)
        uuid = UUID.randomUUID()
    }

    @After
    fun tearDown() {
        distanceMeasurementManager.cleanup()
        testLooperManager.release()
        handlerThread.quit()
        MetricsLogger.setInstanceForTesting(null)
        MetricsLogger.getInstance()
    }

    @Test
    fun testStartRssiTracker() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        verify(nativeInterface)
            .startDistanceMeasurement(
                APP_UID,
                device.address,
                RSSI_FREQUENCY_LOW,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
                ChannelSoundingParams.SIGHT_TYPE_UNKNOWN,
                ChannelSoundingParams.LOCATION_TYPE_UNKNOWN,
            )
    }

    @Test
    fun testStopRssiTracker() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        distanceMeasurementManager.stopDistanceMeasurement(
            uuid,
            device,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
            false,
        )
        verify(nativeInterface)
            .stopDistanceMeasurement(
                device.address,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
            )
    }

    @Test
    fun testHandleRssiStarted() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        verify(nativeInterface)
            .startDistanceMeasurement(
                APP_UID,
                device.address,
                RSSI_FREQUENCY_LOW,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
                ChannelSoundingParams.SIGHT_TYPE_UNKNOWN,
                ChannelSoundingParams.LOCATION_TYPE_UNKNOWN,
            )
        distanceMeasurementManager.onDistanceMeasurementStarted(
            device.address,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        verify(callback).onStarted(device)
    }

    @Test
    fun testHandleRssiStartFail() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        verify(nativeInterface)
            .startDistanceMeasurement(
                APP_UID,
                device.address,
                RSSI_FREQUENCY_LOW,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
                ChannelSoundingParams.SIGHT_TYPE_UNKNOWN,
                ChannelSoundingParams.LOCATION_TYPE_UNKNOWN,
            )
        distanceMeasurementManager.onDistanceMeasurementStopped(
            device.address,
            BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        verify(callback)
            .onStartFail(device, BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL)
    }

    @Test
    fun testCsStartFailForNoBondedBLE() {
        doReturn(BluetoothDevice.BOND_NONE).whenever(adapterService).getBondState(any())
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface, never())
            .startDistanceMeasurement(
                APP_UID,
                device.address,
                CS_FREQUENCY_LOW,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
                ChannelSoundingParams.SIGHT_TYPE_UNKNOWN,
                ChannelSoundingParams.LOCATION_TYPE_UNKNOWN,
            )
        verify(callback).onStartFail(device, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
    }

    @Test
    fun testCsStartSuccessForBondedBLE() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface)
            .startDistanceMeasurement(
                APP_UID,
                device.address,
                CS_FREQUENCY_LOW,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
                ChannelSoundingParams.SIGHT_TYPE_UNKNOWN,
                ChannelSoundingParams.LOCATION_TYPE_UNKNOWN,
            )

        distanceMeasurementManager.onDistanceMeasurementStarted(
            device.address,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
        )
        distanceMeasurementManager.onDistanceMeasurementResult(
            device.address,
            100,
            0,
            100,
            0,
            45,
            0,
            10000L,
            DistanceMeasurementResult.INVALID_TX_POWER_DBM,
            -20,
            1,
            /* delaySpreadMeters = */ 10.0,
            /* detectedAttackLevel= */ DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE,
            /* velocityMetersPerSecond= */ 1.0,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
        )
        val captor = argumentCaptor<DistanceMeasurementResult>()
        verify(callback).onResult(eq(device), captor.capture())
        val result = captor.firstValue
        assertThat(result.resultMeters).isEqualTo(1.00)
        assertThat(result.azimuthAngle).isEqualTo(100)
        assertThat(result.altitudeAngle).isEqualTo(45)
        assertThat(result.measurementTimestampNanos).isEqualTo(10000)
        if (Flags.includePowerAndRssiInDistanceMeasurementResult()) {
            assertThat(result.remoteTxPowerDbm)
                .isEqualTo(DistanceMeasurementResult.INVALID_TX_POWER_DBM)
            assertThat(result.rssiDbm).isEqualTo(-20)
        }
        assertThat(result.confidenceLevel).isEqualTo(0.01)
        assertThat(result.delaySpreadMeters).isEqualTo(10.0)
        assertThat(result.detectedAttackLevel)
            .isEqualTo(DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE)
        assertThat(result.velocityMetersPerSecond).isEqualTo(1.0)
        distanceMeasurementManager.onDistanceMeasurementStopped(
            device.address,
            BluetoothStatusCodes.REASON_REMOTE_REQUEST,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
        )
    }

    @Test
    fun testHandleRssiStopped() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        distanceMeasurementManager.onDistanceMeasurementStarted(
            device.address,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        verify(callback).onStarted(device)

        distanceMeasurementManager.onDistanceMeasurementStopped(
            device.address,
            BluetoothStatusCodes.REASON_REMOTE_REQUEST,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        verify(callback).onStopped(device, BluetoothStatusCodes.REASON_REMOTE_REQUEST)
    }

    @Test
    fun testHandleRssiResult() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        distanceMeasurementManager.onDistanceMeasurementStarted(
            device.address,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        verify(callback).onStarted(device)

        distanceMeasurementManager.onDistanceMeasurementResult(
            device.address,
            100,
            100,
            -1,
            -1,
            -1,
            -1,
            1000L,
            -10,
            -20,
            -1,
            /* delaySpreadMeters= */ 10.0,
            /* detectedAttackLevel= */ DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE,
            /* velocityMetersPerSecond= */ 0.0,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        val captor = argumentCaptor<DistanceMeasurementResult>()
        verify(callback).onResult(eq(device), captor.capture())
        val result = captor.firstValue
        assertThat(result.resultMeters).isEqualTo(1.00)
        assertThat(result.errorMeters).isEqualTo(1.00)
        assertThat(result.azimuthAngle).isEqualTo(Double.NaN)
        assertThat(result.errorAzimuthAngle).isEqualTo(Double.NaN)
        if (Flags.includePowerAndRssiInDistanceMeasurementResult()) {
            assertThat(result.remoteTxPowerDbm).isEqualTo(-10)
            assertThat(result.rssiDbm).isEqualTo(-20)
        }
        assertThat(result.altitudeAngle).isEqualTo(Double.NaN)
        assertThat(result.errorAltitudeAngle).isEqualTo(Double.NaN)
        assertThat(result.measurementTimestampNanos).isEqualTo(1000L)
        assertThat(result.delaySpreadMeters).isEqualTo(Double.NaN)
        assertThat(result.detectedAttackLevel).isEqualTo(DistanceMeasurementResult.NADM_UNKNOWN)
        assertThat(result.velocityMetersPerSecond).isEqualTo(Double.NaN)
    }

    @Test
    fun testReceivedResultAfterStopped() {
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setDurationSeconds(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)
        distanceMeasurementManager.stopDistanceMeasurement(
            uuid,
            device,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
            false,
        )
        verify(nativeInterface)
            .stopDistanceMeasurement(
                device.address,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
            )
        distanceMeasurementManager.onDistanceMeasurementResult(
            device.address,
            100,
            100,
            -1,
            -1,
            -1,
            -1,
            1000L,
            127,
            127,
            -1,
            /* delaySpreadMeters= */ 10.0,
            /* detectedAttackLevel= */ DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE,
            /* velocityMetersPerSecond= */ 0.0,
            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI,
        )
        val result = DistanceMeasurementResult.Builder(1.00, 1.00).build()
        verify(callback, after(100).never()).onResult(device, result)
    }

    @Test
    fun testLogChannelSoundingTypesSupportedMetrics() {
        val captor = argumentCaptor<IntArray>()
        verify(mockMetricsLogger).logChannelSoundingTypesSupported(captor.capture())
        assertThat(captor.firstValue)
            .asList()
            .contains(BluetoothStatsLog.CHANNEL_SOUNDING_TYPES_SUPPORTED__CS_TYPES__CS_BT_CORE60)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENFORCE_SECURITY_FOR_RANGING)
    fun testStartRssiTracker_SecurityCheck_BondStatusFail() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())

        val bondStatus = mock<BondStatus>()
        whenever(bondStatus.pairingAlgorithm)
            .thenReturn(BluetoothDevice.PAIRING_ALGORITHM_LE_LEGACY)
        doReturn(bondStatus)
            .whenever(adapterService)
            .getBondStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface, never())
            .startDistanceMeasurement(any(), any(), any(), any(), any(), any())
        verify(callback).onStartFail(device, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENFORCE_SECURITY_FOR_RANGING)
    fun testStartRssiTracker_SecurityCheck_EncryptionStatusNull() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())
        doReturn(null)
            .whenever(adapterService)
            .getBondStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))
        doReturn(null)
            .whenever(adapterService)
            .getEncryptionStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface, never())
            .startDistanceMeasurement(any(), any(), any(), any(), any(), any())
        verify(callback).onStartFail(device, BluetoothStatusCodes.ERROR_NO_LE_CONNECTION)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENFORCE_SECURITY_FOR_RANGING)
    fun testStartRssiTracker_SecurityCheck_EncryptionStatusAlgoFail() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())
        doReturn(null)
            .whenever(adapterService)
            .getBondStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val encryptionStatus = mock<EncryptionStatus>()
        whenever(encryptionStatus.algorithm).thenReturn(BluetoothDevice.ENCRYPTION_ALGORITHM_NONE)
        doReturn(encryptionStatus)
            .whenever(adapterService)
            .getEncryptionStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface, never())
            .startDistanceMeasurement(any(), any(), any(), any(), any(), any())
        verify(callback).onStartFail(device, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENFORCE_SECURITY_FOR_RANGING)
    fun testStartRssiTracker_SecurityCheck_EncryptionStatusKeySizeFail() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())
        doReturn(null)
            .whenever(adapterService)
            .getBondStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val encryptionStatus = mock<EncryptionStatus>()
        whenever(encryptionStatus.algorithm).thenReturn(BluetoothDevice.ENCRYPTION_ALGORITHM_AES)
        whenever(encryptionStatus.keySize).thenReturn(10)
        doReturn(encryptionStatus)
            .whenever(adapterService)
            .getEncryptionStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface, never())
            .startDistanceMeasurement(any(), any(), any(), any(), any(), any())
        verify(callback).onStartFail(device, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENFORCE_SECURITY_FOR_RANGING)
    fun testStartRssiTracker_SecurityCheck_Success() {
        doReturn(BluetoothDevice.BOND_BONDED).whenever(adapterService).getBondState(any())
        doReturn(null)
            .whenever(adapterService)
            .getBondStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val encryptionStatus = mock<EncryptionStatus>()
        whenever(encryptionStatus.algorithm).thenReturn(BluetoothDevice.ENCRYPTION_ALGORITHM_AES)
        whenever(encryptionStatus.keySize).thenReturn(16)
        doReturn(encryptionStatus)
            .whenever(adapterService)
            .getEncryptionStatus(any(), eq(BluetoothDevice.TRANSPORT_LE))

        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(1000)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                .build()
        distanceMeasurementManager.startDistanceMeasurement(uuid, APP_UID, params, callback)

        verify(nativeInterface).startDistanceMeasurement(any(), any(), any(), any(), any(), any())
    }

    companion object {
        private const val RSSI_FREQUENCY_LOW = 3000
        private const val CS_FREQUENCY_LOW = 5000
        private const val APP_UID = 100
    }
}
