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

package com.android.bluetooth.hap;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;

import java.lang.annotation.Native;

/** Hearing Access Profile Client Native Interface to/from JNI. */
public class HapClientNativeInterface {
    private static final String TAG = HapClientNativeInterface.class.getSimpleName();

    @Native private final HapClientNativeCallback mHapClientNativeCallback;

    public HapClientNativeInterface(HapClientNativeCallback hapClientNativeCallback) {
        mHapClientNativeCallback = requireNonNull(hapClientNativeCallback);
    }

    boolean connectHapClient(BluetoothDevice device) {
        return connectHapClientNative(getByteAddress(device));
    }

    boolean disconnectHapClient(BluetoothDevice device) {
        return disconnectHapClientNative(getByteAddress(device));
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    void init() {
        initNative();
    }

    void cleanup() {
        cleanupNative();
    }

    void selectActivePreset(BluetoothDevice device, int presetIndex) {
        selectActivePresetNative(getByteAddress(device), presetIndex);
    }

    void groupSelectActivePreset(int groupId, int presetIndex) {
        groupSelectActivePresetNative(groupId, presetIndex);
    }

    void nextActivePreset(BluetoothDevice device) {
        nextActivePresetNative(getByteAddress(device));
    }

    void groupNextActivePreset(int groupId) {
        groupNextActivePresetNative(groupId);
    }

    void previousActivePreset(BluetoothDevice device) {
        previousActivePresetNative(getByteAddress(device));
    }

    void groupPreviousActivePreset(int groupId) {
        groupPreviousActivePresetNative(groupId);
    }

    void getPresetInfo(BluetoothDevice device, int presetIndex) {
        getPresetInfoNative(getByteAddress(device), presetIndex);
    }

    void setPresetName(BluetoothDevice device, int presetIndex, String name) {
        setPresetNameNative(getByteAddress(device), presetIndex, name);
    }

    void groupSetPresetName(int groupId, int presetIndex, String name) {
        groupSetPresetNameNative(groupId, presetIndex, name);
    }

    // Native methods that call into the JNI interface
    private native void initNative();

    private native void cleanupNative();

    private native boolean connectHapClientNative(byte[] address);

    private native boolean disconnectHapClientNative(byte[] address);

    private native void selectActivePresetNative(byte[] byteAddress, int presetIndex);

    private native void groupSelectActivePresetNative(int groupId, int presetIndex);

    private native void nextActivePresetNative(byte[] byteAddress);

    private native void groupNextActivePresetNative(int groupId);

    private native void previousActivePresetNative(byte[] byteAddress);

    private native void groupPreviousActivePresetNative(int groupId);

    private native void getPresetInfoNative(byte[] byteAddress, int presetIndex);

    private native void setPresetNameNative(byte[] byteAddress, int presetIndex, String name);

    private native void groupSetPresetNameNative(int groupId, int presetIndex, String name);
}
