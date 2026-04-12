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

import static android.bluetooth.BluetoothGatt.GATT_FAILURE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasFlag;

import static com.android.bluetooth.TestUtils.getRealDevice;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.bass_client.BassClientStateMachine.ADD_BCAST_SOURCE;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CANCEL_PENDING_SOURCE_OPERATION;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CONNECT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.bass_client.BassClientStateMachine.CONNECT_TIMEOUT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.DISCONNECT;
import static com.android.bluetooth.bass_client.BassClientStateMachine.ENCRYPTION_STATE_CHANGED;
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
import static com.android.bluetooth.bass_client.BassClientStateMachine.UPDATE_METADATA;
import static com.android.bluetooth.bass_client.BassConstants.CLIENT_CHARACTERISTIC_CONFIG;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Intent;
import android.os.Looper;
import android.os.Message;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioConstants;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.tests.bluetooth.MockitoRule;

import com.google.common.primitives.Bytes;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
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

/** Test cases for {@link BassClientStateMachine}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BassClientStateMachineTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private BassClientService mBassClientService;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private BassClientStateMachine.BluetoothGattTestableWrapper mBluetoothGatt;
    @Mock private BluetoothGattCharacteristic mBroadcastScanControlPoint;
    @Mock private ScanController mScanController;

    private static final int TEST_BROADCAST_ID = 42;
    private static final int TEST_SOURCE_ID = 1;
    private static final int TEST_CHANNEL_INDEX = 1;
    private static final String EMPTY_BLUETOOTH_DEVICE_ADDRESS = "00:00:00:00:00:00";
    private static final byte OPCODE_UPDATE_SOURCE = 0x03;
    private static final int UPDATE_SOURCE_FIXED_LENGTH = 6;

    private final BluetoothDevice mDevice = getTestDevice(0);
    private final BluetoothDevice mEmptyTestDevice = getTestDevice(EMPTY_BLUETOOTH_DEVICE_ADDRESS);
    private final BluetoothDevice mSourceTestDevice = getTestDevice(1);

    private StubBassClientStateMachine mStateMachine;
    private TestLooper mLooper;
    private InOrder mInOrder;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mInOrder = inOrder(mAdapterService);

        MetricsLogger.setInstanceForTesting(mMetricsLogger);

        doReturn(mEmptyTestDevice)
                .when(mAdapterService)
                .getDeviceFromByte(Util.getBytesFromAddress(EMPTY_BLUETOOTH_DEVICE_ADDRESS));
        doReturn(mAdapterService).when(mBassClientService).getBaseContext();
        mockGetBluetoothManager(mAdapterService);

        doAnswer(
                        invocation -> {
                            Runnable runnable = invocation.getArgument(0);
                            runnable.run();
                            return null;
                        })
                .when(mScanController)
                .doOnScanThread(any(Runnable.class));

        mStateMachine =
                new StubBassClientStateMachine(
                        mDevice,
                        mBassClientService,
                        mAdapterService,
                        mScanController,
                        mLooper.getLooper());
        mStateMachine.start();
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
        if (mStateMachine == null) {
            return;
        }

        MetricsLogger.setInstanceForTesting(null);
        mStateMachine.doQuit();
    }

    /** Test that default state is disconnected */
    @Test
    public void testDefaultDisconnectedState() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BASS_READ_CHARACTERISTICS_AFTER_ENCRYPTION)
    public void testReadCharacteristicsAfterEncryption() {
        // Test that BASS characteristics are read only after encryption is complete.
        // Initial state is Disconnected
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        // Mock connect
        allowConnection(true);
        allowConnectGatt(true);
        // Link is not encrypted at first
        doReturn(false).when(mBassClientService).isEncrypted(mDevice);

        // Message CONNECT
        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connecting.class);

        // Gatt callback, connected
        mStateMachine.notifyConnectionStateChanged(GATT_SUCCESS, STATE_CONNECTED);
        mLooper.dispatchAll();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connected.class);

        // Service discovery
        mStateMachine.mBluetoothGatt = mBluetoothGatt;
        mStateMachine.mDiscoveryInitiated = true;
        BluetoothGattService gattService = mock(BluetoothGattService.class);
        doReturn(gattService).when(mBluetoothGatt).getService(BassConstants.BASS_UUID);
        List<BluetoothGattCharacteristic> characteristics = new ArrayList<>();
        BluetoothGattCharacteristic characteristic = mock(BluetoothGattCharacteristic.class);
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();
        characteristics.add(characteristic);
        doReturn(characteristics).when(gattService).getCharacteristics();
        mStateMachine.mGattCallback.onServicesDiscovered(null, GATT_SUCCESS);
        mLooper.dispatchAll();

        // After service discovery, it should request MTU
        verify(mBluetoothGatt).requestMtu(anyInt());

        // After MTU change, it should check encryption. Let's say MTU is changed.
        mStateMachine.mGattCallback.onMtuChanged(null, 512, GATT_SUCCESS);
        mLooper.dispatchAll();

        // It should not read characteristics because it's not encrypted
        assertThat(mStateMachine.mIsWaitingForEncryption).isTrue();
        assertThat(mStateMachine.mMsgWhats).doesNotContain(READ_BASS_CHARACTERISTICS);
        verify(callbacks, never()).notifyBassStateReady(eq(mStateMachine.getDevice()));
        int oldMsgCount = mStateMachine.mMsgWhats.size();

        // Now, mock encryption success
        mStateMachine.sendMessage(ENCRYPTION_STATE_CHANGED, BassConstants.ENCRYPTED);
        mLooper.dispatchAll();

        // Now it should read characteristics
        List<Integer> newMessages =
                mStateMachine.mMsgWhats.subList(oldMsgCount, mStateMachine.mMsgWhats.size());
        assertThat(newMessages).contains(READ_BASS_CHARACTERISTICS);
        assertThat(mStateMachine.mIsWaitingForEncryption).isFalse();
    }

    /**
     * Allow/disallow connection to any device.
     *
     * @param allow if true, connection is allowed
     */
    private void allowConnection(boolean allow) {
        doReturn(allow).when(mBassClientService).okToConnect(any());
    }

    private void allowConnectGatt(boolean allow) {
        mStateMachine.mShouldAllowGatt = allow;
    }

    @Test
    public void testFailToConnectGatt() {
        allowConnection(true);
        allowConnectGatt(false);

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();

        verifyNoIntentSent();

        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
        assertThat(mStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void testSuccessfullyConnected() {
        allowConnection(true);
        allowConnectGatt(true);

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.mGattCallback).isNotNull();

        mStateMachine.notifyConnectionStateChanged(GATT_SUCCESS, STATE_CONNECTED);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_CONNECTING);
    }

    @Test
    public void timeout_whenConnecting_isDisconnected() {
        allowConnection(true);
        allowConnectGatt(true);

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);

        mLooper.moveTimeForward(BassConstants.CONNECT_TIMEOUT_MS);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void disconnect_whenConnecting_receiveConnected_isDisconnected() {
        allowConnection(true);
        allowConnectGatt(true);

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);

        // Message is deferred
        mStateMachine.sendMessage(DISCONNECT);
        mLooper.dispatchAll();

        mStateMachine.mBluetoothGatt = mBluetoothGatt;
        mStateMachine.sendMessage(CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_CONNECTED));
        mLooper.dispatchNext();
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_CONNECTING);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTED);
    }

    @Test
    public void receiveConnected_whenDisconnected_isConnected() {
        allowConnection(true);
        allowConnectGatt(true);

        mStateMachine.sendMessage(CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_CONNECTED));
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_DISCONNECTED);
    }

    @Test
    public void receiveConnected_whenConnecting_isConnected() {
        allowConnection(true);
        allowConnectGatt(true);

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);

        mStateMachine.sendMessage(CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_CONNECTED));
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_CONNECTING);
    }

    @Test
    public void receiveDisconnected_whenConnecting_isDisconnected() {
        allowConnection(true);
        allowConnectGatt(true);

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);

        mStateMachine.sendMessage(CONNECTION_STATE_CHANGED, Integer.valueOf(STATE_DISCONNECTED));
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
    }

    @Test
    public void readBassCharacteristics_whenConnected_isProcessing() {
        receiveConnected_whenDisconnected_isConnected();
        mStateMachine.mBluetoothGatt = mBluetoothGatt;

        mStateMachine.sendMessage(READ_BASS_CHARACTERISTICS, null);
        mLooper.dispatchAll();
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.ConnectedProcessing.class);
    }

    @Test
    public void gattTxnProcess_whenProcessing_isConnected() {
        readBassCharacteristics_whenConnected_isProcessing();

        mStateMachine.sendMessage(GATT_TXN_PROCESSED);
        mLooper.dispatchAll();
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connected.class);
    }

    @Test
    public void gattTxnTimeout_whenProcessing_isConnected() {
        readBassCharacteristics_whenConnected_isProcessing();

        mStateMachine.sendMessage(GATT_TXN_TIMEOUT);
        mLooper.dispatchAll();
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Connected.class);
    }

    @Test
    public void startScanOffload_whenConnected_isProcessing() {
        receiveConnected_whenDisconnected_isConnected();
        mStateMachine.mBluetoothGatt = mBluetoothGatt;
        mStateMachine.mBroadcastScanControlPoint = mBroadcastScanControlPoint;

        mStateMachine.sendMessage(START_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.ConnectedProcessing.class);
    }

    @Test
    public void stopScanOffload_whenConnected_isProcessing() {
        receiveConnected_whenDisconnected_isConnected();
        mStateMachine.mBluetoothGatt = mBluetoothGatt;
        mStateMachine.mBroadcastScanControlPoint = mBroadcastScanControlPoint;

        mStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        verifyNoIntentSent();
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.ConnectedProcessing.class);
    }

    @Test
    public void acquireAllBassChars() {
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        // Do nothing when mBluetoothGatt.getService returns null
        mStateMachine.acquireAllBassChars();

        BluetoothGattService gattService = Mockito.mock(BluetoothGattService.class);
        doReturn(gattService).when(btGatt).getService(BassConstants.BASS_UUID);

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

        doReturn(characteristics).when(gattService).getCharacteristics();
        mStateMachine.acquireAllBassChars();
        assertThat(mStateMachine.mBroadcastScanControlPoint).isEqualTo(scanControlPoint);
        assertThat(mStateMachine.mBroadcastCharacteristics).contains(bassCharacteristic);
    }

    @Test
    public void acquireAllBassChars_noCharacteristics() {
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattService gattService = Mockito.mock(BluetoothGattService.class);
        doReturn(gattService).when(btGatt).getService(BassConstants.BASS_UUID);
        List<BluetoothGattCharacteristic> characteristics = new ArrayList<>();
        doReturn(characteristics).when(gattService).getCharacteristics();

        mStateMachine.acquireAllBassChars();

        assertThat(mStateMachine.mBroadcastScanControlPoint).isNull();
        assertThat(mStateMachine.mBroadcastCharacteristics).isEmpty();
        // numOfChars is 0, so mNumOfBroadcastReceiverStates becomes 0 - 1 = -1
        assertThat(mStateMachine.mNumOfBroadcastReceiverStates).isEqualTo(-1);
    }

    @Test
    public void acquireAllBassChars_onlyControlPoint() {
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattService gattService = Mockito.mock(BluetoothGattService.class);
        doReturn(gattService).when(btGatt).getService(BassConstants.BASS_UUID);

        List<BluetoothGattCharacteristic> characteristics = new ArrayList<>();
        BluetoothGattCharacteristic scanControlPoint =
                new BluetoothGattCharacteristic(
                        BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT,
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        characteristics.add(scanControlPoint);

        doReturn(characteristics).when(gattService).getCharacteristics();
        mStateMachine.acquireAllBassChars();
        assertThat(mStateMachine.mBroadcastScanControlPoint).isEqualTo(scanControlPoint);
        assertThat(mStateMachine.mBroadcastCharacteristics).isEmpty();
        // numOfChars is 1, so mNumOfBroadcastReceiverStates becomes 1 - 1 = 0
        assertThat(mStateMachine.mNumOfBroadcastReceiverStates).isEqualTo(0);
    }

    @Test
    public void acquireAllBassChars_controlPointWithInvalidProperties() {
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattService gattService = Mockito.mock(BluetoothGattService.class);
        doReturn(gattService).when(btGatt).getService(BassConstants.BASS_UUID);

        List<BluetoothGattCharacteristic> characteristics = new ArrayList<>();
        // Invalid properties (e.g., missing PROPERTY_WRITE)
        BluetoothGattCharacteristic scanControlPoint =
                new BluetoothGattCharacteristic(
                        BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        characteristics.add(scanControlPoint);

        doReturn(characteristics).when(gattService).getCharacteristics();
        mStateMachine.acquireAllBassChars();

        // Control point should not be set due to invalid properties
        assertThat(mStateMachine.mBroadcastScanControlPoint).isNull();
        // The characteristic is not added to the broadcast characteristics list either
        assertThat(mStateMachine.mBroadcastCharacteristics).isEmpty();
        // numOfChars is 1, so mNumOfBroadcastReceiverStates becomes 1 - 1 = 0
        assertThat(mStateMachine.mNumOfBroadcastReceiverStates).isEqualTo(0);
    }

    @Test
    public void simpleMethods() {
        // dump() shouldn't crash
        StringBuilder sb = new StringBuilder();
        mStateMachine.dump(sb);

        // messageWhatToString() shouldn't crash
        for (int i = CONNECT; i <= CONNECT_TIMEOUT + 1; ++i) {
            mStateMachine.messageWhatToString(i);
        }

        final int invalidSourceId = -100;
        assertThat(mStateMachine.getCurrentBroadcastMetadata(invalidSourceId)).isNull();
        assertThat(mStateMachine.getDevice()).isEqualTo(mDevice);
        assertThat(mStateMachine.hasPendingSourceOperation()).isFalse();
        assertThat(mStateMachine.hasPendingSourceOperation(1)).isFalse();
        assertThat(mStateMachine.isEmpty(new byte[] {0})).isTrue();
        assertThat(mStateMachine.isEmpty(new byte[] {1})).isFalse();
        assertThat(mStateMachine.isPendingRemove(invalidSourceId)).isFalse();
    }

    @Test
    public void gattCallbackOnConnectionStateChange_changedToConnected()
            throws InterruptedException {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        // disallow connection
        allowConnection(false);
        int status = STATE_CONNECTING;
        int newState = STATE_CONNECTED;
        cb.onConnectionStateChange(null, status, newState);
        mLooper.dispatchAll();

        verify(btGatt).disconnect();
        verify(btGatt).close();
        assertThat(mStateMachine.mBluetoothGatt).isNull();
        assertThat(mStateMachine.mMsgWhats).contains(CONNECTION_STATE_CHANGED);
        mStateMachine.mMsgWhats.clear();

        mStateMachine.mBluetoothGatt = btGatt;
        allowConnection(true);
        mStateMachine.mDiscoveryInitiated = false;
        status = STATE_DISCONNECTED;
        newState = STATE_CONNECTED;
        cb.onConnectionStateChange(null, status, newState);
        mLooper.dispatchAll();

        assertThat(mStateMachine.mDiscoveryInitiated).isTrue();
        assertThat(mStateMachine.mMsgWhats).contains(CONNECTION_STATE_CHANGED);
        assertThat(mStateMachine.mMsgObj).isEqualTo(newState);
        mStateMachine.mMsgWhats.clear();
    }

    @Test
    public void gattCallbackOnConnectionStateChanged_changedToDisconnected()
            throws InterruptedException {
        initToConnectingState();
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        allowConnection(false);
        int status = STATE_CONNECTING;
        int newState = STATE_DISCONNECTED;
        cb.onConnectionStateChange(null, status, newState);
        mLooper.dispatchAll();

        assertThat(mStateMachine.mMsgWhats).contains(CONNECTION_STATE_CHANGED);
        assertThat(mStateMachine.mMsgObj).isEqualTo(newState);
        mStateMachine.mMsgWhats.clear();
    }

    @Test
    public void gattCallbackOnServicesDiscovered() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        // Do nothing if mDiscoveryInitiated is false.
        mStateMachine.mDiscoveryInitiated = false;
        int status = GATT_FAILURE;
        cb.onServicesDiscovered(null, status);

        verify(btGatt, never()).requestMtu(anyInt());

        // Do nothing if status is not GATT_SUCCESS.
        mStateMachine.mDiscoveryInitiated = true;
        status = GATT_FAILURE;
        cb.onServicesDiscovered(null, status);

        verify(btGatt, never()).requestMtu(anyInt());
        verify(callbacks).notifyBassStateSetupFailed(eq(mStateMachine.getDevice()));
        assertThat(mStateMachine.isBassStateReady()).isFalse();

        // call requestMtu() if status is GATT_SUCCESS.
        mStateMachine.mDiscoveryInitiated = true;
        status = GATT_SUCCESS;
        cb.onServicesDiscovered(null, status);

        verify(btGatt).requestMtu(anyInt());
    }

    @Test
    public void gattCallbackOnCharacteristicRead() {
        mStateMachine.mShouldHandleMessage = false;
        mStateMachine.connectGatt(true);
        mLooper.dispatchAll();
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        BluetoothGattDescriptor desc = Mockito.mock(BluetoothGattDescriptor.class);
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();
        doReturn(callbacks).when(mBassClientService).getCallbacks();
        mStateMachine.mNumOfBroadcastReceiverStates = 2;

        // Characteristic read success with null value
        doReturn(null).when(characteristic).getValue();
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();
        InOrder inOrderCharacteristic = inOrder(characteristic);
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic).getValue();
        InOrder inOrderCallbacks = inOrder(callbacks);
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());
        verify(characteristic, never()).getDescriptor(any());

        // Characteristic read failed and mBluetoothGatt is null.
        mStateMachine.mBluetoothGatt = null;
        cb.onCharacteristicRead(null, characteristic, GATT_FAILURE);
        mLooper.dispatchAll();
        inOrderCharacteristic.verify(characteristic, never()).getUuid();
        inOrderCharacteristic.verify(characteristic, never()).getValue();
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());
        assertThat(mStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
        assertThat(mStateMachine.mMsgAgr1).isEqualTo(GATT_FAILURE);
        mStateMachine.mMsgWhats.clear();

        // Characteristic read failed and mBluetoothGatt is not null.
        mStateMachine.mBluetoothGatt = btGatt;
        doReturn(desc).when(characteristic).getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        cb.onCharacteristicRead(null, characteristic, GATT_FAILURE);
        mLooper.dispatchAll();
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
        doReturn(new byte[] {}).when(characteristic).getValue();
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();
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
                    Util.getByteAddress(mSourceTestDevice)[5],
                    Util.getByteAddress(mSourceTestDevice)[4],
                    Util.getByteAddress(mSourceTestDevice)[3],
                    Util.getByteAddress(mSourceTestDevice)[2],
                    Util.getByteAddress(mSourceTestDevice)[1],
                    Util.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    (byte) (TEST_BROADCAST_ID & 0xFF),
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
        doReturn(value).when(characteristic).getValue();
        doReturn(instanceId).when(characteristic).getInstanceId();
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();
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
        doReturn(instanceId2).when(characteristic).getInstanceId();
        cb.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();
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
    public void gattCallbackOnCharacteristicChanged() {
        mStateMachine.connectGatt(true);
        mLooper.dispatchAll();
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        mStateMachine.mNumOfBroadcastReceiverStates = 1;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();

        // Null value
        doReturn(null).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
        InOrder inOrderCharacteristic = inOrder(characteristic);
        inOrderCharacteristic.verify(characteristic).getUuid();
        inOrderCharacteristic.verify(characteristic).getValue();
        InOrder inOrderCallbacks = inOrder(callbacks);
        inOrderCallbacks
                .verify(callbacks, never())
                .notifyReceiveStateChanged(any(), anyInt(), any());

        // Empty value without any previous read/change
        doReturn(new byte[] {}).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
                    Util.getByteAddress(mSourceTestDevice)[5],
                    Util.getByteAddress(mSourceTestDevice)[4],
                    Util.getByteAddress(mSourceTestDevice)[3],
                    Util.getByteAddress(mSourceTestDevice)[2],
                    Util.getByteAddress(mSourceTestDevice)[1],
                    Util.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    (byte) (TEST_BROADCAST_ID & 0xFF),
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
        doReturn(value).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        doReturn(new byte[] {}).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        mStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mStateMachine.mPendingMetadata = metadata;
        doReturn(value).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        mStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        doReturn(new byte[] {}).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        mStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        mStateMachine.mPendingMetadata = metadata;
        doReturn(value).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        mStateMachine.mPendingSourceToSwitch = metadata;
        doReturn(new byte[] {}).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        assertThat(mStateMachine.mMsgWhats).contains(ADD_BCAST_SOURCE);
        assertThat(mStateMachine.mMsgObj).isEqualTo(metadata);

        // Sync value again
        mStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        mStateMachine.mPendingMetadata = metadata;
        doReturn(value).when(characteristic).getValue();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        doReturn(paResult).when(mBassClientService).getPeriodicAdvertisementResult(any(), anyInt());
        int syncHandle = 100;
        doReturn(syncHandle).when(paResult).getSyncHandle();
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
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
        verify(mScanController).transferSync(any(), eq(serviceData), eq(syncHandle));
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - PA SyncInfo Request, local broadcast
        mStateMachine.mPendingMetadata = metadata;
        doReturn(true)
                .when(mBassClientService)
                .isLocalBroadcast(any(BluetoothLeBroadcastReceiveState.class));
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        serviceData = 0x000000FF & sourceId;
        serviceData = serviceData << 8;
        // Address we set in the Source Address can differ from the address in the air
        serviceData = serviceData | BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS;
        verify(mScanController).transferSetInfo(any(), eq(serviceData), anyInt(), any());
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - Broadcast Code
        value[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX] =
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED;
        value[BassConstants.BCAST_RCVR_STATE_ENC_STATUS_IDX] =
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED;
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        assertThat(mStateMachine.mMsgWhats).contains(SET_BCAST_CODE);
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);

        // Update value - Pending Remove
        value[BassConstants.BCAST_RCVR_STATE_PA_SYNC_IDX] =
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        mStateMachine.mIsPendingRemove = true;
        cb.onCharacteristicChanged(null, characteristic);
        mLooper.dispatchAll();
        inOrderCallbacks
                .verify(callbacks)
                .notifySourceModified(
                        any(), eq(sourceId), eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        assertThat(mStateMachine.mMsgWhats).contains(REMOVE_BCAST_SOURCE);
        assertThat(mStateMachine.mMsgAgr1).isEqualTo(sourceId);
        inOrderCallbacks
                .verify(callbacks)
                .notifyReceiveStateChanged(any(), eq(sourceId), receiveStateCaptor.capture());
        assertThat(receiveStateCaptor.getValue().getSourceDevice()).isEqualTo(mSourceTestDevice);
    }

    @Test
    public void gattCharacteristicWrite() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;

        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        doReturn(BassConstants.BASS_BCAST_AUDIO_SCAN_CTRL_POINT).when(characteristic).getUuid();

        cb.onCharacteristicWrite(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();
        assertThat(mStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
    }

    @Test
    public void gattCallbackOnDescriptorWrite() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        BluetoothGattDescriptor descriptor = Mockito.mock(BluetoothGattDescriptor.class);
        doReturn(BassConstants.CLIENT_CHARACTERISTIC_CONFIG).when(descriptor).getUuid();

        cb.onDescriptorWrite(null, descriptor, GATT_SUCCESS);
        mLooper.dispatchAll();
        assertThat(mStateMachine.mMsgWhats).contains(GATT_TXN_PROCESSED);
    }

    @Test
    public void gattCallbackOnMtuChanged() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        mStateMachine.mMTUChangeRequested = true;

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        // Verify notifyBassStateSetupFailed is called
        cb.onMtuChanged(null, 10, GATT_FAILURE);
        verify(callbacks).notifyBassStateSetupFailed(eq(mStateMachine.getDevice()));
        assertThat(mStateMachine.mMTUChangeRequested).isTrue();
        assertThat(mStateMachine.isBassStateReady()).isFalse();

        cb.onMtuChanged(null, 10, GATT_SUCCESS);
        assertThat(mStateMachine.mMTUChangeRequested).isTrue();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        cb.onMtuChanged(null, 10, GATT_SUCCESS);
        assertThat(mStateMachine.mMTUChangeRequested).isFalse();
    }

    @Test
    public void sendConnectMessage_inDisconnectedState() {
        initToDisconnectedState();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CONNECT), BassClientStateMachine.Connecting.class);
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendDisconnectMessage_inDisconnectedState() {
        initToDisconnectedState();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        mStateMachine.sendMessage(DISCONNECT);
        mLooper.dispatchAll();
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendStateChangedMessage_inDisconnectedState() {
        initToDisconnectedState();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        Message msgToConnectingState = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToConnectingState.obj = STATE_CONNECTING;

        mStateMachine.sendMessage(msgToConnectingState);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        Message msgToConnectedState = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToConnectedState.obj = STATE_CONNECTED;
        sendMessageAndVerifyTransition(msgToConnectedState, BassClientStateMachine.Connected.class);
    }

    @Test
    public void sendOtherMessages_inDisconnectedState_doesNotChangeState() {
        initToDisconnectedState();

        mStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        mStateMachine.sendMessage(-1);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendConnectMessages_inConnectingState_doesNotChangeState() {
        initToConnectingState();

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendDisconnectMessages_inConnectingState_defersMessage() {
        initToConnectingState();

        mStateMachine.sendMessage(DISCONNECT);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(DISCONNECT)).isTrue();
    }

    @Test
    public void sendReadBassCharacteristicsMessage_inConnectingState_defersMessage() {
        initToConnectingState();

        mStateMachine.sendMessage(READ_BASS_CHARACTERISTICS);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(READ_BASS_CHARACTERISTICS)).isTrue();
    }

    @Test
    public void sendStateChangedToNonConnectedMessage_inConnectingState_movesToDisconnected() {
        initToConnectingState();

        Message msg = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msg.obj = STATE_CONNECTING;
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void sendStateChangedToConnectedMessage_inConnectingState_movesToConnected() {
        initToConnectingState();

        Message msg = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msg.obj = STATE_CONNECTED;
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Connected.class);
    }

    @Test
    public void sendConnectTimeMessage_inConnectingState() {
        initToConnectingState();

        Message timeoutWithDifferentDevice =
                mStateMachine.obtainMessage(CONNECT_TIMEOUT, getTestDevice(230));
        mStateMachine.sendMessage(timeoutWithDifferentDevice);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        Message msg = mStateMachine.obtainMessage(CONNECT_TIMEOUT, mDevice);
        sendMessageAndVerifyTransition(msg, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void sendInvalidMessage_inConnectingState_doesNotChangeState() {
        initToConnectingState();
        mStateMachine.sendMessage(-1);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendConnectMessage_inConnectedState() {
        initToConnectedState();

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendDisconnectMessage_inConnectedState() {
        initToConnectedState();

        mStateMachine.mBluetoothGatt = null;

        mStateMachine.sendMessage(DISCONNECT);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(DISCONNECT), BassClientStateMachine.Disconnected.class);
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendStateChangedMessage_inConnectedState() {
        initToConnectedState();

        Message connectedMsg = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        connectedMsg.obj = STATE_CONNECTED;

        mStateMachine.sendMessage(connectedMsg);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        Message noneConnectedMsg = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        noneConnectedMsg.obj = STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(noneConnectedMsg, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mStateMachine.mBluetoothGatt).isNull();
    }

    @Test
    public void sendReadBassCharacteristicsMessage_inConnectedState() {
        initToConnectedState();
        BluetoothGattCharacteristic gattCharacteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);

        mStateMachine.sendMessage(READ_BASS_CHARACTERISTICS, gattCharacteristic);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(READ_BASS_CHARACTERISTICS, gattCharacteristic),
                BassClientStateMachine.ConnectedProcessing.class);
    }

    @Test
    public void sendStartScanOffloadMessage_inConnectedState() {
        initToConnectedState();
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        mStateMachine.sendMessage(START_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(START_SCAN_OFFLOAD),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(btGatt).writeCharacteristic(scanControlPoint);
        verify(scanControlPoint).setValue(REMOTE_SCAN_START);
    }

    @Test
    public void sendStopScanOffloadMessage_inConnectedState() {
        initToConnectedState();
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;

        mStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(STOP_SCAN_OFFLOAD),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(btGatt).writeCharacteristic(scanControlPoint);
        verify(scanControlPoint).setValue(REMOTE_SCAN_STOP);
    }

    @Test
    public void sendInvalidMessage_inConnectedState_doesNotChangeState() {
        initToConnectedState();

        mStateMachine.sendMessage(-1);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendAddBcastSourceMessage_inConnectedState() {
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        doReturn(true)
                .when(mBassClientService)
                .isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class));
        mStateMachine.sendMessage(ADD_BCAST_SOURCE, metadata);
        mLooper.dispatchAll();

        verify(mBassClientService).getCallbacks();
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mStateMachine.mPendingSourceToSwitch = metadata;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
        assertThat(mStateMachine.mPendingSourceToSwitch).isNull();
    }

    @Test
    public void sendSwitchSourceMessage_inConnectedState() {
        initToConnectedState();
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        Integer sourceId = 1;

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mStateMachine.sendMessage(SWITCH_BCAST_SOURCE, sourceId, 0, metadata);
        mLooper.dispatchAll();
        assertThat(mStateMachine.mPendingSourceToSwitch).isEqualTo(metadata);
    }

    @Test
    public void sendUpdateBcastSourceMessage_inConnectedState() {
        initToConnectedState();
        mStateMachine.connectGatt(true);
        mStateMachine.mNumOfBroadcastReceiverStates = 2;

        // Prepare mBluetoothLeBroadcastReceiveStates for test
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();
        int sourceId = 1;
        int paSync = BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Util.getByteAddress(mSourceTestDevice)[5],
                    Util.getByteAddress(mSourceTestDevice)[4],
                    Util.getByteAddress(mSourceTestDevice)[3],
                    Util.getByteAddress(mSourceTestDevice)[2],
                    Util.getByteAddress(mSourceTestDevice)[1],
                    Util.getByteAddress(mSourceTestDevice)[0], // sourceAddress
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
        doReturn(value).when(characteristic).getValue();
        doReturn(sourceId).when(characteristic).getInstanceId();
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();
        mStateMachine.mGattCallback.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        doReturn(null).when(mBassClientService).getPeriodicAdvertisementResult(any(), anyInt());

        mStateMachine.sendMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata);
        mLooper.dispatchAll();

        PeriodicAdvertisementResult paResult = Mockito.mock(PeriodicAdvertisementResult.class);
        doReturn(paResult).when(mBassClientService).getPeriodicAdvertisementResult(any(), anyInt());
        doReturn(null).when(mBassClientService).getBase(anyInt());
        Mockito.clearInvocations(callbacks);

        mStateMachine.sendMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata);
        mLooper.dispatchAll();

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
        doReturn(data).when(mBassClientService).getBase(anyInt());
        Mockito.clearInvocations(callbacks);

        mStateMachine.sendMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata);
        mLooper.dispatchAll();
        verify(callbacks).notifySourceModifyFailed(any(), anyInt(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBluetoothGatt = btGatt;
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;
        mStateMachine.mPendingOperation = 0;
        mStateMachine.mPendingSourceId = 0;
        mStateMachine.mPendingMetadata = null;
        Mockito.clearInvocations(callbacks);

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(sourceId);
        assertThat(mStateMachine.mPendingMetadata).isEqualTo(metadata);
    }

    @Test
    public void sendSetBcastCodeMessage_inConnectedState() {
        initToConnectedState();
        mStateMachine.connectGatt(true);
        mStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        // Prepare mBluetoothLeBroadcastReceiveStates with metadata for test
        mStateMachine.mShouldHandleMessage = false;
        int sourceId = 1;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Util.getByteAddress(mSourceTestDevice)[5],
                    Util.getByteAddress(mSourceTestDevice)[4],
                    Util.getByteAddress(mSourceTestDevice)[3],
                    Util.getByteAddress(mSourceTestDevice)[2],
                    Util.getByteAddress(mSourceTestDevice)[1],
                    Util.getByteAddress(mSourceTestDevice)[0], // sourceAddress
                    0x00, // sourceAdvSid
                    (byte) (TEST_BROADCAST_ID & 0xFF),
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
        mStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        mStateMachine.mPendingSourceId = (byte) sourceId;
        BluetoothGattCharacteristic characteristic =
                Mockito.mock(BluetoothGattCharacteristic.class);
        doReturn(value).when(characteristic).getValue();
        doReturn(sourceId).when(characteristic).getInstanceId();
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();

        mStateMachine.mGattCallback.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();

        mStateMachine.mPendingMetadata = createBroadcastMetadata();
        mStateMachine.mGattCallback.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();
        mStateMachine.mShouldHandleMessage = true;

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        // Not set bcast code as source id is different
        BluetoothLeBroadcastReceiveState recvState =
                new BluetoothLeBroadcastReceiveState(
                        2,
                        BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                        getRealDevice("00:00:00:00:00:00"),
                        0,
                        0,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                        null,
                        0,
                        Arrays.asList(new Long[0]),
                        Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
        mStateMachine.sendMessage(SET_BCAST_CODE, recvState);
        mLooper.dispatchAll();
        verify(btGatt, never()).writeCharacteristic(any());
        verify(scanControlPoint, never()).setValue(any(byte[].class));

        recvState =
                new BluetoothLeBroadcastReceiveState(
                        sourceId,
                        BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                        getRealDevice("00:00:00:00:00:00"),
                        0,
                        0,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_CODE_REQUIRED,
                        null,
                        0,
                        Arrays.asList(new Long[0]),
                        Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(SET_BCAST_CODE, recvState),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(SET_BCAST_CODE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(sourceId);
        verify(btGatt).writeCharacteristic(any());
        verify(scanControlPoint).setValue(any(byte[].class));
    }

    @Test
    public void receiveSinkReceiveState_inConnectedState() {
        int sourceId = 1;

        initToConnectedState();
        mStateMachine.connectGatt(true);
        mStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

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
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        int sid = 10;
        mStateMachine.sendMessage(REMOVE_BCAST_SOURCE, sid);
        mLooper.dispatchAll();
        verify(callbacks).notifySourceRemoveFailed(any(), anyInt(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(REMOVE_BCAST_SOURCE, sid),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
        assertThat(mStateMachine.mPendingOperation).isEqualTo(REMOVE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(sid);
    }

    @Test
    public void syncRequestForPast_initiatePaSyncTransferMessage() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);

        verify(mBassClientService).syncRequestForPast(mDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);

        int syncHandle = 1234;
        mStateMachine.sendMessage(INITIATE_PA_SYNC_TRANSFER, syncHandle, TEST_SOURCE_ID);
        mLooper.dispatchAll();

        int serviceData = 0x000000FF & TEST_SOURCE_ID;
        serviceData = serviceData << 8;
        // advA matches EXT_ADV_ADDRESS
        // also matches source address (as we would have written)
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_EXT_ADV_ADDRESS);
        serviceData = serviceData & (~BassConstants.ADV_ADDRESS_DONT_MATCHES_SOURCE_ADV_ADDRESS);
        verify(mScanController).transferSync(any(), eq(serviceData), eq(syncHandle));
    }

    @Test
    public void syncRequestForMetadata_updateMetadataMessage() {
        initToConnectedState();
        mStateMachine.connectGatt(true);
        mStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        verify(callbacks)
                .notifySourceAdded(any(), any(), eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));

        assertThat(mStateMachine.getCurrentBroadcastMetadata(TEST_SOURCE_ID)).isNull();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        Message msg = mStateMachine.obtainMessage(UPDATE_METADATA);
        msg.arg1 = TEST_SOURCE_ID;
        msg.obj = metadata;
        mStateMachine.sendMessage(msg);
        mLooper.dispatchAll();

        assertThat(mStateMachine.getCurrentBroadcastMetadata(TEST_SOURCE_ID)).isEqualTo(metadata);
    }

    @Test
    public void sendConnectMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void sendDisconnectMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        // Mock instance of btGatt was created in initToConnectedProcessingState().
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt = mStateMachine.mBluetoothGatt;

        mStateMachine.mBluetoothGatt = null;
        mStateMachine.sendMessage(DISCONNECT);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        mStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(DISCONNECT), BassClientStateMachine.Disconnected.class);
        verify(btGatt).disconnect();
        verify(btGatt).close();
    }

    @Test
    public void sendStateChangedMessage_inConnectedProcessingState() {
        initToConnectedProcessingState();

        Message msgToConnectedState = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToConnectedState.obj = STATE_CONNECTED;

        mStateMachine.sendMessage(msgToConnectedState);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        Message msgToNoneConnectedState = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
        msgToNoneConnectedState.obj = STATE_DISCONNECTING;
        sendMessageAndVerifyTransition(
                msgToNoneConnectedState, BassClientStateMachine.Disconnected.class);
        verify(btGatt).close();
        assertThat(mStateMachine.mBluetoothGatt).isNull();
    }

    /** This also tests BassClientStateMachine#sendPendingCallbacks */
    @Test
    public void sendGattTxnProcessedMessage_inConnectedProcessingState() {
        initToConnectedProcessingState();
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        // Test sendPendingCallbacks(START_SCAN_OFFLOAD, ERROR_UNKNOWN)
        mStateMachine.mPendingOperation = START_SCAN_OFFLOAD;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);

        // Test sendPendingCallbacks(ADD_BCAST_SOURCE, ERROR_UNKNOWN)
        moveConnectedStateToConnectedProcessingState();
        mStateMachine.mPendingMetadata = createBroadcastMetadata();
        mStateMachine.mPendingOperation = ADD_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        // Test sendPendingCallbacks(UPDATE_BCAST_SOURCE, ERROR_UNKNOWN)
        moveConnectedStateToConnectedProcessingState();
        mStateMachine.mPendingOperation = UPDATE_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        verify(callbacks).notifySourceModifyFailed(any(), anyInt(), anyInt());

        // Test sendPendingCallbacks(REMOVE_BCAST_SOURCE, ERROR_UNKNOWN)
        moveConnectedStateToConnectedProcessingState();
        mStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        verify(callbacks).notifySourceRemoveFailed(any(), anyInt(), anyInt());

        // Test sendPendingCallbacks(SET_BCAST_CODE, REASON_LOCAL_APP_REQUEST)
        moveConnectedStateToConnectedProcessingState();
        mStateMachine.mPendingOperation = REMOVE_BCAST_SOURCE;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        // Nothing to verify more

        // Test sendPendingCallbacks(SET_BCAST_CODE, REASON_LOCAL_APP_REQUEST)
        moveConnectedStateToConnectedProcessingState();
        mStateMachine.mPendingOperation = -1;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        // Nothing to verify more
    }

    @Test
    public void sendGattTxnTimeoutMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        mStateMachine.mPendingOperation = SET_BCAST_CODE;
        mStateMachine.mPendingSourceId = 0;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_TIMEOUT, GATT_FAILURE),
                BassClientStateMachine.Connected.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(-1);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(-1);
    }

    @Test
    public void sendMessageForDeferring_inConnectedProcessingState_defersMessage() {
        initToConnectedProcessingState();

        mStateMachine.sendMessage(READ_BASS_CHARACTERISTICS);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(READ_BASS_CHARACTERISTICS)).isTrue();

        mStateMachine.sendMessage(START_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(START_SCAN_OFFLOAD)).isTrue();

        mStateMachine.sendMessage(STOP_SCAN_OFFLOAD);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(STOP_SCAN_OFFLOAD)).isTrue();

        mStateMachine.sendMessage(ADD_BCAST_SOURCE);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(ADD_BCAST_SOURCE)).isTrue();

        mStateMachine.sendMessage(UPDATE_BCAST_SOURCE);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(UPDATE_BCAST_SOURCE)).isTrue();

        mStateMachine.sendMessage(SET_BCAST_CODE);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(SET_BCAST_CODE)).isTrue();

        mStateMachine.sendMessage(REMOVE_BCAST_SOURCE);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(REMOVE_BCAST_SOURCE)).isTrue();

        mStateMachine.sendMessage(SWITCH_BCAST_SOURCE);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(SWITCH_BCAST_SOURCE)).isTrue();

        mStateMachine.sendMessage(INITIATE_PA_SYNC_TRANSFER);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(INITIATE_PA_SYNC_TRANSFER)).isTrue();

        mStateMachine.sendMessage(UPDATE_METADATA);
        mLooper.dispatchAll();
        assertThat(mStateMachine.hasDeferredMessagesSuper(UPDATE_METADATA)).isTrue();
    }

    @Test
    public void sendInvalidMessage_inConnectedProcessingState_doesNotChangeState() {
        initToConnectedProcessingState();

        mStateMachine.sendMessage(-1);
        mLooper.dispatchAll();
        verify(mBassClientService, never()).sendBroadcast(any(Intent.class), anyString(), any());
    }

    @Test
    public void dump_doesNotCrash() {
        mStateMachine.dump(new StringBuilder());
    }

    @Test
    public void sendAddBcastSourceMessage_NoResponseWrite() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        cb.onMtuChanged(null, 250, GATT_SUCCESS);
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        doReturn(true)
                .when(mBassClientService)
                .isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class));
        mStateMachine.sendMessage(ADD_BCAST_SOURCE, metadata);
        mLooper.dispatchAll();

        verify(mBassClientService).getCallbacks();
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
    }

    @Test
    public void sendAddBcastSourceMessage_LongWrite() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        cb.onMtuChanged(null, 23, GATT_SUCCESS);
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        doReturn(true)
                .when(mBassClientService)
                .isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class));
        mStateMachine.sendMessage(ADD_BCAST_SOURCE, metadata);
        mLooper.dispatchAll();

        verify(mBassClientService).getCallbacks();
        verify(callbacks).notifySourceAddFailed(any(), any(), anyInt());

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());
    }

    @Test
    public void getPendingOperationBroadcastId_withPendingSourceToSwitch() {
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mStateMachine.mPendingSourceToSwitch = metadata;
        mStateMachine.mPendingMetadata = null;

        assertThat(mStateMachine.getPendingOperationBroadcastId()).isEqualTo(TEST_BROADCAST_ID);
    }

    @Test
    public void getPendingOperationBroadcastId_withPendingMetadata() {
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mStateMachine.mPendingSourceToSwitch = null;
        mStateMachine.mPendingMetadata = metadata;

        assertThat(mStateMachine.getPendingOperationBroadcastId()).isEqualTo(TEST_BROADCAST_ID);
    }

    @Test
    public void getPendingOperationBroadcastId_withPendingSourceToSwitchAndPendingMetadata() {
        int broadcastId1 = 1;
        int broadcastId2 = 2;
        BluetoothLeBroadcastMetadata metadata1 = createBroadcastMetadata(broadcastId1);
        BluetoothLeBroadcastMetadata metadata2 = createBroadcastMetadata(broadcastId2);
        mStateMachine.mPendingSourceToSwitch = metadata1;
        mStateMachine.mPendingMetadata = metadata2;

        // mPendingSourceToSwitch should have priority
        assertThat(mStateMachine.getPendingOperationBroadcastId()).isEqualTo(broadcastId1);
    }

    @Test
    public void getPendingOperationBroadcastId_noPendingOperation() {
        mStateMachine.mPendingSourceToSwitch = null;
        mStateMachine.mPendingMetadata = null;

        assertThat(mStateMachine.getPendingOperationBroadcastId())
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void hasPendingSwitchingSourceOperation() {
        assertThat(mStateMachine.hasPendingSwitchingSourceOperation()).isFalse();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mStateMachine.mPendingSourceToSwitch = metadata;

        assertThat(mStateMachine.hasPendingSwitchingSourceOperation()).isTrue();
        assertThat(mStateMachine.hasPendingSwitchingSourceOperation(TEST_BROADCAST_ID)).isTrue();
        assertThat(mStateMachine.hasPendingSwitchingSourceOperation(TEST_BROADCAST_ID + 1))
                .isFalse();
    }

    private void initToDisconnectedState() {
        allowConnection(true);
        allowConnectGatt(true);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(BassClientStateMachine.Disconnected.class);
    }

    @Test
    public void cancelPendingAddBcastSourceMessage_inConnectedState() {
        initToConnectedState();

        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // verify local broadcast doesn't require active synced source
        doReturn(true)
                .when(mBassClientService)
                .isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class));

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());

        /* Verify if there is pending add source operation */
        assertThat(mStateMachine.hasPendingSourceOperation(metadata.getBroadcastId())).isTrue();

        assertThat(mStateMachine.mMsgWhats).contains(CANCEL_PENDING_SOURCE_OPERATION);
        assertThat(mStateMachine.mMsgAgr1).isEqualTo(TEST_BROADCAST_ID);

        /* Inject a cancel pending source operation event */
        Message msg = mStateMachine.obtainMessage(CANCEL_PENDING_SOURCE_OPERATION);
        msg.arg1 = metadata.getBroadcastId();
        mStateMachine.sendMessage(msg);
        mLooper.dispatchAll();

        /* Verify if pending add source operation is canceled */
        assertThat(mStateMachine.hasPendingSourceOperation(metadata.getBroadcastId())).isFalse();
    }

    @Test
    public void cancelPendingUpdateBcastSourceMessage_inConnectedState() {
        initToConnectedState();
        mStateMachine.connectGatt(true);
        mStateMachine.mNumOfBroadcastReceiverStates = 2;

        // Prepare mBluetoothLeBroadcastReceiveStates for test
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();
        int sourceId = 1;
        int paSync = BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        byte[] value =
                new byte[] {
                    (byte) sourceId, // sourceId
                    (byte) (mSourceTestDevice.getAddressType() & 0xFF), // sourceAddressType
                    Util.getByteAddress(mSourceTestDevice)[5],
                    Util.getByteAddress(mSourceTestDevice)[4],
                    Util.getByteAddress(mSourceTestDevice)[3],
                    Util.getByteAddress(mSourceTestDevice)[2],
                    Util.getByteAddress(mSourceTestDevice)[1],
                    Util.getByteAddress(mSourceTestDevice)[0], // sourceAddress
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
        doReturn(value).when(characteristic).getValue();
        doReturn(sourceId).when(characteristic).getInstanceId();
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();
        mStateMachine.mGattCallback.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        verify(scanControlPoint).setValue(any(byte[].class));
        verify(btGatt).writeCharacteristic(any());

        /* Verify if there is pending add source operation */
        assertThat(mStateMachine.hasPendingSourceOperation(metadata.getBroadcastId())).isTrue();

        assertThat(mStateMachine.mMsgWhats).contains(CANCEL_PENDING_SOURCE_OPERATION);
        assertThat(mStateMachine.mMsgAgr1).isEqualTo(TEST_BROADCAST_ID);

        /* Inject a cancel pending source operation event */
        Message msg = mStateMachine.obtainMessage(CANCEL_PENDING_SOURCE_OPERATION);
        msg.arg1 = metadata.getBroadcastId();
        mStateMachine.sendMessage(msg);
        mLooper.dispatchAll();

        /* Verify if pending add source operation is canceled */
        assertThat(mStateMachine.hasPendingSourceOperation(metadata.getBroadcastId())).isFalse();
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
                        eq(mDevice),
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
                        eq(mDevice),
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
                        eq(mDevice),
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
                        eq(mDevice),
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
                mStateMachine.obtainMessage(DISCONNECT), BassClientStateMachine.Disconnected.class);
        mLooper.dispatchAll();

        // Verify broadcast audio session is logged when source removed
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSync(
                        eq(mDevice),
                        eq(TEST_BROADCAST_ID),
                        eq(false),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x3)); // STATS_SYNC_AUDIO_SYNC_SUCCESS
    }

    @Test
    public void sinkConnected_queueAddingSourceForReceiveStateReady() {
        mStateMachine.connectGatt(true);
        BluetoothGattCallback cb = mStateMachine.mGattCallback;
        cb.onMtuChanged(null, 23, GATT_SUCCESS);
        initToConnectedState();

        mStateMachine.mNumOfBroadcastReceiverStates = 1;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        // Initial receive state with empty source device
        generateBroadcastReceiveStatesAndVerify(
                mEmptyTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        // Verify notifyBassStateReady is called
        verify(callbacks).notifyBassStateReady(eq(mDevice));
        assertThat(mStateMachine.isBassStateReady()).isTrue();
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
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mStateMachine.mPendingOperation = 0;
        mStateMachine.mPendingSourceId = 0;
        mStateMachine.mPendingMetadata = null;

        // update source without metadata
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(UPDATE_BCAST_SOURCE, sourceId, paSync, null),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(sourceId);
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
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mStateMachine.mPendingMetadata = metadata;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(REMOVE_BCAST_SOURCE, TEST_SOURCE_ID),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);

        mStateMachine.mPendingOperation = 0;
        mStateMachine.mPendingSourceId = 0;
        // Verify not removing source when PA is still synced
        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(0);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(0);

        // Verify removing source when PA is unsynced
        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(REMOVE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
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
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        // Verify pausing broadcast stream with updated metadata
        BluetoothLeBroadcastMetadata updatedMetadataPaused = getMetadataToPauseStream(metadata);
        mStateMachine.mPendingMetadata = updatedMetadataPaused;
        byte[] valueBisPaused = convertMetadataToUpdateSourceByteArray(updatedMetadataPaused);

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE,
                        TEST_SOURCE_ID,
                        BassConstants.FLAG_SYNC_PA | BassConstants.FLAG_SYNC_BIS_CHANNEL_PREFERENCE,
                        updatedMetadataPaused),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
        verify(scanControlPoint).setValue(eq(valueBisPaused));

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);
        mLooper.dispatchAll();
        Mockito.clearInvocations(scanControlPoint);

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0L);
        // After modifying source with no channels selected, verify metadata is updated to
        // updatedMetadataPaused
        assertThat(mStateMachine.getCurrentBroadcastMetadata(TEST_SOURCE_ID))
                .isEqualTo(updatedMetadataPaused);

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                1L << (TEST_CHANNEL_INDEX - 1));
        // After BIS synced, verify channels are selected as the original metadata
        assertThat(mStateMachine.getCurrentBroadcastMetadata(TEST_SOURCE_ID)).isEqualTo(metadata);

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0L);
        // After BIS unsynced, verify channels are still selected as the original metadata
        assertThat(mStateMachine.getCurrentBroadcastMetadata(TEST_SOURCE_ID)).isEqualTo(metadata);

        // Verify resuming broadcast stream with the original metadata
        byte[] valueBisResumed = convertMetadataToUpdateSourceByteArray(metadata);
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE,
                        TEST_SOURCE_ID,
                        BassConstants.INVALID_PA_SYNC_VALUE,
                        metadata),
                BassClientStateMachine.ConnectedProcessing.class);
        assertThat(mStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
        verify(scanControlPoint).setValue(eq(valueBisResumed));

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);

        mLooper.dispatchAll();
        Mockito.clearInvocations(scanControlPoint);
    }

    @Test
    public void remoteRemovedBroadcastSource_clearPendingOperations() {
        prepareInitialReceiveStateForGatt();

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING,
                0x1L);

        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        mStateMachine.mPendingOperation = 0;
        mStateMachine.mPendingSourceId = 0;
        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        mStateMachine.mPendingMetadata = metadata;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(
                        UPDATE_BCAST_SOURCE, TEST_SOURCE_ID, BassConstants.FLAG_SYNC_PA, metadata),
                BassClientStateMachine.ConnectedProcessing.class);

        assertThat(mStateMachine.mPendingOperation).isEqualTo(UPDATE_BCAST_SOURCE);
        assertThat(mStateMachine.mPendingSourceId).isEqualTo(TEST_SOURCE_ID);
        assertThat(mStateMachine.mPendingMetadata).isEqualTo(metadata);

        // remote removed source
        generateBroadcastReceiveStatesAndVerify(
                mEmptyTestDevice,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                0x0L);
        // verify mPendingMetadata is cleared
        assertThat(mStateMachine.mPendingMetadata).isEqualTo(null);
        assertThat(mStateMachine.isPendingRemove(TEST_SOURCE_ID)).isFalse();

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(GATT_TXN_PROCESSED),
                BassClientStateMachine.Connected.class);
        mLooper.dispatchAll();
        Mockito.clearInvocations(scanControlPoint);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_INTENT_BROADCAST_IN_STATE_MACHINE_CLEANUP)
    public void testDoQuit_inConnectedState_broadcastsDisconnectedIntent() {
        allowConnection(true);
        allowConnectGatt(true);

        // Transition the state machine from DISCONNECTED to CONNECTING
        mStateMachine.sendMessage(CONNECT);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);

        // Transition the state machine to CONNECTED
        mStateMachine.notifyConnectionStateChanged(GATT_SUCCESS, STATE_CONNECTED);
        mLooper.dispatchAll();
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_CONNECTING);

        // Call the method under test, which should trigger a disconnect intent
        mStateMachine.doQuit();

        // Verify that the connection state broadcast was made for the disconnection
        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, STATE_DISCONNECTED),
                hasExtra(EXTRA_PREVIOUS_STATE, STATE_CONNECTED));
    }

    @Test
    public void testIsSyncedToTheSource_checksSpecificFailureStates() {
        prepareInitialReceiveStateForGatt();

        int sourceId = TEST_SOURCE_ID;
        int paSyncStateIdle = BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE;
        int bigEncryptState = BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING;

        // CASE A: PA not synced, BIS indicates FAILED_SYNC_TO_BIG
        long bisSyncStateFailed = BassConstants.BCAST_RCVR_STATE_BIS_SYNC_FAILED_SYNC_TO_BIG;

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice, sourceId, paSyncStateIdle, bigEncryptState, bisSyncStateFailed);

        assertThat(mStateMachine.isSyncedToTheSource(sourceId)).isFalse();

        // CASE B: PA not synced, BIS indicates NOT_SYNC_TO_BIS
        long bisSyncStateNone = BassConstants.BCAST_RCVR_STATE_BIS_SYNC_NOT_SYNC_TO_BIS;

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice, sourceId, paSyncStateIdle, bigEncryptState, bisSyncStateNone);

        assertThat(mStateMachine.isSyncedToTheSource(sourceId)).isFalse();

        // CASE C: PA not synced, BIS indicates Valid Sync (e.g. bit 1 set)
        long bisSyncStateSuccess = 0x01; // Channel 1 synced

        generateBroadcastReceiveStatesAndVerify(
                mSourceTestDevice, sourceId, paSyncStateIdle, bigEncryptState, bisSyncStateSuccess);

        assertThat(mStateMachine.isSyncedToTheSource(sourceId)).isTrue();
    }

    private void initToConnectingState() {
        allowConnection(true);
        allowConnectGatt(true);
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(CONNECT), BassClientStateMachine.Connecting.class);
        Mockito.clearInvocations(mBassClientService);
    }

    private void initToConnectedState() {
        initToConnectingState();

        Message msg = mStateMachine.obtainMessage(CONNECTION_STATE_CHANGED);
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
        mStateMachine.mBluetoothGatt = btGatt;
        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(READ_BASS_CHARACTERISTICS, gattCharacteristic),
                BassClientStateMachine.ConnectedProcessing.class);
        Mockito.clearInvocations(mBassClientService);
    }

    private static boolean isConnectionIntentExpected(Class currentType, Class nextType) {
        if (currentType == nextType) {
            return false; // Same state, no intent expected
        }

        // ConnectedProcessing is an internal state that doesn't generate a broadcast
        return currentType != BassClientStateMachine.ConnectedProcessing.class
                && nextType != BassClientStateMachine.ConnectedProcessing.class;
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcastMultiplePermissions(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any());
    }

    private void verifyNoIntentSent() {
        mInOrder.verify(mAdapterService, never()).sendBroadcastAsUser(any(), any(), any(), any());
        mInOrder.verify(mAdapterService, never())
                .sendBroadcastWithMultiplePermissions(any(), any());
        mInOrder.verify(mAdapterService, never())
                .sendBroadcastMultiplePermissions(any(), any(), any());
    }

    private void verifyConnectionStateIntent(int newState, int oldState) {
        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
        assertThat(mStateMachine.getConnectionState()).isEqualTo(newState);
    }

    private <T> void sendMessageAndVerifyTransition(Message msg, Class<T> type) {
        Mockito.clearInvocations(mBassClientService);

        mStateMachine.sendMessage(msg);
        mLooper.dispatchAll();
        Class currentStateClass = mStateMachine.getCurrentState().getClass();
        if (isConnectionIntentExpected(currentStateClass, type)) {
            verifyIntentSent(
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
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(type);
    }

    private static BluetoothLeBroadcastMetadata createBroadcastMetadata() {
        return createBroadcastMetadata(TEST_BROADCAST_ID);
    }

    private static BluetoothLeBroadcastMetadata createBroadcastMetadata(int broadcastId) {
        final String testMacAddress = "00:11:22:33:44:55";
        final int testAdvertiserSid = 1234;
        final int testPaSyncInterval = 100;
        final int testPresentationDelayMs = 345;

        BluetoothDevice testDevice =
                getRealDevice(testMacAddress, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(testDevice, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(testAdvertiserSid)
                        .setBroadcastId(broadcastId)
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
            BluetoothLeBroadcastMetadata original) {
        BluetoothLeBroadcastMetadata.Builder metaDataBuilder =
                new BluetoothLeBroadcastMetadata.Builder(original);
        metaDataBuilder.clearSubgroup();

        for (BluetoothLeBroadcastSubgroup subgroup : original.getSubgroups()) {
            BluetoothLeBroadcastSubgroup.Builder subGroupBuilder =
                    new BluetoothLeBroadcastSubgroup.Builder(subgroup);
            subGroupBuilder.clearChannel();

            for (BluetoothLeBroadcastChannel channel : subgroup.getChannels()) {
                BluetoothLeBroadcastChannel.Builder channelBuilder =
                        new BluetoothLeBroadcastChannel.Builder(channel).setSelected(false);
                subGroupBuilder.addChannel(channelBuilder.build());
            }
            metaDataBuilder.addSubgroup(subGroupBuilder.build());
        }

        return metaDataBuilder.build();
    }

    private void prepareInitialReceiveStateForGatt() {
        initToConnectedState();
        mStateMachine.connectGatt(true);

        mStateMachine.mNumOfBroadcastReceiverStates = 2;
        BassClientService.Callbacks callbacks = Mockito.mock(BassClientService.Callbacks.class);
        doReturn(callbacks).when(mBassClientService).getCallbacks();

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata();
        doReturn(false)
                .when(mBassClientService)
                .isLocalBroadcast(any(BluetoothLeBroadcastMetadata.class));
        BassClientStateMachine.BluetoothGattTestableWrapper btGatt =
                Mockito.mock(BassClientStateMachine.BluetoothGattTestableWrapper.class);
        mStateMachine.mBluetoothGatt = btGatt;
        BluetoothGattCharacteristic scanControlPoint =
                Mockito.mock(BluetoothGattCharacteristic.class);
        mStateMachine.mBroadcastScanControlPoint = scanControlPoint;

        sendMessageAndVerifyTransition(
                mStateMachine.obtainMessage(ADD_BCAST_SOURCE, metadata),
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
                    Util.getByteAddress(sourceDevice)[5],
                    Util.getByteAddress(sourceDevice)[4],
                    Util.getByteAddress(sourceDevice)[3],
                    Util.getByteAddress(sourceDevice)[2],
                    Util.getByteAddress(sourceDevice)[1],
                    Util.getByteAddress(sourceDevice)[0], // sourceAddress
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
        doReturn(
                        Bytes.concat(
                                bigEncryptState
                                                == BluetoothLeBroadcastReceiveState
                                                        .BIG_ENCRYPTION_STATE_BAD_CODE
                                        ? Bytes.concat(value_base, badBroadcastCode, value_subgroup)
                                        : Bytes.concat(value_base, value_subgroup),
                                metadataHeader,
                                metadataPayload))
                .when(characteristic)
                .getValue();
        doReturn(sourceId).when(characteristic).getInstanceId();
        doReturn(BassConstants.BASS_BCAST_RECEIVER_STATE).when(characteristic).getUuid();

        mStateMachine.mGattCallback.onCharacteristicRead(null, characteristic, GATT_SUCCESS);
        mLooper.dispatchAll();

        assertThat(mStateMachine.getAllSources()).hasSize(1);
        BluetoothLeBroadcastReceiveState recvState = mStateMachine.getAllSources().get(0);

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
                ScanController scanController,
                Looper looper) {
            super(device, service, adapterService, scanController, looper);
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
