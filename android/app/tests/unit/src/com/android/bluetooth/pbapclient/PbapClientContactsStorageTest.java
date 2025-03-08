/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.accounts.Account;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.test.mock.MockContentResolver;
import android.util.SparseArray;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.vcard.VCardConfig;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PbapClientContactsStorageTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private static final String ACCOUNT_TYPE = "com.android.bluetooth.pbapclient.account";

    private static final int TEST_CONTACTS_SIZE = 200;
    private static final int DATA_PER_CONTACT = 1;

    private BluetoothAdapter mAdapter = null;

    @Mock private Context mMockContext;
    private MockContentResolver mMockContentResolver;
    private FakeContactsProvider mFakeContactsProvider;
    ArgumentCaptor<ArrayList<ContentProviderOperation>> mBatchesCaptor =
            ArgumentCaptor.forClass(ArrayList.class);
    @Mock private File mMockDirectory;
    @Mock private PbapClientAccountManager mMockAccountManager;
    private final List<Account> mMockedAccounts = new ArrayList<>();
    @Mock private PbapClientContactsStorage.Callback mMockStorageCallback;
    private PbapClientContactsStorage.PbapClientAccountManagerCallback mAccountManagerCallback;

    private PbapClientContactsStorage mStorage;

    @Before
    public void setUp() throws Exception {
        mAdapter =
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getSystemService(BluetoothManager.class)
                        .getAdapter();
        assertThat(mAdapter).isNotNull();

        // Mock PbapClientAccountManager to add/remove from a locally managed list
        doAnswer(
                        invocation -> {
                            BluetoothDevice device = (BluetoothDevice) invocation.getArgument(0);
                            return getAccountForDevice(device);
                        })
                .when(mMockAccountManager)
                .getAccountForDevice(any(BluetoothDevice.class));

        doAnswer(
                        invocation -> {
                            Account account = (Account) invocation.getArgument(0);
                            mMockedAccounts.add(account);
                            return true;
                        })
                .when(mMockAccountManager)
                .addAccount(any(Account.class));

        doAnswer(
                        invocation -> {
                            Account account = (Account) invocation.getArgument(0);
                            mMockedAccounts.remove(account);
                            return true;
                        })
                .when(mMockAccountManager)
                .removeAccount(any(Account.class));

        doAnswer(
                        invocation -> {
                            return mMockedAccounts;
                        })
                .when(mMockAccountManager)
                .getAccounts();

        doReturn(mMockDirectory).when(mMockContext).getFilesDir();
        doReturn(new File[] {}).when(mMockDirectory).listFiles();

        mMockContentResolver = new MockContentResolver();
        mFakeContactsProvider = new FakeContactsProvider();
        mMockContentResolver.addProvider(ContactsContract.AUTHORITY, mFakeContactsProvider);
        mMockContentResolver.addProvider(CallLog.AUTHORITY, mFakeContactsProvider);
        doReturn(mMockContentResolver).when(mMockContext).getContentResolver();

        mStorage = new PbapClientContactsStorage(mMockContext, mMockAccountManager);
        mAccountManagerCallback = mStorage.new PbapClientAccountManagerCallback();
        mStorage.registerCallback(mMockStorageCallback);
    }

    @After
    public void tearDown() throws Exception {
        if (mStorage != null) {
            mStorage.unregisterCallback(mMockStorageCallback);
            mStorage.stop();
            mStorage = null;
        }
    }

    // *********************************************************************************************
    // * Incoming Events
    // *********************************************************************************************

    // Start/stop/init

    @Test
    public void testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts() {
        startStorage(new ArrayList<Account>());

        verify(mMockStorageCallback, times(1)).onStorageReady();
        verify(mMockStorageCallback, times(1))
                .onStorageAccountsChanged(new ArrayList<Account>(), new ArrayList<Account>());
        assertThat(mStorage.isStorageReady()).isTrue();
        assertThat(mStorage.getStorageAccounts()).isEmpty();
    }

    @Test
    public void testStartStorage_withExistingAccounts_accountsCleanedUp() {
        BluetoothDevice device1 = getTestDevice(1);
        Account account1 = getAccountForDevice(device1);
        BluetoothDevice device2 = getTestDevice(2);
        Account account2 = getAccountForDevice(device2);
        List<Account> existingAccounts = Arrays.asList(new Account[] {account1, account2});

        startStorage(existingAccounts);

        verify(mMockAccountManager, times(1)).removeAccount(eq(account1));
        verify(mMockAccountManager, times(1)).removeAccount(eq(account2));

        verify(mMockStorageCallback, times(1)).onStorageReady();
        verify(mMockStorageCallback, times(1))
                .onStorageAccountsChanged(new ArrayList<Account>(), new ArrayList<Account>());
        assertThat(mStorage.isStorageReady()).isTrue();
        assertThat(mStorage.getStorageAccounts()).isEmpty();
    }

    // Storage Account management (create, get, add, remove)

    @Test
    public void testGetStorageAccountForDevice() {
        BluetoothDevice device = getTestDevice(1);
        Account expected = getAccountForDevice(device);

        assertThat(mStorage.getStorageAccountForDevice(device)).isEqualTo(expected);
    }

    @Test
    public void testGetStorageAccounts_accountsExist_accountsReturned() {
        mMockedAccounts.add(getAccountForDevice(getTestDevice(1)));
        mMockedAccounts.add(getAccountForDevice(getTestDevice(2)));

        assertThat(mStorage.getStorageAccounts()).isEqualTo(mMockedAccounts);
    }

    @Test
    public void testGetStorageAccounts_noAccountsExist_emptyListReturned() {
        assertThat(mStorage.getStorageAccounts()).isEmpty();
    }

    @Test
    public void testAddAccount_accountAddedAndInAccountsList() {
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);
        assertThat(mStorage.getStorageAccounts()).contains(account);
    }

    @Test
    public void testRemoveAccount_accountRemovedAndNotInAccountsList() {
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);

        mMockedAccounts.add(account);
        assertThat(mStorage.getStorageAccounts()).contains(account);

        mStorage.removeAccount(account);
        assertThat(mStorage.getStorageAccounts()).doesNotContain(account);
    }

    @Test
    public void testRemoveAccount_accountDoesNotExist_accountsUnchanged() {
        BluetoothDevice device1 = getTestDevice(1);
        Account account1 = mStorage.getStorageAccountForDevice(device1);

        BluetoothDevice device2 = getTestDevice(2);
        Account account2 = mStorage.getStorageAccountForDevice(device2);

        mMockedAccounts.add(account1);
        assertThat(mStorage.getStorageAccounts()).contains(account1);

        mStorage.removeAccount(account2);
        assertThat(mStorage.getStorageAccounts()).hasSize(mMockedAccounts.size());
        assertThat(mStorage.getStorageAccounts()).contains(account1);
        assertThat(mStorage.getStorageAccounts()).doesNotContain(account2);
    }

    // *********************************************************************************************
    // * Contacts DB interfaces
    // *********************************************************************************************

    // Insert contacts

    @Test
    public void testInsertFavorites_validFavoritesList_contactsInserted()
            throws RemoteException, OperationApplicationException, NumberFormatException {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertFavorites(account, getMockContacts(TEST_CONTACTS_SIZE))).isTrue();
        verifyDbAccounts(1);
        verifyDbFavorites(TEST_CONTACTS_SIZE);
        verifyDbRawContacts(TEST_CONTACTS_SIZE);
        verifyDbData(TEST_CONTACTS_SIZE * DATA_PER_CONTACT);
        verifyDbCallHistory(0);
    }

    @Test
    public void testInsertLocalContacts()
            throws RemoteException, OperationApplicationException, NumberFormatException {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertLocalContacts(account, getMockContacts(TEST_CONTACTS_SIZE)))
                .isTrue();
        verifyDbAccounts(1);
        verifyDbFavorites(0);
        verifyDbRawContacts(TEST_CONTACTS_SIZE);
        verifyDbData(TEST_CONTACTS_SIZE * DATA_PER_CONTACT);
        verifyDbCallHistory(0);
    }

    @Test
    public void testInsertSimContacts()
            throws RemoteException, OperationApplicationException, NumberFormatException {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertSimContacts(account, getMockContacts(TEST_CONTACTS_SIZE)))
                .isTrue();
        verifyDbAccounts(1);
        verifyDbFavorites(0);
        verifyDbRawContacts(TEST_CONTACTS_SIZE);
        verifyDbData(TEST_CONTACTS_SIZE * DATA_PER_CONTACT);
        verifyDbCallHistory(0);
    }

    // Insert call history

    @Test
    public void testInsertIncomingCallHistory_validHistory_historyInserted()
            throws RemoteException, OperationApplicationException {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(
                        mStorage.insertIncomingCallHistory(
                                account,
                                getMockCallHistory(
                                        CallLog.Calls.INCOMING_TYPE, TEST_CONTACTS_SIZE)))
                .isTrue();
        verifyDbAccounts(0);
        verifyDbFavorites(0);
        verifyDbRawContacts(0);
        verifyDbData(0);
        verifyDbCallHistory(TEST_CONTACTS_SIZE);
    }

    @Test
    public void testInsertOutgoingCallHistory_validHistory_historyInserted()
            throws RemoteException, OperationApplicationException {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(
                        mStorage.insertOutgoingCallHistory(
                                account,
                                getMockCallHistory(
                                        CallLog.Calls.OUTGOING_TYPE, TEST_CONTACTS_SIZE)))
                .isTrue();
        verifyDbAccounts(0);
        verifyDbFavorites(0);
        verifyDbRawContacts(0);
        verifyDbData(0);
        verifyDbCallHistory(TEST_CONTACTS_SIZE);
    }

    @Test
    public void testInsertMissedCallHistory_validHistory_historyInserted()
            throws RemoteException, OperationApplicationException {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(
                        mStorage.insertMissedCallHistory(
                                account,
                                getMockCallHistory(CallLog.Calls.MISSED_TYPE, TEST_CONTACTS_SIZE)))
                .isTrue();
        verifyDbAccounts(0);
        verifyDbFavorites(0);
        verifyDbRawContacts(0);
        verifyDbData(0);
        verifyDbCallHistory(TEST_CONTACTS_SIZE);
    }

    // Remove Contacts

    @Test
    public void testRemoveAllContacts_allContactsRemovedForAccount() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        addFakeAccount(device.getAddress());
        addFakeContacts(device.getAddress(), PbapPhonebook.FAVORITES_PATH, 5);
        addFakeContacts(
                device.getAddress(), PbapPhonebook.LOCAL_PHONEBOOK_PATH, TEST_CONTACTS_SIZE);
        addFakeContacts(device.getAddress(), PbapPhonebook.SIM_PHONEBOOK_PATH, TEST_CONTACTS_SIZE);

        assertThat(mStorage.removeAllContacts(account)).isTrue();
        verifyDbAccounts(1);
        verifyDbFavorites(0);
        verifyDbRawContacts(0);
        verifyDbData(0);
        verifyDbCallHistory(0);
    }

    // Remove Call History

    @Test
    public void testRemoveAllCallHistory_callHistoryRemoved() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        addFakeCallLogs(device.getAddress(), CallLog.Calls.INCOMING_TYPE, TEST_CONTACTS_SIZE);
        addFakeCallLogs(device.getAddress(), CallLog.Calls.OUTGOING_TYPE, TEST_CONTACTS_SIZE);
        addFakeCallLogs(device.getAddress(), CallLog.Calls.MISSED_TYPE, TEST_CONTACTS_SIZE);

        assertThat(mStorage.removeCallHistory(account)).isTrue();
        verifyDbCallHistory(0);
    }

    // contacts error cases

    @Test
    public void testInsertContacts_storageNotReady_insertFails() {
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);

        assertThat(mStorage.insertFavorites(account, getMockContacts(TEST_CONTACTS_SIZE)))
                .isFalse();
    }

    @Test
    public void testInsertContacts_accountNull_insertFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();

        assertThat(mStorage.insertFavorites(null, getMockContacts(TEST_CONTACTS_SIZE))).isFalse();
    }

    @Test
    public void testInsertContacts_contactsNull_insertFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertFavorites(account, null)).isFalse();
    }

    @Test
    public void testInsertContacts_contactsEmpty_insertFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertFavorites(account, new ArrayList<VCardEntry>())).isFalse();
    }

    @Test
    public void testRemoveAllContacts_accountNull_removeFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.removeAllContacts(null)).isFalse();
    }

    // call history error cases

    @Test
    public void testInsertCallHistory_storageNotReady_insertFails() {
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);

        assertThat(
                        mStorage.insertIncomingCallHistory(
                                account,
                                getMockCallHistory(
                                        CallLog.Calls.INCOMING_TYPE, TEST_CONTACTS_SIZE)))
                .isFalse();
    }

    @Test
    public void testInsertCallHistory_accountNull_insertFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();

        assertThat(
                        mStorage.insertIncomingCallHistory(
                                null,
                                getMockCallHistory(
                                        CallLog.Calls.INCOMING_TYPE, TEST_CONTACTS_SIZE)))
                .isFalse();
    }

    @Test
    public void testInsertCallHistory_historyNull_insertFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertIncomingCallHistory(account, null)).isFalse();
    }

    @Test
    public void testInsertCallHistory_historyEmpty_insertFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.insertIncomingCallHistory(account, new ArrayList<VCardEntry>()))
                .isFalse();
    }

    @Test
    public void testRemoveCallHistory_accountNull_removeFails() {
        testStartStorage_withoutExistingAccounts_storageReadyWithNoAccounts();
        BluetoothDevice device = getTestDevice(1);
        Account account = mStorage.getStorageAccountForDevice(device);
        mStorage.addAccount(account);

        assertThat(mStorage.removeCallHistory(null)).isFalse();
    }

    // *********************************************************************************************
    // * Debug/Dump/toString()
    // *********************************************************************************************

    @Test
    public void testToString() {
        String str = mStorage.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testDump() {
        String dumpContents = mStorage.dump();
        assertThat(dumpContents).isNotNull();
        assertThat(dumpContents.length()).isNotEqualTo(0);
    }

    // *********************************************************************************************
    // * Testing Utilities
    // *********************************************************************************************

    private void startStorage(List<Account> existingAccounts) {
        mMockedAccounts.addAll(existingAccounts);
        mStorage.start();
        verify(mMockAccountManager).start();
        mAccountManagerCallback.onAccountsChanged(null, existingAccounts);
        verify(mMockStorageCallback, times(1)).onStorageReady();
        assertThat(mStorage.isStorageReady()).isTrue();
    }

    private static Account getAccountForDevice(BluetoothDevice device) {
        return new Account(device.getAddress(), ACCOUNT_TYPE);
    }

    private static List<VCardEntry> getMockContacts(int numContacts) {
        List<VCardEntry> contacts = new ArrayList<VCardEntry>();
        for (int i = 0; i < numContacts; i++) {
            VCardEntry card = new VCardEntry(VCardConfig.VCARD_TYPE_V21_GENERIC);
            VCardProperty property = new VCardProperty();
            property.setName(VCardConstants.PROPERTY_TEL);
            property.addValues(String.valueOf(i));
            card.addProperty(property);
            contacts.add(card);
        }

        return contacts;
    }

    private static List<VCardEntry> getMockCallHistory(int type, int numEntries) {
        String typeIndicator = "";
        if (type == CallLog.Calls.INCOMING_TYPE) {
            typeIndicator = "RECEIVED";
        } else if (type == CallLog.Calls.OUTGOING_TYPE) {
            typeIndicator = "DIALED";
        } else /* CallLog.Calls.MISSED_TYPE */ {
            typeIndicator = "MISSED";
        }

        List<VCardEntry> callHistory = new ArrayList<VCardEntry>();
        for (int i = 0; i < numEntries; i++) {
            VCardEntry card = new VCardEntry(VCardConfig.VCARD_TYPE_V21_GENERIC);

            ZonedDateTime zonedDateTime =
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(i), ZoneId.of("UTC"));
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
            String callTimestamp = zonedDateTime.format(formatter);

            VCardProperty callTime = new VCardProperty();
            callTime.setName("X-IRMC-CALL-DATETIME");
            callTime.addParameter("TYPE", typeIndicator);
            callTime.addValues(callTimestamp);
            card.addProperty(callTime);

            VCardProperty fromOrWith = new VCardProperty();
            fromOrWith.setName(VCardConstants.PROPERTY_TEL);
            fromOrWith.addValues(String.valueOf(i));
            card.addProperty(fromOrWith);

            callHistory.add(card);
        }

        return callHistory;
    }

    private void addFakeAccount(String accountName) {
        mFakeContactsProvider.addAccount(ACCOUNT_TYPE, accountName);
    }

    private void addFakeContacts(String accountName, String phonebook, int numContacts) {
        boolean favorite = PbapPhonebook.FAVORITES_PATH.equals(phonebook);
        for (int i = 0; i < numContacts; i++) {
            String phone = String.valueOf(i);
            mFakeContactsProvider.addContact(ACCOUNT_TYPE, accountName, favorite, phonebook, phone);
        }
    }

    private void addFakeCallLogs(String phoneAccount, int type, int numCalls) {
        for (int i = 0; i < numCalls; i++) {
            ZonedDateTime zonedDateTime =
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(i), ZoneId.of("UTC"));
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
            String timestamp = zonedDateTime.format(formatter);
            String who = String.valueOf(i);
            mFakeContactsProvider.addCallLog(phoneAccount, type, timestamp, who);
        }
    }

    private void verifyDbRawContacts(int numRawContacts) {
        SparseArray<FakeContactsProvider.FakeRawContact> rawContacts =
                mFakeContactsProvider.getRawContacts();
        assertThat(rawContacts.size()).isEqualTo(numRawContacts);
    }

    private void verifyDbFavorites(int numFavorites) {
        SparseArray<FakeContactsProvider.FakeRawContact> favorites =
                mFakeContactsProvider.getFavorites();
        assertThat(favorites.size()).isEqualTo(numFavorites);
    }

    private void verifyDbData(int numData) {
        SparseArray<FakeContactsProvider.FakeData> data = mFakeContactsProvider.getData();
        assertThat(data.size()).isEqualTo(numData);
    }

    private void verifyDbAccounts(int numAccounts) {
        SparseArray<FakeContactsProvider.FakeAccount> accounts =
                mFakeContactsProvider.getAccounts();
        assertThat(accounts.size()).isEqualTo(numAccounts);
    }

    private void verifyDbCallHistory(int numCallHistory) {
        SparseArray<FakeContactsProvider.FakeCallLog> calls =
                mFakeContactsProvider.getCallHistory();
        assertThat(calls.size()).isEqualTo(numCallHistory);
    }
}
