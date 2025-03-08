/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.DistanceMeasurementResult;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.UUID;

/** Test cases for {@link DistanceMeasurementManager}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DistanceMeasurementManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private DistanceMeasurementNativeInterface mDistanceMeasurementNativeInterface;
    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mPackageManager;
    @Mock private IDistanceMeasurementCallback mCallback;

    private final BluetoothDevice mDevice = getTestDevice(57);

    private DistanceMeasurementManager mDistanceMeasurementManager;
    private UUID mUuid;
    private HandlerThread mHandlerThread;

    private static final int RSSI_FREQUENCY_LOW = 3000;
    private static final int CS_FREQUENCY_LOW = 5000;

    @Before
    public void setUp() throws Exception {
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
        doReturn(true).when(mPackageManager).hasSystemFeature(any());
        doReturn(true).when(mAdapterService).isLeChannelSoundingSupported();
        doReturn(mDevice.getAddress())
                .when(mAdapterService)
                .getIdentityAddress(mDevice.getAddress());
        doReturn(true).when(mAdapterService).isConnected(any());
        DistanceMeasurementNativeInterface.setInstance(mDistanceMeasurementNativeInterface);

        mHandlerThread = new HandlerThread("DistanceMeasurementManagerTest");
        mHandlerThread.start();

        mDistanceMeasurementManager =
                new DistanceMeasurementManager(mAdapterService, mHandlerThread.getLooper());
        mUuid = UUID.randomUUID();
    }

    @After
    public void tearDown() throws Exception {
        mDistanceMeasurementManager.cleanup();
        DistanceMeasurementNativeInterface.setInstance(null);
        mHandlerThread.quit();
    }

    @Test
    public void testStartRssiTracker() {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        verify(mDistanceMeasurementNativeInterface)
                .startDistanceMeasurement(
                        mDevice.getAddress(),
                        RSSI_FREQUENCY_LOW,
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
    }

    @Test
    public void testStopRssiTracker() {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        mDistanceMeasurementManager.stopDistanceMeasurement(
                mUuid, mDevice, DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI, false);
        verify(mDistanceMeasurementNativeInterface)
                .stopDistanceMeasurement(
                        mDevice.getAddress(),
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
    }

    @Test
    public void testHandleRssiStarted() throws RemoteException {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        verify(mDistanceMeasurementNativeInterface)
                .startDistanceMeasurement(
                        mDevice.getAddress(),
                        RSSI_FREQUENCY_LOW,
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        mDistanceMeasurementManager.onDistanceMeasurementStarted(
                mDevice.getAddress(), DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        verify(mCallback).onStarted(mDevice);
    }

    @Test
    public void testHandleRssiStartFail() throws RemoteException {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        verify(mDistanceMeasurementNativeInterface)
                .startDistanceMeasurement(
                        mDevice.getAddress(),
                        RSSI_FREQUENCY_LOW,
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        mDistanceMeasurementManager.onDistanceMeasurementStopped(
                mDevice.getAddress(),
                BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        verify(mCallback)
                .onStartFail(mDevice, BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL);
    }

    @Test
    public void testCsStartFailForNoBondedBLE() throws RemoteException {
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(any());
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(
                                DistanceMeasurementMethod
                                        .DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);

        verify(mDistanceMeasurementNativeInterface, never())
                .startDistanceMeasurement(
                        mDevice.getAddress(),
                        CS_FREQUENCY_LOW,
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
        verify(mCallback).onStartFail(mDevice, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
    }

    @Test
    public void testCsStartSuccessForBondedBLE() throws RemoteException {
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(any());
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(
                                DistanceMeasurementMethod
                                        .DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);

        verify(mDistanceMeasurementNativeInterface)
                .startDistanceMeasurement(
                        mDevice.getAddress(),
                        CS_FREQUENCY_LOW,
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);

        mDistanceMeasurementManager.onDistanceMeasurementStarted(
                mDevice.getAddress(),
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
        mDistanceMeasurementManager.onDistanceMeasurementResult(
                mDevice.getAddress(),
                100,
                0,
                100,
                0,
                45,
                0,
                10000,
                1,
                /* delayedSpreadMeters= */ 10.0,
                /* detectedAttackLevel= */ DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE,
                /* velocityMetersPerSecond= */ 1.0,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
        ArgumentCaptor<DistanceMeasurementResult> result =
                ArgumentCaptor.forClass(DistanceMeasurementResult.class);

        verify(mCallback).onResult(eq(mDevice), result.capture());
        assertThat(result.getValue().getResultMeters()).isEqualTo(1.00);
        assertThat(result.getValue().getAzimuthAngle()).isEqualTo(100);
        assertThat(result.getValue().getAltitudeAngle()).isEqualTo(45);
        assertThat(result.getValue().getMeasurementTimestampNanos()).isEqualTo(10000);
        assertThat(result.getValue().getConfidenceLevel()).isEqualTo(0.01);
        assertThat(result.getValue().getDelaySpreadMeters()).isEqualTo(10.0);
        assertThat(result.getValue().getDetectedAttackLevel())
                .isEqualTo(DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE);
        assertThat(result.getValue().getVelocityMetersPerSecond()).isEqualTo(1.0);
    }

    @Test
    public void testHandleRssiStopped() throws RemoteException {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        mDistanceMeasurementManager.onDistanceMeasurementStarted(
                mDevice.getAddress(), DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        verify(mCallback).onStarted(mDevice);

        mDistanceMeasurementManager.onDistanceMeasurementStopped(
                mDevice.getAddress(),
                BluetoothStatusCodes.REASON_REMOTE_REQUEST,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        verify(mCallback).onStopped(mDevice, BluetoothStatusCodes.REASON_REMOTE_REQUEST);
    }

    @Test
    public void testHandleRssiResult() throws RemoteException {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        mDistanceMeasurementManager.onDistanceMeasurementStarted(
                mDevice.getAddress(), DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        verify(mCallback).onStarted(mDevice);

        mDistanceMeasurementManager.onDistanceMeasurementResult(
                mDevice.getAddress(),
                100,
                100,
                -1,
                -1,
                -1,
                -1,
                1000L,
                -1,
                /* delayedSpreadMeters= */ 10.0,
                /* detectedAttackLevel= */ DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE,
                /* velocityMetersPerSecond= */ 0.0,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        ArgumentCaptor<DistanceMeasurementResult> result =
                ArgumentCaptor.forClass(DistanceMeasurementResult.class);
        verify(mCallback).onResult(eq(mDevice), result.capture());
        assertThat(result.getValue().getResultMeters()).isEqualTo(1.00);
        assertThat(result.getValue().getErrorMeters()).isEqualTo(1.00);
        assertThat(result.getValue().getAzimuthAngle()).isEqualTo(Double.NaN);
        assertThat(result.getValue().getErrorAzimuthAngle()).isEqualTo(Double.NaN);
        assertThat(result.getValue().getAltitudeAngle()).isEqualTo(Double.NaN);
        assertThat(result.getValue().getErrorAltitudeAngle()).isEqualTo(Double.NaN);
        assertThat(result.getValue().getMeasurementTimestampNanos()).isEqualTo(1000L);
        assertThat(result.getValue().getDelaySpreadMeters()).isEqualTo(Double.NaN);
        assertThat(result.getValue().getDetectedAttackLevel())
                .isEqualTo(DistanceMeasurementResult.NADM_UNKNOWN);
        assertThat(result.getValue().getVelocityMetersPerSecond()).isEqualTo(Double.NaN);
    }

    @Test
    public void testReceivedResultAfterStopped() throws RemoteException {
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(1000)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setDurationSeconds(
                                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build();
        mDistanceMeasurementManager.startDistanceMeasurement(mUuid, params, mCallback);
        mDistanceMeasurementManager.stopDistanceMeasurement(
                mUuid, mDevice, DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI, false);
        verify(mDistanceMeasurementNativeInterface)
                .stopDistanceMeasurement(
                        mDevice.getAddress(),
                        DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        mDistanceMeasurementManager.onDistanceMeasurementResult(
                mDevice.getAddress(),
                100,
                100,
                -1,
                -1,
                -1,
                -1,
                1000L,
                -1,
                /* delayedSpreadMeters= */ 10.0,
                /* detectedAttackLevel= */ DistanceMeasurementResult.NADM_ATTACK_IS_POSSIBLE,
                /* velocityMetersPerSecond= */ 0.0,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        DistanceMeasurementResult result =
                new DistanceMeasurementResult.Builder(1.00, 1.00).build();
        verify(mCallback, after(100).never()).onResult(mDevice, result);
    }
}
