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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.Host;
import android.bluetooth.PandoraDevice;
import android.bluetooth.VirtualOnly;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
import pandora.HostProto.DiscoverabilityMode;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.SetDiscoverabilityModeRequest;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class LeAudioServiceDiscoveryTest {
    private static final String TAG = LeAudioServiceDiscoveryTest.class.getSimpleName();

    private static final String BUMBLE_DEVICE_NAME = "Bumble";
    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DISCOVERY_TIMEOUT = 2000; // 2 seconds
    private CompletableFuture<BluetoothDevice> mDeviceFound;
    private static final ParcelUuid BATTERY_UUID =
            ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private static final ParcelUuid LEAUDIO_UUID =
            ParcelUuid.fromString("0000184E-0000-1000-8000-00805F9B34FB");

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
    private Host mHost;

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
                                ParcelUuid[] uuids =
                                        intent.getParcelableArrayExtra(
                                                BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                                Log.d(TAG, "onReceive(): UUID=" + Arrays.toString(uuids));
                            } else if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                                BluetoothDevice device =
                                        intent.getParcelableExtra(
                                                BluetoothDevice.EXTRA_DEVICE,
                                                BluetoothDevice.class);
                                String deviceName =
                                        String.valueOf(
                                                intent.getStringExtra(BluetoothDevice.EXTRA_NAME));
                                Log.i(
                                        TAG,
                                        "Discovered device: "
                                                + device
                                                + " with name: "
                                                + deviceName);
                                if (deviceName != null && BUMBLE_DEVICE_NAME.equals(deviceName)) {
                                    mDeviceFound.complete(device);
                                }
                            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(
                                    intent.getAction())) {
                                BluetoothDevice device =
                                        intent.getParcelableExtra(
                                                BluetoothDevice.EXTRA_DEVICE,
                                                BluetoothDevice.class);
                                int transport =
                                        intent.getIntExtra(
                                                BluetoothDevice.EXTRA_TRANSPORT,
                                                BluetoothDevice.TRANSPORT_AUTO);
                                Log.i(
                                        TAG,
                                        "ACL connected for device="
                                                + device
                                                + " with transport: "
                                                + transport);
                            }
                            return null;
                        })
                .when(mReceiver)
                .onReceive(any(), any());

        mInOrder = inOrder(mReceiver);
        mHost = new Host(sTargetContext);
    }

    @After
    public void tearDown() throws Exception {
        if (mBumbleDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            mHost.removeBondAndVerify(mBumbleDevice);
        }
        mHost.close();
        mBumbleDevice = null;
        if (getTotalActionRegistrationCounts() > 0) {
            sTargetContext.unregisterReceiver(mReceiver);
            mActionRegistrationCounts.clear();
        }
    }

    /**
     * Ensure that successful service discovery results on both Transport for LE Audio capable
     * device
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
     *   <li>Bumble is discoverable and connectable on both Transport
     *   <li>Android creates the Bond
     *   <li>Android starts service discovery on both Transport
     * </ol>
     *
     * Expectation: ACTION_UUID intent is received and The ACTION_UUID intent has both LE and
     * Classic services
     */
    @Test
    @VirtualOnly
    public void testServiceDiscoveryWithRandomAddr() {

        registerIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_FOUND);

        // Register Battery and Le Audio services on Bumble
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
                                                .setUuid(LEAUDIO_UUID.toString())
                                                .build())
                                .build());

        // Make Bumble connectable
        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.RANDOM)
                                .build());
        // Make Bumble discoverable over BR/EDR
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Start Discovery
        mDeviceFound = new CompletableFuture<>();
        assertThat(sAdapter.startDiscovery()).isTrue();
        mBumbleDevice =
                mDeviceFound
                        .completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS)
                        .join();
        assertThat(sAdapter.cancelDiscovery()).isTrue();
        // Create Bond
        mHost.createBondAndVerify(mBumbleDevice);

        // Verify  ACL connection on classic transport first and then LE transport
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));

        // Verify both LE and Classic Services
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(
                        BluetoothDevice.EXTRA_UUID,
                        Matchers.allOf(
                                Matchers.hasItemInArray(BluetoothUuid.HFP),
                                Matchers.hasItemInArray(BluetoothUuid.A2DP_SOURCE),
                                Matchers.hasItemInArray(BluetoothUuid.A2DP_SINK),
                                Matchers.hasItemInArray(BluetoothUuid.AVRCP),
                                Matchers.hasItemInArray(BluetoothUuid.LE_AUDIO),
                                Matchers.hasItemInArray(BluetoothUuid.BATTERY))));
        unregisterIntentActions(
                BluetoothDevice.ACTION_UUID,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_FOUND);
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
