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
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.PandoraDevice;
import android.bluetooth.Utils;
import android.bluetooth.VirtualOnly;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

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

import pandora.BumbleConfigProto.IoCapability;
import pandora.BumbleConfigProto.KeyDistribution;
import pandora.BumbleConfigProto.OverrideRequest;
import pandora.BumbleConfigProto.PairingConfig;
import pandora.GattProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.DiscoverabilityMode;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.SetDiscoverabilityModeRequest;

import java.time.Duration;

@RunWith(AndroidJUnit4.class)
public class LeAudioServiceDiscoveryTest {
    private static final String TAG = LeAudioServiceDiscoveryTest.class.getSimpleName();

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
    private static final ParcelUuid LEAUDIO_UUID =
            ParcelUuid.fromString("0000184E-0000-1000-8000-00805F9B34FB");

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    private InOrder mInOrder;
    private BluetoothDevice mBumbleDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mInOrder = inOrder(mReceiver);
        mBumbleDevice = mBumble.getRemoteDevice();
        mBumble.bumbleConfigBlocking()
                .override(
                        OverrideRequest.newBuilder()
                                .setIoCapability(IoCapability.NO_OUTPUT_NO_INPUT)
                                .setPairingConfig(
                                        PairingConfig.newBuilder()
                                                .setSc(true)
                                                .setMitm(true)
                                                .setBonding(true)
                                                .setIdentityAddressType(OwnAddressType.PUBLIC)
                                                .build())
                                .addInitiatorKeyDistribution(KeyDistribution.ENCRYPTION_KEY)
                                .addInitiatorKeyDistribution(KeyDistribution.IDENTITY_KEY)
                                .addInitiatorKeyDistribution(KeyDistribution.SIGNING_KEY)
                                .addInitiatorKeyDistribution(KeyDistribution.LINK_KEY)
                                .addResponderKeyDistribution(KeyDistribution.ENCRYPTION_KEY)
                                .addResponderKeyDistribution(KeyDistribution.IDENTITY_KEY)
                                .addResponderKeyDistribution(KeyDistribution.SIGNING_KEY)
                                .addResponderKeyDistribution(KeyDistribution.LINK_KEY)
                                .build());

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);

        mTargetContext.registerReceiver(mReceiver, filter);
        Utils.setupIntentLogger(TAG, mReceiver);
    }

    @After
    public void tearDown() throws Exception {
        if (mBumbleDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mBumbleDevice);
        }
        mTargetContext.unregisterReceiver(mReceiver);
    }

    /**
     * Ensure that successful service discovery results on both Transport for LE Audio capable
     * device
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     *   <li>Bumble has LE Audio service in addition to GAP and GATT services
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
    public void testServiceDiscoveryWithPublicAddr() {

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
                                .setOwnAddressType(OwnAddressType.PUBLIC)
                                .build());
        // Make Bumble discoverable over BR/EDR
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Start Discovery
        assertThat(mAdapter.startDiscovery()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_FOUND),
                hasExtra(BluetoothDevice.EXTRA_NAME, Utils.BUMBLE_DEVICE_NAME),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        assertThat(mAdapter.cancelDiscovery()).isTrue();

        // Start pairing from Android with Auto transport
        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        // Verify  ACL connection on LE transport first and then Classic transport
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        verifyIntentReceivedUnordered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
        // Verify both LE and Classic Services
        verifyIntentReceivedUnorderedAtLeast(
                1,
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
    public void testServiceDiscoveryWithRandomAddr() throws Exception {
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

        // Make Bumble discoverable over BR/EDR
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Start Discovery
        assertThat(mAdapter.startDiscovery()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_FOUND),
                hasExtra(BluetoothDevice.EXTRA_NAME, Utils.BUMBLE_DEVICE_NAME),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        assertThat(mAdapter.cancelDiscovery()).isTrue();
        // Start pairing from Android with Auto transport
        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        // Make Bumble connectable with some delay
        Thread.sleep(300);
        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.RANDOM)
                                .build());

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));
        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
        // Verify both LE and Classic Services
        verifyIntentReceivedUnorderedAtLeast(
                1,
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
    }

    private void removeBond(BluetoothDevice device) {
        assertThat(device.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnordered(int num, Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()).times(num))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnorderedAtLeast(int atLeast, Matcher<Intent>... matchers) {
        verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnordered(Matcher<Intent>... matchers) {
        verifyIntentReceivedUnordered(1, matchers);
    }
}
