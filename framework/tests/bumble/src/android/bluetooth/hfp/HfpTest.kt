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

package android.bluetooth.hfp

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
import android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED
import android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTING
import android.bluetooth.BluetoothHeadset.STATE_AUDIO_DISCONNECTED
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.EXTRA_STATE
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.PandoraDevice
import android.bluetooth.adapter
import android.bluetooth.setupIntentLogger
import android.bluetooth.test_utils.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.time.Duration
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.junit.MockitoJUnit
import pandora.HFPGrpc
import pandora.HfpProto
import pandora.HostProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType
import pandora.SecurityProto

@RunWith(TestParameterInjector::class)
class HfpTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val permissionRule: AdoptShellPermissionsRule = AdoptShellPermissionsRule()
    @get:Rule(order = 1) val bumble = PandoraDevice()
    @get:Rule(order = 2) val secondBumble = PandoraDevice.createSecondPandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var serviceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var hfBlockingStub: HFPGrpc.HFPBlockingStub
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var secondBumbleDevice: BluetoothDevice
    private lateinit var hfpService: BluetoothHeadset
    private lateinit var a2dpService: BluetoothA2dp
    private lateinit var inOrder: InOrder
    private val TEST_PHONE_NUMBER = "1234567890"

    @Before
    fun setUp() {
        inOrder = inOrder(receiver)

        val filter =
            IntentFilter().apply {
                addAction(ACTION_ACL_DISCONNECTED)
                addAction(ACTION_ACL_CONNECTED)
                addAction(ACTION_BOND_STATE_CHANGED)
                addAction(ACTION_PAIRING_REQUEST)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            }
        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)

        hfpService = connectToProfile(BluetoothProfile.HEADSET) as BluetoothHeadset
        a2dpService = connectToProfile(BluetoothProfile.A2DP) as BluetoothA2dp

        hfBlockingStub = bumble.hfBlocking()
        bumbleDevice = bumble.remoteDevice
        secondBumbleDevice = secondBumble.remoteDevice
        if (bumbleDevice in adapter.bondedDevices) {
            if (bumbleDevice.removeBond()) {
                verifyIntentReceived(
                    hasAction(ACTION_BOND_STATE_CHANGED),
                    hasExtra(EXTRA_DEVICE, bumbleDevice),
                    hasExtra(EXTRA_BOND_STATE, BOND_NONE),
                )
            }
        }
        if (secondBumbleDevice in adapter.bondedDevices) {
            if (secondBumbleDevice.removeBond()) {
                verifyIntentReceived(
                    hasAction(ACTION_BOND_STATE_CHANGED),
                    hasExtra(EXTRA_DEVICE, secondBumbleDevice),
                    hasExtra(EXTRA_BOND_STATE, BOND_NONE),
                )
            }
        }
        // Start advertising from bumble side with address type PUBLIC
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )
        // Start advertising from bumble side with address type PUBLIC
        secondBumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )
    }

    @After
    fun tearDown() {
        removeBond()
        context.unregisterReceiver(receiver)
    }

    @Test
    fun connectAndDisconnectFromAg() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        assertThat(hfpService.disconnect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_DISCONNECTING, bumbleDevice)
        verifyConnectionState(STATE_DISCONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun connectAndDisconnectFromHf() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        // Obtain the connection which will be used for EnableSlc
        val address = ByteString.copyFrom(adapter.address.toAddressBytes())
        val connectRequest = HostProto.ConnectRequest.newBuilder().setAddress(address).build()
        val response = bumble.hostBlocking().connect(connectRequest)

        // Enable Slc from HF/Bumble side
        hfBlockingStub.enableSlcAsHandsfree(
            HfpProto.EnableSlcAsHandsfreeRequest.newBuilder()
                .setConnection(response.connection)
                .build()
        )

        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        // Disable Slc from HF/Bumble side
        hfBlockingStub.disableSlcAsHandsfree(
            HfpProto.DisableSlcAsHandsfreeRequest.newBuilder()
                .setConnection(response.connection)
                .build()
        )

        verifyConnectionState(STATE_DISCONNECTING, bumbleDevice)
        verifyConnectionState(STATE_DISCONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
    }

    /**
     * Test connecting and disconnecting audio (SCO) from the Audio Gateway (AG) side (Android
     * device).
     *
     * <p>Steps:
     * <ol>
     * <li>Call `prepareBumbleDeviceAsBondedAndDisconnected()` to ensure the Bumble device is bonded
     *   and in a disconnected state.
     * <li>Initiate an HFP connection to the Bumble device using `hfpService.connect(bumbleDevice)`.
     * <li>Verify that the final HFP connection state is `STATE_CONNECTED` .
     * <li>Set the Bumble device as the active device for HFP .
     * <li>Force SCO audio to be used to setup SCO without a call .
     * <li>Initiate an audio connection (SCO)
     * <li>Verify that the audio got connected to Bumble
     * <li>Initiate an audio disconnection (SCO) .
     * <li>Verify that the audio got disconnected .
     * </ol>
     *
     * <p>Expectation:
     * <ul>
     * <li>SCO audio connection is successfully established and subsequently disconnected from the
     *   AG side.
     * </ul>
     */
    @Test
    @Throws(Exception::class)
    fun connectAndDisconnectAudioFromAg() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)
        // Allow one second delay to complete  SLC on bumble side
        Thread.sleep(1000)
        assertThat(hfpService.startVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, bumbleDevice)
        verifyAudioState(STATE_AUDIO_CONNECTED, bumbleDevice)
        assertThat(hfpService.stopVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_DISCONNECTED, bumbleDevice)
    }

    /**
     * Test scenario where an SCO (Synchronous Connection-Oriented) audio connection is disconnected
     * while it's in the connecting state to the Bumble device, and then re-connected successfully.
     *
     * <p>Steps:
     * <ol>
     * <li>Call `prepareBumbleDeviceAsBondedAndDisconnected()` to ensure the Bumble device is bonded
     *   and in a disconnected state.
     * <li>Create HFP connection to the Bumble device .
     * <li>Force SCO audio to setup SCO without a call.
     * <li>Set the Bumble device as the active device for HFP .
     * <li>Initiate an audio connection (SCO) using `hfpService.connectAudio()`.
     * <li>**Immediately disconnect the SCO audio while it's still in the `STATE_AUDIO_CONNECTING`
     *   state** using `hfpService.disconnectAudio()`.
     * <li>Verify that the audio state transitions to `STATE_AUDIO_DISCONNECTED` .
     * <li> Create Audio connection to bumble
     * </ol>
     *
     * <p>Expectation:
     * <ul>
     * <li>The initial SCO audio connection attempt is successfully interrupted during the
     *   connecting phase.
     * <li>A subsequent SCO audio connection attempt to the same device succeeds.
     * </ul>
     */
    @Test
    @Throws(Exception::class)
    fun disconnectScoWhileConnectingBumble() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)
        // Disconnect sco to first bumble device while in connecting state
        assertThat(hfpService.startVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, bumbleDevice)
        assertThat(hfpService.disconnectAudio()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyAudioState(STATE_AUDIO_DISCONNECTED, bumbleDevice)

        // Try to connect SCO to Bumble device
        assertThat(hfpService.startVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, bumbleDevice)
        verifyAudioState(STATE_AUDIO_CONNECTED, bumbleDevice)
        assertThat(hfpService.stopVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_DISCONNECTED, bumbleDevice)
    }

    @Test
    @Throws(Exception::class)
    fun disconnectScoWhileConnectingFirstBumbleThenConnectScoToSecondBumble() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        Log.d(TAG, "Start to bond the second device")
        prepareSecondBumbleDeviceAsBondedAndDisconnected()
        assertThat(hfpService.connect(secondBumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, secondBumbleDevice)
        verifyConnectionState(STATE_CONNECTED, secondBumbleDevice)

        assertThat(hfpService.getConnectionState(secondBumbleDevice)).isEqualTo(STATE_CONNECTED)

        // Disconnect sco to first bumble device while in connecting state
        assertThat(hfpService.startVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, bumbleDevice)
        assertThat(hfpService.disconnectAudio()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyAudioState(STATE_AUDIO_DISCONNECTED, bumbleDevice)

        assertThat(hfpService.startVoiceRecognition(secondBumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, secondBumbleDevice)
        verifyAudioState(STATE_AUDIO_CONNECTED, secondBumbleDevice)
        assertThat(hfpService.stopVoiceRecognition(secondBumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_DISCONNECTED, secondBumbleDevice)
    }

    @Test
    @Throws(Exception::class)
    fun switchScoFromFirstBumbleToSecondBumble() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        Log.d(TAG, "Start to bond the second device")
        prepareSecondBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(secondBumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING, secondBumbleDevice)
        verifyConnectionState(STATE_CONNECTED, secondBumbleDevice)
        assertThat(hfpService.getConnectionState(secondBumbleDevice)).isEqualTo(STATE_CONNECTED)

        assertThat(hfpService.startVoiceRecognition(bumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, bumbleDevice)
        verifyAudioState(STATE_AUDIO_CONNECTED, bumbleDevice)

        assertThat(hfpService.setActiveDevice(secondBumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_DISCONNECTED, bumbleDevice)
        assertThat(hfpService.stopVoiceRecognition(bumbleDevice)).isTrue()

        assertThat(hfpService.startVoiceRecognition(secondBumbleDevice)).isTrue()
        verifyAudioState(STATE_AUDIO_CONNECTING, secondBumbleDevice)
        verifyAudioState(STATE_AUDIO_CONNECTED, secondBumbleDevice)
    }

    private fun prepareBumbleDeviceAsBondedAndDisconnected() {
        if (bumbleDevice.bondState == BOND_BONDED) {
            assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
            return
        }

        assertThat(bumbleDevice.createBond(TRANSPORT_BREDR)).isTrue()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(ACTION_PAIRING_REQUEST),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
        )
        bumbleDevice.setPairingConfirmation(true)
        verifyIntentReceivedUnordered(
            hasAction(ACTION_ACL_CONNECTED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_LE),
        )
        restartSettingsApp()

        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDED),
        )
        if (a2dpService.getConnectionPolicy(bumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(a2dpService.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue()
        }
        // Connect HFP
        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(STATE_CONNECTING, bumbleDevice)
        verifyConnectionState(STATE_CONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(STATE_DISCONNECTING, bumbleDevice)
        verifyConnectionState(STATE_DISCONNECTED, bumbleDevice)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
        verifyIntentReceived(
            hasAction(ACTION_ACL_DISCONNECTED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )
    }

    private fun prepareSecondBumbleDeviceAsBondedAndDisconnected() {
        if (secondBumbleDevice.bondState == BOND_BONDED) {
            assertThat(hfpService.getConnectionState(secondBumbleDevice))
                .isEqualTo(STATE_DISCONNECTED)
            return
        }

        assertThat(secondBumbleDevice.createBond(TRANSPORT_BREDR)).isTrue()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, secondBumbleDevice),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(ACTION_PAIRING_REQUEST),
            hasExtra(EXTRA_DEVICE, secondBumbleDevice),
        )
        secondBumbleDevice.setPairingConfirmation(true)
        verifyIntentReceivedUnordered(
            hasAction(ACTION_ACL_CONNECTED),
            hasExtra(EXTRA_DEVICE, secondBumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_LE),
        )
        restartSettingsApp()

        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, secondBumbleDevice),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDED),
        )

        if (a2dpService.getConnectionPolicy(secondBumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(
                    a2dpService.setConnectionPolicy(secondBumbleDevice, CONNECTION_POLICY_FORBIDDEN)
                )
                .isTrue()
        }
        // Connect HFP
        assertThat(secondBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(STATE_CONNECTING, secondBumbleDevice)
        verifyConnectionState(STATE_CONNECTED, secondBumbleDevice)
        assertThat(hfpService.getConnectionState(secondBumbleDevice)).isEqualTo(STATE_CONNECTED)

        assertThat(secondBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(STATE_DISCONNECTING, secondBumbleDevice)
        verifyConnectionState(STATE_DISCONNECTED, secondBumbleDevice)
        assertThat(hfpService.getConnectionState(secondBumbleDevice)).isEqualTo(STATE_DISCONNECTED)
        verifyIntentReceived(
            hasAction(ACTION_ACL_DISCONNECTED),
            hasExtra(EXTRA_DEVICE, secondBumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )
    }

    private fun restartSettingsApp() {
        // Restart settings and system UI after ACL connection to avoid auto profile connection
        // which leads test failure
        Runtime.getRuntime().exec("am crash com.android.systemui").waitFor()
        Runtime.getRuntime().exec("am crash com.android.settings").waitFor()
    }

    private fun removeBond() {
        if (bumbleDevice.removeBond()) {
            verifyIntentReceived(
                hasAction(ACTION_BOND_STATE_CHANGED),
                hasExtra(EXTRA_DEVICE, bumbleDevice),
                hasExtra(EXTRA_BOND_STATE, BOND_NONE),
            )
        }
        if (secondBumbleDevice.removeBond()) {
            verifyIntentReceived(
                hasAction(ACTION_BOND_STATE_CHANGED),
                hasExtra(EXTRA_DEVICE, secondBumbleDevice),
                hasExtra(EXTRA_BOND_STATE, BOND_NONE),
            )
        }
        // Remove the bond on the Bumble device as well.
        val localAddress = ByteString.copyFrom(adapter.address.toAddressBytes())
        bumble
            .securityStorageBlocking()
            .deleteBond(
                SecurityProto.DeleteBondRequest.newBuilder().setPublic(localAddress).build()
            )
        secondBumble
            .securityStorageBlocking()
            .deleteBond(
                SecurityProto.DeleteBondRequest.newBuilder().setPublic(localAddress).build()
            )
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnordered(num: Int, vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()).times(num))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnordered(vararg matchers: Matcher<Intent>) {
        verifyIntentReceivedUnordered(1, *matchers)
    }

    private fun verifyConnectionState(state: Int, device: BluetoothDevice) {
        verifyIntentReceived(
            hasAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_STATE, state),
        )
    }

    private fun verifyAudioState(state: Int, device: BluetoothDevice) {
        verifyIntentReceived(
            hasAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_STATE, state),
        )
    }

    private fun connectToProfile(profile: Int): BluetoothProfile {
        adapter.getProfileProxy(context, serviceListener, profile)
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(serviceListener, timeout(INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private const val TAG = "HfpTest"
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
    }
}
