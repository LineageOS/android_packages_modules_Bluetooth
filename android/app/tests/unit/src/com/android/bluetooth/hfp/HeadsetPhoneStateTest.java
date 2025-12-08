/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothDevice;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrengthUpdateRequest;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

/** Test cases for {@link HeadsetPhoneState}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetPhoneStateTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private SubscriptionManager mSubscriptionManager;

    private HeadsetPhoneState mHeadsetPhoneState;
    private HandlerThread mHandlerThread;
    private boolean testIsRunning;
    private InOrder mInOrder;

    @Before
    public void setUp() throws Exception {
        mInOrder = inOrder(mTelephonyManager);
        testIsRunning =
                SubscriptionManager.isValidSubscriptionId(
                        SubscriptionManager.getDefaultSubscriptionId());
        assumeTrue(testIsRunning);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mockGetSystemService(mAdapterService, SubscriptionManager.class, mSubscriptionManager);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        mockGetSystemService(mAdapterService, TelephonyManager.class, mTelephonyManager);

        mHandlerThread = new HandlerThread("HeadsetStateMachineTestHandlerThread");
        mHandlerThread.start();
        mHeadsetPhoneState =
                new HeadsetPhoneState(mAdapterService, mHeadsetService, mHandlerThread.getLooper());
    }

    @After
    public void tearDown() throws Exception {
        if (!testIsRunning) {
            return;
        }
        mHeadsetPhoneState.cleanup();
        mHandlerThread.quit();
    }

    /** Verify that {@link PhoneStateListener#LISTEN_NONE} should result in no subscription */
    @Test
    public void testListenForPhoneState_NoneResultsNoListen() {
        BluetoothDevice device1 = getTestDevice(1);
        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_NONE);
        verifyNoMoreInteractions(mTelephonyManager);
    }

    /**
     * Verify that {@link PhoneStateListener#LISTEN_SERVICE_STATE} should result in corresponding
     * subscription
     */
    @Test
    public void testListenForPhoneState_ServiceOnly() {
        BluetoothDevice device1 = getTestDevice(1);
        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_SERVICE_STATE);
        verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_SERVICE_STATE));
        verifyNoMoreInteractions(mTelephonyManager);
    }

    /**
     * Verify that {@link PhoneStateListener#LISTEN_SERVICE_STATE} and {@link
     * PhoneStateListener#LISTEN_SIGNAL_STRENGTHS} should result in corresponding subscription
     */
    @Test
    public void testListenForPhoneState_ServiceAndSignalStrength() {
        BluetoothDevice device1 = getTestDevice(1);
        int events =
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;
        mHeadsetPhoneState.listenForPhoneState(device1, events);
        mInOrder.verify(mTelephonyManager).listen(any(), eq(events));
        mInOrder.verify(mTelephonyManager)
                .setSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));
    }

    /**
     * Verify that partially turning off {@link PhoneStateListener#LISTEN_SIGNAL_STRENGTHS} update
     * should only unsubscribe signal strength update
     */
    @Test
    public void testListenForPhoneState_ServiceAndSignalStrengthUpdateTurnOffSignalStrength() {
        BluetoothDevice device1 = getTestDevice(1);
        int events =
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;

        mHeadsetPhoneState.listenForPhoneState(device1, events);
        mInOrder.verify(mTelephonyManager).listen(any(), eq(events));
        mInOrder.verify(mTelephonyManager)
                .setSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));

        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_SERVICE_STATE);
        mInOrder.verify(mTelephonyManager)
                .clearSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));
        mInOrder.verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        mInOrder.verify(mTelephonyManager)
                .listen(any(), eq(PhoneStateListener.LISTEN_SERVICE_STATE));
        verifyNoMoreInteractions(mTelephonyManager);
    }

    /** Verify that completely disabling all updates should unsubscribe from everything */
    @Test
    public void testListenForPhoneState_ServiceAndSignalStrengthUpdateTurnOffAll() {
        BluetoothDevice device1 = getTestDevice(1);
        int events =
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;

        mHeadsetPhoneState.listenForPhoneState(device1, events);
        mInOrder.verify(mTelephonyManager).listen(any(), eq(events));
        mInOrder.verify(mTelephonyManager)
                .setSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));

        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_NONE);
        mInOrder.verify(mTelephonyManager)
                .clearSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));
        mInOrder.verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        verifyNoMoreInteractions(mTelephonyManager);
    }

    /**
     * Verify that when multiple devices tries to subscribe to the same indicator, the same
     * subscription is not triggered twice. Also, when one of the device is unsubsidized from an
     * indicator, the other device still remain subscribed.
     */
    @Test
    public void testListenForPhoneState_MultiDevice_AllUpAllDown() {
        BluetoothDevice device1 = getTestDevice(1);
        BluetoothDevice device2 = getTestDevice(2);
        int events =
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;

        // Enabling updates from first device should trigger subscription
        mHeadsetPhoneState.listenForPhoneState(device1, events);
        mInOrder.verify(mTelephonyManager).listen(any(), eq(events));
        mInOrder.verify(mTelephonyManager)
                .setSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));

        // Enabling updates from second device should not trigger the same subscription
        mHeadsetPhoneState.listenForPhoneState(device2, events);

        // Disabling updates from first device should not cancel subscription
        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_NONE);

        // Disabling updates from second device should cancel subscription
        mHeadsetPhoneState.listenForPhoneState(device2, PhoneStateListener.LISTEN_NONE);
        mInOrder.verify(mTelephonyManager)
                .clearSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));
        mInOrder.verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        verifyNoMoreInteractions(mTelephonyManager);
    }

    /**
     * Verity that two device each partially subscribe to an indicator result in subscription to
     * both indicators. Also unsubscribing from one indicator from one device will not cause
     * unrelated indicator from other device from being unsubscribed.
     */
    @Test
    public void testListenForPhoneState_MultiDevice_PartialUpPartialDown() {
        BluetoothDevice device1 = getTestDevice(1);
        BluetoothDevice device2 = getTestDevice(2);
        int events =
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;

        // Partially enabling updates from first device should trigger partial subscription
        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_SERVICE_STATE);
        mInOrder.verify(mTelephonyManager)
                .listen(any(), eq(PhoneStateListener.LISTEN_SERVICE_STATE));

        // Partially enabling updates from second device should trigger partial subscription
        mHeadsetPhoneState.listenForPhoneState(device2, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        mInOrder.verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        mInOrder.verify(mTelephonyManager).listen(any(), eq(events));
        mInOrder.verify(mTelephonyManager)
                .setSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));

        // Partially disabling updates from first device should not cancel all subscription
        mHeadsetPhoneState.listenForPhoneState(device1, PhoneStateListener.LISTEN_NONE);
        mInOrder.verify(mTelephonyManager)
                .clearSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));
        mInOrder.verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        mInOrder.verify(mTelephonyManager)
                .listen(any(), eq(PhoneStateListener.LISTEN_SIGNAL_STRENGTHS));
        mInOrder.verify(mTelephonyManager)
                .setSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));

        // Partially disabling updates from second device should cancel subscription
        mHeadsetPhoneState.listenForPhoneState(device2, PhoneStateListener.LISTEN_NONE);
        mInOrder.verify(mTelephonyManager)
                .clearSignalStrengthUpdateRequest(any(SignalStrengthUpdateRequest.class));
        mInOrder.verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        verifyNoMoreInteractions(mTelephonyManager);
    }
}
