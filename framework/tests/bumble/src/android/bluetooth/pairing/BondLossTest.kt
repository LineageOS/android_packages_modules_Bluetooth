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

package android.bluetooth.pairing

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.PandoraDevice
import android.bluetooth.StreamObserverSpliterator
import android.bluetooth.adapter
import android.bluetooth.test_utils.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.whenever
import pandora.HostProto.ConnectRequest
import pandora.HostProto.ConnectabilityMode
import pandora.HostProto.SetConnectabilityModeRequest
import pandora.SecurityProto.DeleteBondRequest
import pandora.SecurityProto.PairingEvent
import pandora.SecurityProto.PairingEventAnswer
import pandora.SecurityProto.SecureRequest
import pandora.SecurityProto.SecureResponse
import pandora.SecurityProto.SecurityLevel

@RunWith(AndroidJUnit4::class)
class BondLossTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3)
    val enableBluetoothRule =
        EnableBluetoothRule(false /* enableTestMode */, true /* toggleBluetooth */)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val actionRegistrationCounts = mutableMapOf<String, Int>()
    private val pairingEventStreamObserver = StreamObserverSpliterator<Void, PairingEvent>()
    private lateinit var inOrder: InOrder
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var hidService: BluetoothHidHost
    private lateinit var hfpService: BluetoothHeadset
    private lateinit var pairingEventAnswerObserver: StreamObserver<PairingEventAnswer>

    @Before
    fun setUp() {
        doAnswer {
                val intent = it.getArgument<Intent>(1)
                val action = intent.action
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    Log.d(TAG, "onReceive(): bondState=$bondState")
                }
            }
            .whenever(receiver)
            .onReceive(any(), any())

        inOrder = inOrder(receiver)

        // Get profile proxies
        hidService = getProfileProxy(BluetoothProfile.HID_HOST) as BluetoothHidHost
        hfpService = getProfileProxy(BluetoothProfile.HEADSET) as BluetoothHeadset

        bumbleDevice = bumble.remoteDevice
        if (adapter.bondedDevices.contains(bumbleDevice)) {
            removeBond(bumbleDevice)
        }
    }

    @After
    fun tearDown() {
        if (adapter.bondedDevices.contains(bumbleDevice)) {
            removeBond(bumbleDevice)
        }
        if (actionRegistrationCounts.isNotEmpty()) {
            context.unregisterReceiver(receiver)
            actionRegistrationCounts.clear()
        }
    }

    @RequiresFlagsDisabled("android.bluetooth.platform.flags.autonomous_repairing_initiation")
    @Test
    fun testBondBredrBondLoss_Keymissing() {
        registerIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_KEY_MISSING,
        )

        testStep_BondBredr()
        val address = adapter.address.toAddressBytes()
        bumble
            .securityStorageBlocking()
            .deleteBond(
                DeleteBondRequest.newBuilder().setPublic(ByteString.copyFrom(address)).build()
            )
        bumble.hostBlocking().reset(Empty.getDefaultInstance())

        val request =
            SetConnectabilityModeRequest.newBuilder()
                .setMode(ConnectabilityMode.CONNECTABLE)
                .build()
        bumble.hostBlocking().setConnectabilityMode(request)
        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_KEY_MISSING),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        // Wait for the ACL to get disconnected
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        verifyNoMoreInteractions(receiver)
        unregisterIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_KEY_MISSING,
        )
    }

    @RequiresFlagsDisabled("android.bluetooth.platform.flags.autonomous_repairing_initiation")
    @Test
    fun testBondBredrBondLoss_RemoteInitiatedPairing() {
        registerIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_KEY_MISSING,
        )

        testStep_BondBredr()
        val address = adapter.address.toAddressBytes()
        bumble
            .securityStorageBlocking()
            .deleteBond(
                DeleteBondRequest.newBuilder().setPublic(ByteString.copyFrom(address)).build()
            )
        bumble.hostBlocking().reset(Empty.getDefaultInstance())

        val connRsp =
            bumble
                .hostBlocking()
                .connect(
                    ConnectRequest.newBuilder().setAddress(ByteString.copyFrom(address)).build()
                )
        // Start pairing from Bumble
        val responseObserver = StreamObserverSpliterator<SecureRequest, SecureResponse>()
        bumble
            .security()
            .secure(
                SecureRequest.newBuilder()
                    .setConnection(connRsp.connection)
                    .setClassic(SecurityLevel.LEVEL3)
                    .build(),
                responseObserver,
            )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_KEY_MISSING),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        // Wait for the ACL to get disconnected
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        verifyNoMoreInteractions(receiver)
        unregisterIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_KEY_MISSING,
        )
    }

    private fun testStep_BondBredr() {
        registerIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )

        pairingEventAnswerObserver =
            bumble
                .security()
                .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(pairingEventStreamObserver)

        // Disable all profiles other than A2DP as profile connections take too long
        assertThat(
                hfpService.setConnectionPolicy(
                    bumbleDevice,
                    BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                )
            )
            .isTrue()
        assertThat(
                hidService.setConnectionPolicy(
                    bumbleDevice,
                    BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                )
            )
            .isTrue()

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        verifyIntentReceivedUnordered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        verifyIntentReceivedUnordered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        val pairingEvent = pairingEventStreamObserver.iterator().next()
        assertThat(pairingEvent.hasJustWorks()).isTrue()
        pairingEventAnswerObserver.onNext(
            PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build()
        )

        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        // Disable all profiles other than A2DP as profile connections take too long
        assertThat(
                hfpService.setConnectionPolicy(
                    bumbleDevice,
                    BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                )
            )
            .isTrue()
        assertThat(
                hidService.setConnectionPolicy(
                    bumbleDevice,
                    BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                )
            )
            .isTrue()
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        // Wait for profiles to get connected
        verifyIntentReceived(
            hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTING),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        verifyIntentReceived(
            hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        // Disconnect all profiles
        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyIntentReceived(
            hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTING),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        verifyIntentReceived(
            hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        // Wait for the ACL to get disconnected
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        verifyNoMoreInteractions(receiver)
        unregisterIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )
    }

    private fun removeBond(device: BluetoothDevice) {
        registerIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED)

        assertThat(device.removeBond()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        unregisterIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnordered(num: Int, vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(BOND_INTENT_TIMEOUT.toMillis()).times(num))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnordered(vararg matchers: Matcher<Intent>) {
        verifyIntentReceivedUnordered(1, *matchers)
    }

    private fun registerIntentActions(vararg actions: String) {
        if (actions.isEmpty()) {
            return
        }
        if (actionRegistrationCounts.isNotEmpty()) {
            Log.d(TAG, "registerIntentActions(): unregister ALL intents")
            context.unregisterReceiver(receiver)
        }
        for (action in actions) {
            actionRegistrationCounts[action] = actionRegistrationCounts.getOrDefault(action, 0) + 1
        }
        val filter = IntentFilter()
        actionRegistrationCounts.entries
            .filter { it.value > 0 }
            .forEach {
                Log.d(TAG, "registerIntentActions(): Registering action = ${it.key}")
                filter.addAction(it.key)
            }
        context.registerReceiver(receiver, filter)
    }

    private fun unregisterIntentActions(vararg actions: String) {
        if (actions.isEmpty() || actionRegistrationCounts.isEmpty()) {
            return
        }
        Log.d(TAG, "unregisterIntentActions(): unregister ALL intents")
        context.unregisterReceiver(receiver)
        for (action in actions) {
            if (!actionRegistrationCounts.containsKey(action)) {
                continue
            }
            actionRegistrationCounts[action] = actionRegistrationCounts.getOrDefault(action, 0) - 1
            if (actionRegistrationCounts.getOrDefault(action, 0) <= 0) {
                actionRegistrationCounts.remove(action)
            }
        }
        if (actionRegistrationCounts.isNotEmpty()) {
            val filter = IntentFilter()
            actionRegistrationCounts.entries
                .filter { it.value > 0 }
                .forEach {
                    Log.d(TAG, "unregisterIntentActions(): Registering action = ${it.key}")
                    filter.addAction(it.key)
                }
            context.registerReceiver(receiver, filter)
        }
    }

    private fun getProfileProxy(profile: Int): BluetoothProfile {
        adapter.getProfileProxy(context, profileServiceListener, profile)
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(profileServiceListener, timeout(BOND_INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private const val TAG = "BondLossTest"
        private val BOND_INTENT_TIMEOUT = Duration.ofSeconds(10)
    }
}
