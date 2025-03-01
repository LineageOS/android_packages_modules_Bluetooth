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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.PandoraDevice;
import android.bluetooth.VirtualOnly;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
import org.mockito.stubbing.Answer;

import pandora.HIDGrpc;
import pandora.HidProto.HidServiceType;
import pandora.HidProto.ServiceRequest;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;

import java.time.Duration;
import java.util.Arrays;

/** Test cases for {@link BluetoothHidHost}. */
@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4.class)
@VirtualOnly
public class HidHostDualModeTest {
    private static final String TAG = HidHostDualModeTest.class.getSimpleName();

    private static final String BUMBLE_DEVICE_NAME = "Bumble";
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final int KEYBD_RPT_ID = 1;
    private static final int KEYBD_RPT_SIZE = 9;
    private static final int MOUSE_RPT_ID = 2;
    private static final int MOUSE_RPT_SIZE = 4;
    private BluetoothDevice mDevice;
    private BluetoothHidHost mHidService;
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();
    private HIDGrpc.HIDBlockingStub mHidBlockingStub;

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final EnableBluetoothRule enableBluetoothRule = new EnableBluetoothRule(false, true);

    @Mock private BroadcastReceiver mReceiver;
    private InOrder mInOrder = null;
    private byte[] mReportData = {};

    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;

