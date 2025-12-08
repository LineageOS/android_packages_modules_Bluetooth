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

package com.android.bluetooth.hap;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothHapClientCallback;
import android.content.AttributionSource;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Test cases for {@link HapClientServiceBinder}. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class HapClientServiceBinderTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mAttributionSource;
    @Mock private HapClientService mService;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private HapClientServiceBinder mBinder;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_HAP_ON_MAIN_LOOPER);
    }

    public HapClientServiceBinderTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doCallRealMethod().when(mService).syncPost(any());
        doAnswer(
                        inv -> {
                            ((Consumer) inv.getArgument(0)).accept(mService);
                            return null;
                        })
                .when(mService)
                .post(any());
        doAnswer(
                        inv -> {
                            return ((Function) inv.getArgument(0)).apply(mService);
                        })
                .when(mService)
                .syncPost(any(), any());
        mBinder = new HapClientServiceBinder(mService);
    }

    @Test
    public void getConnectedDevices() {
        assertThrows(NullPointerException.class, () -> mBinder.getConnectedDevices(null));
        mBinder.getConnectedDevices(mAttributionSource);
        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates() {
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getDevicesMatchingConnectionStates(null, null));
        mBinder.getDevicesMatchingConnectionStates(null, mAttributionSource);
        verify(mService).getDevicesMatchingConnectionStates(any());
    }

    @Test
    public void getConnectionState() {
        assertThrows(NullPointerException.class, () -> mBinder.getConnectionState(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getConnectionState(null, mAttributionSource));

        mBinder.getConnectionState(mDevice, mAttributionSource);
        verify(mService).getConnectionState(eq(mDevice));
    }

    @Test
    public void setConnectionPolicy() {
        assertThrows(
                NullPointerException.class,
                () -> mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, null));
        assertThrows(
                NullPointerException.class,
                () ->
                        mBinder.setConnectionPolicy(
                                null, CONNECTION_POLICY_ALLOWED, mAttributionSource));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mBinder.setConnectionPolicy(
                                mDevice, CONNECTION_POLICY_UNKNOWN, mAttributionSource));

        mBinder.setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED, mAttributionSource);
        verify(mService).setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    public void getConnectionPolicy() {
        assertThrows(NullPointerException.class, () -> mBinder.getConnectionPolicy(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getConnectionPolicy(null, mAttributionSource));
        mBinder.getConnectionPolicy(mDevice, mAttributionSource);
        verify(mService).getConnectionPolicy(eq(mDevice));
    }

    @Test
    public void getActivePresetIndex() {
        assertThrows(NullPointerException.class, () -> mBinder.getActivePresetIndex(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getActivePresetIndex(null, mAttributionSource));
        mBinder.getActivePresetIndex(mDevice, mAttributionSource);
        verify(mService).getActivePresetIndex(eq(mDevice));
    }

    @Test
    public void getActivePresetInfo() {
        assertThrows(NullPointerException.class, () -> mBinder.getActivePresetInfo(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getActivePresetInfo(null, mAttributionSource));
        mBinder.getActivePresetInfo(mDevice, mAttributionSource);
        verify(mService).getActivePresetInfo(eq(mDevice));
    }

    @Test
    public void getHapGroup() {
        assertThrows(NullPointerException.class, () -> mBinder.getHapGroup(mDevice, null));
        assertThrows(
                NullPointerException.class, () -> mBinder.getHapGroup(null, mAttributionSource));
        mBinder.getHapGroup(mDevice, mAttributionSource);
        verify(mService).getHapGroup(eq(mDevice));
    }

    @Test
    public void selectPreset() {
        int index = 42;
        assertThrows(NullPointerException.class, () -> mBinder.selectPreset(mDevice, index, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.selectPreset(null, index, mAttributionSource));
        mBinder.selectPreset(mDevice, index, mAttributionSource);
        verify(mService).selectPreset(eq(mDevice), eq(index));
    }

    @Test
    public void selectPresetForGroup() {
        int index = 42;
        int groupId = 4242;
        assertThrows(
                NullPointerException.class,
                () -> mBinder.selectPresetForGroup(groupId, index, null));
        mBinder.selectPresetForGroup(groupId, index, mAttributionSource);
        verify(mService).selectPresetForGroup(eq(groupId), eq(index));
    }

    @Test
    public void switchToNextPreset() {
        assertThrows(NullPointerException.class, () -> mBinder.switchToNextPreset(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.switchToNextPreset(null, mAttributionSource));
        mBinder.switchToNextPreset(mDevice, mAttributionSource);
        verify(mService).switchToNextPreset(eq(mDevice));
    }

    @Test
    public void switchToNextPresetForGroup() {
        int groupId = 4242;
        assertThrows(
                NullPointerException.class,
                () -> mBinder.switchToNextPresetForGroup(groupId, null));
        mBinder.switchToNextPresetForGroup(groupId, mAttributionSource);
        verify(mService).switchToNextPresetForGroup(eq(groupId));
    }

    @Test
    public void switchToPreviousPreset() {
        assertThrows(
                NullPointerException.class, () -> mBinder.switchToPreviousPreset(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.switchToPreviousPreset(null, mAttributionSource));
        mBinder.switchToPreviousPreset(mDevice, mAttributionSource);
        verify(mService).switchToPreviousPreset(eq(mDevice));
    }

    @Test
    public void switchToPreviousPresetForGroup() {
        int groupId = 4242;
        assertThrows(
                NullPointerException.class,
                () -> mBinder.switchToPreviousPresetForGroup(groupId, null));
        mBinder.switchToPreviousPresetForGroup(groupId, mAttributionSource);
        verify(mService).switchToPreviousPresetForGroup(eq(groupId));
    }

    @Test
    public void getPresetInfo() {
        int index = 42;
        assertThrows(NullPointerException.class, () -> mBinder.getPresetInfo(mDevice, index, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getPresetInfo(null, index, mAttributionSource));
        mBinder.getPresetInfo(mDevice, index, mAttributionSource);
        verify(mService).getPresetInfo(eq(mDevice), eq(index));
    }

    @Test
    public void getAllPresetInfo() {
        assertThrows(NullPointerException.class, () -> mBinder.getAllPresetInfo(mDevice, null));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.getAllPresetInfo(null, mAttributionSource));
        mBinder.getAllPresetInfo(mDevice, mAttributionSource);
        verify(mService).getAllPresetInfo(eq(mDevice));
    }

    @Test
    public void getFeatures() {
        assertThrows(NullPointerException.class, () -> mBinder.getFeatures(mDevice, null));
        assertThrows(
                NullPointerException.class, () -> mBinder.getFeatures(null, mAttributionSource));
        mBinder.getFeatures(mDevice, mAttributionSource);
        verify(mService).getFeatures(eq(mDevice));
    }

    @Test
    public void setPresetName() {
        String name = "This is a preset name";
        int index = 42;
        assertThrows(
                NullPointerException.class,
                () -> mBinder.setPresetName(null, index, name, mAttributionSource));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.setPresetName(mDevice, index, null, mAttributionSource));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.setPresetName(mDevice, index, name, null));
        mBinder.setPresetName(mDevice, index, name, mAttributionSource);
        verify(mService).setPresetName(eq(mDevice), eq(index), eq(name));
    }

    @Test
    public void setPresetNameForGroup() {
        String name = "This is a preset name";
        int index = 42;
        int groupId = 4242;
        assertThrows(
                NullPointerException.class,
                () -> mBinder.setPresetNameForGroup(groupId, index, null, mAttributionSource));
        assertThrows(
                NullPointerException.class,
                () -> mBinder.setPresetNameForGroup(groupId, index, name, null));
        mBinder.setPresetNameForGroup(groupId, index, name, mAttributionSource);
        verify(mService).setPresetNameForGroup(eq(groupId), eq(index), eq(name));
    }

    @Test
    public void registerCallback() {
        IBluetoothHapClientCallback callback = Mockito.mock(IBluetoothHapClientCallback.class);
        assertThrows(
                NullPointerException.class,
                () -> mBinder.registerCallback(null, mAttributionSource));
        assertThrows(NullPointerException.class, () -> mBinder.registerCallback(callback, null));
        mBinder.registerCallback(callback, mAttributionSource);
        verify(mService).registerCallback(eq(callback));
    }

    @Test
    public void unregisterCallback() {
        IBluetoothHapClientCallback callback = Mockito.mock(IBluetoothHapClientCallback.class);
        assertThrows(
                NullPointerException.class,
                () -> mBinder.unregisterCallback(null, mAttributionSource));
        assertThrows(NullPointerException.class, () -> mBinder.unregisterCallback(callback, null));
        mBinder.unregisterCallback(callback, mAttributionSource);
        verify(mService).unregisterCallback(eq(callback));
    }
}
