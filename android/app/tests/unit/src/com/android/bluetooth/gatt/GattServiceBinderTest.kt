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
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import java.util.UUID
import java.util.function.Supplier
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test cases for [GattServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class GattServiceBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var serverCallback: IBluetoothGattServerCallback
    @Mock private lateinit var callback: IBluetoothGattCallback
    @Mock private lateinit var service: GattService
    @Mock private lateinit var serverManager: GattServerManager

    private val device = getTestDevice(109)

    private lateinit var binder: GattServiceBinder

    @Before
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

        binder.registerClient(ParcelUuid(uuid), callback, eattSupport, transport, source)
        verify(service).registerClient(uuid, callback, eattSupport, transport, source)
    }

    @Test
    fun unregisterClient() {
        binder.unregisterClient(callback, source)
        verify(service)
            .unregisterClient(callback, source, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)
    }

    @Test
    fun clientConnect() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = 2
        val opportunistic = true
        val isAutomaticMtuEnabled = false

        binder.clientConnect(
            callback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            isAutomaticMtuEnabled,
            source,
        )
        verify(service)
            .clientConnect(
                callback,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                isAutomaticMtuEnabled,
                source,
            )
    }

    @Test
    fun clientDisconnect() {
        binder.clientDisconnect(callback, device, source)
        verify(service).clientDisconnect(callback, device, source)
    }

    @Test
    fun clientSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3

        binder.clientSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions, source)
        verify(service).clientSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun clientReadPhy() {
        binder.clientReadPhy(callback, device, source)
        verify(service).clientReadPhy(callback, device)
    }

    @Test
    fun refreshDevice() {
        binder.refreshDevice(callback, device, source)
        verify(service).refreshDevice(callback, device)
    }

    @Test
    fun discoverServices() {
        binder.discoverServices(callback, device, source)
        verify(service).discoverServices(callback, device)
    }

    @Test
    fun discoverServiceByUuid() {
        val uuid = UUID.randomUUID()

        binder.discoverServiceByUuid(callback, device, ParcelUuid(uuid), source)
        verify(service).discoverServiceByUuid(callback, device, uuid)
    }

    @Test
    fun readCharacteristic() {
        val handle = 2
        val authReq = 3

        binder.readCharacteristic(callback, device, handle, authReq, source)
        verify(service).readCharacteristic(callback, device, handle, authReq)
    }

    @Test
    fun readUsingCharacteristicUuid() {
        val uuid = UUID.randomUUID()
        val startHandle = 2
        val endHandle = 3
        val authReq = 4

        binder.readUsingCharacteristicUuid(
            callback,
            device,
            ParcelUuid(uuid),
            startHandle,
            endHandle,
            authReq,
            source,
        )
        verify(service)
            .readUsingCharacteristicUuid(callback, device, uuid, startHandle, endHandle, authReq)
    }

    @Test
    fun writeCharacteristic() {
        val handle = 2
        val writeType = 3
        val authReq = 4
        val value = byteArrayOf(5, 6)

        binder.writeCharacteristic(callback, device, handle, writeType, authReq, value, source)
        verify(service).writeCharacteristic(callback, device, handle, writeType, authReq, value)
    }

    @Test
    fun readDescriptor() {
        val handle = 2
        val authReq = 3

        binder.readDescriptor(callback, device, handle, authReq, source)
        verify(service).readDescriptor(callback, device, handle, authReq)
    }

    @Test
    fun writeDescriptor() {
        val handle = 2
        val authReq = 3
        val value = byteArrayOf(4, 5)

        binder.writeDescriptor(callback, device, handle, authReq, value, source)
        verify(service).writeDescriptor(callback, device, handle, authReq, value)
    }

    @Test
    fun beginReliableWrite() {
        binder.beginReliableWrite(device, source)
        verify(service).beginReliableWrite(device)
    }

    @Test
    fun endReliableWrite() {
        val execute = true

        binder.endReliableWrite(callback, device, execute, source)
        verify(service).endReliableWrite(callback, device, execute)
    }

    @Test
    fun registerForNotification() {
        val handle = 2
        val enable = true

        binder.registerForNotification(callback, device, handle, enable, source)
        verify(service).registerForNotification(callback, device, handle, enable)
    }

    @Test
    fun readRemoteRssi() {
        binder.readRemoteRssi(callback, device, source)
        verify(service).readRemoteRssi(callback, device)
    }

    @Test
    fun configureMTU() {
        val mtu = 2

        binder.configureMTU(callback, device, mtu, source)
        verify(service).configureMTU(callback, device, mtu)
    }

    @Test
    fun connectionParameterUpdate() {
        val connectionPriority = 2

        binder.connectionParameterUpdate(callback, device, connectionPriority, source)
        verify(service).connectionParameterUpdate(callback, device, connectionPriority)
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
            callback,
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
                callback,
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

        binder.subrateModeRequest(callback, testDevice, subrateMode, source)

        verify(service).subrateModeRequest(callback, testDevice, subrateMode)
    }

    @Test
    fun registerServer() {
        val eattSupport = true
        val transport = BluetoothDevice.TRANSPORT_LE

        binder.registerServer(serverCallback, eattSupport, transport, source)
        verify(serverManager).registerServer(serverCallback, eattSupport, transport, source)
    }

    @Test
    fun unregisterServer() {
        binder.unregisterServer(serverCallback, source)
        verify(serverManager).unregisterServer(serverCallback)
    }

    @Test
    fun serverConnect() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = 2

        binder.serverConnect(serverCallback, device, addressType, isDirect, transport, source)
        verify(serverManager)
            .serverConnect(serverCallback, device, addressType, isDirect, transport, source)
    }

    @Test
    fun serverDisconnect() {
        binder.serverDisconnect(serverCallback, device, source)
        verify(serverManager).serverDisconnect(serverCallback, device)
    }

    @Test
    fun serverSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3

        binder.serverSetPreferredPhy(serverCallback, device, txPhy, rxPhy, phyOptions, source)
        verify(serverManager)
            .serverSetPreferredPhy(serverCallback, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun serverReadPhy() {
        binder.serverReadPhy(serverCallback, device, source)
        verify(serverManager).serverReadPhy(serverCallback, device)
    }

    @Test
    fun addService() {
        val svc = mock<BluetoothGattService>()

        binder.addService(serverCallback, svc, source)
        verify(serverManager).addService(serverCallback, svc)
    }

    @Test
    fun removeService() {
        val handle = 2

        binder.removeService(serverCallback, handle, source)
        verify(serverManager).removeService(serverCallback, handle)
    }

    @Test
    fun clearServices() {
        binder.clearServices(serverCallback, source)
        verify(serverManager).clearServices(serverCallback)
    }

    @Test
    fun sendResponse() {
        val requestId = 2
        val status = 3
        val offset = 4
        val values = listOf(null, byteArrayOf(5, 6))

        values.forEach { value ->
            binder.sendResponse(serverCallback, device, requestId, status, offset, value, source)
            verify(serverManager)
                .sendResponse(serverCallback, device, requestId, status, offset, value)
        }
    }

    @Test
    fun sendNotification() {
        val handle = 2
        val confirm = true
        val value = byteArrayOf(5, 6)

        binder.sendNotification(serverCallback, device, handle, confirm, value, source)
        verify(serverManager).sendNotification(serverCallback, device, handle, confirm, value)
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
