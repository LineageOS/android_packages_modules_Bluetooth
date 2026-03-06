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

package com.android.bluetooth.hearingaid

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothHearingAid
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.HEARING_AID
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.bluetooth.BluetoothUuid
import android.content.Intent
import android.media.AudioManager
import android.media.BluetoothProfileConnectionInfo
import android.os.Bundle
import android.os.ParcelUuid
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.ActiveDeviceManager
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getRealDevice
import com.android.bluetooth.mockGetSystemService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Test cases for [HearingAidService]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HearingAidServiceTest {
    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var activeDeviceManager: ActiveDeviceManager
    @Mock private lateinit var nativeInterface: HearingAidNativeInterface
    @Mock private lateinit var audioManager: AudioManager

    private val leftDevice = getRealDevice(43)
    private val rightDevice = getRealDevice(23)
    private val singleDevice = getRealDevice(13)

    private lateinit var service: HearingAidService
    private lateinit var binder: HearingAidServiceBinder
    private lateinit var inOrder: InOrder
    private lateinit var looper: TestLooper

    @Before
    fun setUp() {
        inOrder = Mockito.inOrder(adapterService)
        looper = TestLooper()

        adapterService.mockGetSystemService<AudioManager>(audioManager)

        doReturn(CONNECTION_POLICY_ALLOWED)
            .whenever(adapterService)
            .getProfileConnectionPolicy(any(), any<Int>())
        doReturn(BOND_BONDED).whenever(adapterService).getBondState(any())
        doReturn(arrayOf(BluetoothUuid.HEARING_AID)).whenever(adapterService).getRemoteUuids(any())

        doReturn(true).whenever(nativeInterface).connectHearingAid(any())
        doReturn(true).whenever(nativeInterface).disconnectHearingAid(any())

        service =
            HearingAidService(adapterService, nativeInterface, activeDeviceManager, looper.looper)
        service.isAvailable = true
        binder = service.initBinder() as HearingAidServiceBinder
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @SafeVarargs
    private fun verifyIntentSent(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(adapterService)
            .sendBroadcast(argThat(AllOf.allOf(*matchers)), any<String>(), any<Bundle>())
    }

    private fun verifyConnectionStateIntent(
        device: BluetoothDevice,
        newState: Int,
        prevState: Int,
        stopAudio: Boolean = true,
    ) {
        verifyIntentSent(
            hasAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothProfile.EXTRA_STATE, newState),
            hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState),
        )

        if (newState == STATE_CONNECTED) {
            // ActiveDeviceManager calls setActiveDevice when connected.
            service.setActiveDevice(device)
        } else if (prevState == STATE_CONNECTED) {
            if (service.connectedDevices.isEmpty()) {
                service.removeActiveDevice(stopAudio)
            }
        }
    }

    @Test
    fun getConnectionPolicy() {
        for (policy in
            listOf(
                CONNECTION_POLICY_UNKNOWN,
                CONNECTION_POLICY_FORBIDDEN,
                CONNECTION_POLICY_ALLOWED,
            )) {
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any<Int>())
            assertThat(service.getConnectionPolicy(leftDevice)).isEqualTo(policy)
        }
    }

    @Test
    fun okToConnect_whenNotBonded_returnFalse() {
        val badPolicyValue = 1024
        val badBondState = 42
        for (bondState in listOf(BOND_NONE, BOND_BONDING, badBondState)) {
            doReturn(bondState).whenever(adapterService).getBondState(any())
            for (policy in
                listOf(
                    CONNECTION_POLICY_UNKNOWN,
                    CONNECTION_POLICY_ALLOWED,
                    CONNECTION_POLICY_FORBIDDEN,
                    badPolicyValue,
                )) {
                doReturn(policy)
                    .whenever(adapterService)
                    .getProfileConnectionPolicy(any(), any<Int>())
                assertThat(service.okToConnect(singleDevice)).isFalse()
            }
        }
    }

    @Test
    fun okToConnect_whenBonded() {
        val badPolicyValue = 1024
        for (policy in listOf(CONNECTION_POLICY_FORBIDDEN, badPolicyValue)) {
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any<Int>())
            assertThat(service.okToConnect(singleDevice)).isFalse()
        }
        for (policy in listOf(CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_ALLOWED)) {
            doReturn(policy).whenever(adapterService).getProfileConnectionPolicy(any(), any<Int>())
            assertThat(service.okToConnect(singleDevice)).isTrue()
        }
    }

    @Test
    fun connectToDevice_whenUuidIsMissing_returnFalse() {
        // Return No UUID
        doReturn(arrayOf<ParcelUuid>())
            .whenever(adapterService)
            .getRemoteUuids(any<BluetoothDevice>())

        assertThat(service.connect(leftDevice)).isFalse()
    }

    @Test
    fun connectToDevice_whenPolicyForbid_returnFalse() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
            .whenever(adapterService)
            .getProfileConnectionPolicy(any(), any<Int>())

        assertThat(service.connect(leftDevice)).isFalse()
    }

    @Test
    fun outgoingConnect_whenTimeOut_isDisconnected() {
        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)

        looper.moveTimeForward(HearingAidStateMachine.CONNECT_TIMEOUT.toMillis())
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_DISCONNECTED, STATE_CONNECTING)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun connectLeft_whenInAPair_connectBothDevices() {
        getHiSyncIdFromNative()

        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)
        verifyConnectionStateIntent(rightDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTING)
    }

    @Test
    fun connectDifferentPair_whenConnected_currentIsDisconnected() {
        getHiSyncIdFromNative()

        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        verifyConnectionStateIntent(rightDevice, STATE_CONNECTING, STATE_DISCONNECTED)

        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        generateConnectionMessageFromNative(rightDevice, STATE_CONNECTED, STATE_CONNECTING)

        assertThat(service.connect(singleDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        verifyConnectionStateIntent(rightDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        verifyConnectionStateIntent(singleDevice, STATE_CONNECTING, STATE_DISCONNECTED)

        assertThat(service.connectedDevices).isEmpty()
        assertThat(service.getConnectionState(singleDevice)).isEqualTo(STATE_CONNECTING)
    }

    @Test
    fun disconnect_whenAudioRoutedToHa_audioIsPaused() {
        getHiSyncIdFromNative()

        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()
        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)

        assertThat(service.connect(rightDevice)).isTrue()
        looper.dispatchAll()
        verifyConnectionStateIntent(rightDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTING)

        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        generateConnectionMessageFromNative(rightDevice, STATE_CONNECTED, STATE_CONNECTING)

        assertThat(service.connectedDevices).containsExactly(leftDevice, rightDevice)

        // Verify the audio is routed to Hearing Aid Profile
        verify(audioManager)
            .handleBluetoothActiveDeviceChanged(
                eq(leftDevice),
                eq(null),
                any<BluetoothProfileConnectionInfo>(),
            )

        assertThat(service.disconnect(leftDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTING)

        assertThat(service.disconnect(rightDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(rightDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_DISCONNECTING)

        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING)
        generateConnectionMessageFromNative(rightDevice, STATE_DISCONNECTED, STATE_DISCONNECTING)

        assertThat(service.connectedDevices).isEmpty()

        // Verify the audio is not routed to Hearing Aid Profile.
        // Music should be paused (i.e. should not suppress noisy intent)
        val connectionInfoArgumentCaptor = argumentCaptor<BluetoothProfileConnectionInfo>()
        verify(audioManager)
            .handleBluetoothActiveDeviceChanged(
                eq(null),
                eq(leftDevice),
                connectionInfoArgumentCaptor.capture(),
            )
        val connectionInfo = connectionInfoArgumentCaptor.firstValue
        assertThat(connectionInfo.isSuppressNoisyIntent).isFalse()
    }

    @Test
    fun outgoingDisconnect_whenAudioRoutedToHa_audioIsNotPaused() {
        getHiSyncIdFromNative()

        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()
        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)

        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)

        assertThat(service.connectedDevices).containsExactly(leftDevice)

        // Verify the audio is routed to Hearing Aid Profile
        verify(audioManager)
            .handleBluetoothActiveDeviceChanged(
                eq(leftDevice),
                eq(null),
                any<BluetoothProfileConnectionInfo>(),
            )

        assertThat(service.disconnect(leftDevice)).isTrue()
        looper.dispatchAll()

        // Note that we call verifyConnectionStateIntent() with (stopAudio == false).
        verifyConnectionStateIntent(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED, false)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTING)

        // Verify the audio is not routed to Hearing Aid Profile.
        // Note that music should be not paused (i.e. should suppress noisy intent)
        val connectionInfoArgumentCaptor = argumentCaptor<BluetoothProfileConnectionInfo>()
        verify(audioManager)
            .handleBluetoothActiveDeviceChanged(
                eq(null),
                eq(leftDevice),
                connectionInfoArgumentCaptor.capture(),
            )
        val connectionInfo = connectionInfoArgumentCaptor.firstValue
        assertThat(connectionInfo.isSuppressNoisyIntent).isTrue()
    }

    @Test
    fun incomingConnecting_whenNoDevice_createStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun incomingDisconnect_whenConnectingDevice_keepStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)

        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_CONNECTING)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun incomingConnect_whenNoDevice_createStateMachine() {
        // Theoretically impossible case
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun incomingDisconnect_whenConnectedDevice_keepStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_DISCONNECTED)

        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_CONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun incomingDisconnecting_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(leftDevice, STATE_DISCONNECTING)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
        assertThat(service.getDevices()).doesNotContain(leftDevice)
    }

    @Test
    fun incomingDisconnect_whenNoDevice_noStateMachine() {
        generateUnexpectedConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
        assertThat(service.getDevices()).doesNotContain(leftDevice)
    }

    @Test
    fun unBondDevice_whenConnecting_keepStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)
        assertThat(service.getDevices()).contains(leftDevice)

        service.bondStateChanged(leftDevice, BOND_NONE)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun unBondDevice_whenConnected_keepStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)

        service.bondStateChanged(leftDevice, BOND_NONE)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun unBondDevice_whenDisconnecting_keepStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTING)
        assertThat(service.getDevices()).contains(leftDevice)

        service.bondStateChanged(leftDevice, BOND_NONE)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun unBondDevice_whenDisconnected_removeStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)

        service.bondStateChanged(leftDevice, BOND_NONE)
        assertThat(service.getDevices()).doesNotContain(leftDevice)
    }

    @Test
    fun disconnect_whenBonded_keepStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)
    }

    @Test
    fun disconnect_whenUnBonded_removeStateMachine() {
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)
        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTING, STATE_CONNECTED)
        assertThat(service.getDevices()).contains(leftDevice)

        doReturn(BOND_NONE).whenever(adapterService).getBondState(any())
        service.bondStateChanged(leftDevice, BOND_NONE)
        assertThat(service.getDevices()).contains(leftDevice)

        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING)

        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_DISCONNECTED)
        assertThat(service.getDevices()).doesNotContain(leftDevice)
    }

    @Test
    fun getActiveDevice() {
        getHiSyncIdFromNative()

        generateConnectionMessageFromNative(rightDevice, STATE_CONNECTED, STATE_DISCONNECTED)
        assertThat(service.getActiveDevices()).containsExactly(null, rightDevice)

        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_DISCONNECTED)
        assertThat(service.getActiveDevices()).containsExactly(rightDevice, leftDevice)

        generateConnectionMessageFromNative(rightDevice, STATE_DISCONNECTED, STATE_CONNECTED)
        assertThat(service.getActiveDevices()).containsExactly(null, leftDevice)

        generateConnectionMessageFromNative(leftDevice, STATE_DISCONNECTED, STATE_CONNECTED)
        assertThat(service.getActiveDevices()).containsExactly(null, null)
    }

    @Test
    fun connectNewDevice_whenOtherPairIsActive_newDeviceIsActive() {
        getHiSyncIdFromNative()

        generateConnectionMessageFromNative(rightDevice, STATE_CONNECTED, STATE_DISCONNECTED)
        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_DISCONNECTED)
        assertThat(service.getActiveDevices()).containsExactly(rightDevice, leftDevice)

        generateConnectionMessageFromNative(singleDevice, STATE_CONNECTED, STATE_DISCONNECTED)
        assertThat(service.getActiveDevices()).containsExactly(null, singleDevice)

        assertThat(service.setActiveDevice(null)).isTrue()
        assertThat(service.getActiveDevices()).containsExactly(null, null)
    }

    // Verify the correctness during first time connection.
    // Connect to left device -> Get left device hiSyncId -> Connect to right device ->
    // Get right device hiSyncId -> Both devices should be always connected
    @Test
    fun firstTimeConnection_shouldConnectToBothDevices() {
        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)

        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)

        // Get hiSyncId for left device
        service.onDeviceAvailableFromNative(leftDevice, 0x02, 0x0101)
        looper.dispatchAll()

        assertThat(service.connect(rightDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(rightDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTING)
        // Verify the left device is still connected
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)

        generateConnectionMessageFromNative(rightDevice, STATE_CONNECTED, STATE_CONNECTING)

        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)

        // Get hiSyncId for right device
        service.onDeviceAvailableFromNative(rightDevice, 0x02, 0x0101)
        looper.dispatchAll()

        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)
    }

    @Test
    fun getHiSyncId_afterFirstDeviceConnected() {
        assertThat(service.connect(leftDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(leftDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTING)
        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_DISCONNECTED)

        generateConnectionMessageFromNative(leftDevice, STATE_CONNECTED, STATE_CONNECTING)

        getHiSyncIdFromNative()

        assertThat(service.connect(rightDevice)).isTrue()
        looper.dispatchAll()

        verifyConnectionStateIntent(rightDevice, STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTING)
        // Verify the left device is still connected
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)

        generateConnectionMessageFromNative(rightDevice, STATE_CONNECTED, STATE_CONNECTING)

        assertThat(service.getConnectionState(rightDevice)).isEqualTo(STATE_CONNECTED)
        assertThat(service.getConnectionState(leftDevice)).isEqualTo(STATE_CONNECTED)
    }

    /** Test that the service can update HiSyncId from native message */
    @Test
    fun getHiSyncIdFromNative_addToMap() {
        getHiSyncIdFromNative()
        assertThat(service.hiSyncIdMap).containsKey(leftDevice)
        assertThat(service.hiSyncIdMap).containsKey(rightDevice)
        assertThat(service.hiSyncIdMap).containsKey(singleDevice)

        var id = binder.getHiSyncId(leftDevice, null)
        assertThat(id).isNotEqualTo(BluetoothHearingAid.HI_SYNC_ID_INVALID)

        id = binder.getHiSyncId(rightDevice, null)
        assertThat(id).isNotEqualTo(BluetoothHearingAid.HI_SYNC_ID_INVALID)

        id = binder.getHiSyncId(singleDevice, null)
        assertThat(id).isNotEqualTo(BluetoothHearingAid.HI_SYNC_ID_INVALID)
    }

    /** Test that the service removes the device from HiSyncIdMap when it's unbonded */
    @Test
    fun deviceUnbonded_removeHiSyncId() {
        getHiSyncIdFromNative()
        service.bondStateChanged(leftDevice, BOND_NONE)
        assertThat(service.hiSyncIdMap).doesNotContainKey(leftDevice)
    }

    @Test
    fun serviceBinder_callGetDeviceMode() {
        val mode = binder.getDeviceMode(singleDevice, null)
        // return unknown value if no device connected
        assertThat(mode).isEqualTo(BluetoothHearingAid.MODE_UNKNOWN)
    }

    @Test
    fun serviceBinder_callGetDeviceSide() {
        val side = binder.getDeviceSide(singleDevice, null)

        // return unknown value if no device connected
        assertThat(side).isEqualTo(BluetoothHearingAid.SIDE_UNKNOWN)
    }

    @Test
    fun serviceBinder_setConnectionPolicy() {
        assertThat(binder.setConnectionPolicy(singleDevice, CONNECTION_POLICY_UNKNOWN, null))
            .isTrue()
        verify(adapterService)
            .setProfileConnectionPolicy(singleDevice, HEARING_AID, CONNECTION_POLICY_UNKNOWN)
    }

    @Test
    fun serviceBinder_setVolume() {
        binder.setVolume(0, null)
        verify(nativeInterface).setVolume(0)
    }

    @Test
    fun dump_doesNotCrash() {
        service.connect(singleDevice)
        looper.dispatchAll()

        service.dump(StringBuilder())
    }

    private fun generateConnectionMessageFromNative(
        device: BluetoothDevice,
        newConnectionState: Int,
        oldConnectionState: Int,
    ) {
        service.onConnectionStateChangedFromNative(device, newConnectionState)
        looper.dispatchAll()
        verifyConnectionStateIntent(device, newConnectionState, oldConnectionState)
        assertThat(service.getConnectionState(device)).isEqualTo(newConnectionState)
    }

    private fun generateUnexpectedConnectionMessageFromNative(
        device: BluetoothDevice,
        newConnectionState: Int,
    ) {
        service.onConnectionStateChangedFromNative(device, newConnectionState)
        looper.dispatchAll()
        inOrder
            .verify(adapterService, never())
            .sendBroadcast(
                argThat(hasAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED)),
                any<String>(),
                any<Bundle>(),
            )
    }

    // Emulate hiSyncId map update from native stack
    private fun getHiSyncIdFromNative() {
        service.onDeviceAvailableFromNative(leftDevice, 0x02, 0x0101)
        looper.dispatchAll()
        service.onDeviceAvailableFromNative(rightDevice, 0x03, 0x0101)
        looper.dispatchAll()
        service.onDeviceAvailableFromNative(singleDevice, 0x00, 0x0102)
        looper.dispatchAll()
    }
}
