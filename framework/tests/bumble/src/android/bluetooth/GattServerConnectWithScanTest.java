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

package android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link BluetoothGattServer}. */
@RunWith(AndroidJUnit4.class)
public class GattServerConnectWithScanTest {
    private static final String TAG = "GattServerConnectWithScanTest";

    private static final int TIMEOUT_SCANNING_MS = 2_000;
    private static final int TIMEOUT_GATT_CONNECTION_MS = 2_000;

    @Rule(order = 2)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 1)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mBluetoothManager =
            mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mBluetoothAdapter = mBluetoothManager.getAdapter();
    private final BluetoothLeScanner mLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

    @Test
    @Ignore("b/343525982: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToRandomAddress_withTransportAuto() throws Exception {
        advertiseWithBumble(OwnAddressType.RANDOM);
        assertThat(scanBumbleDevice(Utils.BUMBLE_RANDOM_ADDRESS)).isNotNull();

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);

        assertThat(gattServer).isNotNull();

        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/343525982: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToRandomAddress_withTransportLE() throws Exception {
        advertiseWithBumble(OwnAddressType.RANDOM);
        assertThat(scanBumbleDevice(Utils.BUMBLE_RANDOM_ADDRESS)).isNotNull();

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE);

        assertThat(gattServer).isNotNull();

        try {
            BluetoothDevice device =
                    mBluetoothAdapter.getRemoteLeDevice(
                            Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

            gattServer.connect(device, false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_BLE_GATT_SERVER_USE_ADDRESS_TYPE_IN_CONNECTION)
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToPublicAddress_withTransportAuto() throws Exception {
        String publicAddress = mBumble.getRemoteDevice().getAddress();
        advertiseWithBumble(OwnAddressType.PUBLIC);
        assertThat(scanBumbleDevice(publicAddress)).isNotNull();

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);

        assertThat(gattServer).isNotNull();

        try {
            gattServer.connect(mBumble.getRemoteDevice(), false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToPublicAddress_withTransportLE() throws Exception {
        String publicAddress = mBumble.getRemoteDevice().getAddress();
        advertiseWithBumble(OwnAddressType.PUBLIC);
        assertThat(scanBumbleDevice(publicAddress)).isNotNull();

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE);

        assertThat(gattServer).isNotNull();

        try {
            gattServer.connect(mBumble.getRemoteDevice(), false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    private void advertiseWithBumble(OwnAddressType ownAddressType) {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(ownAddressType)
                        .build();
        mBumble.hostBlocking().advertise(request);
    }

    private List<ScanResult> scanBumbleDevice(String address) {
        CompletableFuture<List<ScanResult>> future = new CompletableFuture<>();
        ScanSettings scanSettings =
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build();

        ScanFilter scanFilter = new ScanFilter.Builder().setDeviceAddress(address).build();

        ScanCallback scanCallback =
                new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        Log.d(TAG, "onScanResult: result=" + result);
                        future.complete(List.of(result));
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        Log.d(TAG, "onScanFailed: errorCode=" + errorCode);
                        future.complete(null);
                    }
                };

        mLeScanner.startScan(List.of(scanFilter), scanSettings, scanCallback);

        List<ScanResult> result =
                future.completeOnTimeout(null, TIMEOUT_SCANNING_MS, TimeUnit.MILLISECONDS).join();

        mLeScanner.stopScan(scanCallback);
        return result;
    }
}
