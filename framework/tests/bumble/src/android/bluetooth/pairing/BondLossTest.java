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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.Utils;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;

import io.grpc.stub.StreamObserver;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import pandora.HostProto.ConnectRequest;
import pandora.HostProto.ConnectResponse;
import pandora.HostProto.ConnectabilityMode;
import pandora.HostProto.SetConnectabilityModeRequest;
import pandora.SecurityProto.DeleteBondRequest;
import pandora.SecurityProto.PairingEvent;
import pandora.SecurityProto.PairingEventAnswer;
import pandora.SecurityProto.SecureRequest;
import pandora.SecurityProto.SecureResponse;
import pandora.SecurityProto.SecurityLevel;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class BondLossTest {
    private static final String TAG = BondLossTest.class.getSimpleName();
    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);

    private static final Context sTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final BluetoothAdapter sAdapter =
            sTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule mEnableBluetoothRule =
            new EnableBluetoothRule(false /* enableTestMode */, true /* toggleBluetooth */);

    private final Map<String, Integer> mActionRegistrationCounts = new HashMap<>();
    private final StreamObserverSpliterator<Void, PairingEvent> mPairingEventStreamObserver =
            new StreamObserverSpliterator<>();
    @Mock private BroadcastReceiver mReceiver;
    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;
    private InOrder mInOrder = null;
    private BluetoothDevice mBumbleDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;
    private StreamObserver<PairingEventAnswer> mPairingEventAnswerObserver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doAnswer(
                        inv -> {
                            Log.d(
                                    TAG,
                                    "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                            Intent intent = inv.getArgument(1);
                            String action = intent.getAction();
                            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                                int bondState =
                                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                                Log.d(TAG, "onReceive(): bondState=" + bondState);
                            }
                            return null;
                        })
                .when(mReceiver)
                .onReceive(any(), any());

        mInOrder = inOrder(mReceiver);

        // Get profile proxies
        mHidService = (BluetoothHidHost) getProfileProxy(BluetoothProfile.HID_HOST);
        mHfpService = (BluetoothHeadset) getProfileProxy(BluetoothProfile.HEADSET);

        mBumbleDevice = mBumble.getRemoteDevice();
        Set<BluetoothDevice> bondedDevices = sAdapter.getBondedDevices();
        if (bondedDevices.contains(mBumbleDevice)) {
            removeBond(mBumbleDevice);
        }
    }

    @After
    public void tearDown() throws Exception {
        Set<BluetoothDevice> bondedDevices = sAdapter.getBondedDevices();
        if (bondedDevices.contains(mBumbleDevice)) {
            removeBond(mBumbleDevice);
        }
        mBumbleDevice = null;
        if (getTotalActionRegistrationCounts() > 0) {
            sTargetContext.unregisterReceiver(mReceiver);
            mActionRegistrationCounts.clear();
        }
    }

    /**
     * Test Key missing on the BREDR device
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>1. Bumble deletes the pairing info.
     *   <li>2. Android initiate connection over BREDR.
     *   <li>3. Android disconnect from Bumble.
     *   <li>4. Android verifies ACTION_KEY_MISSING intent.
     *   <li>5. Android verifies Bumble device is in bonded device list.
     * </ol>
     */
    @Test
    public void testBondBredrBondLoss_Keymissing() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_KEY_MISSING);

        testStep_BondBredr();
        byte[] address = Utils.addressBytesFromString(sAdapter.getAddress());
        mBumble.securityStorageBlocking()
                .deleteBond(
                        DeleteBondRequest.newBuilder()
                                .setPublic(ByteString.copyFrom(address))
                                .build());
        mBumble.hostBlocking().reset(Empty.getDefaultInstance());

        SetConnectabilityModeRequest request =
                SetConnectabilityModeRequest.newBuilder()
                        .setMode(ConnectabilityMode.CONNECTABLE)
                        .build();
        mBumble.hostBlocking().setConnectabilityMode(request);
        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_KEY_MISSING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        // Wait for the ACL to get disconnected
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);
        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_KEY_MISSING);
    }

    /**
     * Test Remote initiated pairing on the BREDR device
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>1. Bumble deletes the pairing info.
     *   <li>2. Bumble initiate connection over BREDR.
     *   <li>3. Android disconnect from Bumble.
     *   <li>4. Android verifies ACTION_KEY_MISSING intent.
     *   <li>5. Android verifies Bumble device is in bonded device list.
     * </ol>
     */
    @Test
    public void testBondBredrBondLoss_RemoteInitiatedPairing() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_KEY_MISSING);

        testStep_BondBredr();
        byte[] address = Utils.addressBytesFromString(sAdapter.getAddress());
        mBumble.securityStorageBlocking()
                .deleteBond(
                        DeleteBondRequest.newBuilder()
                                .setPublic(ByteString.copyFrom(address))
                                .build());
        mBumble.hostBlocking().reset(Empty.getDefaultInstance());

        ConnectResponse connRsp =
                mBumble.hostBlocking()
                        .connect(
                                ConnectRequest.newBuilder()
                                        .setAddress(ByteString.copyFrom(address))
                                        .build());
        // Start pairing from Bumble
        StreamObserverSpliterator<SecureRequest, SecureResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.security()
                .secure(
                        SecureRequest.newBuilder()
                                .setConnection(connRsp.getConnection())
                                .setClassic(SecurityLevel.LEVEL3)
                                .build(),
                        responseObserver);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_KEY_MISSING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        // Wait for the ACL to get disconnected
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);
        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_KEY_MISSING);
    }

    private void testStep_BondBredr() {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);

        mPairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        // Disable all profiles other than A2DP as profile connections take too long
        assertThat(
                        mHfpService.setConnectionPolicy(
                                mBumbleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        assertThat(
                        mHidService.setConnectionPolicy(
                                mBumbleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        mPairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
        // Disable all profiles other than A2DP as profile connections take too long
        assertThat(
                        mHfpService.setConnectionPolicy(
                                mBumbleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        assertThat(
                        mHidService.setConnectionPolicy(
                                mBumbleDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);
        // Wait for profiles to get connected
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        // Disconnect all profiles
        assertThat(mBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTING),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyIntentReceived(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        // Wait for the ACL to get disconnected
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        assertThat(sAdapter.getBondedDevices()).contains(mBumbleDevice);
        verifyNoMoreInteractions(mReceiver);
        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    private void removeBond(BluetoothDevice device) {
        registerIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        assertThat(device.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        unregisterIntentActions(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnordered(int num, Matcher<Intent>... matchers) {
        verify(mReceiver, timeout(BOND_INTENT_TIMEOUT.toMillis()).times(num))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnordered(Matcher<Intent>... matchers) {
        verifyIntentReceivedUnordered(1, matchers);
    }

    /**
     * Helper function to add reference count to registered intent actions
     *
     * @param actions new intent actions to add. If the array is empty, it is a no-op.
     */
    private void registerIntentActions(String... actions) {
        if (actions.length == 0) {
            return;
        }
        if (getTotalActionRegistrationCounts() > 0) {
            Log.d(TAG, "registerIntentActions(): unregister ALL intents");
            sTargetContext.unregisterReceiver(mReceiver);
        }
        for (String action : actions) {
            mActionRegistrationCounts.merge(action, 1, Integer::sum);
        }
        IntentFilter filter = new IntentFilter();
        mActionRegistrationCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .forEach(
                        entry -> {
                            Log.d(
                                    TAG,
                                    "registerIntentActions(): Registering action = "
                                            + entry.getKey());
                            filter.addAction(entry.getKey());
                        });
        sTargetContext.registerReceiver(mReceiver, filter);
    }

    /**
     * Helper function to reduce reference count to registered intent actions If total reference
     * count is zero after removal, no broadcast receiver will be registered.
     *
     * @param actions intent actions to be removed. If some action is not registered, it is no-op
     *     for that action. If the actions array is empty, it is also a no-op.
     */
    private void unregisterIntentActions(String... actions) {
        if (actions.length == 0) {
            return;
        }
        if (getTotalActionRegistrationCounts() <= 0) {
            return;
        }
        Log.d(TAG, "unregisterIntentActions(): unregister ALL intents");
        sTargetContext.unregisterReceiver(mReceiver);
        for (String action : actions) {
            if (!mActionRegistrationCounts.containsKey(action)) {
                continue;
            }
            mActionRegistrationCounts.put(action, mActionRegistrationCounts.get(action) - 1);
            if (mActionRegistrationCounts.get(action) <= 0) {
                mActionRegistrationCounts.remove(action);
            }
        }
        if (getTotalActionRegistrationCounts() > 0) {
            IntentFilter filter = new IntentFilter();
            mActionRegistrationCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .forEach(
                            entry -> {
                                Log.d(
                                        TAG,
                                        "unregisterIntentActions(): Registering action = "
                                                + entry.getKey());
                                filter.addAction(entry.getKey());
                            });
            sTargetContext.registerReceiver(mReceiver, filter);
        }
    }

    /**
     * Get sum of reference count from all registered actions
     *
     * @return sum of reference count from all registered actions
     */
    private int getTotalActionRegistrationCounts() {
        return mActionRegistrationCounts.values().stream().reduce(0, Integer::sum);
    }

    private BluetoothProfile getProfileProxy(int profile) {
        sAdapter.getProfileProxy(sTargetContext, mProfileServiceListener, profile);
        ArgumentCaptor<BluetoothProfile> proxyCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.class);
        verify(mProfileServiceListener, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onServiceConnected(eq(profile), proxyCaptor.capture());
        return proxyCaptor.getValue();
    }
}
