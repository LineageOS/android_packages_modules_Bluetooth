/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.pairing.utils.IntentReceiver;
import android.bluetooth.pairing.utils.TestUtil;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import io.grpc.stub.StreamObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import pandora.GattProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;
import pandora.SecurityProto.PairingEvent;
import pandora.SecurityProto.PairingEventAnswer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class EncryptionChangeTest {
    private static final String TAG = EncryptionChangeTest.class.getSimpleName();

    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);

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

    private final StreamObserverSpliterator<Void, PairingEvent> mPairingEventStreamObserver =
            new StreamObserverSpliterator<>();
    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;

    /* Util instance for common test steps with current Context reference */
    private TestUtil mUtil;
    private BluetoothDevice mBumbleDevice;

    /**
     * IntentListener for the received intents Note: This is added as a default listener for all the
     * IntentReceiver instances created in this test class. Please add your own listener if required
     * as per the test requirement.
     */
    private final IntentReceiver.IntentListener mIntentListener =
            new IntentReceiver.IntentListener() {
                @Override
                public void onReceive(Intent intent) {
                    String action = intent.getAction();
                    if (BluetoothDevice.ACTION_UUID.equals(action)) {
                        ParcelUuid[] uuids =
                                intent.getParcelableArrayExtra(
                                        BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                        Log.d(TAG, "onReceive(): UUID=" + Arrays.toString(uuids));
                    } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                        Log.d(TAG, "onReceive(): bondState=" + bondState);
                    }
                }
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUtil =
                new TestUtil.Builder(sTargetContext)
                        .setProfileServiceListener(mProfileServiceListener)
                        .setBluetoothAdapter(sAdapter)
                        .build();

        mBumbleDevice = mBumble.getRemoteDevice();
        Set<BluetoothDevice> bondedDevices = sAdapter.getBondedDevices();
        if (bondedDevices.contains(mBumbleDevice)) {
            mUtil.removeBond(null, mBumbleDevice);
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

        mBumbleDevice = null;
    }

    /** All the test function goes here */

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
    //     the same instance to all the helper/testStep functions which has similar Intent
    //     requirements.
    // 3. Once all the verification is done, call `intentReceiver.close()` before returning
    //     from the function.
    //

    /**
     * Test Encryption change event on LE Secure link:
     *
     * <ol>
     *   <li>1. Android initiate create bond over LE link
     *   <li>2. Android confirms the pairing via pairing request intent
     *   <li>3. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>4. Android verifies Encryption change Intent and bonded intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_ENCRYPTION_CHANGE_BROADCAST})
    public void encryptionChangeSecureLeLink() {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                sTargetContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ENCRYPTION_CHANGE,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .setIntentListener(mIntentListener)
                        .build();

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

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();
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
                hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true),
                hasExtra(BluetoothDevice.EXTRA_KEY_SIZE, 16),
                hasExtra(
                        BluetoothDevice.EXTRA_ENCRYPTION_ALGORITHM,
                        BluetoothDevice.ENCRYPTION_ALGORITHM_AES));

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    /**
     * Test Encryption change event on classic link:
     *
     * <ol>
     *   <li>1. Android initiate create bond over Classic link
     *   <li>2. Android confirms the pairing via pairing request intent
     *   <li>3. Bumble confirms the pairing internally (optional, added only for test confirmation)
     *   <li>4. Android verifies Encryption change Intent and bonded intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_ENCRYPTION_CHANGE_BROADCAST})
    public void encryptionChangeSecureClassicLink() {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                sTargetContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ENCRYPTION_CHANGE,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .setIntentListener(mIntentListener)
                        .build();

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();
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
                hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
                hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true),
                hasExtra(BluetoothDevice.EXTRA_KEY_SIZE, 16),
                hasExtra(
                        BluetoothDevice.EXTRA_ENCRYPTION_ALGORITHM,
                        BluetoothDevice.ENCRYPTION_ALGORITHM_AES));

        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }
}
