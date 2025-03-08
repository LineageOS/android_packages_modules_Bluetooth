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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothGatt.GATT_FAILURE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasFlag;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.Utils.joinUninterruptibly;
import static com.android.bluetooth.bass_client.BassClientStateMachine.ADD_BCAST_SOURCE;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CANCEL_PENDING_SOURCE_OPERATION;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CONNECT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CONNECT_TIMEOUT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.DISCONNECT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.GATT_TXN_PROCESSED;
import static com.android.bluetooth.bass_client.BassClientStateMachine.GATT_TXN_TIMEOUT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.INITIATE_PA_SYNC_TRANSFER;
import static com.android.bluetooth.bass_client.BassClientStateMachine.READ_BASS_CHARACTERISTICS;
import static com.android.bluetooth.bass_client.BassClientStateMachine.REMOTE_SCAN_START;
import static com.android.bluetooth.bass_client.BassClientStateMachine.REMOTE_SCAN_STOP;
import static com.android.bluetooth.bass_client.BassClientStateMachine.REMOVE_BCAST_SOURCE;
import static com.android.bluetooth.bass_client.BassClientStateMachine.SET_BCAST_CODE;
import static com.android.bluetooth.bass_client.BassClientStateMachine.START_SCAN_OFFLOAD;
import static com.android.bluetooth.bass_client.BassClientStateMachine.STOP_SCAN_OFFLOAD;
import static com.android.bluetooth.bass_client.BassClientStateMachine.SWITCH_BCAST_SOURCE;
import static com.android.bluetooth.bass_client.BassClientStateMachine.UPDATE_BCAST_SOURCE;
import static com.android.bluetooth.bass_client.BassConstants.CLIENT_CHARACTERISTIC_CONFIG;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.BroadcastOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.flags.Flags;

