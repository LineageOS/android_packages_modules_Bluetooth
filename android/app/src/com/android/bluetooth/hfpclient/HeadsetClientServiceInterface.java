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

import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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

    private static <T> T withHeadsetClient(
            Function<HeadsetClientService, T> action, T defaultValue) {
        var headsetClient =
                Optional.ofNullable(AdapterService.deprecatedGetAdapterService())
                        .flatMap(AdapterService::getHeadsetClientService)
                        .orElse(null);
        if (headsetClient == null) {
            Log.w(TAG, "HeadsetClientService is not available");
            return defaultValue;
        }
        return action.apply(headsetClient);
    }

    public HfpClientCall dial(BluetoothDevice device, String number) {
        return withHeadsetClient(hC -> hC.dial(device, number), null);
    }

    public boolean enterPrivateMode(BluetoothDevice device, int index) {
        return withHeadsetClient(hC -> hC.enterPrivateMode(device, index), false);
    }

    public boolean sendDTMF(BluetoothDevice device, byte code) {
        return withHeadsetClient(hC -> hC.sendDTMF(device, code), false);
    }

    public boolean terminateCall(BluetoothDevice device, HfpClientCall call) {
        var uuid = call != null ? call.getUUID() : null;
        return withHeadsetClient(hC -> hC.terminateCall(device, uuid), false);
    }

    public boolean holdCall(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.holdCall(device), false);
    }

    public boolean acceptCall(BluetoothDevice device, int flag) {
        return withHeadsetClient(hC -> hC.acceptCall(device, flag), false);
    }

    public boolean rejectCall(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.rejectCall(device), false);
    }

    public boolean connectAudio(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.connectAudio(device), false);
    }

    public boolean disconnectAudio(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.disconnectAudio(device), false);
    }

    public @Nullable Set<Integer> getCurrentAgFeatures(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.getCurrentAgFeatures(device), null);
    }

    public Bundle getCurrentAgEvents(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.getCurrentAgEvents(device), null);
    }

    public List<BluetoothDevice> getConnectedDevices() {
        return withHeadsetClient(hC -> hC.getConnectedDevices(), null);
    }

    public List<HfpClientCall> getCurrentCalls(BluetoothDevice device) {
        return withHeadsetClient(hC -> hC.getCurrentCalls(device), null);
    }

    public boolean hasHfpClientEcc(BluetoothDevice device) {
        Set<Integer> features = getCurrentAgFeatures(device);
        return features != null && features.contains(HeadsetClientHalConstants.PEER_FEAT_ECC);
    }
}
