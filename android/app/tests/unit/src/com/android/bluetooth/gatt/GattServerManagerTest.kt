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
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.IBluetoothGattServerCallback
import android.content.AttributionSource
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.ActionOnDeathRecipient
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class GattServerManagerTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var gattServerCallback: IBluetoothGattServerCallback
    @Mock private lateinit var gattServerCallback2: IBluetoothGattServerCallback
    @Mock private lateinit var serverMap: ContextMap<IBluetoothGattServerCallback>
    @Mock private lateinit var nativeInterface: GattNativeInterface
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var service: GattService
    @Mock private lateinit var metricsReporter: GattMetricsReporter

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val device = getTestDevice(109)
    private val serverConnections = mutableListOf<ContextMap.Connection>()

    private lateinit var serverManager: GattServerManager

    @Before
    fun setUp() {
        doAnswer { invocation ->
                (invocation.getArgument(0) as Runnable).run()
                null
            }
            .whenever(service)
            .doOnGattThread(any())

        doAnswer { invocation ->
                val arguments = invocation.arguments
                val id = arguments[0] as Int
                val connId = arguments[1] as Int
                val transport = arguments[2] as Int
                val device = arguments[3] as BluetoothDevice
                serverConnections.add(ContextMap.Connection(connId, device, transport, id))
            }
            .whenever(serverMap)
            .addConnection(any<Int>(), any<Int>(), any<Int>(), any<BluetoothDevice>())

        doAnswer { invocation ->
                val arguments = invocation.arguments
                val id = arguments[0] as Int
                val connId = arguments[1] as Int
                serverConnections.removeAll { conn -> conn.appId == id && conn.connId == connId }
            }
            .whenever(serverMap)
            .removeConnection(any<Int>(), any<Int>())

        doAnswer { invocation ->
                val currentConnections = mutableListOf<ContextMap.Connection>()
                val arguments = invocation.arguments
                val id = arguments[0] as Int
                val device = arguments[1] as BluetoothDevice
                for (connection in serverConnections) {
                    if (connection.device == device && connection.appId == id) {
                        currentConnections.add(connection)
                    }
                }
                currentConnections
            }
            .whenever(serverMap)
            .getConnectionsByDevice(any<Int>(), any<BluetoothDevice>())

        doReturn(context.packageManager).whenever(adapterService).packageManager
        doReturn(nativeInterface).whenever(service).nativeInterface
        serverManager = GattServerManager(adapterService, service, serverMap, metricsReporter)
    }

    @Test
    fun onServerRegistered_appNotFound_doesNotLinkToDeath() {
        val uuid = UUID.randomUUID()
        whenever(serverMap.getByUuid(uuid)).thenReturn(null)

        serverManager.onServerRegisteredFromNative(BluetoothGatt.GATT_SUCCESS, SERVER_IF, uuid)
        verify(gattServerCallback, never()).onServerRegistered(any())
    }

    @Test
    fun onServerRegistered_appFound_linksToDeathAndCallbacks() {
        val uuid = UUID.randomUUID()
        val serverApp = mock<ContextApp<IBluetoothGattServerCallback>>()
        whenever(serverApp.callback).thenReturn(gattServerCallback)
        whenever(serverMap.getByUuid(uuid)).thenReturn(serverApp)

        serverManager.onServerRegisteredFromNative(BluetoothGatt.GATT_SUCCESS, SERVER_IF, uuid)
        verify(serverApp).id = SERVER_IF
        verify(serverApp).linkToDeath(any<ActionOnDeathRecipient>())
        verify(gattServerCallback).onServerRegistered(BluetoothGatt.GATT_SUCCESS)
    }

    @Test
    fun onServerRegistered_appDied_cleanupActionExecuted() {
        val uuid = UUID.randomUUID()
        val serverApp = mock<ContextApp<IBluetoothGattServerCallback>>()
        whenever(serverApp.callback).thenReturn(gattServerCallback)
        whenever(serverApp.id).thenReturn(SERVER_IF)
        whenever(serverMap.getByUuid(uuid)).thenReturn(serverApp)
        whenever(serverMap.getByCallbackId(any())).thenReturn(serverApp)

        serverManager.onServerRegisteredFromNative(BluetoothGatt.GATT_SUCCESS, SERVER_IF, uuid)

        val captor = argumentCaptor<ActionOnDeathRecipient>()
        verify(serverApp).linkToDeath(captor.capture())

        captor.firstValue.binderDied()

        // Check that unregister logic flowed through to the native interface
        verify(nativeInterface).gattServerUnregisterApp(SERVER_IF)
    }

    @Test
    fun serverConnect() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = 2

        addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        serverManager.serverConnect(
            gattServerCallback,
            device,
            addressType,
            isDirect,
            transport,
            source,
        )
        verify(nativeInterface)
            .gattServerConnect(SERVER_IF, device, addressType, isDirect, transport)
    }

    @Test
    fun serverDisconnect_oneBearerConnected_bearerDisconnectRequested() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.serverDisconnect(gattServerCallback, device)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    fun serverDisconnect_multipleBearersConnected_allBearersDisconnected() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID_2, TRANSPORT_LE, device)

        serverManager.serverDisconnect(gattServerCallback, device)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID_2)
    }

    @Test
    fun serverDisconnect_noBearersConnected_zeroUsedToDisconnectInFlightConnections() {
        addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)

        serverManager.serverDisconnect(gattServerCallback, device)
        verify(nativeInterface, never()).gattServerDisconnect(SERVER_IF, device, SERVER_CONN_ID)
        verify(nativeInterface).gattServerDisconnect(SERVER_IF, device, 0)
    }

    @Test
    @Throws(Exception::class)
    fun serverClientConnects_noExistingBearers_stateChangedToConnected() {
        addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)

        serverManager.onClientConnectedFromNative(
            device,
            BluetoothDevice.TRANSPORT_BREDR,
            true,
            SERVER_CONN_ID_2,
            SERVER_IF,
        )
        verify(serverMap)
            .addConnection(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID_2),
                eq(BluetoothDevice.TRANSPORT_BREDR),
                eq(device),
            )
        verify(gattServerCallback).onServerConnectionState(eq(0), eq(true), eq(device))
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    @Throws(Exception::class)
    fun serverClientConnects_bearerExistsForSameDevice_stateDoesNotChange() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.onClientConnectedFromNative(
            device,
            TRANSPORT_LE,
            true,
            SERVER_CONN_ID_2,
            SERVER_IF,
        )
        verify(serverMap)
            .addConnection(eq(SERVER_IF), eq(SERVER_CONN_ID_2), eq(TRANSPORT_LE), eq(device))
        verify(gattServerCallback, never())
            .onServerConnectionState(any<Int>(), any<Boolean>(), any())
    }

    @Test
    @Throws(Exception::class)
    fun serverClientDisconnects_noMoreBearersExistsForDevice_stateChangedToDisconnected() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.onClientConnectedFromNative(
            device,
            TRANSPORT_LE,
            false,
            SERVER_CONN_ID,
            SERVER_IF,
        )
        verify(serverMap).removeConnection(eq(SERVER_IF), eq(SERVER_CONN_ID))
        assertThat(serverConnections).isEmpty()
        verify(gattServerCallback).onServerConnectionState(eq(0), eq(false), eq(device))
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    @Throws(Exception::class)
    fun serverClientDisconnects_bearerStillExistsForDevice_stateDoesNotChange() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID_2, TRANSPORT_LE, device)

        serverManager.onClientConnectedFromNative(
            device,
            TRANSPORT_LE,
            false,
            SERVER_CONN_ID,
            SERVER_IF,
        )
        verify(serverMap).removeConnection(eq(SERVER_IF), eq(SERVER_CONN_ID))
        verify(serverMap, never()).removeConnection(eq(SERVER_IF), eq(SERVER_CONN_ID_2))
        verify(gattServerCallback, never())
            .onServerConnectionState(any<Int>(), any<Boolean>(), any())
    }

    @Test
    @Throws(Exception::class)
    fun serverServiceAdded_forRegisteredApp_serviceAdded() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, listOf(service))
        verify(gattServerCallback).onServiceAdded(eq(0), any<BluetoothGattService>())
    }

    @Test
    @Throws(Exception::class)
    fun serverServiceAdded_forUnregisteredApp_serviceNotAdded() {
        addClientConnectionRecordForUnregisteredApp(SERVER_IF, SERVER_CONN_ID, TRANSPORT_LE, device)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, listOf(service))
        verify(gattServerCallback, never()).onServiceAdded(any<Int>(), any<BluetoothGattService>())
    }

    @Test
    @Throws(Exception::class)
    fun serverServiceAdded_statusNotSuccess_serviceNotAdded() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        serverManager.onServiceAddedFromNative(1, SERVER_IF, listOf(service))
        verify(gattServerCallback, never()).onServiceAdded(any<Int>(), any<BluetoothGattService>())
    }

    @Test
    fun serverClearServices_withEmptyServiceSetForApp_noServicesDeleted() {
        addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)

        serverManager.clearServices(gattServerCallback)
        verify(nativeInterface, never()).gattServerDeleteService(eq(SERVER_IF), any<Int>())
    }

    @Test
    @Throws(Exception::class)
    fun serverSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3

        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.serverSetPreferredPhy(gattServerCallback, device, txPhy, rxPhy, phyOptions)
        verify(nativeInterface)
            .gattServerSetPreferredPhy(SERVER_IF, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun serverReadPhy() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.serverReadPhy(gattServerCallback, device)
        verify(nativeInterface).gattServerReadPhy(SERVER_IF, device)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val serviceList = listOf(service, characteristic)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, serviceList)
        serverManager.onServerReadCharacteristicFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            2, /* handle */
            0, /* offset */
            false, /* isLong */
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
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverReadDescriptor_AppAndDescriptorExist_requestSentToApp() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val descriptor = createDescriptor(SERVER_TEST_DESC_UUID, 3, 0)
        val serviceList = listOf(service, characteristic, descriptor)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, serviceList)
        serverManager.onServerReadDescriptorFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            2, /* handle */
            0, /* offset */
            false, /* isLong */
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
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverWriteCharacteristic_AppAndCharacteristicExist_requestSentToApp() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val serviceList = listOf(service, characteristic)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, serviceList)
        val data = byteArrayOf(5, 6)
        serverManager.onServerWriteCharacteristicFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            2, /* handle */
            0, /* offset */
            2, /* length */
            false, /* needRsp */
            false, /* isPrepared */
            data,
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
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverWriteDescriptor_AppAndDescriptorExist_requestSentToApp() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val descriptor = createDescriptor(SERVER_TEST_DESC_UUID, 3, 0)
        val serviceList = listOf(service, characteristic, descriptor)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, serviceList)
        val data = byteArrayOf(5, 6)
        serverManager.onServerWriteDescriptorFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            2, /* handle */
            0, /* offset */
            2, /* length */
            false, /* needRsp */
            false, /* isPrepared */
            data,
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
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverExecuteWrite_writePreparedWrite_writeSentAndAppResponds() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

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
            0, /* request ID */
            0, /* status */
            0, /* offset */
            null, /* Data null for a prepared write response */
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(0), /* status */
                eq(0), /* prepared write executes don't use a handle, use 0x0 */
                eq(0), /* offset */
                eq(null),
                eq(0), /* authReq */
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverExecuteWrite_cancelPreparedWrite_cancelSentAndAppResponds() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

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
            0, /* request ID */
            0, /* status */
            0, /* offset */
            null, /* Data null for a prepared write cancel response */
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(0), /* status */
                eq(0), /* prepared write executes don't use a handle, use 0x0 */
                eq(0), /* offset */
                eq(null),
                eq(0), /* authReq */
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverSendResponse_requestContextExists_responseSent() {
        // Stage valid service/characteristic and request to respond to
        serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp()

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            0, /* request ID */
            0, /* status */
            0, /* offset */
            data,
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(0), /* status */
                eq(2), /* handle of characteristic, from previous test */
                eq(0), /* offset */
                eq(data),
                eq(0), /* authReq */
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverSendResponse_requestContextDoesNotExist_responseNotSent() {
        // Stage valid service/characteristic and request that we _could_ respond to
        serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp()

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            85, /* request ID, intentionally wrong so it doesn't exist */
            0, /* status */
            0, /* offset */
            data,
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
            0, /* request ID */
            0, /* status */
            0, /* offset */
            data,
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
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    fun serverSendResponse_withSameTransactionIdAndDifferentBearers_responsesSent() {
        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID_2,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )
        val service = createPrimaryService(SERVER_TEST_SERVICE_UUID, 1)
        val characteristic1 = createCharacteristic(SERVER_TEST_CHAR_UUID, 2, 0, 0)
        val characteristic2 = createCharacteristic(SERVER_TEST_CHAR_UUID, 3, 0, 0)
        val serviceList = listOf(service, characteristic1, characteristic2)
        serverManager.onServiceAddedFromNative(0, SERVER_IF, serviceList)
        serverManager.onServerReadCharacteristicFromNative(
            device,
            SERVER_CONN_ID,
            SERVER_REQUEST_TRANSACTION_ID,
            2, /* handle */
            0, /* offset */
            false, /* isLong */
        )
        serverManager.onServerReadCharacteristicFromNative(
            device,
            SERVER_CONN_ID_2,
            SERVER_REQUEST_TRANSACTION_ID, /* Note: transaction IDs are local to the bearer */
            3, /* handle */
            0, /* offset */
            false, /* isLong */
        )

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback,
            device,
            0, /* request ID, from bearer/request 1 */
            0, /* offset */
            0, /* status */
            data,
        )
        serverManager.sendResponse(
            gattServerCallback,
            device,
            1, /* request ID, from bearer/request 2 */
            0, /* offset */
            0, /* status */
            data,
        )

        verify(nativeInterface)
            .gattServerSendResponse(
                eq(SERVER_IF),
                eq(SERVER_CONN_ID),
                eq(SERVER_REQUEST_TRANSACTION_ID),
                eq(0), /* status */
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
                eq(0), /* status */
                eq(3), /* handle of characteristic, from previous test */
                eq(0), /* offset */
                eq(data),
                eq(0), /* authReq */
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_TRANSACTIONS)
    @Throws(Exception::class)
    fun serverSendResponse_usingRequestIdBelongingToAnotherServer_responseNotSent() {
        // Stage request for server, then register a new server
        serverReadCharacteristic_AppAndCharacteristicExist_requestSentToApp()
        addServerAppRecord(SERVER_IF_2, TRANSPORT_LE, gattServerCallback2)

        val data = byteArrayOf(5, 6)
        serverManager.sendResponse(
            gattServerCallback2,
            device,
            0, /* request ID belongs to other server */
            0, /* offset */
            0, /* status */
            data,
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
    @Throws(Exception::class)
    fun serverSendNotification_oneBearerConnected_bearerNotified() {
        val handle = 2
        val confirm = true
        val value = byteArrayOf(5, 6)

        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendIndication(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    @Throws(Exception::class)
    fun serverSendIndication_oneBearerConnected_bearerIndicated() {
        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)

        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    fun serverSendNotification_multipleBearersConnectedPrefLe_leTransportUsed() {
        val handle = 2
        val value = byteArrayOf(5, 6)

        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )
        addClientConnectionRecord(serverApp, SERVER_CONN_ID_2, TRANSPORT_LE, device)

        serverManager.sendNotification(gattServerCallback, device, handle, false, value)
        verify(nativeInterface)
            .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID_2, value)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    fun serverSendNotification_multipleBearersConnectedPrefBredr_BredrTransportUsed() {
        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)

        val serverApp =
            addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_BREDR, gattServerCallback)
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )
        addClientConnectionRecord(serverApp, SERVER_CONN_ID_2, TRANSPORT_LE, device)

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    fun serverSendNotification_twoBearersConnectedPrefAutoBredrOldest_bredrTransportUsed() {
        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)

        val serverApp =
            addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_AUTO, gattServerCallback)
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )
        addClientConnectionRecord(serverApp, SERVER_CONN_ID_2, TRANSPORT_LE, device)

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    fun serverSendNotification_twoBearersConnectedPrefAutoLeOldest_leTransportUsed() {
        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)

        val serverApp =
            addServerAppRecord(SERVER_IF, BluetoothDevice.TRANSPORT_AUTO, gattServerCallback)
        addClientConnectionRecord(serverApp, SERVER_CONN_ID, TRANSPORT_LE, device)
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID_2,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    @Test
    fun serverSendNotification_noBearersConnected_noNotificationSent() {
        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)
        addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface, never())
            .gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID_2, value)
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_MULTI_BEARER_CONNECTIONS)
    fun serverSendNotification_noBearersThatMatchPref_notificationSentOnOldest() {
        val handle = 2
        val confirm = false
        val value = byteArrayOf(5, 6)

        val serverApp = addServerAppRecord(SERVER_IF, TRANSPORT_LE, gattServerCallback)
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )
        addClientConnectionRecord(
            serverApp,
            SERVER_CONN_ID_2,
            BluetoothDevice.TRANSPORT_BREDR,
            device,
        )

        serverManager.sendNotification(gattServerCallback, device, handle, confirm, value)
        verify(nativeInterface).gattServerSendNotification(SERVER_IF, handle, SERVER_CONN_ID, value)
    }

    private fun addServerAppRecord(
        serverIf: Int,
        transport: Int,
        cb: IBluetoothGattServerCallback,
    ): ContextApp<IBluetoothGattServerCallback> {
        val serverApp = mock<ContextApp<IBluetoothGattServerCallback>>()
        doReturn(serverIf).whenever(serverApp).id
        doReturn(transport).whenever(serverApp).transport
        doReturn(cb).whenever(serverApp).callback
        doReturn(serverApp).whenever(serverMap).getByCallbackId(gattServerCallback)
        doReturn(serverApp).whenever(serverMap).getById(serverIf)
        return serverApp
    }

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

    private fun addClientConnectionRecordForUnregisteredApp(
        serverIf: Int,
        connId: Int,
        transport: Int,
        device: BluetoothDevice,
    ) {
        val conn = ContextMap.Connection(connId, device, transport, serverIf)
        serverConnections.add(conn)
    }

    private fun addClientConnectionRecord(
        serverApp: ContextApp<IBluetoothGattServerCallback>,
        connId: Int,
        transport: Int,
        device: BluetoothDevice,
    ) {
        val conn = ContextMap.Connection(connId, device, transport, serverApp.id)
        serverConnections.add(conn)
        doReturn(serverApp).whenever(serverMap).getByConnId(eq(connId))
    }

    companion object {
        private const val CLIENT_IF = 12
        private const val CLIENT_CONN_ID = 42
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
