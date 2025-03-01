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

package android.bluetooth.pairing;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDevice.BluetoothAddress;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.Utils;
import android.bluetooth.pairing.utils.IntentReceiver;
import android.bluetooth.pairing.utils.TestUtil;
import android.bluetooth.test_utils.BlockingBluetoothAdapter;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.Context;
import android.os.ParcelUuid;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import io.grpc.stub.StreamObserver;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import pandora.GattProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.ConnectabilityMode;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.SetConnectabilityModeRequest;
import pandora.SecurityProto.LESecurityLevel;
import pandora.SecurityProto.PairingEvent;
import pandora.SecurityProto.PairingEventAnswer;
import pandora.SecurityProto.SecureRequest;
import pandora.SecurityProto.SecureResponse;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RunWith(TestParameterInjector.class)
public class PairingTest {
    private static final String TAG = PairingTest.class.getSimpleName();

    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final int TEST_DELAY_MS = 1000;

    private static final ParcelUuid BATTERY_UUID =
            ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB");

    private static final ParcelUuid HOGP_UUID =
            ParcelUuid.fromString("00001812-0000-1000-8000-00805F9B34FB");

    private static final Context sTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final BluetoothAdapter sAdapter =
            sTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule mEnableBluetoothRule =
            new EnableBluetoothRule(false /* enableTestMode */, true /* toggleBluetooth */);

    private final StreamObserverSpliterator<PairingEvent> mPairingEventStreamObserver =
            new StreamObserverSpliterator<>();
    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;

    /* Util instance for common test steps with current Context reference */
    private TestUtil mUtil;
    private BluetoothDevice mBumbleDevice;
    private BluetoothDevice mRemoteLeDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUtil = new TestUtil.Builder(sTargetContext)
                .setProfileServiceListener(mProfileServiceListener)
                .setBluetoothAdapter(sAdapter)
                .build();

        // Get profile proxies
        mHidService = (BluetoothHidHost) mUtil.getProfileProxy(BluetoothProfile.HID_HOST);
        mHfpService = (BluetoothHeadset) mUtil.getProfileProxy(BluetoothProfile.HEADSET);

