/*
 * Copyright 2022 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.ScanFilter;
import android.content.AttributionSource;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class BleBroadcastAssistantBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private final BluetoothAdapter mAdapter =
            InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getSystemService(BluetoothManager.class)
                    .getAdapter();
    private final AttributionSource mAttributionSource = mAdapter.getAttributionSource();
    private final BluetoothDevice mDevice = getTestDevice(0);

    @Mock private BassClientService mService;

    private BassClientService.BluetoothLeBroadcastAssistantBinder mBinder;

    @Before
    public void setUp() {
        mBinder = new BassClientService.BluetoothLeBroadcastAssistantBinder(mService);
    }

    @Test
    public void cleanUp() {
        mBinder.cleanup();
        assertThat(mBinder.mService).isNull();
    }

    @Test
    public void getConnectionState() {
        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mService).getConnectionState(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getConnectionState(mDevice, mAttributionSource))
                .isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        int[] states = new int[] {STATE_DISCONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(states);

        mBinder.cleanup();
        assertThat(mBinder.getDevicesMatchingConnectionStates(states, mAttributionSource))
                .isEqualTo(Collections.emptyList());
    }

    @Test
    public void getConnectedDevices() {
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();

        mBinder.cleanup();
        assertThat(mBinder.getConnectedDevices(mAttributionSource))
                .isEqualTo(Collections.emptyList());
    }

    @Test
    public void setConnectionPolicy() {
        mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, mAttributionSource);
        verify(mService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        mBinder.cleanup();
        assertThat(
                        mBinder.setConnectionPolicy(
                                mDevice, CONNECTION_POLICY_ALLOWED, mAttributionSource))
                .isFalse();
    }

    @Test
    public void getConnectionPolicy() {
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getConnectionPolicy(mDevice, mAttributionSource))
                .isEqualTo(CONNECTION_POLICY_FORBIDDEN);
    }

    @Test
    public void registerCallback() {
        IBluetoothLeBroadcastAssistantCallback cb =
                Mockito.mock(IBluetoothLeBroadcastAssistantCallback.class);
        mBinder.registerCallback(cb, mAttributionSource);
        verify(mService).registerCallback(cb);
    }

    @Test
    public void registerCallback_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.registerCallback(null, mAttributionSource);
        verify(mService, never()).registerCallback(any());
    }

    @Test
    public void unregisterCallback() {
        IBluetoothLeBroadcastAssistantCallback cb =
                Mockito.mock(IBluetoothLeBroadcastAssistantCallback.class);
        mBinder.unregisterCallback(cb, mAttributionSource);
        verify(mService).unregisterCallback(cb);
    }

    @Test
    public void unregisterCallback_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.unregisterCallback(null, mAttributionSource);
        verify(mService, never()).unregisterCallback(any());
    }

    @Test
    public void startSearchingForSources() {
        List<ScanFilter> filters = Collections.EMPTY_LIST;
        mBinder.startSearchingForSources(filters, mAttributionSource);
        verify(mService).startSearchingForSources(filters);
    }

    @Test
    public void startSearchingForSources_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.startSearchingForSources(null, mAttributionSource);
        verify(mService, never()).startSearchingForSources(any());
    }

    @Test
    public void stopSearchingForSources() {
        mBinder.stopSearchingForSources(mAttributionSource);
        verify(mService).stopSearchingForSources();
    }

    @Test
    public void stopSearchingForSources_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.stopSearchingForSources(mAttributionSource);
        verify(mService, never()).stopSearchingForSources();
    }

    @Test
    public void isSearchInProgress() {
        mBinder.isSearchInProgress(mAttributionSource);
        verify(mService).isSearchInProgress();

        mBinder.cleanup();
        assertThat(mBinder.isSearchInProgress(mAttributionSource)).isFalse();
    }

    @Test
    public void addSource() {
        mBinder.addSource(mDevice, null, false, mAttributionSource);
        verify(mService).addSource(mDevice, null, false);
    }

    @Test
    public void addSource_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.addSource(mDevice, null, false, mAttributionSource);
        verify(mService, never()).addSource(mDevice, null, false);
    }

    @Test
    public void modifySource() {
        mBinder.modifySource(mDevice, 0, null, mAttributionSource);
        verify(mService).modifySource(mDevice, 0, null);
    }

    @Test
    public void modifySource_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.modifySource(mDevice, 0, null, mAttributionSource);
        verify(mService, never()).modifySource(mDevice, 0, null);
    }

    @Test
    public void removeSource() {
        mBinder.removeSource(mDevice, 0, mAttributionSource);
        verify(mService).removeSource(mDevice, 0);
    }

    @Test
    public void removeSource_afterCleanup_doNothing() {
        mBinder.cleanup();
        mBinder.removeSource(mDevice, 0, mAttributionSource);
        verify(mService, never()).removeSource(mDevice, 0);
    }

    @Test
    public void getAllSources() {
        mBinder.getAllSources(mDevice, mAttributionSource);
        verify(mService).getAllSources(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getAllSources(mDevice, mAttributionSource))
                .isEqualTo(Collections.emptyList());
    }

    @Test
    public void getMaximumSourceCapacity() {
        mBinder.getMaximumSourceCapacity(mDevice, mAttributionSource);
        verify(mService).getMaximumSourceCapacity(mDevice);

        mBinder.cleanup();
        assertThat(mBinder.getMaximumSourceCapacity(mDevice, mAttributionSource)).isEqualTo(0);
    }
}
