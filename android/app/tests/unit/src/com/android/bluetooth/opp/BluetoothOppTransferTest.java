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

package com.android.bluetooth.opp;

import static com.android.bluetooth.TestUtils.getRealDevice;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.opp.BluetoothOppTransfer.TRANSPORT_CONNECTED;
import static com.android.bluetooth.opp.BluetoothOppTransfer.TRANSPORT_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.NotificationManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Looper;
import android.os.Message;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.obex.BluetoothObexTransport;
import com.android.obex.ObexTransport;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Objects;

/** Test cases for {@link BluetoothOppTransfer}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothOppTransferTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private BluetoothOppObexSession mSession;
    @Mock private BluetoothMethodProxy mCallProxy;

    private BluetoothOppBatch mBluetoothOppBatch;
    private BluetoothOppTransfer mTransfer;
    private BluetoothOppTransfer.EventHandler mEventHandler;
    private BluetoothOppShareInfo mInitShareInfo;

    @Before
    public void setUp() throws Exception {
        mockGetSystemService(mAdapterService, NotificationManager.class);
        doCallRealMethod().when(mAdapterService).getBrEdrAddress(any(BluetoothDevice.class));
        doCallRealMethod().when(mAdapterService).getBrEdrAddress(any(String.class));
        doAnswer(
                        invocation -> {
                            String address = invocation.getArgument(0);
                            return getRealDevice(address);
                        })
                .when(mAdapterService)
                .getRemoteDevice(anyString());

        BluetoothMethodProxy.setInstanceForTesting(mCallProxy);
        doReturn(0)
                .when(mCallProxy)
                .contentResolverDelete(
                        any(),
                        nullable(Uri.class),
                        nullable(String.class),
                        nullable(String[].class));
        doReturn(0)
                .when(mCallProxy)
                .contentResolverUpdate(
                        any(),
                        nullable(Uri.class),
                        nullable(ContentValues.class),
                        nullable(String.class),
                        nullable(String[].class));

        mInitShareInfo =
                new BluetoothOppShareInfo(
                        8765,
                        Uri.parse("file://Idontknow/Justmadeitup"),
                        "this is a object that take 4 bytes",
                        "random.jpg",
                        "image/jpeg",
                        BluetoothShare.DIRECTION_INBOUND,
                        "01:23:45:67:89:AB",
                        BluetoothShare.VISIBILITY_VISIBLE,
                        BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED,
                        BluetoothShare.STATUS_PENDING,
                        1023,
                        42,
                        123456789,
                        false);
        mBluetoothOppBatch = new BluetoothOppBatch(mAdapterService, mInitShareInfo);
        mockGetBluetoothManager(mAdapterService);
        mTransfer = new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch, mSession);
        mEventHandler = mTransfer.new EventHandler(Looper.getMainLooper());
    }

    @After
    public void tearDown() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(null);
    }

    @Test
    public void onShareAdded_checkFirstPendingShare() {
        BluetoothOppShareInfo newShareInfo =
                new BluetoothOppShareInfo(
                        1,
                        Uri.parse("file://Idontknow/Justmadeitup"),
                        "this is a object that take 4 bytes",
                        "random.jpg",
                        "image/jpeg",
                        BluetoothShare.DIRECTION_INBOUND,
                        "01:23:45:67:89:AB",
                        BluetoothShare.VISIBILITY_VISIBLE,
                        BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED,
                        BluetoothShare.STATUS_PENDING,
                        1023,
                        42,
                        123456789,
                        false);

        doAnswer(
                        invocation -> {
                            assertThat((BluetoothOppShareInfo) invocation.getArgument(0))
                                    .isEqualTo(mInitShareInfo);
                            return null;
                        })
                .when(mSession)
                .addShare(any(BluetoothOppShareInfo.class));

        // This will trigger mTransfer.onShareAdded(), which will call mTransfer
        // .processCurrentShare(),
        // which will add the first pending share to the session
        mBluetoothOppBatch.addShare(newShareInfo);
        verify(mSession).addShare(any(BluetoothOppShareInfo.class));
    }

    @Test
    public void onBatchCanceled_checkStatus() {
        // This will trigger mTransfer.onBatchCanceled(),
        // which will then change the status of the batch accordingly
        mBluetoothOppBatch.cancelBatch();
        assertThat(mBluetoothOppBatch.mStatus).isEqualTo(Constants.BATCH_STATUS_FINISHED);
    }

    @Test
    public void start_receiverRegistered() {
        doReturn(true).when(mCallProxy).bluetoothAdapterIsEnabled(any());
        mTransfer.start();
        verify(mAdapterService).registerReceiver(any(), any(IntentFilter.class));
        // need this, or else the handler thread might throw in middle of the next test
        mTransfer.stop();
    }

    @Test
    public void stop_unregisterRegistered() {
        doReturn(true).when(mCallProxy).bluetoothAdapterIsEnabled(any());
        mTransfer.start();
        mTransfer.stop();
        verify(mAdapterService).unregisterReceiver(any());
    }

    @Test
    public void eventHandler_handleMessage_TRANSPORT_ERROR_connectThreadIsNull() {
        Message message = Message.obtain(mEventHandler, TRANSPORT_ERROR);
        mEventHandler.handleMessage(message);
        assertThat(mTransfer.mConnectThread).isNull();
        assertThat(mBluetoothOppBatch.mStatus).isEqualTo(Constants.BATCH_STATUS_FAILED);
    }

    @Test
    public void eventHandler_handleMessage_TRANSPORT_CONNECTED_obexSessionStarted() {
        ObexTransport transport = mock(BluetoothObexTransport.class);
        Message message = Message.obtain(mEventHandler, TRANSPORT_CONNECTED, transport);
        mEventHandler.handleMessage(message);
        assertThat(mBluetoothOppBatch.mStatus).isEqualTo(Constants.BATCH_STATUS_RUNNING);
    }

    @Test
    public void eventHandler_handleMessage_MSG_SHARE_COMPLETE_shareAdded() {
        Message message = Message.obtain(mEventHandler, BluetoothOppObexSession.MSG_SHARE_COMPLETE);

        mInitShareInfo =
                new BluetoothOppShareInfo(
                        123,
                        Uri.parse("file://Idontknow/Justmadeitup"),
                        "this is a object that take 4 bytes",
                        "random.jpg",
                        "image/jpeg",
                        BluetoothShare.DIRECTION_OUTBOUND,
                        "01:23:45:67:89:AB",
                        BluetoothShare.VISIBILITY_VISIBLE,
                        BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED,
                        BluetoothShare.STATUS_PENDING,
                        1023,
                        42,
                        123456789,
                        false);
        mBluetoothOppBatch = new BluetoothOppBatch(mAdapterService, mInitShareInfo);
        mTransfer = new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch, mSession);
        mEventHandler = mTransfer.new EventHandler(Looper.getMainLooper());
        mEventHandler.handleMessage(message);

        // Since there is still a share in mBluetoothOppBatch, it will be added into session
        verify(mSession).addShare(any(BluetoothOppShareInfo.class));
    }

    @Test
    public void eventHandler_handleMessage_MSG_SESSION_COMPLETE_batchFinished() {
        BluetoothOppShareInfo info = mock(BluetoothOppShareInfo.class);
        Message message =
                Message.obtain(mEventHandler, BluetoothOppObexSession.MSG_SESSION_COMPLETE, info);
        mEventHandler.handleMessage(message);

        assertThat(mBluetoothOppBatch.mStatus).isEqualTo(Constants.BATCH_STATUS_FINISHED);
    }

    @Test
    public void eventHandler_handleMessage_MSG_SESSION_ERROR_batchFailed() {
        BluetoothOppShareInfo info = mock(BluetoothOppShareInfo.class);
        Message message =
                Message.obtain(mEventHandler, BluetoothOppObexSession.MSG_SESSION_ERROR, info);
        mEventHandler.handleMessage(message);

        assertThat(mBluetoothOppBatch.mStatus).isEqualTo(Constants.BATCH_STATUS_FAILED);
    }

    @Test
    public void eventHandler_handleMessage_MSG_SHARE_INTERRUPTED_batchFailed() {
        mInitShareInfo =
                new BluetoothOppShareInfo(
                        123,
                        Uri.parse("file://Idontknow/Justmadeitup"),
                        "this is a object that take 4 bytes",
                        "random.jpg",
                        "image/jpeg",
                        BluetoothShare.DIRECTION_OUTBOUND,
                        "01:23:45:67:89:AB",
                        BluetoothShare.VISIBILITY_VISIBLE,
                        BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED,
                        BluetoothShare.STATUS_PENDING,
                        1023,
                        42,
                        123456789,
                        false);
        mBluetoothOppBatch = new BluetoothOppBatch(mAdapterService, mInitShareInfo);
        mTransfer = new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch, mSession);
        mEventHandler = mTransfer.new EventHandler(Looper.getMainLooper());

        BluetoothOppShareInfo info = mock(BluetoothOppShareInfo.class);
        Message message =
                Message.obtain(mEventHandler, BluetoothOppObexSession.MSG_SHARE_INTERRUPTED, info);
        mEventHandler.handleMessage(message);

        assertThat(mBluetoothOppBatch.mStatus).isEqualTo(Constants.BATCH_STATUS_FAILED);
    }

    @Test
    public void eventHandler_handleMessage_MSG_CONNECT_TIMEOUT() {
        Message message =
                Message.obtain(mEventHandler, BluetoothOppObexSession.MSG_CONNECT_TIMEOUT);
        BluetoothOppShareInfo newInfo =
                new BluetoothOppShareInfo(
                        321,
                        Uri.parse("file://Idontknow/Justmadeitup"),
                        "this is a object that take 4 bytes",
                        "random.jpg",
                        "image/jpeg",
                        BluetoothShare.DIRECTION_INBOUND,
                        "01:23:45:67:89:AB",
                        BluetoothShare.VISIBILITY_VISIBLE,
                        BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED,
                        BluetoothShare.STATUS_PENDING,
                        1023,
                        42,
                        123456789,
                        false);
        // Adding new info will assign value to mCurrentShare
        mBluetoothOppBatch.addShare(newInfo);
        mEventHandler.handleMessage(message);

        verify(mAdapterService)
                .sendBroadcast(
                        argThat(
                                arg ->
                                        arg.getAction()
                                                .equals(
                                                        BluetoothShare
                                                                .USER_CONFIRMATION_TIMEOUT_ACTION)));
    }

    @Test
    public void socketConnectThreadConstructors() {
        BluetoothDevice device = getTestDevice(23);
        BluetoothOppTransfer transfer =
                new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch);
        BluetoothOppTransfer.SocketConnectThread socketConnectThread =
                transfer.new SocketConnectThread(device, true);
        BluetoothOppTransfer.SocketConnectThread socketConnectThread2 =
                transfer.new SocketConnectThread(device, true, false, 0);
        assertThat(Objects.equals(socketConnectThread.mDevice, device)).isTrue();
        assertThat(Objects.equals(socketConnectThread2.mDevice, device)).isTrue();
    }

    @Test
    public void socketConnectThreadInterrupt() {
        final BluetoothDevice device = getTestDevice(34);
        BluetoothOppTransfer transfer =
                new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch);
        BluetoothOppTransfer.SocketConnectThread socketConnectThread =
                transfer.new SocketConnectThread(device, true);
        socketConnectThread.interrupt();
        assertThat(socketConnectThread.mIsInterrupted).isTrue();
    }

    @Test
    @SuppressWarnings("DoNotCall")
    public void socketConnectThreadRun_bluetoothDisabled_connectionFailed() {
        final BluetoothDevice device = getRealDevice(34);
        BluetoothOppTransfer transfer =
                new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch);
        BluetoothOppTransfer.SocketConnectThread socketConnectThread =
                transfer.new SocketConnectThread(device, true);
        transfer.mSessionHandler = mEventHandler;

        socketConnectThread.run();
        verify(mCallProxy).handlerSendEmptyMessage(any(), eq(TRANSPORT_ERROR));
    }

    @Test
    public void oppConnectionReceiver_onReceiveWithActionAclDisconnected_sendsConnectTimeout() {
        final BluetoothDevice device = getRealDevice("01:23:45:67:89:AB");
        BluetoothOppTransfer transfer =
                new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch);
        transfer.mCurrentShare = mInitShareInfo;
        transfer.mCurrentShare.mConfirm = BluetoothShare.USER_CONFIRMATION_PENDING;
        BluetoothOppTransfer.OppConnectionReceiver receiver = transfer.new OppConnectionReceiver();
        Intent intent = new Intent();
        intent.setAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        transfer.mSessionHandler = mEventHandler;
        receiver.onReceive(mAdapterService, intent);
        verify(mCallProxy)
                .handlerSendEmptyMessage(any(), eq(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT));
    }

    @Test
    public void oppConnectionReceiver_onReceiveWithActionSdpRecord_withoutSdpRecord() {
        final BluetoothDevice device = getRealDevice("01:23:45:67:89:AB");
        BluetoothOppTransfer transfer =
                new BluetoothOppTransfer(mAdapterService, mBluetoothOppBatch);
        transfer.mCurrentShare = mInitShareInfo;
        transfer.mCurrentShare.mConfirm = BluetoothShare.USER_CONFIRMATION_PENDING;
        transfer.mDevice = device;
        transfer.mSessionHandler = mEventHandler;
        BluetoothOppTransfer.OppConnectionReceiver receiver = transfer.new OppConnectionReceiver();
        Intent intent = new Intent();
        intent.setAction(BluetoothDevice.ACTION_SDP_RECORD);
        intent.putExtra(BluetoothDevice.EXTRA_UUID, BluetoothUuid.OBEX_OBJECT_PUSH);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        receiver.onReceive(mAdapterService, intent);

        // No sdp record was passed to intent => sends TRANSPORT_ERROR
        verify(mCallProxy).handlerSendEmptyMessage(any(), eq(TRANSPORT_ERROR));
    }
}
