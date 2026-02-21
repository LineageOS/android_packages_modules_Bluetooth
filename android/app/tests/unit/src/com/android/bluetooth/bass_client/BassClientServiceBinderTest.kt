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

package com.android.bluetooth.bass_client;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.ScanFilter;
import android.content.AttributionSource;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

/** Test cases for {@link BassClientServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BassClientServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mSource;
    @Mock private BassClientService mService;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private BassClientServiceBinder mBinder;

    @Before
    public void setUp() {
        mBinder = new BassClientServiceBinder(mService);
    }

    @Test
    public void cleanUp() {
        mBinder.cleanup();
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mSource);
        verify(mService).getConnectionState(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getConnectionState(mDevice, mSource)).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_DISCONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, mSource);
        verify(mService).getDevicesMatchingConnectionStates(states);

        mBinder.cleanup();
        assertThat(mBinder.getDevicesMatchingConnectionStates(states, mSource))
                .isEqualTo(Collections.emptyList());
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mSource);
        verify(mService).getConnectedDevices();

        mBinder.cleanup();
        assertThat(mBinder.getConnectedDevices(mSource)).isEqualTo(Collections.emptyList());
    }

    @Test
    public void setConnectionPolicy() {
        mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, mSource);
        verify(mService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        mBinder.cleanup();
        assertThat(mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, mSource))
                .isFalse();
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mSource);
        verify(mService).getConnectionPolicy(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getConnectionPolicy(mDevice, mSource))
                .isEqualTo(CONNECTION_POLICY_FORBIDDEN);
    }

    @Test
    public void registerCallback() {
        IBluetoothLeBroadcastAssistantCallback cb =
                Mockito.mock(IBluetoothLeBroadcastAssistantCallback.class);
        mBinder.registerCallback(cb, mSource);
        verify(mService).registerCallback(cb);
    }

    @Test
    public void registerCallback_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.registerCallback(null, mSource);
        verify(mService, never()).registerCallback(any());
    }

    @Test
    public void unregisterCallback() {
        IBluetoothLeBroadcastAssistantCallback cb =
                Mockito.mock(IBluetoothLeBroadcastAssistantCallback.class);
        mBinder.unregisterCallback(cb, mSource);
        verify(mService).unregisterCallback(cb);
    }

    @Test
    public void unregisterCallback_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.unregisterCallback(null, mSource);
        verify(mService, never()).unregisterCallback(any());
    }

    @Test
    public void startSearchingForSources() {
        List<ScanFilter> filters = Collections.EMPTY_LIST;
        mBinder.startSearchingForSources(filters, mSource);
        verify(mService).startSearchingForSources(filters);
    }

    @Test
    public void startSearchingForSources_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.startSearchingForSources(null, mSource);
        verify(mService, never()).startSearchingForSources(any());
    }

    @Test
    public void stopSearchingForSources() {
        mBinder.stopSearchingForSources(mSource);
        verify(mService).stopSearchingForSources();
    }

    @Test
    public void stopSearchingForSources_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.stopSearchingForSources(mSource);
        verify(mService, never()).stopSearchingForSources();
    }

    @Test
    public void isSearchInProgress() {
        mBinder.isSearchInProgress(mSource);
        verify(mService).isSearchInProgress();

        mBinder.cleanup();
        assertThat(mBinder.isSearchInProgress(mSource)).isFalse();
    }

    @Test
    public void addSource() {
        mBinder.addSource(mDevice, null, false, mSource);
        verify(mService).addSource(mDevice, null, false);
    }

    @Test
    public void addSource_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.addSource(mDevice, null, false, mSource);
        verify(mService, never()).addSource(mDevice, null, false);
    }

    @Test
    public void modifySource() {
        mBinder.modifySource(mDevice, 0, null, mSource);
        verify(mService).modifySource(mDevice, 0, null);
    }

    @Test
    public void modifySource_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.modifySource(mDevice, 0, null, mSource);
        verify(mService, never()).modifySource(mDevice, 0, null);
    }

    @Test
    public void removeSource() {
        mBinder.removeSource(mDevice, 0, mSource);
        verify(mService).removeSource(mDevice, 0);
    }

    @Test
    public void removeSource_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.removeSource(mDevice, 0, mSource);
        verify(mService, never()).removeSource(mDevice, 0);
    }

    @Test
    public void getAllSources() {
        mBinder.getAllSources(mDevice, mSource);
        verify(mService).getAllSources(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getAllSources(mDevice, mSource)).isEqualTo(Collections.emptyList());
    }

    @Test
    public void getMaximumSourceCapacity() {
        mBinder.getMaximumSourceCapacity(mDevice, mSource);
        verify(mService).getMaximumSourceCapacity(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getMaximumSourceCapacity(mDevice, mSource)).isEqualTo(0);
    }
}
