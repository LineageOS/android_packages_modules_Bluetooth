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

package com.android.bluetooth.avrcpcontroller;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpControllerServiceBinderTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AvrcpControllerService mService;

    private final BluetoothDevice mDevice = getTestDevice(49);

    AvrcpControllerService.AvrcpControllerServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder = new AvrcpControllerService.AvrcpControllerServiceBinder(mService);
    }

    @Test
    public void getConnectedDevices_callsServiceMethod() {
        mBinder.getConnectedDevices(null);

        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates_callsServiceMethod() {
        int[] states = new int[] {STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, null);

        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState_callsServiceMethod() {
        mBinder.getConnectionState(mDevice, null);

        verify(mService).getConnectionState(mDevice);
    }

    @Test
    public void sendGroupNavigationCmd_notImplemented_doesNothing() {
        mBinder.sendGroupNavigationCmd(mDevice, 1, 2, null);
    }

    @Test
    public void getPlayerSettings_notImplemented_doesNothing() {
        mBinder.getPlayerSettings(mDevice, null);
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mBinder.cleanup();
    }
}
