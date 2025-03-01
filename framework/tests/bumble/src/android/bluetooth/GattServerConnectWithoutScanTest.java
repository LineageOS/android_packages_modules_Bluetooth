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

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;

/** Test cases for {@link BluetoothGattServer}. */
@RunWith(AndroidJUnit4.class)
public class GattServerConnectWithoutScanTest {
    private static final String TAG = GattServerConnectWithoutScanTest.class.getSimpleName();

    private static final int TIMEOUT_GATT_CONNECTION_MS = 2_000;

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 0)
    public final PandoraDevice mBumble = new PandoraDevice();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mBluetoothManager =
            mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mBluetoothAdapter = mBluetoothManager.getAdapter();

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToRandomAddress_withTransportAuto() throws Exception {
        advertiseWithBumble(OwnAddressType.RANDOM);

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
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToRandomAddress_withTransportLE() throws Exception {
        advertiseWithBumble(OwnAddressType.RANDOM);

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
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/333018293")
    public void serverConnectToPublicAddress_withTransportAuto() throws Exception {
        advertiseWithBumble(OwnAddressType.PUBLIC);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_AUTO);

        assertThat(gattServer).isNotNull();

        try {
            gattServer.connect(mBumble.getRemoteDevice(), false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
        } finally {
            gattServer.close();
        }
    }

    @Test
    @Ignore("b/343749428: Remove hidden api's dependencies to enable the test.")
    public void serverConnectToPublicAddress_withTransportLE() throws Exception {
        advertiseWithBumble(OwnAddressType.PUBLIC);

        BluetoothGattServerCallback mockGattServerCallback =
                mock(BluetoothGattServerCallback.class);
        BluetoothGattServer gattServer =
                mBluetoothManager.openGattServer(
                        mContext, mockGattServerCallback, BluetoothDevice.TRANSPORT_LE);

        assertThat(gattServer).isNotNull();

        try {
            gattServer.connect(mBumble.getRemoteDevice(), false);
            verify(mockGattServerCallback, timeout(TIMEOUT_GATT_CONNECTION_MS))
                    .onConnectionStateChange(any(), anyInt(), eq(STATE_CONNECTED));
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
}
