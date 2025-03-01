/*
 * Copyright 2023 The Android Open Source Project
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
package com.android.bluetooth.btservice;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.HandlerThread;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.Utils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AdapterPropertiesTest {
    private static final byte[] TEST_BT_ADDR_BYTES = {00, 11, 22, 33, 44, 55};
    private static final byte[] TEST_BT_ADDR_BYTES_2 = {00, 11, 22, 33, 44, 66};

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothManager mBluetoothManager =
            mTargetContext.getSystemService(BluetoothManager.class);

    private AdapterProperties mAdapterProperties;
    private RemoteDevices mRemoteDevices;
    private HandlerThread mHandlerThread;

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private AdapterNativeInterface mNativeInterface;

    @Before
    public void setUp() throws Exception {
        doReturn(mNativeInterface).when(mAdapterService).getNative();
        mHandlerThread = new HandlerThread("RemoteDevicesTestHandlerThread");
        mHandlerThread.start();

        mockGetSystemService(
                mAdapterService,
                Context.BLUETOOTH_SERVICE,
                BluetoothManager.class,
                mBluetoothManager);

        when(mAdapterService.getIdentityAddress(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES)))
                .thenReturn(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES));
        when(mAdapterService.getIdentityAddress(
                        Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES_2)))
                .thenReturn(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES));
        when(mNativeInterface.removeBond(any(byte[].class))).thenReturn(true);

        mRemoteDevices = new RemoteDevices(mAdapterService, mHandlerThread.getLooper());
        verify(mAdapterService).getSystemService(BluetoothManager.class);

        mRemoteDevices.reset();

        doReturn(mHandlerThread.getLooper()).when(mAdapterService).getMainLooper();
        when(mAdapterService.getResources())
                .thenReturn(
                        InstrumentationRegistry.getInstrumentation()
                                .getTargetContext()
                                .getResources());

        // Must be called to initialize services
        mAdapterProperties =
                new AdapterProperties(mAdapterService, mRemoteDevices, mHandlerThread.getLooper());
        mAdapterProperties.init();
    }

    @Test
    public void testCleanupPrevBondRecordsFor() {
        mRemoteDevices.reset();
        mRemoteDevices
                .addDeviceProperties(TEST_BT_ADDR_BYTES)
                .setDeviceType(BluetoothDevice.DEVICE_TYPE_LE);
        mRemoteDevices
                .addDeviceProperties(TEST_BT_ADDR_BYTES_2)
                .setDeviceType(BluetoothDevice.DEVICE_TYPE_LE);

        BluetoothDevice device1, device2;
        device1 = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES);
        device2 = mRemoteDevices.getDevice(TEST_BT_ADDR_BYTES_2);

        // Bond record for device1 should be deleted when pairing with device2
        // as they are same device (have same identity address)
        mAdapterProperties.onBondStateChanged(device1, BluetoothDevice.BOND_BONDED);
        mAdapterProperties.onBondStateChanged(device2, BluetoothDevice.BOND_BONDED);
        assertThat(mAdapterProperties.getBondedDevices().length).isEqualTo(1);
        assertThat(mAdapterProperties.getBondedDevices()[0].getAddress())
                .isEqualTo(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES_2));
    }

    @Test
    public void setName_shortName_isEqual() {
        StringBuilder builder = new StringBuilder();
        String stringName = "Wonderful Bluetooth Name Using utf8";
        builder.append(stringName);
        builder.append(Character.toChars(0x20AC));

        String initial = builder.toString();

        final ArgumentCaptor<byte[]> argumentName = ArgumentCaptor.forClass(byte[].class);

        mAdapterProperties.setName(initial);
        verify(mNativeInterface)
                .setAdapterProperty(
                        eq(AbstractionLayer.BT_PROPERTY_BDNAME), argumentName.capture());

        assertThat(argumentName.getValue()).isEqualTo(initial.getBytes());
    }

    @Test
    public void setName_tooLongName_isTruncated() {
        StringBuilder builder = new StringBuilder();
        String stringName = "Wonderful Bluetooth Name Using utf8 ... But this name is too long";
        builder.append(stringName);

        int n = 300;
        for (int i = 0; i < 2 * n; i++) {
            builder.append(Character.toChars(0x20AC));
        }

        String initial = builder.toString();

        final ArgumentCaptor<byte[]> argumentName = ArgumentCaptor.forClass(byte[].class);

        mAdapterProperties.setName(initial);
        verify(mNativeInterface)
                .setAdapterProperty(
                        eq(AbstractionLayer.BT_PROPERTY_BDNAME), argumentName.capture());

        byte[] name = argumentName.getValue();

        assertThat(name.length).isLessThan(initial.getBytes().length);

        assertThat(initial).startsWith(new String(name));
    }
}
