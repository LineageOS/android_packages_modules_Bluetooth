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
import android.bluetooth.Utils
import android.bluetooth.adapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.leAdvertiser
import android.bluetooth.pairing.utils.IntentReceiver
import android.bluetooth.pairing.utils.TestUtil
import android.bluetooth.test_utils.BlockingBluetoothAdapter
import android.bluetooth.test_utils.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.bluetooth.toAddressString
import android.content.Context
import android.os.ParcelUuid
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import io.grpc.Deadline
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.hamcrest.Matchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import pandora.BumbleConfigProto.OverrideRequest
import pandora.BumbleConfigProto.PairingConfig
import pandora.GattProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.ConnectLERequest
import pandora.HostProto.ConnectLEResponse
import pandora.HostProto.ConnectRequest
import pandora.HostProto.ConnectabilityMode
import pandora.HostProto.DisconnectRequest
import pandora.HostProto.OwnAddressType
import pandora.HostProto.ScanRequest
import pandora.HostProto.ScanningResponse
import pandora.HostProto.SetConnectabilityModeRequest
import pandora.RfcommProto
import pandora.SecurityProto.DeleteBondRequest
import pandora.SecurityProto.LESecurityLevel
import pandora.SecurityProto.SecureRequest
import pandora.SecurityProto.SecureResponse
import pandora.SecurityProto.SecurityLevel

private const val TAG = "PairingTest"

