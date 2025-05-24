/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.pan;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Looper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.List;

/** Test cases for {@link BluetoothTetheringNetworkFactory}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothTetheringNetworkFactoryTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private PanService mPanService;

    private BluetoothTetheringNetworkFactory mBluetoothTetheringNetworkFactory;

    private <T> void mockGetSystemService(String serviceName, Class<T> serviceClass, T service) {
        doReturn(service).when(mContext).getSystemService(eq(serviceClass));
        doReturn(service).when(mContext).getSystemService(eq(serviceName));
        doReturn(serviceName).when(mContext).getSystemServiceName(eq(serviceClass));
    }

    @Before
    public void setUp() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mockGetSystemService(
                Context.CONNECTIVITY_SERVICE, ConnectivityManager.class, mConnectivityManager);
        mBluetoothTetheringNetworkFactory =
                new BluetoothTetheringNetworkFactory(mContext, Looper.myLooper(), mPanService);
    }

    @Test
    public void networkStartReverseTether() {
        String iface = "iface";
        mBluetoothTetheringNetworkFactory.startReverseTether(iface);

        assertThat(mBluetoothTetheringNetworkFactory.getProvider()).isNotNull();
    }

    @Test
    public void networkStartReverseTetherStop() {
        String iface = "iface";
        mBluetoothTetheringNetworkFactory.startReverseTether(iface);

        assertThat(mBluetoothTetheringNetworkFactory.getProvider()).isNotNull();

        final var bluetoothDevice = getTestDevice(11);
        when(mPanService.getConnectedDevices()).thenReturn(List.of(bluetoothDevice));

        mBluetoothTetheringNetworkFactory.stopReverseTether();

        verify(mPanService).getConnectedDevices();
        verify(mPanService).disconnect(bluetoothDevice);
    }

    @Test
    public void networkStartReverseTetherEmptyIface() {
        String iface = "";
        mBluetoothTetheringNetworkFactory.startReverseTether(iface);

        assertThat(mBluetoothTetheringNetworkFactory.getProvider()).isNull();
    }

    @Test
    public void networkStopEmptyIface() {
        mBluetoothTetheringNetworkFactory.stopNetwork();
        mBluetoothTetheringNetworkFactory.stopReverseTether();

        assertThat(mBluetoothTetheringNetworkFactory.getProvider()).isNull();
    }
}
