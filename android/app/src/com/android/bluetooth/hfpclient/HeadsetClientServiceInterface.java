/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.bluetooth.hfpclient;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Interface for talking to the HeadsetClientService
 *
 * <p>Deals with service lifecycle and returns consistent error values
 */
public class HeadsetClientServiceInterface {
    private static final String TAG = HeadsetClientServiceInterface.class.getSimpleName();

    /* Action policy for other calls when accepting call */
    public static final int CALL_ACCEPT_NONE = 0;
    public static final int CALL_ACCEPT_HOLD = 1;
    public static final int CALL_ACCEPT_TERMINATE = 2;

    HeadsetClientServiceInterface() {}

    private static Optional<HeadsetClientService> getHeadsetClientService() {
        return Optional.ofNullable(AdapterService.deprecatedGetAdapterService())
                .flatMap(AdapterService::getHeadsetClientService);
    }

    private static boolean isServiceAvailable(Optional<HeadsetClientService> headsetClient) {
        if (headsetClient.isEmpty()) {
            Log.w(TAG, "HeadsetClientService is not available");
            return false;
        }
        return true;
    }

    public HfpClientCall dial(BluetoothDevice device, String number) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return null;
        return headsetClient.get().dial(device, number);
    }

    public boolean enterPrivateMode(BluetoothDevice device, int index) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().enterPrivateMode(device, index);
    }

    public boolean sendDTMF(BluetoothDevice device, byte code) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().sendDTMF(device, code);
    }

    public boolean terminateCall(BluetoothDevice device, HfpClientCall call) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().terminateCall(device, call != null ? call.getUUID() : null);
    }

    public boolean holdCall(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().holdCall(device);
    }

    public boolean acceptCall(BluetoothDevice device, int flag) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().acceptCall(device, flag);
    }

    public boolean rejectCall(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().rejectCall(device);
    }

    public boolean connectAudio(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().connectAudio(device);
    }

    public boolean disconnectAudio(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return false;
        return headsetClient.get().disconnectAudio(device);
    }

    public Set<Integer> getCurrentAgFeatures(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return null;
        return headsetClient.get().getCurrentAgFeatures(device);
    }

    public Bundle getCurrentAgEvents(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return null;
        return headsetClient.get().getCurrentAgEvents(device);
    }

    public List<BluetoothDevice> getConnectedDevices() {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return null;
        return headsetClient.get().getConnectedDevices();
    }

    public List<HfpClientCall> getCurrentCalls(BluetoothDevice device) {
        final var headsetClient = getHeadsetClientService();
        if (!isServiceAvailable(headsetClient)) return null;
        return headsetClient.get().getCurrentCalls(device);
    }

    public boolean hasHfpClientEcc(BluetoothDevice device) {
        Set<Integer> features = getCurrentAgFeatures(device);
        return features != null && features.contains(HeadsetClientHalConstants.PEER_FEAT_ECC);
    }
}
