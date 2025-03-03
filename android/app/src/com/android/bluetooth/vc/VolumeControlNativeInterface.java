/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;

import java.lang.annotation.Native;

public class VolumeControlNativeInterface {
    private static final String TAG = VolumeControlNativeInterface.class.getSimpleName();

    // Value access from native, see com_android_bluetooth_vc.cpp
    @Native private final VolumeControlNativeCallback mNativeCallback;

    VolumeControlNativeInterface(VolumeControlNativeCallback nativeCallback) {
        mNativeCallback = requireNonNull(nativeCallback);
    }

    void init() {
        initNative();
    }

    void cleanup() {
        cleanupNative();
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    boolean connectVolumeControl(BluetoothDevice device) {
        return connectVolumeControlNative(getByteAddress(device));
    }

    boolean disconnectVolumeControl(BluetoothDevice device) {
        return disconnectVolumeControlNative(getByteAddress(device));
    }

    void setVolume(BluetoothDevice device, int volume) {
        setVolumeNative(getByteAddress(device), volume);
    }

    void setGroupVolume(int groupId, int volume) {
        setGroupVolumeNative(groupId, volume);
    }

    void mute(BluetoothDevice device) {
        muteNative(getByteAddress(device));
    }

    void muteGroup(int groupId) {
        muteGroupNative(groupId);
    }

    void unmute(BluetoothDevice device) {
        unmuteNative(getByteAddress(device));
    }

    void unmuteGroup(int groupId) {
        unmuteGroupNative(groupId);
    }

    boolean getExtAudioOutVolumeOffset(BluetoothDevice device, int externalOutputId) {
        return getExtAudioOutVolumeOffsetNative(getByteAddress(device), externalOutputId);
    }

    boolean setExtAudioOutVolumeOffset(BluetoothDevice device, int externalOutputId, int offset) {
        if (Utils.isPtsTestMode()) {
            setVolumeNative(getByteAddress(device), offset);
            return true;
        }
        return setExtAudioOutVolumeOffsetNative(getByteAddress(device), externalOutputId, offset);
    }

    boolean getExtAudioOutLocation(BluetoothDevice device, int externalOutputId) {
        return getExtAudioOutLocationNative(getByteAddress(device), externalOutputId);
    }

    boolean setExtAudioOutLocation(BluetoothDevice device, int externalOutputId, int location) {
        return setExtAudioOutLocationNative(getByteAddress(device), externalOutputId, location);
    }

    boolean getExtAudioOutDescription(BluetoothDevice device, int externalOutputId) {
        return getExtAudioOutDescriptionNative(getByteAddress(device), externalOutputId);
    }

    boolean setExtAudioOutDescription(BluetoothDevice device, int externalOutputId, String descr) {
        return setExtAudioOutDescriptionNative(getByteAddress(device), externalOutputId, descr);
    }

    boolean getExtAudioInState(BluetoothDevice device, int externalInputId) {
        return getExtAudioInStateNative(getByteAddress(device), externalInputId);
    }

    boolean getExtAudioInStatus(BluetoothDevice device, int externalInputId) {
        return getExtAudioInStatusNative(getByteAddress(device), externalInputId);
    }

    boolean getExtAudioInType(BluetoothDevice device, int externalInputId) {
        return getExtAudioInTypeNative(getByteAddress(device), externalInputId);
    }

    boolean getExtAudioInGainProps(BluetoothDevice device, int externalInputId) {
        return getExtAudioInGainPropsNative(getByteAddress(device), externalInputId);
    }

    boolean getExtAudioInDescription(BluetoothDevice device, int externalInputId) {
        return getExtAudioInDescriptionNative(getByteAddress(device), externalInputId);
    }

    boolean setExtAudioInDescription(BluetoothDevice device, int externalInputId, String descr) {
        return setExtAudioInDescriptionNative(getByteAddress(device), externalInputId, descr);
    }

    boolean setExtAudioInGainSetting(BluetoothDevice device, int externalInputId, int gainSetting) {
        return setExtAudioInGainSettingNative(getByteAddress(device), externalInputId, gainSetting);
    }

    boolean setExtAudioInGainMode(BluetoothDevice device, int externalInputId, int gainMode) {
        return setExtAudioInGainModeNative(getByteAddress(device), externalInputId, gainMode);
    }

    boolean setExtAudioInMute(BluetoothDevice device, int externalInputId, int mute) {
        return setExtAudioInMuteNative(getByteAddress(device), externalInputId, mute);
    }

    // Native methods that call into the JNI interface
    private native void initNative();

    private native void cleanupNative();

    private native boolean connectVolumeControlNative(byte[] address);

    private native boolean disconnectVolumeControlNative(byte[] address);

    private native void setVolumeNative(byte[] address, int volume);

    private native void setGroupVolumeNative(int groupId, int volume);

    private native void muteNative(byte[] address);

    private native void muteGroupNative(int groupId);

    private native void unmuteNative(byte[] address);

    private native void unmuteGroupNative(int groupId);

    private native boolean getExtAudioOutVolumeOffsetNative(byte[] address, int externalOutputId);

    private native boolean setExtAudioOutVolumeOffsetNative(
            byte[] address, int externalOutputId, int offset);

    private native boolean getExtAudioOutLocationNative(byte[] address, int externalOutputId);

    private native boolean setExtAudioOutLocationNative(
            byte[] address, int externalOutputId, int location);

    private native boolean getExtAudioOutDescriptionNative(byte[] address, int externalOutputId);

    private native boolean setExtAudioOutDescriptionNative(
            byte[] address, int externalOutputId, String descr);

    /* Native methods for audio inputs control service */
    private native boolean getExtAudioInStateNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInStatusNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInTypeNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInGainPropsNative(byte[] address, int externalInputId);

    private native boolean getExtAudioInDescriptionNative(byte[] address, int externalInputId);

    private native boolean setExtAudioInDescriptionNative(
            byte[] address, int externalInputId, String descr);

    private native boolean setExtAudioInGainSettingNative(
            byte[] address, int externalInputId, int gainSetting);

    private native boolean setExtAudioInGainModeNative(
            byte[] address, int externalInputId, int gainMode);

    private native boolean setExtAudioInMuteNative(byte[] address, int externalInputId, int mute);
}
