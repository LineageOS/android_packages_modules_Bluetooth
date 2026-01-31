/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.Utils;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;

/** Test cases for {@link AdapterProperties}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class AdapterPropertiesTest {
    private static final byte[] TEST_BT_ADDR_BYTES = {00, 11, 22, 33, 44, 55};
    private static final byte[] TEST_BT_ADDR_BYTES_2 = {00, 11, 22, 33, 44, 66};

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mPackageManager;
    @Mock private AdapterNativeInterface mNativeInterface;

    private AdapterProperties mAdapterProperties;
    private RemoteDevices mRemoteDevices;
    private HandlerThread mHandlerThread;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public AdapterPropertiesTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doReturn(mNativeInterface).when(mAdapterService).getNative();
        mHandlerThread = new HandlerThread("RemoteDevicesTestHandlerThread");
        mHandlerThread.start();

        mockGetBluetoothManager(mAdapterService);
        mockGetSystemService(mAdapterService, CompanionDeviceManager.class);
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
        doCallRealMethod().when(mAdapterService).getBrEdrAddress(any(BluetoothDevice.class));
        doCallRealMethod().when(mAdapterService).getBrEdrAddress(any(String.class));
        doReturn(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES))
                .when(mAdapterService)
                .getIdentityAddress(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES));
        doReturn(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES))
                .when(mAdapterService)
                .getIdentityAddress(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES_2));
        doReturn(true).when(mNativeInterface).removeBond(any(byte[].class));

        mRemoteDevices = new RemoteDevices(mAdapterService, mHandlerThread.getLooper());
        verify(mAdapterService).getSystemService(BluetoothManager.class);

        mRemoteDevices.reset();

        doReturn(mHandlerThread.getLooper()).when(mAdapterService).getMainLooper();
        doReturn(InstrumentationRegistry.getInstrumentation().getContext().getResources())
                .when(mAdapterService)
                .getResources();

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
    @DisableFlags(Flags.FLAG_SET_NAME_IN_SYSTEM_SERVER)
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
    @DisableFlags(Flags.FLAG_SET_NAME_IN_SYSTEM_SERVER)
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

    @Test
    public void isNativeDiscovering_initialValueIsFalse() {
        // Verifies that the default discovery state is false.
        assertThat(mAdapterProperties.isNativeDiscovering()).isFalse();
    }

    @Test
    public void discoveryStateChangeCallback_Started_setsNativeDiscoveringTrue() {
        // Verifies that starting discovery updates the state and broadcasts the correct intent.
        assertThat(mAdapterProperties.isNativeDiscovering()).isFalse();

        // Trigger discovery started callback.
        mAdapterProperties.discoveryStateChangeCallback(AbstractionLayer.BT_DISCOVERY_STARTED);

        // Assert that native discovering is now true.
        assertThat(mAdapterProperties.isNativeDiscovering()).isTrue();

        // Verify that an ACTION_DISCOVERY_STARTED intent was broadcast.
        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mAdapterService)
                .sendBroadcast(
                        intentCaptor.capture(),
                        eq(android.Manifest.permission.BLUETOOTH_SCAN),
                        any());
        assertThat(intentCaptor.getValue().getAction())
                .isEqualTo(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
    }

    @Test
    public void discoveryStateChangeCallback_Stopped_setsNativeDiscoveringFalse() {
        // Verifies that stopping discovery updates the state and broadcasts the correct intent.
        // Start discovery first to ensure the state changes.
        mAdapterProperties.discoveryStateChangeCallback(AbstractionLayer.BT_DISCOVERY_STARTED);
        assertThat(mAdapterProperties.isNativeDiscovering()).isTrue();
        // Clear invocations on the mock from the setup call to isolate verification.
        clearInvocations(mAdapterService);

        // Trigger discovery stopped callback.
        mAdapterProperties.discoveryStateChangeCallback(AbstractionLayer.BT_DISCOVERY_STOPPED);

        // Assert that native discovering is now false.
        assertThat(mAdapterProperties.isNativeDiscovering()).isFalse();

        // Verify that clearDiscoveryData is called.
        verify(mAdapterService).clearDiscoveryData();

        // Verify that an ACTION_DISCOVERY_FINISHED intent was broadcast.
        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mAdapterService)
                .sendBroadcast(
                        intentCaptor.capture(),
                        eq(android.Manifest.permission.BLUETOOTH_SCAN),
                        any());
        assertThat(intentCaptor.getValue().getAction())
                .isEqualTo(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
    }
}
