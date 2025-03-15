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

package com.android.bluetooth.gatt;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link AdvertiseBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdvertiseBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private AdvertiseManager mAdvertiseManager;

    private final AttributionSource mAttributionSource =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter()
                    .getAttributionSource();
    private AdvertiseBinder mBinder;

    @Before
    public void setUp() {
        doAnswer(
                        invocation -> {
                            ((Runnable) invocation.getArgument(0)).run();
                            return null;
                        })
                .when(mAdvertiseManager)
                .doOnAdvertiseThread(any());
        mBinder = new AdvertiseBinder(mAdapterService, mAdvertiseManager);
    }

    @Test
    public void startAdvertisingSet() {
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();
        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        PeriodicAdvertisingParameters periodicParameters =
                new PeriodicAdvertisingParameters.Builder().build();
        AdvertiseData periodicData = new AdvertiseData.Builder().build();
        int duration = 1;
        int maxExtAdvEvents = 2;
        int serverIf = 3;
        IAdvertisingSetCallback callback = mock(IAdvertisingSetCallback.class);

        mBinder.startAdvertisingSet(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                serverIf,
                callback,
                mAttributionSource);

        verify(mAdvertiseManager)
                .startAdvertisingSet(
                        parameters,
                        advertiseData,
                        scanResponse,
                        periodicParameters,
                        periodicData,
                        duration,
                        maxExtAdvEvents,
                        serverIf,
                        callback,
                        mAttributionSource);
    }

    @Test
    public void stopAdvertisingSet() {
        IAdvertisingSetCallback callback = mock(IAdvertisingSetCallback.class);

        mBinder.stopAdvertisingSet(callback, mAttributionSource);

        verify(mAdvertiseManager).stopAdvertisingSet(callback);
    }

    @Test
    public void setAdvertisingData() {
        int advertiserId = 1;
        AdvertiseData data = new AdvertiseData.Builder().build();

        mBinder.setAdvertisingData(advertiserId, data, mAttributionSource);
        verify(mAdvertiseManager).setAdvertisingData(advertiserId, data);
    }

    @Test
    public void setAdvertisingParameters() {
        int advertiserId = 1;
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().build();

        mBinder.setAdvertisingParameters(advertiserId, parameters, mAttributionSource);
        verify(mAdvertiseManager).setAdvertisingParameters(advertiserId, parameters);
    }

    @Test
    public void setPeriodicAdvertisingData() {
        int advertiserId = 1;
        AdvertiseData data = new AdvertiseData.Builder().build();

        mBinder.setPeriodicAdvertisingData(advertiserId, data, mAttributionSource);
        verify(mAdvertiseManager).setPeriodicAdvertisingData(advertiserId, data);
    }

    @Test
    public void setPeriodicAdvertisingEnable() {
        int advertiserId = 1;
        boolean enable = true;

        mBinder.setPeriodicAdvertisingEnable(advertiserId, enable, mAttributionSource);
        verify(mAdvertiseManager).setPeriodicAdvertisingEnable(advertiserId, enable);
    }

    @Test
    public void setPeriodicAdvertisingParameters() {
        int advertiserId = 1;
        PeriodicAdvertisingParameters parameters =
                new PeriodicAdvertisingParameters.Builder().build();

        mBinder.setPeriodicAdvertisingParameters(advertiserId, parameters, mAttributionSource);
        verify(mAdvertiseManager).setPeriodicAdvertisingParameters(advertiserId, parameters);
    }

    @Test
    public void setScanResponseData() {
        int advertiserId = 1;
        AdvertiseData data = new AdvertiseData.Builder().build();

        mBinder.setScanResponseData(advertiserId, data, mAttributionSource);
        verify(mAdvertiseManager).setScanResponseData(advertiserId, data);
    }

    @Test
    public void getOwnAddress() {
        int advertiserId = 1;

        mBinder.getOwnAddress(advertiserId, mAttributionSource);
        verify(mAdvertiseManager).getOwnAddress(advertiserId);
    }

    @Test
    public void enableAdvertisingSet() {
        int advertiserId = 1;
        boolean enable = true;
        int duration = 3;
        int maxExtAdvEvents = 4;

        mBinder.enableAdvertisingSet(
                advertiserId, enable, duration, maxExtAdvEvents, mAttributionSource);
        verify(mAdvertiseManager)
                .enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mBinder.cleanup();
    }
}
