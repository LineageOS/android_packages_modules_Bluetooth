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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.PandoraDevice;
import android.bluetooth.Utils;
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
import pandora.HostProto.OwnAddressType;

import java.time.Duration;

@RunWith(AndroidJUnit4.class)
public class ServiceDiscoveryTest {
    private static final String TAG = ServiceDiscoveryTest.class.getSimpleName();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule mEnableBluetoothRule = new EnableBluetoothRule(false, true);

    @Mock private BroadcastReceiver mReceiver;

    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final ParcelUuid BATTERY_UUID =
            ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private InOrder mInOrder;
    private BluetoothDevice mBumbleDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mInOrder = inOrder(mReceiver);
        mBumbleDevice = mBumble.getRemoteDevice();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_UUID);

        mTargetContext.registerReceiver(mReceiver, filter);
        Utils.setupIntentLogger(TAG, mReceiver);
    }

    @After
    public void tearDown() throws Exception {
        Log.d(TAG, "start tearDown");
        mTargetContext.unregisterReceiver(mReceiver);
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
    public void testServiceDiscoveryBredr_SingleIntent() {
        // Start GATT service discovery, this will establish BR/EDR
        assertThat(mBumbleDevice.fetchUuidsWithSdp(BluetoothDevice.TRANSPORT_BREDR)).isTrue();

        // Wait for connection on Android
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));

        // Wait for GATT service discovery to complete on Android
        verifyIntentReceived(hasAction(BluetoothDevice.ACTION_UUID));

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(mReceiver);
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
    public void testServiceDiscoveryLe_SingleIntent() {
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

        // Ensure that no other ACTION_UUID intent is received
        verifyNoMoreInteractions(mReceiver);
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }
}
