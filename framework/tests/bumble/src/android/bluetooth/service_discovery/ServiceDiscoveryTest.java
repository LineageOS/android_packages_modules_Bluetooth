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

package android.bluetooth.service_discovery.pairing;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.PandoraDevice;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import pandora.GattProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class ServiceDiscoveryTest {
    private static final String TAG = ServiceDiscoveryTest.class.getSimpleName();

    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);

    private static final ParcelUuid BATTERY_UUID =
            ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB");

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

    @Mock private BroadcastReceiver mReceiver;
    private final Map<String, Integer> mActionRegistrationCounts = new HashMap<>();
    private InOrder mInOrder = null;
    private BluetoothDevice mBumbleDevice;

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
                            if (BluetoothDevice.ACTION_UUID.equals(action)) {
                                BluetoothDevice device =
                                        intent.getParcelableExtra(
                                                BluetoothDevice.EXTRA_DEVICE,
                                                BluetoothDevice.class);
                                Parcelable[] uuidsRaw =
                                        intent.getParcelableArrayExtra(
                                                BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                                if (uuidsRaw == null) {
                                    Log.e(TAG, "onReceive(): device " + device + " null uuid list");
                                } else if (uuidsRaw.length == 0) {
                                    Log.e(
                                            TAG,
                                            "onReceive(): device "
                                                    + device
                                                    + " 0 length uuid list");
                                } else {
                                    ParcelUuid[] uuids =
                                            Arrays.copyOf(
                                                    uuidsRaw, uuidsRaw.length, ParcelUuid[].class);
                                    Log.d(
                                            TAG,
                                            "onReceive(): device "
                                                    + device
                                                    + ", UUID="
                                                    + Arrays.toString(uuids));
                                }
                            }
                            return null;
                        })
                .when(mReceiver)
                .onReceive(any(), any());

        mInOrder = inOrder(mReceiver);

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

    @Test
    public void remoteConnectServiceDiscoveryTest() throws Exception {
        registerIntentActions(BluetoothDevice.ACTION_UUID);

        // Start service discovery over BR/EDR
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        // Wait for service discovery to complete on Android
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(
                        BluetoothDevice.EXTRA_UUID,
                        Matchers.allOf(
                                Matchers.hasItemInArray(BluetoothUuid.HFP),
                                Matchers.hasItemInArray(BluetoothUuid.A2DP_SOURCE),
                                Matchers.hasItemInArray(BluetoothUuid.A2DP_SINK),
                                Matchers.hasItemInArray(BluetoothUuid.AVRCP))));

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(BluetoothDevice.ACTION_UUID);
    }

    /**
     * Ensure that successful service discovery results in a single ACTION_UUID intent
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
     * </ol>
     *
     * Expectation: A single ACTION_UUID intent is received The ACTION_UUID intent is not empty
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_PREVENT_DUPLICATE_UUID_INTENT})
    public void testServiceDiscoveryBredr_SingleIntent() {
        // Setup intent filters
        registerIntentActions(
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED);

        // Start GATT service discovery, this will establish BR/EDR
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        // Wait for connection on Android
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));

        // Wait for GATT service discovery to complete on Android
        verifyIntentReceived(hasAction(BluetoothDevice.ACTION_UUID));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED);
    }

    /**
     * Ensure that successful service discovery results in a single ACTION_UUID intent
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
     * </ol>
     *
     * Expectation: A single ACTION_UUID intent is received The ACTION_UUID intent is not empty
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_PREVENT_DUPLICATE_UUID_INTENT})
    public void testServiceDiscoveryLe_SingleIntent() {
        // Setup intent filters
        registerIntentActions(
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED);

        // Register some services on Bumble
        for (int i = 0; i < 6; i++) {
            mBumble.gattBlocking()
                    .registerService(
                            GattProto.RegisterServiceRequest.newBuilder()
                                    .setService(
                                            GattProto.GattServiceParams.newBuilder()
                                                    .setUuid(BATTERY_UUID.toString())
                                                    .build())
                                    .build());
        }

        // Make Bumble connectable
        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.PUBLIC)
                                .build());

        // Start GATT service discovery, this will establish LE ACL
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_LE)).isTrue();

        // Wait for connection on Android
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));

        // Wait for GATT service discovery to complete on Android
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(BATTERY_UUID)));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(mReceiver);

        unregisterIntentActions(
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED);
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
}