        mBumbleDevice = mBumble.getRemoteDevice();
        mRemoteLeDevice =
                sAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        /*
         * Note: Since there was no IntentReceiver registered, passing the instance as
         *  NULL in removeBond(). But, if there is an instance already present, that
         *  must be passed instead of NULL.
         */
        for (BluetoothDevice device : sAdapter.getBondedDevices()) {
            mUtil.removeBond(null, device);
        }
    }

    @After
    public void tearDown() throws Exception {
        Set<BluetoothDevice> bondedDevices = sAdapter.getBondedDevices();

        /*
         * Note: Since there was no IntentReceiver registered, passing the instance as
         *  NULL in removeBond(). But, if there is an instance already present, that
         *  must be passed instead of NULL.
         */
        if (bondedDevices.contains(mBumbleDevice)) {
            mUtil.removeBond(null, mBumbleDevice);
        }
        if (bondedDevices.contains(mRemoteLeDevice)) {
            mUtil.removeBond(null, mRemoteLeDevice);
        }
        mBumbleDevice = null;
        mRemoteLeDevice = null;
    }

    /** All the test function goes here */

    /**
     * Process of writing a test function
     *
     * 1. Create an IntentReceiver object first with following way:
     *      IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
     *          BluetoothDevice.ACTION_1,
     *          BluetoothDevice.ACTION_2)
     *          .setIntentListener(--) // optional
     *          .setIntentTimeout(--)  // optional
     *          .build();
     * 2. Use the intentReceiver instance for all Intent related verification, and pass
     *     the same instance to all the helper/testStep functions which has similar Intent
     *     requirements.
     * 3. Once all the verification is done, call `intentReceiver.close()` before returning
     *     from the function.
     */

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     *
     * <ol>
     *   <li>1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no
     *       input
     *   <li>2. Android tries to create bond via MAC address, emitting bonding intent
     *   <li>3. Android confirms the pairing via pairing request intent
     *   <li>4. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>5. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void testBrEdrPairing_phoneInitiatedBrEdrInquiryOnlyJustWorks() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST)
                .build();

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        mBumbleDevice.setPairingConfirmation(true);

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     *
     * <ol>
     *   <li>1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no
     *       input
     *   <li>2. Android tries to create bond via MAC address, emitting bonding intent
     *   <li>3. Android confirms the pairing via pairing request intent
     *   <li>4. Android cancel the pairing of unrelated device. verify current pairing is continued
     *       and success.
     *   <li>5. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>6. Android verifies bonded intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_IGNORE_UNRELATED_CANCEL_BOND})
    public void testBrEdrPairing_cancelBond_forUnrelatedDevice() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST)
                .build();

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        // cancel bonding for unrelated device and verify current pairing continued and success.
        BluetoothDevice fakeUnintendedDevice = sAdapter.getRemoteDevice("51:F7:A8:75:17:01");
        assertThat(fakeUnintendedDevice.cancelBondProcess()).isTrue();
        mBumbleDevice.setPairingConfirmation(true);

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    /**
     * Test a simple BR/EDR just works pairing flow in the follow steps:
     *
     * <ol>
     *   <li>1. Bumble resets, enables inquiry and page scan, and sets I/O cap to no display no
     *       input
     *   <li>2. Android connects to Bumble via its MAC address
     *   <li>3. Android tries to create bond, emitting bonding intent
     *   <li>4. Android confirms the pairing via pairing request intent
     *   <li>5. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>6. Android verifies bonded intent
     * </ol>
     */
    @Test
    public void testBrEdrPairing_phoneInitiatedBrEdrInquiryOnlyJustWorksWhileSdpConnected() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST)
                .build();

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        // Start SDP.  This will create an ACL connection before the bonding starts.
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        assertThat(mBumbleDevice.createBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        mBumbleDevice.setPairingConfirmation(true);

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    /**
     * Test if parallel GATT service discovery interrupts cancelling LE pairing
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble has GATT services in addition to GAP and GATT services
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Android connects to Bumble over LE
     *   <li>Android starts GATT service discovery
     *   <li>Bumble initiates pairing
     *   <li>Android does not confirm the pairing immediately
     *   <li>Service discovery completes
     *   <li>Android cancels the pairing
     * </ol>
     *
     * <p>Expectation: Pairing gets cancelled instead of getting timed out
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_PREVENT_DUPLICATE_UUID_INTENT})
    public void testCancelBondLe_WithGattServiceDiscovery() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .build();

        // Outgoing GATT service discovery and incoming LE pairing in parallel
        StreamObserverSpliterator<SecureResponse> responseObserver =
                helper_OutgoingGattServiceDiscoveryWithIncomingLePairing(intentReceiver);

        // Cancel pairing from Android
        assertThat(mBumbleDevice.cancelBondProcess()).isTrue();

        SecureResponse secureResponse = responseObserver.iterator().next();
        assertThat(secureResponse.hasPairingFailure()).isTrue();

        // Pairing should be cancelled in a moment instead of timing out in 30
        // seconds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        intentReceiver.close();
    }

    /**
     * Test if parallel GATT service discovery interrupts the LE pairing
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble has GATT services in addition to GAP and GATT services
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Android connects to Bumble over LE
     *   <li>Android starts GATT service discovery
     *   <li>Bumble starts pairing
     *   <li>Service discovery completes
     *   <li>Android does confirms the pairing
     *   <li>Pairing is successful
     * </ol>
     *
     * <p>Expectation: Pairing succeeds
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_PREVENT_DUPLICATE_UUID_INTENT})
    public void testBondLe_WithGattServiceDiscovery() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .build();

        // Outgoing GATT service discovery and incoming LE pairing in parallel
        StreamObserverSpliterator<SecureResponse> responseObserver =
                helper_OutgoingGattServiceDiscoveryWithIncomingLePairing(intentReceiver);

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        SecureResponse secureResponse = responseObserver.iterator().next();
        assertThat(secureResponse.hasSuccess()).isTrue();

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    /**
     * Test if bonded LE device can reconnect after BT restart
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Android pairs with Bumble over LE
     *   <li>Android restarts
     *   <li>Bumble is connectable over LE
     *   <li>Android reconnects to Bumble successfully and re-encrypts the link
     * </ol>
     *
     * <p>Expectation: Pairing succeeds
     */
    @Test
    public void testBondLe_Reconnect() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_CONNECTED)
                .build();

        testStep_BondLe(intentReceiver, mBumbleDevice, OwnAddressType.PUBLIC);
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        testStep_restartBt();

        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);
        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .setUuid(HOGP_UUID.toString())
                                                .build())
                                .build());

        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.PUBLIC)
                                .build());

        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        intentReceiver.close();
    }

    /**
     * Test if bonded LE device's identity address and type can be read
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over LE
     *   <li>Bumble device's identity address and type unknown
     *   <li>Android pairs with Bumble over LE
     *   <li>Bumble device's identity address and type are retrievable
     * </ol>
     *
     * <p>Expectation: Bumble device's identity address and type are present
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_IDENTITY_ADDRESS_TYPE_API)
    public void testBondLe_identityAddressWithType(@TestParameter boolean isRandom) {
        if (isRandom) {
            doTestIdentityAddressWithType(mRemoteLeDevice, OwnAddressType.RANDOM);
        } else {
            doTestIdentityAddressWithType(mBumbleDevice, OwnAddressType.PUBLIC);
        }
    }

    /**
     * Test if bonded BR/EDR device can reconnect after BT restart
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable and connectable over BR/EDR
     *   <li>Android pairs with Bumble over BR/EDR
     *   <li>Android restarts
     *   <li>Bumble is connectable over BR/EDR
     *   <li>Android reconnects to Bumble successfully and re-encrypts the link
     * </ol>
     *
     * <p>Expectation: Pairing succeeds
     */
    @Test
    public void testBondBredr_Reconnect() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_CONNECTED)
                .build();

        testStep_BondBredr(intentReceiver);
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        testStep_restartBt();

        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        SetConnectabilityModeRequest request =
                SetConnectabilityModeRequest.newBuilder()
                        .setMode(ConnectabilityMode.CONNECTABLE)
                        .build();
        mBumble.hostBlocking().setConnectabilityMode(request);
        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        intentReceiver.close();
    }

    /**
     * Test removeDevice API when connected over LE
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over LE
     *   <li>Android pairs with Bumble over LE
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Android disconnects the ACL and removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_WAIT_FOR_DISCONNECT_BEFORE_UNBOND})
    public void testRemoveBondLe_WhenConnected() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .build();

        testStep_BondLe(intentReceiver, mBumbleDevice, OwnAddressType.PUBLIC);
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        assertThat(mBumbleDevice.removeBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        intentReceiver.close();
    }

    /**
     * Test removeDevice API when connected over BR/EDR
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over BR/EDR
     *   <li>Android pairs with Bumble over BR/EDR
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Android disconnects the ACL and removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_WAIT_FOR_DISCONNECT_BEFORE_UNBOND})
    public void testRemoveBondBredr_WhenConnected() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .build();

        testStep_BondBredr(intentReceiver);
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        assertThat(mBumbleDevice.removeBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        intentReceiver.close();
    }

    /**
     * Test removeDevice API when not connected
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble supports HOGP
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over LE
     *   <li>Android pairs with Bumble over LE
     *   <li>Disconnect the Bumble
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    public void testRemoveBondLe_WhenDisconnected() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
                .build();

        testStep_BondLe(intentReceiver, mBumbleDevice, OwnAddressType.PUBLIC);
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        // Wait for profiles to get connected
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTING));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_CONNECTED));

        // Disconnect Bumble
        assertThat(mBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_DISCONNECTING));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothHidHost.EXTRA_STATE, BluetoothHidHost.STATE_DISCONNECTED));

        // Wait for ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Remove bond
        assertThat(mBumbleDevice.removeBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        intentReceiver.close();
    }

    /**
     * Test removeDevice API when not connected
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble supports HID device role
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is connectable over BR/EDR
     *   <li>Android pairs with Bumble over BR/EDR
     *   <li>Disconnect the Bumble
     *   <li>Call BluetoothDevice.removeBond() API
     *   <li>Removes the bond
     * </ol>
     *
     * <p>Expectation: Bumble is not bonded
     */
    @Test
    public void testRemoveBondBredr_WhenDisconnected() {
        IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                .build();

        // Disable all profiles other than A2DP as profile connections take too long
        assertThat(mHfpService.setConnectionPolicy(mBumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        assertThat(mHidService.setConnectionPolicy(mBumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        testStep_BondBredr(intentReceiver);
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);

        // Wait for profiles to get connected
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Todo: b/382118305 - due to settings app interference, profile connection initiate twice
        // after bonding. Introduced 1 second delay after first profile connection success
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeOnTimeout(null, TEST_DELAY_MS, TimeUnit.MILLISECONDS).join();
        // Disconnect all profiles
        assertThat(mBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Wait for the ACL to get disconnected
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        // Remove bond
        assertThat(mBumbleDevice.removeBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
        assertThat(sAdapter.getBondedDevices()).doesNotContain(mBumbleDevice);

        intentReceiver.close();
    }

    /** Helper/testStep functions goes here */

    /**
     * Process of writing a helper/test_step function.
     *
     * 1. All the helper functions should have IntentReceiver instance passed as an
     *  argument to them (if any intents needs to be registered).
     * 2. The caller (if a test function) can initiate a fresh instance of IntentReceiver
     *  and use it for all subsequent helper/testStep functions.
     * 3. The helper function should first register all required intent actions through the
     *  helper -> IntentReceiver.update()
     *  which either modifies the intentReceiver instance, or creates
     *  one (if the caller has passed a `null`).
     * 4. At the end, all functions should call `intentReceiver.close()` which either
     *  unregisters the recent actions, or frees the original instance as per the call.
     */

    private void testStep_BondBredr(IntentReceiver parentIntentReceiver) {
        IntentReceiver intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                new IntentReceiver.Builder(
                    sTargetContext,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST));

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).
            isTrue();

        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_BONDING));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT,
                    BluetoothDevice.TRANSPORT_BREDR));
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent)
                    .setConfirm(true).build());

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_BONDED));

        /* Unregisters all intent actions registered in this function */
        intentReceiver.close();
    }

    private void testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
        assertThat(BlockingBluetoothAdapter.enable()).isTrue();
    }

    /* Starts outgoing GATT service discovery and incoming LE pairing in parallel */
    private StreamObserverSpliterator<SecureResponse>
            helper_OutgoingGattServiceDiscoveryWithIncomingLePairing(
                IntentReceiver parentIntentReceiver) {
        // Register new actions specific to this helper function
        IntentReceiver intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                new IntentReceiver.Builder(
                    sTargetContext,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                    BluetoothDevice.ACTION_UUID,
                    BluetoothDevice.ACTION_ACL_CONNECTED));

        // Register lots of interesting GATT services on Bumble
        for (int i = 0; i < 40; i++) {
            mBumble.gattBlocking()
                    .registerService(
                            GattProto.RegisterServiceRequest.newBuilder()
                                    .setService(
                                            GattProto.GattServiceParams.newBuilder()
                                                    .setUuid(BATTERY_UUID.toString())
                                                    .build())
                                    .build());
        }

        // Start GATT service discovery, this will establish LE ACL
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_LE))
            .isTrue();

        // Make Bumble connectable
        AdvertiseResponse advertiseResponse =
                mBumble.hostBlocking()
                        .advertise(
                                AdvertiseRequest.newBuilder()
                                        .setLegacy(true)
                                        .setConnectable(true)
                                        .setOwnAddressType(OwnAddressType.PUBLIC)
                                        .build())
                        .next();

        // Todo: Unexpected empty ACTION_UUID intent is generated
        intentReceiver.verifyReceived(hasAction(BluetoothDevice.ACTION_UUID));

        // Wait for connection on Android
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT,
                    BluetoothDevice.TRANSPORT_LE));

        // Start pairing from Bumble
        StreamObserverSpliterator<SecureResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.security()
                .secure(
                        SecureRequest.newBuilder()
                                .setConnection(advertiseResponse.getConnection())
                                .setLe(LESecurityLevel.LE_LEVEL3)
                                .build(),
                        responseObserver);

        // Wait for incoming pairing notification on Android
        // TODO: Order of these events is not deterministic
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_BONDING));
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Allow participating in the incoming pairing on Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        // Wait for pairing approval notification on Android
        intentReceiver.verifyReceived(
                2,
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Wait for GATT service discovery to complete on Android
        // so that ACTION_UUID is received here.
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_UUID,
                    Matchers.hasItemInArray(BATTERY_UUID)));

        intentReceiver.close();
        return responseObserver;
    }

    private void testStep_BondLe(IntentReceiver parentIntentReceiver,
        BluetoothDevice device, OwnAddressType ownAddressType) {
        IntentReceiver intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                new IntentReceiver.Builder(
                    sTargetContext,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST));

        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .setUuid(BATTERY_UUID.toString())
                                                .build())
                                .build());
        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .setUuid(HOGP_UUID.toString())
                                                .build())
                                .build());

        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(ownAddressType)
                                .build());

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(device.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();

        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_BONDING));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT,
                    BluetoothDevice.TRANSPORT_LE));
        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(device.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent)
                    .setConfirm(true).build());

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    private void doTestIdentityAddressWithType(BluetoothDevice device,
            OwnAddressType ownAddressType) {
        BluetoothAddress identityAddress = device.getIdentityAddressWithType();
        assertThat(identityAddress.getAddress()).isNull();
        assertThat(identityAddress.getAddressType())
                .isEqualTo(BluetoothDevice.ADDRESS_TYPE_UNKNOWN);

        /*
         * Note: Since there was no IntentReceiver registered, passing the
         *  instance as NULL. But, if there is an instance already present, that
         *  must be passed instead of NULL.
         */
        testStep_BondLe(null, device, ownAddressType);
        assertThat(sAdapter.getBondedDevices()).contains(device);

        identityAddress = device.getIdentityAddressWithType();
        assertThat(identityAddress.getAddress()).isEqualTo(device.getAddress());
        assertThat(identityAddress.getAddressType())
                .isEqualTo(
                        ownAddressType == OwnAddressType.RANDOM
                                ? BluetoothDevice.ADDRESS_TYPE_RANDOM
                                : BluetoothDevice.ADDRESS_TYPE_PUBLIC);
    }
}
