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

package android.bluetooth.hfp;

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.PandoraDevice;
import android.bluetooth.Utils;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.protobuf.ByteString;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

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

import pandora.HFPGrpc;
import pandora.HfpProto.DisableSlcAsHandsfreeRequest;
import pandora.HfpProto.EnableSlcAsHandsfreeRequest;
import pandora.HostProto;
import pandora.HostProto.WaitConnectionRequest;

import java.time.Duration;

@RunWith(TestParameterInjector.class)
public class HfpTest {
    private static final String TAG = HfpTest.class.getSimpleName();

    @Rule(order = 0)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 1)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 2)
    public final EnableBluetoothRule mEnableBluetoothRule = new EnableBluetoothRule();

    @Mock private BroadcastReceiver mReceiver;
    @Mock private BluetoothProfile.ServiceListener mServiceListener;

    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mTargetContext.getSystemService(BluetoothManager.class).getAdapter();

    private HFPGrpc.HFPBlockingStub mHfBlockingStub;
    private BluetoothDevice mBumbleDevice;
    private BluetoothHeadset mHfpService;
    private InOrder mInOrder;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mReceiver);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        mTargetContext.registerReceiver(mReceiver, filter);
        Utils.setupIntentLogger(TAG, mReceiver);

        mHfpService = (BluetoothHeadset) connectToProfile(BluetoothProfile.HEADSET);

        mHfBlockingStub = mBumble.hfBlocking();
        mBumbleDevice = mBumble.getRemoteDevice();
    }

    @After
    public void tearDown() {
        removeBond();
    }

    @Test
    public void connectAndDisconnectFromAg() {
        prepareBumbleDeviceAsBondedAndDisconnected();

        assertThat(mHfpService.connect(mBumbleDevice)).isTrue();
        verifyConnectionState(STATE_CONNECTING);
        verifyConnectionState(STATE_CONNECTED);
        assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_CONNECTED);

        assertThat(mHfpService.disconnect(mBumbleDevice)).isTrue();
        verifyConnectionState(STATE_DISCONNECTING);
        verifyConnectionState(STATE_DISCONNECTED);
        assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void connectAndDisconnectFromHf() {
        prepareBumbleDeviceAsBondedAndDisconnected();

        // Obtain the connection which will be used for EnableSlc
        ByteString address =
                ByteString.copyFrom(Utils.addressBytesFromString(mAdapter.getAddress()));
        WaitConnectionRequest connectionRequest =
                WaitConnectionRequest.newBuilder().setAddress(address).build();
        HostProto.WaitConnectionResponse response =
                mBumble.hostBlocking().waitConnection(connectionRequest);

        // Enable Slc from HF/Bumble side
        mHfBlockingStub.enableSlcAsHandsfree(
                EnableSlcAsHandsfreeRequest.newBuilder()
                        .setConnection(response.getConnection())
                        .build());

        verifyConnectionState(STATE_CONNECTING);
        verifyConnectionState(STATE_CONNECTED);
        assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_CONNECTED);

        // Disable Slc from HF/Bumble side
        mHfBlockingStub.disableSlcAsHandsfree(
                DisableSlcAsHandsfreeRequest.newBuilder()
                        .setConnection(response.getConnection())
                        .build());

        verifyConnectionState(STATE_DISCONNECTING);
        verifyConnectionState(STATE_DISCONNECTED);
        assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    private void prepareBumbleDeviceAsBondedAndDisconnected() {
        if (mBumbleDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_DISCONNECTED);
            return;
        }

        assertThat(mBumbleDevice.createBond(TRANSPORT_BREDR)).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice));

        mBumbleDevice.setPairingConfirmation(true);
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        // Connection is automatically triggered by pairing
        verifyConnectionState(STATE_CONNECTING);
        verifyConnectionState(STATE_CONNECTED);
        assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_CONNECTED);

        assertThat(mHfpService.disconnect(mBumbleDevice)).isTrue();
        verifyConnectionState(STATE_DISCONNECTING);
        verifyConnectionState(STATE_DISCONNECTED);
        assertThat(mHfpService.getConnectionState(mBumbleDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    private void removeBond() {
        if (mBumbleDevice.removeBond()) {
            verifyIntentReceived(
                    hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                    hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));
        }
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private void verifyConnectionState(int state) {
        verifyIntentReceived(
                hasAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
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
