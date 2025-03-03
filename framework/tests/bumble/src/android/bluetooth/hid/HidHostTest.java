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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.equalTo;
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
import android.bluetooth.PandoraDevice;
import android.bluetooth.VirtualOnly;
import android.bluetooth.cts.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.protobuf.Empty;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
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
import pandora.HidProto.ProtocolModeEvent;
import pandora.HidProto.ReportEvent;
import pandora.HidProto.ServiceRequest;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link BluetoothHidHost}. */
@RunWith(AndroidJUnit4.class)
@VirtualOnly
public class HidHostTest {
    private static final String TAG = HidHostTest.class.getSimpleName();

    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private BluetoothDevice mDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;
    private BluetoothA2dp mA2dpService;
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();
    private HIDGrpc.HIDBlockingStub mHidBlockingStub;
    private byte mReportId;
    private static final int KEYBD_RPT_ID = 1;
    private static final int KEYBD_RPT_SIZE = 9;
    private static final int MOUSE_RPT_ID = 2;
    private static final int MOUSE_RPT_SIZE = 4;
    private static final int INVALID_RPT_ID = 3;
    private static final int CONNECTION_TIMEOUT_MS = 2_000;
    private static final int BT_ON_DELAY_MS = 3000;
    private static final int REPORT_UPDATE_TIMEOUT_MS = 100;

    private static final Duration PROTO_MODE_TIMEOUT = Duration.ofSeconds(10);

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
    private CompletableFuture<Boolean> mIsReportUpdated;
    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;

