/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.hearingaid;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetRemoteDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;

import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

/** Test cases for {@link HearingAidNativeInterface}. */
@RunWith(AndroidJUnit4.class)
public class HearingAidNativeInterfaceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private HearingAidService mService;

    private final BluetoothDevice mDevice = getTestDevice(14);

    private HearingAidNativeInterface mNativeInterface;

    @Before
    public void setUp() throws Exception {
        mockGetRemoteDevice(mAdapterService, mDevice);
        when(mService.isAvailable()).thenReturn(true);
        HearingAidService.setHearingAidService(mService);
        mNativeInterface = HearingAidNativeInterface.getInstance(mAdapterService);
    }

    @After
    public void tearDown() throws Exception {
        HearingAidService.setHearingAidService(null);
        HearingAidNativeInterface.setInstance(null);
    }

    @Test
    public void getByteAddress() {
        assertThat(mNativeInterface.getByteAddress(null))
                .isEqualTo(Utils.getBytesFromAddress("00:00:00:00:00:00"));

        assertThat(mNativeInterface.getByteAddress(mDevice))
                .isEqualTo(Utils.getBytesFromAddress(mDevice.getAddress()));
    }

    @Test
    public void onConnectionStateChanged() {
        mNativeInterface.onConnectionStateChanged(
                STATE_CONNECTED, mNativeInterface.getByteAddress(mDevice));

        ArgumentCaptor<HearingAidStackEvent> event =
                ArgumentCaptor.forClass(HearingAidStackEvent.class);
        verify(mService).messageFromNative(event.capture());
        assertThat(event.getValue().type)
                .isEqualTo(HearingAidStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        assertThat(event.getValue().valueInt1).isEqualTo(STATE_CONNECTED);

        Mockito.clearInvocations(mService);
        HearingAidService.setHearingAidService(null);
        mNativeInterface.onConnectionStateChanged(
                STATE_CONNECTED, mNativeInterface.getByteAddress(mDevice));
        verify(mService, never()).messageFromNative(any());
    }

    @Test
    public void onDeviceAvailable() {
        byte capabilities = 0;
        long hiSyncId = 100;
        mNativeInterface.onDeviceAvailable(
                capabilities, hiSyncId, mNativeInterface.getByteAddress(mDevice));

        ArgumentCaptor<HearingAidStackEvent> event =
                ArgumentCaptor.forClass(HearingAidStackEvent.class);
        verify(mService).messageFromNative(event.capture());
        assertThat(event.getValue().type)
                .isEqualTo(HearingAidStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        assertThat(event.getValue().valueInt1).isEqualTo(capabilities);
        assertThat(event.getValue().valueLong2).isEqualTo(hiSyncId);

        Mockito.clearInvocations(mService);
        HearingAidService.setHearingAidService(null);
        mNativeInterface.onDeviceAvailable(
                capabilities, hiSyncId, mNativeInterface.getByteAddress(mDevice));
        verify(mService, never()).messageFromNative(any());
    }
}
