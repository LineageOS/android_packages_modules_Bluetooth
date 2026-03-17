/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.hid

import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothUuid
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.TestUtils
import com.android.bluetooth.Util.getByteAddress
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.AdapterSuspend.AWAKE
import com.android.bluetooth.btservice.AdapterSuspend.DEEP_SLEEP
import com.android.bluetooth.btservice.AdapterSuspend.SHALLOW_SLEEP
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.hid.HidHostService.RECONNECT_ALLOWED
import com.android.bluetooth.hid.HidHostService.RECONNECT_NOT_ALLOWED
import com.android.bluetooth.hid.HidHostService.RECONNECT_NOT_ALLOWED_TEMPORARY
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [HidHostService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class HidHostServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var nativeInterface: HidHostNativeInterface

    private val device = getTestDevice(0)

    private lateinit var service: HidHostService
    private lateinit var looper: TestLooper

    @Before
    fun setUp() {
        looper = TestLooper()
        service = HidHostService(adapterService, nativeInterface, looper.looper)
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun okToConnect_whenInvalidBonded_returnFalse() {
        val badPolicyValue = 1024
        val badBondState = 42
        doReturn(badBondState).whenever(adapterService).getBondState(any())
        for (policy in listOf(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any())
            assertThat(service.okToConnect(device)).isFalse()
        }
    }

    @Test
    fun okToConnect_whenNotBonded_returnTrue() {
        // allow connect Due to desync between BondStateMachine and AdapterProperties
        for (bondState in listOf(BOND_NONE, BOND_BONDING)) {
            doReturn(bondState).whenever(adapterService).getBondState(any())
            for (policy in listOf(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
                doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any())
                assertThat(service.okToConnect(device)).isTrue()
            }
        }
    }

    @Test
    fun canConnect_whenBonded() {
        val badPolicyValue = 1024
        doReturn(BOND_BONDED).whenever(adapterService).getBondState(any())

        for (policy in listOf(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any())
            assertThat(service.okToConnect(device)).isFalse()
        }
        for (policy in listOf(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any())
            assertThat(service.okToConnect(device)).isTrue()
        }
    }

    @Test
    fun testDumpDoesNotCrash() {
        service.dump(StringBuilder())
    }

    @Test
    fun suspend_shallowSleepConnected() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_LE)
        service.onSuspendStateChange(SHALLOW_SLEEP)

        // Disconnect and allow reconnection
        order
            .verify(nativeInterface)
            .disconnectHid(
                eq(device.getByteAddress()),
                any(),
                eq(TRANSPORT_LE),
                eq(RECONNECT_ALLOWED),
            )
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_shallowSleepDisconnected() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_LE)
        disconnectDevice(order, TRANSPORT_LE)
        service.onSuspendStateChange(SHALLOW_SLEEP)

        // No-op for connection/disconnection.
        order.verify(nativeInterface, never()).disconnectHid(any(), any(), any(), any())
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_deepSleepConnected() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_LE)
        service.onSuspendStateChange(DEEP_SLEEP)

        // Just disconnect and not rearm the connection
        order
            .verify(nativeInterface)
            .disconnectHid(
                eq(device.getByteAddress()),
                any(),
                eq(TRANSPORT_LE),
                eq(RECONNECT_NOT_ALLOWED_TEMPORARY),
            )
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_deepSleepDisconnected() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_LE)
        disconnectDevice(order, TRANSPORT_LE)
        service.onSuspendStateChange(DEEP_SLEEP)

        // Disconnect to remove the accept list, and not rearm the connection
        order
            .verify(nativeInterface)
            .disconnectHid(
                eq(device.getByteAddress()),
                any(),
                eq(TRANSPORT_LE),
                eq(RECONNECT_NOT_ALLOWED_TEMPORARY),
            )
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_awakeConnected() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_LE)
        service.onSuspendStateChange(AWAKE)

        // Already connected - No-op
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
        order.verify(nativeInterface, never()).disconnectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_awakeDisconnected() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_LE)
        disconnectDevice(order, TRANSPORT_LE)
        service.onSuspendStateChange(AWAKE)

        // Initiate background connection
        order
            .verify(nativeInterface)
            .connectHid(eq(device.getByteAddress()), any(), eq(TRANSPORT_LE), eq(false))
        order.verify(nativeInterface, never()).disconnectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_connectedBredr() {
        setupPeerWithUuids(BluetoothUuid.HID)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_BREDR)
        service.onSuspendStateChange(SHALLOW_SLEEP)
        service.onSuspendStateChange(AWAKE)

        // Don't manage BREDR connection
        order.verify(nativeInterface, never()).disconnectHid(any(), any(), any(), any())
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    @Test
    fun suspend_disconnectedBredr() {
        setupPeerWithUuids(BluetoothUuid.HID)
        val order = inOrder(nativeInterface)

        connectDevice(order, TRANSPORT_BREDR)
        disconnectDevice(order, TRANSPORT_BREDR)
        service.onSuspendStateChange(SHALLOW_SLEEP)
        service.onSuspendStateChange(AWAKE)

        // Don't manage BREDR connection
        order.verify(nativeInterface, never()).disconnectHid(any(), any(), any(), any())
        order.verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    @Test
    fun disconnect_withConnectionPolicyAllowed_reconnectAllowed() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)
        connectDevice(order, TRANSPORT_LE)
        doReturn(CONNECTION_POLICY_ALLOWED)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, service.profileId)

        service.disconnect(device)
        TestUtils.syncHandler(looper, 2 /* MESSAGE_DISCONNECT */)

        order
            .verify(nativeInterface)
            .disconnectHid(
                eq(device.getByteAddress()),
                any(),
                eq(TRANSPORT_LE),
                eq(RECONNECT_ALLOWED),
            )
    }

    @Test
    fun disconnect_withConnectionPolicyForbidden_reconnectNotAllowed() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val order = inOrder(nativeInterface)
        connectDevice(order, TRANSPORT_LE)
        doReturn(CONNECTION_POLICY_FORBIDDEN)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, service.profileId)

        service.disconnect(device)
        TestUtils.syncHandler(looper, 2 /* MESSAGE_DISCONNECT */)

        order
            .verify(nativeInterface)
            .disconnectHid(
                eq(device.getByteAddress()),
                any(),
                eq(TRANSPORT_LE),
                eq(RECONNECT_NOT_ALLOWED),
            )
    }

    @Test
    fun setPreferredTransport_switchesTransportAndDisconnectsOldOne() {
        // Setup device that supports both transports
        setupPeerWithUuids(BluetoothUuid.HOGP, BluetoothUuid.HID)
        doReturn(BOND_BONDED).whenever(adapterService).getBondState(device)

        // Connect with TRANSPORT_BREDR first
        service.setPreferredTransport(device, TRANSPORT_BREDR)
        TestUtils.syncHandler(looper, 17 /* MESSAGE_SET_PREFERRED_TRANSPORT */)
        verify(nativeInterface).connectHid(any(), any(), eq(TRANSPORT_BREDR), eq(true))
        service.onConnectStateChanged(
            device.getByteAddress(),
            ADDRESS_TYPE_PUBLIC,
            TRANSPORT_BREDR,
            STATE_CONNECTED,
            0,
        )
        TestUtils.syncHandler(looper, 3 /* MESSAGE_CONNECT_STATE_CHANGED */)
        assertThat(service.getConnectionState(device)).isEqualTo(STATE_CONNECTED)

        // Now switch to LE
        service.setPreferredTransport(device, TRANSPORT_LE)
        TestUtils.syncHandler(looper, 17 /* MESSAGE_SET_PREFERRED_TRANSPORT */)

        // Should disconnect BREDR and connect LE
        val inOrder = inOrder(nativeInterface)
        inOrder
            .verify(nativeInterface)
            .disconnectHid(any(), any(), eq(TRANSPORT_BREDR), eq(RECONNECT_NOT_ALLOWED))
        inOrder.verify(nativeInterface).connectHid(any(), any(), eq(TRANSPORT_LE), eq(true))
    }

    @Test
    fun onConnectStateChanged_forUnknownDeviceAndNotAccepting_disconnects() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        val byteAddress = device.getByteAddress()

        // Send a connect state changed for a device that the service does not know about
        service.onConnectStateChanged(
            byteAddress,
            ADDRESS_TYPE_PUBLIC,
            TRANSPORT_LE,
            STATE_CONNECTED,
            0,
        )
        TestUtils.syncHandler(looper, 3 /* MESSAGE_CONNECT_STATE_CHANGED */)

        // Service should try to disconnect it
        verify(nativeInterface)
            .disconnectHid(eq(byteAddress), any(), eq(TRANSPORT_LE), eq(RECONNECT_NOT_ALLOWED))
    }

    @Test
    fun connect_connectedWithPolicyForbidden_doesNotConnectHid() {
        setupPeerWithUuids(BluetoothUuid.HOGP)
        doReturn(CONNECTION_POLICY_FORBIDDEN)
            .whenever(adapterService)
            .getProfileConnectionPolicy(device, service.profileId)

        service.connect(device)

        verify(nativeInterface, never()).connectHid(any(), any(), any(), any())
    }

    private fun setupPeerWithUuids(vararg uuids: ParcelUuid) {
        doReturn(CONNECTION_POLICY_ALLOWED)
            .whenever(adapterService)
            .getProfileConnectionPolicy(any(), any())
        doReturn(uuids).whenever(adapterService).getRemoteUuids(any())
        doReturn(device).whenever(adapterService).getDeviceFromByte(any())
        doReturn(device.getByteAddress()).whenever(adapterService).getByteBrEdrAddress(device)
    }

    private fun connectDevice(order: InOrder, transport: Int) {
        service.connect(device)
        TestUtils.syncHandler(looper, 1)
        order.verify(nativeInterface).connectHid(any(), any(), any(), any())

        service.onConnectStateChanged(
            device.getByteAddress(),
            ADDRESS_TYPE_PUBLIC,
            transport,
            STATE_CONNECTED,
            0,
        )
        TestUtils.syncHandler(looper, 3)
    }

    private fun disconnectDevice(order: InOrder, transport: Int) {
        service.disconnect(device)
        TestUtils.syncHandler(looper, 2)
        order.verify(nativeInterface).disconnectHid(any(), any(), any(), eq(RECONNECT_ALLOWED))

        service.onConnectStateChanged(
            device.getByteAddress(),
            ADDRESS_TYPE_PUBLIC,
            transport,
            STATE_DISCONNECTED,
            0,
        )
        TestUtils.syncHandler(looper, 3)
    }
}
