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

package com.android.bluetooth.gatt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandleMapTest {
    @get:Rule val expect = Expect.create()

    private val handleMap = HandleMap()

    /** Verifies that adding a service creates a correct Entry object. */
    @Test
    fun testAddService_createsCorrectEntry() {
        handleMap.addService(
            SERVER_IF_1,
            SERVICE_HANDLE,
            FAKE_UUID,
            SERVICE_TYPE,
            INSTANCE,
            ADVERTISE_PREFERRED,
        )

        assertThat(handleMap.entries).hasSize(1)
        val entry = handleMap.entries[0]

        expect.that(entry.serverIf).isEqualTo(SERVER_IF_1)
        expect.that(entry.type).isEqualTo(HandleMap.Type.SERVICE)
        expect.that(entry.handle).isEqualTo(SERVICE_HANDLE)
        expect.that(entry.uuid).isEqualTo(FAKE_UUID)
        expect.that(entry.instance).isEqualTo(INSTANCE)
        expect.that(entry.serviceType).isEqualTo(SERVICE_TYPE)
        expect.that(entry.serviceHandle).isEqualTo(0)
        expect.that(entry.charHandle).isEqualTo(0)
        expect.that(entry.advertisePreferred).isEqualTo(ADVERTISE_PREFERRED)
    }

    /** Verifies that adding a characteristic creates a correct Entry object. */
    @Test
    fun testAddCharacteristic_createsCorrectEntry() {
        handleMap.addCharacteristic(SERVER_IF_1, CHARACTERISTIC_HANDLE, FAKE_UUID, SERVICE_HANDLE)

        assertThat(handleMap.entries).hasSize(1)
        val entry = handleMap.entries[0]

        assertThat(entry.serverIf).isEqualTo(SERVER_IF_1)
        assertThat(entry.type).isEqualTo(HandleMap.Type.CHARACTERISTIC)
        assertThat(entry.handle).isEqualTo(CHARACTERISTIC_HANDLE)
        assertThat(entry.uuid).isEqualTo(FAKE_UUID)
        assertThat(entry.serviceHandle).isEqualTo(SERVICE_HANDLE)
        // Check default values for fields not set by this constructor
        assertThat(entry.instance).isEqualTo(0)
        assertThat(entry.serviceType).isEqualTo(0)
        assertThat(entry.charHandle).isEqualTo(0)
        assertThat(entry.advertisePreferred).isFalse()
    }

    /** Verifies that adding a descriptor creates a correct Entry object. */
    @Test
    fun testAddDescriptor_createsCorrectEntry() {
        // addDescriptor uses the handle of the last added characteristic
        handleMap.addCharacteristic(SERVER_IF_1, CHARACTERISTIC_HANDLE, FAKE_UUID, SERVICE_HANDLE)
        handleMap.addDescriptor(SERVER_IF_1, DESCRIPTOR_HANDLE, FAKE_UUID, SERVICE_HANDLE)

        assertThat(handleMap.entries).hasSize(2)
        // The descriptor is the second entry added
        val entry = handleMap.entries[1]

        assertThat(entry.serverIf).isEqualTo(SERVER_IF_1)
        assertThat(entry.type).isEqualTo(HandleMap.Type.DESCRIPTOR)
        assertThat(entry.handle).isEqualTo(DESCRIPTOR_HANDLE)
        assertThat(entry.uuid).isEqualTo(FAKE_UUID)
        assertThat(entry.serviceHandle).isEqualTo(SERVICE_HANDLE)
        assertThat(entry.charHandle).isEqualTo(CHARACTERISTIC_HANDLE)
        // Check default values for fields not set by this constructor
        assertThat(entry.instance).isEqualTo(0)
        assertThat(entry.serviceType).isEqualTo(0)
        assertThat(entry.advertisePreferred).isFalse()
    }

    /*
     * Requests from different bearers can that have the same transaction IDs and target the same
     * handles.
     *
     * <p>Test that adding a request ID for two bearers using the same transaction ID returns two
     * unique request IDs
     */
    @Test
    fun testAddRequestContext_addTwoRequestsFromDifferentBearers_returnsUniqueIds() {
        val requestId1 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val requestId2 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_2, TRANS_ID_1, HANDLE_2)

        assertThat(requestId1).isNotEqualTo(requestId2)
    }

    /*
     * Requests from the same bearers must have a different transaction ID but can target the same
     * handles.
     *
     * <p>Test that adding a request IDs for a bearer using different transaction IDs returns two
     * unique request IDs
     */
    @Test
    fun testAddRequestContext_addTwoRequestsFromSameBearer_returnsUniqueIds() {
        val requestId1 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val requestId2 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_2, HANDLE_1)

        assertThat(requestId1).isNotEqualTo(requestId2)
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
    fun testAddRequestContext_addTwoSameRequests_returnsUniqueIds() {
        val requestId1 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val requestId2 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)

        assertThat(requestId1).isNotEqualTo(requestId2)
    }

    @Test
    fun testGetRequestContext_getTwoRequestsFromDifferentBearers_returnsUniqueContexts() {
        val requestId1 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val requestId2 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_2, TRANS_ID_1, HANDLE_2)

        val context1 = handleMap.getRequestContext(SERVER_IF_1, requestId1)
        val context2 = handleMap.getRequestContext(SERVER_IF_1, requestId2)
        assertThat(context1).isNotEqualTo(context2)

        assertThat(context1).isNotNull()
        assertThat(context1!!.connId).isEqualTo(CONN_ID_1)
        assertThat(context1.transactionId).isEqualTo(TRANS_ID_1)
        assertThat(context1.handle).isEqualTo(HANDLE_1)

        assertThat(context2).isNotNull()
        assertThat(context2!!.connId).isEqualTo(CONN_ID_2)
        assertThat(context2.transactionId).isEqualTo(TRANS_ID_1)
        assertThat(context2.handle).isEqualTo(HANDLE_2)
    }

    @Test
    fun testGetRequestContext_requestIdThatDoesntExist_returnsNull() {
        val context = handleMap.getRequestContext(SERVER_IF_1, REQUEST_ID_INVALID)
        assertThat(context).isNull()
    }

    @Test
    fun testGetRequestContext_requestAnotherServersContext_returnsNull() {
        val requestId = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val context = handleMap.getRequestContext(SERVER_IF_2, requestId)

        assertThat(context).isNull()
    }

    @Test
    fun testDeleteRequestContext() {
        val requestId1 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val requestId2 = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_2, TRANS_ID_1, HANDLE_2)
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId1)).isNotNull()
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId2)).isNotNull()

        handleMap.deleteRequestContext(SERVER_IF_1, requestId1)
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId1)).isNull()
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId2)).isNotNull()

        handleMap.deleteRequestContext(SERVER_IF_1, requestId2)
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId1)).isNull()
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId2)).isNull()
    }

    @Test
    fun testDeleteRequestContext_forNonExistingRequestId_otherRequestsStillExist() {
        val requestId = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull()

        handleMap.deleteRequestContext(SERVER_IF_1, REQUEST_ID_INVALID)
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull()
    }

    @Test
    fun testDeleteRequestContext_deleteAnotherServersContext_requestStillExists() {
        val requestId = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull()

        handleMap.deleteRequestContext(SERVER_IF_2, requestId)

        assertThat(handleMap.getRequestContext(SERVER_IF_1, requestId)).isNotNull()
    }

    @Test
    fun testPrintRequestContext() {
        val requestId = handleMap.addRequestContext(SERVER_IF_1, CONN_ID_1, TRANS_ID_1, HANDLE_1)
        val context = handleMap.getRequestContext(SERVER_IF_1, requestId)
        assertThat(context.toString()).isNotNull()
    }

    @Test
    fun testDumpHandleMap() {
        val sb = StringBuilder()
        handleMap.dump(sb)
        assertThat(sb.toString()).isNotNull()
    }

    companion object {
        private const val SERVER_IF_1 = 84
        private const val SERVER_IF_2 = 85

        private const val CONN_ID_1 = 1
        private const val TRANS_ID_1 = 1
        private const val HANDLE_1 = 1

        private const val CONN_ID_2 = 2
        private const val TRANS_ID_2 = 2
        private const val HANDLE_2 = 2

        private const val REQUEST_ID_INVALID = -1

        private val FAKE_UUID = UUID.randomUUID()
        private const val SERVICE_HANDLE = 10
        private const val SERVICE_TYPE = 0
        private const val INSTANCE = 1
        private const val ADVERTISE_PREFERRED = true
        private const val CHARACTERISTIC_HANDLE = 11
        private const val DESCRIPTOR_HANDLE = 12
    }
}
