/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.bluetooth.telephony;

import static android.platform.test.flag.junit.DeviceFlagsValueProvider.createCheckFlagsRule;

import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.telephony.BluetoothInCallService.Result;
import static com.android.bluetooth.telephony.BluetoothInCallService.TerminationReason;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothLeCall;
import android.bluetooth.State;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.telecom.BluetoothCallQualityReport;
import android.telecom.Call;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.tbs.TbsService;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tests for {@link BluetoothInCallService} */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothInCallServiceTest {
    private static final String TAG = BluetoothInCallServiceTest.class.getSimpleName();

    @Rule public final CheckFlagsRule mCheckFlagsRule = createCheckFlagsRule();
    @Rule public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(AdapterService.class);

    @Mock private AdapterService mAdapterService;
    @Mock private TbsService mTbsService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private BluetoothInCallService.CallInfo mCallInfo;
    @Mock private TelephonyManager mTelephonyManager;

    private static final int TEST_DTMF_TONE = 0;
    private static final String TEST_ACCOUNT_ADDRESS = "//foo.com/";
    private static final int TEST_ACCOUNT_INDEX = 0;

    private static final int CALL_STATE_ACTIVE = 0;
    private static final int CALL_STATE_HELD = 1;
    private static final int CALL_STATE_DIALING = 2;
    private static final int CALL_STATE_ALERTING = 3;
    private static final int CALL_STATE_INCOMING = 4;
    private static final int CALL_STATE_WAITING = 5;
    private static final int CALL_STATE_IDLE = 6;
    private static final int CALL_STATE_DISCONNECTED = 7;
    // Terminate all held or set UDUB("busy") to a waiting call
    private static final int CHLD_TYPE_RELEASEHELD = 0;
    // Terminate all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD = 1;
    // Hold all active calls and accepts a waiting/held call
    private static final int CHLD_TYPE_HOLDACTIVE_ACCEPTHELD = 2;
    // Add all held calls to a conference
    private static final int CHLD_TYPE_ADDHELDTOCONF = 3;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private BluetoothInCallService mBluetoothInCallService;

    @Before
    public void setUp() {
        ExtendedMockito.doReturn(mAdapterService)
                .when(() -> AdapterService.deprecatedGetAdapterService());
        doReturn(Optional.of(mTbsService)).when(mAdapterService).getTbsService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();
        doReturn(true).when(mCallInfo).isNullCall(null);
        doReturn(false).when(mCallInfo).isNullCall(notNull());

        Context spiedContext = spy(new ContextWrapper(mContext));
        mockGetSystemService(spiedContext, TelephonyManager.class, mTelephonyManager);

        mBluetoothInCallService = new BluetoothInCallService(spiedContext, mCallInfo);
        mBluetoothInCallService.onCreate();
    }

    @Test
    public void onCreate_registerBearer_whenTbsServiceIsNull_doesNothing() {
        clearInvocations(mTbsService);

        Context spiedContext = spy(new ContextWrapper(mContext));
        mockGetSystemService(spiedContext, TelephonyManager.class, mTelephonyManager);
        final var bluetoothInCallService = new BluetoothInCallService(spiedContext, mCallInfo);
        bluetoothInCallService.onCreate();

        verify(mTbsService, never())
                .registerBearer(
                        anyString(),
                        any(),
                        anyString(),
                        anyList(),
                        anyInt(),
                        anyString(),
                        anyInt());
    }

    @Test
    public void onCreate_registerBearer_whenTbsServiceIsPresent_callsRegisterBearer() {
        clearInvocations(mTbsService);

        PhoneAccount fakePhoneAccount = makeQuickAccount("id0", TEST_ACCOUNT_INDEX);
        doReturn(fakePhoneAccount).when(mCallInfo).getBestPhoneAccount();
        String networkOperator = mBluetoothInCallService.getNetworkOperator();
        assertThat(networkOperator).isEqualTo("label0");

        Context spiedContext = spy(new ContextWrapper(mContext));
        mockGetSystemService(spiedContext, TelephonyManager.class, mTelephonyManager);
        final var bluetoothInCallService = new BluetoothInCallService(spiedContext, mCallInfo);
        bluetoothInCallService.onCreate();

        verify(mTbsService)
                .registerBearer(
                        anyString(),
                        any(),
                        anyString(),
                        anyList(),
                        anyInt(),
                        anyString(),
                        anyInt());
    }

    @Test
    public void headsetAnswerCall() {
        BluetoothCall mockCall = createRingingCall(UUID.randomUUID());

        boolean callAnswered = mBluetoothInCallService.answerCall();
        verify(mockCall).answer(any(int.class));

        assertThat(callAnswered).isTrue();
    }

    @Test
    public void headsetAnswerCallNull() {
        assertThat(mBluetoothInCallService.answerCall()).isFalse();
    }

    @Test
    public void headsetHangupCall() {
        BluetoothCall mockCall = createForegroundCall(UUID.randomUUID());

        boolean callHungup = mBluetoothInCallService.hangupCall();

        verify(mockCall).disconnect();
        assertThat(callHungup).isTrue();
    }

    @Test
    public void headsetHangupCallNull() {
        assertThat(mBluetoothInCallService.hangupCall()).isFalse();
    }

    @Test
    public void headsetSendDTMF() {
        BluetoothCall mockCall = createForegroundCall(UUID.randomUUID());

        boolean sentDtmf = mBluetoothInCallService.sendDtmf(TEST_DTMF_TONE);

        verify(mockCall).playDtmfTone(eq((char) TEST_DTMF_TONE));
        verify(mockCall).stopDtmfTone();
        assertThat(sentDtmf).isTrue();
    }

    @Test
    public void headsetSendDTMFNull() {
        assertThat(mBluetoothInCallService.sendDtmf(TEST_DTMF_TONE)).isFalse();
    }

    @Test
    public void getNetworkOperator() {
        PhoneAccount fakePhoneAccount = makeQuickAccount("id0", TEST_ACCOUNT_INDEX);
        doReturn(fakePhoneAccount).when(mCallInfo).getBestPhoneAccount();

        String networkOperator = mBluetoothInCallService.getNetworkOperator();
        assertThat(networkOperator).isEqualTo("label0");
    }

    @Test
    public void getNetworkOperatorNoPhoneAccount() {
        final String fakeOperator = "label1";
        doReturn(fakeOperator).when(mTelephonyManager).getNetworkOperatorName();

        String networkOperator = mBluetoothInCallService.getNetworkOperator();
        assertThat(networkOperator).isEqualTo(fakeOperator);
    }

    @Test
    public void getSubscriberNumber() {
        PhoneAccount fakePhoneAccount = makeQuickAccount("id0", TEST_ACCOUNT_INDEX);
        doReturn(fakePhoneAccount).when(mCallInfo).getBestPhoneAccount();

        String subscriberNumber = mBluetoothInCallService.getSubscriberNumber();
        assertThat(subscriberNumber).isEqualTo(TEST_ACCOUNT_ADDRESS + TEST_ACCOUNT_INDEX);
    }

    @Test
    public void getSubscriberNumberFallbackToTelephony() {
        final String fakeNumber = "8675309";
        doReturn(fakeNumber).when(mTelephonyManager).getLine1Number();

        String subscriberNumber = mBluetoothInCallService.getSubscriberNumber();
        assertThat(subscriberNumber).isEqualTo(fakeNumber);
    }

    @Test
    public void listCurrentCallsOneCall() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(Uri.parse("tel:555-000")).when(activeCall).getHandle();

        doReturn(List.of(activeCall)).when(mCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        verify(mHeadsetService)
                .clccResponse(1, 0, 0, 0, false, "555000", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    /**
     * Verifies bluetooth call quality reports are properly parceled and set as a call event to
     * Telecom.
     */
    @Test
    public void bluetoothCallQualityReport() {
        BluetoothCall activeCall = createForegroundCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);

        mBluetoothInCallService.sendBluetoothCallQualityReport(
                10, // long timestamp
                20, // int rssi
                30, // int snr
                40, // int retransmissionCount
                50, // int packetsNotReceiveCount
                60 // int negativeAcknowledgementCount
                );

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(activeCall)
                .sendCallEvent(
                        eq(BluetoothCallQualityReport.EVENT_BLUETOOTH_CALL_QUALITY_REPORT),
                        bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        BluetoothCallQualityReport report =
                (BluetoothCallQualityReport)
                        bundle.get(BluetoothCallQualityReport.EXTRA_BLUETOOTH_CALL_QUALITY_REPORT);
        assertThat(report.getSentTimestampMillis()).isEqualTo(10);
        assertThat(report.getRssiDbm()).isEqualTo(20);
        assertThat(report.getSnrDb()).isEqualTo(30);
        assertThat(report.getRetransmittedPacketsCount()).isEqualTo(40);
        assertThat(report.getPacketsNotReceivedCount()).isEqualTo(50);
        assertThat(report.getNegativeAcknowledgementCount()).isEqualTo(60);
    }

    @Test
    public void listCurrentCallsSilentRinging() {
        BluetoothCall silentRingingCall = createActiveCall(UUID.randomUUID());
        doReturn(Call.STATE_RINGING).when(silentRingingCall).getState();
        doReturn(true).when(silentRingingCall).isSilentRingingRequested();
        doReturn(Uri.parse("tel:555-000")).when(silentRingingCall).getHandle();

        doReturn(List.of(silentRingingCall)).when(mCallInfo).getBluetoothCalls();
        doReturn(silentRingingCall).when(mCallInfo).getRingingOrSimulatedRingingCall();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), silentRingingCall);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        verify(mHeadsetService, never())
                .clccResponse(1, 0, 0, 0, false, "555000", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void conferenceInProgressCDMA() {
        // If two calls are being conferenced and updateHeadsetWithCallState runs while this is
        // still occurring, it will look like there is an active and held BluetoothCall still while
        // we are transitioning into a conference.
        // BluetoothCall has been put into a CDMA "conference" with one BluetoothCall on hold.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall confCall1 = getMockCall(UUID.randomUUID());
        final BluetoothCall confCall2 = createHeldCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);
        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), confCall1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), confCall2);

        doReturn(List.of(parentCall, confCall1, confCall2)).when(mCallInfo).getBluetoothCalls();
        doReturn(Call.STATE_ACTIVE).when(confCall1).getState();
        doReturn(Call.STATE_ACTIVE).when(confCall2).getState();
        doReturn(true).when(confCall2).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(confCall1)
                .getGatewayInfo();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(confCall2)
                .getGatewayInfo();
        addCallCapability(parentCall, Connection.CAPABILITY_MERGE_CONFERENCE);
        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);
        Integer confCall1Id = confCall1.getId();
        doReturn(confCall1Id).when(parentCall).getGenericConferenceActiveChildCallId();
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(confCall1.getId(), confCall2.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();
        // Add links from child calls to parent
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(confCall1).getParentId();
        doReturn(parentId).when(confCall2).getParentId();

        mBluetoothInCallService.queryPhoneState(Optional.of(mHeadsetService));
        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 1, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);

        doReturn(true).when(parentCall).wasConferencePreviouslyMerged();
        List<BluetoothCall> children =
                mBluetoothInCallService.getBluetoothCallsByIds(parentCall.getChildrenIds());
        mBluetoothInCallService
                .getCallback(parentCall)
                .onChildrenChanged(Optional.of(mHeadsetService), parentCall, children);
        verify(mHeadsetService, times(2))
                .phoneStateChanged(
                        1, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);

        // Spurious BluetoothCall to onIsConferencedChanged.
        mBluetoothInCallService
                .getCallback(parentCall)
                .onChildrenChanged(Optional.of(mHeadsetService), parentCall, children);
        // Make sure the BluetoothCall has only occurred collectively 2 times (not on the third)
        verify(mHeadsetService, times(3))
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));
    }

    @Test
    public void listCurrentCallsCdmaHold() {
        // BluetoothCall has been put into a CDMA "conference" with one BluetoothCall on hold.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        final BluetoothCall foregroundCall = getMockCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0001")).when(foregroundCall).getHandle();
        final BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0002")).when(heldCall).getHandle();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), foregroundCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), heldCall);

        doReturn(List.of(parentCall, foregroundCall, heldCall)).when(mCallInfo).getBluetoothCalls();
        doReturn(Call.STATE_ACTIVE).when(foregroundCall).getState();
        doReturn(Call.STATE_ACTIVE).when(heldCall).getState();
        doReturn(true).when(heldCall).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(foregroundCall)
                .getGatewayInfo();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0002")))
                .when(heldCall)
                .getGatewayInfo();
        addCallCapability(parentCall, Connection.CAPABILITY_MERGE_CONFERENCE);
        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);

        Integer foregroundCallId = foregroundCall.getId();
        doReturn(foregroundCallId).when(parentCall).getGenericConferenceActiveChildCallId();
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(foregroundCall.getId(), heldCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();
        // Add links from child calls to parent
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(foregroundCall).getParentId();
        doReturn(parentId).when(heldCall).getParentId();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void listCurrentCallsCdmaConference() {
        // BluetoothCall is in a true CDMA conference
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall confCall1 = getMockCall(UUID.randomUUID());
        final BluetoothCall confCall2 = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(Uri.parse("tel:555-0001")).when(confCall1).getHandle();
        doReturn(Uri.parse("tel:555-0002")).when(confCall2).getHandle();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), confCall1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), confCall2);

        doReturn(List.of(parentCall, confCall1, confCall2)).when(mCallInfo).getBluetoothCalls();
        doReturn(Call.STATE_ACTIVE).when(confCall1).getState();
        doReturn(Call.STATE_ACTIVE).when(confCall2).getState();
        doReturn(true).when(confCall2).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(confCall1)
                .getGatewayInfo();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(confCall2)
                .getGatewayInfo();
        doReturn(true).when(parentCall).wasConferencePreviouslyMerged();
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(confCall1.getId(), confCall2.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();
        // Add links from child calls to parent
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(confCall1).getParentId();
        doReturn(parentId).when(confCall2).getParentId();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550000", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void waitingCallClccResponse() {
        BluetoothCall waitingCall = createRingingCall(UUID.randomUUID());
        doReturn(List.of(waitingCall)).when(mCallInfo).getBluetoothCalls();
        // This test does not define a value for getForegroundCall(), so this ringing
        // BluetoothCall will be treated as if it is a waiting BluetoothCall
        // when listCurrentCalls() is invoked.
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), waitingCall);

        doReturn(true).when(waitingCall).isIncoming();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(waitingCall)
                .getGatewayInfo();
        doReturn(Call.STATE_RINGING).when(waitingCall).getState();
        doReturn(Uri.parse("tel:555-0000")).when(waitingCall).getHandle();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_WAITING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mHeadsetService, times(2))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void newCallClccResponse() {
        BluetoothCall newCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(newCall)).when(mCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), newCall);

        doReturn(Call.STATE_NEW).when(newCall).getState();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mHeadsetService)
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void listCurrentCallsCallHandleChanged() {
        doReturn("").when(mTelephonyManager).getNetworkCountryIso();

        BluetoothCall activeCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(activeCall)).when(mCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);

        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(true).when(activeCall).isIncoming();
        doReturn(Uri.parse("tel:2135550000")).when(activeCall).getHandle();
        Log.w(TAG, "call handle" + Uri.parse("tel:2135550000"));
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:2135550000")))
                .when(activeCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_ACTIVE,
                        0,
                        false,
                        "2135550000",
                        PhoneNumberUtils.TOA_Unknown);

        // call handle changed
        doReturn(Uri.parse("tel:213-555-0000")).when(activeCall).getHandle();
        clearInvocations(mHeadsetService);
        Log.w(TAG, "call handle" + Uri.parse("tel:213-555-0000"));
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_ACTIVE,
                        0,
                        false,
                        "2135550000",
                        PhoneNumberUtils.TOA_Unknown);
    }

    @Test
    public void ringingCallClccResponse() {
        BluetoothCall ringingCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(ringingCall)).when(mCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);

        doReturn(Call.STATE_RINGING).when(ringingCall).getState();
        doReturn(true).when(ringingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0000")).when(ringingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(ringingCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_INCOMING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mHeadsetService, times(2))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void callClccCache() {
        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();
        BluetoothCall ringingCall = createForegroundCall(UUID.randomUUID());
        calls.add(ringingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);

        doReturn(Call.STATE_RINGING).when(ringingCall).getState();
        doReturn(true).when(ringingCall).isIncoming();
        doReturn(Uri.parse("tel:5550000")).when(ringingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:5550000")))
                .when(ringingCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        1,
                        CALL_STATE_INCOMING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);

        // Test Caching of old BluetoothCall indices in clcc
        doReturn(Call.STATE_ACTIVE).when(ringingCall).getState();
        BluetoothCall newHoldingCall = createHeldCall(UUID.randomUUID());
        calls.add(0, newHoldingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), newHoldingCall);

        doReturn(Call.STATE_HOLDING).when(newHoldingCall).getState();
        doReturn(true).when(newHoldingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0001")).when(newHoldingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(newHoldingCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 1, CALL_STATE_ACTIVE, 0, false, "5550000", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService, times(2)).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void alertingCallClccResponse() {
        BluetoothCall dialingCall = createForegroundCall(UUID.randomUUID());
        doReturn(List.of(dialingCall)).when(mCallInfo).getBluetoothCalls();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), dialingCall);

        doReturn(Call.STATE_DIALING).when(dialingCall).getState();
        doReturn(Uri.parse("tel:555-0000")).when(dialingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(dialingCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        0,
                        CALL_STATE_ALERTING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mHeadsetService, times(2))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void holdingCallClccResponse() {
        ArrayList<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();
        BluetoothCall dialingCall = createForegroundCall(UUID.randomUUID());
        calls.add(dialingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), dialingCall);

        doReturn(Call.STATE_DIALING).when(dialingCall).getState();
        doReturn(Uri.parse("tel:555-0000")).when(dialingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0000")))
                .when(dialingCall)
                .getGatewayInfo();
        BluetoothCall holdingCall = createHeldCall(UUID.randomUUID());
        calls.add(holdingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), holdingCall);

        doReturn(Call.STATE_HOLDING).when(holdingCall).getState();
        doReturn(true).when(holdingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0001")).when(holdingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(holdingCall)
                .getGatewayInfo();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1,
                        0,
                        CALL_STATE_ALERTING,
                        0,
                        false,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
        verify(mHeadsetService, times(3))
                .clccResponse(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyBoolean(),
                        nullable(String.class),
                        anyInt());
    }

    @Test
    public void listCurrentCallsImsConference() {
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());

        addCallCapability(parentCall, Connection.CAPABILITY_CONFERENCE_HAS_NO_CHILDREN);
        doReturn(true).when(parentCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(parentCall).getState();
        doReturn(true).when(parentCall).isIncoming();
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(List.of(parentCall)).when(mCallInfo).getBluetoothCalls();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        verify(mHeadsetService).clccResponse(1, 1, CALL_STATE_ACTIVE, 0, true, "5550000", 129);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void listCurrentCallsHeldImsCepConference() {
        BluetoothCall parentCall = createHeldCall(UUID.randomUUID());
        BluetoothCall childCall1 = createActiveCall(UUID.randomUUID());
        BluetoothCall childCall2 = createActiveCall(UUID.randomUUID());
        doReturn(List.of(parentCall, childCall1, childCall2)).when(mCallInfo).getBluetoothCalls();
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(Uri.parse("tel:555-0001")).when(childCall1).getHandle();
        doReturn(Uri.parse("tel:555-0002")).when(childCall2).getHandle();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), childCall1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), childCall2);

        addCallCapability(parentCall, Connection.CAPABILITY_MANAGE_CONFERENCE);
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(childCall1).getParentId();
        doReturn(parentId).when(childCall2).getParentId();
        List<Integer> childrenIds = Arrays.asList(childCall1.getId(), childCall2.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        doReturn(true).when(parentCall).isConference();
        doReturn(Call.STATE_HOLDING).when(parentCall).getState();
        doReturn(Call.STATE_ACTIVE).when(childCall1).getState();
        doReturn(Call.STATE_ACTIVE).when(childCall2).getState();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(parentCall).isIncoming();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_HELD, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 0, CALL_STATE_HELD, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    public void listCurrentCallsConferenceGetChildrenIsEmpty() {
        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        doReturn(List.of(conferenceCall)).when(mCallInfo).getBluetoothCalls();
        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();

        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), conferenceCall);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService).clccResponse(1, 1, 0, 0, true, "5551234", 129);
    }

    @Test
    public void listCurrentCallsConferenceEmptyChildrenInference() {
        doReturn("").when(mTelephonyManager).getNetworkCountryIso();

        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // active call is added
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        calls.add(activeCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);

        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(activeCall)
                .getGatewayInfo();

        // holding call is added
        BluetoothCall holdingCall = createHeldCall(UUID.randomUUID());
        calls.add(holdingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), holdingCall);

        doReturn(Call.STATE_HOLDING).when(holdingCall).getState();
        doReturn(true).when(holdingCall).isIncoming();
        doReturn(Uri.parse("tel:555-0002")).when(holdingCall).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0002")))
                .when(holdingCall)
                .getGatewayInfo();

        // needs to have at least one CLCC response before merge to enable call inference
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
        calls.clear();

        // calls merged for conference call
        DisconnectCause cause = new DisconnectCause(DisconnectCause.OTHER);
        doReturn(cause).when(activeCall).getDisconnectCause();
        doReturn(cause).when(holdingCall).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall, true);
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), holdingCall, true);

        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);

        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // parent call arrived, but children have not, then do inference on children
        calls.add(conferenceCall);
        assertThat(calls).hasSize(1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), conferenceCall);

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // real children arrive, no change on CLCC response
        calls.add(activeCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);
        doReturn(true).when(activeCall).isConference();
        calls.add(holdingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), holdingCall);
        doReturn(Call.STATE_ACTIVE).when(holdingCall).getState();
        doReturn(true).when(holdingCall).isConference();
        doReturn(List.of(1, 2)).when(conferenceCall).getChildrenIds();

        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // when call is terminated, children first removed, then parent
        cause = new DisconnectCause(DisconnectCause.LOCAL);
        doReturn(cause).when(activeCall).getDisconnectCause();
        doReturn(cause).when(holdingCall).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall, true);
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), holdingCall, true);
        calls.remove(activeCall);
        calls.remove(holdingCall);
        assertThat(calls).hasSize(1);

        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);

        // when parent is removed
        doReturn(cause).when(conferenceCall).getDisconnectCause();
        calls.remove(conferenceCall);
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), conferenceCall, true);

        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService).clccResponse(0, 0, 0, 0, false, null, 0);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_NON_CONFERENCE_CALL_HANGUP)
    public void endActiveCallWhenConferenceCallInHoldState() {
        doReturn("").when(mTelephonyManager).getNetworkCountryIso();

        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // Call 1 active call is added
        BluetoothCall activeCall_1 = createActiveCall(UUID.randomUUID());
        calls.add(activeCall_1);
        ManageCall(activeCall_1, "tel:555-0001", Call.STATE_ACTIVE);

        // Call 2 holding call is added
        BluetoothCall activeCall_2 = createHeldCall(UUID.randomUUID());
        calls.add(activeCall_2);
        ManageCall(activeCall_2, "tel:555-0002", Call.STATE_HOLDING);
        doReturn(true).when(activeCall_2).isIncoming();

        // calls merged for conference call
        DisconnectCause cause =
                new DisconnectCause(DisconnectCause.OTHER, "IMS_MERGED_SUCCESSFULLY");
        doReturn(cause).when(activeCall_1).getDisconnectCause();
        doReturn(cause).when(activeCall_2).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall_1, true);
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall_2, true);

        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);

        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // Conference created
        calls.add(conferenceCall);
        doReturn(3).when(conferenceCall).getParentId();
        doReturn(3).when(conferenceCall).getId();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), conferenceCall);

        // Call_1 and Call_2 are part of conference
        calls.add(activeCall_1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_1);
        doReturn(true).when(activeCall_1).isConference();
        calls.add(activeCall_2);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_2);
        doReturn(Call.STATE_ACTIVE).when(activeCall_2).getState();
        doReturn(true).when(activeCall_2).isConference();
        doReturn(List.of(1, 2)).when(conferenceCall).getChildrenIds();
        doReturn(conferenceCall).when(mCallInfo).getForegroundCall();

        // Call 3 is added
        BluetoothCall activeCall_3 = createActiveCall(UUID.randomUUID());
        doReturn(null).when(activeCall_3).getParentId();
        calls.add(activeCall_3);

        // Call 3 active call, Conference on hold
        doReturn(Call.STATE_HOLDING).when(conferenceCall).getState();
        ManageCall(activeCall_3, "tel:555-0003", Call.STATE_ACTIVE);
        doReturn(true).when(activeCall_3).isIncoming();

        mBluetoothInCallService.hangupCall();

        verify(activeCall_3).disconnect();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MERGE_CALL_WITH_HELD_CONFERENCE)
    public void mergeActiveCallWithConferenceCall() {
        List<BluetoothCall> calls = new ArrayList<>();
        // Call 1 active call is added
        BluetoothCall activeCall_1 = createActiveCall(UUID.randomUUID());
        calls.add(activeCall_1);

        // Call 2 holding call is added
        BluetoothCall activeCall_2 = createHeldCall(UUID.randomUUID());
        calls.add(activeCall_2);

        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);

        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();
        doReturn(true).when(conferenceCall).isConference();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // Conference created
        calls.add(conferenceCall);
        doReturn(3).when(conferenceCall).getParentId();
        doReturn(3).when(conferenceCall).getId();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), conferenceCall);
        // Call 3 is added
        BluetoothCall activeCall_3 = createActiveCall(UUID.randomUUID());
        doReturn(null).when(activeCall_3).getParentId();
        calls.add(activeCall_3);

        // Call 3 active call, Conference on hold
        doReturn(Call.STATE_HOLDING).when(conferenceCall).getState();
        ManageCall(activeCall_3, "tel:555-0003", Call.STATE_ACTIVE);

        doReturn(conferenceCall).when(mCallInfo).getActiveCall();
        ArrayList<Integer> conferenceableCalls = new ArrayList<>();
        conferenceableCalls.add(activeCall_3.getId());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_3);
        doReturn(conferenceableCalls).when(activeCall_3).getConferenceableCalls();

        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_ADDHELDTOCONF);
        verify(activeCall_3).conference(activeCall_3);
        assertThat(didProcess).isTrue();
    }

    @Test
    public void conferenceLastCallIndexIsMaintained() throws Exception {
        doReturn("").when(mTelephonyManager).getNetworkCountryIso();

        List<BluetoothCall> calls = new ArrayList<>();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // Call 1 active call is added
        BluetoothCall activeCall_1 = createActiveCall(UUID.randomUUID());
        calls.add(activeCall_1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_1);

        doReturn(Call.STATE_ACTIVE).when(activeCall_1).getState();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall_1).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0001")))
                .when(activeCall_1)
                .getGatewayInfo();

        // Call 2 holding call is added
        BluetoothCall activeCall_2 = createHeldCall(UUID.randomUUID());
        calls.add(activeCall_2);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_2);

        doReturn(Call.STATE_HOLDING).when(activeCall_2).getState();
        doReturn(true).when(activeCall_2).isIncoming();
        doReturn(Uri.parse("tel:555-0002")).when(activeCall_2).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse("tel:555-0002")))
                .when(activeCall_2)
                .getGatewayInfo();

        // needs to have at least one CLCC response before merge to enable call inference
        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, false, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_HELD, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
        calls.clear();

        // calls merged for conference call
        DisconnectCause cause =
                new DisconnectCause(DisconnectCause.OTHER, "IMS_MERGED_SUCCESSFULLY");
        doReturn(cause).when(activeCall_1).getDisconnectCause();
        doReturn(cause).when(activeCall_2).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall_1, true);
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall_2, true);

        BluetoothCall conferenceCall = createActiveCall(UUID.randomUUID());
        addCallCapability(conferenceCall, Connection.CAPABILITY_MANAGE_CONFERENCE);

        doReturn(Uri.parse("tel:555-1234")).when(conferenceCall).getHandle();
        doReturn(true).when(conferenceCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(conferenceCall).getState();
        doReturn(true).when(conferenceCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(conferenceCall).isIncoming();
        doReturn(calls).when(mCallInfo).getBluetoothCalls();

        // parent call arrived, but children have not, then do inference on children
        calls.add(conferenceCall);
        assertThat(calls).hasSize(1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), conferenceCall);

        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // real children arrive, no change on CLCC response
        calls.add(activeCall_1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_1);
        doReturn(true).when(activeCall_1).isConference();
        calls.add(activeCall_2);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall_2);
        doReturn(Call.STATE_ACTIVE).when(activeCall_2).getState();
        doReturn(true).when(activeCall_2).isConference();
        doReturn(List.of(1, 2)).when(conferenceCall).getChildrenIds();

        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);
        verify(mHeadsetService)
                .clccResponse(
                        1, 0, CALL_STATE_ACTIVE, 0, true, "5550001", PhoneNumberUtils.TOA_Unknown);
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, true, "5550002", PhoneNumberUtils.TOA_Unknown);

        // Call 1 Disconnected and removed from conf
        doReturn(Call.STATE_DISCONNECTED).when(activeCall_1).getState();
        cause = new DisconnectCause(DisconnectCause.OTHER);
        doReturn(cause).when(activeCall_1).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall_1, true);
        doReturn(false).when(activeCall_1).isConference();
        calls.remove(activeCall_1);
        assertThat(calls).hasSize(2);

        // Call 2 removed from conf
        doReturn(cause).when(activeCall_2).getDisconnectCause();
        mBluetoothInCallService.onCallRemoved(Optional.of(mHeadsetService), activeCall_2, true);
        doReturn(false).when(activeCall_2).isConference();

        clearInvocations(mHeadsetService);
        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        // Index 2 is retained
        verify(mHeadsetService)
                .clccResponse(
                        2, 1, CALL_STATE_ACTIVE, 0, false, "5550002", PhoneNumberUtils.TOA_Unknown);
    }

    @Test
    public void queryPhoneState() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:5550000")).when(ringingCall).getHandle();

        mBluetoothInCallService.queryPhoneState(Optional.of(mHeadsetService));
        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_INCOMING,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);
    }

    @Test
    public void cDMAConferenceQueryState() {
        BluetoothCall parentConfCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall confCall1 = getMockCall(UUID.randomUUID());
        final BluetoothCall confCall2 = getMockCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), confCall1);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), confCall2);
        doReturn(Uri.parse("tel:555-0000")).when(parentConfCall).getHandle();
        addCallCapability(parentConfCall, Connection.CAPABILITY_SWAP_CONFERENCE);
        doReturn(true).when(parentConfCall).wasConferencePreviouslyMerged();
        doReturn(true).when(parentConfCall).isConference();
        List<Integer> childrenIds = Arrays.asList(confCall1.getId(), confCall2.getId());
        doReturn(childrenIds).when(parentConfCall).getChildrenIds();

        mBluetoothInCallService.queryPhoneState(Optional.of(mHeadsetService));
        verify(mHeadsetService, times(2))
                .phoneStateChanged(
                        1, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void processChldTypeReleaseHeldRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        Log.i("BluetoothInCallService", "asdf start " + Integer.toString(ringingCall.hashCode()));

        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_RELEASEHELD);

        verify(ringingCall).reject(eq(false), nullable(String.class));
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldTypeReleaseHeldHold() {
        BluetoothCall onHoldCall = createHeldCall(UUID.randomUUID());
        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_RELEASEHELD);

        verify(onHoldCall).disconnect();
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldTypeReleaseHeld_noRingingOrHeldCall_returnsTrue() {
        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_RELEASEHELD);

        // The method should return true even if no action was taken.
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldReleaseActiveRinging() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD);

        verify(activeCall).disconnect();
        verify(ringingCall).answer(any(int.class));
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldReleaseActiveHold() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD);

        verify(activeCall).disconnect();
        // BluetoothCall unhold will occur as part of CallsManager auto-unholding
        // the background BluetoothCall on its own.
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldHoldActiveRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(ringingCall).answer(any(int.class));
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldHoldActiveUnhold() {
        BluetoothCall heldCall = createHeldCall(UUID.randomUUID());

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(heldCall).unhold();
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldHoldActiveHold() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        addCallCapability(activeCall, Connection.CAPABILITY_HOLD);

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(activeCall).hold();
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldHoldActiveAcceptHeld_noActionableCall_returnsTrue() {
        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        // The method should return true even if no action was taken.
        assertThat(didProcess).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HOLD_CONFERENCE_CALL_FROM_REMOTE)
    public void processChldHoldActiveConfCall() {
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        BluetoothCall childCall = createActiveCall(UUID.randomUUID());
        doReturn(List.of(parentCall, childCall)).when(mCallInfo).getBluetoothCalls();
        doReturn(Uri.parse("tel:555-0000")).when(parentCall).getHandle();
        doReturn(Uri.parse("tel:555-0001")).when(childCall).getHandle();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), childCall);

        addCallCapability(parentCall, Connection.CAPABILITY_MANAGE_CONFERENCE);
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(childCall).getParentId();
        List<Integer> childrenIds = Arrays.asList(childCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        doReturn(true).when(parentCall).isConference();
        doReturn(Call.STATE_ACTIVE).when(parentCall).getState();
        doReturn(Call.STATE_ACTIVE).when(childCall).getState();
        doReturn(true).when(parentCall).hasProperty(Call.Details.PROPERTY_GENERIC_CONFERENCE);
        doReturn(true).when(parentCall).isIncoming();

        mBluetoothInCallService.listCurrentCalls(mHeadsetService);

        addCallCapability(parentCall, Connection.CAPABILITY_HOLD);

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(parentCall).hold();

        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldAddHeldToConfHolding() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        addCallCapability(activeCall, Connection.CAPABILITY_MERGE_CONFERENCE);

        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_ADDHELDTOCONF);

        verify(activeCall).mergeConference();
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldAddHeldToConf() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        BluetoothCall conferenceableCall = getMockCall(UUID.randomUUID());
        ArrayList<Integer> conferenceableCalls = new ArrayList<>();
        conferenceableCalls.add(conferenceableCall.getId());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), conferenceableCall);

        doReturn(conferenceableCalls).when(activeCall).getConferenceableCalls();

        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_ADDHELDTOCONF);

        verify(activeCall).conference(conferenceableCall);
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldAddHeldToConf_noActiveCall_returnsTrue() {
        boolean didProcess =
                mBluetoothInCallService.processChld(mHeadsetService, CHLD_TYPE_ADDHELDTOCONF);

        // The method should return true even if no action was taken.
        assertThat(didProcess).isTrue();
    }

    @Test
    public void processChldHoldActiveSwapConference() {
        // Create an active CDMA BluetoothCall with a BluetoothCall on hold
        // and simulate a swapConference().
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall foregroundCall = getMockCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0001")).when(foregroundCall).getHandle();
        final BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0002")).when(heldCall).getHandle();
        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);
        doReturn(true).when(parentCall).isConference();
        doReturn(Uri.parse("tel:555-0000")).when(heldCall).getHandle();
        List<Integer> childrenIds = Arrays.asList(foregroundCall.getId(), heldCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        boolean didProcess =
                mBluetoothInCallService.processChld(
                        mHeadsetService, CHLD_TYPE_HOLDACTIVE_ACCEPTHELD);

        verify(parentCall).swapConference();
        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 1, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
        assertThat(didProcess).isTrue();
    }

    // Testing the CallsManager Listener Functionality on Bluetooth
    @Test
    public void onCallAddedRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555000")).when(ringingCall).getHandle();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_INCOMING,
                        "555000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);
    }

    @Test
    public void silentRingingCallState() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(true).when(ringingCall).isSilentRingingRequested();
        doReturn(Uri.parse("tel:555000")).when(ringingCall).getHandle();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);

        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));
    }

    @Test
    public void onCallAddedCdmaActiveHold() {
        // BluetoothCall has been put into a CDMA "conference" with one BluetoothCall on hold.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        final BluetoothCall foregroundCall = getMockCall(UUID.randomUUID());
        final BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0001")).when(foregroundCall).getHandle();
        doReturn(Uri.parse("tel:555-0002")).when(heldCall).getHandle();
        addCallCapability(parentCall, Connection.CAPABILITY_MERGE_CONFERENCE);
        doReturn(true).when(parentCall).isConference();
        List<Integer> childrenIds = Arrays.asList(foregroundCall.getId(), heldCall.getId());
        doReturn(childrenIds).when(parentCall).getChildrenIds();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);

        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 1, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onCallRemoved() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);
        doReturn(null).when(mCallInfo).getActiveCall();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();

        mBluetoothInCallService.onCallRemoved(
                Optional.of(mHeadsetService), activeCall, true /* forceRemoveCallback */);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onDetailsChangeExternalRemovesCall() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);
        doReturn(null).when(mCallInfo).getActiveCall();
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();

        doReturn(true).when(activeCall).isExternalCall();
        mBluetoothInCallService
                .getCallback(activeCall)
                .onDetailsChanged(Optional.of(mHeadsetService), activeCall, null);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onDetailsChangeExternalAddsCall() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);
        doReturn(Uri.parse("tel:555-0001")).when(activeCall).getHandle();
        BluetoothInCallService.CallStateCallback callBack =
                mBluetoothInCallService.getCallback(activeCall);

        doReturn(true).when(activeCall).isExternalCall();
        callBack.onDetailsChanged(Optional.of(mHeadsetService), activeCall, null);

        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onCallStateChangedConnectingCall() {
        BluetoothCall activeCall = getMockCall(UUID.randomUUID());
        BluetoothCall connectingCall = getMockCall(UUID.randomUUID());
        doReturn(Call.STATE_CONNECTING).when(connectingCall).getState();

        doReturn(List.of(connectingCall, activeCall)).when(mCallInfo).getBluetoothCalls();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), connectingCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);

        mBluetoothInCallService
                .getCallback(activeCall)
                .onStateChanged(activeCall, Call.STATE_HOLDING);

        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));
    }

    @Test
    public void onCallAddedAudioProcessing() {
        BluetoothCall call = getMockCall(UUID.randomUUID());
        doReturn(Call.STATE_AUDIO_PROCESSING).when(call).getState();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), call);

        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));
    }

    @Test
    public void onCallStateChangedRingingToAudioProcessing() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555000")).when(ringingCall).getHandle();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_INCOMING,
                        "555000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);

        doReturn(Call.STATE_AUDIO_PROCESSING).when(ringingCall).getState();
        doReturn(null).when(mCallInfo).getRingingOrSimulatedRingingCall();

        mBluetoothInCallService
                .getCallback(ringingCall)
                .onStateChanged(ringingCall, Call.STATE_AUDIO_PROCESSING);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onCallStateChangedAudioProcessingToSimulatedRinging() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(ringingCall).getHandle();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);
        mBluetoothInCallService
                .getCallback(ringingCall)
                .onStateChanged(ringingCall, Call.STATE_SIMULATED_RINGING);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_INCOMING,
                        "555-0000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);
    }

    @Test
    public void onCallStateChangedAudioProcessingToActive() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());
        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);
        mBluetoothInCallService
                .getCallback(activeCall)
                .onStateChanged(activeCall, Call.STATE_ACTIVE);

        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onCallStateChangedDialing() {
        BluetoothCall activeCall = createActiveCall(UUID.randomUUID());

        // make "mLastState" STATE_CONNECTING
        BluetoothInCallService.CallStateCallback callback =
                mBluetoothInCallService.new CallStateCallback(Call.STATE_CONNECTING);
        mBluetoothInCallService.mCallbacks.put(activeCall.getId(), callback);

        mBluetoothInCallService
                .mCallbacks
                .get(activeCall.getId())
                .onStateChanged(activeCall, Call.STATE_DIALING);

        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));
    }

    @Test
    public void onCallStateChangedAlerting() {
        BluetoothCall outgoingCall = createOutgoingCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), outgoingCall);
        mBluetoothInCallService
                .getCallback(outgoingCall)
                .onStateChanged(outgoingCall, Call.STATE_DIALING);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0, 0, CALL_STATE_DIALING, "", PhoneNumberUtils.TOA_Unknown, null, false);
        verify(mHeadsetService)
                .phoneStateChanged(
                        0, 0, CALL_STATE_ALERTING, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onCallStateChangedDisconnected() {
        BluetoothCall disconnectedCall = createDisconnectedCall(UUID.randomUUID());
        doReturn(true).when(mCallInfo).hasOnlyDisconnectedCalls();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), disconnectedCall);
        mBluetoothInCallService
                .getCallback(disconnectedCall)
                .onStateChanged(disconnectedCall, Call.STATE_DISCONNECTED);
        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_DISCONNECTED,
                        "",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);
    }

    @Test
    public void onCallStateChanged() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(ringingCall).getHandle();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), ringingCall);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_INCOMING,
                        "555-0000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);

        // Switch to active
        doReturn(null).when(mCallInfo).getRingingOrSimulatedRingingCall();
        doReturn(ringingCall).when(mCallInfo).getActiveCall();

        mBluetoothInCallService
                .getCallback(ringingCall)
                .onStateChanged(ringingCall, Call.STATE_ACTIVE);

        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 0, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void onCallStateChangedGSMSwap() {
        BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:555-0000")).when(heldCall).getHandle();
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), heldCall);
        doReturn(2).when(mCallInfo).getNumHeldCalls();

        mBluetoothInCallService.getCallback(heldCall).onStateChanged(heldCall, Call.STATE_HOLDING);

        verify(mHeadsetService, never())
                .phoneStateChanged(
                        0,
                        2,
                        CALL_STATE_HELD,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);
    }

    @Test
    public void onParentOnChildrenChanged() {
        // Start with two calls that are being merged into a CDMA conference call. The
        // onIsConferencedChanged method will be called multiple times during the call. Make sure
        // that the bluetooth phone state is updated properly.
        BluetoothCall parentCall = createActiveCall(UUID.randomUUID());
        BluetoothCall activeCall = getMockCall(UUID.randomUUID());
        BluetoothCall heldCall = createHeldCall(UUID.randomUUID());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), parentCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), activeCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), heldCall);
        Integer parentId = parentCall.getId();
        doReturn(parentId).when(activeCall).getParentId();
        doReturn(parentId).when(heldCall).getParentId();

        List<Integer> calls = new ArrayList<>();
        calls.add(activeCall.getId());

        doReturn(calls).when(parentCall).getChildrenIds();
        doReturn(true).when(parentCall).isConference();

        addCallCapability(parentCall, Connection.CAPABILITY_SWAP_CONFERENCE);

        clearInvocations(mHeadsetService);
        // Be sure that onIsConferencedChanged rejects spurious changes during set up of
        // CDMA "conference"
        mBluetoothInCallService
                .getCallback(activeCall)
                .onParentChanged(Optional.of(mHeadsetService), activeCall);
        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));

        mBluetoothInCallService
                .getCallback(heldCall)
                .onParentChanged(Optional.of(mHeadsetService), heldCall);
        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));

        mBluetoothInCallService
                .getCallback(parentCall)
                .onChildrenChanged(
                        Optional.of(mHeadsetService),
                        parentCall,
                        mBluetoothInCallService.getBluetoothCallsByIds(calls));
        verify(mHeadsetService, never())
                .phoneStateChanged(
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        nullable(String.class),
                        eq(false));

        calls.add(heldCall.getId());
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), heldCall);
        mBluetoothInCallService
                .getCallback(parentCall)
                .onChildrenChanged(
                        Optional.of(mHeadsetService),
                        parentCall,
                        mBluetoothInCallService.getBluetoothCallsByIds(calls));
        verify(mHeadsetService)
                .phoneStateChanged(
                        1, 1, CALL_STATE_IDLE, "", PhoneNumberUtils.TOA_Unknown, null, false);
    }

    @Test
    public void bluetoothAdapterReceiver() {
        BluetoothCall ringingCall = createRingingCall(UUID.randomUUID());
        doReturn(Uri.parse("tel:5550000")).when(ringingCall).getHandle();

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, State.ON);
        mBluetoothInCallService.mBluetoothAdapterReceiver =
                mBluetoothInCallService.new BluetoothAdapterReceiver();
        mBluetoothInCallService.mBluetoothAdapterReceiver.onReceive(
                mBluetoothInCallService, intent);

        verify(mHeadsetService)
                .phoneStateChanged(
                        0,
                        0,
                        CALL_STATE_INCOMING,
                        "5550000",
                        PhoneNumberUtils.TOA_Unknown,
                        null,
                        false);
    }

    @Test
    public void clear() {
        mBluetoothInCallService.clear();

        assertThat(mBluetoothInCallService.mBluetoothAdapterReceiver).isNull();
    }

    @Test
    public void getBearerTechnology() {
        doReturn(TelephonyManager.NETWORK_TYPE_GSM).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_GSM);

        doReturn(TelephonyManager.NETWORK_TYPE_GPRS).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_2G);

        doReturn(TelephonyManager.NETWORK_TYPE_EVDO_B).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_3G);

        doReturn(TelephonyManager.NETWORK_TYPE_TD_SCDMA)
                .when(mTelephonyManager)
                .getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_WCDMA);

        doReturn(TelephonyManager.NETWORK_TYPE_LTE).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_LTE);

        doReturn(TelephonyManager.NETWORK_TYPE_1xRTT).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_CDMA);

        doReturn(TelephonyManager.NETWORK_TYPE_HSPAP).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_4G);

        doReturn(TelephonyManager.NETWORK_TYPE_IWLAN).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_WIFI);

        doReturn(TelephonyManager.NETWORK_TYPE_NR).when(mTelephonyManager).getDataNetworkType();
        assertThat(mBluetoothInCallService.getBearerTechnology())
                .isEqualTo(BluetoothInCallService.BEARER_TECHNOLOGY_5G);
    }

    @Test
    public void getTbsTerminationReason() {
        BluetoothCall call = getMockCall(UUID.randomUUID());

        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.FAIL);

        DisconnectCause cause = new DisconnectCause(DisconnectCause.BUSY, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.LINE_BUSY);

        cause = new DisconnectCause(DisconnectCause.REJECTED, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.REMOTE_HANGUP);

        cause = new DisconnectCause(DisconnectCause.LOCAL, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        mBluetoothInCallService.mIsTerminatedByClient = false;
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.SERVER_HANGUP);

        cause = new DisconnectCause(DisconnectCause.LOCAL, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        mBluetoothInCallService.mIsTerminatedByClient = true;
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.CLIENT_HANGUP);

        cause = new DisconnectCause(DisconnectCause.ERROR, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.NETWORK_CONGESTION);

        cause =
                new DisconnectCause(
                        DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.INVALID_URI);

        cause = new DisconnectCause(DisconnectCause.ERROR, null, null, null, 1);
        doReturn(cause).when(call).getDisconnectCause();
        assertThat(mBluetoothInCallService.getTbsTerminationReason(call))
                .isEqualTo(TerminationReason.NETWORK_CONGESTION);
    }

    @Test
    public void onDestroy() {
        assertThat(BluetoothInCallService.getInstance()).isNotNull();

        mBluetoothInCallService.onDestroy();

        assertThat(BluetoothInCallService.getInstance()).isNull();
    }

    @Test
    public void onAcceptCall_withUnknownCallId() throws Exception {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mLeCallControlClient.onAcceptCall(requestId, unknownCallId);
        verify(mTbsService)
                .requestResult(anyInt(), eq(requestId), eq(Result.ERROR_UNKNOWN_CALL_ID));
    }

    @Test
    public void onTerminateCall_withUnknownCallId() throws Exception {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mLeCallControlClient.onTerminateCall(requestId, unknownCallId);
        verify(mTbsService)
                .requestResult(anyInt(), eq(requestId), eq(Result.ERROR_UNKNOWN_CALL_ID));
    }

    @Test
    public void onHoldCall_withUnknownCallId() throws Exception {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mLeCallControlClient.onHoldCall(requestId, unknownCallId);
        verify(mTbsService)
                .requestResult(anyInt(), eq(requestId), eq(Result.ERROR_UNKNOWN_CALL_ID));
    }

    @Test
    public void onUnholdCall_withUnknownCallId() throws Exception {
        int requestId = 1;
        UUID unknownCallId = UUID.randomUUID();
        mBluetoothInCallService.mLeCallControlClient.onUnholdCall(requestId, unknownCallId);
        verify(mTbsService)
                .requestResult(anyInt(), eq(requestId), eq(Result.ERROR_UNKNOWN_CALL_ID));
    }

    @Test
    public void onJoinCalls() throws Exception {
        int requestId = 1;
        UUID baseCallId = UUID.randomUUID();
        UUID firstJoiningCallId = UUID.randomUUID();
        UUID secondJoiningCallId = UUID.randomUUID();

        BluetoothCall baseCall = createActiveCall(baseCallId);
        BluetoothCall firstCall = createRingingCall(firstJoiningCallId);
        BluetoothCall secondCall = createRingingCall(secondJoiningCallId);

        doReturn(Call.STATE_ACTIVE).when(baseCall).getState();
        doReturn(Call.STATE_RINGING).when(firstCall).getState();
        doReturn(Call.STATE_RINGING).when(secondCall).getState();

        doReturn(List.of(baseCall, firstCall, secondCall)).when(mCallInfo).getBluetoothCalls();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), baseCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), firstCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), secondCall);

        doReturn(Uri.parse("tel:111-111")).when(baseCall).getHandle();
        doReturn(Uri.parse("tel:222-222")).when(firstCall).getHandle();
        doReturn(Uri.parse("tel:333-333")).when(secondCall).getHandle();

        doReturn(baseCall).when(mCallInfo).getCallByCallId(baseCallId);
        doReturn(firstCall).when(mCallInfo).getCallByCallId(firstJoiningCallId);
        doReturn(secondCall).when(mCallInfo).getCallByCallId(secondJoiningCallId);

        List<UUID> uuids = List.of(baseCallId, firstJoiningCallId, secondJoiningCallId);
        mBluetoothInCallService.mLeCallControlClient.onJoinCalls(requestId, uuids);
        verify(mTbsService).requestResult(anyInt(), eq(requestId), eq(Result.SUCCESS));
        verify(baseCall, times(2)).conference(any(BluetoothCall.class));
    }

    @Test
    public void onJoinCalls_omitDoubledCalls() throws Exception {
        int requestId = 1;
        UUID baseCallId = UUID.randomUUID();
        UUID firstJoiningCallId = UUID.randomUUID();

        BluetoothCall baseCall = createActiveCall(baseCallId);
        BluetoothCall firstCall = createRingingCall(firstJoiningCallId);

        doReturn(List.of(baseCall, firstCall)).when(mCallInfo).getBluetoothCalls();

        doReturn(Call.STATE_ACTIVE).when(baseCall).getState();
        doReturn(Call.STATE_RINGING).when(firstCall).getState();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), baseCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), firstCall);

        doReturn(Uri.parse("tel:111-111")).when(baseCall).getHandle();
        doReturn(Uri.parse("tel:222-222")).when(firstCall).getHandle();

        doReturn(baseCall).when(mCallInfo).getCallByCallId(eq(baseCallId));
        doReturn(firstCall).when(mCallInfo).getCallByCallId(eq(firstJoiningCallId));

        List<UUID> uuids = List.of(baseCallId, firstJoiningCallId);
        mBluetoothInCallService.mLeCallControlClient.onJoinCalls(requestId, uuids);
        verify(mTbsService).requestResult(anyInt(), eq(requestId), eq(Result.SUCCESS));
        verify(baseCall).conference(any(BluetoothCall.class));
    }

    @Test
    public void onJoinCalls_omitNullCalls() throws Exception {
        int requestId = 1;
        UUID baseCallId = UUID.randomUUID();
        UUID firstJoiningCallId = UUID.randomUUID();
        UUID secondJoiningCallId = UUID.randomUUID();

        BluetoothCall baseCall = createActiveCall(baseCallId);
        BluetoothCall firstCall = createRingingCall(firstJoiningCallId);
        BluetoothCall secondCall = createRingingCall(secondJoiningCallId);

        doReturn(List.of(baseCall, firstCall, secondCall)).when(mCallInfo).getBluetoothCalls();

        doReturn(Call.STATE_ACTIVE).when(baseCall).getState();
        doReturn(Call.STATE_RINGING).when(firstCall).getState();
        doReturn(Call.STATE_RINGING).when(secondCall).getState();

        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), baseCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), firstCall);
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), secondCall);

        doReturn(Uri.parse("tel:111-111")).when(baseCall).getHandle();
        doReturn(Uri.parse("tel:222-222")).when(firstCall).getHandle();
        doReturn(Uri.parse("tel:333-333")).when(secondCall).getHandle();

        doReturn(baseCall).when(mCallInfo).getCallByCallId(null);
        doReturn(firstCall).when(mCallInfo).getCallByCallId(firstJoiningCallId);
        doReturn(secondCall).when(mCallInfo).getCallByCallId(secondJoiningCallId);

        List<UUID> uuids = List.of(baseCallId, firstJoiningCallId, secondJoiningCallId);
        mBluetoothInCallService.mLeCallControlClient.onJoinCalls(requestId, uuids);
        verify(mTbsService).requestResult(anyInt(), eq(requestId), eq(Result.SUCCESS));
        verify(firstCall).conference(any(BluetoothCall.class));
    }

    @Test
    public void toLeCallDecodesEncodedPlusSign() {
        UUID callId = UUID.randomUUID();
        BluetoothCall activeCall = createActiveCall(callId);

        Uri handle = Uri.parse("tel:%2B12345");

        doReturn(handle).when(activeCall).getHandle();
        doReturn(Call.STATE_ACTIVE).when(activeCall).getState();
        doReturn(true).when(activeCall).isIncoming();
        doReturn(null).when(activeCall).getGatewayInfo();
        doReturn(null).when(activeCall).getParentId();

        BluetoothLeCall leCall = mBluetoothInCallService.toLeCall(activeCall);
        assertThat(leCall.getUri()).isEqualTo("tel:+12345");
    }

    private static void addCallCapability(BluetoothCall call, int capability) {
        doReturn(true).when(call).can(eq(capability));
    }

    private BluetoothCall createActiveCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mCallInfo).getActiveCall();
        return call;
    }

    private BluetoothCall createRingingCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mCallInfo).getRingingOrSimulatedRingingCall();
        return call;
    }

    private BluetoothCall createHeldCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mCallInfo).getHeldCall();
        return call;
    }

    private BluetoothCall createOutgoingCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mCallInfo).getOutgoingCall();
        return call;
    }

    private BluetoothCall createDisconnectedCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mCallInfo).getCallByState(Call.STATE_DISCONNECTED);
        return call;
    }

    private BluetoothCall createForegroundCall(UUID uuid) {
        BluetoothCall call = getMockCall(uuid);
        doReturn(call).when(mCallInfo).getForegroundCall();
        return call;
    }

    private static ComponentName makeQuickConnectionServiceComponentName() {
        return new ComponentName(
                "com.android.server.telecom.tests",
                "com.android.server.telecom.tests.MockConnectionService");
    }

    private static PhoneAccountHandle makeQuickAccountHandle(String id) {
        return new PhoneAccountHandle(
                makeQuickConnectionServiceComponentName(), id, Binder.getCallingUserHandle());
    }

    private static PhoneAccount.Builder makeQuickAccountBuilder(String id, int idx) {
        return new PhoneAccount.Builder(makeQuickAccountHandle(id), "label" + idx);
    }

    private static PhoneAccount makeQuickAccount(String id, int idx) {
        return makeQuickAccountBuilder(id, idx)
                .setAddress(Uri.parse(TEST_ACCOUNT_ADDRESS + idx))
                .setSubscriptionAddress(Uri.parse("tel:555-000" + idx))
                .setCapabilities(idx)
                .setShortDescription("desc" + idx)
                .build();
    }

    private static BluetoothCall getMockCall(UUID uuid) {
        BluetoothCall call = mock(com.android.bluetooth.telephony.BluetoothCall.class);
        Integer integerUuid = uuid.hashCode();
        doReturn(integerUuid).when(call).getId();
        return call;
    }

    private void ManageCall(BluetoothCall call, String TeleString, int STATE) {
        mBluetoothInCallService.onCallAdded(Optional.of(mHeadsetService), call);
        doReturn(STATE).when(call).getState();
        doReturn(Uri.parse(TeleString)).when(call).getHandle();
        doReturn(new GatewayInfo(null, null, Uri.parse(TeleString))).when(call).getGatewayInfo();
    }
}
