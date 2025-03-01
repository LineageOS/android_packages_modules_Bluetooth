/*
 * Copyright 2022 The Android Open Source Project
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
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Looper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link BluetoothTetheringNetworkFactory}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothTetheringNetworkFactoryTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private PanService mPanService;
    @Mock Context mContext;

    @Test
    public void networkStartReverseTether() {
        mockGetSystemService(mContext, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        BluetoothTetheringNetworkFactory bluetoothTetheringNetworkFactory =
                new BluetoothTetheringNetworkFactory(mContext, Looper.myLooper(), mPanService);

        String iface = "iface";
        bluetoothTetheringNetworkFactory.startReverseTether(iface);

        assertThat(bluetoothTetheringNetworkFactory.getProvider()).isNotNull();
    }

    @Test
    public void networkStartReverseTetherStop() {
        mockGetSystemService(mContext, Context.CONNECTIVITY_SERVICE, ConnectivityManager.class);
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        BluetoothTetheringNetworkFactory bluetoothTetheringNetworkFactory =
                new BluetoothTetheringNetworkFactory(mContext, Looper.myLooper(), mPanService);

        String iface = "iface";
        bluetoothTetheringNetworkFactory.startReverseTether(iface);

        assertThat(bluetoothTetheringNetworkFactory.getProvider()).isNotNull();

        List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
        BluetoothDevice bluetoothDevice = getTestDevice(11);
        bluetoothDevices.add(bluetoothDevice);

        when(mPanService.getConnectedDevices()).thenReturn(bluetoothDevices);

        bluetoothTetheringNetworkFactory.stopReverseTether();

        verify(mPanService).getConnectedDevices();
        verify(mPanService).disconnect(bluetoothDevice);
    }
}
