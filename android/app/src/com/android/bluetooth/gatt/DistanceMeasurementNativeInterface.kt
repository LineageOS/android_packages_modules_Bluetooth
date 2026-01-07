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

package com.android.bluetooth.gatt;

import static java.util.Objects.requireNonNull;

import com.android.bluetooth.profile.NativeInterface;

public class DistanceMeasurementNativeInterface
        extends NativeInterface<DistanceMeasurementNativeCallback> {

    DistanceMeasurementNativeInterface(DistanceMeasurementNativeCallback nativeCallback) {
        super(requireNonNull(nativeCallback));
    }

    void init() {
        initializeNative();
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    void startDistanceMeasurement(
            int appUid, String address, int interval, int method, int sightType, int locationType) {
        startDistanceMeasurementNative(appUid, address, interval, method, sightType, locationType);
    }

    void stopDistanceMeasurement(String address, int method) {
        stopDistanceMeasurementNative(address, method);
    }

    private native void initializeNative();

    private native void cleanupNative();

    private native void startDistanceMeasurementNative(
            int appUid, String address, int interval, int method, int sightType, int locationType);

    private native void stopDistanceMeasurementNative(String address, int method);
}
