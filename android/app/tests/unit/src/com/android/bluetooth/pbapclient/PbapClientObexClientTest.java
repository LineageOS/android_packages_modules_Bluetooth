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

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.FakeObexServer;
import com.android.bluetooth.ObexAppParameters;
import com.android.bluetooth.TestLooper;
import com.android.obex.ApplicationParameter;
import com.android.obex.HeaderSet;
import com.android.obex.ObexTransport;
import com.android.obex.Operation;
import com.android.obex.ResponseCodes;
import com.android.vcard.VCardEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class PbapClientObexClientTest {
    private static final int TEST_L2CAP_PSM = 4098;
    private static final int TEST_RFCOMM_CHANNEL_ID = 3;

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private BluetoothAdapter mAdapter = null;
    private BluetoothDevice mDevice;

    // Normal supported features for our client
    private static final int SUPPORTED_FEATURES =
            PbapSdpRecord.FEATURE_DOWNLOADING
                    | PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT;

    // Default property filter for downloaded contacts
    private static final long DEFAULT_PROPERTIES =
            PbapApplicationParameters.PROPERTY_VERSION
                    | PbapApplicationParameters.PROPERTY_FN
                    | PbapApplicationParameters.PROPERTY_N
                    | PbapApplicationParameters.PROPERTY_PHOTO
                    | PbapApplicationParameters.PROPERTY_ADR
                    | PbapApplicationParameters.PROPERTY_TEL
                    | PbapApplicationParameters.PROPERTY_EMAIL
                    | PbapApplicationParameters.PROPERTY_NICKNAME;

    // Default configuration for VCard format -> prefer 3.0 to 2.1
    private static final byte DEFAULT_VCARD_VERSION = PbapPhonebook.FORMAT_VCARD_30;

    private TestLooper mTestLooper;
    private FakePbapObexServer mServer;

    @Captor ArgumentCaptor<PbapPhonebookMetadata> mMetadataCaptor;
    @Captor ArgumentCaptor<PbapPhonebook> mPhonebookCaptor;

    @Mock PbapClientObexClient.Callback mMockCallback;
    PbapClientObexClient mObexClient;

    @Before
    public void setUp() throws IOException {
        mAdapter =
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getSystemService(BluetoothManager.class)
                        .getAdapter();
        assertThat(mAdapter).isNotNull();
        mDevice = getTestDevice(1);

        mServer = new FakePbapObexServer();
        ObexTransport transport = mServer.getClientTransport();
        PbapClientSocket.inject(transport.openInputStream(), transport.openOutputStream());

        mTestLooper = new TestLooper();
        mObexClient =
                new PbapClientObexClient(
                        mDevice, SUPPORTED_FEATURES, mMockCallback, mTestLooper.getLooper());
    }

    @After
    public void tearDown() throws IOException {
        mServer.close();
    }

    // *********************************************************************************************
    // * Base State
    // *********************************************************************************************

    @Test
    public void testClientCreated_inDisconnectedState() {
        assertThat(mObexClient.getTransportType()).isEqualTo(PbapClientObexClient.TRANSPORT_NONE);
        assertThat(mObexClient.getL2capPsm()).isEqualTo(PbapClientObexClient.L2CAP_INVALID_PSM);
        assertThat(mObexClient.getRfcommChannelId())
                .isEqualTo(PbapClientObexClient.RFCOMM_INVALID_CHANNEL_ID);
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mObexClient.isConnected()).isFalse();
    }

    // *********************************************************************************************
    // * Connection Establishment
    // *********************************************************************************************

    // L2CAP

    @Test
    public void testConnect_usingL2capTransport_deviceConnected() throws IOException {
        mObexClient.connectL2cap(TEST_L2CAP_PSM);
        mTestLooper.dispatchAll();

        verify(mMockCallback)
                .onConnectionStateChanged(eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));
        verify(mMockCallback).onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_CONNECTED));
        assertThat(mObexClient.getTransportType()).isEqualTo(PbapClientObexClient.TRANSPORT_L2CAP);
        assertThat(mObexClient.getL2capPsm()).isEqualTo(TEST_L2CAP_PSM);
        assertThat(mObexClient.getRfcommChannelId())
                .isEqualTo(PbapClientObexClient.RFCOMM_INVALID_CHANNEL_ID);
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_CONNECTED);
        assertThat(mObexClient.isConnected()).isTrue();
    }

    // RFCOMM

    @Test
    public void testConnect_usingRfcommTransport_deviceConnected() throws IOException {
        mObexClient.connectRfcomm(TEST_RFCOMM_CHANNEL_ID);
        mTestLooper.dispatchAll();

        verify(mMockCallback)
                .onConnectionStateChanged(eq(STATE_DISCONNECTED), eq(STATE_CONNECTING));
        verify(mMockCallback).onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_CONNECTED));
        assertThat(mObexClient.getTransportType()).isEqualTo(PbapClientObexClient.TRANSPORT_RFCOMM);
        assertThat(mObexClient.getRfcommChannelId()).isEqualTo(TEST_RFCOMM_CHANNEL_ID);
        assertThat(mObexClient.getL2capPsm()).isEqualTo(PbapClientObexClient.L2CAP_INVALID_PSM);
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_CONNECTED);
        assertThat(mObexClient.isConnected()).isTrue();
    }

    // *********************************************************************************************
    // * Request Metadata
    // *********************************************************************************************

    @Test
    public void testRequestPhonebookMetadata() throws IOException {
        testConnect_usingL2capTransport_deviceConnected();

        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Foo",
                        "Bar",
                        "+1-234-567-8901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Bar",
                        "Foo",
                        "+1-345-678-9012",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Bar@email.com");
        List<String> contacts = Arrays.asList(vcard1, vcard2);
        mServer.setPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH, 1l, 1l, 1l, contacts);

        PbapApplicationParameters params =
                new PbapApplicationParameters(
                        DEFAULT_PROPERTIES,
                        DEFAULT_VCARD_VERSION,
                        PbapApplicationParameters.RETURN_SIZE_ONLY,
                        0);
        mObexClient.requestPhonebookMetadata(PbapPhonebook.LOCAL_PHONEBOOK_PATH, params);
        mTestLooper.dispatchAll();

        verify(mMockCallback)
                .onGetPhonebookMetadataComplete(
                        eq(160), eq(PbapPhonebook.LOCAL_PHONEBOOK_PATH), mMetadataCaptor.capture());
        PbapPhonebookMetadata metadata = mMetadataCaptor.getValue();
        assertThat(metadata.getPhonebook()).isEqualTo(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        assertThat(metadata.getSize()).isEqualTo(2);
    }

    // *********************************************************************************************
    // * Request Contacts and Call History
    // *********************************************************************************************

    @Test
    public void testRequestPhonebook() throws IOException {
        testConnect_usingL2capTransport_deviceConnected();

        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Foo",
                        "Bar",
                        "+1-234-567-8901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        List<String> contacts = Arrays.asList(vcard1);
        mServer.setPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH, 1l, 1l, 1l, contacts);

        // Common download parameters for a client
        int numToFetch = 250;
        int batchStart = 0;

        PbapApplicationParameters params =
                new PbapApplicationParameters(
                        DEFAULT_PROPERTIES, DEFAULT_VCARD_VERSION, numToFetch, batchStart);
        mObexClient.requestDownloadPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH, params);
        mTestLooper.dispatchAll();

        verify(mMockCallback)
                .onPhonebookContactsDownloaded(
                        eq(160),
                        eq(PbapPhonebook.LOCAL_PHONEBOOK_PATH),
                        mPhonebookCaptor.capture());
        PbapPhonebook phonebook = mPhonebookCaptor.getValue();
        assertThat(phonebook).isNotNull();
        assertThat(phonebook.getPhonebook()).isEqualTo(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        assertThat(phonebook.getOffset()).isEqualTo(0);
        assertThat(phonebook.getCount()).isEqualTo(1);
        assertThat(phonebook.getList()).isNotEmpty();
        assertThat(phonebook.getList()).hasSize(1);

        VCardEntry contact1 = phonebook.getList().get(0);
        assertThat(contact1.getDisplayName()).isEqualTo("Foo Bar");
        assertThat(contact1.getPhoneList()).isNotNull();
        assertThat(contact1.getPhoneList()).hasSize(1);
        assertThat(contact1.getPhoneList().get(0).getNumber()).isEqualTo("+1-234-567-8901");
    }

    // *********************************************************************************************
    // * Disconnections
    // *********************************************************************************************

    @Test
    public void testDisconnect_obexDisconnected_nothingHappens() {
        assertThat(mObexClient.isConnected()).isFalse();

        mObexClient.disconnect();
        mTestLooper.dispatchAll();

        assertThat(mObexClient.getTransportType()).isEqualTo(PbapClientObexClient.TRANSPORT_NONE);
        assertThat(mObexClient.getL2capPsm()).isEqualTo(PbapClientObexClient.L2CAP_INVALID_PSM);
        assertThat(mObexClient.getRfcommChannelId())
                .isEqualTo(PbapClientObexClient.RFCOMM_INVALID_CHANNEL_ID);
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mObexClient.isConnected()).isFalse();
    }

    @Test
    public void testDisconnect_disconnectAfterConnect_handlerQuits() throws IOException {
        mObexClient.connectL2cap(TEST_L2CAP_PSM);
        mObexClient.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockCallback, never()).onConnectionStateChanged(anyInt(), anyInt());

        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mObexClient.isConnected()).isFalse();
    }

    @Test
    public void testDisconnect_obexConnected_obexDisconnects() throws IOException {
        testConnect_usingL2capTransport_deviceConnected();

        mObexClient.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testDisconnect_obexDisconnecting_nothingHappens() throws IOException {
        testConnect_usingL2capTransport_deviceConnected();

        // Stack a second on top immediately
        mObexClient.disconnect();
        mObexClient.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testDisconnect_whileRequestingPhonebookMetadata_obexDisconnects()
            throws IOException {
        testConnect_usingL2capTransport_deviceConnected();

        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Foo",
                        "Bar",
                        "+1-234-567-8901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        String vcard2 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Bar",
                        "Foo",
                        "+1-345-678-9012",
                        "112 Test Street;Test Town;CA;90210;USA",
                        "Bar@email.com");
        List<String> contacts = Arrays.asList(vcard1, vcard2);
        mServer.setPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH, 1l, 1l, 1l, contacts);

        PbapApplicationParameters params =
                new PbapApplicationParameters(
                        DEFAULT_PROPERTIES,
                        DEFAULT_VCARD_VERSION,
                        PbapApplicationParameters.RETURN_SIZE_ONLY,
                        0);
        mObexClient.requestPhonebookMetadata(PbapPhonebook.LOCAL_PHONEBOOK_PATH, params);

        mObexClient.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testDisconnect_whileRequestingPhonebook_obexDisconnects() throws IOException {
        testConnect_usingL2capTransport_deviceConnected();

        String vcard1 =
                Utils.createVcard(
                        Utils.VERSION_30,
                        "Foo",
                        "Bar",
                        "+1-234-567-8901",
                        "111 Test Street;Test Town;CA;90210;USA",
                        "Foo@email.com");
        List<String> contacts = Arrays.asList(vcard1);
        mServer.setPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH, 1l, 1l, 1l, contacts);

        // Common download parameters for a client
        int numToFetch = 250;
        int batchStart = 0;

        PbapApplicationParameters params =
                new PbapApplicationParameters(
                        DEFAULT_PROPERTIES, DEFAULT_VCARD_VERSION, numToFetch, batchStart);
        mObexClient.requestDownloadPhonebook(PbapPhonebook.LOCAL_PHONEBOOK_PATH, params);

        mObexClient.disconnect();
        mTestLooper.dispatchAll();

        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_CONNECTED), eq(STATE_DISCONNECTING));
        verify(mMockCallback, times(1))
                .onConnectionStateChanged(eq(STATE_DISCONNECTING), eq(STATE_DISCONNECTED));
        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    // *********************************************************************************************
    // * Close (Forced Disconnections)
    // *********************************************************************************************

    @Test
    public void testClose_whileDisconnected_expected() {
        // Let the object make its own thread so closing it doesn't cause an InterruptedException
        mObexClient = new PbapClientObexClient(mDevice, SUPPORTED_FEATURES, mMockCallback);

        assertThat(mObexClient.isConnected()).isFalse();

        mObexClient.close();
        mTestLooper.dispatchAll();

        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
        assertThat(mObexClient.isConnected()).isFalse();
    }

    @Test
    public void testClose_whileConnected_expected() throws IOException {
        // Let the object make its own thread so closing it doesn't cause an InterruptedException
        mObexClient = new PbapClientObexClient(mDevice, SUPPORTED_FEATURES, mMockCallback);

        mObexClient.connectL2cap(TEST_L2CAP_PSM);

        // Timeout() is desirable, but we can't use test looper without receiving the
        // InterruptedExceptions
        verify(mMockCallback, timeout(2000))
                .onConnectionStateChanged(eq(STATE_CONNECTING), eq(STATE_CONNECTED));

        mObexClient.close();
        mTestLooper.dispatchAll();

        assertThat(mObexClient.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    // *********************************************************************************************
    // * Debug/Dump/toString()
    // *********************************************************************************************

    @Test
    public void testTransportToString() {
        assertThat(mObexClient.transportToString(PbapClientObexClient.TRANSPORT_NONE))
                .isEqualTo("TRANSPORT_NONE");
        assertThat(mObexClient.transportToString(PbapClientObexClient.TRANSPORT_RFCOMM))
                .isEqualTo("TRANSPORT_RFCOMM");
        assertThat(mObexClient.transportToString(PbapClientObexClient.TRANSPORT_L2CAP))
                .isEqualTo("TRANSPORT_L2CAP");
        assertThat(mObexClient.transportToString(/* unused transport id */ 4))
                .isEqualTo("TRANSPORT_RESERVED (4)");
    }

    @Test
    public void testPhonebookToString() throws IOException {
        String str = mObexClient.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    // *********************************************************************************************
    // * Fake PBAP Server
    // *********************************************************************************************

    private static class FakePbapObexServer extends FakeObexServer {
        private static final String TAG = FakePbapObexServer.class.getSimpleName();

        private static final String TYPE_GET_PHONEBOOK = "x-bt/phonebook";
        private static final byte SIZE_BYTES = 2;
        private static final byte DATABASE_IDENTIFIER_BYTES = 4;
        private static final byte VERSION_COUNTER_BYTES = 4;

        final Map<String, FakePhonebook> mPhonebooks = new HashMap<String, FakePhonebook>();
        private int mClientSupportedFeatures = 0;

        FakePbapObexServer() throws IOException {
            super();
        }

        public void setPhonebook(
                String phonebook,
                long dbIdentifier,
                long primaryVersion,
                long secondaryVersion,
                List<String> vcards) {
            FakePhonebook contacts = new FakePhonebook();
            if (vcards == null) {
                vcards = new ArrayList<>();
            }

            contacts.size = (short) vcards.size();
            contacts.dbIdentifier = dbIdentifier;
            contacts.primaryVersion = primaryVersion;
            contacts.secondaryVersion = secondaryVersion;
            contacts.vcards = vcards;

            mPhonebooks.put(phonebook, contacts);
        }

        @Override
        public int onConnect(final HeaderSet request, HeaderSet reply) {
            try {
                Log.i(TAG, "onConnect(): " + request.dump());

                ObexAppParameters oap = ObexAppParameters.fromHeaderSet(request);

                // PBAP v1.2.3, Sec. 6.4, Table 1, C3: supported features are required if the SDP
                // record also has them. If not present, the server should use 0x00000003.
                // PBAP v1.2.3, Sec. 6.2.1, size is 4 Bytes for PBAP Supported Features
                int supportedFeatures = 0x3;
                if (oap.exists(PbapApplicationParameters.OAP_PBAP_SUPPORTED_FEATURES)) {
                    supportedFeatures =
                            oap.getInt(PbapApplicationParameters.OAP_PBAP_SUPPORTED_FEATURES);
                }
                mClientSupportedFeatures = supportedFeatures;
            } catch (Exception e) {
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }

            return ResponseCodes.OBEX_HTTP_OK;
        }

        @Override
        public void onDisconnect(final HeaderSet request, HeaderSet reply) {
            Log.i(TAG, "onDisconnect()");
            // Automatically sends OBEX_HTTP_OK if no transport errors occur
        }

        @Override
        public int onGet(final Operation op) {
            Log.i(TAG, "onGet()");
            try {
                HeaderSet request = op.getReceivedHeader();

                Log.i(TAG, "onGet(): " + request.dump());

                String type = (String) request.getHeader(HeaderSet.TYPE);
                if (TYPE_GET_PHONEBOOK.equals(type)) {
                    String phonebook = (String) request.getHeader(HeaderSet.NAME);
                    Log.i(TAG, "onGet(): Request for phonebook=" + phonebook);
                    if (phonebook == null) {
                        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                    }

                    ObexAppParameters oap = ObexAppParameters.fromHeaderSet(request);
                    Log.i(TAG, "onGet(): Dumping Request Params\n" + oap);

                    int maxItemsToReturn = -1;
                    if (oap.exists(PbapApplicationParameters.OAP_MAX_LIST_COUNT)) {
                        maxItemsToReturn =
                                oap.getShort(PbapApplicationParameters.OAP_MAX_LIST_COUNT);
                    }

                    // Set MaxListCount in the request to 0 to get no items in the response, and get
                    // only metadata instead. If a vCardSelector is present in the request, then the
                    // result shall contain the number of items that satisfy the selector’s criteria
                    // See PBAP v1.2.3, Sec. 5.1.4.5.
                    if (maxItemsToReturn == 0) {
                        return onGetPhonebookMetadata(phonebook, op);
                    } else {
                        return onGetPhonebook(phonebook, op);
                    }
                }
            } catch (Exception e) {
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }

            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        private int onGetPhonebookMetadata(String phonebook, final Operation op) {
            Log.i(TAG, "onGetPhonebookMetadata(): Request metadata for phonebook=" + phonebook);

            short size = 0;
            long dbIdentifier = 0l;
            long primaryVersion = 0l;
            long secondaryVersion = 0l;

            FakePhonebook contacts = mPhonebooks.get(phonebook);
            if (contacts != null) {
                size = contacts.size;
                dbIdentifier = contacts.dbIdentifier;
                primaryVersion = contacts.primaryVersion;
                secondaryVersion = contacts.secondaryVersion;
            }

            Log.i(
                    TAG,
                    "onGetPhonebookMetadata(): Metadata={"
                            + ("size=" + size)
                            + (", dbIdentifier=" + dbIdentifier)
                            + (", primaryVersion=" + primaryVersion)
                            + (", secondaryVersion=" + secondaryVersion)
                            + "}");

            ApplicationParameter params = new ApplicationParameter();
            params.addTriplet(
                    PbapApplicationParameters.OAP_PHONEBOOK_SIZE,
                    SIZE_BYTES,
                    Utils.shortToByteArray(size));

            if ((mClientSupportedFeatures & PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS) == 1) {
                params.addTriplet(
                        PbapApplicationParameters.OAP_PRIMARY_FOLDER_VERSION,
                        VERSION_COUNTER_BYTES,
                        Utils.longToByteArray(primaryVersion));

                params.addTriplet(
                        PbapApplicationParameters.OAP_SECONDARY_FOLDER_VERSION,
                        VERSION_COUNTER_BYTES,
                        Utils.longToByteArray(secondaryVersion));
            }

            if ((mClientSupportedFeatures & PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER) == 1) {
                params.addTriplet(
                        PbapApplicationParameters.OAP_DATABASE_IDENTIFIER,
                        DATABASE_IDENTIFIER_BYTES,
                        Utils.longToByteArray(dbIdentifier));
            }

            HeaderSet replyHeaders = new HeaderSet();
            replyHeaders.setHeader(HeaderSet.APPLICATION_PARAMETER, params.getHeader());
            return sendResponse(op, replyHeaders, null);
        }

        private int onGetPhonebook(String phonebook, final Operation op) {
            Log.i(TAG, "onGetPhonebook(): Request metadata for phonebook=" + phonebook);
            String vcards = null;

            FakePhonebook contacts = mPhonebooks.get(phonebook);
            if (contacts != null) {
                vcards = Utils.createPhonebook(contacts.vcards);

                Log.i(
                        TAG,
                        "onGetPhonebook(): Contacts={"
                                + ("size=" + contacts.size)
                                + (", entries=" + contacts.vcards)
                                + "}");
            }

            byte[] payload = vcards == null ? null : vcards.getBytes();

            HeaderSet replyHeaders = new HeaderSet();
            return sendResponse(op, replyHeaders, payload);
        }

        private static class FakePhonebook {
            short size = 0;
            long dbIdentifier = 0l;
            long primaryVersion = 0l;
            long secondaryVersion = 0l;
            List<String> vcards = new ArrayList<String>();
        }
    }
}
