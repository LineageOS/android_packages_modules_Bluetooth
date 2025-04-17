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

package android.bluetooth;

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.test_utils.BlockingBluetoothAdapter;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.Context;
import android.os.SystemProperties;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.OwnAddressType;

import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class ConnParamTest {
    private static final int MIN_CONN_INTERVAL_RELAXED =
            SystemProperties.getInt("bluetooth.core.le.min_connection_interval_relaxed", 0x0018);
    private static final int MAX_CONN_INTERVAL_RELAXED =
            SystemProperties.getInt("bluetooth.core.le.max_connection_interval_relaxed", 0x0028);

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 2)
    public final EnableBluetoothRule mEnableBluetoothRule = new EnableBluetoothRule(false, true);

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();

    private Host mHost;
    private BluetoothDevice mRemoteLeDevice;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();

        mHost = new Host(mContext);
        mRemoteLeDevice =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        mRemoteLeDevice.removeBond();
    }

    @After
    public void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        if (bondedDevices.contains(mRemoteLeDevice)) {
            mHost.removeBondAndVerify(mRemoteLeDevice);
        }
        mHost.close();
    }

    @RequiresFlagsEnabled(Flags.FLAG_INITIAL_CONN_PARAMS_P1)
    @Test
    public void connParamsAreRelaxedAfterServiceDiscovery() {
        checkAggressiveConnectionWillBeUsed();

        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        ArgumentCaptor<Integer> connectionIntervalCaptor = ArgumentCaptor.forClass(Integer.class);

        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback, false);

        // Wait until service discovery is done and parameters are relaxed.
        verify(gattCallback, timeout(10_000).times(1))
                .onConnectionUpdated(
                        any(), connectionIntervalCaptor.capture(), anyInt(), anyInt(), anyInt());

        List<Integer> capturedConnectionIntervals = connectionIntervalCaptor.getAllValues();
        assertThat(capturedConnectionIntervals).hasSize(1);

        // Since aggressive parameters are used in the initial connection,
        // there should be only one connection parameters update event for relaxing them.
        int relaxedConnIntervalAfterServiceDiscovery = capturedConnectionIntervals.get(0);
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtLeast(MIN_CONN_INTERVAL_RELAXED);
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtMost(MAX_CONN_INTERVAL_RELAXED);

        disconnectAndWaitDisconnection(gatt, gattCallback);
    }

    @RequiresFlagsEnabled(Flags.FLAG_INITIAL_CONN_PARAMS_P1)
    @Test
    public void connParamsAreRelaxedForBondedDevice_withBluetoothRestart() {
        checkAggressiveConnectionWillBeUsed();
        createLeBondAndWaitBonding(mRemoteLeDevice);

        // Turn BT off, and then turn it on
        assertThat(BlockingBluetoothAdapter.disable(false)).isTrue();
        assertThat(BlockingBluetoothAdapter.enable()).isTrue();

        // Connect GATT
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        ArgumentCaptor<Integer> connectionIntervalCaptor = ArgumentCaptor.forClass(Integer.class);
        BluetoothGatt gatt = connectGattAndWaitConnection(gattCallback, false);

        // Wait for the connection parameter update event
        verify(gattCallback, timeout(3_000))
                .onConnectionUpdated(
                        any(), connectionIntervalCaptor.capture(), anyInt(), anyInt(), anyInt());

        List<Integer> capturedConnectionIntervals = connectionIntervalCaptor.getAllValues();
        assertThat(capturedConnectionIntervals).hasSize(1);

        // Since aggressive parameters are used in the initial connection,
        // there should be only one connection parameters update event for relaxing them.
        int relaxedConnIntervalAfterServiceDiscovery = capturedConnectionIntervals.get(0);
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtLeast(MIN_CONN_INTERVAL_RELAXED);
        assertThat(relaxedConnIntervalAfterServiceDiscovery).isAtMost(MAX_CONN_INTERVAL_RELAXED);

        disconnectAndWaitDisconnection(gatt, gattCallback);
    }

    private BluetoothGatt connectGattAndWaitConnection(
            BluetoothGattCallback callback, boolean autoConnect) {
        final int status = GATT_SUCCESS;
        final int state = STATE_CONNECTED;

        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> observer =
                advertiseWithBumble();

        BluetoothGatt gatt = mRemoteLeDevice.connectGatt(mContext, autoConnect, callback);
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), eq(status), eq(state));
        observer.cancel("Canceling advertisement");

        return gatt;
    }

    private static void disconnectAndWaitDisconnection(
            BluetoothGatt gatt, BluetoothGattCallback callback) {
        final int state = STATE_DISCONNECTED;
        gatt.disconnect();
        verify(callback, timeout(1000)).onConnectionStateChange(eq(gatt), anyInt(), eq(state));

        gatt.close();
        gatt = null;
    }

    private StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> advertiseWithBumble() {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .build();

        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();

        mBumble.host().advertise(request, responseObserver);

        return responseObserver;
    }

    private void createLeBondAndWaitBonding(BluetoothDevice device) {
        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> observer =
                advertiseWithBumble();
        mHost.createBondAndVerify(device);
        observer.cancel("Canceling advertisement");
    }

    private static void checkAggressiveConnectionWillBeUsed() {
        int aggressiveConnectionThreshold =
                SystemProperties.getInt("bluetooth.core.le.aggressive_connection_threshold", 2);
        assumeThat(aggressiveConnectionThreshold, greaterThan(0));
    }
}
