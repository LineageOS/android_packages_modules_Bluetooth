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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_AUTO
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt.GATT_FAILURE
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattService
import android.bluetooth.IBluetoothGattServerCallback
import android.content.AttributionSource
import android.os.IBinder
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class GattServerManagerTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var binder: IBinder
    @Mock private lateinit var binder2: IBinder
    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var gattServerCallback: IBluetoothGattServerCallback
    @Mock private lateinit var gattServerCallback2: IBluetoothGattServerCallback
    @Mock private lateinit var nativeInterface: GattNativeInterface
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var service: GattService
    @Mock private lateinit var metricsReporter: GattMetricsReporter

    private val device = getTestDevice(109)

    private lateinit var serverManager: GattServerManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        adapterService.mockPackageManager(context.packageManager)
        doAnswer { invocation ->
                (invocation.getArgument(0) as Runnable).run()
                null
            }
            .whenever(service)
            .doOnGattThread(any())

        doReturn(binder).whenever(gattServerCallback).asBinder()
        doReturn(binder2).whenever(gattServerCallback2).asBinder()
        doReturn(nativeInterface).whenever(service).nativeInterface
        serverManager = GattServerManager(adapterService, service, metricsReporter)
    }

    @Test
    fun onServerRegistered_appNotFound_doesNotLinkToDeath() {
        val uuid = UUID.randomUUID()
        serverManager.onServerRegisteredFromNative(GATT_SUCCESS, SERVER_IF, uuid)
        verify(gattServerCallback, never()).onServerRegistered(any())
    }

    @Test
    fun onServerRegistered_appFound_linksToDeathAndCallbacks_onBinderDiedCleanupActionExecuted() {
        val serverApp = register(TRANSPORT_LE, SERVER_IF, gattServerCallback)
        assertThat(serverApp.deathRecipient).isNull()

        onRegistered(serverApp.uuid, SERVER_IF, serverApp, gattServerCallback)
        assertThat(serverApp.deathRecipient).isNotNull()

        serverApp.deathRecipient!!.binderDied()
        verify(nativeInterface).gattServerUnregisterApp(SERVER_IF)
    }

    @Test
    fun serverConnect() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)

        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        serverManager.serverConnect(
            gattServerCallback,
            device,
            addressType,
            isDirect,
            TRANSPORT_LE,
            source,
        )
        verify(nativeInterface)
            .gattServerConnect(SERVER_IF, device, addressType, isDirect, TRANSPORT_LE)
    }

    @Test
    fun serverDisconnect_oneBearerConnected_bearerDisconnectRequested() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        serverManager.serverDisconnect(gattServerCallback, device)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID)
    }

    @Test
    fun serverDisconnect_multipleBearersConnected_allBearersDisconnected() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID_2, SERVER_IF)

        serverManager.serverDisconnect(gattServerCallback, device)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID_2)
    }

    @Test
    fun serverDisconnect_noBearersConnected_zeroUsedToDisconnectInFlightConnections() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)

        serverManager.serverDisconnect(gattServerCallback, device)
        verify(nativeInterface, never()).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, 0)
    }

    @Test
    fun serverClientConnects_noExistingBearers_stateChangedToConnected() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        verify(gattServerCallback).onServerConnectionState(eq(GATT_SUCCESS), eq(true), eq(device))
    }

    @Test
    fun serverClientConnects_bearerExistsForSameDevice_stateDoesNotChange() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)
        clearInvocations(gattServerCallback)

        // Second call should do nothing
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID_2, SERVER_IF)
        verify(gattServerCallback, never())
            .onServerConnectionState(eq(GATT_SUCCESS), any<Boolean>(), any())
    }

    @Test
    fun serverClientDisconnects_noMoreBearersExistsForDevice_stateChangedToDisconnected() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        // connected = false should remove connection
        onDisconnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)
        assertThat(serverManager.serverMap.getConnectionByApp(SERVER_IF)).isEmpty()
        verify(gattServerCallback).onServerConnectionState(eq(GATT_SUCCESS), eq(false), eq(device))
    }

    @Test
    fun serverClientDisconnects_bearerStillExistsForDevice_stateDoesNotChange() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID_2, SERVER_IF)
        clearInvocations(gattServerCallback)

        // connected = false should remove only connection for given connection id
        onDisconnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val connectionByApp = serverManager.serverMap.getConnectionByApp(SERVER_IF)
        assertThat(connectionByApp.firstOrNull { it.connId == SERVER_CONN_ID }).isNull()
        assertThat(connectionByApp.firstOrNull { it.connId == SERVER_CONN_ID_2 }).isNotNull()
        verify(gattServerCallback, never())
            .onServerConnectionState(any<Int>(), any<Boolean>(), any())
    }

    @Test
    fun serverServiceAdded_forRegisteredApp_serviceAdded() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, listOf(service))
        verify(gattServerCallback).onServiceAdded(eq(GATT_SUCCESS), any<BluetoothGattService>())
    }

    @Test
    fun serverServiceAdded_forUnregisteredApp_serviceNotAdded() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = false)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF, isRegistered = false)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, listOf(service))
        verify(gattServerCallback, never())
            .onServiceAdded(eq(GATT_SUCCESS), any<BluetoothGattService>())
    }

    @Test
    fun serverServiceAdded_statusNotSuccess_serviceNotAdded() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        serverManager.onServiceAddedFromNative(GATT_FAILURE, SERVER_IF, listOf(service))
        verify(gattServerCallback, never()).onServiceAdded(any<Int>(), any<BluetoothGattService>())
    }

    @Test
    fun serverClearServices_withEmptyServiceSetForApp_noServicesDeleted() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)

        serverManager.clearServices(gattServerCallback)
        verify(nativeInterface, never()).gattServerDeleteService(eq(SERVER_IF), any<Int>())
    }

    @Test
    fun serverSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        serverManager.serverSetPreferredPhy(gattServerCallback, device, txPhy, rxPhy, phyOptions)
        verify(nativeInterface)
            .gattServerSetPreferredPhy(SERVER_IF, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun serverReadPhy() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        serverManager.serverReadPhy(gattServerCallback, device)
        verify(nativeInterface).gattServerReadPhy(SERVER_IF, device)
    }

    @Test
    fun serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val serviceList = listOf(service, characteristic)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, serviceList)
        serverManager.onServerReadCharacteristicFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            handle = 2,
            offset = 0,
            isLong = false,
        )

        // Transaction ID is mapped to a "request ID" which is an auto-increment starting at 0
        verify(gattServerCallback)
            .onCharacteristicReadRequest(
                eq(device),
                eq(0), /* Request ID */
                eq(0), /* offset */
                eq(false), /* isLong */
                eq(2), /* handle */
            )
    }

    @Test
    fun serverReadDescriptor_AppAndDescriptorExist_requestSentToApp() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val descriptor = createDescriptor(SERVER_TEST_DESC_UUID, 3, 0)
        val serviceList = listOf(service, characteristic, descriptor)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, serviceList)
        serverManager.onServerReadDescriptorFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            handle = 2,
            offset = 0,
            isLong = false,
        )

        // Transaction ID is mapped to a "request ID" which is an auto-increment starting at 0
        verify(gattServerCallback)
            .onDescriptorReadRequest(
                eq(device),
                eq(0), /* Request ID */
                eq(0), /* offset */
                eq(false), /* isLong */
                eq(2), /* handle */
            )
    }

    @Test
    fun serverWriteCharacteristic_AppAndCharacteristicExist_requestSentToApp() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val serviceList = listOf(service, characteristic)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, serviceList)
        val data = byteArrayOf(5, 6)
        serverManager.onServerWriteCharacteristicFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            handle = 2,
            offset = 0,
            length = 2,
            needRsp = false,
            isPrep = false,
            data = data,
        )

        // Transaction ID is mapped to a "request ID" which is an auto-increment starting at 0
        verify(gattServerCallback)
            .onCharacteristicWriteRequest(
                eq(device),
                eq(0), /* requestId */
                eq(0), /* offset */
                eq(2), /* length */
                eq(false), /* isPrepared */
                eq(false), /* needRsp */
                eq(2), /* handle */
                eq(data),
            )
    }

    @Test
    fun serverWriteDescriptor_AppAndDescriptorExist_requestSentToApp() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val descriptor = createDescriptor(SERVER_TEST_DESC_UUID, 3, 0)
        val serviceList = listOf(service, characteristic, descriptor)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, serviceList)
        val data = byteArrayOf(5, 6)
        serverManager.onServerWriteDescriptorFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            handle = 2,
            offset = 0,
            length = 2,
            needRsp = false,
            isPrep = false,
            data = data,
        )

        // Transaction ID is mapped to a "request ID" which is an auto-increment starting at 0
        verify(gattServerCallback)
            .onDescriptorWriteRequest(
                eq(device),
                eq(0), /* requestId */
                eq(0), /* offset */
                eq(2), /* length */
                eq(false), /* isPrepared */
                eq(false), /* needRsp */
                eq(2), /* handle */
                eq(data),
            )
    }

    @Test
    fun serverExecuteWrite_writePreparedWrite_writeSentAndAppResponds() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        serverManager.onExecuteWriteFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            1, /* write = 1, cancel = 0 */
        )

        verify(gattServerCallback)
            .onExecuteWrite(
                eq(device),
                eq(0), /* requestId */
                eq(true), /* write = true, cancel = false */
            )

        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 0,
            status = GATT_SUCCESS,
            offset = 0,
            value = null, /* Data null for a prepared write response */
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(GATT_SUCCESS),
                eq(0), /* prepared write executes don't use a handle, use 0x0 */
                eq(0), /* offset */
                eq(null),
                eq(0), /* authReq */
            )
    }

    @Test
    fun serverExecuteWrite_cancelPreparedWrite_cancelSentAndAppResponds() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        serverManager.onExecuteWriteFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            0, /* write = 1, cancel = 0 */
        )

        verify(gattServerCallback)
            .onExecuteWrite(
                eq(device),
                eq(0), /* requestId */
                eq(false), /* write = true, cancel = false */
            )

        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 0,
            status = GATT_SUCCESS,
            offset = 0,
            value = null, /* Data null for a prepared write cancel response */
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(GATT_SUCCESS),
                eq(0), /* prepared write executes don't use a handle, use 0x0 */
                eq(0), /* offset */
                eq(null),
                eq(0), /* authReq */
            )
    }

    @Test
    fun serverSendResponse_requestContextExists_responseSent() {
        // Stage valid service/characteristic and request to respond to
        serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp()

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 0,
            status = GATT_SUCCESS,
            offset = 0,
            value = data,
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(GATT_SUCCESS),
                eq(2), /* handle of characteristic, from previous test */
                eq(0), /* offset */
                eq(data),
                eq(0), /* authReq */
            )
    }

    @Test
    fun serverSendResponse_requestContextDoesNotExist_responseNotSent() {
        // Stage valid service/characteristic and request that we _could_ respond to
        serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp()

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 85, /* Intentionally wrong so it doesn't exist */
            status = GATT_SUCCESS,
            offset = 0,
            value = data,
        )

        verify(nativeInterface, never())
            .gattServerSendResponse(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<ByteArray>(),
                any<Int>(),
            )
    }

    @Test
    fun serverSendResponse_appDoesNotExist_responseNotSent() {
        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 0,
            status = GATT_SUCCESS,
            offset = 0,
            value = data,
        )

        verify(nativeInterface, never())
            .gattServerSendResponse(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<ByteArray>(),
                any<Int>(),
            )
    }

    @Test
    fun serverSendResponse_withSameTransactionIdAndDifferentBearers_responsesSent() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID_2, SERVER_IF)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic1 = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val characteristic2 = createCharacteristic(SERVER_TEST_CHAR_UUID, 3, 0, 0)
        val serviceList = listOf(service, characteristic1, characteristic2)
        serverManager.onServiceAddedFromNative(GATT_SUCCESS, SERVER_IF, serviceList)
        serverManager.onServerReadCharacteristicFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            handle = 2,
            offset = 0,
            isLong = false,
        )
        serverManager.onServerReadCharacteristicFromNative(
            device,
            SERVER_CONN_ID_2,
            SERVER_REQUEST_TRANSACTION_ID, /* Note: transaction IDs are local to the bearer */
            handle = 3,
            offset = 0,
            isLong = false,
        )

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 0, /* From bearer/request 1 */
            status = GATT_SUCCESS,
            offset = 0,
            value = data,
        )
        serverManager.sendResponse(
            gattServerCallback,
            device,
            requestId = 1, /* From bearer/request 2 */
            status = GATT_SUCCESS,
            offset = 0,
            value = data,
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(GATT_SUCCESS),
                eq(2), /* handle of characteristic, from previous test */
                eq(0), /* offset */
                eq(data),
                eq(0), /* authReq */
            )
        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID_2),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(GATT_SUCCESS),
                eq(3), /* handle of characteristic, from previous test */
                eq(0), /* offset */
                eq(data),
                eq(0), /* authReq */
            )
    }

    @Test
    fun serverSendResponse_usingRequestIdBelongingToAnotherServer_responseNotSent() {
        // Stage request for server, then register a new server
        serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp()
        register(TRANSPORT_LE, SERVER_IF_2, gattServerCallback2, onRegistered = true)

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback2,
            device,
            requestId = 0, /* Belongs to other server */
            status = GATT_SUCCESS,
            offset = 0,
            value = data,
        )

        verify(nativeInterface, never())
            .gattServerSendResponse(
                any<Int>(),
                any<Int>(),
                any<Int>(),
                eq(GATT_SUCCESS),
                any<Int>(),
                any<Int>(),
                any<ByteArray>(),
                any<Int>(),
            )
    }

    @Test
    fun serverSendNotification_oneBearerConnected_bearerNotified() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val handle = 2
        val confirm = true
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendIndication(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    fun serverSendIndication_oneBearerConnected_bearerIndicated() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)

        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    fun serverSendNotification_multipleBearersConnectedPrefLe_leTransportUsed() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID_2, SERVER_IF)

        val handle = 2
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, false, value)
        verify(nativeInterface)
            .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID_2, value)
    }

    @Test
    fun serverSendNotification_multipleBearersConnectedPrefBredr_BredrTransportUsed() {
        register(TRANSPORT_BREDR, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID_2, SERVER_IF)

        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    fun serverSendNotification_twoBearersConnectedPrefAutoBredrOldest_bredrTransportUsed() {
        register(TRANSPORT_AUTO, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID_2, SERVER_IF)

        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    fun serverSendNotification_twoBearersConnectedPrefAutoLeOldest_leTransportUsed() {
        register(TRANSPORT_AUTO, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_LE, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID_2, SERVER_IF)

        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    fun serverSendNotification_noBearersConnected_noNotificationSent() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)

        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface, never())
            .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID_2, value)
    }

    @Test
    fun serverSendNotification_noBearersThatMatchPref_notificationSentOnOldest() {
        register(TRANSPORT_LE, SERVER_IF, gattServerCallback, onRegistered = true)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID, SERVER_IF)
        onConnected(device, TRANSPORT_BREDR, SERVER_CONN_ID_2, SERVER_IF)

        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    private fun register(
        transport: Int,
        serverIf: Int,
        callback: IBluetoothGattServerCallback,
        onRegistered: Boolean = false,
    ): ContextApp<IBluetoothGattServerCallback> {
        serverManager.registerServer(callback, true, transport, source)
        val serverApp = serverManager.serverMap.getByCallbackId(callback)
        assertThat(serverApp).isNotNull()
        assertThat(serverApp!!.id).isEqualTo(0)
        assertThat(serverApp.callback).isEqualTo(callback)

        if (onRegistered) onRegistered(serverApp.uuid, serverIf, serverApp, callback)

        return serverApp
    }

    private fun onRegistered(
        uuid: UUID,
        serverIf: Int,
        serverApp: ContextApp<IBluetoothGattServerCallback>,
        callback: IBluetoothGattServerCallback,
    ) {
        serverManager.onServerRegisteredFromNative(GATT_SUCCESS, serverIf, uuid)
        assertThat(serverApp.id).isEqualTo(serverIf)
        verify(callback).onServerRegistered(GATT_SUCCESS)
    }

    private fun onConnected(
        device: BluetoothDevice,
        transport: Int,
        connId: Int,
        serverIf: Int,
        isRegistered: Boolean = true,
    ) {
        serverManager.onClientConnectedFromNative(device, transport, true, connId, serverIf)
        if (isRegistered) {
            assertThat(serverManager.serverMap.getConnectionByApp(serverIf)).isNotEmpty()
        } else {
            assertThat(serverManager.serverMap.getConnectionByApp(serverIf)).isEmpty()
        }
    }

    private fun onDisconnected(
        device: BluetoothDevice,
        transport: Int,
        connId: Int,
        serverIf: Int,
    ) = serverManager.onClientConnectedFromNative(device, transport, false, connId, serverIf)

    private fun createPrimaryService(uuid: UUID, handle: Int): GattDbElement {
        val service = GattDbElement.createPrimaryService(uuid)
        service.attributeHandle = handle
        return service
    }

    private fun createCharacteristic(
        uuid: UUID,
        handle: Int,
        properties: Int,
        perms: Int,
    ): GattDbElement {
        val characteristic = GattDbElement.createCharacteristic(uuid, properties, perms)
        characteristic.attributeHandle = handle
        return characteristic
    }

    private fun createDescriptor(uuid: UUID, handle: Int, perms: Int): GattDbElement {
        val descriptor = GattDbElement.createDescriptor(uuid, perms)
        descriptor.attributeHandle = handle
        return descriptor
    }

    companion object {
        private const val SERVER_IF = 34
        private const val SERVER_IF_2 = 35
        private const val SERVER_CONN_ID = 84
        private const val SERVER_CONN_ID_2 = 85
        private val SERVER_TEST_SERVICE_UUID =
            UUID.fromString("00001111-2222-3333-4444-555566667777")
        private val SERVER_TEST_CHAR_UUID = UUID.fromString("00002222-3333-4444-5555-666677778888")
        private val SERVER_TEST_DESC_UUID = UUID.fromString("00003333-4444-5555-6666-777788889999")
        private const val SERVER_REQUEST_TRANSACTION_ID = 75
    }
}
