/*
 * Copyright (C) 2017 The Android Open Source Project
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

/*
 * Defines the native interface that is used by HID Device service to
 * send or receive messages from the native stack. This file is registered
 * for the native methods in the corresponding JNI C++ file.
 */

package com.android.bluetooth.hid;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

/** HID Device Native Interface to/from JNI. */
public class HidDeviceNativeInterface {
    private static final String TAG = HidDeviceNativeInterface.class.getSimpleName();

    private final AdapterService mAdapterService;
    private final HidDeviceService mService;

    HidDeviceNativeInterface(AdapterService adapterService, HidDeviceService service) {
        mAdapterService = requireNonNull(adapterService);
        mService = service;
    }

    /** Initializes the native interface. */
    void init() {
        initNative();
    }

    /** Cleanup the native interface. */
    void cleanup() {
        cleanupNative();
    }

    /**
     * Registers the application
     *
     * @param name name of the HID Device application
     * @param description description of the HID Device application
     * @param provider provider of the HID Device application
     * @param subclass subclass of the HID Device application
     * @param descriptors HID descriptors
     * @param inQos incoming QoS settings
     * @param outQos outgoing QoS settings
     * @return the result of the native call
     */
    boolean registerApp(
            String name,
            String description,
            String provider,
            byte subclass,
            byte[] descriptors,
            int[] inQos,
            int[] outQos) {
        return registerAppNative(name, description, provider, subclass, descriptors, inQos, outQos);
    }

    /**
     * Unregisters the application
     *
     * @return the result of the native call
     */
    boolean unregisterApp() {
        return unregisterAppNative();
    }

    /**
     * Send report to the remote host
     *
     * @param id report ID
     * @param data report data array
     * @return the result of the native call
     */
    boolean sendReport(int id, byte[] data) {
        return sendReportNative(id, data);
    }

    /**
     * Reply report to the remote host
     *
     * @param type report type
     * @param id report ID
     * @param data report data array
     * @return the result of the native call
     */
    boolean replyReport(byte type, byte id, byte[] data) {
        return replyReportNative(type, id, data);
    }

    /**
     * Send virtual unplug to the remote host
     *
     * @return the result of the native call
     */
    boolean unplug() {
        return unplugNative();
    }

    /**
     * Connect to the remote host
     *
     * @param device remote host device
     * @return the result of the native call
     */
    boolean connect(BluetoothDevice device) {
        return connectNative(Utils.getByteBrEdrAddress(mAdapterService, device));
    }

    /**
     * Disconnect from the remote host
     *
     * @return the result of the native call
     */
    boolean disconnect() {
        return disconnectNative();
    }

    /**
     * Report error to the remote host
     *
     * @param error error byte
     * @return the result of the native call
     */
    boolean reportError(byte error) {
        return reportErrorNative(error);
    }

    @VisibleForTesting
    synchronized void onApplicationStateChanged(byte[] address, boolean registered) {
        mService.onApplicationStateChangedFromNative(getDevice(address), registered);
    }

    @VisibleForTesting
    synchronized void onConnectStateChanged(byte[] address, int state) {
        mService.onConnectStateChangedFromNative(getDevice(address), state);
    }

    @VisibleForTesting
    synchronized void onGetReport(byte type, byte id, short bufferSize) {
        mService.onGetReportFromNative(type, id, bufferSize);
    }

    @VisibleForTesting
    synchronized void onSetReport(byte reportType, byte reportId, byte[] data) {
        mService.onSetReportFromNative(reportType, reportId, data);
    }

    @VisibleForTesting
    synchronized void onSetProtocol(byte protocol) {
        mService.onSetProtocolFromNative(protocol);
    }

    @VisibleForTesting
    synchronized void onInterruptData(byte reportId, byte[] data) {
        mService.onInterruptDataFromNative(reportId, data);
    }

    @VisibleForTesting
    synchronized void onVirtualCableUnplug() {
        mService.onVirtualCableUnplugFromNative();
    }

    private BluetoothDevice getDevice(byte[] address) {
        if (address == null) {
            return null;
        }
        return mAdapterService.getDeviceFromByte(address);
    }

    private native void initNative();

    private native void cleanupNative();

    private native boolean registerAppNative(
            String name,
            String description,
            String provider,
            byte subclass,
            byte[] descriptors,
            int[] inQos,
            int[] outQos);

    private native boolean unregisterAppNative();

    private native boolean sendReportNative(int id, byte[] data);

    private native boolean replyReportNative(byte type, byte id, byte[] data);

    private native boolean unplugNative();

    private native boolean connectNative(byte[] btAddress);

    private native boolean disconnectNative();

    private native boolean reportErrorNative(byte error);
}
