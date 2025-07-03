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

package android.bluetooth.hid;

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;

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

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.PandoraDevice;
import android.bluetooth.cts.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

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

import pandora.HIDGrpc;
import pandora.HidProto.HidServiceType;
import pandora.HidProto.ServiceRequest;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test cases for {@link BluetoothHidDevice}. */
@RunWith(AndroidJUnit4.class)
public class HidDeviceTest {
    private static final String TAG = HidDeviceTest.class.getSimpleName();

    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private BluetoothDevice mDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHidDevice mHidDeviceService;
    private BluetoothA2dp mA2dpService;
    private BluetoothHeadset mHfpService;
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();

    private HIDGrpc.HIDBlockingStub mHidBlockingStub;

    // HID Device role
    private static final String SDP_NAME = "BumbleBluetooth";
    private static final String SDP_DESCRIPTION = "BumbleBluetooth HID Device test";
    private static final String SDP_PROVIDER = "Android";
    private static final int QOS_TOKEN_RATE = 800; // 9 bytes * 1000000 us / 11250 us
    private static final int QOS_TOKEN_BUCKET_SIZE = 9;
    private static final int QOS_PEAK_BANDWIDTH = 0;
    private static final int QOS_LATENCY = 11250;
    private static final byte[] HIDD_REPORT_DESC = {};

    private ExecutorService mExecutor;

    private final BluetoothHidDeviceAppSdpSettings mSdpSettings =
            new BluetoothHidDeviceAppSdpSettings(
                    SDP_NAME,
                    SDP_DESCRIPTION,
                    SDP_PROVIDER,
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    HIDD_REPORT_DESC);

