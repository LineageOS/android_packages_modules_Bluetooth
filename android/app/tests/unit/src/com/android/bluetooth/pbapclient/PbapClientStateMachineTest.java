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

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.accounts.Account;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.SdpPseRecord;
import android.content.Context;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.obex.ResponseCodes;
import com.android.vcard.VCardEntry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PbapClientStateMachineTest {
    private static final int L2CAP_PSM = 4101;
    private static final int RFCOMM_CHANNEL = 5;
    private static final int INVALID_L2CAP = -1;
    private static final int INVALID_RFCOMM = -1;
    private static final int SUPPORTED_FEATURES = PbapSdpRecord.FEATURE_DOWNLOADING;
    // private static final int SUPPORTED_FEATURES_CACHING =
    //         PbapSdpRecord.FEATURE_DOWNLOADING | PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER |
    // PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS;
    private static final int SUPPORTED_REPOSITORIES =
            PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK | PbapSdpRecord.REPOSITORY_FAVORITES;
    private static final int NO_REPOSITORIES_SUPPORTED = 0;

    // Constants for SDP. Note that these values come from the native stack, but no centralized
    // constants exist for them as part of the various SDP APIs. SDP_UNKNOWN is an unused value
    // for testing
    private static final int SDP_SUCCESS = 0;
    private static final int SDP_FAILED = 1;
    private static final int SDP_BUSY = 2;
    private static final int SDP_UNKNOWN = -1;

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private BluetoothDevice mTestDevice;

    @Mock private Context mMockContext;
    private TestLooper mTestLooper;

    @Mock private PbapClientContactsStorage mMockStorage;
    ArgumentCaptor<PbapClientContactsStorage.Callback> mCaptor =
            ArgumentCaptor.forClass(PbapClientContactsStorage.Callback.class);
    private PbapClientContactsStorage.Callback mStorageCallback;
    private List<Account> mMockedAccounts = new ArrayList<>();

    @Mock private PbapClientObexClient mMockObexClient;

    @Mock private PbapClientStateMachine.Callback mMockCallback;

    private PbapClientStateMachine mPbapClientStateMachine = null;
    private PbapClientStateMachine.PbapClientObexClientCallback mObexCallback;

    @Before
    public void setUp() throws Exception {
        mTestDevice = getTestDevice(1);

        doNothing().when(mMockObexClient).connectL2cap(anyInt());
        doNothing().when(mMockObexClient).connectRfcomm(anyInt());

        // Mock Contacts Storage
        doReturn(false).when(mMockStorage).isStorageReady();

        doAnswer(
                        invocation -> {
                            BluetoothDevice device = (BluetoothDevice) invocation.getArgument(0);
                            return getAccountForDevice(device);
                        })
                .when(mMockStorage)
                .getStorageAccountForDevice(any(BluetoothDevice.class));

        doAnswer(
                        invocation -> {
                            Account account = (Account) invocation.getArgument(0);
                            mMockedAccounts.add(account);
                            return true;
                        })
                .when(mMockStorage)
                .addAccount(any(Account.class));

        doAnswer(
                        invocation -> {
                            Account account = (Account) invocation.getArgument(0);
                            mMockedAccounts.remove(account);
                            return true;
                        })
                .when(mMockStorage)
                .removeAccount(any(Account.class));

        doAnswer(
                        invocation -> {
                            return mMockedAccounts;
                        })
                .when(mMockStorage)
                .getStorageAccounts();

        mTestLooper = new TestLooper();

        mPbapClientStateMachine =
                new PbapClientStateMachine(
                        mTestDevice,
                        mMockStorage,
                        mMockContext,
                        mTestLooper.getLooper(),
                        mMockCallback,
                        mMockObexClient);
        mObexCallback = mPbapClientStateMachine.new PbapClientObexClientCallback();
        mPbapClientStateMachine.start();
    }

    // *********************************************************************************************
    // * Disconnected
    // *********************************************************************************************

    @Test
    public void testDisconnected_receivedDisconnect_nothingHappens() {
        mPbapClientStateMachine.disconnect();
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mMockCallback, never()).onConnectionStateChanged(anyInt(), anyInt());
    }

    @Test
    public void testDisconnected_receivedConnect_connectionStateChangesToConnecting() {
        mPbapClientStateMachine.connect();
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTING);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));
    }

    // *********************************************************************************************
    // * Connecting
    // *********************************************************************************************

    @Test
    public void testConnecting_receivedSdpResultWithTransportL2cap_connectionReqOnL2cap() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM, INVALID_RFCOMM, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        verify(mMockObexClient, times(1)).connectL2cap(eq(L2CAP_PSM));
    }

    @Test
    public void testConnecting_receivedSdpResultWithTransportRfcomm_connectionReqOnRfcomm() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        INVALID_L2CAP, RFCOMM_CHANNEL, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        verify(mMockObexClient, times(1)).connectRfcomm(eq(RFCOMM_CHANNEL));
    }

    @Test
    public void testConnecting_receivedSdpResultWithTransportL2capOrRfcomm_connectionReqOnL2cap() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM, RFCOMM_CHANNEL, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        verify(mMockObexClient, times(1)).connectL2cap(eq(L2CAP_PSM));
    }

    @Test
    public void testConnecting_receivedSdpResultWithFailedStatus_transitiontoDisconnecting() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_FAILED,
                makeSdpRecord(
                        L2CAP_PSM, RFCOMM_CHANNEL, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        verify(mMockObexClient, never()).connectL2cap(eq(L2CAP_PSM));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testConnecting_receivedSdpResultWithBusyStatus_sdpRetried() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_BUSY,
                makeSdpRecord(
                        L2CAP_PSM, RFCOMM_CHANNEL, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        // We can't currently mock a BluetoothDevice to verify the sdpSearch() call, but we can
        // validate that the state machine stays in the same state and will adequately receive the
        // next valid SDP record that arrives.
        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTING);

        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM, RFCOMM_CHANNEL, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        verify(mMockObexClient, times(1)).connectL2cap(eq(L2CAP_PSM));
    }

    @Test
    public void testConnecting_receivedSdpResultWithUnknownStatus_transitiontoDisconnecting() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_UNKNOWN,
                makeSdpRecord(
                        L2CAP_PSM, RFCOMM_CHANNEL, SUPPORTED_FEATURES, SUPPORTED_REPOSITORIES));
        mTestLooper.dispatchAll();

        verify(mMockObexClient, never()).connectL2cap(eq(L2CAP_PSM));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testConnecting_receivedObexConnection_transitionToConnected() {
        testConnecting_receivedSdpResultWithTransportL2cap_connectionReqOnL2cap();
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_CONNECTED));
    }

    @Test
    public void testConnecting_receivedConnectionTimeout_transitionToDisconnecting() {
        testConnecting_receivedSdpResultWithTransportL2cap_connectionReqOnL2cap();
        mTestLooper.moveTimeForward(PbapClientStateMachine.CONNECT_TIMEOUT_MS);
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testConnecting_receivedDisconnect_transitionToDisconnecting() {
        testConnecting_receivedSdpResultWithTransportL2cap_connectionReqOnL2cap();
        mPbapClientStateMachine.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testConnecting_receivedObexDisconnection_transitionToDisconnected() {
        testConnecting_receivedSdpResultWithTransportL2cap_connectionReqOnL2cap();
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_DISCONNECTED);
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
    }

    // *********************************************************************************************
    // * Connected
    // *********************************************************************************************

    @Test
    public void testConnected_storageNotReady_downloadNotInitiated() {
        testConnecting_receivedObexConnection_transitionToConnected();
        verify(mMockStorage, times(1)).registerCallback(mCaptor.capture());
        mStorageCallback = mCaptor.getValue();

        verifyNoMoreInteractions(mMockObexClient);
    }

    @Test
    public void testConnected_receivedStorageReadyWithoutAccountReady_accountAdded() {
        testConnected_storageNotReady_downloadNotInitiated();
        mStorageCallback.onStorageReady();
        mTestLooper.dispatchAll();

        verify(mMockStorage).addAccount(eq(getAccountForDevice(mTestDevice)));
    }

    @Test
    public void testConnected_storageReadyImmediatelyWithoutAccountReady_accountAdded() {
        doReturn(true).when(mMockStorage).isStorageReady();
        testConnecting_receivedObexConnection_transitionToConnected();
        verify(mMockStorage, times(1)).registerCallback(mCaptor.capture());
        mStorageCallback = mCaptor.getValue();
        mTestLooper.dispatchAll();

        verify(mMockStorage).addAccount(eq(getAccountForDevice(mTestDevice)));
    }

    @Test
    public void testConnected_storageReadyImmediatelyWithAccountReady_downloadStarted() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        testConnecting_receivedObexConnection_transitionToConnected();
        verify(mMockStorage, times(1)).registerCallback(mCaptor.capture());
        mStorageCallback = mCaptor.getValue();
        mTestLooper.dispatchAll();

        verify(mMockObexClient)
                .requestPhonebookMetadata(anyString(), any(PbapApplicationParameters.class));
    }

    @Test
    public void testConnected_receivedAccountReady_downloadStarted() {
        testConnected_receivedStorageReadyWithoutAccountReady_accountAdded();
        mStorageCallback.onStorageAccountsChanged(new ArrayList<Account>(), mMockedAccounts);
        mTestLooper.dispatchAll();

        verify(mMockObexClient)
                .requestPhonebookMetadata(anyString(), any(PbapApplicationParameters.class));
    }

    @Test
    public void testConnected_receivedAccountRemoved_transitionToDisconnecting() {
        testConnected_receivedAccountReady_downloadStarted();

        // Normally the account would get added, a download would happen, and then the user may
        // remove the account in Settings at any time, causing us to get this. But skip the download
        // mocking for tests.
        mStorageCallback.onStorageAccountsChanged(mMockedAccounts, new ArrayList<Account>());
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
    }

    @Test
    public void testConnected_receivedObexDisconnection_transitionToDisconnected() {
        testConnecting_receivedObexConnection_transitionToConnected();
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_DISCONNECTED);
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
    }

    @Test
    public void testConnected_receivedDisconnect_transitiontoDisconnecting() {
        testConnecting_receivedObexConnection_transitionToConnected();
        mPbapClientStateMachine.disconnect();
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTING);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
    }

    // *********************************************************************************************
    // * Downloading
    // *********************************************************************************************

    @Test
    public void testDownloading_multiplePhonebooksSupported_allPhonebooksDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.FAVORITES_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.LOCAL_PHONEBOOK_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.MCH_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.ICH_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.OCH_PATH, "0", "0", "0", 5);

        mockRemoteContacts(PbapPhonebook.SIM_PHONEBOOK_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.SIM_MCH_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.SIM_ICH_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.SIM_OCH_PATH, "0", "0", "0", 0);

        testConnecting_receivedObexConnection_transitionToConnected();
        mTestLooper.dispatchAll();

        verify(mMockStorage, times(1)).insertFavorites(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertLocalContacts(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertMissedCallHistory(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertIncomingCallHistory(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertOutgoingCallHistory(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_onlyFavoritesSupported_favoritesDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.FAVORITES_PATH, "0", "0", "0", 5);

        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_FAVORITES));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        verify(mMockStorage, times(1)).insertFavorites(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_onlyLocalPhonebookSupported_localPhonebooksDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.LOCAL_PHONEBOOK_PATH, "0", "0", "0", 5);

        mockRemoteContacts(PbapPhonebook.MCH_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.ICH_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.OCH_PATH, "0", "0", "0", 0);

        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        verify(mMockStorage, times(1)).insertLocalContacts(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_onlyCallHistorySupported_callHistoryDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.ICH_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.OCH_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.MCH_PATH, "0", "0", "0", 5);

        mockRemoteContacts(PbapPhonebook.LOCAL_PHONEBOOK_PATH, "0", "0", "0", 0);

        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        verify(mMockStorage, never()).insertLocalContacts(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertMissedCallHistory(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertIncomingCallHistory(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertOutgoingCallHistory(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_onlySimPhonebookSupported_simPhonebooksDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.SIM_PHONEBOOK_PATH, "0", "0", "0", 5);

        mockRemoteContacts(PbapPhonebook.SIM_MCH_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.SIM_ICH_PATH, "0", "0", "0", 0);
        mockRemoteContacts(PbapPhonebook.SIM_OCH_PATH, "0", "0", "0", 0);

        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_SIM_CARD));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        verify(mMockStorage, times(1)).insertSimContacts(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_onlySimCallHistorySupported_simCallHistoryDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.SIM_ICH_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.SIM_OCH_PATH, "0", "0", "0", 5);
        mockRemoteContacts(PbapPhonebook.SIM_MCH_PATH, "0", "0", "0", 5);

        mockRemoteContacts(PbapPhonebook.SIM_PHONEBOOK_PATH, "0", "0", "0", 0);

        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_SIM_CARD));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        verify(mMockStorage, times(1)).insertMissedCallHistory(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertIncomingCallHistory(any(Account.class), anyList());
        verify(mMockStorage, times(1)).insertOutgoingCallHistory(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_onlyLargeBatchOfFavoritesSupported_favoritesDownloaded() {
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mockRemoteContacts(PbapPhonebook.FAVORITES_PATH, "0", "0", "0", 1000);

        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_FAVORITES));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        verify(mMockStorage, times(4)).insertFavorites(any(Account.class), anyList());
    }

    @Test
    public void testDownloading_noRepositoriesSupported_nothingDownloaded() {
        testDisconnected_receivedConnect_connectionStateChangesToConnecting();

        // Make storage ready, add the account, send an SDP record with no supported repositories
        mMockedAccounts.add(getAccountForDevice(mTestDevice));
        doReturn(true).when(mMockStorage).isStorageReady();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM, INVALID_RFCOMM, SUPPORTED_FEATURES, NO_REPOSITORIES_SUPPORTED));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        // Verify we're connected
        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_CONNECTED);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_CONNECTED));

        // Verify storage not hit
        verify(mMockStorage, never()).insertFavorites(any(Account.class), anyList());
        verify(mMockStorage, never()).insertLocalContacts(any(Account.class), anyList());
        verify(mMockStorage, never()).insertSimContacts(any(Account.class), anyList());
        verify(mMockStorage, never()).insertMissedCallHistory(any(Account.class), anyList());
        verify(mMockStorage, never()).insertIncomingCallHistory(any(Account.class), anyList());
        verify(mMockStorage, never()).insertOutgoingCallHistory(any(Account.class), anyList());
    }

    @Test
    public void testDownloadRequestAfterDownloadingHasCompleted_downloadNotReattempted() {
        mockRemoteContacts(PbapPhonebook.FAVORITES_PATH, "0", "0", "0", 5);

        // Connect
        mPbapClientStateMachine.connect();
        mPbapClientStateMachine.onSdpResultReceived(
                SDP_SUCCESS,
                makeSdpRecord(
                        L2CAP_PSM,
                        INVALID_RFCOMM,
                        SUPPORTED_FEATURES,
                        PbapSdpRecord.REPOSITORY_FAVORITES));
        setAndNotifyObexClientStatus(STATE_DISCONNECTED, STATE_CONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_CONNECTED);
        mTestLooper.dispatchAll();

        // Get storage callback
        verify(mMockStorage, times(1)).registerCallback(mCaptor.capture());
        mStorageCallback = mCaptor.getValue();
        mStorageCallback.onStorageReady();
        mTestLooper.dispatchAll();

        // Issue storage Ready and wait for download
        verify(mMockStorage).addAccount(eq(getAccountForDevice(mTestDevice)));
        mStorageCallback.onStorageAccountsChanged(new ArrayList<Account>(), mMockedAccounts);
        mTestLooper.dispatchAll();

        // Make sure download happened and then clear invocations.
        verify(mMockStorage, times(1)).insertFavorites(any(Account.class), anyList());
        clearInvocations(mMockStorage);
        clearInvocations(mMockObexClient);

        // Resend a storage ready to trigger another download
        mStorageCallback.onStorageReady();
        mTestLooper.dispatchAll();

        // No interactions should happen on the client or storage, besides the account check
        verify(mMockStorage, times(1)).getStorageAccounts();
        verifyNoMoreInteractions(mMockStorage);
        verifyNoMoreInteractions(mMockObexClient);
    }

    // *********************************************************************************************
    // * Disconnecting
    // *********************************************************************************************

    @Test
    public void testEnterDisconnecting_clientConnected_disconnectIssued() {
        testConnected_receivedDisconnect_transitiontoDisconnecting();
        verify(mMockObexClient, times(1)).disconnect();
    }

    @Test
    public void testDisconnecting_clientDisconnects_transitionToDisconnected() {
        testEnterDisconnecting_clientConnected_disconnectIssued();
        setAndNotifyObexClientStatus(STATE_CONNECTED, STATE_DISCONNECTING);
        setAndNotifyObexClientStatus(STATE_CONNECTING, STATE_DISCONNECTED);
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
    }

    @Test
    public void testDisconnecting_disconnectingTimeout_transitionToDisconnected() {
        testEnterDisconnecting_clientConnected_disconnectIssued();
        mTestLooper.moveTimeForward(PbapClientStateMachine.DISCONNECT_TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mPbapClientStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
    }

    @Test
    public void testDisconnecting_receiveDisconnectRequest_requestIgnored() {
        testEnterDisconnecting_clientConnected_disconnectIssued();
        clearInvocations(mMockObexClient);

        mPbapClientStateMachine.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockObexClient, never()).disconnect();
    }

    // *********************************************************************************************
    // * Debug/Dump/toString()
    // *********************************************************************************************

    @Test
    public void testDump() {
        testConnected_storageNotReady_downloadNotInitiated();
        StringBuilder sb = new StringBuilder();
        mPbapClientStateMachine.dump(sb);
        String dumpContents = sb.toString();
        assertThat(dumpContents).isNotNull();
        assertThat(dumpContents.length()).isNotEqualTo(0);
    }

    @Test
    public void testDump_noSdpRecord() {
        StringBuilder sb = new StringBuilder();
        mPbapClientStateMachine.dump(sb);
        String dumpContents = sb.toString();
        assertThat(dumpContents).isNotNull();
        assertThat(dumpContents.length()).isNotEqualTo(0);
    }

    // *********************************************************************************************
    // * Test Utilities
    // *********************************************************************************************

    private PbapSdpRecord makeSdpRecord(int l2capPsm, int rfcommChnl, int feats, int repositories) {
        SdpPseRecord sdpRecord =
                new SdpPseRecord(l2capPsm, rfcommChnl, 0x0102, feats, repositories, null);
        return new PbapSdpRecord(mTestDevice, sdpRecord);
    }

    private static Account getAccountForDevice(BluetoothDevice device) {
        return new Account(device.getAddress(), "com.android.bluetooth.pbabclient.account");
    }

    private void setAndNotifyObexClientStatus(int from, int to) {
        doReturn(to).when(mMockObexClient).getConnectionState();
        mObexCallback.onConnectionStateChanged(from, to);
    }

    private void mockRemoteContacts(
            String phonebook,
            String dbIdentifier,
            String primaryVersion,
            String secondaryVersion,
            int numContacts) {
        doAnswer(
                        invocation -> {
                            String pb = (String) invocation.getArgument(0);
                            PbapPhonebookMetadata metadata =
                                    new PbapPhonebookMetadata(
                                            pb,
                                            numContacts,
                                            dbIdentifier,
                                            primaryVersion,
                                            secondaryVersion);
                            mObexCallback.onGetPhonebookMetadataComplete(
                                    ResponseCodes.OBEX_HTTP_OK, pb, metadata);
                            return null;
                        })
                .when(mMockObexClient)
                .requestPhonebookMetadata(eq(phonebook), any(PbapApplicationParameters.class));

        doAnswer(
                        invocation -> {
                            String pb = (String) invocation.getArgument(0);
                            PbapApplicationParameters params = invocation.getArgument(1);
                            PbapPhonebook book = mock(PbapPhonebook.class);
                            List<VCardEntry> contacts = mock(List.class);

                            doReturn(pb).when(book).getPhonebook();

                            int offset = params.getListStartOffset();
                            int end = offset + params.getMaxListCount();
                            end = (end >= numContacts ? numContacts : end);

                            if (offset < numContacts) {
                                doReturn(end - offset).when(contacts).size();
                                doReturn(end - offset).when(book).getCount();
                            } else {
                                doReturn(0).when(contacts).size();
                                doReturn(0).when(book).getCount();
                            }

                            doReturn(offset).when(book).getOffset();
                            doReturn(contacts).when(book).getList();

                            mObexCallback.onPhonebookContactsDownloaded(
                                    ResponseCodes.OBEX_HTTP_OK, pb, book);
                            return null;
                        })
                .when(mMockObexClient)
                .requestDownloadPhonebook(eq(phonebook), any(PbapApplicationParameters.class));
    }
}
