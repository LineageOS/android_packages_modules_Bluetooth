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

package com.android.bluetooth.vc;

import static android.bluetooth.BluetoothUtils.RemoteExceptionIgnoringConsumer;

import static java.util.Objects.requireNonNull;

import android.bluetooth.AudioInputControl.AudioInputStatus;
import android.bluetooth.AudioInputControl.AudioInputType;
import android.bluetooth.AudioInputControl.GainMode;
import android.bluetooth.AudioInputControl.Mute;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IAudioInputCallback;
import android.os.RemoteCallbackList;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;

class VolumeControlInputDescriptor {
    private static final String TAG = VolumeControlInputDescriptor.class.getSimpleName();

    final VolumeControlNativeInterface mNativeInterface;
    final BluetoothDevice mDevice;
    final Descriptor[] mVolumeInputs;

    VolumeControlInputDescriptor(
            VolumeControlNativeInterface nativeInterface,
            BluetoothDevice device,
            int numberOfExternalInputs) {
        mNativeInterface = requireNonNull(nativeInterface);
        mDevice = requireNonNull(device);
        mVolumeInputs = new Descriptor[numberOfExternalInputs];
        // Stack delivers us number of AICSs instances. ids are continuous from [0;n[
        for (int i = 0; i < numberOfExternalInputs; i++) {
            mVolumeInputs[i] = new Descriptor();
        }
    }

    private static class Descriptor {
        @AudioInputStatus int mStatus = bluetooth.constants.aics.AudioInputStatus.INACTIVE;

        @AudioInputType int mType = bluetooth.constants.AudioInputType.UNSPECIFIED;

        int mGainSetting = 0;
        @Mute int mMute = bluetooth.constants.aics.Mute.DISABLED;
        @GainMode int mGainMode = bluetooth.constants.aics.GainMode.MANUAL_ONLY;

        /* See AICS 1.0
         * The Gain_Setting (mGainSetting) field is a signed value for which a single increment or
         * decrement should result in a corresponding increase or decrease of the input amplitude by
         * the value of the Gain_Setting_Units (mGainSettingsUnits) field of the Gain Setting
         * Properties characteristic value.
         */
        int mGainSettingsUnits = 0;

        int mGainSettingsMax = 0;
        int mGainSettingsMin = 0;

        String mDescription = "";
        boolean mDescriptionIsWritable = false;

        private final RemoteCallbackList<IAudioInputCallback> mCallbacks =
                new RemoteCallbackList<>();

        void registerCallback(IAudioInputCallback callback) {
            mCallbacks.register(callback);
        }

        void unregisterCallback(IAudioInputCallback callback) {
            mCallbacks.unregister(callback);
        }

        synchronized void broadcast(
                String logAction, RemoteExceptionIgnoringConsumer<IAudioInputCallback> action) {
            final int itemCount = mCallbacks.beginBroadcast();
            Log.d(TAG, "Broadcasting " + logAction + "() to " + itemCount + " receivers.");
            for (int i = 0; i < itemCount; i++) {
                action.accept(mCallbacks.getBroadcastItem(i));
            }
            mCallbacks.finishBroadcast();
        }
    }

    int size() {
        return mVolumeInputs.length;
    }

    private boolean isValidId(int id) {
        if (id >= size() || id < 0) {
            Log.e(TAG, "Request fail. Illegal id argument: " + id);
            return false;
        }
        return true;
    }

