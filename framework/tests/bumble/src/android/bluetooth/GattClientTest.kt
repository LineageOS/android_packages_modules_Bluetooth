/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth

import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.Context
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assume
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import pandora.GattProto
import pandora.HostProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse

@RunWith(TestParameterInjector::class)
@ExperimentalCoroutinesApi
class GattClientTest {
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1) val bumble = PandoraDevice()

    @get:Rule(order = 2) val enableBluetoothRule = EnableBluetoothRule(false, true)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var host: Host
    private lateinit var remoteLeDevice: BluetoothDevice

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity()

        host = Host(context)
        remoteLeDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        remoteLeDevice.removeBond()
    }

    @After
    fun tearUp() {
        val bondedDevices = adapter.bondedDevices
        if (bondedDevices.contains(remoteLeDevice)) {
            remoteLeDevice.removeBond()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        host.close()
    }

    @Test
    fun directConnectGattAfterClose() {
        advertiseWithBumble()

        val device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = device.connectGatt(context, false, gattCallback)
        gatt.close()

        // Save the number of call in the callback to be checked later
        val invocations = mockingDetails(gattCallback).invocations
        val numberOfCalls = invocations.size

        val gattCallback2 = mock<BluetoothGattCallback>()
        val gatt2 = device.connectGatt(context, false, gattCallback2)
        verify(gattCallback2, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))
        disconnectAndWaitDisconnection(gatt2, gattCallback2)

        // After reconnecting, verify the first callback was not invoked.
        val invocationsAfterSomeTimes = mockingDetails(gattCallback).invocations
        val numberOfCallsAfterSomeTimes = invocationsAfterSomeTimes.size
        assertThat(numberOfCallsAfterSomeTimes).isEqualTo(numberOfCalls)
    }

    @Test
    fun fullGattClientLifecycle(@TestParameter autoConnect: Boolean) {
        if (autoConnect) {
            createLeBondAndWaitBonding(remoteLeDevice)
        }
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback, autoConnect)
        disconnectAndWaitDisconnection(gatt, gattCallback)
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_conn_settings")
    fun fullGattClientLifecycleWithGattSettings(
        @TestParameter autoConnect: Boolean,
        @TestParameter autoMtu: Boolean,
    ) {
        if (autoConnect) {
            createLeBondAndWaitBonding(remoteLeDevice)
        }
        val gattCallback = mock<BluetoothGattCallback>()

        val gattSettings =
            BluetoothGattConnectionSettings.Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutomaticMtuEnabled(autoMtu)
                .setAutoConnectEnabled(autoConnect)
                .setOpportunisticEnabled(false)
                .build()

        val gatt =
            connectGattAndWaitConnectionWithGattSettings(gattCallback, autoConnect, gattSettings)

        verify(gattCallback, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        // Ensure Application receive MTU update with default ANDROID_MTU value
        if (autoMtu) {
            verify(gattCallback, timeout(5000).atLeast(1))
                .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS))
        }
        disconnectAndWaitDisconnection(gatt, gattCallback)
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_conn_settings")
    fun fullGattClientLifecycleWithGattSettingsWithMultipleClients(
        @TestParameter autoConnect: Boolean
    ) {
        if (autoConnect) {
            createLeBondAndWaitBonding(remoteLeDevice)
        }

        val gattCallback_1 = mock<BluetoothGattCallback>()
        // first gatt connection settings with autoMtu as false
        val gattSettingsWithAutoMtuOff =
            BluetoothGattConnectionSettings.Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutomaticMtuEnabled(false)
                .setAutoConnectEnabled(autoConnect)
                .setOpportunisticEnabled(false)
                .build()

        // Second gatt connection settings with autoMtu as true
        val gattCallback_2 = mock<BluetoothGattCallback>()
        // second settings with autoMtu as True
        val gattSettingsWithAutoMtuOn =
            BluetoothGattConnectionSettings.Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutomaticMtuEnabled(true)
                .setAutoConnectEnabled(autoConnect)
                .setOpportunisticEnabled(false)
                .build()

        // Trigger gatt connection with gatt connection setting with autoMtu as FALSE
        val gatt_1 =
            connectGattAndWaitConnectionWithGattSettings(
                gattCallback_1,
                autoConnect,
                gattSettingsWithAutoMtuOff,
            )

        // First Gatt connection should only generate connection state without any
        // MTU update
        verify(gattCallback_1, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))
        // No MTU update callback
        verify(gattCallback_1, never()).onMtuChanged(eq(gatt_1), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        clearInvocations(gattCallback_1)
        clearInvocations(gattCallback_2)
        // Trigger gatt connection with gatt connection with setting of autoMtu as TRUE
        val gatt_2: BluetoothGatt
        gatt_2 =
            connectGattAndWaitConnectionWithGattSettings(
                gattCallback_2,
                autoConnect,
                gattSettingsWithAutoMtuOn,
            )

        // Second Gatt connection should only generate connection state for second GATT client
        // and MTU update only for GATT client 2
        verify(gattCallback_2, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        // Ensure both clients gets the MTU update
        verify(gattCallback_1, timeout(5000).atLeast(1))
            .onMtuChanged(eq(gatt_1), eq(ANDROID_MTU), eq(GATT_SUCCESS))
        verify(gattCallback_2, timeout(5000).atLeast(1))
            .onMtuChanged(eq(gatt_2), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        // Now create GATT client 3 with autoMtu as FALSE
        val gattCallback_3 = mock<BluetoothGattCallback>()
        val gatt_3 =
            connectGattAndWaitConnectionWithGattSettings(
                gattCallback_3,
                autoConnect,
                gattSettingsWithAutoMtuOff,
            )

        clearInvocations(gattCallback_1)
        clearInvocations(gattCallback_2)

        // Third Gatt connection should only generate connection state for third GATT client
        // and MTU update only for 3rd client
        verify(gattCallback_3, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        // Ensure first 2 client won't receive any MTU update callbacks
        verify(gattCallback_1, never()).onMtuChanged(eq(gatt_1), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        verify(gattCallback_2, never()).onMtuChanged(eq(gatt_2), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        // Only 3rd client supposed to be notified of MTU update
        verify(gattCallback_3, timeout(5000).atLeast(1))
            .onMtuChanged(eq(gatt_3), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        // disconnect and connect back the gatt client 2 with MTU on
        // connecting back should generate the MTU callback on gatt client 2 only
        clearInvocations(gattCallback_1)
        clearInvocations(gattCallback_2)
        clearInvocations(gattCallback_3)

        Log.i(TAG, "calling gat client 2 disconnect")
        // Trigger gatt connection with gatt connection with setting of autoMtu as TRUE
        gatt_2.disconnect()
        verify(gattCallback_2, timeout(1000))
            .onConnectionStateChange(eq(gatt_2), any<Int>(), eq(STATE_DISCONNECTED))
        // reuse the same obj to trigger connect
        gatt_2.connect()

        // Second Gatt connection should only generate connection state for second GATT client
        // and MTU update for both the GATT clients
        verify(gattCallback_2, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        // Ensure first  client_1 & client_3 won't receive any MTU update callbacks
        verify(gattCallback_1, never()).onMtuChanged(eq(gatt_1), eq(ANDROID_MTU), eq(GATT_SUCCESS))
        verify(gattCallback_3, never()).onMtuChanged(eq(gatt_3), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        // Ensure only client_2 receive MTU update with default ANDROID_MTU value
        verify(gattCallback_2, timeout(5000).atLeast(1))
            .onMtuChanged(eq(gatt_2), eq(ANDROID_MTU), eq(GATT_SUCCESS))

        // clear all invocations so that disconnect util expect
        // right number callbacks on disconnects
        clearInvocations(gattCallback_1)
        clearInvocations(gattCallback_2)
        clearInvocations(gattCallback_3)

        // disconnect both GATT clients
        disconnectAndWaitDisconnection(gatt_1, gattCallback_1)
        disconnectAndWaitDisconnection(gatt_2, gattCallback_2)
        disconnectAndWaitDisconnection(gatt_3, gattCallback_3)
    }

    @Test
    fun reconnectExistingClient() {
        advertiseWithBumble()

        val gattCallback = mock<BluetoothGattCallback>()
        val inOrder = inOrder(gattCallback)

        val gatt = remoteLeDevice.connectGatt(context, false, gattCallback)
        inOrder
            .verify(gattCallback, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        gatt.disconnect()
        inOrder
            .verify(gattCallback, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_DISCONNECTED))

        gatt.connect()
        inOrder
            .verify(gattCallback, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        // TODO(323889717): Fix callback being called after gatt.close(). This disconnect shouldn't
        // be necessary.
        gatt.disconnect()
        inOrder
            .verify(gattCallback, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_DISCONNECTED))
        gatt.close()
    }

    @Test
    fun clientGattDiscoverServices() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            assertThat(gatt.services.map { it.uuid }).contains(GAP_UUID)
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun clientGattReadCharacteristics() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.services.first()
            val firstCharacteristic = firstService.characteristics.first()

            gatt.readCharacteristic(firstCharacteristic)
            verify(gattCallback, timeout(5000))
                .onCharacteristicRead(any(), any(), any(), any<Int>())
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun clientGattWriteCharacteristic() {
        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val characteristic =
                gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID)
            val newValue = byteArrayOf(13)

            gatt.writeCharacteristic(
                characteristic,
                newValue,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
            verify(gattCallback, timeout(5000))
                .onCharacteristicWrite(any(), eq(characteristic), eq(GATT_SUCCESS))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun clientGattNotifyOrIndicateCharacteristic(@TestParameter isIndicate: Boolean) {
        registerNotificationIndicationGattService(isIndicate)

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val characteristic =
                gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor.value =
                if (isIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            assertThat(gatt.writeDescriptor(descriptor)).isTrue()

            verify(gattCallback, timeout(5000))
                .onDescriptorWrite(any(), eq(descriptor), eq(GATT_SUCCESS))

            gatt.setCharacteristicNotification(characteristic, true)

            if (isIndicate) {
                Log.i(TAG, "Triggering characteristic indication")
                triggerCharacteristicIndication(characteristic.instanceId)
            } else {
                Log.i(TAG, "Triggering characteristic notification")
                triggerCharacteristicNotification(characteristic.instanceId)
            }

            verify(gattCallback, timeout(5000))
                .onCharacteristicChanged(
                    any(),
                    any(),
                    eq(NOTIFICATION_VALUE.toByteArray(StandardCharsets.UTF_8)),
                )
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun connectTimeout() {
        val device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        val gattCallback = mock<BluetoothGattCallback>()

        // Connecting to a device not advertising results in connection timeout after 30 seconds
        device.connectGatt(context, false, gattCallback)

        verify(gattCallback, timeout(35000))
            .onConnectionStateChange(
                any(),
                eq(BluetoothGatt.GATT_CONNECTION_TIMEOUT),
                eq(STATE_DISCONNECTED),
            )
    }

    @Test
    fun consecutiveWriteCharacteristicFails_thenSuccess() {
        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gattCallback2 = mock<BluetoothGattCallback>()

        val gatt = connectGattAndWaitConnection(gattCallback)
        val gatt2 = connectGattAndWaitConnection(gattCallback2)

        try {
            gatt.discoverServices()
            gatt2.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))
            verify(gattCallback2, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val characteristic =
                gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID)
            val characteristic2 =
                gatt2.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID)
            val newValue = byteArrayOf(13)

            gatt.writeCharacteristic(
                characteristic,
                newValue,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )

            // TODO: b/324355496 - Make the test consistent when Bumble supports holding a response.
            // Skip the test if the second write succeeded.
            Assume.assumeFalse(
                gatt2.writeCharacteristic(
                    characteristic2,
                    newValue,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            )

            verify(gattCallback, timeout(5000))
                .onCharacteristicWrite(any(), eq(characteristic), eq(GATT_SUCCESS))
            verify(gattCallback2, never())
                .onCharacteristicWrite(any(), eq(characteristic), eq(GATT_SUCCESS))

            assertThat(
                    gatt2.writeCharacteristic(
                        characteristic2,
                        newValue,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                )
                .isEqualTo(BluetoothStatusCodes.SUCCESS)
            verify(gattCallback2, timeout(5000))
                .onCharacteristicWrite(any(), eq(characteristic2), eq(GATT_SUCCESS))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
            disconnectAndWaitDisconnection(gatt2, gattCallback2)
        }
    }

    @Test
    fun connectMultiple_closeOne_shouldSuccess() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gattCallback2 = mock<BluetoothGattCallback>()

        advertiseWithBumble()
        val device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        val gatt = device.connectGatt(context, false, gattCallback)
        val gatt2 = device.connectGatt(context, false, gattCallback2)

        try {
            verify(gattCallback2, timeout(1000))
                .onConnectionStateChange(eq(gatt2), eq(GATT_SUCCESS), eq(STATE_CONNECTED))

            gatt.disconnect()
            gatt.close()
        } finally {
            gatt2.disconnect()
            gatt2.close()
        }
    }

    private fun registerGattService() {
        val characteristicParams =
            GattProto.GattCharacteristicParams.newBuilder()
                .setProperties(
                    BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE
                )
                .setUuid(TEST_CHARACTERISTIC_UUID.toString())
                .build()

        val characteristicParams2 =
            GattProto.GattCharacteristicParams.newBuilder()
                .setProperties(
                    BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE
                )
                .setUuid(TEST_CHARACTERISTIC_UUID2.toString())
                .build()

        val serviceParams =
            GattProto.GattServiceParams.newBuilder()
                .addCharacteristics(characteristicParams)
                .addCharacteristics(characteristicParams2)
                .setUuid(TEST_SERVICE_UUID.toString())
                .build()

        val request =
            GattProto.RegisterServiceRequest.newBuilder().setService(serviceParams).build()

        bumble.gattBlocking().registerService(request)
    }

    private fun registerNotificationIndicationGattService(isIndicate: Boolean) {
        val characteristicParams =
            GattProto.GattCharacteristicParams.newBuilder()
                .setProperties(
                    if (isIndicate) BluetoothGattCharacteristic.PROPERTY_INDICATE
                    else BluetoothGattCharacteristic.PROPERTY_NOTIFY
                )
                .setUuid(TEST_CHARACTERISTIC_UUID.toString())
                .build()

        val serviceParams =
            GattProto.GattServiceParams.newBuilder()
                .addCharacteristics(characteristicParams)
                .setUuid(TEST_SERVICE_UUID.toString())
                .build()

        val request =
            GattProto.RegisterServiceRequest.newBuilder().setService(serviceParams).build()

        bumble.gattBlocking().registerService(request)
    }

    private fun triggerCharacteristicNotification(instanceId: Int) {
        val req =
            GattProto.NotifyOnCharacteristicRequest.newBuilder()
                .setHandle(instanceId)
                .setValue(ByteString.copyFromUtf8(NOTIFICATION_VALUE))
                .build()
        val resp = bumble.gattBlocking().notifyOnCharacteristic(req)
        assertThat(resp.status).isEqualTo(GattProto.AttStatusCode.SUCCESS)
    }

    private fun triggerCharacteristicIndication(instanceId: Int) {
        val req =
            GattProto.IndicateOnCharacteristicRequest.newBuilder()
                .setHandle(instanceId)
                .setValue(ByteString.copyFromUtf8(NOTIFICATION_VALUE))
                .build()
        val resp = bumble.gattBlocking().indicateOnCharacteristic(req)
        assertThat(resp.status).isEqualTo(GattProto.AttStatusCode.SUCCESS)
    }

    @Test
    fun multipleGattClientsSeparateInteractions() {
        advertiseWithBumble()

        val device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val gattCallbackA = mock<BluetoothGattCallback>()
        val gattCallbackB = mock<BluetoothGattCallback>()
        val inOrder: InOrder = inOrder(gattCallbackA, gattCallbackB)

        val gattA = device.connectGatt(context, false, gattCallbackA)
        inOrder
            .verify(gattCallbackA, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        val gattB = device.connectGatt(context, false, gattCallbackB)
        inOrder
            .verify(gattCallbackB, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        gattA.disconnect()
        inOrder
            .verify(gattCallbackA, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_DISCONNECTED))

        gattA.connect()
        inOrder
            .verify(gattCallbackA, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        gattB.disconnect()
        inOrder
            .verify(gattCallbackB, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_DISCONNECTED))

        gattB.close()

        gattA.disconnect()
        inOrder
            .verify(gattCallbackA, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_DISCONNECTED))

        gattA.connect()
        inOrder
            .verify(gattCallbackA, timeout(1000))
            .onConnectionStateChange(any(), any<Int>(), eq(STATE_CONNECTED))

        gattA.close()
    }

    private fun advertiseWithBumble() {
        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                .build()

        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()

        bumble.host().advertise(request, responseObserver)
    }

    private fun connectGattAndWaitConnection(callback: BluetoothGattCallback): BluetoothGatt {
        return connectGattAndWaitConnection(callback, autoConnect = false)
    }

    private fun connectGattAndWaitConnection(
        callback: BluetoothGattCallback,
        autoConnect: Boolean,
    ): BluetoothGatt {
        val status = GATT_SUCCESS
        val state = STATE_CONNECTED

        advertiseWithBumble()

        val gatt = remoteLeDevice.connectGatt(context, autoConnect, callback)
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), eq(status), eq(state))

        return gatt
    }

    private fun connectGattAndWaitConnectionWithGattSettings(
        callback: BluetoothGattCallback,
        autoConnect: Boolean,
        gattConnectionSettings: BluetoothGattConnectionSettings,
    ): BluetoothGatt {
        val status = GATT_SUCCESS
        val state = STATE_CONNECTED

        advertiseWithBumble()

        val gatt: BluetoothGatt? =
            remoteLeDevice.connectGatt(gattConnectionSettings, context.mainExecutor, callback)
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), eq(status), eq(state))

        return gatt!!
    }

    /** Tries to connect GATT, it could fail and return null. */
    private fun tryConnectGatt(
        callback: BluetoothGattCallback,
        autoConnect: Boolean,
    ): BluetoothGatt? {
        advertiseWithBumble()

        val gatt = remoteLeDevice.connectGatt(context, autoConnect, callback)

        val statusCaptor = ArgumentCaptor.forClass(Int::class.java)
        val stateCaptor = ArgumentCaptor.forClass(Int::class.java)
        verify(callback, timeout(1000))
            .onConnectionStateChange(eq(gatt), statusCaptor.capture(), stateCaptor.capture())

        if (statusCaptor.value == GATT_SUCCESS && stateCaptor.value == STATE_CONNECTED) {
            return gatt
        }
        gatt.close()
        return null
    }

    @Test
    fun requestMtu_invalidParameter_isFalse() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            assertThat(gatt.requestMtu(1024)).isTrue()
            // status should be 0x87 (GATT_ILLEGAL_PARAMETER) but not defined.
            verify(gattCallback, timeout(5000).atLeast(1))
                .onMtuChanged(eq(gatt), any<Int>(), AdditionalMatchers.not(eq(GATT_SUCCESS)))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun requestMtu_once_isSuccess() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            assertThat(gatt.requestMtu(MTU_REQUESTED)).isTrue()
            // Check that only the ANDROID_MTU is returned, not the MTU_REQUESTED
            verify(gattCallback, timeout(5000))
                .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun requestMtu_multipleTimeFromSameClient_isRejected() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            assertThat(gatt.requestMtu(MTU_REQUESTED)).isTrue()
            // Check that only the ANDROID_MTU is returned, not the MTU_REQUESTED
            verify(gattCallback, timeout(5000))
                .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS))

            assertThat(gatt.requestMtu(ANOTHER_MTU_REQUESTED)).isTrue()
            verify(gattCallback, timeout(5000).times(2))
                .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    fun requestMtu_onceFromMultipleClient_secondIsSuccessWithoutUpdate() {
        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            assertThat(gatt.requestMtu(MTU_REQUESTED)).isTrue()
            verify(gattCallback, timeout(5000))
                .onMtuChanged(eq(gatt), eq(ANDROID_MTU), eq(GATT_SUCCESS))

            val gattCallback2 = mock<BluetoothGattCallback>()
            val gatt2 = connectGattAndWaitConnection(gattCallback2)
            try {
                // first callback because there is already a connected device
                verify(gattCallback2, timeout(9000))
                    .onMtuChanged(eq(gatt2), eq(ANDROID_MTU), eq(GATT_SUCCESS))
                assertThat(gatt2.requestMtu(ANOTHER_MTU_REQUESTED)).isTrue()
                verify(gattCallback2, timeout(9000).times(2))
                    .onMtuChanged(eq(gatt2), eq(ANDROID_MTU), eq(GATT_SUCCESS))
            } finally {
                disconnectAndWaitDisconnection(gatt2, gattCallback2)
            }
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    // Check if we can have 100 simultaneous clients
    @Test
    fun connectGatt_multipleClients() {
        registerGattService()

        val gatts = mutableListOf<BluetoothGatt>()
        var failed = false
        val repeatTimes = 100

        try {
            repeat(repeatTimes) {
                var gattCallback = mock<BluetoothGattCallback>()
                var gatt = tryConnectGatt(gattCallback, false)
                // If it fails, close an existing gatt instance and try again.
                if (gatt == null) {
                    failed = true
                    val connectedGatt = gatts.removeAt(0)
                    connectedGatt.disconnect()
                    connectedGatt.close()
                    gattCallback = mock<BluetoothGattCallback>()
                    gatt = connectGattAndWaitConnection(gattCallback)
                }
                gatts.add(gatt)
                gatt.discoverServices()
                verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

                val characteristic =
                    gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID)
                gatt.readCharacteristic(characteristic)
                verify(gattCallback, timeout(5000))
                    .onCharacteristicRead(any(), any(), any(), any<Int>())
            }
        } finally {
            gatts.forEach {
                it.disconnect()
                it.close()
            }
        }
        // We should fail because we reached the limit.
        assertThat(failed).isTrue()
    }

    @Test
    fun writeCharacteristic_disconnected_shouldNotCrash() {
        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()

        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val characteristic =
                gatt.getService(TEST_SERVICE_UUID).getCharacteristic(TEST_CHARACTERISTIC_UUID)

            val newValue = byteArrayOf(13)

            gatt.writeCharacteristic(
                characteristic,
                newValue,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
            // TODO(b/370607862): disconnect from the remote
            gatt.disconnect()
            gatt.close()
        } finally {
            // it's okay to close twice.
            gatt.close()
        }
    }

    @Test
    fun connectAndDisconnectManyClientsWithoutClose() {
        advertiseWithBumble()

        val gatts = mutableListOf<BluetoothGatt>()
        val gattCallbackTimeout = 10000L
        try {
            repeat(100) {
                val gattCallback = mock<BluetoothGattCallback>()
                val inOrder = inOrder(gattCallback)

                val gatt = remoteLeDevice.connectGatt(context, false, gattCallback)
                gatts.add(gatt)
                inOrder
                    .verify(gattCallback, timeout(gattCallbackTimeout))
                    .onConnectionStateChange(eq(gatt), eq(GATT_SUCCESS), eq(STATE_CONNECTED))

                gatt.disconnect()
                inOrder
                    .verify(gattCallback, timeout(gattCallbackTimeout))
                    .onConnectionStateChange(eq(gatt), eq(GATT_SUCCESS), eq(STATE_DISCONNECTED))

                assertThat(gatt.connect()).isTrue()
                inOrder
                    .verify(gattCallback, timeout(gattCallbackTimeout))
                    .onConnectionStateChange(eq(gatt), eq(GATT_SUCCESS), eq(STATE_CONNECTED))

                gatt.disconnect()
                inOrder
                    .verify(gattCallback, timeout(gattCallbackTimeout))
                    .onConnectionStateChange(eq(gatt), eq(GATT_SUCCESS), eq(STATE_DISCONNECTED))
            }
        } finally {
            gatts.forEach { it.close() }
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientOffloadCharacteristics() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.getService(TEST_SERVICE_UUID)
            val status =
                gatt.offloadCharacteristics(
                    firstService,
                    firstService.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)

            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            val session = sessionCaptor.getValue()
            assertThat(session).isNotNull()
            Log.i(TAG, "Offload session: ${session}")
            assertThat(session.sessionId)
                .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)
            assertThat(session.gattService).isEqualTo(firstService)
            assertThat(session.gattCharacteristics).isEqualTo(firstService.characteristics)
            assertThat(session.endpointId).isEqualTo(TEST_ENDPOINT_ID)
            assertThat(session.hubId).isEqualTo(TEST_HUB_ID)
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientsConcurrentOffloadDifferentCharacteristics() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.getService(TEST_SERVICE_UUID)
            val t1 = thread {
                val status =
                    gatt.offloadCharacteristics(
                        firstService,
                        listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                        TEST_ENDPOINT_ID,
                        TEST_HUB_ID,
                    )
                assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            }

            val t2 = thread {
                val status =
                    gatt.offloadCharacteristics(
                        firstService,
                        listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID2)),
                        TEST_ENDPOINT_ID,
                        TEST_HUB_ID,
                    )
                assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            }

            t1.join()
            t2.join()

            verify(gattCallback, timeout(10000).times(2))
                .onCharacteristicsOffloaded(any(), any(), eq(GattOffloadSession.STATUS_SUCCESS))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientOffloadSameCharacteristicsFails() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.getService(TEST_SERVICE_UUID)
            var status =
                gatt.offloadCharacteristics(
                    firstService,
                    listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(any(), any(), eq(GattOffloadSession.STATUS_SUCCESS))

            clearInvocations(gattCallback)
            status =
                gatt.offloadCharacteristics(
                    firstService,
                    listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isNotEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, never()).onCharacteristicsOffloaded(any(), any(), any<Int>())
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientOffloadSameCharacteristicsDifferentEndpointFails() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.getService(TEST_SERVICE_UUID)
            var status =
                gatt.offloadCharacteristics(
                    firstService,
                    listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(any(), any(), eq(GattOffloadSession.STATUS_SUCCESS))

            clearInvocations(gattCallback)
            status =
                gatt.offloadCharacteristics(
                    firstService,
                    listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                    TEST_ENDPOINT_ID2,
                    TEST_HUB_ID,
                )
            assertThat(status).isNotEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, never()).onCharacteristicsOffloaded(any(), any(), any<Int>())
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun differentClientsOffloadSameCharacteristicsFails() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerGattService()

        val gattCallback = mock<BluetoothGattCallback>()
        val gattCallback2 = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)
        val gatt2 = connectGattAndWaitConnection(gattCallback2)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            gatt2.discoverServices()
            verify(gattCallback2, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.getService(TEST_SERVICE_UUID)
            var status =
                gatt.offloadCharacteristics(
                    firstService,
                    listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(any(), any(), eq(GattOffloadSession.STATUS_SUCCESS))

            clearInvocations(gattCallback)
            status =
                gatt2.offloadCharacteristics(
                    firstService,
                    listOf(firstService.getCharacteristic(TEST_CHARACTERISTIC_UUID)),
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isNotEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, never()).onCharacteristicsOffloaded(any(), any(), any<Int>())
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
            disconnectAndWaitDisconnection(gatt2, gattCallback2)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientUnoffloadCharacteristics() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.services[0]
            val status =
                gatt.offloadCharacteristics(
                    firstService,
                    firstService.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)

            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            val session = sessionCaptor.getValue()
            val sessionId = session.sessionId
            assertThat(session).isNotNull()
            Log.i(TAG, "Offload session: ${session}")
            assertThat(sessionId).isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)

            session.close()
            verify(gattCallback, timeout(10000))
                .onCharacteristicsUnoffloaded(
                    any(),
                    eq(sessionId),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientUnoffloadCharacteristics_autoClose() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val firstService = gatt.services[0]
            var sessionId = GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN // Initialize sessionId

            val status =
                gatt.offloadCharacteristics(
                    firstService,
                    firstService.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)

            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            val session = sessionCaptor.getValue()
            assertThat(session).isNotNull()
            sessionId = session.sessionId

            session.use { currentSession ->
                Log.i(TAG, "Current offload session: ${currentSession}")
                assertThat(currentSession.sessionId)
                    .isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)
            } // session.close() is automatically called here
            verify(gattCallback, timeout(10000))
                .onCharacteristicsUnoffloaded(
                    any(),
                    eq(sessionId),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientOffloadNotificationCharacteristicsFails_thenSuccess() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerNotificationIndicationGattService(/* isIndicate */ false)

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val notiService = gatt.getService(TEST_SERVICE_UUID)
            val characteristic = notiService.getCharacteristic(TEST_CHARACTERISTIC_UUID)

            // Register the notification characteristic
            gatt.setCharacteristicNotification(characteristic, true)
            var status =
                gatt.offloadCharacteristics(
                    notiService,
                    notiService.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isNotEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, never()).onCharacteristicsOffloaded(any(), any(), any<Int>())

            // Unregister the notification characteristic
            gatt.setCharacteristicNotification(characteristic, false)
            clearInvocations(gattCallback)
            status =
                gatt.offloadCharacteristics(
                    notiService,
                    notiService.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(any(), any(), eq(GattOffloadSession.STATUS_SUCCESS))
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.gatt_offload_api")
    fun clientOffloadNotificationCharacteristics_thenUnoffloaded() {
        assumeTrue(adapter.supportedGattOffloadCapabilities?.isClientOffloadSupported ?: false)

        registerNotificationIndicationGattService(/* isIndicate */ false)

        val gattCallback = mock<BluetoothGattCallback>()
        val gatt = connectGattAndWaitConnection(gattCallback)

        try {
            gatt.discoverServices()
            verify(gattCallback, timeout(10000)).onServicesDiscovered(any(), eq(GATT_SUCCESS))

            val notiService = gatt.getService(TEST_SERVICE_UUID)
            val characteristic = notiService.getCharacteristic(TEST_CHARACTERISTIC_UUID)

            val status =
                gatt.offloadCharacteristics(
                    notiService,
                    notiService.characteristics,
                    TEST_ENDPOINT_ID,
                    TEST_HUB_ID,
                )
            assertThat(status).isEqualTo(GattOffloadSession.STATUS_SUCCESS)

            val sessionCaptor = ArgumentCaptor.forClass(GattOffloadSession::class.java)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsOffloaded(
                    any(),
                    sessionCaptor.capture(),
                    eq(GattOffloadSession.STATUS_SUCCESS),
                )
            val session = sessionCaptor.getValue()
            assertThat(session).isNotNull()
            val sessionId = session.sessionId
            Log.i(TAG, "Offload session: ${session}")
            assertThat(sessionId).isNotEqualTo(GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN)

            // Register the notification characteristic
            gatt.setCharacteristicNotification(characteristic, true)
            verify(gattCallback, timeout(10000))
                .onCharacteristicsUnoffloaded(any(), eq(sessionId), any<Int>())
        } finally {
            disconnectAndWaitDisconnection(gatt, gattCallback)
        }
    }

    private fun disconnectAndWaitDisconnection(
        gatt: BluetoothGatt,
        callback: BluetoothGattCallback,
    ) {
        val state = STATE_DISCONNECTED
        gatt.disconnect()
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), any<Int>(), eq(state))

        gatt.close()
    }

    private fun createLeBondAndWaitBonding(device: BluetoothDevice) {
        advertiseWithBumble()
        host.createBondAndVerify(device)
    }

    companion object {
        private const val TAG = "GattClientTest"
        private const val ANDROID_MTU = 517
        private const val MTU_REQUESTED = 23
        private const val ANOTHER_MTU_REQUESTED = 42
        private const val TEST_HUB_ID: Long = 1
        private const val TEST_ENDPOINT_ID: Long = 2
        private const val TEST_ENDPOINT_ID2: Long = 3
        private const val NOTIFICATION_VALUE = "hello world"

        private val GAP_UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val TEST_SERVICE_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000")
        private val TEST_CHARACTERISTIC_UUID =
            UUID.fromString("00010001-0000-0000-0000-000000000000")
        private val TEST_CHARACTERISTIC_UUID2 =
            UUID.fromString("00010002-0000-0000-0000-000000000000")
    }
}