    private final Answer<Void> mIntentHandler =
            inv -> {
                Log.i(TAG, "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                Intent intent = inv.getArgument(1);
                String action = intent.getAction();
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    Log.d(TAG, "onReceive(): discovery finished");
                } else if (BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    int state =
                            intent.getIntExtra(
                                    BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR);
                    int transport =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_TRANSPORT,
                                    BluetoothDevice.TRANSPORT_AUTO);
                    Log.i(
                            TAG,
                            "Connection state change: device="
                                    + device
                                    + " "
                                    + BluetoothProfile.getConnectionStateName(state)
                                    + "("
                                    + state
                                    + "), transport: "
                                    + transport);
                } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    mBumble.getRemoteDevice().setPairingConfirmation(true);
                    Log.i(TAG, "onReceive(): setPairingConfirmation(true) for " + device);
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    int bondState =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR);
                    int prevBondState =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                    BluetoothAdapter.ERROR);
                    Log.i(
                            TAG,
                            "onReceive(): device "
                                    + device
                                    + " bond state changed from "
                                    + prevBondState
                                    + " to "
                                    + bondState);
                } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    Parcelable[] uuidsRaw =
                            intent.getParcelableArrayExtra(
                                    BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                    if (uuidsRaw == null) {
                        Log.e(TAG, "onReceive(): device " + device + " null uuid list");
                    } else if (uuidsRaw.length == 0) {
                        Log.e(TAG, "onReceive(): device " + device + " 0 length uuid list");
                    } else {
                        ParcelUuid[] uuids =
                                Arrays.copyOf(uuidsRaw, uuidsRaw.length, ParcelUuid[].class);
                        Log.d(
                                TAG,
                                "onReceive(): device "
                                        + device
                                        + ", UUID="
                                        + Arrays.toString(uuids));
                    }
                } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    String deviceName =
                            String.valueOf(intent.getStringExtra(BluetoothDevice.EXTRA_NAME));
                    Log.i(TAG, "Discovered device: " + device + " with name: " + deviceName);
                } else if (BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    int protocolMode =
                            intent.getIntExtra(
                                    BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                                    BluetoothHidHost.PROTOCOL_UNSUPPORTED_MODE);
                    Log.i(TAG, "onReceive(): device " + device + " protocol mode " + protocolMode);
                } else if (BluetoothHidHost.ACTION_HANDSHAKE.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    int handShake =
                            intent.getIntExtra(
                                    BluetoothHidHost.EXTRA_STATUS,
                                    BluetoothHidDevice.ERROR_RSP_UNKNOWN);
                    Log.i(TAG, "onReceive(): device " + device + " handshake status:" + handShake);
                } else if (BluetoothHidHost.ACTION_REPORT.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    mReportData = intent.getByteArrayExtra(BluetoothHidHost.EXTRA_REPORT);
                    int reportBufferSize =
                            intent.getIntExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, 0);
                    Log.i(
                            TAG,
                            "onReceive(): device "
                                    + device
                                    + " reportBufferSize "
                                    + reportBufferSize);
                } else {
                    Log.i(TAG, "onReceive(): unknown intent action " + action);
                }
                return null;
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doAnswer(mIntentHandler).when(mReceiver).onReceive(any(), any());

        mInOrder = inOrder(mReceiver);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_HANDSHAKE);
        filter.addAction(BluetoothHidHost.ACTION_REPORT);
        mContext.registerReceiver(mReceiver, filter);

        // Get profile proxies
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.HID_HOST);
        mHidService = (BluetoothHidHost) verifyProfileServiceConnected(BluetoothProfile.HID_HOST);
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.A2DP);
        BluetoothA2dp a2dpService =
                (BluetoothA2dp) verifyProfileServiceConnected(BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.HEADSET);
        BluetoothHeadset hfpService =
                (BluetoothHeadset) verifyProfileServiceConnected(BluetoothProfile.HEADSET);

        mHidBlockingStub = mBumble.hidBlocking();
        mHidBlockingStub.registerService(
                ServiceRequest.newBuilder()
                        .setServiceType(HidServiceType.SERVICE_TYPE_BOTH)
                        .build());
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .build();
        mBumble.hostBlocking().advertise(request);

        mDevice = mBumble.getRemoteDevice();

        // Remove bond if the device is already bonded
        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mDevice);
        }
        assertThat(mDevice.createBond(TRANSPORT_BREDR)).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        if (a2dpService.getConnectionPolicy(mDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(a2dpService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (hfpService.getConnectionPolicy(mDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(hfpService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }

        // Have to use Hamcrest matchers instead of Mockito matchers in MockitoHamcrest context
        verifyConnectionState(mDevice, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING));
        verifyConnectionState(mDevice, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED));
        assertThat(mHidService.getPreferredTransport(mDevice)).isEqualTo(TRANSPORT_BREDR);
        // Two ACTION_UUIDs are returned after pairing with dual mode HID device
        // 2nd ACTION_UUID and ACTION_CONNECTION_STATE_CHANGED has race condition, hence unordered
        verifyIntentReceivedUnorderedAtLeast(
                1,
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_UUID,
                        allOf(
                                Matchers.hasItemInArray(BluetoothUuid.HOGP),
                                Matchers.hasItemInArray(BluetoothUuid.HID))));

        if (mHidService.getPreferredTransport(mDevice) == TRANSPORT_BREDR) {
            // Switch to LE transport to prepare for test cases
            mHidService.setPreferredTransport(mDevice, TRANSPORT_LE);
            verifyTransportSwitch(mDevice, TRANSPORT_BREDR, TRANSPORT_LE);
        }

        assertThat(mHidService.getPreferredTransport(mDevice)).isEqualTo(TRANSPORT_LE);
    }

    @After
    public void tearDown() throws Exception {
        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mDevice);
        }
        mContext.unregisterReceiver(mReceiver);
    }

    /**
     * Test HID Preferred transport selection Test case
     *
     * <ol>
     *   <li>1. Android to creates bonding and HID connected with default transport.
     *   <li>2. Android switch the transport to LE and Verifies the transport
     *   <li>3. Android switch the transport to BR/EDR and Verifies the transport
     * </ol>
     */
    @Test
    public void setPreferredTransportTest() {
        // BR/EDR transport
        mHidService.setPreferredTransport(mDevice, TRANSPORT_BREDR);
        verifyTransportSwitch(mDevice, TRANSPORT_LE, TRANSPORT_BREDR);
        // Check if the API returns the correct transport
        assertThat(mHidService.getPreferredTransport(mDevice)).isEqualTo(TRANSPORT_BREDR);
    }

    /**
     * Test Get Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android get report and verifies the report
     * </ol>
     */
    @Test
    public void hogpGetReportTest() throws Exception {
        // Keyboard report
        mReportData = new byte[0];
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, (byte) KEYBD_RPT_ID, 0);
        // Report Buffer = Report ID (1 byte) + Report Data (KEYBD_RPT_SIZE byte)
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_REPORT),
                hasExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, KEYBD_RPT_SIZE + 1));
        assertThat(mReportData).isNotNull();
        assertThat(mReportData.length).isGreaterThan(0);
        assertThat(mReportData[0]).isEqualTo(KEYBD_RPT_ID);

        // Mouse report
        mReportData = new byte[0];
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, (byte) MOUSE_RPT_ID, 0);
        // Report Buffer = Report ID (1 byte) + Report Data (MOUSE_RPT_SIZE byte)
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_REPORT),
                hasExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, MOUSE_RPT_SIZE + 1));
        assertThat(mReportData).isNotNull();
        assertThat(mReportData.length).isGreaterThan(0);
        assertThat(mReportData[0]).isEqualTo(MOUSE_RPT_ID);
    }

    /**
     * Test Get Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Gets the Protocol mode and verifies the mode
     * </ol>
     */
    @Test
    public void hogpGetProtocolModeTest() {
        mHidService.getProtocolMode(mDevice);
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED),
                hasExtra(
                        BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                        BluetoothHidHost.PROTOCOL_REPORT_MODE));
    }

    /**
     * Test Set Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Sets the Protocol mode and verifies the mode
     * </ol>
     */
    @Test
    public void hogpSetProtocolModeTest() throws Exception {
        mHidService.setProtocolMode(mDevice, BluetoothHidHost.PROTOCOL_BOOT_MODE);
        // Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS, (int) BluetoothHidDevice.ERROR_RSP_SUCCESS));
    }

    /**
     * Test Set Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Set report and verifies the report
     * </ol>
     */
    @Test
    public void hogpSetReportTest() throws Exception {
        // Keyboard report
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, "010203040506070809");
        /// Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS, (int) BluetoothHidDevice.ERROR_RSP_SUCCESS));
        // Mouse report
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, "02030405");
        // Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS, (int) BluetoothHidDevice.ERROR_RSP_SUCCESS));
    }

    /**
     * Test Virtual Unplug from Hid Host
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Virtual Unplug and verifies Bonding
     * </ol>
     */
    @Test
    public void hogpVirtualUnplugFromHidHostTest() throws Exception {
        mHidService.virtualUnplug(mDevice);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
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
        verifyConnectionState(mDevice, equalTo(fromTransport), equalTo(STATE_DISCONNECTING));

        // Capture the next intent with filter
        // Filter is necessary as otherwise it will corrupt all other unordered verifications
        final Intent[] savedIntent = {null};
        verifyIntentReceived(
                new CustomTypeSafeMatcher<>("Intent Matcher") {
                    public boolean matchesSafely(Intent intent) {
                        savedIntent[0] = intent;
                        return AllOf.allOf(
                                        hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                                        hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                                        hasExtra(
                                                BluetoothDevice.EXTRA_TRANSPORT,
                                                oneOf(fromTransport, toTransport)),
                                        hasExtra(
                                                BluetoothProfile.EXTRA_STATE,
                                                oneOf(STATE_CONNECTING, STATE_DISCONNECTED)))
                                .matches(intent);
                    }
                });

        // Verify saved intent is correct
        assertThat(savedIntent[0]).isNotNull();
        Intent intent = savedIntent[0];
        assertThat(intent.getAction()).isNotNull();
        assertThat(intent.getAction()).isEqualTo(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        assertThat(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class))
                .isEqualTo(device);
        assertThat(intent.hasExtra(BluetoothProfile.EXTRA_STATE)).isTrue();
        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, STATE_CONNECTED);
        assertThat(state).isAnyOf(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(intent.hasExtra(BluetoothDevice.EXTRA_TRANSPORT)).isTrue();
        int transport =
                intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO);
        assertThat(transport).isAnyOf(TRANSPORT_BREDR, TRANSPORT_LE);

        // Conditionally verify the next intent
        if (transport == fromTransport) {
            assertThat(state).isEqualTo(STATE_DISCONNECTED);
            verifyConnectionState(mDevice, equalTo(toTransport), equalTo(STATE_CONNECTING));
        } else {
            assertThat(state).isEqualTo(STATE_CONNECTING);
            verifyConnectionState(mDevice, equalTo(fromTransport), equalTo(STATE_DISCONNECTED));
        }
        verifyConnectionState(mDevice, equalTo(toTransport), equalTo(STATE_CONNECTED));
    }

    private void verifyConnectionState(
            BluetoothDevice device, Matcher<Integer> transport, Matcher<Integer> state) {
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
                hasExtra(BluetoothProfile.EXTRA_STATE, state));
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
    private void verifyIntentReceivedUnorderedAtLeast(int atLeast, Matcher<Intent>... matchers) {
        verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private BluetoothProfile verifyProfileServiceConnected(int profile) {
        ArgumentCaptor<BluetoothProfile> proxyCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.class);
        verify(mProfileServiceListener, timeout(INTENT_TIMEOUT.toMillis()))
                .onServiceConnected(eq(profile), proxyCaptor.capture());
        return proxyCaptor.getValue();
    }
}
