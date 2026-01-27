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

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.btservice.AdapterService;
import com.android.tests.bluetooth.MockitoRule;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Test cases for {@link MapClientContent}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class MapClientContentTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private MapClientContent.Callbacks mCallbacks;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private SubscriptionInfo mSubscription;
    @Mock private UserManager mUserManager;
    @Mock private Bundle mRestrictions;

    private static final String TAG = MapClientContentTest.class.getSimpleName();

    private static final int READ = 1;
    private static final Long TIMESTAMP = 1234L;
    private static final String HANDLE_1 = "0001";
    private static final String HANDLE_2 = "0002";
    private static final boolean MESSAGE_SEEN = true;
    private static final boolean MESSAGE_NOT_SEEN = false;

    private final BluetoothDevice mDevice = getTestDevice(68);

    private MockContentResolver mMockContentResolver;
    private FakeContentProvider mFakeSmsContentProvider;
    private FakeContentProvider mFakeMmsContentProvider;
    private FakeContentProvider mFakeThreadContentProvider;
    private VCardEntry mOriginator;
    private MapClientContent mMapClientContent;
    private Bmessage mTestMessage1;
    private Bmessage mTestMessage2;

    @Before
    public void setUp() throws Exception {
        mFakeSmsContentProvider = new FakeContentProvider(mAdapterService);
        mFakeMmsContentProvider = new FakeContentProvider(mAdapterService);
        mFakeThreadContentProvider = new FakeContentProvider(mAdapterService);

        mMockContentResolver = Mockito.spy(new MockContentResolver());
        mMockContentResolver.addProvider("sms", mFakeSmsContentProvider);
        mMockContentResolver.addProvider("mms", mFakeMmsContentProvider);
        mMockContentResolver.addProvider("mms-sms", mFakeThreadContentProvider);

        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();
        mockGetSystemService(mAdapterService, SubscriptionManager.class, mSubscriptionManager);
        mockGetSystemService(mAdapterService, UserManager.class, mUserManager);
        doReturn(mRestrictions).when(mUserManager).getUserRestrictions();

        doReturn(Arrays.asList(mSubscription))
                .when(mSubscriptionManager)
                .getActiveSubscriptionInfoList();
        createTestMessages();
    }

    /** Test that everything initializes correctly with an empty content provider */
    @Test
    public void testCreateMapClientContent() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeSmsContentProvider.mContentValues).isEmpty();
    }

    /** Test that a dirty database gets cleaned at startup. */
    @Test
    public void testCleanDirtyDatabase() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        assertThat(mFakeSmsContentProvider.mContentValues).isEmpty();
    }

    /** Test inserting 2 SMS messages and then clearing out the database. */
    @Test
    public void testStoreTwoSMS() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);

        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(2);
        assertThat(mFakeMmsContentProvider.mContentValues).isEmpty();

        mMapClientContent.cleanUp();
        assertThat(mFakeSmsContentProvider.mContentValues).isEmpty();
        assertThat(mFakeThreadContentProvider.mContentValues).isEmpty();
    }

    /** Test inserting 2 MMS messages and then clearing out the database. */
    @Test
    public void testStoreTwoMMS() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);

        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(2);

        mMapClientContent.cleanUp();
        assertThat(mFakeMmsContentProvider.mContentValues).isEmpty();
    }

    /** Test that SMS and MMS messages end up in their respective databases. */
    @Test
    public void testStoreOneSMSOneMMS() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);

        mMapClientContent.storeMessage(mTestMessage2, HANDLE_2, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(2);

        mMapClientContent.cleanUp();
        assertThat(mFakeMmsContentProvider.mContentValues).isEmpty();
    }

    /** Test read status changed */
    @Test
    public void testReadStatusChanged() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);

        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(2);

        mMapClientContent.markRead(HANDLE_1);

        mMapClientContent.cleanUp();
        assertThat(mFakeMmsContentProvider.mContentValues).isEmpty();
    }

    /**
     * Test read status changed in local provider
     *
     * <p>Insert a message, and notify the observer about a change The cursor is configured to
     * return messages marked as read Verify that the local change is observed and propagated to the
     * remote
     */
    @Test
    public void testLocalReadStatusChanged() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);
        mMapClientContent.mContentObserver.onChange(false);
        verify(mCallbacks).onMessageStatusChanged(eq(HANDLE_1), eq(BluetoothMapClient.READ));
    }

    /** Test if seen status is set to true in database for SMS */
    @Test
    public void testStoreSmsMessageWithSeenTrue_smsWrittenWithSeenTrue() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);

        ContentValues storedSMS =
                (ContentValues) mFakeSmsContentProvider.mContentValues.values().toArray()[0];

        assertThat(storedSMS.get(Sms.SEEN)).isEqualTo(MESSAGE_SEEN);
    }

    /** Test if seen status is set to false in database for SMS */
    @Test
    public void testStoreSmsMessageWithSeenFalse_smsWrittenWithSeenFalse() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_NOT_SEEN);
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);

        ContentValues storedSMS =
                (ContentValues) mFakeSmsContentProvider.mContentValues.values().toArray()[0];

        assertThat(storedSMS.get(Sms.SEEN)).isEqualTo(MESSAGE_NOT_SEEN);
    }

    /** Test if seen status is set to true in database for MMS */
    @Test
    public void testStoreMmsMessageWithSeenTrue_mmsWrittenWithSeenTrue() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);

        ContentValues storedMMS =
                (ContentValues) mFakeMmsContentProvider.mContentValues.values().toArray()[0];

        assertThat(storedMMS.get(Mms.SEEN)).isEqualTo(MESSAGE_SEEN);
    }

    /** Test if seen status is set to false in database for MMS */
    @Test
    public void testStoreMmsMessageWithSeenFalse_mmsWrittenWithSeenFalse() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_1, TIMESTAMP, MESSAGE_NOT_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);

        ContentValues storedMMS =
                (ContentValues) mFakeMmsContentProvider.mContentValues.values().toArray()[0];

        assertThat(storedMMS.get(Mms.SEEN)).isEqualTo(MESSAGE_NOT_SEEN);
    }

    /**
     * Test remote message deleted
     *
     * <p>Add a message to the database Simulate the message getting deleted on the phone Verify
     * that the message is deleted locally
     */
    @Test
    public void testMessageDeleted() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);
        // attempt to delete an invalid handle, nothing should be removed.
        mMapClientContent.deleteMessage(HANDLE_2);
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);

        // delete a valid handle
        mMapClientContent.deleteMessage(HANDLE_1);
        assertThat(mFakeSmsContentProvider.mContentValues).isEmpty();
    }

    /**
     * Test read status changed in local provider
     *
     * <p>Insert a message, manually remove it and notify the observer about a change Verify that
     * the local change is observed and propagated to the remote
     */
    @Test
    public void testLocalMessageDeleted() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        verify(mSubscriptionManager)
                .addSubscriptionInfoRecord(
                        any(),
                        any(),
                        anyInt(),
                        eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);
        mFakeSmsContentProvider.mContentValues.clear();
        mMapClientContent.mContentObserver.onChange(false);
        verify(mCallbacks).onMessageStatusChanged(eq(HANDLE_1), eq(BluetoothMapClient.DELETED));
    }

    /**
     * Preconditions: - Create new {@link MapClientContent}, own phone number not initialized yet.
     *
     * <p>Actions: - Invoke {@link MapClientContent#setRemoteDeviceOwnNumber} with a non-null
     * number.
     *
     * <p>Outcome: - {@link MapClientContent#mPhoneNumber} should now store the number.
     */
    @Test
    public void testSetRemoteDeviceOwnNumber() {
        String testNumber = "5551212";

        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        assertThat(mMapClientContent.mPhoneNumber).isNull();

        mMapClientContent.setRemoteDeviceOwnNumber(testNumber);
        assertThat(mMapClientContent.mPhoneNumber).isEqualTo(testNumber);
    }

    /** Test to validate that some poorly formatted messages don't crash. */
    @Test
    public void testStoreBadMessage() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mTestMessage1 = new Bmessage();
        mTestMessage1.setBodyContent("HelloWorld");
        mTestMessage1.setType(Bmessage.Type.SMS_GSM);
        mTestMessage1.setFolder("telecom/msg/sent");
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);

        mTestMessage2 = new Bmessage();
        mTestMessage2.setBodyContent("HelloWorld");
        mTestMessage2.setType(Bmessage.Type.MMS);
        mTestMessage2.setFolder("telecom/msg/inbox");
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_2, TIMESTAMP, MESSAGE_SEEN);
    }

    /**
     * Test to validate that an exception in the Subscription manager won't crash Bluetooth during
     * disconnect.
     */
    @Test
    public void testCleanUpRemoteException() {
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        doThrow(java.lang.NullPointerException.class)
                .when(mSubscriptionManager)
                .removeSubscriptionInfoRecord(any(), anyInt());
        mMapClientContent.cleanUp();
    }

    /** Test to validate old subscriptions are removed at startup. */
    @Test
    public void testCleanUpAtStartup() {
        MapClientContent.clearAllContent(mAdapterService);
        verify(mSubscriptionManager, never()).removeSubscriptionInfoRecord(any(), anyInt());

        doReturn(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM)
                .when(mSubscription)
                .getSubscriptionType();
        MapClientContent.clearAllContent(mAdapterService);
        verify(mSubscriptionManager)
                .removeSubscriptionInfoRecord(
                        any(), eq(SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM));
    }

    /** Test to validate that cleaning content does not crash when no subscription are available. */
    @Test
    public void testCleanUpWithNoSubscriptions() {
        doReturn(null).when(mSubscriptionManager).getActiveSubscriptionInfoList();

        MapClientContent.clearAllContent(mAdapterService);
    }

    /** Test that we gracefully exit when there's a problem with the SMS/MMS DB being available */
    @Test
    public void testInsertSmsFails_messageHandleNotIntractable() {
        // Try to store an MMS, but make the content resolver fail to insert and provide a null URI
        MissingContentProvider missingContentProvider =
                Mockito.spy(new MissingContentProvider(mAdapterService));
        mMockContentResolver.addProvider("sms", missingContentProvider);
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);

        // Because the insert failed, function calls to update or delete this message should not
        // work either
        mMapClientContent.markRead(HANDLE_1);
        verify(missingContentProvider, never())
                .update(any(Uri.class), any(ContentValues.class), any(Bundle.class));

        mMapClientContent.deleteMessage(HANDLE_1);
        verify(missingContentProvider, never())
                .delete(any(Uri.class), anyString(), any(String[].class));
    }

    /** Test that we gracefully exit when there's a problem with the SMS/MMS DB being available */
    @Test
    public void testInsertMmsPartsSkippedWhenMmsInsertFails_messageHandleNotIntractable() {
        // Try to store an MMS, but make the content resolver fail to insert and provide a null URI
        MissingContentProvider missingContentProvider =
                Mockito.spy(new MissingContentProvider(mAdapterService));
        mMockContentResolver.addProvider("mms", missingContentProvider);
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_2, TIMESTAMP, MESSAGE_SEEN);

        // Because the insert failed, function calls to update or delete this message should not
        // work either
        mMapClientContent.markRead(HANDLE_2);
        verify(missingContentProvider, never())
                .update(any(Uri.class), any(ContentValues.class), any(Bundle.class));

        mMapClientContent.deleteMessage(HANDLE_2);
        verify(missingContentProvider, never())
                .delete(any(Uri.class), anyString(), any(String[].class));
    }

    /**
     * Test verifying dumpsys does not cause Bluetooth to crash (esp since we're querying the
     * database to generate dump).
     */
    @Test
    public void testDumpsysDoesNotCauseCrash() {
        testStoreOneSMSOneMMS();
        // mMapClientContent is set in testStoreOneSMSOneMMS
        StringBuilder sb = new StringBuilder("Hello world!\n");
        mMapClientContent.dump(sb);

        assertThat(sb.toString()).isNotNull();
    }

    /** Test that messages are not stored when SMS is disallowed for the user. */
    @Test
    public void testStoreMessage_whenSmsDisallowed_doesNotStoreMessage() {
        doReturn(true).when(mRestrictions).getBoolean(UserManager.DISALLOW_SMS);
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);

        // Attempt to store an SMS message
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeSmsContentProvider.mContentValues).isEmpty();

        // Attempt to store an MMS message
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_2, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).isEmpty();
    }

    /** Test that messages are stored when SMS is allowed for the user. */
    @Test
    public void testStoreMessage_whenSmsAllowed_storesMessage() {
        doReturn(false).when(mRestrictions).getBoolean(UserManager.DISALLOW_SMS);
        mMapClientContent = new MapClientContent(mAdapterService, mCallbacks, mDevice);

        // Attempt to store an SMS message
        mMapClientContent.storeMessage(mTestMessage1, HANDLE_1, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeSmsContentProvider.mContentValues).hasSize(1);

        // Attempt to store an MMS message
        mMapClientContent.storeMessage(mTestMessage2, HANDLE_2, TIMESTAMP, MESSAGE_SEEN);
        assertThat(mFakeMmsContentProvider.mContentValues).hasSize(1);
    }

    void createTestMessages() {
        mOriginator = new VCardEntry();
        VCardProperty property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_TEL);
        property.addValues("555-1212");
        mOriginator.addProperty(property);
        mTestMessage1 = new Bmessage();
        mTestMessage1.setBodyContent("HelloWorld");
        mTestMessage1.setType(Bmessage.Type.SMS_GSM);
        mTestMessage1.setFolder("telecom/msg/inbox");
        mTestMessage1.addOriginator(mOriginator);

        mTestMessage2 = new Bmessage();
        mTestMessage2.setBodyContent("HelloWorld");
        mTestMessage2.setType(Bmessage.Type.MMS);
        mTestMessage2.setFolder("telecom/msg/inbox");
        mTestMessage2.addOriginator(mOriginator);
        mTestMessage2.addRecipient(mOriginator);
    }

    static class FakeContentProvider extends MockContentProvider {

        Map<Uri, ContentValues> mContentValues = new HashMap<>();

        FakeContentProvider(Context context) {
            super(context);
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            Log.i(TAG, "Delete " + uri);
            Log.i(TAG, "Contents" + mContentValues.toString());
            mContentValues.remove(uri);
            if (uri.equals(Sms.CONTENT_URI) || uri.equals(Mms.CONTENT_URI)) {
                mContentValues.clear();
            }
            return 1;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            Log.i(TAG, "URI = " + uri);
            if (uri.equals(Mms.Inbox.CONTENT_URI)) uri = Mms.CONTENT_URI;
            Uri returnUri = Uri.withAppendedPath(uri, String.valueOf(mContentValues.size() + 1));
            // only store top level message parts
            if (uri.equals(Sms.Inbox.CONTENT_URI) || uri.equals(Mms.CONTENT_URI)) {
                Log.i(TAG, "adding content" + values);
                mContentValues.put(returnUri, values);
                Log.i(TAG, "ContentSize = " + mContentValues.size());
            }
            return returnUri;
        }

        @Override
        public Cursor query(
                Uri uri,
                String[] projection,
                String selection,
                String[] selectionArgs,
                String sortOrder) {
            Cursor cursor = Mockito.mock(Cursor.class);

            doReturn(true).when(cursor).moveToFirst();
            doReturn(true).doReturn(false).when(cursor).moveToNext();

            doReturn((long) mContentValues.size()).when(cursor).getLong(anyInt());
            doReturn(String.valueOf(mContentValues.size())).when(cursor).getString(anyInt());
            doReturn(READ).when(cursor).getInt(anyInt());
            return cursor;
        }

        @Override
        public int update(Uri uri, ContentValues values, Bundle extras) {
            return 0;
        }
    }

    public static class MissingContentProvider extends FakeContentProvider {
        MissingContentProvider(Context context) {
            super(context);
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            // nothing deleted
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            // Insert fails, so there's no URI that points to the inserted values
            return null;
        }

        @Override
        public Cursor query(
                Uri uri,
                String[] projection,
                String selection,
                String[] selectionArgs,
                String sortOrder) {
            // Return empty cursor
            Cursor cursor = Mockito.mock(Cursor.class);
            doReturn(false).when(cursor).moveToFirst();
            doReturn(false).when(cursor).moveToNext();
            return cursor;
        }

        @Override
        public int update(Uri uri, ContentValues values, Bundle extras) {
            // zero rows updated
            return 0;
        }
    }
}
