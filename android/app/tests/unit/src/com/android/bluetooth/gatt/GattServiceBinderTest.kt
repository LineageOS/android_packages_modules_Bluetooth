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
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.IBluetoothGattCallback
import android.bluetooth.IBluetoothGattServerCallback
import android.content.AttributionSource
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import java.util.UUID
import java.util.function.Supplier
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [GattServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class GattServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var gattServerCallback: IBluetoothGattServerCallback
    @Mock private lateinit var gattCallback: IBluetoothGattCallback
    @Mock private lateinit var service: GattService
    @Mock private lateinit var serverManager: GattServerManager

    private val device = getTestDevice(109)

    private lateinit var binder: GattServiceBinder

    @Before
    @Throws(Exception::class)
    fun setUp() {
        doAnswer { invocation ->
                (invocation.getArgument(0) as Runnable).run()
                null
            }
            .whenever(service)
            .doOnGattThread(any())
        doAnswer { invocation ->
                val supplier = invocation.getArgument<Supplier<*>>(0)
                supplier.get()
            }
            .whenever(service)
            .fetchOnGattThread<Any>(any(), any())
        doReturn(true).whenever(service).isAvailable
        doReturn(serverManager).whenever(service).serverManager
        binder = GattServiceBinder(service)
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(BluetoothProfile.STATE_CONNECTED)

        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun registerClient() {
        val uuid = UUID.randomUUID()
        val eattSupport = true
        val transport = BluetoothDevice.TRANSPORT_LE

        binder.registerClient(ParcelUuid(uuid), gattCallback, eattSupport, transport, source)
        verify(service).registerClient(uuid, gattCallback, eattSupport, transport, source)
    }

    @Test
    fun unregisterClient() {
        binder.unregisterClient(gattCallback, source)
        verify(service)
            .unregisterClient(
                gattCallback,
                source,
                ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT,
            )
    }

    @Test
    fun clientConnect() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = 2
        val opportunistic = true

        binder.clientConnect(
            gattCallback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            source,
        )
        verify(service)
            .clientConnect(
                gattCallback,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                source,
            )
    }

    @Test
    fun clientDisconnect() {
        binder.clientDisconnect(gattCallback, device, source)
        verify(service).clientDisconnect(gattCallback, device, source)
    }

    @Test
    fun clientSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3

        binder.clientSetPreferredPhy(gattCallback, device, txPhy, rxPhy, phyOptions, source)
        verify(service).clientSetPreferredPhy(gattCallback, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun clientReadPhy() {
        binder.clientReadPhy(gattCallback, device, source)
        verify(service).clientReadPhy(gattCallback, device)
    }

    @Test
    fun refreshDevice() {
        binder.refreshDevice(gattCallback, device, source)
        verify(service).refreshDevice(gattCallback, device)
    }

    @Test
    fun discoverServices() {
        binder.discoverServices(gattCallback, device, source)
        verify(service).discoverServices(gattCallback, device)
    }

    @Test
    fun discoverServiceByUuid() {
        val uuid = UUID.randomUUID()

        binder.discoverServiceByUuid(gattCallback, device, ParcelUuid(uuid), source)
        verify(service).discoverServiceByUuid(gattCallback, device, uuid)
    }

    @Test
    fun readCharacteristic() {
        val handle = 2
        val authReq = 3

        binder.readCharacteristic(gattCallback, device, handle, authReq, source)
        verify(service).readCharacteristic(gattCallback, device, handle, authReq)
    }

    @Test
    fun readUsingCharacteristicUuid() {
        val uuid = UUID.randomUUID()
        val startHandle = 2
        val endHandle = 3
        val authReq = 4

        binder.readUsingCharacteristicUuid(
            gattCallback,
            device,
            ParcelUuid(uuid),
            startHandle,
            endHandle,
            authReq,
            source,
        )
        verify(service)
            .readUsingCharacteristicUuid(
                gattCallback,
                device,
                uuid,
                startHandle,
                endHandle,
                authReq,
            )
    }

    @Test
    fun writeCharacteristic() {
        val handle = 2
        val writeType = 3
        val authReq = 4
        val value = byteArrayOf(5, 6)

        binder.writeCharacteristic(gattCallback, device, handle, writeType, authReq, value, source)
        verify(service).writeCharacteristic(gattCallback, device, handle, writeType, authReq, value)
    }

    @Test
    fun readDescriptor() {
        val handle = 2
        val authReq = 3

        binder.readDescriptor(gattCallback, device, handle, authReq, source)
        verify(service).readDescriptor(gattCallback, device, handle, authReq)
    }

    @Test
    fun writeDescriptor() {
        val handle = 2
        val authReq = 3
        val value = byteArrayOf(4, 5)

        binder.writeDescriptor(gattCallback, device, handle, authReq, value, source)
        verify(service).writeDescriptor(gattCallback, device, handle, authReq, value)
    }

    @Test
    fun beginReliableWrite() {
        binder.beginReliableWrite(device, source)
        verify(service).beginReliableWrite(device)
    }

    @Test
    fun endReliableWrite() {
        val execute = true

        binder.endReliableWrite(gattCallback, device, execute, source)
        verify(service).endReliableWrite(gattCallback, device, execute)
    }

    @Test
    fun registerForNotification() {
        val handle = 2
        val enable = true

        binder.registerForNotification(gattCallback, device, handle, enable, source)
        verify(service).registerForNotification(gattCallback, device, handle, enable)
    }

    @Test
    fun readRemoteRssi() {
        binder.readRemoteRssi(gattCallback, device, source)
        verify(service).readRemoteRssi(gattCallback, device)
    }

    @Test
    fun configureMTU() {
        val mtu = 2

        binder.configureMTU(gattCallback, device, mtu, source)
        verify(service).configureMTU(gattCallback, device, mtu)
    }

    @Test
    fun connectionParameterUpdate() {
        val connectionPriority = 2

        binder.connectionParameterUpdate(gattCallback, device, connectionPriority, source)
        verify(service).connectionParameterUpdate(gattCallback, device, connectionPriority)
    }

    @Test
    fun leConnectionUpdate() {
        val minConnectionInterval = 3
        val maxConnectionInterval = 4
        val peripheralLatency = 5
        val supervisionTimeout = 6
        val minConnectionEventLen = 7
        val maxConnectionEventLen = 8

        binder.leConnectionUpdate(
            gattCallback,
            device,
            minConnectionInterval,
            maxConnectionInterval,
            peripheralLatency,
            supervisionTimeout,
            minConnectionEventLen,
            maxConnectionEventLen,
            source,
        )
        verify(service)
            .leConnectionUpdate(
                gattCallback,
                device,
                minConnectionInterval,
                maxConnectionInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
            )
    }

    @Test
    fun subrateModeRequest() {
        val testDevice = getTestDevice(5)
        val subrateMode = 0

        binder.subrateModeRequest(gattCallback, testDevice, subrateMode, source)

        verify(service).subrateModeRequest(gattCallback, testDevice, subrateMode)
    }

    @Test
    fun registerServer() {
        val uuid = UUID.randomUUID()
        val eattSupport = true
        val transport = BluetoothDevice.TRANSPORT_LE

        binder.registerServer(ParcelUuid(uuid), gattServerCallback, eattSupport, transport, source)
        verify(serverManager)
            .registerServer(uuid, gattServerCallback, eattSupport, transport, source)
    }

    @Test
    fun unregisterServer() {
        binder.unregisterServer(gattServerCallback, source)
        verify(serverManager).unregisterServer(gattServerCallback)
    }

    @Test
    fun serverConnect() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = 2

        binder.serverConnect(gattServerCallback, device, addressType, isDirect, transport, source)
        verify(serverManager)
            .serverConnect(gattServerCallback, device, addressType, isDirect, transport, source)
    }

    @Test
    fun serverDisconnect() {
        binder.serverDisconnect(gattServerCallback, device, source)
        verify(serverManager).serverDisconnect(gattServerCallback, device)
    }

    @Test
    fun serverSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3

        binder.serverSetPreferredPhy(gattServerCallback, device, txPhy, rxPhy, phyOptions, source)
        verify(serverManager)
            .serverSetPreferredPhy(gattServerCallback, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun serverReadPhy() {
        binder.serverReadPhy(gattServerCallback, device, source)
        verify(serverManager).serverReadPhy(gattServerCallback, device)
    }

    @Test
    fun addService() {
        val svc = mock<BluetoothGattService>()

        binder.addService(gattServerCallback, svc, source)
        verify(serverManager).addService(gattServerCallback, svc)
    }

    @Test
    fun removeService() {
        val handle = 2

        binder.removeService(gattServerCallback, handle, source)
        verify(serverManager).removeService(gattServerCallback, handle)
    }

    @Test
    fun clearServices() {
        binder.clearServices(gattServerCallback, source)
        verify(serverManager).clearServices(gattServerCallback)
    }

    @Test
    fun sendResponse() {
        val requestId = 2
        val status = 3
        val offset = 4
        val value = byteArrayOf(5, 6)

        binder.sendResponse(gattServerCallback, device, requestId, status, offset, value, source)
        verify(serverManager)
            .sendResponse(gattServerCallback, device, requestId, status, offset, value)
    }

    @Test
    fun sendNotification() {
        val handle = 2
        val confirm = true
        val value = byteArrayOf(5, 6)

        binder.sendNotification(gattServerCallback, device, handle, confirm, value, source)
        verify(serverManager).sendNotification(gattServerCallback, device, handle, confirm, value)
    }

    @Test
    fun disconnectAll() {
        binder.disconnectAll(source)
        verify(service).disconnectAll(source)
    }

    @Test
    fun cleanup_doesNotCrash() {
        binder.cleanup()
    }
}