    private final BluetoothHidDeviceAppQosSettings mOutQos =
            new BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    QOS_TOKEN_RATE,
                    QOS_TOKEN_BUCKET_SIZE,
                    QOS_PEAK_BANDWIDTH,
                    QOS_LATENCY,
                    BluetoothHidDeviceAppQosSettings.MAX);

    @Mock private BluetoothHidDevice.Callback mCallback;

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
    @Mock private BluetoothProfile.ServiceListener mProfileServiceListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doAnswer(
                        inv -> {
                            mBumble.getRemoteDevice().setPairingConfirmation(true);
                            return null;
                        })
                .when(mReceiver)
                .onReceive(
                        any(),
                        MockitoHamcrest.argThat(hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST)));

        mInOrder = inOrder(mReceiver, mCallback);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mContext.registerReceiver(mReceiver, filter);
        // Get profile proxies
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.HID_HOST);
        mHidService = (BluetoothHidHost) verifyProfileServiceConnected(BluetoothProfile.HID_HOST);
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.HID_DEVICE);
        mHidDeviceService =
                (BluetoothHidDevice) verifyProfileServiceConnected(BluetoothProfile.HID_DEVICE);
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.A2DP);
        mA2dpService = (BluetoothA2dp) verifyProfileServiceConnected(BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.HEADSET);
        mHfpService = (BluetoothHeadset) verifyProfileServiceConnected(BluetoothProfile.HEADSET);

        mHidBlockingStub = mBumble.hidBlocking();
        mHidBlockingStub.registerService(
                ServiceRequest.newBuilder()
                        .setServiceType(HidServiceType.SERVICE_TYPE_HID)
                        .build());

        mExecutor = Executors.newSingleThreadExecutor();

        mDevice = mBumble.getRemoteDevice();
        // Remove bond if the device is already bonded
        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mDevice);
        }
        assertThat(mHidDeviceService.registerApp(mSdpSettings, null, mOutQos, mExecutor, mCallback))
                .isTrue();
        verifyAppStatusChanged(null, true);
    }

    @After
    public void tearDown() throws Exception {
        assertThat(mHidDeviceService.unregisterApp()).isTrue();
        verifyAppStatusChanged(null, false);
        mContext.unregisterReceiver(mReceiver);
    }

    /**
     * Test enable HID Device role and connect with a remote device.
     *
     * <ol>
     *   <li>1. Create bond with a remote device and connect HID Device profile
     *   <li>2. Remove bond with the remote device
     * </ol>
     */
    @Test
    public void remoteDeviceConnectToHidDeviceServiceTest() throws Exception {
        createBond(mDevice);
        if (mA2dpService.getConnectionPolicy(mDevice) != CONNECTION_POLICY_FORBIDDEN) {
            assertThat(mA2dpService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHfpService.getConnectionPolicy(mDevice) != CONNECTION_POLICY_FORBIDDEN) {
            assertThat(mHfpService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHidService.getConnectionPolicy(mDevice) != CONNECTION_POLICY_FORBIDDEN) {
            assertThat(mHidService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }

        assertThat(mHidDeviceService.connect(mDevice)).isTrue();
        verifyHidDeviceConnectionStateChanged(mDevice, STATE_CONNECTING);
        verifyHidDeviceConnectionStateChanged(mDevice, STATE_CONNECTED);

        assertThat(mHidDeviceService.getConnectionState(mDevice))
                .isEqualTo(BluetoothHidDevice.STATE_CONNECTED);

        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mDevice);
        }
    }

    /**
     * Test disable HID Device role and connect a remote device to HID Host service.
     *
     * <ol>
     *   <li>1. Unregister the app, connect a remote device to HID Host service
     *   <li>2. Remove bond with the remote device
     *   <li>3. Register the app
     * </ol>
     */
    @Test
    public void switchHidDeviceToHidHostTest() throws Exception {
        assertThat(mHidDeviceService.unregisterApp()).isTrue();
        verifyAppStatusChanged(null, false);

        verifyRemoteDeviceConnectToHidHostService();

        assertThat(mHidDeviceService.registerApp(mSdpSettings, null, mOutQos, mExecutor, mCallback))
                .isTrue();
        verifyAppStatusChanged(null, true);
    }

    private void verifyRemoteDeviceConnectToHidHostService() {
        createBond(mDevice);
        if (mA2dpService.getConnectionPolicy(mDevice) != CONNECTION_POLICY_FORBIDDEN) {
            assertThat(mA2dpService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHfpService.getConnectionPolicy(mDevice) != CONNECTION_POLICY_FORBIDDEN) {
            assertThat(mHfpService.setConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHidService.getConnectionPolicy(mDevice) != CONNECTION_POLICY_ALLOWED) {
            assertThat(mHidService.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED))
                    .isTrue();
        }
        assertThat(mDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verifyConnectionState(mDevice, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING));
        verifyConnectionState(mDevice, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED));
        assertThat(mHidService.getPreferredTransport(mDevice)).isEqualTo(TRANSPORT_BREDR);
        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            removeBond(mDevice);
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

    private void removeBond(BluetoothDevice device) {
        assertThat(device.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
    }

    private void createBond(BluetoothDevice device) {
        assertThat(device.createBond(TRANSPORT_BREDR)).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private void verifyAppStatusChanged(BluetoothDevice device, boolean status) {
        mInOrder.verify(mCallback, timeout(INTENT_TIMEOUT.toMillis()))
                .onAppStatusChanged(eq(device), eq(status));
    }

    private void verifyHidDeviceConnectionStateChanged(BluetoothDevice device, int state) {
        mInOrder.verify(mCallback, timeout(INTENT_TIMEOUT.toMillis()))
                .onConnectionStateChanged(eq(device), eq(state));
    }

    private BluetoothProfile verifyProfileServiceConnected(int profile) {
        ArgumentCaptor<BluetoothProfile> proxyCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.class);
        verify(mProfileServiceListener, timeout(INTENT_TIMEOUT.toMillis()))
                .onServiceConnected(eq(profile), proxyCaptor.capture());
        return proxyCaptor.getValue();
    }
}
