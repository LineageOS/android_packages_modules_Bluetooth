/*
 * Copyright 2024 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.MockitoRule;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothHapPresetInfo;

import com.android.bluetooth.btservice.AdapterService;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

public class HapClientNativeCallbackTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public Expect expect = Expect.create();

    @Mock private AdapterService mAdapterService;
    @Mock private HapClientService mHapClientService;
    @Captor private ArgumentCaptor<HapClientStackEvent> mEvent;

    private HapClientNativeCallback mNativeCallback;

    @Before
    public void setUp() throws Exception {
        mNativeCallback = new HapClientNativeCallback(mAdapterService, mHapClientService);
    }

    @Test
    public void onConnectionStateChanged() {
        int state = STATE_CONNECTED;
        mNativeCallback.onConnectionStateChanged(state, null);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        expect.that(event.valueInt1).isEqualTo(state);
    }

    @Test
    public void onDeviceAvailable() {
        int features = 1;
        mNativeCallback.onDeviceAvailable(null, features);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        expect.that(event.valueInt1).isEqualTo(features);
    }

    @Test
    public void onFeaturesUpdate() {
        int features = 1;
        mNativeCallback.onFeaturesUpdate(null, features);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_DEVICE_FEATURES);
        expect.that(event.valueInt1).isEqualTo(features);
    }

    @Test
    public void onActivePresetSelected() {
        int presetIndex = 0;
        mNativeCallback.onActivePresetSelected(null, presetIndex);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        expect.that(event.valueInt1).isEqualTo(presetIndex);
    }

    @Test
    public void onActivePresetGroupSelected() {
        int groupId = 1;
        int presetIndex = 0;
        mNativeCallback.onActivePresetGroupSelected(groupId, presetIndex);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        expect.that(event.valueInt1).isEqualTo(presetIndex);
        expect.that(event.valueInt2).isEqualTo(groupId);
    }

    @Test
    public void onActivePresetSelectError() {
        int resultCode = -1;
        mNativeCallback.onActivePresetSelectError(null, resultCode);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type)
                .isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECT_ERROR);
        expect.that(event.valueInt1).isEqualTo(resultCode);
    }

    @Test
    public void onActivePresetGroupSelectError() {
        int groupId = 1;
        int resultCode = -2;
        mNativeCallback.onActivePresetGroupSelectError(groupId, resultCode);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type)
                .isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECT_ERROR);
        expect.that(event.valueInt1).isEqualTo(resultCode);
        expect.that(event.valueInt2).isEqualTo(groupId);
    }

    @Test
    public void onPresetInfo() {
        int infoReason = HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO;
        BluetoothHapPresetInfo[] presets = {
            new BluetoothHapPresetInfo.Builder(0x01, "onPresetInfo")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };
        mNativeCallback.onPresetInfo(null, infoReason, presets);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO);
        expect.that(event.valueInt2).isEqualTo(infoReason);
        expect.that(event.valueList.toArray()).isEqualTo(presets);
    }

    @Test
    public void onGroupPresetInfo() {
        int groupId = 100;
        int infoReason = HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO;
        BluetoothHapPresetInfo[] presets = {
            new BluetoothHapPresetInfo.Builder(0x01, "onPresetInfo")
                    .setWritable(true)
                    .setAvailable(false)
                    .build()
        };
        mNativeCallback.onGroupPresetInfo(groupId, infoReason, presets);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO);
        expect.that(event.valueInt2).isEqualTo(infoReason);
        expect.that(event.valueInt3).isEqualTo(groupId);
        expect.that(event.valueList.toArray()).isEqualTo(presets);
    }

    @Test
    public void onPresetNameSetError() {
        int presetIndex = 2;
        int resultCode = HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED;
        mNativeCallback.onPresetNameSetError(null, presetIndex, resultCode);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_PRESET_NAME_SET_ERROR);
        expect.that(event.valueInt1).isEqualTo(resultCode);
        expect.that(event.valueInt2).isEqualTo(presetIndex);
    }

    @Test
    public void onGroupPresetNameSetError() {
        int groupId = 5;
        int presetIndex = 2;
        int resultCode = HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED;
        mNativeCallback.onGroupPresetNameSetError(groupId, presetIndex, resultCode);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_PRESET_NAME_SET_ERROR);
        expect.that(event.valueInt1).isEqualTo(resultCode);
        expect.that(event.valueInt2).isEqualTo(presetIndex);
        expect.that(event.valueInt3).isEqualTo(groupId);
    }

    @Test
    public void onPresetInfoError() {
        int presetIndex = 2;
        int resultCode = HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED;
        mNativeCallback.onPresetInfoError(null, presetIndex, resultCode);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO_ERROR);
        expect.that(event.valueInt1).isEqualTo(resultCode);
        expect.that(event.valueInt2).isEqualTo(presetIndex);
    }

    @Test
    public void onGroupPresetInfoError() {
        int groupId = 5;
        int presetIndex = 2;
        int resultCode = HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED;
        mNativeCallback.onGroupPresetInfoError(groupId, presetIndex, resultCode);

        verify(mHapClientService).messageFromNative(mEvent.capture());
        HapClientStackEvent event = mEvent.getValue();
        expect.that(event.type).isEqualTo(HapClientStackEvent.EVENT_TYPE_ON_PRESET_INFO_ERROR);
        expect.that(event.valueInt1).isEqualTo(resultCode);
        expect.that(event.valueInt2).isEqualTo(presetIndex);
        expect.that(event.valueInt3).isEqualTo(groupId);
    }
}
