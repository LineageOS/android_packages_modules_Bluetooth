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

package com.android.bluetooth.bas;

import static android.bluetooth.BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.bas.BatteryStateMachine.MESSAGE_CONNECT;
import static com.android.bluetooth.bas.BatteryStateMachine.MESSAGE_CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.bas.BatteryStateMachine.MESSAGE_DISCONNECT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.os.Looper;

import androidx.test.filters.SmallTest;

import com.android.bluetooth.TestLooper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

/** Test cases for {@link BatteryStateMachine}. */
@SmallTest
@RunWith(JUnit4.class)
public class BatteryStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BatteryService mBatteryService;

    private final BluetoothDevice mDevice = getTestDevice(93);

    private TestLooper mLooper;
    private FakeBatteryStateMachine mBatteryStateMachine;

    @Before
    public void setUp() {
        doReturn(true).when(mBatteryService).canConnect(any());

        mLooper = new TestLooper();

        mBatteryStateMachine =
                new FakeBatteryStateMachine(mBatteryService, mDevice, mLooper.getLooper());
        mBatteryStateMachine.mShouldAllowGatt = true;
    }

    @Test
    public void initialState_isDisconnected() {
        assertThat(mBatteryStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void connect_whenNotAllowed_stayDisconnected() {
        doReturn(false).when(mBatteryService).canConnect(any());

        sendAndDispatchMessage(MESSAGE_CONNECT);

        verify(mBatteryService, never()).handleConnectionStateChanged(any(), anyInt(), anyInt());
        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnected.class);
    }

    @Test
    public void connect_whenGattCanNotConnect_stayDisconnected() {
        mBatteryStateMachine.mShouldAllowGatt = false;

        sendAndDispatchMessage(MESSAGE_CONNECT);

        verify(mBatteryService, never()).handleConnectionStateChanged(any(), anyInt(), anyInt());
        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnected.class);
    }

    @Test
    public void connect_successCase_isConnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);

        verify(mBatteryService)
                .handleConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));
        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connecting.class);

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED);

        verify(mBatteryService)
                .handleConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_CONNECTED));
        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connected.class);
    }

    @Test
    public void disconnect_whenConnecting_isDisconnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);

        verify(mBatteryService)
                .handleConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));

        sendAndDispatchMessage(MESSAGE_DISCONNECT);

        verify(mBatteryService)
                .handleConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_DISCONNECTED));
    }

    private void goToStateConnected() {
        sendAndDispatchMessage(MESSAGE_CONNECT);
        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED);
    }

    @Test
    public void connect_whenConnected_doNothing() {
        goToStateConnected();

        sendAndDispatchMessage(MESSAGE_CONNECT);

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connected.class);
    }

    @Test
    public void disconnect_whenConnected_isDisconnected() {
        goToStateConnected();

        sendAndDispatchMessage(MESSAGE_DISCONNECT);
        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnecting.class);

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_DISCONNECTED);

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnected.class);
    }

    @Test
    public void disconnectWithTimeout_whenConnected_isDisconnected() {
        goToStateConnected();

        sendAndDispatchMessage(MESSAGE_DISCONNECT);
        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnecting.class);

        mLooper.moveTimeForward(BatteryStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnected.class);
    }

    @Test
    public void disconnectNotification_whenConnected_isDisconnected() {
        goToStateConnected();

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_DISCONNECTED);

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Disconnected.class);
    }

    @Test
    public void connectNotification_whenConnected_stayConnected() {
        goToStateConnected();

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED);

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connected.class);
    }

    @Test
    public void unknownStateNotification_whenConnected_stayConnected() {
        goToStateConnected();

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, -1);

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connected.class);
    }

    @Test
    public void unknownMessage_whenConnected_stayConnected() {
        goToStateConnected();

        sendAndDispatchMessage(12312);

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connected.class);
    }

    @Test
    public void testBatteryLevelChanged() {
        mBatteryStateMachine.updateBatteryLevel(new byte[] {(byte) 0x30});

        verify(mBatteryService).handleBatteryChanged(any(BluetoothDevice.class), eq(0x30));
    }

    @Test
    public void testEmptyBatteryLevelIgnored() {
        mBatteryStateMachine.updateBatteryLevel(new byte[0]);

        verify(mBatteryService, never()).handleBatteryChanged(any(), anyInt());
    }

    @Test
    public void testDisconnectResetBatteryLevel() {
        sendAndDispatchMessage(MESSAGE_CONNECT);

        verify(mBatteryService)
                .handleConnectionStateChanged(any(), eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connecting.class);

        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED);

        verify(mBatteryService)
                .handleConnectionStateChanged(any(), eq(STATE_CONNECTING), eq(STATE_CONNECTED));

        assertThat(mBatteryStateMachine.getCurrentState())
                .isInstanceOf(BatteryStateMachine.Connected.class);

        mBatteryStateMachine.updateBatteryLevel(new byte[] {(byte) 0x30});
        verify(mBatteryService).handleBatteryChanged(any(), eq(0x30));

        sendAndDispatchMessage(MESSAGE_DISCONNECT);
        verify(mBatteryService).handleBatteryChanged(any(), eq(BATTERY_LEVEL_UNKNOWN));
    }

    private void sendAndDispatchMessage(int what, int arg1) {
        mBatteryStateMachine.sendMessage(what, arg1);
        mLooper.dispatchAll();
    }

    private void sendAndDispatchMessage(int what) {
        mBatteryStateMachine.sendMessage(what);
        mLooper.dispatchAll();
    }

    // Simulates GATT connection for testing.
    private static class FakeBatteryStateMachine extends BatteryStateMachine {
        boolean mShouldAllowGatt = true;

        FakeBatteryStateMachine(BatteryService service, BluetoothDevice device, Looper looper) {
            super(service, device, looper);
        }

        @Override
        boolean connectGatt() {
            return mShouldAllowGatt;
        }

        @Override
        void disconnectGatt() {
            // Do nothing as there is no BluetoothGatt available during test
        }

        @Override
        void discoverServicesGatt() {
            // Do nothing as there is no BluetoothGatt available during test
        }
    }
}
