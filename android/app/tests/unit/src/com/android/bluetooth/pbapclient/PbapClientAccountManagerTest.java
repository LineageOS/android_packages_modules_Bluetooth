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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.UserManager;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PbapClientAccountManagerTest {
    private static final String ACCOUNT_TYPE = "com.android.bluetooth.pbapclient.account";

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private BluetoothAdapter mAdapter;

    @Mock private Context mMockContext;
    @Mock private HandlerThread mMockHandlerThread;
    private TestLooper mTestLooper;
    @Mock private Resources mMockResources;
    @Mock private AccountManager mMockAccountManager;
    @Mock private UserManager mMockUserManager;
    ArgumentCaptor<BroadcastReceiver> mReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);
    private BroadcastReceiver mBroadcastReceiver;
    @Mock private PbapClientAccountManager.Callback mMockCallback;
    ArgumentCaptor<List<Account>> mFromAccountsCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<Account>> mToAccountsCaptor = ArgumentCaptor.forClass(List.class);

    private PbapClientAccountManager mAccountManager;

    @Before
    public void setUp() throws Exception {
        mAdapter =
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getSystemService(BluetoothManager.class)
                        .getAdapter();
        assertThat(mAdapter).isNotNull();

        TestUtils.mockGetSystemService(
                mMockContext, Context.ACCOUNT_SERVICE, AccountManager.class, mMockAccountManager);
        setAvailableAccounts(new Account[] {});
        setAccountVisibility(AccountManager.VISIBILITY_NOT_VISIBLE);
        doReturn(true)
                .when(mMockAccountManager)
                .addAccountExplicitly(
                        any(Account.class), nullable(String.class), nullable(Bundle.class));
        doReturn(true).when(mMockAccountManager).removeAccountExplicitly(any(Account.class));

        TestUtils.mockGetSystemService(
                mMockContext, Context.USER_SERVICE, UserManager.class, mMockUserManager);
        doReturn("").when(mMockContext).getPackageName();
        doReturn(false).when(mMockUserManager).isUserUnlocked();

        doReturn(mMockResources).when(mMockContext).getResources();
        doReturn(ACCOUNT_TYPE).when(mMockResources).getString(anyInt());

        mTestLooper = new TestLooper();
        doReturn(mTestLooper.getLooper()).when(mMockHandlerThread).getLooper();

        mAccountManager =
                new PbapClientAccountManager(mMockContext, mMockHandlerThread, mMockCallback);
    }

    @After
    public void tearDown() throws Exception {
        if (mAccountManager != null) {
            mAccountManager.stop();
            mAccountManager = null;
        }
    }

    // *********************************************************************************************
    // * Public API Methods
    // *********************************************************************************************

    // getAccountForDevice

    @Test
    public void testGetAccountForDevice_deviceAccountNameAndTypeAreValid() {
        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(account.name).isEqualTo(device.getAddress());
        assertThat(account.type).isEqualTo(ACCOUNT_TYPE);
    }

    @Test
    public void testGetAccountForDevice_withNullDevice_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class, () -> mAccountManager.getAccountForDevice(null));
    }

    // Start/Initialization Proceedures

    @Test
    public void testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized() {
        doReturn(true).when(mMockUserManager).isUserUnlocked();
        setAccountVisibility(AccountManager.VISIBILITY_VISIBLE);
        startAccountManager();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onAccountsChanged(mFromAccountsCaptor.capture(), mToAccountsCaptor.capture());
        assertThat(mFromAccountsCaptor.getValue()).isNull();
        assertThat(mToAccountsCaptor.getValue()).isNotNull();
        assertThat(mToAccountsCaptor.getValue()).isEmpty();
    }

    @Test
    public void testStartAccountManager_userUnlockedAccountVisibleHasAccount_accountsInitialized() {
        BluetoothDevice device1 = getTestDevice(/* id= */ 1);
        BluetoothDevice device2 = getTestDevice(/* id= */ 2);
        Account[] accounts =
                new Account[] {getAccountForDevice(device1), getAccountForDevice(device2)};

        doReturn(true).when(mMockUserManager).isUserUnlocked();
        setAccountVisibility(AccountManager.VISIBILITY_VISIBLE);
        setAvailableAccounts(accounts);
        startAccountManager();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onAccountsChanged(mFromAccountsCaptor.capture(), mToAccountsCaptor.capture());
        List<Account> fromAccounts = mFromAccountsCaptor.getValue();
        List<Account> toAccounts = mToAccountsCaptor.getValue();

        assertThat(fromAccounts).isNull();
        assertThat(toAccounts).isNotNull();
        assertThat(toAccounts).hasSize(2);
        assertThat(toAccounts).contains(accounts[0]);
        assertThat(toAccounts).contains(accounts[1]);
    }

    @Test
    public void testStartAccountManager_userUnlockedAndAccountNotVisible_accountChecksBegin() {
        doReturn(true).when(mMockUserManager).isUserUnlocked();
        startAccountManager();
        mTestLooper.dispatchAll();

        verify(mMockAccountManager, times(1)).getAccountVisibility(any(Account.class), anyString());
    }

    @Test
    public void testStartAccountManager_userNotUnlocked_noAccountsOrChecks() {
        doReturn(false).when(mMockUserManager).isUserUnlocked();
        startAccountManager();
        mTestLooper.dispatchAll();

        verify(mMockAccountManager, never()).getAccountVisibility(any(Account.class), anyString());
        verify(mMockCallback, never()).onAccountsChanged(any(List.class), any(List.class));
    }

    @Test
    public void testReceiveUserLocked_accountNotVisible_accountChecksBegin() {
        testStartAccountManager_userNotUnlocked_noAccountsOrChecks();
        sendUserUnlocked();
        mTestLooper.dispatchAll();

        verify(mMockAccountManager, times(1)).getAccountVisibility(any(Account.class), anyString());
    }

    @Test
    public void testReceiveUserLocked_accountVisible_accountsInitialized() {
        testStartAccountManager_userNotUnlocked_noAccountsOrChecks();
        setAccountVisibility(AccountManager.VISIBILITY_VISIBLE);
        sendUserUnlocked();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onAccountsChanged(mFromAccountsCaptor.capture(), mToAccountsCaptor.capture());
        List<Account> fromAccounts = mFromAccountsCaptor.getValue();
        List<Account> toAccounts = mToAccountsCaptor.getValue();

        assertThat(fromAccounts).isNull();
        assertThat(toAccounts).isNotNull();
        assertThat(toAccounts).isEmpty();
    }

    @Test
    public void testAccountVisibilityCheck_notReady_retryQueued() {
        testStartAccountManager_userUnlockedAndAccountNotVisible_accountChecksBegin();

        mTestLooper.moveTimeForward(2000);
        mTestLooper.dispatchAll();
        verify(mMockAccountManager, times(2)).getAccountVisibility(any(Account.class), anyString());
    }

    // getAccounts

    @Test
    public void testGetAccounts_storageInitializedWithAccounts_returnsAccountList() {
        BluetoothDevice device1 = getTestDevice(/* id= */ 1);
        BluetoothDevice device2 = getTestDevice(/* id= */ 2);
        Account[] accounts =
                new Account[] {getAccountForDevice(device1), getAccountForDevice(device2)};

        doReturn(true).when(mMockUserManager).isUserUnlocked();
        setAccountVisibility(AccountManager.VISIBILITY_VISIBLE);
        setAvailableAccounts(accounts);
        startAccountManager();
        mTestLooper.dispatchAll();

        assertThat(mAccountManager.getAccounts()).isNotNull();
        assertThat(mAccountManager.getAccounts()).hasSize(2);
        assertThat(mAccountManager.getAccounts()).contains(accounts[0]);
        assertThat(mAccountManager.getAccounts()).contains(accounts[1]);
    }

    @Test
    public void testGetAccounts_storageNotInitialized_returnsEmptyList() {
        assertThat(mAccountManager.getAccounts()).isEmpty();
    }

    // addAccount/addAccountInternal

    @Test
    public void testAddAccount_accountDoesNotExist_accountInAccountsListAndReturnsTrue() {
        testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized();

        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isTrue();

        verify(mMockCallback, times(2))
                .onAccountsChanged(mFromAccountsCaptor.capture(), mToAccountsCaptor.capture());
        List<Account> toAccounts = mToAccountsCaptor.getValue();
        assertThat(toAccounts).contains(account);

        assertThat(mAccountManager.getAccounts()).contains(account);
    }

    @Test
    public void testAddAccount_accountAlreadyExists_accountInAccountsListAndReturnsTrue() {
        testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized();

        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isTrue();
        assertThat(mAccountManager.getAccounts()).contains(account);

        // Add again once its already in there
        assertThat(mAccountManager.addAccount(account)).isTrue();
        assertThat(mAccountManager.getAccounts()).hasSize(1);
        assertThat(mAccountManager.getAccounts()).contains(account);
    }

    @Test
    public void testAddAccounts_accountManagerOperationFails_returnsFalse() {
        doReturn(false)
                .when(mMockAccountManager)
                .addAccountExplicitly(
                        any(Account.class), nullable(String.class), nullable(Bundle.class));

        testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized();

        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isFalse();
        assertThat(mAccountManager.getAccounts()).isEmpty();
    }

    @Test
    public void testAddAccounts_storageNotInitialized_returnsFalse() {
        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isFalse();
    }

    // removeAccount/removeAccountInternal

    @Test
    public void testRemoveAccount_accountExists_accountNotInAccountsListAndReturnsTrue() {
        testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized();

        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isTrue();
        assertThat(mAccountManager.getAccounts()).contains(account);

        // Remove Account
        assertThat(mAccountManager.removeAccount(account)).isTrue();
        assertThat(mAccountManager.getAccounts()).isEmpty();
    }

    @Test
    public void testRemoveAccount_accountDoesNotExist_accountNotInAccountsListAndReturnsTrue() {
        testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized();

        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isTrue();
        assertThat(mAccountManager.getAccounts()).contains(account);

        BluetoothDevice device2 = getTestDevice(/* id= */ 2);
        Account account2 = mAccountManager.getAccountForDevice(device2);
        assertThat(mAccountManager.getAccounts()).doesNotContain(account2);
        assertThat(mAccountManager.removeAccount(account2)).isTrue();
        assertThat(mAccountManager.getAccounts()).contains(account);
    }

    @Test
    public void testRemoveAccounts_accountManagerOperationFails_returnsFalse() {
        testStartAccountManager_userUnlockedAccountVisibleNoAccounts_accountsInitialized();

        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.addAccount(account)).isTrue();
        assertThat(mAccountManager.getAccounts()).contains(account);

        doReturn(false).when(mMockAccountManager).removeAccountExplicitly(any(Account.class));
        assertThat(mAccountManager.removeAccount(account)).isFalse();
        assertThat(mAccountManager.getAccounts()).contains(account);
    }

    @Test
    public void testRemoveAccounts_storageNotInitialized_returnsFalse() {
        BluetoothDevice device = getTestDevice(/* id= */ 1);
        Account account = mAccountManager.getAccountForDevice(device);
        assertThat(mAccountManager.removeAccount(account)).isFalse();
    }

    // *********************************************************************************************
    // * Debug/Dump/toString()
    // *********************************************************************************************

    @Test
    public void testDump() {
        String dumpContents = mAccountManager.dump();
        assertThat(dumpContents).isNotNull();
        assertThat(dumpContents.length()).isNotEqualTo(0);
    }

    // *********************************************************************************************
    // * Test Utilities
    // *********************************************************************************************

    private void startAccountManager() {
        mAccountManager.start();
        verify(mMockContext).registerReceiver(mReceiverCaptor.capture(), any(IntentFilter.class));
        mBroadcastReceiver = mReceiverCaptor.getValue();
    }

    private void sendUserUnlocked() {
        doReturn(true).when(mMockUserManager).isUserUnlocked();
        Intent intent = new Intent(Intent.ACTION_USER_UNLOCKED);
        mBroadcastReceiver.onReceive(mMockContext, intent);
    }

    private static Account getAccountForDevice(BluetoothDevice device) {
        return new Account(device.getAddress(), "com.android.bluetooth.pbabclient.account");
    }

    private void setAccountVisibility(int visibility) {
        doReturn(visibility)
                .when(mMockAccountManager)
                .getAccountVisibility(any(Account.class), anyString());
    }

    private void setAvailableAccounts(Account[] accounts) {
        doReturn(accounts).when(mMockAccountManager).getAccountsByType(anyString());
    }
}