    @SuppressLint("MissingPermission")
    private final Answer<Void> mIntentHandler =
            inv -> {
                Log.i(TAG, "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                Intent intent = inv.getArgument(1);
                String action = intent.getAction();
                BluetoothDevice device;
                switch (action) {
                    case BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED:
                        device =
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
                        break;
                    case BluetoothDevice.ACTION_PAIRING_REQUEST:
                        device =
                                intent.getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                        mBumble.getRemoteDevice().setPairingConfirmation(true);
                        Log.i(TAG, "onReceive(): setPairingConfirmation(true) for " + device);
                        break;
                    case BluetoothAdapter.ACTION_STATE_CHANGED:
                        int adapterState =
                                intent.getIntExtra(
                                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                        Log.i(TAG, "Adapter state change:" + adapterState);
                        break;
                    case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                        device =
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
                        break;
                    case BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED:
                        device =
                                intent.getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                        int protocolMode =
                                intent.getIntExtra(
                                        BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                                        BluetoothHidHost.PROTOCOL_UNSUPPORTED_MODE);
                        Log.i(
                                TAG,
                                "onReceive(): device " + device + " protocol mode " + protocolMode);
                        break;
                    case BluetoothHidHost.ACTION_HANDSHAKE:
                        device =
                                intent.getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                        int handShake =
                                intent.getIntExtra(
                                        BluetoothHidHost.EXTRA_STATUS,
                                        BluetoothHidDevice.ERROR_RSP_UNKNOWN);
                        Log.i(
                                TAG,
                                "onReceive(): device " + device + " handshake status:" + handShake);
                        break;
                    case BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS:
                        int virtualUnplug =
                                intent.getIntExtra(
                                        BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS,
                                        BluetoothHidHost.VIRTUAL_UNPLUG_STATUS_FAIL);
                        Log.i(TAG, "onReceive(): Virtual Unplug status:" + virtualUnplug);
                        break;
                    case BluetoothHidHost.ACTION_REPORT:
                        device =
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
                        if (mIsReportUpdated != null) {
                            mIsReportUpdated.complete(true);
                        }
                        break;
                    case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                        device =
                                intent.getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                        Log.i(TAG, "onReceive(): ACL Disconnected with device: " + device);
                        break;
                    default:
                        Log.i(TAG, "onReceive(): unknown intent action " + action);
                        break;
                }
                return null;
            };

    @SuppressLint("MissingPermission")
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doAnswer(mIntentHandler).when(mReceiver).onReceive(any(), any());

        mInOrder = inOrder(mReceiver);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_HANDSHAKE);
        filter.addAction(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS);
        filter.addAction(BluetoothHidHost.ACTION_REPORT);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
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
                        .setServiceType(HidServiceType.SERVICE_TYPE_HID)
                        .build());
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
        verifyConnectionState(mDevice, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING));
        verifyConnectionState(mDevice, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED));
        assertThat(mHidService.getPreferredTransport(mDevice)).isEqualTo(TRANSPORT_BREDR);
    }

    @SuppressLint("MissingPermission")
    @After
    public void tearDown() throws Exception {

        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mDevice);
        }
        mContext.unregisterReceiver(mReceiver);
    }

    /**
     * Test HID Disconnection:
     *
     * <ol>
     *   <li>1. Android tries to create bond, emitting bonding intent 4. Android confirms the
     *       pairing via pairing request intent
     *   <li>2. Bumble confirms the pairing internally
     *   <li>3. Android tries to HID connect and verifies Connection state intent
     *   <li>4. Bumble Disconnect the HID and Android verifies Connection state intent
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void disconnectHidDeviceTest() throws Exception {

        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        verifyProfileDisconnectionState();
    }

    /**
     * Test HID Device reconnection when connection policy change:
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     *   <li>5. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>6. Android disable connection policy
     *   <li>7. Bumble connect the HID and Android verifies Connection state intent
     *   <li>8. Android enable connection policy
     *   <li>9. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>10. Bumble connect the HID and Android verifies Connection state intent
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidReconnectionWhenConnectionPolicyChangeTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);

        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        verifyProfileDisconnectionState();

        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        verifyIncomingProfileConnectionState();

        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        verifyProfileDisconnectionState();

        assertThat(mHidService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN)).isTrue();

        reconnectionFromRemoteAndVerifyDisconnectedState();

        assertThat(mHidService.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
                hasExtra(BluetoothProfile.EXTRA_STATE, STATE_CONNECTING));
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
                hasExtra(BluetoothProfile.EXTRA_STATE, STATE_CONNECTED));

        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        verifyProfileDisconnectionState();

        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        verifyIncomingProfileConnectionState();
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy allowed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. BT restart on Android
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidReconnectionAfterBTrestartWithConnectionPolicyAllowedTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);

        bluetoothRestart();

        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        verifyIncomingProfileConnectionState();
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy disallowed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Android disable the connection policy
     *   <li>4. BT restart on Android
     *   <li>5. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidReconnectionAfterBTrestartWithConnectionPolicyiDisallowedTest()
            throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);

        assertThat(mHidService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN)).isTrue();

        bluetoothRestart();
        reconnectionFromRemoteAndVerifyDisconnectedState();
    }

    /**
     * Test HID Device reconnection when device is removed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Android disconnect and remove the bond
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidReconnectionAfterDeviceRemovedTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        verifyProfileDisconnectionState();

        mDevice.removeBond();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        reconnectionFromRemoteAndVerifyDisconnectedState();
    }

    /**
     * Test Virtual Unplug from Hid Host
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Virtual Unplug and verifies Bonding
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidVirtualUnplugFromHidHostTest() throws Exception {
        mHidService.virtualUnplug(mDevice);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
    }

    /**
     * Test Virtual Unplug from Hid Device
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Bumble Virtual Unplug and Android verifies Bonding
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidVirtualUnplugFromHidDeviceTest() throws Exception {
        mHidBlockingStub.virtualCableUnplugHost(Empty.getDefaultInstance());
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS),
                hasExtra(
                        BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS,
                        BluetoothHidHost.VIRTUAL_UNPLUG_STATUS_SUCCESS));
    }

    /**
     * Test Get Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Gets the Protocol mode and verifies the mode
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidGetProtocolModeTest() throws Exception {
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
    @SuppressLint("MissingPermission")
    @Test
    @Ignore("b/349351673: sets wrong protocol mode value")
    public void hidSetProtocolModeTest() throws Exception {
        Iterator<ProtocolModeEvent> mHidProtoModeEventObserver =
                mHidBlockingStub
                        .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onSetProtocolMode(Empty.getDefaultInstance());
        mHidService.setProtocolMode(mDevice, BluetoothHidHost.PROTOCOL_BOOT_MODE);
        // Must cast ERROR_RSP_UNSUPPORTED_REQ, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS,
                        (int) BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ));

        if (mHidProtoModeEventObserver.hasNext()) {
            ProtocolModeEvent hidProtoModeEvent = mHidProtoModeEventObserver.next();
            Log.i(TAG, "Protocol mode:" + hidProtoModeEvent.getProtocolMode());
            assertThat(hidProtoModeEvent.getProtocolModeValue())
                    .isEqualTo(BluetoothHidHost.PROTOCOL_BOOT_MODE);
        }
    }

    /**
     * Test Get Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android get report and verifies the report
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidGetReportTest() throws Exception {
        // Keyboard report
        mReportData = new byte[0];
        mIsReportUpdated = new CompletableFuture<>();
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, (byte) KEYBD_RPT_ID, 0);
        // Report Buffer = Report ID (1 byte) + Report Data (KEYBD_RPT_SIZE byte)
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_REPORT),
                hasExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, KEYBD_RPT_SIZE + 1));
        mIsReportUpdated
                .completeOnTimeout(null, REPORT_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();
        assertThat(mReportData).isNotNull();
        assertThat(mReportData.length).isGreaterThan(0);
        assertThat(mReportData[0]).isEqualTo(KEYBD_RPT_ID);

        // Mouse report
        mReportData = new byte[0];
        mIsReportUpdated = new CompletableFuture<>();
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, (byte) MOUSE_RPT_ID, 0);
        // Report Buffer = Report ID (1 byte) + Report Data (MOUSE_RPT_SIZE byte)
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_REPORT),
                hasExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, MOUSE_RPT_SIZE + 1));
        mIsReportUpdated
                .completeOnTimeout(null, REPORT_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();
        assertThat(mReportData).isNotNull();
        assertThat(mReportData.length).isGreaterThan(0);
        assertThat(mReportData[0]).isEqualTo(MOUSE_RPT_ID);

        // Invalid report
        mHidService.getReport(
                mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, (byte) INVALID_RPT_ID, (int) 0);
        // Must cast ERROR_RSP_INVALID_RPT_ID, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS,
                        (int) BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID));
    }

    /**
     * Test Set Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Set report and verifies the report
     * </ol>
     */
    @SuppressLint("MissingPermission")
    @Test
    public void hidSetReportTest() throws Exception {
        Iterator<ReportEvent> mHidReportEventObserver =
                mHidBlockingStub
                        .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onSetReport(Empty.getDefaultInstance());

        // Todo: as a workaround added 50ms delay.
        // To be removed once root cause is identified for b/382180335
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeOnTimeout(null, 50, TimeUnit.MILLISECONDS).join();

        // Keyboard report
        String kbReportData = "010203040506070809";
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, kbReportData);
        /// Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS, (int) BluetoothHidDevice.ERROR_RSP_SUCCESS));

        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(KEYBD_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(kbReportData.substring(2));
        }
        // Keyboard report - Invalid param
        mHidService.setReport(
                mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, kbReportData.substring(0, 10));
        // Must cast ERROR_RSP_INVALID_PARAM, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS,
                        (int) BluetoothHidDevice.ERROR_RSP_INVALID_PARAM));

        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(KEYBD_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(kbReportData.substring(2, 10));
        }
        // Mouse report
        String mouseReportData = "02030405";
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, mouseReportData);
        // Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS, (int) BluetoothHidDevice.ERROR_RSP_SUCCESS));

        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(MOUSE_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(mouseReportData.substring(2));
        }
        // Invalid report id
        String inValidReportData = "0304";
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, inValidReportData);
        // Must cast ERROR_RSP_INVALID_RPT_ID, otherwise, it won't match with the int extra
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
                hasExtra(
                        BluetoothHidHost.EXTRA_STATUS,
                        (int) BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID));
        if (mHidReportEventObserver.hasNext()) {
            ReportEvent hidReportEvent = mHidReportEventObserver.next();
            assertThat(hidReportEvent.getReportTypeValue())
                    .isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT);
            assertThat(hidReportEvent.getReportIdValue()).isEqualTo(INVALID_RPT_ID);
            assertThat(hidReportEvent.getReportData()).isEqualTo(inValidReportData.substring(2));
        }
    }

    private void verifyConnectionState(
            BluetoothDevice device, Matcher<Integer> transport, Matcher<Integer> state) {

        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
                hasExtra(BluetoothProfile.EXTRA_STATE, state));
    }

    private void verifyIncomingProfileConnectionState() {
        // for incoming connection, connection state transit
        // from STATE_ACCEPTING -->STATE_CONNECTED
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
                hasExtra(BluetoothProfile.EXTRA_STATE, STATE_CONNECTED));
    }

    private void verifyProfileDisconnectionState() {
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
                hasExtra(BluetoothProfile.EXTRA_STATE, STATE_DISCONNECTING));
        verifyIntentReceived(
                hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
                hasExtra(BluetoothProfile.EXTRA_STATE, STATE_DISCONNECTED));
    }

    private void reconnectionFromRemoteAndVerifyDisconnectedState() throws Exception {
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeOnTimeout(null, CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
        assertThat(mHidService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    private void bluetoothRestart() throws Exception {
        mAdapter.disable();
        verifyIntentReceived(
                hasAction(BluetoothAdapter.ACTION_STATE_CHANGED),
                hasExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
        // Without delay, some time HID auto reconnection
        // triggered by BluetoothAdapterService
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeOnTimeout(null, BT_ON_DELAY_MS, TimeUnit.MILLISECONDS).join();

        mAdapter.enable();
        verifyIntentReceived(
                hasAction(BluetoothAdapter.ACTION_STATE_CHANGED),
                hasExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON));
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
    private void verifyIntentReceivedAtLeast(int atLeast, Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
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
