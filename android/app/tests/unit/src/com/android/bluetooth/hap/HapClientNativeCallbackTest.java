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

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX;
import static android.bluetooth.BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG;
import static android.bluetooth.BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED;
import static android.bluetooth.BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.hap.HapClientNativeCallback.STATUS_INVALID_PRESET_INDEX;
import static com.android.bluetooth.hap.HapClientNativeCallback.STATUS_INVALID_PRESET_NAME_LENGTH;
import static com.android.bluetooth.hap.HapClientNativeCallback.STATUS_OPERATION_NOT_POSSIBLE;
import static com.android.bluetooth.hap.HapClientNativeCallback.STATUS_OPERATION_NOT_SUPPORTED;
import static com.android.bluetooth.hap.HapClientNativeCallback.STATUS_SET_NAME_NOT_ALLOWED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapPresetInfo;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.function.Consumer;

/** Test cases for {@link HapClientNativeCallback}. */
@RunWith(ParameterizedAndroidJunit4.class)
public class HapClientNativeCallbackTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public Expect expect = Expect.create();

    @Mock private AdapterService mAdapterService;
    @Mock private HapClientService mService;

    private final BluetoothDevice mDevice = getTestDevice(0);

    private InOrder mInOrder;
    private HapClientNativeCallback mNativeCallback;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_HAP_ON_MAIN_LOOPER);
    }

    public HapClientNativeCallbackTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        mInOrder = inOrder(mService);
        doReturn(true).when(mService).isAvailable();
        var deviceBytes = getByteAddress(mDevice);
        doReturn(mDevice).when(mAdapterService).getDeviceFromByte(deviceBytes);
        doAnswer(
                        inv -> {
                            ((Consumer<HapClientService>) inv.getArgument(0)).accept(mService);
                            return null;
                        })
                .when(mService)
                .post(any());
        mNativeCallback = new HapClientNativeCallback(mAdapterService, mService);
    }

    @Test
    public void onConnectionStateChanged() {
        mNativeCallback.onConnectionStateChanged(getByteAddress(mDevice), STATE_CONNECTED);
        verify(mService).onConnectionStateChanged(mDevice, STATE_CONNECTED);
    }

    @Test
    public void onDeviceAvailable() {
        int features = 1;
        mNativeCallback.onDeviceAvailable(getByteAddress(mDevice), features);
        verify(mService).onDeviceAvailable(mDevice, features);
    }

    @Test
    public void onFeaturesUpdate() {
        int features = 1;
        mNativeCallback.onFeaturesUpdate(getByteAddress(mDevice), features);
        verify(mService).onFeaturesUpdate(mDevice, features);
    }

    @Test
    public void onPresetSelected() {
        int presetIndex = 0;
        mNativeCallback.onPresetSelected(getByteAddress(mDevice), presetIndex);
        verify(mService).onPresetSelected(mDevice, presetIndex);
    }

    @Test
    public void onPresetSelectedForGroup() {
        int groupId = 1;
        int presetIndex = 0;
        mNativeCallback.onPresetSelectedForGroup(groupId, presetIndex);
        verify(mService).onPresetSelectedForGroup(groupId, presetIndex);
    }

    @Test
    public void onPresetSelectionFailed() {
        byte[] deviceBytes = getByteAddress(mDevice);
        /* Not a valid name length */
        mNativeCallback.onPresetSelectionFailed(deviceBytes, STATUS_INVALID_PRESET_NAME_LENGTH);
        mInOrder.verify(mService).onPresetSelectionFailed(mDevice, ERROR_HAP_PRESET_NAME_TOO_LONG);

        /* Invalid preset index provided */
        mNativeCallback.onPresetSelectionFailed(deviceBytes, STATUS_INVALID_PRESET_INDEX);
        mInOrder.verify(mService).onPresetSelectionFailed(mDevice, ERROR_HAP_INVALID_PRESET_INDEX);

        /* Not allowed on this particular preset */
        mNativeCallback.onPresetSelectionFailed(deviceBytes, STATUS_SET_NAME_NOT_ALLOWED);
        mInOrder.verify(mService).onPresetSelectionFailed(mDevice, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onPresetSelectionFailed(deviceBytes, STATUS_OPERATION_NOT_POSSIBLE);
        mInOrder.verify(mService).onPresetSelectionFailed(mDevice, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on all presets - for example missing characteristic */
        mNativeCallback.onPresetSelectionFailed(deviceBytes, STATUS_OPERATION_NOT_SUPPORTED);
        mInOrder.verify(mService)
                .onPresetSelectionFailed(mDevice, ERROR_REMOTE_OPERATION_NOT_SUPPORTED);
    }

    @Test
    public void onPresetSelectionForGroupFailed() {
        int groupId = 1;

        /* Not a valid name length */
        mNativeCallback.onPresetSelectionForGroupFailed(groupId, STATUS_INVALID_PRESET_NAME_LENGTH);
        mInOrder.verify(mService)
                .onPresetSelectionForGroupFailed(groupId, ERROR_HAP_PRESET_NAME_TOO_LONG);

        /* Invalid preset index provided */
        mNativeCallback.onPresetSelectionForGroupFailed(groupId, STATUS_INVALID_PRESET_INDEX);
        mInOrder.verify(mService)
                .onPresetSelectionForGroupFailed(groupId, ERROR_HAP_INVALID_PRESET_INDEX);

        /* Not allowed on this particular preset */
        mNativeCallback.onPresetSelectionForGroupFailed(groupId, STATUS_SET_NAME_NOT_ALLOWED);
        mInOrder.verify(mService)
                .onPresetSelectionForGroupFailed(groupId, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onPresetSelectionForGroupFailed(groupId, STATUS_OPERATION_NOT_POSSIBLE);
        mInOrder.verify(mService)
                .onPresetSelectionForGroupFailed(groupId, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on all presets - for example missing characteristic */
        mNativeCallback.onPresetSelectionForGroupFailed(groupId, STATUS_OPERATION_NOT_SUPPORTED);
        mInOrder.verify(mService)
                .onPresetSelectionForGroupFailed(groupId, ERROR_REMOTE_OPERATION_NOT_SUPPORTED);
    }

    @Test
    public void onPresetInfo() {
        int reason = 1;
        BluetoothHapPresetInfo[] presets = {
            new BluetoothHapPresetInfo.Builder(0x01, "onPresetInfo")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };
        mNativeCallback.onPresetInfo(getByteAddress(mDevice), reason, presets);
        verify(mService).onPresetInfo(mDevice, reason, List.of(presets));
    }

    @Test
    public void onPresetInfoForGroup() {
        int groupId = 100;
        int reason = 1;
        BluetoothHapPresetInfo[] presets = {
            new BluetoothHapPresetInfo.Builder(0x01, "onPresetInfo")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };
        mNativeCallback.onPresetInfoForGroup(groupId, reason, presets);
        verify(mService).onPresetInfoForGroup(groupId, reason, List.of(presets));
    }

    @Test
    public void onSetPresetNameFailed() {
        byte[] deviceBytes = getByteAddress(mDevice);
        /* Not a valid name length */
        mNativeCallback.onSetPresetNameFailed(deviceBytes, STATUS_INVALID_PRESET_NAME_LENGTH);
        mInOrder.verify(mService).onSetPresetNameFailed(mDevice, ERROR_HAP_PRESET_NAME_TOO_LONG);

        /* Invalid preset index provided */
        mNativeCallback.onSetPresetNameFailed(deviceBytes, STATUS_INVALID_PRESET_INDEX);
        mInOrder.verify(mService).onSetPresetNameFailed(mDevice, ERROR_HAP_INVALID_PRESET_INDEX);

        /* Not allowed on this particular preset */
        mNativeCallback.onSetPresetNameFailed(deviceBytes, STATUS_SET_NAME_NOT_ALLOWED);
        mInOrder.verify(mService).onSetPresetNameFailed(mDevice, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onSetPresetNameFailed(deviceBytes, STATUS_OPERATION_NOT_POSSIBLE);
        mInOrder.verify(mService).onSetPresetNameFailed(mDevice, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on all presets - for example missing characteristic */
        mNativeCallback.onSetPresetNameFailed(deviceBytes, STATUS_OPERATION_NOT_SUPPORTED);
        mInOrder.verify(mService)
                .onSetPresetNameFailed(mDevice, ERROR_REMOTE_OPERATION_NOT_SUPPORTED);
    }

    @Test
    public void onSetPresetNameForGroupFailed() {
        int groupId = 5;
        /* Not a valid name length */
        mNativeCallback.onSetPresetNameForGroupFailed(groupId, STATUS_INVALID_PRESET_NAME_LENGTH);
        mInOrder.verify(mService)
                .onSetPresetNameForGroupFailed(groupId, ERROR_HAP_PRESET_NAME_TOO_LONG);

        /* Invalid preset index provided */
        mNativeCallback.onSetPresetNameForGroupFailed(groupId, STATUS_INVALID_PRESET_INDEX);
        mInOrder.verify(mService)
                .onSetPresetNameForGroupFailed(groupId, ERROR_HAP_INVALID_PRESET_INDEX);

        /* Not allowed on this particular preset */
        mNativeCallback.onSetPresetNameForGroupFailed(groupId, STATUS_SET_NAME_NOT_ALLOWED);
        mInOrder.verify(mService)
                .onSetPresetNameForGroupFailed(groupId, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeCallback.onSetPresetNameForGroupFailed(groupId, STATUS_OPERATION_NOT_POSSIBLE);
        mInOrder.verify(mService)
                .onSetPresetNameForGroupFailed(groupId, ERROR_REMOTE_OPERATION_REJECTED);

        /* Not allowed on all presets - for example missing characteristic */
        mNativeCallback.onSetPresetNameForGroupFailed(groupId, STATUS_OPERATION_NOT_SUPPORTED);
        mInOrder.verify(mService)
                .onSetPresetNameForGroupFailed(groupId, ERROR_REMOTE_OPERATION_NOT_SUPPORTED);
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        return Util.getBytesFromAddress(device.getAddress());
    }
}
