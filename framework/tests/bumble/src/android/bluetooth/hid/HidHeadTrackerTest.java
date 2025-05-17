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

package android.bluetooth.hid;

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.oneOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.Host;
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

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
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

import pandora.GattProto;
import pandora.GattProto.GattCharacteristicParams;
import pandora.HIDGrpc;
import pandora.HidProto.HidServiceType;
import pandora.HidProto.ServiceRequest;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.DiscoverabilityMode;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.SetDiscoverabilityModeRequest;

import java.time.Duration;

@RunWith(AndroidJUnit4.class)
public class HidHeadTrackerTest {
    private static final String TAG = HidHeadTrackerTest.class.getSimpleName();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule mEnableBluetoothRule = new EnableBluetoothRule(false, true);

    @Mock private BroadcastReceiver mReceiver;
    @Mock private BluetoothProfile.ServiceListener mServiceListener;

    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final ParcelUuid HEADTRACKER_UUID =
            ParcelUuid.fromString("109b862f-50e3-45cc-8ea1-ac62de4846d1");
    private static final ParcelUuid HEADTRACKER_VERSION_CHARACTERISTIC_UUID =
            ParcelUuid.fromString("b4eb9919-a910-46a2-a9dd-fec2525196fd");
    private static final ParcelUuid HEADTRACKER_CONTROL_CHARACTERISTIC_UUID =
            ParcelUuid.fromString("8584cbb5-2d58-45a3-ab9d-583e0958b067");
    private static final ParcelUuid HEADTRACKER_REPORT_CHARACTERISTIC_UUID =
            ParcelUuid.fromString("e66dd173-b2ae-4f5a-ae16-0162af8038ae");

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    private HIDGrpc.HIDBlockingStub mHidBlockingStub;
    private InOrder mInOrder;
    private BluetoothDevice mBumbleDevice;
    private Host mHost;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;
    private BluetoothA2dp mA2dpService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHidBlockingStub = mBumble.hidBlocking();