    void registerCallback(int id, IAudioInputCallback callback) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].registerCallback(callback);
    }

    void unregisterCallback(int id, IAudioInputCallback callback) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].unregisterCallback(callback);
    }

    void onStatusChanged(int id, @AudioInputStatus int status) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].mStatus = status;
        mVolumeInputs[id].broadcast("onStatusChanged", (c) -> c.onStatusChanged(status));
    }

    int getStatus(int id) {
        if (!isValidId(id)) return bluetooth.constants.aics.AudioInputStatus.INACTIVE;
        return mVolumeInputs[id].mStatus;
    }

    boolean isDescriptionWritable(int id) {
        if (!isValidId(id)) return false;
        return mVolumeInputs[id].mDescriptionIsWritable;
    }

    boolean setDescription(int id, String description) {
        if (!isValidId(id)) return false;

        if (!mVolumeInputs[id].mDescriptionIsWritable) {
            throw new IllegalStateException("Description is not writable");
        }

        return mNativeInterface.setExtAudioInDescription(mDevice, id, description);
    }

    String getDescription(int id) {
        if (!isValidId(id)) return null;
        return mVolumeInputs[id].mDescription;
    }

    void onDescriptionChanged(int id, String description, boolean isWritable) {
        if (!isValidId(id)) return;
        Descriptor desc = mVolumeInputs[id];

        desc.mDescription = description;
        desc.mDescriptionIsWritable = isWritable;
        desc.broadcast("onDescriptionChanged", c -> c.onDescriptionChanged(description));
    }

    void setType(int id, int type) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].mType = type;
    }

    int getType(int id) {
        if (!isValidId(id)) return bluetooth.constants.AudioInputType.UNSPECIFIED;
        return mVolumeInputs[id].mType;
    }

    void onGainSettingsPropertiesChanged(int id, int gainUnit, int gainMin, int gainMax) {
        if (!isValidId(id)) return;

        mVolumeInputs[id].mGainSettingsUnits = gainUnit;
        mVolumeInputs[id].mGainSettingsMin = gainMin;
        mVolumeInputs[id].mGainSettingsMax = gainMax;
    }

    int getGainSettingUnit(int id) {
        if (!isValidId(id)) return 0;
        return mVolumeInputs[id].mGainSettingsUnits;
    }

    int getGainSettingMin(int id) {
        if (!isValidId(id)) return 0;
        return mVolumeInputs[id].mGainSettingsMin;
    }

    int getGainSettingMax(int id) {
        if (!isValidId(id)) return 0;
        return mVolumeInputs[id].mGainSettingsMax;
    }

    void onStateChanged(int id, int gainSetting, @Mute int mute, @GainMode int gainMode) {
        if (!isValidId(id)) return;

        Descriptor desc = mVolumeInputs[id];

        if (gainSetting > desc.mGainSettingsMax || gainSetting < desc.mGainSettingsMin) {
            Log.e(TAG, "Request fail. Illegal gainSetting argument: " + gainSetting);
            return;
        }

        desc.mGainSetting = gainSetting;
        desc.mMute = mute;
        desc.mGainMode = gainMode;

        mVolumeInputs[id].broadcast(
                "onStateChanged", (c) -> c.onStateChanged(gainSetting, mute, gainMode));
    }

    int getGainSetting(int id) {
        if (!isValidId(id)) return 0;
        return mVolumeInputs[id].mGainSetting;
    }

    boolean setGainSetting(int id, int gainSetting) {
        if (!isValidId(id)) return false;

        Descriptor desc = mVolumeInputs[id];

        if (gainSetting > desc.mGainSettingsMax || gainSetting < desc.mGainSettingsMin) {

            throw new IllegalArgumentException(
                    ("gainSetting=" + gainSetting + " is not in correct range")
                            + (" [" + desc.mGainSettingsMin + "-" + desc.mGainSettingsMax + "]"));
        }

        if (desc.mGainMode == bluetooth.constants.aics.GainMode.AUTOMATIC
                || desc.mGainMode == bluetooth.constants.aics.GainMode.AUTOMATIC_ONLY) {
            throw new IllegalStateException("Disallowed due to gain mode being " + desc.mGainMode);
        }

        return mNativeInterface.setExtAudioInGainSetting(mDevice, id, gainSetting);
    }

    void onSetGainSettingFailed(int id) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].broadcast("onSetGainSettingFailed", (c) -> c.onSetGainSettingFailed());
    }

    @Mute
    int getMute(int id) {
        if (!isValidId(id)) return bluetooth.constants.aics.Mute.DISABLED;
        return mVolumeInputs[id].mMute;
    }

    boolean setMute(int id, @Mute int mute) {
        if (!isValidId(id)) return false;

        if (mVolumeInputs[id].mMute == bluetooth.constants.aics.Mute.DISABLED) {
            throw new IllegalStateException("Disallowed due to mute being disabled");
        }

        return mNativeInterface.setExtAudioInMute(mDevice, id, mute);
    }

    void onSetMuteFailed(int id) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].broadcast("onSetMuteFailed", (c) -> c.onSetMuteFailed());
    }

    @GainMode
    int getGainMode(int id) {
        if (!isValidId(id)) return bluetooth.constants.aics.GainMode.AUTOMATIC_ONLY;
        return mVolumeInputs[id].mGainMode;
    }

    boolean setGainMode(int id, @GainMode int gainMode) {
        if (!isValidId(id)) return false;

        Descriptor desc = mVolumeInputs[id];

        if (desc.mGainMode == bluetooth.constants.aics.GainMode.MANUAL_ONLY
                || desc.mGainMode == bluetooth.constants.aics.GainMode.AUTOMATIC_ONLY) {
            throw new IllegalStateException("Disallowed due to gain mode being " + desc.mGainMode);
        }

        return mNativeInterface.setExtAudioInGainMode(mDevice, id, gainMode);
    }

    void onSetGainModeFailed(int id) {
        if (!isValidId(id)) return;
        mVolumeInputs[id].broadcast("onSetGainModeFailed", (c) -> c.onSetGainModeFailed());
    }

    void dump(StringBuilder sb) {
        for (int i = 0; i < mVolumeInputs.length; i++) {
            Descriptor desc = mVolumeInputs[i];
            ProfileService.println(sb, "      id: " + i);
            ProfileService.println(sb, "        description: " + desc.mDescription);
            ProfileService.println(sb, "        type: " + desc.mType);
            ProfileService.println(sb, "        status: " + desc.mStatus);
            ProfileService.println(sb, "        gainSetting: " + desc.mGainSetting);
            ProfileService.println(sb, "        gainMode: " + desc.mGainMode);
            ProfileService.println(sb, "        mute: " + desc.mMute);
            ProfileService.println(sb, "        units:" + desc.mGainSettingsUnits);
            ProfileService.println(sb, "        minGain:" + desc.mGainSettingsMin);
            ProfileService.println(sb, "        maxGain:" + desc.mGainSettingsMax);
        }
    }
}
