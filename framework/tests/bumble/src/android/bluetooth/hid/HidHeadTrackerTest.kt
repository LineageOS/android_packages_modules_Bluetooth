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

package android.bluetooth.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.PandoraDevice
import android.bluetooth.StreamObserverSpliterator
import android.bluetooth.Utils
import android.bluetooth.adapter
import android.bluetooth.setupIntentLogger
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.hamcrest.CustomTypeSafeMatcher
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import org.hamcrest.Matchers.oneOf
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
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import pandora.GattProto
import pandora.GattProto.GattCharacteristicParams
import pandora.HIDGrpc
import pandora.HidProto.HidServiceType
import pandora.HidProto.ServiceRequest
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.DiscoverabilityMode
import pandora.HostProto.OwnAddressType
import pandora.HostProto.SetDiscoverabilityModeRequest
import pandora.SecurityProto.PairingEvent
import pandora.SecurityProto.PairingEventAnswer

private const val TAG = "HidHeadTrackerTest"

@RunWith(AndroidJUnit4::class)
class HidHeadTrackerTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var serviceListener: BluetoothProfile.ServiceListener

    private val pairingEventStreamObserver = StreamObserverSpliterator<Void, PairingEvent>()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var hidBlockingStub: HIDGrpc.HIDBlockingStub
    private lateinit var inOrder: InOrder
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var hidService: BluetoothHidHost
    private lateinit var hfpService: BluetoothHeadset
    private lateinit var a2dpService: BluetoothA2dp

    @Before
    @Throws(Exception::class)
    fun setUp() {
        hidBlockingStub = bumble.hidBlocking()

        inOrder = inOrder(receiver)
        bumbleDevice = bumble.remoteDevice
        // Get profile proxies
        hidService = connectToProfile(BluetoothProfile.HID_HOST) as BluetoothHidHost
        a2dpService = connectToProfile(BluetoothProfile.A2DP) as BluetoothA2dp
        hfpService = connectToProfile(BluetoothProfile.HEADSET) as BluetoothHeadset

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            }

        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        Log.d(TAG, "start tearDown")
        if (bumbleDevice.bondState == BluetoothDevice.BOND_BONDED) {
            removeBond(bumbleDevice)
        }
        context.unregisterReceiver(receiver)
    }

    /**
     * Ensure that successful HID connection over LE Transport.
     *
     * <p>Prerequisites:
     * <ol>
     * <li>Bumble has Android Headtracker Service
     * <li>Bumble does not support HID and HOGP
     * <li>Bumble is connectable over LE
     * </ol>
     *
     * <p>Steps:
     * <ol>
     * <li>Android pairs with Bumble
     * <li>Android Bluetooth reports HID host connection
     * <li>Disconnect and reconnect
     * <li>Android Bluetooth reports HID host connection
     * </ol>
     *
     * Expectation: successful HID connection over LE Transport
     */
    @SuppressLint("MissingPermission")
    @Test
    @Throws(Exception::class)
    fun connectWithoutHidServiceTest() {
        pairAndConnect()

        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTING)
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTED)

        // Disable a2dp and HFP connection policy
        if (a2dpService.getConnectionPolicy(bumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(a2dpService.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue()
        }
        if (hfpService.getConnectionPolicy(bumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(hfpService.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue()
        }

        // Disconnect and Reconnect
        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(TRANSPORT_LE, STATE_DISCONNECTING)
        verifyConnectionState(TRANSPORT_LE, STATE_DISCONNECTED)

        // Wait for ACL to get disconnected
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        // Restart advertise
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.RANDOM)
                    .build()
            )
        // HOGP CONNECTING and ACL CONNECTED has race connection hence unordered here
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTED)
    }

    /**
     * Ensure that successful HID connection over BREDR Transport.
     *
     * <p>Prerequisites:
     * <ol>
     * <li>Bumble has Android Headtracker Service
     * <li>Bumble supports only HID but not HOGP
     * <li>Bumble is connectable over LE
     * </ol>
     *
     * <p>Steps:
     * <ol>
     * <li>Android pairs with Bumble
     * <li>Android Bluetooth reports HID host connection
     * <li>Change the preferred transport to LE
     * <li>Android Bluetooth reports HID host connection over LE
     * </ol>
     *
     * Expectation: successful HID connection over BREDR Transport and Preferred transport selection
     * success
     */
    @SuppressLint("MissingPermission")
    @Test
    @Throws(Exception::class)
    fun connectWithHidServiceTest() {
        hidBlockingStub.registerService(
            ServiceRequest.newBuilder().setServiceType(HidServiceType.SERVICE_TYPE_HID).build()
        )
        pairAndConnect()

        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(TRANSPORT_BREDR, STATE_CONNECTED)
        // Switch to LE Transport
        hidService.setPreferredTransport(bumbleDevice, TRANSPORT_LE)
        verifyTransportSwitch(bumbleDevice, TRANSPORT_BREDR, TRANSPORT_LE)

        // Disconnect
        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(TRANSPORT_LE, STATE_DISCONNECTING)
        verifyConnectionState(TRANSPORT_LE, STATE_DISCONNECTED)

        // Wait for ACL to get disconnected
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
    }

    @Throws(Exception::class)
    private fun pairAndConnect() {
        // Register Head tracker services on Bumble
        val characteristicVersion =
            GattCharacteristicParams.newBuilder()
                .setProperties(
                    BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE
                )
                .setUuid(HEADTRACKER_VERSION_CHARACTERISTIC_UUID.toString())
                .build()
        val characteristicControl =
            GattCharacteristicParams.newBuilder()
                .setProperties(
                    BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE
                )
                .setUuid(HEADTRACKER_CONTROL_CHARACTERISTIC_UUID.toString())
                .build()
        val characteristicReport =
            GattCharacteristicParams.newBuilder()
                .setProperties(
                    BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE
                )
                .setUuid(HEADTRACKER_REPORT_CHARACTERISTIC_UUID.toString())
                .build()
        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .addCharacteristics(characteristicVersion)
                            .addCharacteristics(characteristicControl)
                            .addCharacteristics(characteristicReport)
                            .setUuid(HEADTRACKER_UUID.toString())
                            .build()
                    )
                    .build()
            )

        // Make Bumble discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                    .build()
            )
        // Start Discovery
        assertThat(adapter.startDiscovery()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_FOUND),
            hasExtra(BluetoothDevice.EXTRA_NAME, Utils.BUMBLE_DEVICE_NAME),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        assertThat(adapter.cancelDiscovery()).isTrue()
        // Create Bond
        val pairingEventAnswerObserver =
            bumble
                .security()
                .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(pairingEventStreamObserver)

        // Start pairing from Android with Auto transport
        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )
        verifyIntentReceived(
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
        // Make Bumble connectable with some delay
        Thread.sleep(300)
        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        bumble
            .host()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.RANDOM)
                    .build(),
                responseObserver,
            )
        // Verify  ACL connection on classic transport first and then LE transport
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_LE),
        )
        responseObserver.cancel("Canceling advertise request")
        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(BluetoothDevice.ACTION_UUID),
            hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(HEADTRACKER_UUID)),
        )
    }

    /**
     * CONNECTING and DISCONNECTING intents can go out of order, hence need a special function to
     * verify transport switches if we want to verify connecting and disconnected states
     *
     * <p>Four intents are expected: 1. fromTransport DISCONNECTING 2. toTransport CONNECTING 3.
     * fromTransport DISCONNECTED 4. toTransport CONNECTED
     *
     * <p>Currently, the order of 2 and 3 is unstable and hence we need this method to work with
     * both 2 -> 3 AND 3 -> 2
     *
     * <p>This function is complicated because we cannot mix ordered verification and unordered
     * verification if the same set of argument will appear more than once.
     *
     * @param device target dual mode HID device
     * @param fromTransport from which transport
     * @param toTransport to which transport
     */
    private fun verifyTransportSwitch(
        device: BluetoothDevice,
        fromTransport: Int,
        toTransport: Int,
    ) {
        assertThat(fromTransport).isNotEqualTo(toTransport)

        class Wrapper {
            var state = 0
            var transport = 0
        }
        val wrap = Wrapper()

        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, oneOf(fromTransport, toTransport)),
            hasExtra(BluetoothProfile.EXTRA_STATE, oneOf(STATE_CONNECTING, STATE_DISCONNECTED)),
            object : CustomTypeSafeMatcher<Intent>("retrieve state & transport") {
                override fun matchesSafely(intent: Intent): Boolean {
                    wrap.state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, STATE_CONNECTED)
                    wrap.transport =
                        intent.getIntExtra(
                            BluetoothDevice.EXTRA_TRANSPORT,
                            BluetoothDevice.TRANSPORT_AUTO,
                        )
                    return true
                }
            },
        )
        val state = wrap.state
        val transport = wrap.transport
        assertThat(state).isAnyOf(STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(transport).isAnyOf(TRANSPORT_BREDR, TRANSPORT_LE)

        // Conditionally verify the next intent
        if (transport == fromTransport) {
            assertThat(state).isEqualTo(STATE_DISCONNECTED)
            verifyConnectionState(toTransport, STATE_CONNECTING)
        } else {
            assertThat(state).isEqualTo(STATE_CONNECTING)
            verifyConnectionState(fromTransport, STATE_DISCONNECTED)
        }
        verifyConnectionState(toTransport, STATE_CONNECTED)
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnorderedAtLeast(
        atLeast: Int,
        vararg matchers: Matcher<Intent>,
    ) {
        verify(receiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyConnectionState(transport: Int, state: Int) {
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
            hasExtra(BluetoothProfile.EXTRA_STATE, state),
        )
    }

    private fun connectToProfile(profile: Int): BluetoothProfile {
        adapter.getProfileProxy(context, serviceListener, profile)
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(serviceListener, timeout(INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    private fun removeBond(device: BluetoothDevice) {
        assertThat(device.removeBond()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )
    }

    companion object {
        private val BOND_INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val HEADTRACKER_UUID = ParcelUuid.fromString("109b862f-50e3-45cc-8ea1-ac62de4846d1")
        private val HEADTRACKER_VERSION_CHARACTERISTIC_UUID =
            ParcelUuid.fromString("b4eb9919-a910-46a2-a9dd-fec2525196fd")
        private val HEADTRACKER_CONTROL_CHARACTERISTIC_UUID =
            ParcelUuid.fromString("8584cbb5-2d58-45a3-ab9d-583e0958b067")
        private val HEADTRACKER_REPORT_CHARACTERISTIC_UUID =
            ParcelUuid.fromString("e66dd173-b2ae-4f5a-ae16-0162af8038ae")
    }
}