        mInOrder = inOrder(mReceiver);
        mBumbleDevice = mBumble.getRemoteDevice();
        mHost = new Host(mTargetContext);
        // Get profile proxies
        mHidService = (BluetoothHidHost) connectToProfile(BluetoothProfile.HID_HOST);
        mA2dpService = (BluetoothA2dp) connectToProfile(BluetoothProfile.A2DP);
        mHfpService = (BluetoothHeadset) connectToProfile(BluetoothProfile.HEADSET);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);

        mTargetContext.registerReceiver(mReceiver, filter);
        Utils.setupIntentLogger(TAG, mReceiver);
    }

    @After
    public void tearDown() throws Exception {
        Log.d(TAG, "start tearDown");
        mTargetContext.unregisterReceiver(mReceiver);
        if (mBumbleDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            mHost.removeBondAndVerify(mBumbleDevice);
        }
        mHost.close();
    }

    /**
     * Ensure that successful HID connection over LE Transport.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble has Android Headtracker Service
     *   <li>Bumble does not support HID and HOGP
     *   <li>Bumble is connectable over LE
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Android pairs with Bumble
     *   <li>Android Bluetooth reports HID host connection
     *   <li>Disconnect and reconnect
     *   <li>Android Bluetooth reports HID host connection
     * </ol>
     *
     * Expectation: successful HID connection over LE Transport
     */
    @SuppressLint("MissingPermission")
    @Test
    public void connectWithoutHidServiceTest() {
        pairAndConnect();

        // Verify  ACL connection on classic transport first and then LE transport
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(HEADTRACKER_UUID)));
        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTING);
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTED);

        // Disable a2dp and HFP connection policy

        if (mA2dpService.getConnectionPolicy(mBumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(mA2dpService.setConnectionPolicy(mBumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHfpService.getConnectionPolicy(mBumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(mHfpService.setConnectionPolicy(mBumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }

        // Disconnect  and Reconnect
        assertThat(mBumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS);

        verifyConnectionState(TRANSPORT_LE, STATE_DISCONNECTING);
        verifyConnectionState(TRANSPORT_LE, STATE_DISCONNECTED);
        // Wait for ACL to get disconnected
        verifyIntentReceivedUnorderedAtLeast(
                1,
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTING);
        // HOGP CONNECTING and ACL CONNECTED has race connection when hogp_reconnection flag enabled
        // hence unordered here
        verifyIntentReceivedUnorderedAtLeast(
                1,
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        verifyConnectionState(TRANSPORT_LE, STATE_CONNECTED);
    }

    /**
     * Ensure that successful HID connection over BREDR Transport.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble has Android Headtracker Service
     *   <li>Bumble supports only HID but not HOGP
     *   <li>Bumble is connectable over LE
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Android pairs with Bumble
     *   <li>Android Bluetooth reports HID host connection
     *   <li>Change the preferred transport to LE
     *   <li>Android Bluetooth reports HID host connection over LE
     * </ol>
     *
     * Expectation: successful HID connection over BREDR Transport and Preferred transport selection
     * success
     */
    @SuppressLint("MissingPermission")
    @Test
    public void connectWithHidServiceTest() {
        mHidBlockingStub.registerService(
                ServiceRequest.newBuilder()
                        .setServiceType(HidServiceType.SERVICE_TYPE_HID)
                        .build());
        pairAndConnect();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_UUID, Matchers.hasItemInArray(HEADTRACKER_UUID)));

        assertThat(mBumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyConnectionState(TRANSPORT_BREDR, STATE_CONNECTED);
        // Switch to LE Transport
        mHidService.setPreferredTransport(mBumbleDevice, TRANSPORT_LE);
        verifyTransportSwitch(mBumbleDevice, TRANSPORT_BREDR, TRANSPORT_LE);
    }

    private void pairAndConnect() {
        // Register Head tracker services on Bumble
        GattCharacteristicParams characteristicVersion =
                GattCharacteristicParams.newBuilder()
                        .setProperties(
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_WRITE)
                        .setUuid(HEADTRACKER_VERSION_CHARACTERISTIC_UUID.toString())
                        .build();

        GattCharacteristicParams characteristicControl =
                GattCharacteristicParams.newBuilder()
                        .setProperties(
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_WRITE)
                        .setUuid(HEADTRACKER_CONTROL_CHARACTERISTIC_UUID.toString())
                        .build();
        GattCharacteristicParams characteristicReport =
                GattCharacteristicParams.newBuilder()
                        .setProperties(
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_WRITE)
                        .setUuid(HEADTRACKER_REPORT_CHARACTERISTIC_UUID.toString())
                        .build();
        mBumble.gattBlocking()
                .registerService(
                        GattProto.RegisterServiceRequest.newBuilder()
                                .setService(
                                        GattProto.GattServiceParams.newBuilder()
                                                .addCharacteristics(characteristicVersion)
                                                .addCharacteristics(characteristicControl)
                                                .addCharacteristics(characteristicReport)
                                                .setUuid(HEADTRACKER_UUID.toString())
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
        assertThat(mAdapter.startDiscovery()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_FOUND),
                hasExtra(BluetoothDevice.EXTRA_NAME, Utils.BUMBLE_DEVICE_NAME),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));
        assertThat(mAdapter.cancelDiscovery()).isTrue();
        // Create Bond
        mHost.createBondAndVerify(mBumbleDevice);
    }

    /**
     * CONNECTING and DISCONNECTING intents can go out of order, hence need a special function to
     * verify transport switches if we want to verify connecting and disconnected states
     *
     * <p>Four intents are expected: 1. fromTransport DISCONNECTING 2. toTransport CONNECTING 3.
     * fromTransport DISCONNECTED 4. toTransport CONNECTED
     *
     * <p>Currently, the order of 2 and 3 is unstable and hence we need this method to work with
     * both 2 -> 3 AND 3 -> 2
     *
     * <p>This function is complicated because we cannot mix ordered verification and unordered
     * verification if the same set of argument will appear more than once.
     *
     * @param device target dual mode HID device
     * @param fromTransport from which transport
     * @param toTransport to which transport
     */
    private void verifyTransportSwitch(BluetoothDevice device, int fromTransport, int toTransport) {
        assertThat(fromTransport).isNotEqualTo(toTransport);
        if (!Flags.ignoreUnselectedHidTransportStates()) {
            verifyConnectionState(fromTransport, STATE_DISCONNECTING);
        }

        class Wrapper {
            int mState;
            int mTransport;
        }
        final Wrapper wrap = new Wrapper();

        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, oneOf(fromTransport, toTransport)),
                hasExtra(BluetoothProfile.EXTRA_STATE, oneOf(STATE_CONNECTING, STATE_DISCONNECTED)),
                new CustomTypeSafeMatcher<Intent>("retrieve state & transport") {
                    public boolean matchesSafely(Intent intent) {
                        wrap.mState =
                                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, STATE_CONNECTED);
                        wrap.mTransport =
                                intent.getIntExtra(
                                        BluetoothDevice.EXTRA_TRANSPORT,
                                        BluetoothDevice.TRANSPORT_AUTO);
                        return true;
                    }
                });
        int state = wrap.mState;
        int transport = wrap.mTransport;
        assertThat(state).isAnyOf(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(transport).isAnyOf(TRANSPORT_BREDR, TRANSPORT_LE);

        // Conditionally verify the next intent
        if (transport == fromTransport) {
            assertThat(state).isEqualTo(STATE_DISCONNECTED);
            verifyConnectionState(toTransport, STATE_CONNECTING);
        } else {
            assertThat(state).isEqualTo(STATE_CONNECTING);
            verifyConnectionState(fromTransport, STATE_DISCONNECTED);
        }
        verifyConnectionState(toTransport, STATE_CONNECTED);
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    @SafeVarargs
    private void verifyIntentReceivedUnorderedAtLeast(int atLeast, Matcher<Intent>... matchers) {
        verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private void verifyConnectionState(int transport, int state) {
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
                hasExtra(BluetoothProfile.EXTRA_STATE, state));
    }

    private BluetoothProfile connectToProfile(int profile) {
        mAdapter.getProfileProxy(mTargetContext, mServiceListener, profile);
        ArgumentCaptor<BluetoothProfile> proxyCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.class);
        verify(mServiceListener, timeout(INTENT_TIMEOUT.toMillis()))
                .onServiceConnected(eq(profile), proxyCaptor.capture());
        return proxyCaptor.getValue();
    }
}