import com.google.common.primitives.Bytes;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@MediumTest
@RunWith(JUnit4.class)
public class BassClientStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private BassClientService mBassClientService;
    @Mock private BluetoothMethodProxy mMethodProxy;
    @Mock private MetricsLogger mMetricsLogger;

    private static final int CONNECTION_TIMEOUT_MS = 1_000;
    private static final int TIMEOUT_MS = 2_000;
    private static final int NO_TIMEOUT_MS = 0;
    private static final int WAIT_MS = 1_200;
    private static final int TEST_BROADCAST_ID = 42;
    private static final int TEST_SOURCE_ID = 1;
    private static final int TEST_CHANNEL_INDEX = 1;
    private static final String EMPTY_BLUETOOTH_DEVICE_ADDRESS = "00:00:00:00:00:00";
    private static final byte OPCODE_UPDATE_SOURCE = 0x03;
    private static final int UPDATE_SOURCE_FIXED_LENGTH = 6;

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mTargetContext.getSystemService(BluetoothManager.class).getAdapter();
    private final BluetoothDevice mTestDevice = getTestDevice(0);
    private final BluetoothDevice mSourceTestDevice = getTestDevice(1);

    private HandlerThread mHandlerThread;
    private StubBassClientStateMachine mBassClientStateMachine;
    private BluetoothDevice mEmptyTestDevice;

    @Before
    public void setUp() throws Exception {
        mEmptyTestDevice = mAdapter.getRemoteDevice(EMPTY_BLUETOOTH_DEVICE_ADDRESS);
        assertThat(mEmptyTestDevice).isNotNull();

        TestUtils.setAdapterService(mAdapterService);

        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);
        doNothing()
                .when(mMethodProxy)
                .periodicAdvertisingManagerTransferSync(any(), any(), anyInt(), anyInt());
        MetricsLogger.setInstanceForTesting(mMetricsLogger);

        doReturn(mEmptyTestDevice)
                .when(mAdapterService)
                .getDeviceFromByte(Utils.getBytesFromAddress(EMPTY_BLUETOOTH_DEVICE_ADDRESS));
        doReturn(mBassClientService).when(mBassClientService).getBaseContext();

        // Set up thread and looper
        mHandlerThread = new HandlerThread("BassClientStateMachineTestHandlerThread");
        mHandlerThread.start();
        mBassClientStateMachine =
                new StubBassClientStateMachine(
                        mTestDevice,
                        mBassClientService,
                        mAdapterService,
                        mHandlerThread.getLooper(),
                        CONNECTION_TIMEOUT_MS);
        mBassClientStateMachine.start();
    }

    private static int classTypeToConnectionState(Class type) {
        if (type == BassClientStateMachine.Disconnected.class) {
            return STATE_DISCONNECTED;
        } else if (type == BassClientStateMachine.Connecting.class) {
            return STATE_CONNECTING;
        } else if (type == BassClientStateMachine.Connected.class
                || type == BassClientStateMachine.ConnectedProcessing.class) {
            return STATE_CONNECTED;
        } else {
            assertWithMessage("Invalid class type given: " + type).fail();
            return 0;
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mBassClientStateMachine == null) {
            return;
        }

        MetricsLogger.setInstanceForTesting(null);
        mBassClientStateMachine.doQuit();
        mHandlerThread.quit();
        joinUninterruptibly(mHandlerThread);
        TestUtils.clearAdapterService(mAdapterService);
    }

    /** Test that default state is disconnected */
    @Test
    public void testDefaultDisconnectedState() {
        assertThat(mBassClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    /**
     * Allow/disallow connection to any device.
     *
     * @param allow if true, connection is allowed
     */
    private void allowConnection(boolean allow) {
        when(mBassClientService.okToConnect(any(BluetoothDevice.class))).thenReturn(allow);
    }

    private void allowConnectGatt(boolean allow) {
        mBassClientStateMachine.mShouldAllowGatt = allow;
    }

    /** Test that an incoming connection with policy forbidding connection is rejected */
    @Test
    public void testOkToConnectFails() {
        allowConnection(false);
        allowConnectGatt(true);

        // Inject an event for when incoming connection is requested
        mBassClientStateMachine.sendMessage(CONNECT);

        // Verify that no connection state broadcast is executed
        verify(mBassClientService, after(WAIT_MS).never())
                .sendBroadcast(any(Intent.class), anyString());

        // Check that we are in Disconnected state
        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
    }

    @Test
    public void testFailToConnectGatt() {
        allowConnection(true);
        allowConnectGatt(false);

        // Inject an event for when incoming connection is requested
        mBassClientStateMachine.sendMessage(CONNECT);

        // Verify that no connection state broadcast is executed
        verify(mBassClientService, after(WAIT_MS).never())
                .sendBroadcast(any(Intent.class), anyString());

        // Check that we are in Disconnected state
        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
        assertThat(mBassClientStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void testSuccessfullyConnected() {
        allowConnection(true);
        allowConnectGatt(true);

        // Inject an event for when incoming connection is requested
        mBassClientStateMachine.sendMessage(CONNECT);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mBassClientService, timeout(TIMEOUT_MS))
                .sendBroadcastMultiplePermissions(
                        intentArgument1.capture(),
                        any(String[].class),
                        any(BroadcastOptions.class));
        assertThat(intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_CONNECTING);

        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connecting.class);

        assertThat(mBassClientStateMachine.mGattCallback).isNotNull();
        mBassClientStateMachine.notifyConnectionStateChanged(GATT_SUCCESS, STATE_CONNECTED);

        // Verify that the expected number of broadcasts are executed:
        // - two calls to broadcastConnectionState(): Disconnected -> Connecting -> Connected
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mBassClientService, timeout(TIMEOUT_MS).times(2))
                .sendBroadcastMultiplePermissions(
                        intentArgument2.capture(),
                        any(String[].class),
                        any(BroadcastOptions.class));

        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connected.class);
    }

    @Test
    public void testConnectGattTimeout() {
        allowConnection(true);
        allowConnectGatt(true);

        // Inject an event for when incoming connection is requested
        mBassClientStateMachine.sendMessage(CONNECT);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mBassClientService, timeout(TIMEOUT_MS))
                .sendBroadcastMultiplePermissions(
                        intentArgument1.capture(),
                        any(String[].class),
                        any(BroadcastOptions.class));
        assertThat(intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_CONNECTING);

        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connecting.class);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mBassClientService, timeout(TIMEOUT_MS).times(2))
                .sendBroadcastMultiplePermissions(
                        intentArgument2.capture(),
                        any(String[].class),
                        any(BroadcastOptions.class));
        assertThat(intentArgument2.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                .isEqualTo(STATE_DISCONNECTED);

        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
    }

    @Test
    public void testStatesChangesWithMessages() {
        allowConnection(true);
        allowConnectGatt(true);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);

        // disconnected -> connecting ---timeout---> disconnected
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        BassClientStateMachine.CONNECT_TIMEOUT, mTestDevice),
                BassClientStateMachine.Disconnected.class);

        // disconnected -> connecting ---DISCONNECT---> CONNECTION_STATE_CHANGED(connected)
        // --> connected -> disconnected
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(BassClientStateMachine.DISCONNECT),
                BassClientStateMachine.Connecting.class);
        mBassClientStateMachine.sendMessage(
                CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_CONNECTED));

        // disconnected -> connecting ---CONNECTION_STATE_CHANGED(connected)---> connected -->
        // disconnected
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_CONNECTED)),
                BassClientStateMachine.Connected.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_DISCONNECTED)),
                BassClientStateMachine.Disconnected.class);

        // disconnected -> connecting ---CONNECTION_STATE_CHANGED(non-connected) --> disconnected
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_DISCONNECTED)),
                BassClientStateMachine.Disconnected.class);

        // change default state to connected for the next tests
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_CONNECTED)),
                BassClientStateMachine.Connected.class);

        // connected ----READ_BASS_CHARACTERISTICS---> connectedProcessing --GATT_TXN_PROCESSED
        // --> connected

        // Make bluetoothGatt non-null so state will transit
        mBassClientStateMachine.mBluetoothGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBroadcastScanControlPoint =
                new BluetoothGattCharacteristic(
                        BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT,
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        READ_BASS_CHARACTERISTICS,
                        new BluetoothGattCharacteristic(
                                UUID.randomUUID(),
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)),
                BassClientStateMachine.ConnectedProcessing.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);

        // connected ----READ_BASS_CHARACTERISTICS---> connectedProcessing --GATT_TXN_TIMEOUT -->
        // connected
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        READ_BASS_CHARACTERISTICS,
                        new BluetoothGattCharacteristic(
                                UUID.randomUUID(),
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)),
                BassClientStateMachine.ConnectedProcessing.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_TIMEOUT),
                BassClientStateMachine.Connected.class);

        // connected ----START_SCAN_OFFLOAD---> connectedProcessing --GATT_TXN_PROCESSED-->
        // connected
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(BassClientStateMachine.START_SCAN_OFFLOAD),
                BassClientStateMachine.ConnectedProcessing.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);

        // connected ----STOP_SCAN_OFFLOAD---> connectedProcessing --GATT_TXN_PROCESSED--> connected
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(STOP_SCAN_OFFLOAD),
                BassClientStateMachine.ConnectedProcessing.class);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);
    }

    @Test
    public void acquireAllBassChars() {
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        // Do nothing when mBluetoothGatt.getService returns null
        mBassClientStateMachine.acquireAllBassChars();

        BluetoothGattService gattService = Mockito.mock(BluetoothGattService.class);
        when(btGatt.getService(BassConstants.BASS_UUID)).thenReturn(gattService);

        List<BluetoothGattCharacteristic> characteristics = new ArrayList<>();
        BluetoothGattCharacteristic scanControlPoint =
                new BluetoothGattCharacteristic(
                        BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT,
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        characteristics.add(scanControlPoint);

        BluetoothGattCharacteristic bassCharacteristic =
                new BluetoothGattCharacteristic(
                        UUID.randomUUID(),
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        characteristics.add(bassCharacteristic);

        when(gattService.getCharacteristics()).thenReturn(characteristics);
        mBassClientStateMachine.acquireAllBassChars();
        assertThat(mBassClientStateMachine.mBroadcastScanControlPoint).isEqualTo(scanControlPoint);
        assertThat(mBassClientStateMachine.mBroadcastCharacteristics).contains(bassCharacteristic);
    }

    @Test
    public void simpleMethods() {
        // dump() shouldn't crash
        StringBuilder sb = new StringBuilder();
        mBassClientStateMachine.dump(sb);

        // log() shouldn't crash
        String msg = "test-log-message";
        mBassClientStateMachine.log(msg);

        // messageWhatToString() shouldn't crash
        for (int i = CONNECT; i <= CONNECT_TIMEOUT + 1; ++i) {
            mBassClientStateMachine.messageWhatToString(i);
        }

        final int invalidSourceId = -100;
        assertThat(mBassClientStateMachine.getCurrentBroadcastMetadata(invalidSourceId)).isNull();
        assertThat(mBassClientStateMachine.getDevice()).isEqualTo(mTestDevice);
        assertThat(mBassClientStateMachine.hasPendingSourceOperation()).isFalse();
        assertThat(mBassClientStateMachine.hasPendingSourceOperation(1)).isFalse();
        assertThat(mBassClientStateMachine.isEmpty(new byte[] {0})).isTrue();
        assertThat(mBassClientStateMachine.isEmpty(new byte[] {1})).isFalse();
        assertThat(mBassClientStateMachine.isPendingRemove(invalidSourceId)).isFalse();
    }

    @Test
    public void gattCallbackOnConnectionStateChange_changedToConnected()
            throws InterruptedException {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        // disallow connection
        allowConnection(false);
        int status = STATE_CONNECTING;
        int newState = STATE_CONNECTED;
        cb.onConnectionStateChange(null, status, newState);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        verify(btGatt).disconnect();
        verify(btGatt).close();
        assertThat(mBassClientStateMachine.mBluetoothGatt).isNull();
        assertThat(mBassClientStateMachine.mMsgWhats).contains(CONNECTION_STATE_CHANGED);
        mBassClientStateMachine.mMsgWhats.clear();

        mBassClientStateMachine.mBluetoothGatt = btGatt;
        allowConnection(true);
        mBassClientStateMachine.mDiscoveryInitiated = false;
        status = STATE_DISCONNECTED;
        newState = STATE_CONNECTED;
        cb.onConnectionStateChange(null, status, newState);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        assertThat(mBassClientStateMachine.mDiscoveryInitiated).isTrue();
        assertThat(mBassClientStateMachine.mMsgWhats).contains(CONNECTION_STATE_CHANGED);
        assertThat(mBassClientStateMachine.mMsgObj).isEqualTo(newState);
        mBassClientStateMachine.mMsgWhats.clear();
    }

    @Test
    public void gattCallbackOnConnectionStateChanged_changedToDisconnected()
            throws InterruptedException {
        initToConnectingState();
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        allowConnection(false);
        int status = STATE_CONNECTING;
        int newState = STATE_DISCONNECTED;
        cb.onConnectionStateChange(null, status, newState);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        assertThat(mBassClientStateMachine.mMsgWhats).contains(CONNECTION_STATE_CHANGED);
        assertThat(mBassClientStateMachine.mMsgObj).isEqualTo(newState);
        mBassClientStateMachine.mMsgWhats.clear();
    }

    @Test
    public void gattCallbackOnServicesDiscovered() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        // Do nothing if mDiscoveryInitiated is false.
        mBassClientStateMachine.mDiscoveryInitiated = false;
        int status = GATT_FAILURE;
        cb.onServicesDiscovered(null, status);

        verify(btGatt, never()).requestMtu(anyInt());

        // Do nothing if status is not GATT_SUCCESS.
        mBassClientStateMachine.mDiscoveryInitiated = true;
        status = GATT_FAILURE;
        cb.onServicesDiscovered(null, status);

        verify(btGatt, never()).requestMtu(anyInt());
        verify(callbacks).notifyBassStateSetupFailed(eq(mBassClientStateMachine.getDevice()));
        assertThat(mBassClientStateMachine.isBassStateReady()).isEqualTo(false);

        // call requestMtu() if status is GATT_SUCCESS.
        mBassClientStateMachine.mDiscoveryInitiated = true;
        status = GATT_SUCCESS;
        cb.onServicesDiscovered(null, status);

        verify(btGatt).requestMtu(anyInt());
    }

    /** This also tests BassClientStateMachine#processBroadcastReceiverState. */
    @Test
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RECEIVE_STATE_PROCESSING_REFACTOR)
    public void gattCallbackOnCharacteristicReadObsolete() {
        mBassClientStateMachine.mShouldHandleMessage = false;
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        BluetoothGattDescriptor desc = Mockito.mock(BluetoothGattDescriptor.class);
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        // Characteristic read success with null value
        when(characteristic.getValue()).thenReturn(null);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        verify(characteristic, never()).getDescriptor(any());

        // Characteristic read failed and mBluetoothGatt is null.
        mBassClientStateMachine.mBluetoothGatt = null;
        cb.onCharacteristicRead(null, characteristic, GATT_FAILURE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        assertThat(mBassClientStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
        assertThat(mBassClientStateMachine.mMsgAgr1).isEqualTo(GATT_FAILURE);
        mBassClientStateMachine.mMsgWhats.clear();

        // Characteristic read failed and mBluetoothGatt is not null.
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        when(characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)).thenReturn(desc);
        cb.onCharacteristicRead(null, characteristic, GATT_FAILURE);

        verify(btGatt).setCharacteristicNotification(any(), anyBoolean());
        verify(btGatt).writeDescriptor(desc);
        verify(desc).setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

        // Tests for processBroadcastReceiverState
        int sourceId = 1;
        byte[] value = new byte[] {};
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;
        mBassClientStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        mBassClientStateMachine.mPendingSourceId = (byte) sourceId;
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getInstanceId()).thenReturn(sourceId);

        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        ArgumentCaptor<BluetoothLeBroadcastReceiveState> receiveStateCaptor =
                ArgumentCaptor.forClass(BluetoothLeBroadcastReceiveState.class);
        verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);

        mBassClientStateMachine.mPendingOperation = 0;
        mBassClientStateMachine.mPendingSourceId = 0;
        sourceId = 2; // mNextId would become 2
        when(characteristic.getInstanceId()).thenReturn(sourceId);

        Mockito.clearInvocations(callbacks);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);

        mBassClientStateMachine.mPendingMetadata = createBroadcastMetadata();
        sourceId = 1;
        value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(mSourceTestDevice)[5],
                    Utils.getByteAddress(mSourceTestDevice)[4],
                    Utils.getByteAddress(mSourceTestDevice)[3],
                    Utils.getByteAddress(mSourceTestDevice)[2],
                    Utils.getByteAddress(mSourceTestDevice)[1],
                    Utils.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    0x00,
                    0x00,
                    0x00, // broadcastIdBytes
                    (byte) BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_NO_PAST,
                    (byte) BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE,
                    // 16 bytes badBroadcastCode
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01, // numSubGroups
                    // SubGroup #1
                    0x00,
                    0x00,
                    0x00,
                    0x00, // audioSyncIndex
                    0x02, // metaDataLength
                    0x00,
                    0x00, // metadata
                };
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getInstanceId()).thenReturn(sourceId);

        Mockito.clearInvocations(callbacks);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        verify(callbacks).notifySourceAdded(any(), any(), anyInt());
        verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // set some values for covering more lines of processPASyncState()
        mBassClientStateMachine.mPendingMetadata = null;
        mBassClientStateMachine.mSetBroadcastCodePending = true;
        mBassClientStateMachine.mIsPendingRemove = true;
        value[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX] =
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST;
        value[BassConstants.BCAST_RCVR_STATE_ENC_STATUS_IDX] =
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED;
        value[35] = 0; // set metaDataLength of subgroup #1 0
        PeriodicAdvertisementResult paResult = Mockito.mock(PeriodicAdvertisementResult.class);
        when(characteristic.getValue()).thenReturn(value);
        when(mBassClientService.getPeriodicAdvertisementResult(any(), anyInt()))
                .thenReturn(paResult);
        int syncHandle = 100;
        when(paResult.getSyncHandle()).thenReturn(syncHandle);

        Mockito.clearInvocations(callbacks);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        int serviceData = 0x000000FF & sourceId;
        serviceData = serviceData << 8;
        // advA matches EXT_ADV_ADDRESS
        // also matches source address (as we would have written)
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_EXT_ADV_ADDRESS);
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS);
        verify(mMethodProxy)
                .periodicAdvertisingManagerTransferSync(
                        any(), any(), eq(serviceData), eq(syncHandle));

        verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);
        assertThat(mBassClientStateMachine.mMsgWhats).contains(REMOVE_BCAST_SOURCE);

        mBassClientStateMachine.mIsPendingRemove = null;
        // set some values for covering more lines of processPASyncState()
        mBassClientStateMachine.mPendingMetadata = createBroadcastMetadata();
        for (int i = 0; i < BassConstants.BCAST_RCVR_STATE_SRC_ADDR_SIZE; ++i) {
            value[BassConstants.BCAST_RCVR_STATE_SRC_ADDR_START_IDX + i] = 0x00;
        }
        when(mBassClientService.getPeriodicAdvertisementResult(any(), anyInt())).thenReturn(null);
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(true);
        when(characteristic.getValue()).thenReturn(value);
        mBassClientStateMachine.mPendingSourceToSwitch = mBassClientStateMachine.mPendingMetadata;

        Mockito.clearInvocations(callbacks);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        verify(callbacks)
                .notifySourceRemoved(
                        any(), anyInt(), eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
        verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);
    }

    @Test
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RECEIVE_STATE_PROCESSING_REFACTOR)
    public void gattCallbackOnCharacteristicChangedObsolete() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 1;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);
        when(characteristic.getValue()).thenReturn(null);

        cb.onCharacteristicChanged(null, characteristic);
        verify(characteristic, atLeast(1)).getUuid();
        verify(characteristic).getValue();
        verify(callbacks, never()).notifyReceiveStateChanged(any(), anyInt(), any());
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 1;
        Mockito.clearInvocations(characteristic);
        when(characteristic.getValue()).thenReturn(new byte[] {});
        cb.onCharacteristicChanged(null, characteristic);
        verify(characteristic, atLeast(1)).getUuid();
        verify(characteristic, atLeast(1)).getValue();

        ArgumentCaptor<BluetoothLeBroadcastReceiveState> receiveStateCaptor =
                ArgumentCaptor.forClass(BluetoothLeBroadcastReceiveState.class);
        verify(callbacks).notifyReceiveStateChanged(any(), anyInt(), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RECEIVE_STATE_PROCESSING_REFACTOR)
    public void gattCallbackOnCharacteristicRead() {
        mBassClientStateMachine.mShouldHandleMessage = false;
        mBassClientStateMachine.connectGatt(true);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        BluetoothGattDescriptor desc = Mockito.mock(BluetoothGattDescriptor.class);
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;

        // Characteristic read success with null value
        when(characteristic.getValue()).thenReturn(null);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        InOrder inOrderCharacteristic = inOrder(characteristic);
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic).getValue();
        InOrder inOrderCallbacks = inOrder(callbacks);
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());
        verify(characteristic, never()).getDescriptor(any());

        // Characteristic read failed and mBluetoothGatt is null.
        mBassClientStateMachine.mBluetoothGatt = null;
        cb.onCharacteristicRead(null, characteristic, GATT_FAILURE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic, never()).getUuid();
        inOrderCharacteristic.verify(characteristic, never()).getValue();
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());
        assertThat(mBassClientStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
        assertThat(mBassClientStateMachine.mMsgAgr1).isEqualTo(GATT_FAILURE);
        mBassClientStateMachine.mMsgWhats.clear();

        // Characteristic read failed and mBluetoothGatt is not null.
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        when(characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)).thenReturn(desc);
        cb.onCharacteristicRead(null, characteristic, GATT_FAILURE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic, never()).getUuid();
        inOrderCharacteristic.verify(characteristic, never()).getValue();
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());
        verify(btGatt).setCharacteristicNotification(any(), anyBoolean());
        verify(btGatt).writeDescriptor(desc);
        verify(desc).setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

        // Tests for processBroadcastReceiverState

        // Empty value without any previous read/change
        when(characteristic.getValue()).thenReturn(new byte[] {});
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(4)).getValue();
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());

        // Read first time first characteristic
        int sourceId = 1;
        int instanceId = 1234;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(mSourceTestDevice)[5],
                    Utils.getByteAddress(mSourceTestDevice)[4],
                    Utils.getByteAddress(mSourceTestDevice)[3],
                    Utils.getByteAddress(mSourceTestDevice)[2],
                    Utils.getByteAddress(mSourceTestDevice)[1],
                    Utils.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    0x00,
                    0x00,
                    0x00, // broadcastIdBytes
                    (byte) BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    (byte) BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE,
                    // 16 bytes badBroadcastCode
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01, // numSubGroups
                    // SubGroup #1
                    0x00,
                    0x00,
                    0x00,
                    0x00, // audioSyncIndex
                    0x02, // metaDataLength
                    0x00,
                    0x00, // metadata
                };
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getInstanceId()).thenReturn(instanceId);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(4)).getValue();
        ArgumentCaptor<BluetoothLeBroadcastReceiveState> receiveStateCaptor =
                ArgumentCaptor.forClass(BluetoothLeBroadcastReceiveState.class);
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Read first time second (last) characteristic
        int sourceId2 = 2;
        int instanceId2 = 4321;
        value[BassConstants.BCAST_RCVR_STATE_SRC_ID_IDX] = (byte) sourceId2;
        when(characteristic.getInstanceId()).thenReturn(instanceId2);
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(4)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId2), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);
    }

    /** This also tests BassClientStateMachine#processBroadcastReceiverState. */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RECEIVE_STATE_PROCESSING_REFACTOR)
    public void gattCallbackOnCharacteristicChanged() {
        mBassClientStateMachine.connectGatt(true);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 1;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);

        // Null value
        when(characteristic.getValue()).thenReturn(null);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        InOrder inOrderCharacteristic = inOrder(characteristic);
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic).getValue();
        InOrder inOrderCallbacks = inOrder(callbacks);
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());

        // Empty value without any previous read/change
        when(characteristic.getValue()).thenReturn(new byte[] {});
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());

        // Sync value, first time
        int sourceId = 1;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(mSourceTestDevice)[5],
                    Utils.getByteAddress(mSourceTestDevice)[4],
                    Utils.getByteAddress(mSourceTestDevice)[3],
                    Utils.getByteAddress(mSourceTestDevice)[2],
                    Utils.getByteAddress(mSourceTestDevice)[1],
                    Utils.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    0x00,
                    0x00,
                    0x00, // broadcastIdBytes
                    (byte) BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    (byte) BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE,
                    // 16 bytes badBroadcastCode
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01, // numSubGroups
                    // SubGroup #1
                    0x00,
                    0x00,
                    0x00,
                    0x00, // audioSyncIndex
                    0x02, // metaDataLength
                    0x00,
                    0x00, // metadata
                };
        when(characteristic.getValue()).thenReturn(value);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        ArgumentCaptor<BluetoothLeBroadcastReceiveState> receiveStateCaptor =
                ArgumentCaptor.forClass(BluetoothLeBroadcastReceiveState.class);
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Empty value to indicates removing source from device by remote
        when(characteristic.getValue()).thenReturn(new byte[] {});
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceRemoved(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);

        // Sync value again
        mBassClientStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        when(characteristic.getValue()).thenReturn(value);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Empty value to indicates removing source from device by local app
        mBassClientStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        when(characteristic.getValue()).thenReturn(new byte[] {});
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceRemoved(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);

        // Sync value again
        mBassClientStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        when(characteristic.getValue()).thenReturn(value);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Empty value to indicates removing source from device by stack (source switch)
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mBassClientStateMachine.mPendingSourceToSwitch = metadata;
        when(characteristic.getValue()).thenReturn(new byte[] {});
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceRemoved(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mEmptyTestDevice);
        assertThat(mBassClientStateMachine.mMsgWhats).contains(ADD_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mMsgObj).isEqualTo(metadata);

        // Sync value again
        mBassClientStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        when(characteristic.getValue()).thenReturn(value);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic, times(2)).getValue();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - PA SyncInfo Request
        value[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX] =
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST;
        PeriodicAdvertisementResult paResult = Mockito.mock(PeriodicAdvertisementResult.class);
        when(mBassClientService.getPeriodicAdvertisementResult(any(), anyInt()))
                .thenReturn(paResult);
        int syncHandle = 100;
        when(paResult.getSyncHandle()).thenReturn(syncHandle);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        int serviceData = 0x000000FF & sourceId;
        serviceData = serviceData << 8;
        // advA matches EXT_ADV_ADDRESS
        // also matches source address (as we would have written)
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_EXT_ADV_ADDRESS);
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS);
        verify(mMethodProxy)
                .periodicAdvertisingManagerTransferSync(
                        any(), any(), eq(serviceData), eq(syncHandle));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - PA SyncInfo Request, local broadcast
        mBassClientStateMachine.mPendingMetadata = createBroadcastMetadata();
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(true);
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        serviceData = 0x000000FF & sourceId;
        serviceData = serviceData << 8;
        // Address we set in the Source Address can differ from the address in the air
        serviceData = serviceData | BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS;
        verify(mMethodProxy)
                .periodicAdvertisingManagerTransferSetInfo(
                        any(), any(), eq(serviceData), anyInt(), any());
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - Broadcast Code
        value[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX] =
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED;
        value[BassConstants.BCAST_RCVR_STATE_ENC_STATUS_IDX] =
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED;
        mBassClientStateMachine.mSetBroadcastCodePending = true;
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        assertThat(mBassClientStateMachine.mMsgWhats).contains(SET_BCAST_CODE);
        assertThat(mBassClientStateMachine.mMsgAgr1)
                .isEqualTo(BassClientStateMachine.ARGTYPE_RCVSTATE);
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - Pending Remove
        value[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX] =
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        mBassClientStateMachine.mIsPendingRemove = true;
        cb.onCharacteristicChanged(null, characteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        assertThat(mBassClientStateMachine.mMsgWhats).contains(REMOVE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mMsgAgr1).isEqualTo(sourceId);
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);
    }

    @Test
    public void gattCharacteristicWrite() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;

        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT);

        cb.onCharacteristicWrite(null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
    }

    @Test
    public void gattCallbackOnDescriptorWrite() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        BluetoothGattDescriptor descriptor = Mockito.mock(BluetoothGattDescriptor.class);
        when(descriptor.getUuid()).thenReturn(BassConstants.CLIENT_CHARACTERISTIC_CONFIG);

        cb.onDescriptorWrite(null, descriptor, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
    }

    @Test
    public void gattCallbackOnMtuChanged() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        mBassClientStateMachine.mMTUChangeRequested = true;

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        // Verify notifyBassStateSetupFailed is called
        cb.onMtuChanged(null, 10, GATT_FAILURE);
        verify(callbacks).notifyBassStateSetupFailed(eq(mBassClientStateMachine.getDevice()));
        assertThat(mBassClientStateMachine.mMTUChangeRequested).isTrue();
        assertThat(mBassClientStateMachine.isBassStateReady()).isEqualTo(false);

        cb.onMtuChanged(null, 10, GATT_SUCCESS);
        assertThat(mBassClientStateMachine.mMTUChangeRequested).isTrue();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        cb.onMtuChanged(null, 10, GATT_SUCCESS);
        assertThat(mBassClientStateMachine.mMTUChangeRequested).isFalse();
    }

    @Test
    public void sendConnectMessage_inDisconnectedState() {
        initToDisconnectedState();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendDisconnectMessage_inDisconnectedState() {
        initToDisconnectedState();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        mBassClientStateMachine.sendMessage(DISCONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendStateChangedMessage_inDisconnectedState() {
        initToDisconnectedState();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        Message msgToConnectingState =
                mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToConnectingState.obj = STATE_CONNECTING;

        mBassClientStateMachine.sendMessage(msgToConnectingState);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        Message msgToConnectedState =
                mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToConnectedState.obj = STATE_CONNECTED;
        sendMessageAndVerifyTransition(msgToConnectedState, BassClientStateMachine.Connected.class);
    }

    @Test
    public void sendOtherMessages_inDisconnectedState_doesNotChangeState() {
        initToDisconnectedState();

        mBassClientStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        mBassClientStateMachine.sendMessage(-1);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendConnectMessages_inConnectingState_doesNotChangeState() {
        initToConnectingState();

        mBassClientStateMachine.sendMessage(CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendDisconnectMessages_inConnectingState_defersMessage() {
        initToConnectingState();

        mBassClientStateMachine.sendMessage(DISCONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(DISCONNECT)).isTrue();
    }

    @Test
    public void sendReadBassCharacteristicsMessage_inConnectingState_defersMessage() {
        initToConnectingState();

        mBassClientStateMachine.sendMessage(READ_BASS_CHARACTERISTICS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(READ_BASS_CHARACTERISTICS))
                .isTrue();
    }

    @Test
    public void sendStateChangedToNonConnectedMessage_inConnectingState_movesToDisconnected() {
        initToConnectingState();

        Message msg = mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msg.obj = STATE_CONNECTING;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mBassClientStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void sendStateChangedToConnectedMessage_inConnectingState_movesToConnected() {
        initToConnectingState();

        Message msg = mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msg.obj = STATE_CONNECTED;
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Connected.class);
    }

    @Test
    public void sendConnectTimeMessage_inConnectingState() {
        initToConnectingState();

        Message timeoutWithDifferentDevice =
                mBassClientStateMachine.obtainMessage(CONNECT_TIMEOUT, getTestDevice(230));
        mBassClientStateMachine.sendMessage(timeoutWithDifferentDevice);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        Message msg = mBassClientStateMachine.obtainMessage(CONNECT_TIMEOUT, mTestDevice);
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mBassClientStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void sendInvalidMessage_inConnectingState_doesNotChangeState() {
        initToConnectingState();
        mBassClientStateMachine.sendMessage(-1);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendConnectMessage_inConnectedState() {
        initToConnectedState();

        mBassClientStateMachine.sendMessage(CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendDisconnectMessage_inConnectedState() {
        initToConnectedState();

        mBassClientStateMachine.mBluetoothGatt = null;

        mBassClientStateMachine.sendMessage(DISCONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(DISCONNECT),
                BassClientStateMachine.Disconnected.class);
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendStateChangedMessage_inConnectedState() {
        initToConnectedState();

        Message connectedMsg = mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        connectedMsg.obj = STATE_CONNECTED;

        mBassClientStateMachine.sendMessage(connectedMsg);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        Message noneConnectedMsg = mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        noneConnectedMsg.obj = STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(noneConnectedMsg, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mBassClientStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void sendReadBassCharacteristicsMessage_inConnectedState() {
        initToConnectedState();
        BluetoothGattCharacteristic gattCharacteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);

        mBassClientStateMachine.sendMessage(READ_BASS_CHARACTERISTICS, gattCharacteristic);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        READ_BASS_CHARACTERISTICS, gattCharacteristic),
                BassClientStateMachine.ConnectedProcessing.class);
    }

    @Test
    public void sendStartScanOffloadMessage_inConnectedState() {
        initToConnectedState();
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        mBassClientStateMachine.sendMessage(START_SCAN_OFFLOAD);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(START_SCAN_OFFLOAD),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(btGatt).writeCharacteristic(scanControlPoint);
        verify(scanControlPoint).setValue(REMOTE_SCAN_START);
    }

    @Test
    public void sendStopScanOffloadMessage_inConnectedState() {
        initToConnectedState();
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;

        mBassClientStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(STOP_SCAN_OFFLOAD),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(btGatt).writeCharacteristic(scanControlPoint);
        verify(scanControlPoint).setValue(REMOTE_SCAN_STOP);
    }

    @Test
    public void sendInvalidMessage_inConnectedState_doesNotChangeState() {
        initToConnectedState();

        mBassClientStateMachine.sendMessage(-1);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendAddBcastSourceMessage_inConnectedState() {
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(true);
        mBassClientStateMachine.sendMessage(ADD_BCAST_SOURCE, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        verify(mBassClientService).getCallbacks();
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mBassClientStateMachine.mPendingSourceToSwitch = metadata;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
        assertThat(mBassClientStateMachine.mPendingSourceToSwitch).isNull();
    }

    @Test
    public void sendSwitchSourceMessage_inConnectedState() {
        initToConnectedState();
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        Integer sourceId = 1;

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mBassClientStateMachine.sendMessage(SWITCH_BCAST_SOURCE, sourceId, 0, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.mPendingSourceToSwitch).isEqualTo(metadata);
    }

    @Test
    public void sendUpdateBcastSourceMessage_inConnectedState() {
        initToConnectedState();
        mBassClientStateMachine.connectGatt(true);
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;

        // Prepare mBluetoothLeBroadcastReceiveStates for test
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);
        int sourceId = 1;
        int paSync = BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(mSourceTestDevice)[5],
                    Utils.getByteAddress(mSourceTestDevice)[4],
                    Utils.getByteAddress(mSourceTestDevice)[3],
                    Utils.getByteAddress(mSourceTestDevice)[2],
                    Utils.getByteAddress(mSourceTestDevice)[1],
                    Utils.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    0x00,
                    0x00,
                    0x00, // broadcastIdBytes
                    (byte) paSync,
                    (byte) BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE,
                    // 16 bytes badBroadcastCode
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01, // numSubGroups
                    // SubGroup #1
                    0x00,
                    0x00,
                    0x00,
                    0x00, // audioSyncIndex
                    0x02, // metaDataLength
                    0x00,
                    0x00, // metadata
                };
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getInstanceId()).thenReturn(sourceId);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);
        mBassClientStateMachine.mGattCallback.onCharacteristicRead(
                null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        when(mBassClientService.getPeriodicAdvertisementResult(any(), anyInt())).thenReturn(null);

        mBassClientStateMachine.sendMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        PeriodicAdvertisementResult paResult = Mockito.mock(PeriodicAdvertisementResult.class);
        when(mBassClientService.getPeriodicAdvertisementResult(any(), anyInt()))
                .thenReturn(paResult);
        when(mBassClientService.getBase(anyInt())).thenReturn(null);
        Mockito.clearInvocations(callbacks);

        mBassClientStateMachine.sendMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0xFF, // VENDOR_CODEC
                    (byte) 0x0A,
                    (byte) 0xAB,
                    (byte) 0xBC,
                    (byte) 0xCD,
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x00, // mMetaDataLength
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        when(mBassClientService.getBase(anyInt())).thenReturn(data);
        Mockito.clearInvocations(callbacks);

        mBassClientStateMachine.sendMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(callbacks).notifySourceModifyFailed(any(), anyInt(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;
        mBassClientStateMachine.mPendingOperation = 0;
        mBassClientStateMachine.mPendingSourceId = 0;
        mBassClientStateMachine.mPendingMetadata = null;
        Mockito.clearInvocations(callbacks);

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE, sourceId, paSync, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(sourceId);
        assertThat(mBassClientStateMachine.mPendingMetadata).isEqualTo(metadata);
    }

    @Test
    public void sendSetBcastCodeMessage_inConnectedState() {
        initToConnectedState();
        mBassClientStateMachine.connectGatt(true);
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        // Prepare mBluetoothLeBroadcastReceiveStates with metadata for test
        mBassClientStateMachine.mShouldHandleMessage = false;
        int sourceId = 1;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(mSourceTestDevice)[5],
                    Utils.getByteAddress(mSourceTestDevice)[4],
                    Utils.getByteAddress(mSourceTestDevice)[3],
                    Utils.getByteAddress(mSourceTestDevice)[2],
                    Utils.getByteAddress(mSourceTestDevice)[1],
                    Utils.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    0x00,
                    0x00,
                    0x00, // broadcastIdBytes
                    (byte) BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    (byte) BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                    0x01, // numSubGroups
                    // SubGroup #1
                    0x00,
                    0x00,
                    0x00,
                    0x00, // audioSyncIndex
                    0x02, // metaDataLength
                    0x00,
                    0x00, // metadata
                };
        mBassClientStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        mBassClientStateMachine.mPendingSourceId = (byte) sourceId;
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getInstanceId()).thenReturn(sourceId);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);

        mBassClientStateMachine.mGattCallback.onCharacteristicRead(
                null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        mBassClientStateMachine.mPendingMetadata = createBroadcastMetadata();
        mBassClientStateMachine.mGattCallback.onCharacteristicRead(
                null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        mBassClientStateMachine.mShouldHandleMessage = true;

        BluetoothLeBroadcastReceiveState recvState =
                new BluetoothLeBroadcastReceiveState(
                        2,
                        BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                        mAdapter.getRemoteLeDevice(
                                "00:00:00:00:00:00", BluetoothDevice.ADDRESS_TYPE_PUBLIC),
                        0,
                        0,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                        null,
                        0,
                        Arrays.asList(new Long[0]),
                        Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
        mBassClientStateMachine.mSetBroadcastCodePending = false;
        mBassClientStateMachine.sendMessage(SET_BCAST_CODE, recvState);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.mSetBroadcastCodePending).isTrue();

        recvState =
                new BluetoothLeBroadcastReceiveState(
                        sourceId,
                        BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                        mAdapter.getRemoteLeDevice(
                                "00:00:00:00:00:00", BluetoothDevice.ADDRESS_TYPE_PUBLIC),
                        0,
                        0,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                        null,
                        0,
                        Arrays.asList(new Long[0]),
                        Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
        mBassClientStateMachine.sendMessage(SET_BCAST_CODE, recvState);
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(SET_BCAST_CODE, recvState),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(SET_BCAST_CODE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(sourceId);
        verify(btGatt).writeCharacteristic(any());
        verify(scanControlPoint).setValue(any(byte[].class));
    }

    @Test
    public void receiveSinkReceiveState_inConnectedState() {
        int sourceId = 1;

        initToConnectedState();
        mBassClientStateMachine.connectGatt(true);
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                sourceId,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                0x0L);
    }

    @Test
    public void sendRemoveBcastSourceMessage_inConnectedState() {
        initToConnectedState();
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        int sid = 10;
        mBassClientStateMachine.sendMessage(REMOVE_BCAST_SOURCE, sid);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(callbacks).notifySourceRemoveFailed(any(), anyInt(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(REMOVE_BCAST_SOURCE, sid),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(REMOVE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(sid);
    }

    @Test
    public void sendInitiatePaSyncTransferMessage_inConnectedState() {
        initToConnectedState();
        int syncHandle = 1234;
        int sourceId = 4321;

        mBassClientStateMachine.sendMessage(INITIATE_PA_SYNC_TRANSFER, syncHandle, sourceId);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        int serviceData = 0x000000FF & sourceId;
        serviceData = serviceData << 8;
        // advA matches EXT_ADV_ADDRESS
        // also matches source address (as we would have written)
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_EXT_ADV_ADDRESS);
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS);
        verify(mMethodProxy)
                .periodicAdvertisingManagerTransferSync(
                        any(), any(), eq(serviceData), eq(syncHandle));
    }

    @Test
    public void sendConnectMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        mBassClientStateMachine.sendMessage(CONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendDisconnectMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        // Mock instance of btGatt was created in initToConnectedProcessingState().
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                mBassClientStateMachine.mBluetoothGatt;

        mBassClientStateMachine.mBluetoothGatt = null;
        mBassClientStateMachine.sendMessage(DISCONNECT);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(DISCONNECT),
                BassClientStateMachine.Disconnected.class);
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendStateChangedMessage_inConnectedProcessingState() {
        initToConnectedProcessingState();

        Message msgToConnectedState =
                mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToConnectedState.obj = STATE_CONNECTED;

        mBassClientStateMachine.sendMessage(msgToConnectedState);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        Message msgToNoneConnectedState =
                mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToNoneConnectedState.obj = STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(
                msgToNoneConnectedState, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mBassClientStateMachine.mBluetoothGatt).isNull();
    }

    /** This also tests BassClientStateMachine#sendPendingCallbacks */
    @Test
    public void sendGattTxnProcessedMessage_inConnectedProcessingState() {
        initToConnectedProcessingState();
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        // Test sendPendingCallbacks(START_SCAN_OFFLOAD, ERROR_UNKNOWN)
        mBassClientStateMachine.mPendingOperation = START_SCAN_OFFLOAD;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);

        // Test sendPendingCallbacks(ADD_BCAST_SOURCE, ERROR_UNKNOWN)
        moveConnectedStateToConnectedProcessingState();
        mBassClientStateMachine.mPendingMetadata = createBroadcastMetadata();
        mBassClientStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        // Test sendPendingCallbacks(UPDATE_BCAST_SOURCE, ERROR_UNKNOWN)
        moveConnectedStateToConnectedProcessingState();
        mBassClientStateMachine.mPendingOperation = UPDATE_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        verify(callbacks).notifySourceModifyFailed(any(), anyInt(), anyInt());

        // Test sendPendingCallbacks(REMOVE_BCAST_SOURCE, ERROR_UNKNOWN)
        moveConnectedStateToConnectedProcessingState();
        mBassClientStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        verify(callbacks).notifySourceRemoveFailed(any(), anyInt(), anyInt());

        // Test sendPendingCallbacks(SET_BCAST_CODE, REASON_LOCAL_APP_REQUEST)
        moveConnectedStateToConnectedProcessingState();
        mBassClientStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        // Nothing to verify more

        // Test sendPendingCallbacks(SET_BCAST_CODE, REASON_LOCAL_APP_REQUEST)
        moveConnectedStateToConnectedProcessingState();
        mBassClientStateMachine.mPendingOperation = -1;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        // Nothing to verify more
    }

    @Test
    public void sendGattTxnTimeoutMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        mBassClientStateMachine.mPendingOperation = SET_BCAST_CODE;
        mBassClientStateMachine.mPendingSourceId = 0;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_TIMEOUT, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(-1);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(-1);
    }

    @Test
    public void sendMessageForDeferring_inConnectedProcessingState_defersMessage() {
        initToConnectedProcessingState();

        mBassClientStateMachine.sendMessage(READ_BASS_CHARACTERISTICS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(READ_BASS_CHARACTERISTICS))
                .isTrue();

        mBassClientStateMachine.sendMessage(START_SCAN_OFFLOAD);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(START_SCAN_OFFLOAD)).isTrue();

        mBassClientStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(STOP_SCAN_OFFLOAD)).isTrue();

        mBassClientStateMachine.sendMessage(ADD_BCAST_SOURCE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(ADD_BCAST_SOURCE)).isTrue();

        mBassClientStateMachine.sendMessage(SET_BCAST_CODE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(SET_BCAST_CODE)).isTrue();

        mBassClientStateMachine.sendMessage(REMOVE_BCAST_SOURCE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(REMOVE_BCAST_SOURCE)).isTrue();

        mBassClientStateMachine.sendMessage(SWITCH_BCAST_SOURCE);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(SWITCH_BCAST_SOURCE)).isTrue();

        mBassClientStateMachine.sendMessage(INITIATE_PA_SYNC_TRANSFER);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        assertThat(mBassClientStateMachine.hasDeferredMessagesSuper(INITIATE_PA_SYNC_TRANSFER))
                .isTrue();
    }

    @Test
    public void sendInvalidMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        mBassClientStateMachine.sendMessage(-1);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void dump_doesNotCrash() {
        mBassClientStateMachine.dump(new StringBuilder());
    }

    @Test
    public void sendAddBcastSourceMessage_NoResponseWrite() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        cb.onMtuChanged(null, 250, GATT_SUCCESS);
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(true);
        mBassClientStateMachine.sendMessage(ADD_BCAST_SOURCE, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        verify(mBassClientService).getCallbacks();
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
    }

    @Test
    public void sendAddBcastSourceMessage_LongWrite() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        cb.onMtuChanged(null, 23, GATT_SUCCESS);
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(true);
        mBassClientStateMachine.sendMessage(ADD_BCAST_SOURCE, metadata);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        verify(mBassClientService).getCallbacks();
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
    }

    private void initToDisconnectedState() {
        allowConnection(true);
        allowConnectGatt(true);
        assertThat(mBassClientStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
    }

    @Test
    public void cancelPendingAddBcastSourceMessage_inConnectedState() {
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(true);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());

        /* Verify if there is pending add source operation */
        assertThat(mBassClientStateMachine.hasPendingSourceOperation(metadata.getBroadcastId()))
                .isTrue();

        assertThat(mBassClientStateMachine.mMsgWhats).contains(CANCEL_PENDING_SOURCE_OPERATION);
        assertThat(mBassClientStateMachine.mMsgAgr1).isEqualTo(TEST_BROADCAST_ID);

        /* Inject a cancel pending source operation event */
        Message msg = mBassClientStateMachine.obtainMessage(CANCEL_PENDING_SOURCE_OPERATION);
        msg.arg1 = metadata.getBroadcastId();
        mBassClientStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        /* Verify if pending add source operation is canceled */
        assertThat(mBassClientStateMachine.hasPendingSourceOperation(metadata.getBroadcastId()))
                .isFalse();
    }

    @Test
    public void cancelPendingUpdateBcastSourceMessage_inConnectedState() {
        initToConnectedState();
        mBassClientStateMachine.connectGatt(true);
        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;

        // Prepare mBluetoothLeBroadcastReceiveStates for test
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);
        int sourceId = 1;
        int paSync = BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(mSourceTestDevice)[5],
                    Utils.getByteAddress(mSourceTestDevice)[4],
                    Utils.getByteAddress(mSourceTestDevice)[3],
                    Utils.getByteAddress(mSourceTestDevice)[2],
                    Utils.getByteAddress(mSourceTestDevice)[1],
                    Utils.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    (byte) (TEST_BROADCAST_ID & 0xFF),
                    0x00,
                    0x00, // broadcastIdBytes
                    (byte) paSync,
                    (byte) BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE,
                    // 16 bytes badBroadcastCode
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01, // numSubGroups
                    // SubGroup #1
                    0x00,
                    0x00,
                    0x00,
                    0x00, // audioSyncIndex
                    0x02, // metaDataLength
                    0x00,
                    0x00, // metadata
                };
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getValue()).thenReturn(value);
        when(characteristic.getInstanceId()).thenReturn(sourceId);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);
        mBassClientStateMachine.mGattCallback.onCharacteristicRead(
                null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE, sourceId, paSync, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());

        /* Verify if there is pending add source operation */
        assertThat(mBassClientStateMachine.hasPendingSourceOperation(metadata.getBroadcastId()))
                .isTrue();

        assertThat(mBassClientStateMachine.mMsgWhats).contains(CANCEL_PENDING_SOURCE_OPERATION);
        assertThat(mBassClientStateMachine.mMsgAgr1).isEqualTo(TEST_BROADCAST_ID);

        /* Inject a cancel pending source operation event */
        Message msg = mBassClientStateMachine.obtainMessage(CANCEL_PENDING_SOURCE_OPERATION);
        msg.arg1 = metadata.getBroadcastId();
        mBassClientStateMachine.sendMessage(msg);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        /* Verify if pending add source operation is canceled */
        assertThat(mBassClientStateMachine.hasPendingSourceOperation(metadata.getBroadcastId()))
                .isFalse();
    }

    @Test
    public void receiveSinkReceiveStateChange_logSyncMetricsWhenSyncNoPast() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_NO_PAST,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                0x0L);
        // Verify broadcast audio session is logged when pa no past
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSync(
                        eq(mTestDevice),
                        eq(TEST_BROADCAST_ID),
                        eq(false),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x5)); // STATS_SYNC_PA_NO_PAST
    }

    @Test
    public void receiveSinkReceiveStateChange_logSyncMetricsWhenBigEncryptFailed() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_BAD_CODE,
                0x0L);
        // Verify broadcast audio session is logged when big encryption failed
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSync(
                        eq(mTestDevice),
                        eq(TEST_BROADCAST_ID),
                        eq(false),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x6)); // STATS_SYNC_BIG_DECRYPT_FAILED
    }

    @Test
    public void receiveSinkReceiveStateChange_logSyncMetricsWhenAudioSyncFailed() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG);
        // Verify broadcast audio session is logged when bis sync failed
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSync(
                        eq(mTestDevice),
                        eq(TEST_BROADCAST_ID),
                        eq(false),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x7)); // STATS_SYNC_AUDIO_SYNC_FAILED
    }

    @Test
    public void receiveSinkReceiveStateChange_logSyncMetricsWhenSourceRemoved() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        // Verify broadcast audio session is not reported, only update the status
        verify(mMetricsLogger, never())
                .logLeAudioBroadcastAudioSync(
                        any(), anyInt(), anyBoolean(), anyLong(), anyLong(), anyLong(), anyInt());

        // Update receive state to source removed
        generateBroadcastReceiveStatesAndVerify(
                mEmptyTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);

        // Verify broadcast audio session is logged when source removed
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSync(
                        eq(mTestDevice),
                        eq(TEST_BROADCAST_ID),
                        eq(false),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x3)); // STATS_SYNC_AUDIO_SYNC_SUCCESS
    }

    @Test
    public void sinkDisconnected_logSyncMetricsWhenSourceRemoved() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(DISCONNECT),
                BassClientStateMachine.Disconnected.class);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        // Verify broadcast audio session is logged when source removed
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSync(
                        eq(mTestDevice),
                        eq(TEST_BROADCAST_ID),
                        eq(false),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x3)); // STATS_SYNC_AUDIO_SYNC_SUCCESS
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_RESYNC_HELPER)
    public void sinkConnected_queueAddingSourceForReceiveStateReady() {
        mBassClientStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mBassClientStateMachine.mGattCallback;
        cb.onMtuChanged(null, 23, GATT_SUCCESS);
        initToConnectedState();

        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 1;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        // Initial receive state with empty source device
        generateBroadcastReceiveStatesAndVerify(
                mEmptyTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        // Verify notifyBassStateReady is called
        verify(callbacks).notifyBassStateReady(eq(mTestDevice));
        assertThat(mBassClientStateMachine.isBassStateReady()).isEqualTo(true);
    }

    @Test
    public void updateBroadcastSource_withoutMetadata() {
        int sourceId = 1;
        int paSync = BassConstants.PA_SYNC_DO_NOT_SYNC;

        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mBassClientStateMachine.mPendingOperation = 0;
        mBassClientStateMachine.mPendingSourceId = 0;
        mBassClientStateMachine.mPendingMetadata = null;

        // update source without metadata
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, null),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(sourceId);
    }

    @Test
    public void updateBroadcastSource_pendingSourceToRemove() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mBassClientStateMachine.mPendingMetadata = metadata;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE,
                        TEST_SOURCE_ID,
                        BassConstants.PA_SYNC_DO_NOT_SYNC,
                        metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);

        mBassClientStateMachine.mPendingOperation = 0;
        mBassClientStateMachine.mPendingSourceId = 0;
        // Verify not removing source when PA is still synced
        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(0);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(0);

        // Verify removing source when PA is unsynced
        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(REMOVE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
    }

    @Test
    public void updateBroadcastSource_withMetadataChanged() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mBassClientStateMachine.mPendingMetadata = metadata;

        // Verify pausing broadcast stream with updated metadata
        BluetoothLeBroadcastMetadata updatedMetadataPaused = getMetadataToPauseStream(metadata);
        byte[] valueBisPaused = convertMetadataToUpdateSourceByteArray(updatedMetadataPaused);

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE,
                        TEST_SOURCE_ID,
                        BassConstants.INVALID_PA_SYNC_VALUE,
                        updatedMetadataPaused),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
        verify(scanControlPoint).setValue(eq(valueBisPaused));

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        Mockito.clearInvocations(scanControlPoint);

        // Verify resuming broadcast stream with the original metadata
        byte[] valueBisResumed = convertMetadataToUpdateSourceByteArray(metadata);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE,
                        TEST_SOURCE_ID,
                        BassConstants.INVALID_PA_SYNC_VALUE,
                        metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mBassClientStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mBassClientStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
        verify(scanControlPoint).setValue(eq(valueBisResumed));

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);

        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        Mockito.clearInvocations(scanControlPoint);
    }

    private void initToConnectingState() {
        allowConnection(true);
        allowConnectGatt(true);
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(CONNECT),
                BassClientStateMachine.Connecting.class);
        Mockito.clearInvocations(mBassClientService);
    }

    private void initToConnectedState() {
        initToConnectingState();

        Message msg = mBassClientStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msg.obj = STATE_CONNECTED;
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Connected.class);
        Mockito.clearInvocations(mBassClientService);
    }

    private void initToConnectedProcessingState() {
        initToConnectedState();
        moveConnectedStateToConnectedProcessingState();
    }

    private void moveConnectedStateToConnectedProcessingState() {
        BluetoothGattCharacteristic gattCharacteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(
                        READ_BASS_CHARACTERISTICS, gattCharacteristic),
                BassClientStateMachine.ConnectedProcessing.class);
        Mockito.clearInvocations(mBassClientService);
    }

    private static boolean isConnectionIntentExpected(Class currentType, Class nextType) {
        if (currentType == nextType) {
            return false; // Same state, no intent expected
        }

        if ((currentType == BassClientStateMachine.ConnectedProcessing.class)
                || (nextType == BassClientStateMachine.ConnectedProcessing.class)) {
            return false; // ConnectedProcessing is an internal state that doesn't generate a
            // broadcast
        } else {
            return true; // All other state are generating broadcast
        }
    }

    @SafeVarargs
    private void verifyIntentSent(int timeout_ms, Matcher<Intent>... matchers) {
        verify(mBassClientService, timeout(timeout_ms))
                .sendBroadcast(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(BLUETOOTH_CONNECT),
                        any());
    }

    private <T> void sendMessageAndVerifyTransition(Message msg, Class<T> type) {
        Mockito.clearInvocations(mBassClientService);

        mBassClientStateMachine.sendMessage(msg);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());
        Class currentStateClass = mBassClientStateMachine.getCurrentState().getClass();
        if (isConnectionIntentExpected(currentStateClass, type)) {
            verifyIntentSent(
                    NO_TIMEOUT_MS,
                    hasAction(BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED),
                    hasExtra(
                            BluetoothProfile.EXTRA_STATE,
                            classTypeToConnectionState(currentStateClass)),
                    hasExtra(
                            BluetoothProfile.EXTRA_PREVIOUS_STATE,
                            classTypeToConnectionState(type)),
                    hasFlag(
                            Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND));
        }
        assertThat(mBassClientStateMachine.getCurrentState()).isInstanceOf(type);
    }

    private BluetoothLeBroadcastMetadata createBroadcastMetadata() {
        final String testMacAddress = "00:11:22:33:44:55";
        final int testAdvertiserSid = 1234;
        final int testPaSyncInterval = 100;
        final int testPresentationDelayMs = 345;

        BluetoothDevice testDevice =
                mAdapter.getRemoteLeDevice(testMacAddress, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(testDevice, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(testAdvertiserSid)
                        .setBroadcastId(TEST_BROADCAST_ID)
                        .setBroadcastCode(new byte[] {0x00, 0x01, 0x00, 0x02})
                        .setPaSyncInterval(testPaSyncInterval)
                        .setPresentationDelayMicros(testPresentationDelayMs);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    private static byte[] convertMetadataToUpdateSourceByteArray(
            BluetoothLeBroadcastMetadata metaData) {
        int numSubGroups = metaData.getSubgroups().size();

        byte[] res = new byte[UPDATE_SOURCE_FIXED_LENGTH + numSubGroups * 5];
        int offset = 0;
        // Opcode
        res[offset++] = OPCODE_UPDATE_SOURCE;
        // Source_ID
        res[offset++] = (byte) TEST_SOURCE_ID;
        // PA_Sync
        res[offset++] = (byte) (0x01);
        // PA_Interval
        res[offset++] = (byte) 0xFF;
        res[offset++] = (byte) 0xFF;
        // Num_Subgroups
        res[offset++] = (byte) numSubGroups;

        for (int i = 0; i < numSubGroups; i++) {
            int bisIndexValue = 0;
            for (BluetoothLeBroadcastChannel channel :
                    metaData.getSubgroups().get(i).getChannels()) {
                if (channel.isSelected()) {
                    if (channel.getChannelIndex() == 0) {
                        continue;
                    }
                    bisIndexValue |= 1 << (channel.getChannelIndex() - 1);
                }
            }
            // BIS_Sync
            res[offset++] = (byte) (bisIndexValue & 0x00000000000000FF);
            res[offset++] = (byte) ((bisIndexValue & 0x000000000000FF00) >>> 8);
            res[offset++] = (byte) ((bisIndexValue & 0x0000000000FF0000) >>> 16);
            res[offset++] = (byte) ((bisIndexValue & 0x00000000FF000000) >>> 24);
            // Metadata_Length; On Modify source, don't update any Metadata
            res[offset++] = 0;
        }
        return res;
    }

    private static BluetoothLeBroadcastMetadata getMetadataToPauseStream(
            BluetoothLeBroadcastMetadata metadata) {
        BluetoothLeBroadcastMetadata.Builder metadataToUpdateBuilder =
                new BluetoothLeBroadcastMetadata.Builder(metadata);

        List<BluetoothLeBroadcastSubgroup> updatedSubgroups = new ArrayList<>();
        for (BluetoothLeBroadcastSubgroup subgroup : metadata.getSubgroups()) {
            BluetoothLeBroadcastSubgroup.Builder subgroupBuilder =
                    new BluetoothLeBroadcastSubgroup.Builder(subgroup);

            List<BluetoothLeBroadcastChannel> updatedChannels = new ArrayList<>();
            for (BluetoothLeBroadcastChannel channel : subgroup.getChannels()) {
                BluetoothLeBroadcastChannel updatedChannel =
                        new BluetoothLeBroadcastChannel.Builder(channel).setSelected(false).build();
                updatedChannels.add(updatedChannel);
            }

            subgroupBuilder.clearChannel();
            for (BluetoothLeBroadcastChannel channel : updatedChannels) {
                subgroupBuilder.addChannel(channel);
            }

            updatedSubgroups.add(subgroupBuilder.build());
        }

        metadataToUpdateBuilder.clearSubgroup();
        for (BluetoothLeBroadcastSubgroup subgroup : updatedSubgroups) {
            metadataToUpdateBuilder.addSubgroup(subgroup);
        }

        return metadataToUpdateBuilder.build();
    }

    private void prepareInitialReceiveStateForGatt() {
        initToConnectedState();
        mBassClientStateMachine.connectGatt(true);

        mBassClientStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        when(mBassClientService.getCallbacks()).thenReturn(callbacks);

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        when(mBassClientService.isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class)))
                .thenReturn(false);
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mBassClientStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mBassClientStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mBassClientStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
        // Initial receive state
        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                0x0L);
    }

    private void generateBroadcastReceiveStatesAndVerify(
            BluetoothDevice sourceDevice,
            int sourceId,
            int paSyncState,
            int bigEncryptState,
            long bisSyncState) {
        final int sourceAdvSid = 0;
        final int numOfSubgroups = 1;
        final int metaDataLength = 142;

        // Prepare mBluetoothLeBroadcastReceiveStates with metadata for test
        byte[] value_base =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (sourceDevice.getAddressType() & 0xFF), // sourceAddressType
                    Utils.getByteAddress(sourceDevice)[5],
                    Utils.getByteAddress(sourceDevice)[4],
                    Utils.getByteAddress(sourceDevice)[3],
                    Utils.getByteAddress(sourceDevice)[2],
                    Utils.getByteAddress(sourceDevice)[1],
                    Utils.getByteAddress(sourceDevice)[0], // sourceAddress
                    (byte) sourceAdvSid, // sourceAdvSid
                    (byte) (TEST_BROADCAST_ID & 0xFF),
                    (byte) 0x00,
                    (byte) 0x00, // broadcastIdBytes
                    (byte) paSyncState,
                    (byte) bigEncryptState,
                };

        byte[] value_subgroup =
                new byte[] {
                    (byte) numOfSubgroups, // numSubGroups
                    (byte) (bisSyncState & 0xFF),
                    (byte) ((bisSyncState >> 8) & 0xFF),
                    (byte) ((bisSyncState >> 16) & 0xFF),
                    (byte) ((bisSyncState >> 24) & 0xFF), // audioSyncIndex
                    (byte) metaDataLength, // metaDataLength
                };

        byte[] badBroadcastCode = new byte[16];
        Arrays.fill(badBroadcastCode, (byte) 0xFF);

        byte[] metadataHeader =
                new byte[] {
                    (byte) (metaDataLength - 1), // length 141
                    (byte) 0xFF
                };

        byte[] metadataPayload = new byte[140];
        new Random().nextBytes(metadataPayload);

        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        when(characteristic.getValue())
                .thenReturn(
                        Bytes.concat(
                                bigEncryptState
                                                == BluetoothLeBroadcastReceiveState
                                                        .BIG_ENCRYPTION_STATE_BAD_CODE
                                        ? Bytes.concat(value_base, badBroadcastCode, value_subgroup)
                                        : Bytes.concat(value_base, value_subgroup),
                                metadataHeader,
                                metadataPayload));
        when(characteristic.getInstanceId()).thenReturn(sourceId);
        when(characteristic.getUuid()).thenReturn(BassConstants.BASS_BCAST_RECEIVER_STATE);

        mBassClientStateMachine.mGattCallback.onCharacteristicRead(
                null, characteristic, GATT_SUCCESS);
        TestUtils.waitForLooperToFinishScheduledTask(mHandlerThread.getLooper());

        assertThat(mBassClientStateMachine.getAllSources()).hasSize(1);
        BluetoothLeBroadcastReceiveState recvState = mBassClientStateMachine.getAllSources().get(0);

        assertThat(recvState.getSourceId()).isEqualTo(sourceId);
        assertThat(recvState.getSourceAddressType()).isEqualTo(sourceDevice.getAddressType());
        assertThat(recvState.getSourceDevice()).isEqualTo(sourceDevice);
        assertThat(recvState.getSourceAdvertisingSid()).isEqualTo(sourceAdvSid);
        assertThat(recvState.getBroadcastId()).isEqualTo(TEST_BROADCAST_ID);
        assertThat(recvState.getPaSyncState()).isEqualTo(paSyncState);
        assertThat(recvState.getBigEncryptionState()).isEqualTo(bigEncryptState);
        assertThat(recvState.getNumSubgroups()).isEqualTo(numOfSubgroups);

        assertThat(recvState.getBisSyncState()).hasSize(numOfSubgroups);
        assertThat(recvState.getBisSyncState().get(0)).isEqualTo(bisSyncState);

        assertThat(recvState.getSubgroupMetadata()).hasSize(numOfSubgroups);
        BluetoothLeAudioContentMetadata metaData = recvState.getSubgroupMetadata().get(0);
        assertThat(metaData.getRawMetadata().length).isEqualTo(metaDataLength);
        assertThat(metaData.getRawMetadata())
                .isEqualTo(Bytes.concat(metadataHeader, metadataPayload));
    }

    private static BluetoothLeBroadcastSubgroup createBroadcastSubgroup() {
        final long testAudioLocationFrontLeft = 0x01;
        final long testAudioLocationFrontRight = 0x02;
        // For BluetoothLeAudioContentMetadata
        final String testProgramInfo = "Test";
        // German language code in ISO 639-3
        final String testLanguage = "deu";
        final int testCodecId = 42;

        BluetoothLeAudioCodecConfigMetadata codecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(testAudioLocationFrontLeft)
                        .build();
        BluetoothLeAudioContentMetadata contentMetadata =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setProgramInfo(testProgramInfo)
                        .setLanguage(testLanguage)
                        .build();
        BluetoothLeBroadcastSubgroup.Builder builder =
                new BluetoothLeBroadcastSubgroup.Builder()
                        .setCodecId(testCodecId)
                        .setCodecSpecificConfig(codecMetadata)
                        .setContentMetadata(contentMetadata);

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(testAudioLocationFrontRight)
                        .build();

        // builder expect at least one channel
        BluetoothLeBroadcastChannel channel =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(true)
                        .setChannelIndex(TEST_CHANNEL_INDEX)
                        .setCodecMetadata(channelCodecMetadata)
                        .build();
        builder.addChannel(channel);
        return builder.build();
    }

    // It simulates GATT connection for testing.
    public static class StubBassClientStateMachine extends BassClientStateMachine {
        boolean mShouldAllowGatt = true;
        boolean mShouldHandleMessage = true;
        Boolean mIsPendingRemove;
        List<Integer> mMsgWhats = new ArrayList<>();
        int mMsgWhat;
        int mMsgAgr1;
        int mMsgArg2;
        Object mMsgObj;
        long mMsgDelay;

        StubBassClientStateMachine(
                BluetoothDevice device,
                BassClientService service,
                AdapterService adapterService,
                Looper looper,
                int connectTimeout) {
            super(device, service, adapterService, looper, connectTimeout);
        }

        @Override
        public boolean connectGatt(Boolean autoConnect) {
            mGattCallback = new GattCallback();
            return mShouldAllowGatt;
        }

        @Override
        public void sendMessage(Message msg) {
            mMsgWhats.add(msg.what);
            mMsgWhat = msg.what;
            mMsgAgr1 = msg.arg1;
            mMsgArg2 = msg.arg2;
            mMsgObj = msg.obj;
            if (mShouldHandleMessage) {
                super.sendMessage(msg);
            }
        }

        @Override
        public void sendMessageDelayed(int what, Object obj, long delayMillis) {
            mMsgWhats.add(what);
            mMsgWhat = what;
            mMsgObj = obj;
            mMsgDelay = delayMillis;
            if (mShouldHandleMessage) {
                super.sendMessageDelayed(what, obj, delayMillis);
            }
        }

        @Override
        public void sendMessageDelayed(int what, int arg1, long delayMillis) {
            mMsgWhats.add(what);
            mMsgWhat = what;
            mMsgAgr1 = arg1;
            mMsgDelay = delayMillis;
            if (mShouldHandleMessage) {
                super.sendMessageDelayed(what, arg1, delayMillis);
            }
        }

        public void notifyConnectionStateChanged(int status, int newState) {
            if (mGattCallback != null) {
                BluetoothGatt gatt = null;
                if (mBluetoothGatt != null) {
                    gatt = mBluetoothGatt.mWrappedBluetoothGatt;
                }
                mGattCallback.onConnectionStateChange(gatt, status, newState);
            }
        }

        public boolean hasDeferredMessagesSuper(int what) {
            return super.hasDeferredMessages(what);
        }

        @Override
        boolean isPendingRemove(Integer sourceId) {
            if (mIsPendingRemove == null) {
                return super.isPendingRemove(sourceId);
            }
            return mIsPendingRemove;
        }
    }
}
