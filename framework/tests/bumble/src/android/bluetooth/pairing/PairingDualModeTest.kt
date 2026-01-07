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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.PandoraDevice
import android.bluetooth.StreamObserverSpliterator
import android.bluetooth.Utils
import android.bluetooth.adapter
import android.bluetooth.pairing.utils.IntentReceiver
import android.bluetooth.pairing.utils.TestUtil
import android.bluetooth.test_utils.BlockingBluetoothAdapter
import android.bluetooth.test_utils.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.bluetooth.toAddressString
import android.content.Context
import android.os.ParcelUuid
import android.platform.test.annotations.RequiresFlagsEnabled
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.android.bluetooth.flags.Flags
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import pandora.HostProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.OwnAddressType
import pandora.SecurityProto

private const val TAG = "PairingDualModeTest"

@RunWith(TestParameterInjector::class)
class PairingDualModeTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 1) val bumble = PandoraDevice()
    @get:Rule(order = 2) val secondBumble = PandoraDevice.createSecondPandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var util: TestUtil
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var remoteLeDevice: BluetoothDevice
    private lateinit var currentDevice: PandoraDevice

    @Before
    @Throws(Exception::class)
    fun setUp() {
        util =
            TestUtil.Builder(context)
                .setProfileServiceListener(profileServiceListener)
                .setBluetoothAdapter(adapter)
                .build()
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
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        Log.i(TAG, "Tearing Down")
        for (device in adapter.bondedDevices) {
            util.removeBond(null, device)
        }
    }

    /**
     * Test the scenario where bonding is initiated over BR/EDR, When the DUT and REF is bonded over
     * LE
     *
     * Prerequisites:
     * 1. Bumble is advertising over LE with a random address and is connectable.
     *
     * Steps:
     * 1. Create bonding with the Bumble LE device (`remoteLeDevice`) over the LE transport.
     * 2. Verify that the list of bonded devices on the Android adapter now includes
     *    `remoteLeDevice`.
     * 3. Initiate bonding with the Bumble device (`bumbleDevice`) over the BR/EDR transport.
     * 4. Verify the bonding intents received during the BR/EDR bonding process using
     *    [testStep_VerifyBondIntents].
     * 5. Ensure that the BR/EDR bonding succeeds by checking for the
     *    [BluetoothDevice.ACTION_BOND_STATE_CHANGED] intent with the [BluetoothDevice.BOND_BONDED]
     *    state for `bumbleDevice`.
     * 6. Verify that the list of bonded devices on the Android adapter now includes `bumbleDevice`.
     *
     * Expectation: Bonding with the Bumble device over BR/EDR succeeds after the LE bonding.
     */
    @Test
    fun testBondLe_InitiateBrEdrPairingFromDUT() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()
        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        // Start advertising for LE
        currentDevice
            .host()
            .advertise(
                HostProto.AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                    .build(),
                responseObserver,
            )
        // Create bond over LE transport
        assertThat(remoteLeDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        // Verify bonding intents
        testStep_VerifyBondIntents(intentReceiver, remoteLeDevice, BluetoothDevice.TRANSPORT_LE)
        responseObserver.cancel("Canceling advertise request")

        // Approve pairing from Android
        assertThat(remoteLeDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        // Check if bonded device's list contains REF device
        assertThat(adapter.bondedDevices).contains(remoteLeDevice)

        // Create bond over BR/EDR
        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        // Verify bonding intents
        testStep_VerifyBondIntents(intentReceiver, bumbleDevice, BluetoothDevice.TRANSPORT_BREDR)

        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        // Check if bonded device's list contains REF device
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        intentReceiver.close()
    }

    /**
     * Test the scenario where DUT is bonded with Bumble REF over LE, Pairing initiated by REF
     * device over BR/EDR.
     *
     * Prerequisites:
     * 1. No existing bond between Android and Bumble or `remoteLeDevice`.
     *
     * Steps:
     * 1. Create bonding with the remote LE device (`remoteLeDevice`) over the LE transport from the
     *    DUT.
     * 2. Verify that the list of bonded devices on the Android adapter now includes
     *    `remoteLeDevice`.
     * 3. Initiate bonding with the Bumble device (`bumbleDevice`) over the BR/EDR transport from
     *    the remote (Bumble) side using
     * 4. Ensure that the BR/EDR bonding succeeds by checking for the
     *    [BluetoothDevice.ACTION_BOND_STATE_CHANGED] intent with the [BluetoothDevice.BOND_BONDED]
     *    state for `bumbleDevice`.
     * 5. Verify that the list of bonded devices on the Android adapter now includes `bumbleDevice`.
     *
     * Expectation: Bonding with the Bumble device over BR/EDR initiated by the remote succeeds
     * after the LE bonding.
     */
    @Test
    fun testBondLe_InitiateBrEdrPairingFromREF() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()
        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        // Start advertising for LE
        currentDevice
            .host()
            .advertise(
                HostProto.AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                    .build(),
                responseObserver,
            )

        // Create bond over LE transport
        assertThat(remoteLeDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        // Verify bonding intents
        testStep_VerifyBondIntents(intentReceiver, remoteLeDevice, BluetoothDevice.TRANSPORT_LE)
        responseObserver.cancel("Canceling advertise request")

        // Approve pairing from Android
        assertThat(remoteLeDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        // verify that devices is the bonded list
        assertThat(adapter.bondedDevices).contains(remoteLeDevice)

        // Start bonding from remote side
        testStep_BondBredrFromRemote(intentReceiver)

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        intentReceiver.close()
    }

    /**
     * Test the reconnection over BR/EDR transport after a Bluetooth restart and verify link
     * encryption.
     *
     * Steps:
     * 1. Bond Android and Bumble over BR/EDR
     * 2. Verify that the link is encrypted
     * 3. Restart the Bluetooth adapter using the [testStep_restartBt] helper method.
     * 4. Initiate a connection from the Bumble side to the Android device.
     * 5. Verify that the link is encrypted
     *
     * Expectation: After a Bluetooth restart, Bumble can successfully reconnect to the Android
     * device over BR/EDR. After restart link is encryption.
     */
    @Test
    @Throws(Exception::class)
    fun testReconnection_OverTransportBrEdr() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ENCRYPTION_CHANGE,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        testStep_VerifyBondIntents(intentReceiver, bumbleDevice, BluetoothDevice.TRANSPORT_BREDR)

        bumbleDevice.setPairingConfirmation(true)

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true),
        )

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        testStep_restartBt()

        // Create connection from Bumble side
        val address = ByteString.copyFrom(adapter.address.toAddressBytes())
        val connectionRequest = HostProto.ConnectRequest.newBuilder().setAddress(address).build()
        val response = currentDevice.hostBlocking().connect(connectionRequest)

        assertThat(response.hasConnection()).isTrue()

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        // Verify the link encryption after restart
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true),
        )

        intentReceiver.close()
    }

    /**
     * Test that the properties of a bonded BR/EDR device remain intact after a Bluetooth restart.
     *
     * Steps:
     * 1. Bond Android and Bumble over BR/EDR using the [testStep_BondBrEdr] helper method.
     * 2. Retrieve and store the following properties of the bonded Bumble device: Device type
     *    ([BluetoothDevice.getType]) Device name ([BluetoothDevice.getName]) Device address
     *    ([BluetoothDevice.getAddress]) Device address type ([BluetoothDevice.getAddressType])
     *    Active audio device policy ([BluetoothDevice.getActiveAudioDevicePolicy]) Bond state
     *    ([BluetoothDevice.getBondState]) UUIDs ([BluetoothDevice.getUuids]) identityAddress
     *    ([BluetoothDevice.getIdentityAddress]) identityAddressWithType
     *    ([BluetoothDevice.getIdentityAddressWithType]) class of device
     *    ([BluetoothDevice.getBluetoothClass]) alias
     * 3. Restart the Bluetooth adapter using the [testStep_restartBt] helper method.
     * 4. Retrieve the properties of the Bumble device again after the restart.
     *
     * Expectation: All retrieved properties of the bonded Bumble device (type, name, address,
     * address type, active audio device policy, bond state, and UUIDs) remain the same after the
     * Bluetooth restart.
     */
    @Test
    @Throws(Exception::class)
    fun testProperties_IntactAfterRestart() {
        testStep_BondBrEdr(null)
        // Retrieve all the properties from remote device
        val type = bumbleDevice.type
        val name = bumbleDevice.name
        val address = bumbleDevice.address
        val addressType = bumbleDevice.addressType
        val deviceAudioPolicy = bumbleDevice.activeAudioDevicePolicy
        val bondState = bumbleDevice.bondState
        val uuids: Array<ParcelUuid>? = bumbleDevice.uuids
        val identityAddress = bumbleDevice.identityAddress
        val identityAddressWithType = bumbleDevice.identityAddressWithType
        val cod = bumbleDevice.bluetoothClass
        bumbleDevice.alias = BUMBLE_ALIAS

        testStep_restartBt()
        assertThat(adapter.bondedDevices).contains(bumbleDevice)

        // Verify properties after restart
        assertThat(type).isEqualTo(bumbleDevice.type)
        assertThat(name).isEqualTo(bumbleDevice.name)
        assertThat(address).isEqualTo(bumbleDevice.address)
        assertThat(addressType).isEqualTo(bumbleDevice.addressType)
        assertThat(deviceAudioPolicy).isEqualTo(bumbleDevice.activeAudioDevicePolicy)
        assertThat(bondState).isEqualTo(bumbleDevice.bondState)
        assertThat(uuids).isEqualTo(bumbleDevice.uuids)
        assertThat(identityAddress).isEqualTo(bumbleDevice.identityAddress)
        assertThat(identityAddressWithType.addressType)
            .isEqualTo(bumbleDevice.identityAddressWithType.addressType)
        assertThat(identityAddressWithType.address)
            .isEqualTo(bumbleDevice.identityAddressWithType.address)
        assertThat(cod).isEqualTo(bumbleDevice.bluetoothClass)
        assertThat(bumbleDevice.alias).isEqualTo(BUMBLE_ALIAS)
    }

    /**
     * Tests that a bonded device's bond state remains bonded even after an immediate disconnection
     * from Bumble.
     *
     * steps:
     * 1. Initiates a BR/EDR bonding with Bumble
     * 2. Disconnects from Bumble device
     * 3. Reconnect from Bumble side
     * 4. Disconnect from Bumble
     *
     * Expectation: After immediate disconnection Bumble should still be bonded
     */
    @Test
    @Throws(Exception::class)
    fun testBondState_OnImmediateDisconnectionFromRef() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                )
                .build()

        val response =
            bumble
                .hostBlocking()
                .connect(
                    HostProto.ConnectRequest.newBuilder()
                        .setAddress(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        // Create bond over BR/EDR
        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

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
        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        // Disconnect Bumble
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder().setConnection(response.connection).build()
            )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        val secondResponse =
            bumble
                .hostBlocking()
                .connect(
                    HostProto.ConnectRequest.newBuilder()
                        .setAddress(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        // Disconnect Bumble
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .setConnection(secondResponse.connection)
                    .build()
            )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        assertThat(bumbleDevice.bondState).isEqualTo(BluetoothDevice.BOND_BONDED)
        intentReceiver.close()
    }

    /**
     * Test to verify getBondStatus()
     *
     * Steps:
     * 1. Create a bond
     * 2. Call getBondStatus()
     * 3. Remove Bond
     * 4. Call getBondStatus()
     */
    @RequiresFlagsEnabled(
        "com.android.bluetooth.flags.provide_pairing_algo",
        "com.android.bluetooth.flags.enable_get_bond_status",
    )
    @Test
    @Throws(Exception::class)
    fun testGetBondStatus() {
        testStep_BondBrEdr(null)
        val bondStatus = bumbleDevice.getBondStatus(BluetoothDevice.TRANSPORT_BREDR)
        assertThat(bondStatus).isNotNull()
        assertThat(bondStatus?.getPairingVariant())
            .isEqualTo(BluetoothDevice.PAIRING_VARIANT_CONSENT)
        assertThat(bondStatus?.getPairingAlgorithm())
            .isEqualTo(BluetoothDevice.PAIRING_ALGORITHM_SC)
        util.removeBond(null, bumbleDevice)
        assertThat(bumbleDevice.getBondStatus(BluetoothDevice.TRANSPORT_BREDR)).isNull()
    }

    /**
     * Test to verify getBondStatus()
     *
     * Steps:
     * 1. Create a bond over LE
     * 2. Call getBondStatus()
     * 3. Remove Bond
     * 4. Call getBondStatus()
     */
    @RequiresFlagsEnabled(
        "com.android.bluetooth.flags.provide_pairing_algo",
        "com.android.bluetooth.flags.enable_get_bond_status",
    )
    @Test
    @Throws(Exception::class)
    fun testLe_GetBondStatus() {
        testStep_BondLe(null, remoteLeDevice, OwnAddressType.RANDOM)
        val bondStatus = remoteLeDevice.getBondStatus(BluetoothDevice.TRANSPORT_LE)
        assertThat(bondStatus).isNotNull()
        assertThat(bondStatus?.getPairingVariant())
            .isEqualTo(BluetoothDevice.PAIRING_VARIANT_CONSENT)
        assertThat(bondStatus?.getPairingAlgorithm())
            .isEqualTo(BluetoothDevice.PAIRING_ALGORITHM_SC)
        util.removeBond(null, remoteLeDevice)
        assertThat(remoteLeDevice.getBondStatus(BluetoothDevice.TRANSPORT_LE)).isNull()
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
        if (Flags.providePairingAlgo()) {
            intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                    BluetoothDevice.EXTRA_PAIRING_VARIANT,
                    BluetoothDevice.PAIRING_VARIANT_CONSENT,
                ),
                hasExtra(
                    BluetoothDevice.EXTRA_PAIRING_ALGORITHM,
                    BluetoothDevice.PAIRING_ALGORITHM_SC,
                ),
            )
        } else {
            intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                    BluetoothDevice.EXTRA_PAIRING_VARIANT,
                    BluetoothDevice.PAIRING_VARIANT_CONSENT,
                ),
            )
        }

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

    private fun testStep_BondBrEdr(parentIntentReceiver: IntentReceiver?) {
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

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()

        testStep_VerifyBondIntents(intentReceiver, bumbleDevice, BluetoothDevice.TRANSPORT_BREDR)

        bumbleDevice.setPairingConfirmation(true)
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        intentReceiver.close()
    }

    private fun testStep_BondBredrFromRemote(intentReceiver: IntentReceiver) {
        val response =
            currentDevice
                .hostBlocking()
                .connect(
                    HostProto.ConnectRequest.newBuilder()
                        .setAddress(ByteString.copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )
        // Start pairing from Bumble
        val responseObserver =
            StreamObserverSpliterator<SecurityProto.SecureRequest, SecurityProto.SecureResponse>()
        currentDevice
            .security()
            .secure(
                SecurityProto.SecureRequest.newBuilder()
                    .setConnection(response.connection)
                    .setClassic(SecurityProto.SecurityLevel.LEVEL4)
                    .build(),
                responseObserver,
            )

        testStep_VerifyBondIntents(intentReceiver, bumbleDevice, BluetoothDevice.TRANSPORT_BREDR)
        // Approve pairing from Android
        assertThat(bumbleDevice.setPairingConfirmation(true)).isTrue()
    }

    private fun testStep_VerifyBondIntents(
        intentReceiver: IntentReceiver,
        device: BluetoothDevice,
        transport: Int,
    ) {
        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
        )
        if (Flags.providePairingAlgo()) {
            intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                    BluetoothDevice.EXTRA_PAIRING_VARIANT,
                    BluetoothDevice.PAIRING_VARIANT_CONSENT,
                ),
                hasExtra(
                    BluetoothDevice.EXTRA_PAIRING_ALGORITHM,
                    BluetoothDevice.PAIRING_ALGORITHM_SC,
                ),
            )
        } else {
            intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                    BluetoothDevice.EXTRA_PAIRING_VARIANT,
                    BluetoothDevice.PAIRING_VARIANT_CONSENT,
                ),
            )
        }
    }

    private fun testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue()
        assertThat(BlockingBluetoothAdapter.enable()).isTrue()
    }

    companion object {
        private const val BUMBLE_ALIAS = "Bumble"

        private var toggleDevice = true
    }
}
