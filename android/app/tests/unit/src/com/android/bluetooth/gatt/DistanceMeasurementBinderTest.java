/*
 * Copyright 2025 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Collections;
import java.util.UUID;

/** Test cases for {@link DistanceMeasurementBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DistanceMeasurementBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private DistanceMeasurementManager mDistanceMeasurementManager;
    @Mock private AdapterService mAdapterService;

    private final AttributionSource mAttributionSource =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter()
                    .getAttributionSource();

    private DistanceMeasurementBinder mBinder;

    @Before
    public void setUp() throws Throwable {
        mBinder = new DistanceMeasurementBinder(mAdapterService, mDistanceMeasurementManager);
        when(mDistanceMeasurementManager.getSupportedDistanceMeasurementMethods())
                .thenReturn(Collections.emptyList());
        when(mDistanceMeasurementManager.runOnDistanceMeasurementThreadAndWaitForResult(any()))
                .thenAnswer(
                        invocationOnMock -> {
                            DistanceMeasurementManager.GetResultTask task =
                                    invocationOnMock.getArgument(0);
                            return task.getResult();
                        });
        doAnswer(
                        invocation -> {
                            ((Runnable) (invocation.getArgument(0))).run();
                            return null;
                        })
                .when(mDistanceMeasurementManager)
                .postOnDistanceMeasurementThread(any());
    }

    @Test
    public void getSupportedDistanceMeasurementMethods() {
        mBinder.getSupportedDistanceMeasurementMethods(mAttributionSource);
        verify(mDistanceMeasurementManager).getSupportedDistanceMeasurementMethods();
    }

    @Test
    public void startDistanceMeasurement() {
        UUID uuid = UUID.randomUUID();
        BluetoothDevice device = getTestDevice(3);
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(device)
                        .setDurationSeconds(123)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .build();
        IDistanceMeasurementCallback callback = mock(IDistanceMeasurementCallback.class);
        mBinder.startDistanceMeasurement(
                new ParcelUuid(uuid), params, callback, mAttributionSource);
        verify(mDistanceMeasurementManager).startDistanceMeasurement(uuid, params, callback);
    }

    @Test
    public void stopDistanceMeasurement() {
        UUID uuid = UUID.randomUUID();
        BluetoothDevice device = getTestDevice(3);
        int method = DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI;
        mBinder.stopDistanceMeasurement(new ParcelUuid(uuid), device, method, mAttributionSource);
        verify(mDistanceMeasurementManager).stopDistanceMeasurement(uuid, device, method, false);
    }
}
