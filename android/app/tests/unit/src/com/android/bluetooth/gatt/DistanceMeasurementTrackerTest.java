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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.os.HandlerThread;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.UUID;

/** Test cases for {@link DistanceMeasurementTracker}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DistanceMeasurementTrackerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private DistanceMeasurementManager mDistanceMeasurementManager;
    @Mock private IDistanceMeasurementCallback mCallback;

    private static final int TIMEOUT_S = 1;
    private static final int TIMEOUT_MS = 1500;
    private static final int METHOD = DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI;
    private static final int APP_UID = 100;

    private final BluetoothDevice mDevice = getTestDevice(35);

    private DistanceMeasurementTracker mTracker;
    private UUID mUuid;
    private DistanceMeasurementParams mParams;
    private HandlerThread mHandlerThread;

    @Before
    public void setUp() throws Exception {
        mUuid = UUID.randomUUID();
        mParams =
                new DistanceMeasurementParams.Builder(mDevice)
                        .setDurationSeconds(TIMEOUT_S)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                        .setMethodId(METHOD)
                        .build();
        mTracker =
                new DistanceMeasurementTracker(
                        mDistanceMeasurementManager,
                        APP_UID,
                        mParams,
                        mDevice.getAddress(),
                        mUuid,
                        1000,
                        mCallback);
        mHandlerThread = new HandlerThread("DistanceMeasurementTrackerTestHandlerThread");
        mHandlerThread.start();
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join(TIMEOUT_MS);
    }

    @Test
    public void testStartTimer() {
        mTracker.startTimer(mHandlerThread.getLooper());
        verify(mDistanceMeasurementManager, timeout(TIMEOUT_MS).times(1))
                .stopDistanceMeasurement(mUuid, mDevice, METHOD, true);
    }

    @Test
    public void testCancelTimer() {
        mTracker.startTimer(mHandlerThread.getLooper());
        mTracker.cancelTimer();
        verify(mDistanceMeasurementManager, after(TIMEOUT_MS).never())
                .stopDistanceMeasurement(mUuid, mDevice, METHOD, true);
    }

    @Test
    public void testEquals() {
        DistanceMeasurementTracker tracker =
                new DistanceMeasurementTracker(
                        mDistanceMeasurementManager,
                        APP_UID,
                        mParams,
                        mDevice.getAddress(),
                        mUuid,
                        1000,
                        mCallback);
        assertThat(mTracker.equals(tracker)).isTrue();
    }

    @Test
    public void testHashCode() {
        DistanceMeasurementTracker tracker =
                new DistanceMeasurementTracker(
                        mDistanceMeasurementManager,
                        APP_UID,
                        mParams,
                        mDevice.getAddress(),
                        mUuid,
                        1000,
                        mCallback);
        assertThat(mTracker.hashCode()).isEqualTo(tracker.hashCode());
    }
}
