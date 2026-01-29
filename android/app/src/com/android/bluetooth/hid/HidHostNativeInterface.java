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

package com.android.bluetooth.hid;

import static java.util.Objects.requireNonNull;

import com.android.bluetooth.profile.NativeInterface;

/** Provides Bluetooth Hid Host profile, as a service in the Bluetooth application. */
public class HidHostNativeInterface extends NativeInterface<HidHostNativeCallback> {

    HidHostNativeInterface(HidHostNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    void init() {
        initializeNative();
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    boolean connectHid(byte[] address, int addressType, int transport, boolean direct) {
        return connectHidNative(address, addressType, transport, direct);
    }

    boolean disconnectHid(byte[] address, int addressType, int transport, int reconnectPolicy) {
        return disconnectHidNative(address, addressType, transport, reconnectPolicy);
    }

    boolean getProtocolMode(byte[] address, int addressType, int transport) {
        return getProtocolModeNative(address, addressType, transport);
    }

    boolean virtualUnPlug(byte[] address, int addressType, int transport) {
        return virtualUnPlugNative(address, addressType, transport);
    }

    boolean setProtocolMode(byte[] address, int addressType, int transport, byte protocolMode) {
        return setProtocolModeNative(address, addressType, transport, protocolMode);
    }

    boolean getReport(
            byte[] address,
            int addressType,
            int transport,
            byte reportType,
            byte reportId,
            int bufferSize) {
        return getReportNative(address, addressType, transport, reportType, reportId, bufferSize);
    }

    boolean setReport(
            byte[] address, int addressType, int transport, byte reportType, String report) {
        return setReportNative(address, addressType, transport, reportType, report);
    }

    boolean sendData(byte[] address, int addressType, int transport, String report) {
        return sendDataNative(address, addressType, transport, report);
    }

    boolean setIdleTime(byte[] address, int addressType, int transport, byte idleTime) {
        return setIdleTimeNative(address, addressType, transport, idleTime);
    }

    boolean getIdleTime(byte[] address, int addressType, int transport) {
        return getIdleTimeNative(address, addressType, transport);
    }

    private native void initializeNative();

    private native void cleanupNative();

    private native boolean connectHidNative(
            byte[] btAddress, int addressType, int transport, boolean direct);

    private native boolean disconnectHidNative(
            byte[] btAddress, int addressType, int transport, int reconnectPolicy);

    private native boolean getProtocolModeNative(byte[] btAddress, int addressType, int transport);

    private native boolean virtualUnPlugNative(byte[] btAddress, int addressType, int transport);

    private native boolean setProtocolModeNative(
            byte[] btAddress, int addressType, int transport, byte protocolMode);

    private native boolean getReportNative(
            byte[] btAddress,
            int addressType,
            int transport,
            byte reportType,
            byte reportId,
            int bufferSize);

    private native boolean setReportNative(
            byte[] btAddress, int addressType, int transport, byte reportType, String report);

    private native boolean sendDataNative(
            byte[] btAddress, int addressType, int transport, String report);

    private native boolean setIdleTimeNative(
            byte[] btAddress, int addressType, int transport, byte idleTime);

    private native boolean getIdleTimeNative(byte[] btAddress, int addressType, int transport);
}