@RunWith(TestParameterInjector::class)
class PairingTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val secondBumble = PandoraDevice.createSecondPandoraDevice()
    @get:Rule(order = 4) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /* Util instance for common test steps with current Context reference */
    private lateinit var util: TestUtil
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var remoteLeDevice: BluetoothDevice
    private lateinit var hidService: BluetoothHidHost
    private lateinit var hfpService: BluetoothHeadset
    private lateinit var currentDevice: PandoraDevice

    @Before
    @Throws(Exception::class)
    fun setUp() {
        util =
            TestUtil.Builder(context)
                .setProfileServiceListener(profileServiceListener)
                .setBluetoothAdapter(adapter)
                .build()

        // Get profile proxies
        hidService = util.getProfileProxy(BluetoothProfile.HID_HOST) as BluetoothHidHost
        hfpService = util.getProfileProxy(BluetoothProfile.HEADSET) as BluetoothHeadset
        // switch the bumble devices to avoid profile connection interference
        // caused by settings and system UI
        if (toggleDevice) {
            currentDevice = bumble
            remoteLeDevice =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )
        } else {
            currentDevice = secondBumble
            remoteLeDevice =
                adapter.getRemoteLeDevice(
                    Utils.BUMBLE_RANDOM_ADDRESS_2,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )
        }
        toggleDevice = !toggleDevice
        // Always read fresh address
        val readLocalAddressResponse =
            currentDevice.hostBlocking().readLocalAddress(Empty.getDefaultInstance())
        bumbleDevice = adapter.getRemoteDevice(readLocalAddressResponse.address.toAddressString())
        Log.d(TAG, "Bumble Device: $bumbleDevice")
        Log.d(TAG, "Bumble LE Device: $remoteLeDevice")

        val devName = adapter.name
        // Limit the device name to maximum Le Advertise data
        deviceName =
            if (devName.length > DEVICE_NAME_MAX) devName.take(DEVICE_NAME_MAX) else devName
        /*
         * Note: Since there was no IntentReceiver registered, passing the instance as
         * NULL in removeBond(). But, if there is an instance already present, that
         * must be passed instead of NULL.
         */
        for (device in adapter.bondedDevices) {
            util.removeBond(null, device)
        }
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        for (device in adapter.bondedDevices) {
            util.removeBond(null, device)
        }
    }

    //
    // Process of writing a test function
    //
    // 1. Create an IntentReceiver object first with following way:
    //      IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
    //          BluetoothDevice.ACTION_1,
    //          BluetoothDevice.ACTION_2)
    //          .setIntentListener(--) // optional
    //          .setIntentTimeout(--)  // optional
    //          .build();
    // 2. Use the intentReceiver instance for all Intent related verification, and pass
    //      the same instance to all the helper/testStep functions which has similar Intent
    //      requirements.
    // 3. Once all the verification is done, call `intentReceiver.close()` before returning
    //      from the function.
    //

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     * 1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no input
     * 2. Android tries to create bond via MAC address, emitting bonding intent
     * 3. Android confirms the pairing via pairing request intent
     * 4. Bumble confirms the pairing internally (optional, added only for test confirmation)
     * 5. Android verifies bonded intent
     */
    @Test
    fun testBrEdrPairing_phoneInitiatedBrEdrInquiryOnlyJustWorks() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()

        assertThat(bumbleDevice.createBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        bumbleDevice.setPairingConfirmation(true)

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     * 1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no input
     * 2. Android tries to create bond via MAC address, emitting bonding intent
     * 3. Android confirms the pairing via pairing request intent
     * 4. Android cancel the pairing of unrelated device. verify current pairing is continued and
     *    success.
     * 5. Bumble confirms the pairing internally (optional, added only for test confirmation)
     * 6. Android verifies bonded intent
     */
    @Test
    fun testBrEdrPairing_cancelBond_forUnrelatedDevice() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()

        assertThat(bumbleDevice.createBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        // cancel bonding for unrelated device and verify current pairing continued and success.
        val fakeUnintendedDevice = adapter.getRemoteDevice("51:F7:A8:75:17:01")
        assertThat(fakeUnintendedDevice.cancelBondProcess()).isTrue()
        bumbleDevice.setPairingConfirmation(true)

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    /**
     * Test to verify the remove bond while pairing
     * 1. Initiate BR/EDR pairing.
     * 2. Do not approve/reject pairing from DUT/REF, and keep it idle.
     *
     * Expectation:
     *
     * Pairing should be cancelled. BOND_NONE should be received on DUT, indicating pairing failure.
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.cancel_pairing_while_remove_bond")
    fun testBrEdrPairing_removeBondWhilePairing() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()

        assertThat(bumbleDevice.createBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        assertThat(bumbleDevice.removeBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        intentReceiver.close()
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     * 1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no input
     * 2. Android connects to Bumble via its MAC address
     * 3. Android tries to create bond, emitting bonding intent
     * 4. Android confirms the pairing via pairing request intent
     * 5. Bumble confirms the pairing internally (optional, added only for test confirmation)
     * 6. Android verifies bonded intent
     */
    @Test
    fun testBrEdrPairing_phoneInitiatedBrEdrInquiryOnlyJustWorksWhileSdpConnected() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()

        // Start SDP.  This will create an ACL connection before the bonding starts.
        assertThat(bumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        assertThat(bumbleDevice.createBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        bumbleDevice.setPairingConfirmation(true)

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    /**
     * Test if parallel GATT service discovery interrupts cancelling LE pairing
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     * 2. Bumble has GATT services in addition to GAP and GATT services
     *
     * Steps:
     * 1. Bumble is discoverable and connectable over LE
     * 2. Android connects to Bumble over LE
     * 3. Android starts GATT service discovery
     * 4. Bumble initiates pairing
     * 5. Android does not confirm the pairing immediately
     * 6. Service discovery completes
     * 7. Android cancels the pairing
     *
     * Expectation: Pairing gets cancelled instead of getting timed out
     */
    @Test
    fun testCancelBondLe_WithGattServiceDiscovery() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED).build()

        // Outgoing GATT service discovery and incoming LE pairing in parallel
        val responseObserver =
            helper_OutgoingGattServiceDiscoveryWithIncomingLePairing(intentReceiver)

        // Cancel pairing from Android
        assertThat(bumbleDevice.cancelBondProcess()).isTrue()

        val secureResponse = responseObserver.iterator().next()
        assertThat(secureResponse.hasPairingFailure()).isTrue()

        // Pairing should be cancelled in a moment instead of timing out in 30
        // seconds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        intentReceiver.close()
    }

    /**
     * Test if parallel GATT service discovery interrupts the LE pairing
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     * 2. Bumble has GATT services in addition to GAP and GATT services
     *
     * Steps:
     * 1. Bumble is discoverable and connectable over LE
     * 2. Android connects to Bumble over LE
     * 3. Android starts GATT service discovery
     * 4. Bumble starts pairing
     * 5. Service discovery completes
     * 6. Android does confirms the pairing
     * 7. Pairing is successful
     *
     * Expectation: Pairing succeeds
     */
    @Test
    fun testBondLe_WithGattServiceDiscovery() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED).build()

        // Outgoing GATT service discovery and incoming LE pairing in parallel
        val responseObserver =
            helper_OutgoingGattServiceDiscoveryWithIncomingLePairing(intentReceiver)

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        val secureResponse = responseObserver.iterator().next()
        assertThat(secureResponse.hasSuccess()).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    /**
     * Test if bonded LE device can reconnect after BT restart
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is discoverable and connectable over LE
     * 2. Android pairs with Bumble over LE
     * 3. Android restarts
     * 4. Bumble is connectable over LE
     * 5. Android reconnects to Bumble successfully and re-encrypts the link
     *
     * Expectation: Pairing succeeds
     */
    @Test
    fun testBondLe_Reconnect() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_ACL_CONNECTED).build()

        testStep_BondLe(intentReceiver, bumbleDevice, OwnAddressType.PUBLIC)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        testStep_restartBt()

        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        currentDevice
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(HOGP_UUID.toString())
                            .build()
                    )
                    .build()
            )

        currentDevice
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )

        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        intentReceiver.close()
    }

    /**
     * Test if bonded LE device's identity address and type can be read
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is discoverable and connectable over LE
     * 2. Bumble device's identity address and type unknown
     * 3. Android pairs with Bumble over LE
     * 4. Bumble device's identity address and type are retrievable
     *
     * Expectation: Bumble device's identity address and type are present
     */
    @Test
    fun testBondLe_identityAddressWithType(@TestParameter isRandom: Boolean) {
        if (isRandom) {
            doTestIdentityAddressWithType(remoteLeDevice, OwnAddressType.RANDOM)
        } else {
            doTestIdentityAddressWithType(bumbleDevice, OwnAddressType.PUBLIC)
        }
    }

    /**
     * Test if bonded BR/EDR device can reconnect after BT restart
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is discoverable and connectable over BR/EDR
     * 2. Android pairs with Bumble over BR/EDR
     * 3. Android restarts
     * 4. Bumble is connectable over BR/EDR
     * 5. Android reconnects to Bumble successfully and re-encrypts the link
     *
     * Expectation: Pairing succeeds
     */
    @Test
    fun testBondBredr_Reconnect() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_ACL_CONNECTED).build()

        testStep_BondBredr(intentReceiver)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        testStep_restartBt()

        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        val request =
            SetConnectabilityModeRequest.newBuilder()
                .setMode(ConnectabilityMode.CONNECTABLE)
                .build()
        currentDevice.hostBlocking().setConnectabilityMode(request)
        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        intentReceiver.close()
    }

    /**
     * Test removeDevice API when connected over LE
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is connectable over LE
     * 2. Android pairs with Bumble over LE
     * 3. Call BluetoothDevice.removeBond() API
     * 4. Android disconnects the ACL and removes the bond
     *
     * Expectation: Bumble is not bonded
     */
    @Test
    fun testRemoveBondLe_WhenConnected() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                )
                .build()

        testStep_BondLe(intentReceiver, bumbleDevice, OwnAddressType.PUBLIC)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        assertThat(bumbleDevice.removeBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        assertThat(adapter.bondedDevices).doesNotContain(bumbleDevice)

        intentReceiver.close()
    }

    /**
     * Test bond when encryption failed
     *
     * Prerequisites:
     * 1. Bumble and Android are bonded over LE
     *
     * Steps:
     * 1. Make DUT connectable over LE using connectable advertising
     * 2. Initiate LE connection from Bumble
     * 3. Immediately disconnect from Bumble
     * 4. Wait for disconnection intent on Android
     *
     * Expectation: Devices must remain bonded
     */
    @Test
    fun testBondLePeripheral_WhenEncryptionFail() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_ACL_DISCONNECTED).build()

        testStep_BondLePeripheral(intentReceiver)
        assertThat(adapter.bondedDevices).contains(remoteLeDevice)

        testStep_Advertise(OwnAddressType.RANDOM)
        val leConn = testStep_CreateLeConnection(intentReceiver, OwnAddressType.RANDOM)

        // Disconnect Bumble
        currentDevice
            .hostBlocking()
            .disconnect(DisconnectRequest.newBuilder().setConnection(leConn.connection).build())
        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
        )

        assertThat(adapter.bondedDevices).contains(remoteLeDevice)

        intentReceiver.close()
    }

    /**
     * Test removeDevice API when connected over BR/EDR
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is connectable over BR/EDR
     * 2. Android pairs with Bumble over BR/EDR
     * 3. Call BluetoothDevice.removeBond() API
     * 4. Android disconnects the ACL and removes the bond
     *
     * Expectation: Bumble is not bonded
     */
    @Test
    fun testRemoveBondBredr_WhenConnected() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                )
                .build()

        testStep_BondBredr(intentReceiver)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        assertThat(bumbleDevice.removeBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        assertThat(adapter.bondedDevices).doesNotContain(bumbleDevice)

        intentReceiver.close()
    }

    /**
     * Test removeDevice API when not connected
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     * 2. Bumble supports HOGP
     *
     * Steps:
     * 1. Bumble is connectable over LE
     * 2. Android pairs with Bumble over LE
     * 3. Disconnect the Bumble
     * 4. Call BluetoothDevice.removeBond() API
     * 5. Removes the bond
     *
     * Expectation: Bumble is not bonded
     */
    @Test
    fun testRemoveBondLe_WhenDisconnected() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED,
                )
                .build()

        testStep_BondLe(intentReceiver, bumbleDevice, OwnAddressType.PUBLIC)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        // Wait for profiles to get connected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTED),
        )

        // Disconnect Bumble
        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_DISCONNECTING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_DISCONNECTED),
        )

        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        // As HID reconnection causes additional ACL and profile connection Intents, Close all the
        // Intents and register only Bond state change Intent for remove bond verification

        val bondIntentReceiver =
            IntentReceiver.update(
                intentReceiver,
                IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            )
        // Remove bond
        assertThat(bumbleDevice.removeBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )
        assertThat(adapter.bondedDevices).doesNotContain(bumbleDevice)

        bondIntentReceiver.close()
    }

    /**
     * Test removeDevice API when not connected
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     * 2. Bumble supports HID device role
     *
     * Steps:
     * 1. Bumble is connectable over BR/EDR
     * 2. Android pairs with Bumble over BR/EDR
     * 3. Disconnect the Bumble
     * 4. Call BluetoothDevice.removeBond() API
     * 5. Removes the bond
     *
     * Expectation: Bumble is not bonded
     */
    @Test
    @Throws(Exception::class)
    fun testRemoveBondBredr_WhenDisconnected() {
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

        testStep_BondBredr(null)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        // Wait for profiles to get connected
        // Todo: b/382118305 - due to settings app interference, profile connection initiate twice
        // after bonding. Introduced 2 second delay after first profile connection success
        // (b/378268278)
        val future = CompletableFuture<Int?>()
        future.completeOnTimeout(null, TEST_DELAY_MS.toLong(), TimeUnit.MILLISECONDS).join()

        // Check if A2DP is connected
        val activeDevices = adapter.getActiveDevices(BluetoothProfile.A2DP)
        assertThat(activeDevices).contains(bumbleDevice)

        // Disconnect A2DP
        testStep_A2DPDisconnect(null)

        // Remove bond and verify that it is removed
        util.removeBond(null, bumbleDevice)
        assertThat(adapter.bondedDevices).doesNotContain(bumbleDevice)
    }

    /**
     * Test pending LE L2CAP socket connection and LE pairing
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Make Remote device non-connectable over LE
     * 2. Initiate LE socket connection from DUT to Remote device
     * 3. Initiate LE pairing from DUT to Remote device
     * 4. Start LE Advertisement from Remote device after few seconds
     *
     * Expectation: LE connection should be created and LE Pairing should succeed.
     */
    @Test
    @Throws(Exception::class)
    fun testCreateLeSocket_BondLe() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                )
                .build()

        val bluetoothSocket = bumbleDevice.createL2capChannel(TEST_PSM)

        val executor = Executors.newSingleThreadExecutor()
        val futureSocketConnection: Future<*> = executor.submit {
            try {
                bluetoothSocket.connect()
            } catch (e: IOException) {
                Log.e(TAG, "Exception during socket connection: $e")
            }
        }
        try {
            futureSocketConnection[2, TimeUnit.SECONDS]
        } catch (e: TimeoutException) {
            Log.e(TAG, "Socket connection timed out: $e")
        }
        executor.shutdown()

        bumbleDevice.createBond(BluetoothDevice.TRANSPORT_LE)

        /* Make LE L2CAP socket connection and LE Bond calls to wait for few seconds
        and start LE advertisement from remote device. */
        Thread.sleep(3000)

        // Start LE advertisement from Bumble
        val advRequestBuilder =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.PUBLIC)

        val responseObserver = StreamObserverSpliterator<SecureRequest, AdvertiseResponse>()
        currentDevice.host().advertise(advRequestBuilder.build(), responseObserver)

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        bumbleDevice.setPairingConfirmation(true)

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    /**
     * Ensure that bond loss handling is not enforced for temporarily paired devices
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Create RFCOMM insecure socket between DUT and REF
     * 2. Connect DUT and REF over LE
     * 3. Disconnect LE
     * 4. Disconnect BR/EDR
     * 5. Initiate pairing from REF
     *
     * Expectation: Pairing should not be autonomously rejected
     */
    @Test
    @Throws(Exception::class)
    fun testTemporaryPaired_bondLoss_remoteInitiate() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_ACL_DISCONNECTED).build()

        testStep_Advertise(OwnAddressType.PUBLIC)
        testStep_temporaryBond(intentReceiver)
        val leConn = testStep_CreateLeConnection(intentReceiver, OwnAddressType.PUBLIC)
        // Disconnect Bumble
        currentDevice
            .hostBlocking()
            .disconnect(DisconnectRequest.newBuilder().setConnection(leConn.connection).build())
        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        if (bumbleDevice.isConnected) {
            // Disconnect Bumble
            assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)

            intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            )
        }
        // delete keys at bumble side
        val address = adapter.address.toAddressBytes()
        currentDevice
            .securityStorageBlocking()
            .deleteBond(
                DeleteBondRequest.newBuilder().setPublic(ByteString.copyFrom(address)).build()
            )
        currentDevice.hostBlocking().reset(Empty.getDefaultInstance())
        Thread.sleep(100)
        // Read fresh address
        val readLocalAddressResponse =
            currentDevice.hostBlocking().readLocalAddress(Empty.getDefaultInstance())
        bumbleDevice = adapter.getRemoteDevice(readLocalAddressResponse.address.toAddressString())
        testStep_BondBredrFromRemote(intentReceiver)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
    }

    /**
     * Ensure that bond loss handling is not enforced for temporarily paired devices
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Create RFCOMM insecure socket between DUT and REF
     * 2. Connect DUT and REF over LE
     * 3. Disconnect LE
     * 4. Disconnect BR/EDR
     * 5. Initiate pairing from DUT
     *
     * Expectation: Pairing should not be autonomously rejected
     */
    @Test
    @Throws(Exception::class)
    fun testTemporaryPaired_bondLoss_DutInitiate() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_ACL_DISCONNECTED).build()

        testStep_Advertise(OwnAddressType.PUBLIC)
        testStep_temporaryBond(intentReceiver)
        val leConn = testStep_CreateLeConnection(intentReceiver, OwnAddressType.PUBLIC)
        // Disconnect Bumble
        currentDevice
            .hostBlocking()
            .disconnect(DisconnectRequest.newBuilder().setConnection(leConn.connection).build())
        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        if (bumbleDevice.isConnected) {
            // Disconnect Bumble
            assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
            intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            )
        }
        // delete keys at bumble side
        val address = adapter.address.toAddressBytes()
        currentDevice
            .securityStorageBlocking()
            .deleteBond(
                DeleteBondRequest.newBuilder().setPublic(ByteString.copyFrom(address)).build()
            )
        currentDevice.hostBlocking().reset(Empty.getDefaultInstance())
        Thread.sleep(100)
        // Read fresh address
        val readLocalAddressResponse =
            currentDevice.hostBlocking().readLocalAddress(Empty.getDefaultInstance())
        bumbleDevice = adapter.getRemoteDevice(readLocalAddressResponse.address.toAddressString())
        testStep_BondBredr(intentReceiver)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
    }

    /**
     * Verify isBondingInitiatedLocally()
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Start pairing from DUT over LE transport
     * 2. Approve LE pairing
     * 3. Connect BR/EDR transport
     * 4. Disconnect BR/EDR transport
     * 5. wait till SDP to complete
     * 6. Check return value of BluetoothDevice.isBondingInitiatedLocally()
     *
     * Expectation: BluetoothDevice.isBondingInitiatedLocally() should return true
     */
    @Test
    @Throws(Exception::class)
    fun test_isBondingInitiatedLocally_whenClassicDisconnection() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                )
                .build()
        // Read fresh address
        val readLocalAddressResponse =
            currentDevice.hostBlocking().readLocalAddress(Empty.getDefaultInstance())
        bumbleDevice = adapter.getRemoteDevice(readLocalAddressResponse.address.toAddressString())
        currentDevice
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(HOGP_UUID.toString())
                            .build()
                    )
                    .build()
            )
        // Make Bumble connectable
        currentDevice
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // connect and disconnect the Classic link
        testStep_ConnectDisconnectBredr(intentReceiver)
        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        // Wait for profiles to get connected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTED),
        )
        assertThat(bumbleDevice.isBondingInitiatedLocally).isTrue()
        /* Unregisters all intent actions registered in this function */
        intentReceiver.close()
    }

    /**
     * Verify isBondingInitiatedLocally()
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Start pairing from DUT over Classic transport
     * 2. Approve Classic pairing
     * 3. Connect LE transport
     * 4. Disconnect LE transport
     * 5. wait till SDP to complete
     * 6. Check return value of BluetoothDevice.isBondingInitiatedLocally()
     *
     * Expectation: BluetoothDevice.isBondingInitiatedLocally() should return true
     */
    @Test
    @Throws(Exception::class)
    fun test_isBondingInitiatedLocally_whenLEDisconnection() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                )
                .build()
        // Read fresh address
        val readLocalAddressResponse =
            currentDevice.hostBlocking().readLocalAddress(Empty.getDefaultInstance())
        bumbleDevice = adapter.getRemoteDevice(readLocalAddressResponse.address.toAddressString())
        currentDevice
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(HOGP_UUID.toString())
                            .build()
                    )
                    .build()
            )
        // Make Bumble connectable
        currentDevice
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        Thread.sleep(200)
        // connect and disconnect the LE link
        testStep_ConnectDisconnectLE(intentReceiver)
        // Ensure that pairing succeeds
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice.isBondingInitiatedLocally).isTrue()
        /* Unregisters all intent actions registered in this function */
        intentReceiver.close()
    }

    /**
     * Verify isBondingInitiatedLocally()
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Start pairing from DUT over BREDR transport
     * 2. Approve BREDR pairing
     * 3. Check return value of BluetoothDevice.isBondingInitiatedLocally()
     *
     * Expectation: BluetoothDevice.isBondingInitiatedLocally() should return true
     */
    @Test
    @Throws(Exception::class)
    fun test_isBondingInitiatedLocally_whenBredrBondFromDUT() {
        testStep_BondBredr(null)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice.isBondingInitiatedLocally).isTrue()
    }

    /**
     * Verify isBondingInitiatedLocally()
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Start pairing from DUT over LE transport
     * 2. Approve LE pairing
     * 3. Check return value of BluetoothDevice.isBondingInitiatedLocally()
     *
     * Expectation: BluetoothDevice.isBondingInitiatedLocally() should return true
     */
    @Test
    @Throws(Exception::class)
    fun test_isBondingInitiatedLocally_whenLeBondFromDUT() {
        testStep_BondLe(null, bumbleDevice, OwnAddressType.PUBLIC)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice.isBondingInitiatedLocally).isTrue()
    }

    /**
     * Verify isBondingInitiatedLocally()
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Start pairing from remote over BREDR transport
     * 2. Approve BREDR pairing
     * 3. Check return value of BluetoothDevice.isBondingInitiatedLocally()
     *
     * Expectation: BluetoothDevice.isBondingInitiatedLocally() should return false
     */
    @Test
    @Throws(Exception::class)
    fun test_isBondingInitiatedLocally_whenBredrBondFromRemote() {
        testStep_BondBredrFromRemote(null)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice.isBondingInitiatedLocally).isFalse()
    }

    /**
     * Verify isBondingInitiatedLocally()
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Start pairing from remote over LE transport
     * 2. Approve LE pairing
     * 3. Check return value of BluetoothDevice.isBondingInitiatedLocally()
     *
     * Expectation: BluetoothDevice.isBondingInitiatedLocally() should return false
     */
    @Test
    @Throws(Exception::class)
    fun test_isBondingInitiatedLocally_whenLeBondFromRemote() {
        testStep_BondLeFromRemote(null)
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice.isBondingInitiatedLocally).isFalse()
    }

    /** Helper/testStep functions goes here */

    /**
     * Process of writing a helper/test_step function.
     * 1. All the helper functions should have IntentReceiver instance passed as an argument to them
     *    (if any intents needs to be registered). 2. The caller (if a test function) can initiate a
     *    fresh instance of IntentReceiver and use it for all subsequent helper/testStep
     *    functions. 3. The helper function should first register all required intent actions
     *    through the helper -> IntentReceiver.update() which either modifies the intentReceiver
     *    instance, or creates one (if the caller has passed a `null`). 4. At the end, all functions
     *    should call `intentReceiver.close()` which either unregisters the recent actions, or frees
     *    the original instance as per the call.
     */
    private fun testStep_ConnectDisconnectBredr(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                ),
            )
        val classicConn =
            currentDevice
                .hostBlocking()
                .connect(
                    ConnectRequest.newBuilder()
                        .setAddress(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        // Disconnect Bumble
        currentDevice
            .hostBlocking()
            .disconnect(
                DisconnectRequest.newBuilder().setConnection(classicConn.connection).build()
            )
        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        /* Unregisters all intent actions registered in this function */
        intentReceiver.close()
    }

    private fun testStep_ConnectDisconnectLE(intentReceiver: IntentReceiver) {
        val leConn =
            currentDevice
                .hostBlocking()
                .connectLE(
                    ConnectLERequest.newBuilder()
                        .setOwnAddressType(OwnAddressType.PUBLIC)
                        .setPublic(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        // Disconnect Bumble
        currentDevice
            .hostBlocking()
            .disconnect(DisconnectRequest.newBuilder().setConnection(leConn.connection).build())
        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
    }

    private fun testStep_BondBredr(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                ),
            )

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        /* Unregisters all intent actions registered in this function */
        intentReceiver.close()
    }

    /* Starts outgoing GATT service discovery and incoming LE pairing in parallel */
    private fun helper_OutgoingGattServiceDiscoveryWithIncomingLePairing(
        parentIntentReceiver: IntentReceiver?
    ): StreamObserverSpliterator<SecureRequest, SecureResponse> {
        // Register new actions specific to this helper function
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                    BluetoothDevice.ACTION_UUID,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                ),
            )

        // Register lots of interesting GATT services on Bumble
        for (i in 0 until 40) {
            currentDevice
                .gattBlocking()
                .registerService(
                    GattProto.RegisterServiceRequest.newBuilder()
                        .setService(
                            GattProto.GattServiceParams.newBuilder()
                                .setUuid(BATTERY_UUID.toString())
                                .build()
                        )
                        .build()
                )
        }

        // Start GATT service discovery, this will establish LE ACL
        assertThat(bumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_LE)).isTrue()

        // Make Bumble connectable
        val advertiseResponse =
            currentDevice
                .hostBlocking()
                .advertise(
                    AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.PUBLIC)
                        .build()
                )
                .next()

        // Todo: Unexpected empty ACTION_UUID intent is generated
        intentReceiver.verifyReceived(hasAction(BluetoothDevice.ACTION_UUID))

        // Wait for connection on Android
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )

        // Start pairing from Bumble
        val responseObserver = StreamObserverSpliterator<SecureRequest, SecureResponse>()
        currentDevice
            .security()
            .secure(
                SecureRequest.newBuilder()
                    .setConnection(advertiseResponse.connection)
                    .setLe(LESecurityLevel.LE_LEVEL3)
                    .build(),
                responseObserver,
            )

        // Wait for incoming pairing notification on Android
        // TODO: Order of these events is not deterministic
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Allow participating in the incoming pairing on Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Wait for pairing approval notification on Android
        intentReceiver.verifyReceived(
            2,
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Wait for GATT service discovery to complete on Android
        // so that ACTION_UUID is received here.
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_UUID),
            hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(BATTERY_UUID)),
        )

        intentReceiver.close()
        return responseObserver
    }

    private fun testStep_BondLe(
        parentIntentReceiver: IntentReceiver?,
        device: BluetoothDevice,
        ownAddressType: OwnAddressType,
    ) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                ),
            )

        currentDevice
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(BATTERY_UUID.toString())
                            .build()
                    )
                    .build()
            )
        currentDevice
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(HOGP_UUID.toString())
                            .build()
                    )
                    .build()
            )
        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        currentDevice
            .host()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(ownAddressType)
                    .build(),
                responseObserver,
            )

        assertThat(device.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        responseObserver.cancel("Canceling advertise request")
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(device.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    /*
     * Test A2DP disconnect after bonding.
     * TODO: Should we make this a generic function for all profiles?
     */
    @Throws(Exception::class)
    private fun testStep_A2DPDisconnect(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                ),
            )

        // Wait for profiles to get connected, as this could be called just after bonding.
        Thread.sleep(2000)

        // Disconnect all profiles (A2DP for now)
        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTING),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        // Wait for the ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
        )

        intentReceiver.close()
    }

    private fun doTestIdentityAddressWithType(
        device: BluetoothDevice,
        ownAddressType: OwnAddressType,
    ) {
        var identityAddress = device.identityAddressWithType
        assertThat(identityAddress.address).isNull()
        assertThat(identityAddress.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_UNKNOWN)

        /*
         * Note: Since there was no IntentReceiver registered, passing the
         * instance as NULL. But, if there is an instance already present, that
         * must be passed instead of NULL.
         */
        testStep_BondLe(null, device, ownAddressType)
        assertThat(adapter.bondedDevices).contains(device)

        identityAddress = device.identityAddressWithType
        assertThat(identityAddress.address).isEqualTo(device.address)
        assertThat(identityAddress.addressType)
            .isEqualTo(
                if (ownAddressType == OwnAddressType.RANDOM) BluetoothDevice.ADDRESS_TYPE_RANDOM
                else BluetoothDevice.ADDRESS_TYPE_PUBLIC
            )
    }

    private fun testStep_BondLePeripheral(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                ),
            )

        testStep_Advertise(OwnAddressType.RANDOM)
        val leConn = testStep_CreateLeConnection(intentReceiver, OwnAddressType.RANDOM)

        // Start pairing from Bumble
        val responseObserver = StreamObserverSpliterator<SecureRequest, SecureResponse>()
        currentDevice
            .security()
            .secure(
                SecureRequest.newBuilder()
                    .setConnection(leConn.connection)
                    .setLe(LESecurityLevel.LE_LEVEL3)
                    .build(),
                responseObserver,
            )

        intentReceiver.verifyReceived(
            1,
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceived(
            1,
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(remoteLeDevice.setPairingConfirmation(true)).isTrue()

        val secureResponse = responseObserver.iterator().next()
        assertThat(secureResponse.hasSuccess()).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        // Disconnect Bumble
        currentDevice
            .hostBlocking()
            .disconnect(DisconnectRequest.newBuilder().setConnection(leConn.connection).build())
        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
        )

        intentReceiver.close()
    }

    private fun testStep_CreateLeConnection(
        parentIntentReceiver: IntentReceiver?,
        ownAddressType: OwnAddressType,
    ): ConnectLEResponse {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(context, BluetoothDevice.ACTION_ACL_CONNECTED),
            )
        var deviceAddr: ByteString
        val scanningResponseObserver: StreamObserverSpliterator<ScanRequest, ScanningResponse> =
            StreamObserverSpliterator()
        val deadline = Deadline.after(TIMEOUT_ADVERTISING_MS.toLong(), TimeUnit.MILLISECONDS)
        currentDevice
            .host()
            .withDeadline(deadline)
            .scan(ScanRequest.newBuilder().build(), scanningResponseObserver)
        val scanningResponseIterator = scanningResponseObserver.iterator()

        while (true) {
            if (scanningResponseIterator.hasNext()) {
                val scanningResponse = scanningResponseIterator.next()
                deviceAddr =
                    if (ownAddressType == OwnAddressType.RANDOM) scanningResponse.random
                    else scanningResponse.public
                if (
                    scanningResponse.data.completeLocalName == deviceName ||
                        scanningResponse.data.shortenedLocalName == deviceName
                ) {
                    Log.i(TAG, "Device: found $deviceName")
                    break
                }
            }
        }

        val leConn =
            if (ownAddressType == OwnAddressType.RANDOM) {
                currentDevice
                    .hostBlocking()
                    .connectLE(
                        ConnectLERequest.newBuilder()
                            .setOwnAddressType(ownAddressType)
                            .setRandom(deviceAddr)
                            .build()
                    )
            } else {
                currentDevice
                    .hostBlocking()
                    .connectLE(
                        ConnectLERequest.newBuilder()
                            .setOwnAddressType(ownAddressType)
                            .setPublic(deviceAddr)
                            .build()
                    )
            }
        // Wait for ACL to get connected
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(
                BluetoothDevice.EXTRA_DEVICE,
                if (ownAddressType == OwnAddressType.RANDOM) remoteLeDevice else bumbleDevice,
            ),
        )
        intentReceiver.close()
        return leConn
    }

    private fun testStep_BondBredrFromRemote(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                ),
            )
        val response =
            currentDevice
                .hostBlocking()
                .connect(
                    ConnectRequest.newBuilder()
                        .setAddress(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )
        // Start pairing from Bumble
        val responseObserver = StreamObserverSpliterator<SecureRequest, SecureResponse>()
        currentDevice
            .security()
            .secure(
                SecureRequest.newBuilder()
                    .setConnection(response.connection)
                    .setClassic(SecurityLevel.LEVEL4)
                    .build(),
                responseObserver,
            )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        intentReceiver.close()
    }

    private fun testStep_BondLeFromRemote(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                ),
            )
        testStep_Advertise(OwnAddressType.PUBLIC)
        val leConn =
            currentDevice
                .hostBlocking()
                .connectLE(
                    ConnectLERequest.newBuilder()
                        .setOwnAddressType(OwnAddressType.PUBLIC)
                        .setPublic(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )
        // Start pairing from Bumble
        val responseObserver = StreamObserverSpliterator<SecureRequest, SecureResponse>()
        currentDevice
            .security()
            .secure(
                SecureRequest.newBuilder()
                    .setConnection(leConn.connection)
                    .setLe(LESecurityLevel.LE_LEVEL3)
                    .build(),
                responseObserver,
            )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        intentReceiver.close()
    }

    private fun testStep_temporaryBond(parentIntentReceiver: IntentReceiver?) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                ),
            )
        val request =
            SetConnectabilityModeRequest.newBuilder()
                .setMode(ConnectabilityMode.CONNECTABLE)
                .build()
        currentDevice.hostBlocking().setConnectabilityMode(request)
        val pairingConfig =
            PairingConfig.newBuilder()
                .setBonding(false)
                .setMitm(false)
                .setSc(false)
                .setIdentityAddressType(OwnAddressType.PUBLIC)
                .build()
        val overrideRequest = OverrideRequest.newBuilder().setPairingConfig(pairingConfig).build()
        currentDevice.bumbleConfigBlocking().override(overrideRequest)

        val startServerRequest =
            RfcommProto.StartServerRequest.newBuilder()
                .setName(TEST_SERVER_NAME)
                .setUuid(SERIAL_PORT_UUID)
                .build()
        currentDevice.rfcommBlocking().startServer(startServerRequest)
        try {
            // Create RFCOMM insecure socket to Bumble
            val socket =
                bumbleDevice.createInsecureRfcommSocketToServiceRecord(
                    UUID.fromString(SERIAL_PORT_UUID)
                )
            socket.connect()
            // Verify that Rfcomm Socket is connected
            assertThat(socket.isConnected).isTrue()
        } catch (e: IOException) {
            Log.i(TAG, "Expect socket connection failure: $e")
        }
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )
        intentReceiver.close()
    }

    private fun testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue()
        assertThat(BlockingBluetoothAdapter.enable()).isTrue()
    }

    private fun testStep_Advertise(ownAddressType: OwnAddressType) {
        // Start advertising
        val addrType =
            if (ownAddressType == OwnAddressType.RANDOM)
                AdvertisingSetParameters.ADDRESS_TYPE_RANDOM
            else AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC
        val parameters =
            AdvertisingSetParameters.Builder()
                .setOwnAddressType(addrType)
                .setConnectable(true)
                .build()
        val advertiseData = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val advertisingSetCallback: AdvertisingSetCallback = object : AdvertisingSetCallback() {}
        leAdvertiser.startAdvertisingSet(
            parameters,
            advertiseData,
            null,
            null,
            null,
            0,
            0,
            advertisingSetCallback,
        )
    }

    companion object {
        private const val DEVICE_NAME_MAX = 26
        private const val TEST_DELAY_MS = 2000
        private const val TEST_PSM = 5
        private const val TIMEOUT_ADVERTISING_MS = 1000
        private val BATTERY_UUID = ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        private val HOGP_UUID = ParcelUuid.fromString("00001812-0000-1000-8000-00805F9B34FB")
        private const val SERIAL_PORT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val TEST_SERVER_NAME = "RFCOMM Server"

        private lateinit var deviceName: String
        private var toggleDevice = true
    }
}
