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

package android.bluetooth.pairing;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.Utils;
import android.bluetooth.pairing.utils.IntentReceiver;
import android.bluetooth.pairing.utils.TestUtil;
import android.bluetooth.test_utils.BlockingBluetoothAdapter;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import pandora.HostProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.SecurityProto;

@RunWith(TestParameterInjector.class)
public class PairingTestDualMode {
    @Rule(order = 0)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 1)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 2)
    public final PandoraDevice mSecondBumble = PandoraDevice.createSecondPandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule mEnableBluetoothRule =
            new EnableBluetoothRule(false /* enableTestMode */, true /* toggleBluetooth */);

    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;

    private static final String TAG = PairingTestDualMode.class.getSimpleName();
    private static final String BUMBLE_ALIAS = "Bumble";
    private static boolean sToggleDevice = true;

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    private TestUtil mUtil;
    private BluetoothDevice mBumbleDevice;
    private BluetoothDevice mRemoteLeDevice;
    private PandoraDevice mCurrentDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUtil =
                new TestUtil.Builder(mTargetContext)
                        .setProfileServiceListener(mProfileServiceListener)
                        .setBluetoothAdapter(mAdapter)
                        .build();
        // switch the bumble devices to avoid profile connection interference
        // caused by settings and system UI
        if (sToggleDevice) {
            mCurrentDevice = mBumble;
            mRemoteLeDevice =
                    mAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        } else {
            mCurrentDevice = mSecondBumble;
            mRemoteLeDevice =
                    mAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS_2, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        }
        sToggleDevice = !sToggleDevice;
        // Always read fresh address
        HostProto.ReadLocalAddressResponse readLocalAddressResponse =
                mCurrentDevice.hostBlocking().readLocalAddress(Empty.getDefaultInstance());
        mBumbleDevice =
                mAdapter.getRemoteDevice(
                        Utils.addressStringFromByteString(readLocalAddressResponse.getAddress()));
        Log.d(TAG, "Bumble Device: " + mBumbleDevice);
        Log.d(TAG, "Bumble LE Device: " + mRemoteLeDevice);
    }

    @After
    public void tearDown() throws Exception {
        Log.i(TAG, "Tearing Down");
        for (BluetoothDevice device : mAdapter.getBondedDevices()) {
            mUtil.removeBond(null, device);
        }
    }

    /**
     * Test the scenario where bonding is initiated over BR/EDR, When the DUT and REF is bonded over
     * LE
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble is advertising over LE with a random address and is connectable.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Create bonding with the Bumble LE device ({@code mRemoteLeDevice}) over the LE
     *       transport.
     *   <li>Verify that the list of bonded devices on the Android adapter now includes {@code
     *       mRemoteLeDevice}.
     *   <li>Initiate bonding with the Bumble device ({@code mBumbleDevice}) over the BR/EDR
     *       transport.
     *   <li>Verify the bonding intents received during the BR/EDR bonding process using {@link
     *       #testStep_VerifyBondIntents(IntentReceiver, BluetoothDevice, int)}.
     *   <li>Ensure that the BR/EDR bonding succeeds by checking for the {@link
     *       BluetoothDevice#ACTION_BOND_STATE_CHANGED} intent with the {@link
     *       BluetoothDevice#BOND_BONDED} state for {@code mBumbleDevice}.
     *   <li>Verify that the list of bonded devices on the Android adapter now includes {@code
     *       mBumbleDevice}.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>Bonding with the Bumble device over BR/EDR succeeds after the LE bonding.
     * </ul>
     */
    @Test
    public void testBondLe_InitiateBrEdrPairingFromDUT() {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                mTargetContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .build();
        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();
        // Start advertising for LE
        mCurrentDevice
                .host()
                .advertise(
                        HostProto.AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                .build(),
                        responseObserver);
        // Create bond over LE transport
        assertThat(mRemoteLeDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();

        // Verify bonding intents
        testStep_VerifyBondIntents(intentReceiver, mRemoteLeDevice, BluetoothDevice.TRANSPORT_LE);
        responseObserver.cancel("Canceling advertise request");

        // Approve pairing from Android
        assertThat(mRemoteLeDevice.setPairingConfirmation(true)).isTrue();

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteLeDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        // Check if bonded device's list contains REF device
        assertThat(mAdapter.getBondedDevices()).contains(mRemoteLeDevice);

        // Create bond over BR/EDR
        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        // Verify bonding intents
        testStep_VerifyBondIntents(intentReceiver, mBumbleDevice, BluetoothDevice.TRANSPORT_BREDR);

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        // Check if bonded device's list contains REF device
        assertThat(mAdapter.getBondedDevices()).contains(mBumbleDevice);
        intentReceiver.close();
    }
    /**
     * Test the scenario where DUT is bonded with Bumble REF over LE, Pairing initiated by REF
     * device over BR/EDR.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>No existing bond between Android and Bumble or {@code mRemoteLeDevice}.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Create bonding with the remote LE device ({@code mRemoteLeDevice}) over the LE
     *       transport from the DUT.
     *   <li>Verify that the list of bonded devices on the Android adapter now includes {@code
     *       mRemoteLeDevice}.
     *   <li>Initiate bonding with the Bumble device ({@code mBumbleDevice}) over the BR/EDR
     *       transport from the remote (Bumble) side using
     *   <li>Ensure that the BR/EDR bonding succeeds by checking for the {@link
     *       BluetoothDevice#ACTION_BOND_STATE_CHANGED} intent with the {@link
     *       BluetoothDevice#BOND_BONDED} state for {@code mBumbleDevice}.
     *   <li>Verify that the list of bonded devices on the Android adapter now includes {@code
     *       mBumbleDevice}.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>Bonding with the Bumble device over BR/EDR initiated by the remote succeeds after the
     *       LE bonding.
     * </ul>
     */
    @Test
    public void testBondLe_InitiateBrEdrPairingFromREF() {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                mTargetContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .build();
        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();
        // Start advertising for LE
        mCurrentDevice
                .host()
                .advertise(
                        HostProto.AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                .build(),
                        responseObserver);

        // Create bond over LE transport
        assertThat(mRemoteLeDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();

        // Verify bonding intents
        testStep_VerifyBondIntents(intentReceiver, mRemoteLeDevice, BluetoothDevice.TRANSPORT_LE);
        responseObserver.cancel("Canceling advertise request");

        // Approve pairing from Android
        assertThat(mRemoteLeDevice.setPairingConfirmation(true)).isTrue();

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteLeDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        // verify that devices is the bonded list
        assertThat(mAdapter.getBondedDevices()).contains(mRemoteLeDevice);

        // Start bonding from remote side
        testStep_BondBredrFromRemote(intentReceiver);

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        assertThat(mAdapter.getBondedDevices()).contains(mBumbleDevice);

        intentReceiver.close();
    }

    /**
     * Test the reconnection over BR/EDR transport after a Bluetooth restart and verify link
     * encryption.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bond Android and Bumble over BR/EDR
     *   <li>Verify that the link is encrypted
     *   <li>Restart the Bluetooth adapter using the {@link #testStep_restartBt()} helper method.
     *   <li>Initiate a connection from the Bumble side to the Android device.
     *   <li>Verify that the link is encrypted
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>After a Bluetooth restart, Bumble can successfully reconnect to the Android device over
     *       BR/EDR.
     *   <li>After restart link is encryption.
     * </ul>
     */
    @Test
    public void testReconnection_OverTransportBrEdr() throws Exception {

        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                mTargetContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ENCRYPTION_CHANGE,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .build();

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        testStep_VerifyBondIntents(intentReceiver, mBumbleDevice, BluetoothDevice.TRANSPORT_BREDR);

        mBumbleDevice.setPairingConfirmation(true);

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true));

        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        testStep_restartBt();

        // Create connection from Bumble side
        ByteString address =
                ByteString.copyFrom(Utils.addressBytesFromString(mAdapter.getAddress()));
        HostProto.ConnectRequest connectionRequest =
                HostProto.ConnectRequest.newBuilder().setAddress(address).build();
        HostProto.ConnectResponse response =
                mCurrentDevice.hostBlocking().connect(connectionRequest);

        assertThat(response.hasConnection()).isTrue();

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));

        // Verify the link encryption after restart
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true));

        intentReceiver.close();
    }

    /**
     * Test that the properties of a bonded BR/EDR device remain intact after a Bluetooth restart.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bond Android and Bumble over BR/EDR using the {@link
     *       #testStep_BondBrEdr(IntentReceiver)} helper method.
     *   <li>Retrieve and store the following properties of the bonded Bumble device:
     *       <ul>
     *         <li>Device type ({@link BluetoothDevice#getType()})
     *         <li>Device name ({@link BluetoothDevice#getName()})
     *         <li>Device address ({@link BluetoothDevice#getAddress()})
     *         <li>Device address type ({@link BluetoothDevice#getAddressType()})
     *         <li>Active audio device policy ({@link BluetoothDevice#getActiveAudioDevicePolicy()})
     *         <li>Bond state ({@link BluetoothDevice#getBondState()})
     *         <li>UUIDs ({@link BluetoothDevice#getUuids()})
     *         <li>identityAddress ({@link BluetoothDevice#getIdentityAddress()})
     *         <li>identityAddressWithType ({@link BluetoothDevice#getIdentityAddressWithType()})
     *         <li>class of device ({@link BluetoothDevice#getBluetoothClass()})
     *         <li>alias
     *       </ul>
     *   <li>Restart the Bluetooth adapter using the {@link #testStep_restartBt()} helper method.
     *   <li>Retrieve the properties of the Bumble device again after the restart.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>All retrieved properties of the bonded Bumble device (type, name, address, address
     *       type, active audio device policy, bond state, and UUIDs) remain the same after the
     *       Bluetooth restart.
     * </ul>
     */
    @Test
    public void testProperties_IntactAfterRestart() throws Exception {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                mTargetContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ACL_CONNECTED)
                        .build();

        testStep_BondBrEdr(intentReceiver);
        // Retrieve all the properties from remote device
        int type = mBumbleDevice.getType();
        String name = mBumbleDevice.getName();
        String address = mBumbleDevice.getAddress();
        int addressType = mBumbleDevice.getAddressType();
        int deviceAudioPolicy = mBumbleDevice.getActiveAudioDevicePolicy();
        int bondState = mBumbleDevice.getBondState();
        ParcelUuid[] uuids = mBumbleDevice.getUuids();
        String identityAddress = mBumbleDevice.getIdentityAddress();
        BluetoothDevice.BluetoothAddress identityAddressWithType =
                mBumbleDevice.getIdentityAddressWithType();
        BluetoothClass cod = mBumbleDevice.getBluetoothClass();
        mBumbleDevice.setAlias(BUMBLE_ALIAS);

        testStep_restartBt();
        assertThat(mAdapter.getBondedDevices()).contains(mBumbleDevice);

        // Verify properties after restart
        assertThat(type).isEqualTo(mBumbleDevice.getType());
        assertThat(name).isEqualTo(mBumbleDevice.getName());
        assertThat(address).isEqualTo(mBumbleDevice.getAddress());
        assertThat(addressType).isEqualTo(mBumbleDevice.getAddressType());
        assertThat(deviceAudioPolicy).isEqualTo(mBumbleDevice.getActiveAudioDevicePolicy());
        assertThat(bondState).isEqualTo(mBumbleDevice.getBondState());
        assertThat(uuids).isEqualTo(mBumbleDevice.getUuids());
        assertThat(identityAddress).isEqualTo(mBumbleDevice.getIdentityAddress());
        assertThat(identityAddressWithType.getAddressType())
                .isEqualTo(mBumbleDevice.getIdentityAddressWithType().getAddressType());
        assertThat(identityAddressWithType.getAddress())
                .isEqualTo(mBumbleDevice.getIdentityAddressWithType().getAddress());
        assertThat(cod).isEqualTo(mBumbleDevice.getBluetoothClass());
        assertThat(mBumbleDevice.getAlias()).isEqualTo(BUMBLE_ALIAS);

        intentReceiver.close();
    }

    private static void testStep_VerifyBondIntents(
            IntentReceiver intentReceiver, BluetoothDevice device, int transport) {
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport));

        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
    }

    private static void testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
        assertThat(BlockingBluetoothAdapter.enable()).isTrue();
    }

    private void testStep_BondBrEdr(IntentReceiver parentIntentReceiver) {
        IntentReceiver intentReceiver =
                IntentReceiver.update(
                        parentIntentReceiver,
                        new IntentReceiver.Builder(
                                mTargetContext,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST));

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        testStep_VerifyBondIntents(intentReceiver, mBumbleDevice, BluetoothDevice.TRANSPORT_BREDR);

        mBumbleDevice.setPairingConfirmation(true);
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
    }

    private void testStep_BondBredrFromRemote(IntentReceiver parentIntentReceiver) {
        IntentReceiver intentReceiver =
                IntentReceiver.update(
                        parentIntentReceiver,
                        new IntentReceiver.Builder(
                                mTargetContext,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST));
        HostProto.ConnectResponse response =
                mCurrentDevice
                        .hostBlocking()
                        .connect(
                                HostProto.ConnectRequest.newBuilder()
                                        .setAddress(
                                                ByteString.copyFrom(
                                                        Utils.addressBytesFromString(
                                                                mAdapter.getAddress())))
                                        .build());
        // Start pairing from Bumble
        StreamObserverSpliterator<SecurityProto.SecureRequest, SecurityProto.SecureResponse>
                responseObserver = new StreamObserverSpliterator<>();
        mCurrentDevice
                .security()
                .secure(
                        SecurityProto.SecureRequest.newBuilder()
                                .setConnection(response.getConnection())
                                .setClassic(SecurityProto.SecurityLevel.LEVEL4)
                                .build(),
                        responseObserver);

        testStep_VerifyBondIntents(intentReceiver, mBumbleDevice, BluetoothDevice.TRANSPORT_BREDR);
        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        intentReceiver.close();
    }
}
