/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.mapclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasPackage;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.inOrder;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.SdpMasRecord;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Telephony.Sms;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;

import com.android.bluetooth.ObexAppParameters;
import com.android.bluetooth.TestLooper;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.obex.HeaderSet;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import com.google.common.truth.Correspondence;

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

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class MapClientStateMachineTest {
    private static final String TAG = MapClientStateMachineTest.class.getSimpleName();

    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();

    @Mock private AdapterService mAdapterService;
    @Mock private MapClientService mService;
    @Mock private MapClientContent mDatabase;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private MasClient mMasClient;
    @Mock private RequestPushMessage mRequestPushMessage;
    @Mock private RequestGetMessagesListingForOwnNumber mRequestOwnNumberCompletedWithNumber;
    @Mock private RequestGetMessagesListingForOwnNumber mRequestOwnNumberIncompleteSearch;
    @Mock private RequestGetMessage mRequestGetMessage;
    @Mock private RequestGetMessagesListing mRequestGetMessagesListing;

    private static final long PENDING_INTENT_TIMEOUT_MS = 3_000;
    private static final boolean MESSAGE_SEEN = true;
    private static final boolean MESSAGE_NOT_SEEN = false;

    private static final String SMS_HANDLE = "0001";
    private static final String MMS_HANDLE = "0002";

    private static final String TEST_MESSAGE_HANDLE = "0123456789000032";
    private static final String TEST_MESSAGE = "Hello World!";
    private static final String SENT_PATH = "telecom/msg/sent";
    private static final Uri[] TEST_CONTACTS_ONE_PHONENUM = new Uri[] {Uri.parse("tel://5551234")};
    private static final String TEST_DATETIME = "19991231T235959";
    private static final String TEST_OWN_PHONE_NUMBER = "555-1234";
    private static final Correspondence<Request, String> GET_FOLDER_NAME =
            Correspondence.transforming(
                    MapClientStateMachineTest::getFolderNameFromRequestGetMessagesListing,
                    "has folder name of");
    private static final String ACTION_MESSAGE_SENT =
            "com.android.bluetooth.mapclient.MapClientStateMachineTest.action.MESSAGE_SENT";
    private static final String ACTION_MESSAGE_DELIVERED =
            "com.android.bluetooth.mapclient.MapClientStateMachineTest.action.MESSAGE_DELIVERED";

    private final BluetoothDevice mDevice = getTestDevice(74);
    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private Bmessage mTestIncomingSmsBmessage;
    private Bmessage mTestIncomingMmsBmessage;
    private MceStateMachine mStateMachine;
    private SentDeliveryReceiver mSentDeliveryReceiver;
    private TestLooper mLooper;
    private InOrder mInOrder;

    private static class SentDeliveryReceiver extends BroadcastReceiver {
        private final CountDownLatch mActionReceivedLatch;

        SentDeliveryReceiver() {
            mActionReceivedLatch = new CountDownLatch(1);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "onReceive: Action=" + intent.getAction());
            if (ACTION_MESSAGE_SENT.equals(intent.getAction())
                    || ACTION_MESSAGE_DELIVERED.equals(intent.getAction())) {
                mActionReceivedLatch.countDown();
            } else {
                Log.i(TAG, "unhandled action.");
            }
        }

        public boolean isActionReceived(long timeout) {
            boolean result = false;
            try {
                result = mActionReceivedLatch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Latch await", e);
            }
            return result;
        }
    }

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.progressionOf(
                Flags.FLAG_HANDLE_DELIVERY_SENDING_FAILURE_EVENTS,
                Flags.FLAG_USE_ENTIRE_MESSAGE_HANDLE);
    }

    public MapClientStateMachineTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();

        mInOrder = inOrder(mService);

        MockSmsContentProvider contentProvider = new MockSmsContentProvider();
        MockContentResolver contentResolver = new MockContentResolver();
        contentResolver.addProvider("sms", contentProvider);
        contentResolver.addProvider("mms", contentProvider);
        contentResolver.addProvider("mms-sms", contentProvider);

        when(mService.getContentResolver()).thenReturn(contentResolver);

        doReturn(mTargetContext.getResources()).when(mService).getResources();

        when(mMasClient.makeRequest(any(Request.class))).thenReturn(true);
        mStateMachine =
                new MceStateMachine(
                        mService,
                        mDevice,
                        mAdapterService,
                        mLooper.getLooper(),
                        mMasClient,
                        mDatabase);
        mLooper.dispatchAll();
        verifyStateTransitionAndIntent(
                STATE_DISCONNECTED, STATE_CONNECTING);

        when(mRequestOwnNumberCompletedWithNumber.isSearchCompleted()).thenReturn(true);
        when(mRequestOwnNumberCompletedWithNumber.getOwnNumber()).thenReturn(TEST_OWN_PHONE_NUMBER);
        when(mRequestOwnNumberIncompleteSearch.isSearchCompleted()).thenReturn(false);
        when(mRequestOwnNumberIncompleteSearch.getOwnNumber()).thenReturn(null);

        createTestMessages();

        when(mRequestGetMessage.getMessage()).thenReturn(mTestIncomingSmsBmessage);
        when(mRequestGetMessage.getHandle()).thenReturn(SMS_HANDLE);

        when(mService.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.isSmsCapable()).thenReturn(false);

        // Set up receiver for 'Sent' and 'Delivered' PendingIntents
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(ACTION_MESSAGE_DELIVERED);
        filter.addAction(ACTION_MESSAGE_SENT);
        mSentDeliveryReceiver = new SentDeliveryReceiver();
        mTargetContext.registerReceiver(mSentDeliveryReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @After
    public void tearDown() throws Exception {
        if (mStateMachine != null) {
            mStateMachine.doQuit();
        }

        mTargetContext.unregisterReceiver(mSentDeliveryReceiver);
    }

    /** Test that default state is STATE_CONNECTING */
    @Test
    public void testDefaultConnectingState() {
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    /**
     * Test transition from STATE_CONNECTING --> (receive MSG_MAS_DISCONNECTED) -->
     * STATE_DISCONNECTED
     */
    @Test
    public void testStateTransitionFromConnectingToDisconnected() {
        setupSdpRecordReceipt();
        sendAndDispatchMessage(MceStateMachine.MSG_MAS_DISCONNECTED);

        verifyStateTransitionAndIntent(STATE_CONNECTING, STATE_DISCONNECTED);
    }

    @Test
    public void masConnected_whenConnecting_isConnected() {
        setupSdpRecordReceipt();
        sendAndDispatchMessage(MceStateMachine.MSG_MAS_CONNECTED);
        verifyStateTransitionAndIntent(STATE_CONNECTING, STATE_CONNECTED);
    }

    @Test
    public void masDisconnected_whenConnected_isDisconnected() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_DISCONNECTED);
        verifyStateTransitionAndIntent(STATE_DISCONNECTING, STATE_DISCONNECTED);
    }

    /** Test receiving an empty event report */
    @Test
    public void testReceiveEmptyEvent() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        // Send an empty notification event, verify the mStateMachine is still connected
        sendAndDispatchMessage(MceStateMachine.MSG_NOTIFICATION);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    /** Test set message status */
    @Test
    public void testSetMessageStatus() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        // broadcast request is sent to change state from STATE_CONNECTING to STATE_CONNECTED
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
        assertThat(mStateMachine.setMessageStatus("123456789AB", BluetoothMapClient.READ)).isTrue();
    }

    /** Test MceStateMachine#disconnect */
    @Test
    public void testDisconnect() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        mStateMachine.disconnect();
        mLooper.dispatchAll();
        verifyStateTransitionAndIntent(STATE_CONNECTED, STATE_DISCONNECTING);

        verify(mMasClient).shutdown();
        sendAndDispatchMessage(MceStateMachine.MSG_MAS_DISCONNECTED);
        verifyStateTransitionAndIntent(
                STATE_DISCONNECTING, STATE_DISCONNECTED);
    }

    /** Test disconnect timeout */
    @Test
    public void testDisconnectTimeout() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        mStateMachine.disconnect();
        mLooper.dispatchAll();
        verifyStateTransitionAndIntent(STATE_CONNECTED, STATE_DISCONNECTING);

        mLooper.moveTimeForward(MceStateMachine.DISCONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();
        verifyStateTransitionAndIntent(STATE_DISCONNECTING, STATE_DISCONNECTED);
    }

    /** Test sending a message to a phone */
    @Test
    public void testSendSMSMessageToPhone() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        String testMessage = "Hello World!";
        Uri[] contacts = new Uri[] {Uri.parse("tel://5551212")};

        verify(mMasClient, never()).makeRequest(any(RequestPushMessage.class));
        mStateMachine.sendMapMessage(contacts, testMessage, null, null);
        mLooper.dispatchAll();
        verify(mMasClient).makeRequest(any(RequestPushMessage.class));
    }

    /** Test sending a message to an email */
    @Test
    public void testSendSMSMessageToEmail() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        String testMessage = "Hello World!";
        Uri[] contacts = new Uri[] {Uri.parse("mailto://sms-test@google.com")};

        verify(mMasClient, never()).makeRequest(any(RequestPushMessage.class));
        mStateMachine.sendMapMessage(contacts, testMessage, null, null);
        mLooper.dispatchAll();
        verify(mMasClient).makeRequest(any(RequestPushMessage.class));
    }

    /** Test message sent successfully */
    @Test
    public void testSMSMessageSent() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        when(mRequestPushMessage.getMsgHandle()).thenReturn(SMS_HANDLE);
        when(mRequestPushMessage.getBMsg()).thenReturn(mTestIncomingSmsBmessage);
        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestPushMessage);

        verify(mDatabase)
                .storeMessage(
                        eq(mTestIncomingSmsBmessage), eq(SMS_HANDLE), any(), eq(MESSAGE_SEEN));
    }

    /**
     * Preconditions: - In {@code STATE_CONNECTED}. - {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT} has been
     * set. - Next stage of connection process has NOT begun, i.e.: - Request for Notification
     * Registration not sent - Request for MessageListing of SENT folder not sent - Request for
     * MessageListing of INBOX folder not sent
     */
    private void testGetOwnNumber_setup() {
        masConnected_whenConnecting_isConnected();
        verify(mMasClient, never()).makeRequest(any(RequestSetNotificationRegistration.class));
        verify(mMasClient, never()).makeRequest(any(RequestGetMessagesListing.class));
        assertThat(
                        mStateMachine
                                .getHandler()
                                .hasMessages(MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT))
                .isTrue();
    }

    /**
     * Assert whether the next stage of connection process has begun, i.e., whether the following
     * {@link Request} are sent or not: - Request for Notification Registration, - Request for
     * MessageListing of SENT folder (to start downloading), - Request for MessageListing of INBOX
     * folder (to start downloading).
     */
    private void testGetOwnNumber_assertNextStageStarted(boolean hasStarted) {
        if (hasStarted) {
            verify(mMasClient).makeRequest(any(RequestSetNotificationRegistration.class));
            verify(mMasClient, times(2)).makeRequest(any(RequestGetMessagesListing.class));

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(mMasClient, atLeastOnce()).makeRequest(requestCaptor.capture());
            // There will be multiple calls to {@link MasClient#makeRequest} with different
            // {@link Request} subtypes; not all of them will be {@link
            // RequestGetMessagesListing}.
            List<Request> capturedRequests = requestCaptor.getAllValues();
            assertThat(capturedRequests)
                    .comparingElementsUsing(GET_FOLDER_NAME)
                    .contains(MceStateMachine.FOLDER_INBOX);
            assertThat(capturedRequests)
                    .comparingElementsUsing(GET_FOLDER_NAME)
                    .contains(MceStateMachine.FOLDER_SENT);
        } else {
            verify(mMasClient, never()).makeRequest(any(RequestSetNotificationRegistration.class));
            verify(mMasClient, never()).makeRequest(any(RequestGetMessagesListing.class));
        }
    }

    /**
     * Preconditions: - See {@link testGetOwnNumber_setup}.
     *
     * <p>Actions: - Send a {@code MSG_MAS_REQUEST_COMPLETED} with a {@link
     * RequestGetMessagesListingForOwnNumber} object that has completed its search.
     *
     * <p>Outcome: - {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT} has been cancelled. - Next stage of
     * connection process has begun, i.e.: - Request for Notification Registration is made. -
     * Request for MessageListing of SENT folder is made (to start downloading). - Request for
     * MessageListing of INBOX folder is made (to start downloading).
     */
    @Test
    public void testGetOwnNumberCompleted() {
        testGetOwnNumber_setup();

        sendAndDispatchMessage(
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestOwnNumberCompletedWithNumber);

        verify(mMasClient, never()).makeRequest(eq(mRequestOwnNumberCompletedWithNumber));
        assertThat(
                        mStateMachine
                                .getHandler()
                                .hasMessages(MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT))
                .isFalse();
        testGetOwnNumber_assertNextStageStarted(true);
    }

    /**
     * Preconditions: - See {@link testGetOwnNumber_setup}.
     *
     * <p>Actions: - Send a {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT}.
     *
     * <p>Outcome: - {@link MasClient#abortRequest} invoked on a {@link
     * RequestGetMessagesListingForOwnNumber}. - Any existing {@code MSG_MAS_REQUEST_COMPLETED}
     * (corresponding to a {@link RequestGetMessagesListingForOwnNumber}) has been dropped. - Next
     * stage of connection process has begun, i.e.: - Request for Notification Registration is made.
     * - Request for MessageListing of SENT folder is made (to start downloading). - Request for
     * MessageListing of INBOX folder is made (to start downloading).
     */
    @Test
    public void testGetOwnNumberTimedOut() {
        testGetOwnNumber_setup();

        sendAndDispatchMessage(
                MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT, mRequestOwnNumberIncompleteSearch);

        verify(mMasClient).abortRequest(mRequestOwnNumberIncompleteSearch);
        assertThat(
                        mStateMachine
                                .getHandler()
                                .hasMessages(MceStateMachine.MSG_MAS_REQUEST_COMPLETED))
                .isFalse();
        testGetOwnNumber_assertNextStageStarted(true);
    }

    /**
     * Preconditions: - See {@link testGetOwnNumber_setup}.
     *
     * <p>Actions: - Send a {@code MSG_MAS_REQUEST_COMPLETED} with a {@link
     * RequestGetMessagesListingForOwnNumber} object that has not completed its search.
     *
     * <p>Outcome: - {@link Request} made to continue searching for own number (using existing/same
     * {@link Request}). - {@code MSG_SEARCH_OWN_NUMBER_TIMEOUT} has not been cancelled. - Next
     * stage of connection process has not begun, i.e.: - No Request for Notification Registration,
     * - No Request for MessageListing of SENT folder is made (to start downloading), - No Request
     * for MessageListing of INBOX folder is made (to start downloading).
     */
    @Test
    public void testGetOwnNumberIncomplete() {
        testGetOwnNumber_setup();

        sendAndDispatchMessage(
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestOwnNumberIncompleteSearch);

        verify(mMasClient).makeRequest(eq(mRequestOwnNumberIncompleteSearch));
        assertThat(
                        mStateMachine
                                .getHandler()
                                .hasMessages(MceStateMachine.MSG_SEARCH_OWN_NUMBER_TIMEOUT))
                .isTrue();
        testGetOwnNumber_assertNextStageStarted(false);
    }

    /** Test seen status set for new SMS */
    @Test
    public void testReceivedNewSms_messageStoredAsUnseen() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event =
                createNewEventReport(
                        "NewMessage", dateTime, SMS_HANDLE, "telecom/msg/inbox", null, "SMS_GSM");

        sendAndDispatchEvent(event);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessage);

        verify(mDatabase)
                .storeMessage(
                        eq(mTestIncomingSmsBmessage), eq(SMS_HANDLE), any(), eq(MESSAGE_NOT_SEEN));
    }

    /** Test seen status set for new MMS */
    @Test
    public void testReceivedNewMms_messageStoredAsUnseen() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event =
                createNewEventReport(
                        "NewMessage", dateTime, MMS_HANDLE, "telecom/msg/inbox", null, "MMS");

        when(mRequestGetMessage.getMessage()).thenReturn(mTestIncomingMmsBmessage);
        when(mRequestGetMessage.getHandle()).thenReturn(MMS_HANDLE);

        sendAndDispatchEvent(event);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessage);

        verify(mDatabase)
                .storeMessage(
                        eq(mTestIncomingMmsBmessage), eq(MMS_HANDLE), any(), eq(MESSAGE_NOT_SEEN));
    }

    @Test
    public void testReceiveNewMessage_handleNotRecognized_messageDropped() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        // Send new message event with handle A
        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event =
                createNewEventReport(
                        "NewMessage", dateTime, MMS_HANDLE, "telecom/msg/inbox", null, "MMS");

        // Prepare to send back message content, but use handle B
        when(mRequestGetMessage.getHandle()).thenReturn("0003"); // unknown handle
        when(mRequestGetMessage.getMessage()).thenReturn(mTestIncomingMmsBmessage);

        sendAndDispatchEvent(event);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessage);

        // We should drop the message and not store it, as it's not one we requested
        verify(mDatabase, never())
                .storeMessage(any(Bmessage.class), anyString(), anyLong(), anyBoolean());
    }

    /** Test seen status set in database on initial download */
    @Test
    public void testDownloadExistingSms_messageStoredAsSeen() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        com.android.bluetooth.mapclient.Message testMessageListingSms =
                createNewMessage("SMS_GSM", SMS_HANDLE);
        ArrayList<com.android.bluetooth.mapclient.Message> messageListSms = new ArrayList<>();
        messageListSms.add(testMessageListingSms);
        when(mRequestGetMessagesListing.getList()).thenReturn(messageListSms);

        sendAndDispatchMessage(
                MceStateMachine.MSG_GET_MESSAGE_LISTING, MceStateMachine.FOLDER_INBOX);

        verify(mMasClient).makeRequest(any(RequestGetMessagesListing.class));

        sendAndDispatchMessage(
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessagesListing);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessage);

        verify(mDatabase).storeMessage(any(), any(), any(), eq(MESSAGE_SEEN));
    }

    /** Test seen status set in database on initial download */
    @Test
    public void testDownloadExistingMms_messageStoredAsSeen() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        com.android.bluetooth.mapclient.Message testMessageListingMms =
                createNewMessage("MMS", MMS_HANDLE);
        ArrayList<com.android.bluetooth.mapclient.Message> messageListMms = new ArrayList<>();
        messageListMms.add(testMessageListingMms);

        when(mRequestGetMessage.getMessage()).thenReturn(mTestIncomingMmsBmessage);
        when(mRequestGetMessage.getHandle()).thenReturn(MMS_HANDLE);
        when(mRequestGetMessagesListing.getList()).thenReturn(messageListMms);

        sendAndDispatchMessage(
                MceStateMachine.MSG_GET_MESSAGE_LISTING, MceStateMachine.FOLDER_INBOX);

        verify(mMasClient).makeRequest(any(RequestGetMessagesListing.class));

        sendAndDispatchMessage(
                MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessagesListing);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessage);

        verify(mDatabase).storeMessage(any(), any(), any(), eq(MESSAGE_SEEN));
    }

    /** Test receiving a new message notification. */
    @Test
    public void testReceiveNewMessageNotification() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        // Receive a new message notification.
        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event =
                createNewEventReport(
                        "NewMessage", dateTime, SMS_HANDLE, "telecom/msg/inbox", null, "SMS_GSM");

        sendAndDispatchEvent(event);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        MceStateMachine.MessageMetadata messageMetadata = mStateMachine.mMessages.get(SMS_HANDLE);
        assertThat(messageMetadata.getHandle()).isEqualTo(SMS_HANDLE);
        assertThat(new ObexTime(Instant.ofEpochMilli(messageMetadata.getTimestamp())).toString())
                .isEqualTo(dateTime);
    }

    /**
     * Test MSG_GET_MESSAGE_LISTING does not grab unsupported message types of MESSAGE_TYPE_EMAIL
     * and MESSAGE_TYPE_IM
     */
    @Test
    public void testMsgGetMessageListing_unsupportedMessageTypesNotRequested() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        clearInvocations(mMasClient);
        byte expectedFilter = MessagesFilter.MESSAGE_TYPE_EMAIL | MessagesFilter.MESSAGE_TYPE_IM;

        sendAndDispatchMessage(
                MceStateMachine.MSG_GET_MESSAGE_LISTING, MceStateMachine.FOLDER_INBOX);

        // using Request class as captor grabs all Request sub-classes even if
        // RequestGetMessagesListing is specifically requested
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mMasClient, atLeastOnce()).makeRequest(requestCaptor.capture());
        List<Request> requests = requestCaptor.getAllValues();

        // iterating through captured values to grab RequestGetMessagesListing object
        RequestGetMessagesListing messagesListingRequest = null;
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i) instanceof RequestGetMessagesListing) {
                messagesListingRequest = (RequestGetMessagesListing) requests.get(i);
                break;
            }
        }

        ObexAppParameters appParams =
                ObexAppParameters.fromHeaderSet(messagesListingRequest.mHeaderSet);
        byte filter = appParams.getByte(Request.OAP_TAGID_FILTER_MESSAGE_TYPE);
        assertThat(filter).isEqualTo(expectedFilter);
    }

    @Test
    public void testReceivedNewMmsNoSMSDefaultPackage_broadcastToSMSReplyPackage() {
        masConnected_whenConnecting_isConnected(); // transition to the connected state

        String dateTime = new ObexTime(Instant.now()).toString();
        EventReport event =
                createNewEventReport(
                        "NewMessage", dateTime, SMS_HANDLE, "telecom/msg/inbox", null, "SMS_GSM");

        sendAndDispatchEvent(event);

        verify(mMasClient).makeRequest(any(RequestGetMessage.class));

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, mRequestGetMessage);

        verifyIntentSent(
                android.Manifest.permission.RECEIVE_SMS, hasPackage(nullValue(String.class)));
    }

    @Test
    public void testSdpBusyWhileConnecting_sdpRetried() {
        assertCurrentStateAfterScheduledTask(STATE_CONNECTING);

        // Send SDP Failed with status "busy"
        // Note: There's no way to validate the BluetoothDevice#sdpSearch call
        mStateMachine.sendSdpResult(MceStateMachine.SDP_BUSY, null);

        // Send successful SDP record, then send MAS Client connected
        SdpMasRecord record = new SdpMasRecord(1, 1, 1, 1, 1, 1, "MasRecord");
        mStateMachine.sendSdpResult(MceStateMachine.SDP_SUCCESS, record);
        sendAndDispatchMessage(MceStateMachine.MSG_MAS_CONNECTED);
        verifyStateTransitionAndIntent(STATE_CONNECTING, STATE_CONNECTED);
    }

    @Test
    public void testSdpBusyWhileConnectingAndRetryResultsReceivedAfterTimeout_resultsIgnored() {
        assertCurrentStateAfterScheduledTask(STATE_CONNECTING);

        // Send SDP Failed with status "busy"
        // Note: There's no way to validate the BluetoothDevice#sdpSearch call
        mStateMachine.sendSdpResult(MceStateMachine.SDP_BUSY, null);

        // Simulate timeout waiting for record
        mLooper.moveTimeForward(MceStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyStateTransitionAndIntent(STATE_CONNECTING, STATE_DISCONNECTING);

        // Send successful SDP record, then send MAS Client connected
        SdpMasRecord record = new SdpMasRecord(1, 1, 1, 1, 1, 1, "MasRecord");
        mStateMachine.sendSdpResult(MceStateMachine.SDP_SUCCESS, record);

        // Verify nothing happens
        verifyNoMoreInteractions(mService);
    }

    @Test
    public void testSdpFailedWithNoRecordWhileConnecting_deviceDisconnecting() {
        assertCurrentStateAfterScheduledTask(STATE_CONNECTING);

        // Send SDP process success with no record found
        mStateMachine.sendSdpResult(MceStateMachine.SDP_SUCCESS, null);

        // Verify we move into the disconnecting state
        assertCurrentStateAfterScheduledTask(STATE_DISCONNECTING);
    }

    @Test
    public void testSdpOrganicFailure_deviceDisconnecting() {
        assertCurrentStateAfterScheduledTask(STATE_CONNECTING);

        // Send SDP Failed entirely
        mStateMachine.sendSdpResult(MceStateMachine.SDP_FAILED, null);

        assertCurrentStateAfterScheduledTask(STATE_DISCONNECTING);
    }

    /**
     * Preconditions: - In {@code STATE_CONNECTED}.
     *
     * <p>Actions: - {@link MceStateMachine#sendMapMessage} with 'Sent' {@link PendingIntents}. -
     * {@link MceStateMachine#receiveEvent} of type {@link SENDING_SUCCESS}.
     *
     * <p>Outcome: - SENT_STATUS Intent was broadcast with 'Success' result code.
     */
    @Test
    public void testSendMapMessageSentPendingIntent_notifyStatusSuccess() {
        testSendMapMessagePendingIntents_base(
                ACTION_MESSAGE_SENT, EventReport.Type.SENDING_SUCCESS);

        assertThat(mSentDeliveryReceiver.isActionReceived(PENDING_INTENT_TIMEOUT_MS)).isTrue();
        assertThat(mSentDeliveryReceiver.getResultCode()).isEqualTo(Activity.RESULT_OK);
    }

    /**
     * Preconditions: - In {@code STATE_CONNECTED}.
     *
     * <p>Actions: - {@link MceStateMachine#sendMapMessage} with 'Delivery' {@link PendingIntents}.
     * - {@link MceStateMachine#receiveEvent} of type {@link DELIVERY_SUCCESS}.
     *
     * <p>Outcome: - DELIVERY_STATUS Intent was broadcast with 'Success' result code.
     */
    @Test
    public void testSendMapMessageDeliveryPendingIntent_notifyStatusSuccess() {
        testSendMapMessagePendingIntents_base(
                ACTION_MESSAGE_DELIVERED, EventReport.Type.DELIVERY_SUCCESS);

        assertThat(mSentDeliveryReceiver.isActionReceived(PENDING_INTENT_TIMEOUT_MS)).isTrue();
        assertThat(mSentDeliveryReceiver.getResultCode()).isEqualTo(Activity.RESULT_OK);
    }

    /**
     * Preconditions: - In {@code STATE_CONNECTED}.
     *
     * <p>Actions: - {@link MceStateMachine#sendMapMessage} with 'null' {@link PendingIntents}. -
     * {@link MceStateMachine#receiveEvent} of type {@link SENDING_SUCCESS}. - {@link
     * MceStateMachine#receiveEvent} of type {@link DELIVERY_SUCCESS}.
     *
     * <p>Outcome: - No Intent was broadcast.
     */
    @Test
    public void testSendMapMessageNullPendingIntent_noNotifyStatus() {
        testSendMapMessagePendingIntents_base(null, EventReport.Type.SENDING_SUCCESS);

        assertThat(mSentDeliveryReceiver.isActionReceived(PENDING_INTENT_TIMEOUT_MS)).isFalse();
    }

    /**
     * Preconditions: - In {@code STATE_CONNECTED}.
     *
     * <p>Actions: - {@link MceStateMachine#sendMapMessage} with 'Sent' {@link PendingIntents}. -
     * {@link MceStateMachine#receiveEvent} of type {@link SENDING_FAILURE}.
     *
     * <p>Outcome: - SENT_STATUS Intent was broadcast with 'Failure' result code.
     */
    @Test
    @EnableFlags(Flags.FLAG_HANDLE_DELIVERY_SENDING_FAILURE_EVENTS)
    public void testSendMapMessageSentPendingIntent_notifyStatusFailure() {
        testSendMapMessagePendingIntents_base(
                ACTION_MESSAGE_SENT, EventReport.Type.SENDING_FAILURE);

        assertThat(mSentDeliveryReceiver.isActionReceived(PENDING_INTENT_TIMEOUT_MS)).isTrue();
        assertThat(mSentDeliveryReceiver.getResultCode())
                .isEqualTo(SmsManager.RESULT_ERROR_GENERIC_FAILURE);
    }

    /**
     * Preconditions: - In {@code STATE_CONNECTED}.
     *
     * <p>Actions: - {@link MceStateMachine#sendMapMessage} with 'Delivery' {@link PendingIntents}.
     * - {@link MceStateMachine#receiveEvent} of type {@link DELIVERY_FAILURE}.
     *
     * <p>Outcome: - DELIVERY_STATUS Intent was broadcast with 'Failure' result code.
     */
    @Test
    @EnableFlags(Flags.FLAG_HANDLE_DELIVERY_SENDING_FAILURE_EVENTS)
    public void testSendMapMessageDeliveryPendingIntent_notifyStatusFailure() {
        testSendMapMessagePendingIntents_base(
                ACTION_MESSAGE_DELIVERED, EventReport.Type.DELIVERY_FAILURE);

        assertThat(mSentDeliveryReceiver.isActionReceived(PENDING_INTENT_TIMEOUT_MS)).isTrue();
        assertThat(mSentDeliveryReceiver.getResultCode())
                .isEqualTo(SmsManager.RESULT_ERROR_GENERIC_FAILURE);
    }

    /**
     * @param action corresponding to the {@link PendingIntent} you want to create/register for when
     *     pushing a MAP message, e.g., for 'Sent' or 'Delivery' status.
     * @param type the EventReport type of the new notification, e.g., 'Sent'/'Delivery'
     *     'Success'/'Failure'.
     */
    private void testSendMapMessagePendingIntents_base(String action, EventReport.Type type) {
        sendAndDispatchMessage(MceStateMachine.MSG_MAS_CONNECTED);
        verifyStateTransitionAndIntent(STATE_CONNECTING, STATE_CONNECTED);

        PendingIntent pendingIntentSent;
        PendingIntent pendingIntentDelivered;
        if (ACTION_MESSAGE_SENT.equals(action)) {
            pendingIntentSent = createPendingIntent(action);
            pendingIntentDelivered = null;
        } else if (ACTION_MESSAGE_DELIVERED.equals(action)) {
            pendingIntentSent = null;
            pendingIntentDelivered = createPendingIntent(action);
        } else {
            pendingIntentSent = null;
            pendingIntentDelivered = null;
        }
        sendMapMessageWithPendingIntents(
                pendingIntentSent, pendingIntentDelivered, TEST_MESSAGE_HANDLE);

        sendAndDispatchEvent(
                createNewEventReport(
                        type.toString(),
                        TEST_DATETIME,
                        TEST_MESSAGE_HANDLE,
                        SENT_PATH,
                        null,
                        Bmessage.Type.SMS_GSM.toString()));
    }

    private PendingIntent createPendingIntent(String action) {
        return PendingIntent.getBroadcast(
                mTargetContext, 1, new Intent(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private void sendMapMessageWithPendingIntents(
            PendingIntent pendingIntentSent,
            PendingIntent pendingIntentDelivered,
            String messageHandle) {
        mStateMachine.sendMapMessage(
                TEST_CONTACTS_ONE_PHONENUM, TEST_MESSAGE,
                pendingIntentSent, pendingIntentDelivered);
        mLooper.dispatchAll();

        // {@link sendMapMessage} leads to a new {@link RequestPushMessage}, which contains
        // a {@link Bmessage} object that is used as a key to a map to retrieve the corresponding
        // {@link PendingIntent} that was provided.
        // Thus, we need to intercept this Bmessage and inject it back in for
        // MSG_MAS_REQUEST_COMPLETED. We also need to spy/mock it in order to inject our
        // TEST_MESSAGE_HANDLE (message handles are normally provided by the remote device).
        // The message handle injected here needs to match the handle of the SENT/DELIVERY
        // SUCCESS/FAILURE events.

        ArgumentCaptor<RequestPushMessage> requestCaptor =
                ArgumentCaptor.forClass(RequestPushMessage.class);
        verify(mMasClient, atLeastOnce()).makeRequest(requestCaptor.capture());
        RequestPushMessage spyRequestPushMessage = spy(requestCaptor.getValue());
        when(spyRequestPushMessage.getMsgHandle()).thenReturn(messageHandle);

        sendAndDispatchMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, spyRequestPushMessage);
    }

    private void setupSdpRecordReceipt() {
        assertCurrentStateAfterScheduledTask(STATE_CONNECTING);

        // Setup receipt of SDP record
        SdpMasRecord record = new SdpMasRecord(1, 1, 1, 1, 1, 1, "MasRecord");
        mStateMachine.sendSdpResult(MceStateMachine.SDP_SUCCESS, record);
    }

    private void assertCurrentStateAfterScheduledTask(int expectedState) {
        mLooper.dispatchAll();
        assertThat(mStateMachine.getState()).isEqualTo(expectedState);
    }

    private void verifyStateTransitionAndIntent(int oldState, int newState) {
        assertThat(mStateMachine.getState()).isEqualTo(newState);
        verifyIntentSent(
                new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
                hasAction(BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
    }

    private static class MockSmsContentProvider extends MockContentProvider {
        final Map<Uri, ContentValues> mContentValues = new HashMap<>();
        int mInsertOperationCount = 0;

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            mInsertOperationCount++;
            return Uri.withAppendedPath(Sms.CONTENT_URI, String.valueOf(mInsertOperationCount));
        }

        @Override
        public Cursor query(
                Uri uri,
                String[] projection,
                String selection,
                String[] selectionArgs,
                String sortOrder) {
            Cursor cursor = Mockito.mock(Cursor.class);

            when(cursor.moveToFirst()).thenReturn(true);
            when(cursor.moveToNext()).thenReturn(true).thenReturn(false);

            when(cursor.getLong(anyInt())).thenReturn((long) mContentValues.size());
            when(cursor.getString(anyInt())).thenReturn(String.valueOf(mContentValues.size()));
            return cursor;
        }
    }

    private static String getFolderNameFromRequestGetMessagesListing(Request request) {
        Log.d(TAG, "getFolderName, Request type=" + request);
        String folderName = null;
        if (request instanceof RequestGetMessagesListing) {
            try {
                folderName = (String) request.mHeaderSet.getHeader(HeaderSet.NAME);
            } catch (Exception e) {
                Log.e(TAG, "in getFolderNameFromRequestGetMessagesListing", e);
            }
        }
        Log.d(TAG, "getFolderName, name=" + folderName);
        return folderName;
    }

    // create new Messages from given input
    com.android.bluetooth.mapclient.Message createNewMessage(String mType, String mHandle) {
        HashMap<String, String> attrs = new HashMap<String, String>();

        attrs.put("type", mType);
        attrs.put("handle", mHandle);
        attrs.put("datetime", "20230223T160000");

        com.android.bluetooth.mapclient.Message message =
                new com.android.bluetooth.mapclient.Message(attrs);

        return message;
    }

    EventReport createNewEventReport(
            String mType,
            String mDateTime,
            String mHandle,
            String mFolder,
            String mOldFolder,
            String mMsgType) {

        HashMap<String, String> attrs = new HashMap<String, String>();

        attrs.put("type", mType);
        attrs.put("datetime", mDateTime);
        attrs.put("handle", mHandle);
        attrs.put("folder", mFolder);
        attrs.put("old_folder", mOldFolder);
        attrs.put("msg_type", mMsgType);

        EventReport event = new EventReport(attrs);

        return event;
    }

    // create new Bmessages for testing
    void createTestMessages() {
        VCardEntry originator = new VCardEntry();
        VCardProperty property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_TEL);
        property.addValues("555-1212");
        originator.addProperty(property);

        mTestIncomingSmsBmessage = new Bmessage();
        mTestIncomingSmsBmessage.setBodyContent("HelloWorld");
        mTestIncomingSmsBmessage.setType(Bmessage.Type.SMS_GSM);
        mTestIncomingSmsBmessage.setFolder("telecom/msg/inbox");
        mTestIncomingSmsBmessage.addOriginator(originator);
        mTestIncomingSmsBmessage.addRecipient(originator);

        mTestIncomingMmsBmessage = new Bmessage();
        mTestIncomingMmsBmessage.setBodyContent("HelloWorld");
        mTestIncomingMmsBmessage.setType(Bmessage.Type.MMS);
        mTestIncomingMmsBmessage.setFolder("telecom/msg/inbox");
        mTestIncomingMmsBmessage.addOriginator(originator);
        mTestIncomingMmsBmessage.addRecipient(originator);
    }

    private void sendAndDispatchEvent(EventReport ev) {
        sendAndDispatchMessage(MceStateMachine.MSG_NOTIFICATION, ev);
    }

    private void sendAndDispatchMessage(int what) {
        sendAndDispatchMessage(what, null);
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    @SafeVarargs
    private void verifyIntentSent(String permission, Matcher<Intent>... matchers) {
        mInOrder.verify(mService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), eq(permission));
    }

    @SafeVarargs
    private void verifyIntentSent(String[] permissions, Matcher<Intent>... matchers) {
        mInOrder.verify(mService)
                .sendBroadcastMultiplePermissions(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        eq(permissions),
                        any(BroadcastOptions.class));
    }
}
