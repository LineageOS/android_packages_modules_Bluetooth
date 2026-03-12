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

package com.android.bluetooth.btservice

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothProfile
import android.hardware.devicestate.DeviceStateManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterSuspend.AWAKE
import com.android.bluetooth.btservice.AdapterSuspend.DEEP_SLEEP
import com.android.bluetooth.btservice.AdapterSuspend.SHALLOW_SLEEP
import com.android.bluetooth.btservice.RemoteDevices.AclLinkSpec
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.gatt.AdvertiseManager
import com.android.bluetooth.gatt.GattService
import com.android.bluetooth.hid.HidHostService
import com.android.tests.bluetooth.MockitoRule
import java.util.Optional
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

/** Test cases for [AdapterSuspend]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdapterSuspendTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var adapterNativeInterface: AdapterNativeInterface
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var advertiseManager: AdvertiseManager
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    @Mock private lateinit var gattService: GattService
    @Mock private lateinit var remoteDevices: RemoteDevices
    @Mock private lateinit var hidHostService: HidHostService

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val deviceStateManager = context.getSystemService(DeviceStateManager::class.java)

    private lateinit var testLooper: TestLooper
    private lateinit var adapterSuspend: AdapterSuspend

    @Before
    fun setUp() {
        doReturn(adapterNativeInterface).whenever(adapterService).native
        doReturn(Optional.of(gattService)).whenever(adapterService).gattService
        doReturn(advertiseManager).whenever(gattService).advertiseManager
        doReturn(remoteDevices).whenever(adapterService).remoteDevices
        doReturn(Optional.of(hidHostService)).whenever(adapterService).hidHostService

        testLooper = TestLooper()

        adapterSuspend =
            spy(
                AdapterSuspend(
                    adapterService,
                    testLooper.looper,
                    deviceStateManager,
                    true, // disconnectAcl
                    true, // scanModeNone
                    false, // stopLeScan
                    true, // pauseAdvertisement
                )
            )
    }

    @Test
    fun testSuspendWithFlagSuspendDiscoverability() {
        adapterSuspend.handleSuspend(true)

        verify(adapterService).setSuspendState(true)
        if (!Flags.leHidConnectionPolicySuspend()) {
            verify(adapterNativeInterface).setDefaultEventMaskExcept(anyLong(), anyLong())
            verify(adapterNativeInterface).disconnectAllAcls()
            verify(adapterNativeInterface).clearFilterAcceptList()
        } else {
            verify(adapterNativeInterface).setSuspendState(true)
        }
        verify(adapterNativeInterface).clearEventFilter()
    }

    @Test
    fun testResumeWithFlagSuspendDiscoverability() {
        adapterSuspend.handleResume()

        if (!Flags.leHidConnectionPolicySuspend()) {
            verify(adapterNativeInterface).setDefaultEventMaskExcept(0, 0)
            verify(adapterNativeInterface).restoreFilterAcceptList()
        } else {
            verify(adapterNativeInterface).setSuspendState(false)
        }
        verify(adapterNativeInterface).clearEventFilter()
        verify(adapterService).setSuspendState(false)
    }

    @Test
    fun testAudioReconnect() {
        val nullDevices = listOf(null, null)
        val activeDevices = listOf(bluetoothDevice)

        // It's possible that getActiveDevices returns list of nulls.
        // Make sure we save the actual active device, and not the nulls.
        doReturn(nullDevices).whenever(adapterService).getActiveDevices(BluetoothProfile.A2DP)
        doReturn(activeDevices).whenever(adapterService).getActiveDevices(BluetoothProfile.LE_AUDIO)
        adapterSuspend.handleSuspend(true)

        // It is possible to call handleSuspend twice.
        // Make sure we don't accidentally overwrite the saved device with an empty list.
        doReturn(nullDevices).whenever(adapterService).getActiveDevices(BluetoothProfile.LE_AUDIO)
        adapterSuspend.handleSuspend(true)

        // Verify we initiate reconnection attempt on resume.
        adapterSuspend.handleResume()
        verify(adapterService).connectAllEnabledProfiles(bluetoothDevice)
    }

    @Test
    fun testAdvertisementPauseAndResume() {
        adapterSuspend.handleSuspend(true)
        verify(advertiseManager).enterSuspend()
        adapterSuspend.handleResume()
        verify(advertiseManager).exitSuspend()
    }

    @Test
    fun testTwoTasksDisconnectionThenAdvertisement() {
        val audioDevices = listOf(bluetoothDevice)
        doReturn(audioDevices)
            .whenever(adapterService)
            .getConnectedDevicesForProfile(BluetoothProfile.HEARING_AID)

        adapterSuspend.handleSuspend(true)
        verify(adapterService).acquireWakeLock(any())

        // When disconnection task is done, wakelock is not yet released.
        adapterSuspend.profileConnectionStateChanged(
            BluetoothProfile.HEARING_AID,
            bluetoothDevice,
            BluetoothProfile.STATE_CONNECTED,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        verify(adapterService, never()).releaseWakeLock(any())

        // Wakelock is released when both tasks are done.
        adapterSuspend.advertiseSuspendReady()
        verify(adapterService).releaseWakeLock(any())
    }

    @Test
    fun testTwoTasksAdvertisementThenDisconnection() {
        val audioDevices = listOf(bluetoothDevice)
        doReturn(audioDevices)
            .whenever(adapterService)
            .getConnectedDevicesForProfile(BluetoothProfile.HEARING_AID)

        adapterSuspend.handleSuspend(true)
        verify(adapterService).acquireWakeLock(any())

        // When advertisement task is done, wakelock is not yet released.
        adapterSuspend.advertiseSuspendReady()
        verify(adapterService, never()).releaseWakeLock(any())

        // Wakelock is released when both tasks are done.
        adapterSuspend.profileConnectionStateChanged(
            BluetoothProfile.HEARING_AID,
            bluetoothDevice,
            BluetoothProfile.STATE_CONNECTED,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        verify(adapterService).releaseWakeLock(any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    fun testSuspendWaitAclDisconnection() {
        val linkSpec = AclLinkSpec(bluetoothDevice, TRANSPORT_LE)
        doReturn(setOf(linkSpec)).whenever(remoteDevices).connectedDevices

        adapterSuspend.handleSuspend(false)
        verify(adapterService).acquireWakeLock(any())

        // Lock isn't released because ACL is still connected.
        adapterSuspend.advertiseSuspendReady()
        verify(adapterService, never()).releaseWakeLock(any())

        // Lock isn't released if there are more devices to disconnect.
        adapterSuspend.aclDisconnected(bluetoothDevice, TRANSPORT_BREDR)
        verify(adapterService, never()).releaseWakeLock(any())

        // Lock is released if there are no more devices to disconnect.
        // Here we need to change the return value of the mocked getConnectedDevices.
        doReturn(emptySet<Any>()).whenever(remoteDevices).connectedDevices
        adapterSuspend.aclDisconnected(bluetoothDevice, TRANSPORT_LE)
        verify(adapterService).releaseWakeLock(any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    fun testSuspendButNoAcl() {
        doReturn(emptySet<Any>()).whenever(remoteDevices).connectedDevices

        adapterSuspend.handleSuspend(false)
        verify(adapterService).acquireWakeLock(any())

        // Lock is immediately released since there are no devices to disconnect.
        adapterSuspend.advertiseSuspendReady()
        verify(adapterService).releaseWakeLock(any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    fun testNotSuspendingButAclIsDisconnected() {
        doReturn(emptySet<Any>()).whenever(remoteDevices).connectedDevices

        // No suspend related behavior shall be triggered since we're not suspending.
        adapterSuspend.aclDisconnected(bluetoothDevice, TRANSPORT_BREDR)
        verify(adapterService, never()).releaseWakeLock(any())
        verify(adapterService, never()).acquireWakeLock(any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    fun testNonWakeableSuspendWithLeHid() {
        val hidDevices = listOf(bluetoothDevice)
        doReturn(hidDevices)
            .whenever(adapterService)
            .getConnectedDevicesForProfile(BluetoothProfile.HID_HOST)
        doReturn(TRANSPORT_LE).whenever(hidHostService).getPreferredTransport(bluetoothDevice)

        // Setting a "non-wakeable by HID" suspend, this causes deep sleep
        adapterSuspend.handleSuspend(false)
        verify(adapterService).acquireWakeLock(any())

        // Lock isn't released, waiting for HoGP disconnection.
        adapterSuspend.advertiseSuspendReady()
        verify(adapterService, never()).releaseWakeLock(any())

        // Lock is released once profile is disconnected
        adapterSuspend.profileConnectionStateChanged(
            BluetoothProfile.HID_HOST,
            bluetoothDevice,
            BluetoothProfile.STATE_CONNECTED,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        verify(adapterService).releaseWakeLock(any())
        verify(hidHostService).onSuspendStateChange(DEEP_SLEEP)

        adapterSuspend.handleResume()
        verify(hidHostService).onSuspendStateChange(AWAKE)
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_HID_CONNECTION_POLICY_SUSPEND)
    fun testWakeableSuspendWithClassicHid() {
        val hidDevices = listOf(bluetoothDevice)
        doReturn(hidDevices)
            .whenever(adapterService)
            .getConnectedDevicesForProfile(BluetoothProfile.HID_HOST)
        doReturn(TRANSPORT_BREDR).whenever(hidHostService).getPreferredTransport(bluetoothDevice)

        // Setting a "wakeable by HID" suspend, this causes shallow sleep
        adapterSuspend.handleSuspend(true)
        verify(adapterService).acquireWakeLock(any())

        // BREDR HID shouldn't block profile disconnection, so lock is released
        adapterSuspend.advertiseSuspendReady()
        verify(adapterService).releaseWakeLock(any())
        verify(hidHostService).onSuspendStateChange(SHALLOW_SLEEP)

        adapterSuspend.handleResume()
        verify(hidHostService).onSuspendStateChange(AWAKE)
    }
}
