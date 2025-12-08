/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class HandleMapTest {

    private final HandleMap mHandleMap = new HandleMap();

    private static final int SERVER_IF_1 = 84;
    private static final int SERVER_IF_2 = 85;

    private static final int CONN_ID_1 = 1;
    private static final int TRANS_ID_1 = 1;
    private static final int HANDLE_1 = 1;

    private static final int CONN_ID_2 = 2;
    private static final int TRANS_ID_2 = 2;
    private static final int HANDLE_2 = 2;

    private static final int REQUEST_ID_INVALID = -1;

    private static final UUID FAKE_UUID = UUID.randomUUID();
    private static final int SERVICE_HANDLE = 10;
    private static final int SERVICE_TYPE = 0;
    private static final int INSTANCE = 1;
    private static final boolean ADVERTISE_PREFERRED = true;
    private static final int CHARACTERISTIC_HANDLE = 11;
    private static final int DESCRIPTOR_HANDLE = 12;

    @Rule public Expect expect = Expect.create();

    /** Verifies that adding a service creates a correct Entry object. */
    @Test
    public void testAddService_createsCorrectEntry() {
        mHandleMap.addService(
                SERVER_IF_1,
                SERVICE_HANDLE,
                FAKE_UUID,
                SERVICE_TYPE,
                INSTANCE,
                ADVERTISE_PREFERRED);

        assertThat(mHandleMap.getEntries()).hasSize(1);
        HandleMap.Entry entry = mHandleMap.getEntries().get(0);

        expect.that(entry.mServerIf).isEqualTo(SERVER_IF_1);
        expect.that(entry.mType).isEqualTo(HandleMap.Type.SERVICE);
        expect.that(entry.mHandle).isEqualTo(SERVICE_HANDLE);
        expect.that(entry.mUuid).isEqualTo(FAKE_UUID);
        expect.that(entry.mInstance).isEqualTo(INSTANCE);
        expect.that(entry.mServiceType).isEqualTo(SERVICE_TYPE);
        expect.that(entry.mServiceHandle).isEqualTo(0);
        expect.that(entry.mCharHandle).isEqualTo(0);
        expect.that(entry.mAdvertisePreferred).isEqualTo(ADVERTISE_PREFERRED);
    }

    /** Verifies that adding a characteristic creates a correct Entry object. */
    @Test
    public void testAddCharacteristic_createsCorrectEntry() {
        mHandleMap.addCharacteristic(SERVER_IF_1, CHARACTERISTIC_HANDLE, FAKE_UUID, SERVICE_HANDLE);

        assertThat(mHandleMap.getEntries()).hasSize(1);
        HandleMap.Entry entry = mHandleMap.getEntries().get(0);

        assertThat(entry.mServerIf).isEqualTo(SERVER_IF_1);
        assertThat(entry.mType).isEqualTo(HandleMap.Type.CHARACTERISTIC);
        assertThat(entry.mHandle).isEqualTo(CHARACTERISTIC_HANDLE);
        assertThat(entry.mUuid).isEqualTo(FAKE_UUID);
        assertThat(entry.mServiceHandle).isEqualTo(SERVICE_HANDLE);
        // Check default values for fields not set by this constructor
        assertThat(entry.mInstance).isEqualTo(0);
        assertThat(entry.mServiceType).isEqualTo(0);
        assertThat(entry.mCharHandle).isEqualTo(0);
        assertThat(entry.mAdvertisePreferred).isFalse();
    }

    /** Verifies that adding a descriptor creates a correct Entry object. */
    @Test
    public void testAddDescriptor_createsCorrectEntry() {
        // addDescriptor uses the handle of the last added characteristic
        mHandleMap.addCharacteristic(SERVER_IF_1, CHARACTERISTIC_HANDLE, FAKE_UUID, SERVICE_HANDLE);
        mHandleMap.addDescriptor(SERVER_IF_1, DESCRIPTOR_HANDLE, FAKE_UUID, SERVICE_HANDLE);

        assertThat(mHandleMap.getEntries()).hasSize(2);
        // The descriptor is the second entry added
        HandleMap.Entry entry = mHandleMap.getEntries().get(1);

        assertThat(entry.mServerIf).isEqualTo(SERVER_IF_1);
        assertThat(entry.mType).isEqualTo(HandleMap.Type.DESCRIPTOR);
        assertThat(entry.mHandle).isEqualTo(DESCRIPTOR_HANDLE);
        assertThat(entry.mUuid).isEqualTo(FAKE_UUID);
        assertThat(entry.mServiceHandle).isEqualTo(SERVICE_HANDLE);
        assertThat(entry.mCharHandle).isEqualTo(CHARACTERISTIC_HANDLE);
        // Check default values for fields not set by this constructor
        assertThat(entry.mInstance).isEqualTo(0);
        assertThat(entry.mServiceType).isEqualTo(0);
        assertThat(entry.mAdvertisePreferred).isFalse();
    }

    /*
     * Requests from different bearers can that have the same transaction IDs and target the same
     * handles.
     *
     * <p>Test that adding a request ID for two bearers using the same transaction ID returns two
     * unique request IDs
     */
    @Test
    public void testAddRequestContext_addTwoRequestsFromDifferentBearers_returnsUniqueIds() {
        int requestId1 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        int requestId2 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_2, TRANS_ID_1, HANDLE_2);

        assertThat(requestId1).isNotEqualTo(requestId2);
    }

    /*
     * Requests from the same bearers must have a different transaction ID but can target the same
     * handles.
     *
     * <p>Test that adding a request IDs for a bearer using different transaction IDs returns two
     * unique request IDs
     */
    @Test
    public void testAddRequestContext_addTwoRequestsFromSameBearer_returnsUniqueIds() {
        int requestId1 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        int requestId2 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_2, HANDLE_1);

        assertThat(requestId1).isNotEqualTo(requestId2);
    }

    /*
     * Transactions from the native stack _should not_ have identical transaction IDs. Regardless,
     * our implementation here simply returns a unique key to fetch the data stored each time there
     * is an add request. It's not our job to ensure consistency here. We only need to make sure we
     * can store and return the same context.
     *
     * <p>Test that identical transaction IDs for the same bearer still returns different request
     * IDs.
     */
    @Test
    public void testAddRequestContext_addTwoSameRequests_returnsUniqueIds() {
        int requestId1 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        int requestId2 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);

        assertThat(requestId1).isNotEqualTo(requestId2);
    }

    /*
     * Test that adding and querying for request IDs for different bearers using the same
     * transaction IDs returns the same request context inserted for those requests
     */
    @Test
    public void testGetRequestContext_getTwoRequestsFromDifferentBearers_returnsUniqueContexts() {
        int requestId1 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        int requestId2 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_2, TRANS_ID_1, HANDLE_2);

        HandleMap.RequestContext context1 = mHandleMap.getRequestContext(SERVER_IF_1, requestId1);
        HandleMap.RequestContext context2 = mHandleMap.getRequestContext(SERVER_IF_1, requestId2);
        assertThat(context1).isNotEqualTo(context2);

        assertThat(context1).isNotNull();
        assertThat(context1.connId()).isEqualTo(CONN_ID_1);
        assertThat(context1.transactionId()).isEqualTo(TRANS_ID_1);
        assertThat(context1.handle()).isEqualTo(HANDLE_1);

        assertThat(context2).isNotNull();
        assertThat(context2.connId()).isEqualTo(CONN_ID_2);
        assertThat(context2.transactionId()).isEqualTo(TRANS_ID_1);
        assertThat(context2.handle()).isEqualTo(HANDLE_2);
    }

    /*
     * Test that you cannot get a context that has never been stored
     */
    @Test
    public void testGetRequestContext_requestIdThatDoesntExist_returnsNull() {
        HandleMap.RequestContext context =
                mHandleMap.getRequestContext(SERVER_IF_1, REQUEST_ID_INVALID);

        assertThat(context).isNull();
    }

    /*
     * Test that one server cannot get the request context of another server
     */
    @Test
    public void testGetRequestContext_requestAnotherServersContext_returnsNull() {
        int requestId = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);

        HandleMap.RequestContext context = mHandleMap.getRequestContext(SERVER_IF_2, requestId);

        assertThat(context).isNull();
    }

    /*
     * Test that removing a request context for a request ID removes the proper request
     */
    @Test
    public void testDeleteRequestContext() {
        int requestId1 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        int requestId2 = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_2, TRANS_ID_1, HANDLE_2);
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId1)).isNotNull();
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId2)).isNotNull();

        mHandleMap.deleteRequestContext(SERVER_IF_1, requestId1);
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId1)).isNull();
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId2)).isNotNull();

        mHandleMap.deleteRequestContext(SERVER_IF_1, requestId2);
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId1)).isNull();
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId2)).isNull();
    }

    /*
     * Test that removing a non existing request context doesn't impact the other contexts
     */
    @Test
    public void testDeleteRequestContext_forNonExistingRequestId_otherRequestsStillExist() {
        int requestId = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull();

        mHandleMap.deleteRequestContext(SERVER_IF_1, REQUEST_ID_INVALID);
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull();
    }

    /*
     * Test that one server cannot get the request context of another server
     */
    @Test
    public void testDeleteRequestContext_deleteAnotherServersContext_requestStillExists() {
        int requestId = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull();

        mHandleMap.deleteRequestContext(SERVER_IF_2, requestId);

        assertThat(mHandleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull();
    }

    /*
     * Test that printing a RequestContext returns a string and does not crash
     */
    @Test
    public void testPrintRequestContext() {
        int requestId = mHandleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1);
        HandleMap.RequestContext context = mHandleMap.getRequestContext(SERVER_IF_1, requestId);
        assertThat(context.toString()).isNotNull();
    }

    /*
     * Test that dumping the HandleMap returns a string and does not crash
     */
    @Test
    public void testDumpHandleMap() {
        StringBuilder sb = new StringBuilder();
        mHandleMap.dump(sb);
        assertThat(sb.toString()).isNotNull();
    }
}
