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

package com.android.bluetooth.hid;

import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/** Provides Bluetooth Hid Host profile, as a service in the Bluetooth application. */
public class HidHostNativeInterface {
    private static final String TAG = HidHostNativeInterface.class.getSimpleName();

    private HidHostService mHidHostService;

    @GuardedBy("INSTANCE_LOCK")
    private static HidHostNativeInterface sInstance;

    private static final Object INSTANCE_LOCK = new Object();

    static HidHostNativeInterface getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new HidHostNativeInterface();
            }
            return sInstance;
        }
    }

    /** Set singleton instance. */
    @VisibleForTesting
    public static void setInstance(HidHostNativeInterface instance) {
        synchronized (INSTANCE_LOCK) {
            sInstance = instance;
        }
    }

    void init(HidHostService service) {
        mHidHostService = service;
        initializeNative();
    }

    void cleanup() {
        cleanupNative();
    }

    boolean connectHid(byte[] address, int addressType, int transport) {
        return connectHidNative(address, addressType, transport);
    }

    boolean disconnectHid(
            byte[] address, int addressType, int transport, boolean reconnectAllowed) {
        return disconnectHidNative(address, addressType, transport, reconnectAllowed);
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

    private static int convertHalState(int halState) {
        switch (halState) {
            case CONN_STATE_CONNECTED:
                return HidHostService.STATE_CONNECTED;
            case CONN_STATE_CONNECTING:
                return HidHostService.STATE_CONNECTING;
            case CONN_STATE_DISCONNECTED:
                return HidHostService.STATE_DISCONNECTED;
            case CONN_STATE_DISCONNECTING:
                return HidHostService.STATE_DISCONNECTING;
            case CONN_STATE_ACCEPTING:
                return HidHostService.STATE_ACCEPTING;
            default:
                Log.e(TAG, "bad hid connection state: " + halState);
                return HidHostService.STATE_DISCONNECTED;
        }
    }

    /**********************************************************************************************/
    /*********************************** callbacks from native ************************************/
    /**********************************************************************************************/

    private void onConnectStateChanged(byte[] address, int addressType, int transport, int state) {
        Log.d(TAG, "onConnectStateChanged: state=" + state);
        mHidHostService.onConnectStateChanged(
                address, addressType, transport, convertHalState(state));
    }

    private void onGetProtocolMode(byte[] address, int addressType, int transport, int mode) {
        Log.d(TAG, "onGetProtocolMode()");
        mHidHostService.onGetProtocolMode(address, addressType, transport, mode);
    }

    private void onGetReport(
            byte[] address, int addressType, int transport, byte[] report, int rptSize) {
        Log.d(TAG, "onGetReport()");
        mHidHostService.onGetReport(address, addressType, transport, report, rptSize);
    }

    private void onHandshake(byte[] address, int addressType, int transport, int status) {
        Log.d(TAG, "onHandshake: status=" + status);
        mHidHostService.onHandshake(address, addressType, transport, status);
    }

    private void onVirtualUnplug(byte[] address, int addressType, int transport, int status) {
        Log.d(TAG, "onVirtualUnplug: status=" + status);
        mHidHostService.onVirtualUnplug(address, addressType, transport, status);
    }

    private void onGetIdleTime(byte[] address, int addressType, int transport, int idleTime) {
        Log.d(TAG, "onGetIdleTime()");
        mHidHostService.onGetIdleTime(address, addressType, transport, idleTime);
    }

    /**********************************************************************************************/
    /******************************************* native *******************************************/
    /**********************************************************************************************/

    // Constants matching Hal header file bt_hh.h
    // bthh_connection_state_t
    private static final int CONN_STATE_CONNECTED = 0;

    private static final int CONN_STATE_CONNECTING = 1;
    private static final int CONN_STATE_DISCONNECTED = 2;
    private static final int CONN_STATE_DISCONNECTING = 3;
    private static final int CONN_STATE_ACCEPTING = 4;

    private native void initializeNative();

    private native void cleanupNative();

    private native boolean connectHidNative(byte[] btAddress, int addressType, int transport);

    private native boolean disconnectHidNative(
            byte[] btAddress, int addressType, int transport, boolean reconnectAllowed);

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
