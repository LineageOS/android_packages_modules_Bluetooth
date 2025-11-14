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

import android.bluetooth.BluetoothStatusCodes;

/** Distance Measurement Native Interface to/from JNI. */
public class DistanceMeasurementNativeInterface {
    private static final String TAG = DistanceMeasurementNativeInterface.class.getSimpleName();

    /**
     * Do not modify without updating distance_measurement_manager.h match up with
     * DistanceMeasurementErrorCode enum of distance_measurement_manager.h
     */
    private static final int REASON_FEATURE_NOT_SUPPORTED_LOCAL = 0;

    private static final int REASON_FEATURE_NOT_SUPPORTED_REMOTE = 1;
    private static final int REASON_LOCAL_REQUEST = 2;
    private static final int REASON_REMOTE_REQUEST = 3;
    private static final int REASON_DURATION_TIMEOUT = 4;
    private static final int REASON_NO_LE_CONNECTION = 5;
    private static final int REASON_INVALID_PARAMETERS = 6;
    private static final int REASON_INTERNAL_ERROR = 7;

    private static final Object INSTANCE_LOCK = new Object();

    private final DistanceMeasurementManager mManager;

    DistanceMeasurementNativeInterface(DistanceMeasurementManager manager) {
        mManager = manager;
    }

    void init() {
        initializeNative();
    }

    void cleanup() {
        cleanupNative();
    }

    void startDistanceMeasurement(
            int appUid, String address, int interval, int method, int sightType, int locationType) {
        startDistanceMeasurementNative(appUid, address, interval, method, sightType, locationType);
    }

    void stopDistanceMeasurement(String address, int method) {
        stopDistanceMeasurementNative(address, method);
    }

    void onDistanceMeasurementStarted(String address, int method) {
        mManager.postOnDistanceMeasurementThread(
                () -> mManager.onDistanceMeasurementStarted(address, method));
    }

    void onDistanceMeasurementStopped(String address, int reason, int method) {
        mManager.postOnDistanceMeasurementThread(
                () ->
                        mManager.onDistanceMeasurementStopped(
                                address, convertErrorCode(reason), method));
    }

    void onDistanceMeasurementResult(
            String address,
            int centimeter,
            int errorCentimeter,
            int azimuthAngle,
            int errorAzimuthAngle,
            int altitudeAngle,
            int errorAltitudeAngle,
            long elapsedRealtimeNanos,
            int confidenceLevel,
            double delayedSpreadMeters,
            int detectedAttackLevel,
            double velocityMetersPerSecond,
            int method) {
        mManager.postOnDistanceMeasurementThread(
                () ->
                        mManager.onDistanceMeasurementResult(
                                address,
                                centimeter,
                                errorCentimeter,
                                azimuthAngle,
                                errorAzimuthAngle,
                                altitudeAngle,
                                errorAltitudeAngle,
                                elapsedRealtimeNanos,
                                confidenceLevel,
                                delayedSpreadMeters,
                                detectedAttackLevel,
                                velocityMetersPerSecond,
                                method));
    }

    private static int convertErrorCode(int errorCode) {
        return switch (errorCode) {
            case REASON_FEATURE_NOT_SUPPORTED_LOCAL -> BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
            case REASON_FEATURE_NOT_SUPPORTED_REMOTE ->
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED;
            case REASON_LOCAL_REQUEST -> BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST;
            case REASON_REMOTE_REQUEST -> BluetoothStatusCodes.REASON_REMOTE_REQUEST;
            case REASON_DURATION_TIMEOUT -> BluetoothStatusCodes.ERROR_TIMEOUT;
            case REASON_NO_LE_CONNECTION -> BluetoothStatusCodes.ERROR_NO_LE_CONNECTION;
            case REASON_INVALID_PARAMETERS -> BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
            case REASON_INTERNAL_ERROR -> BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
            default -> BluetoothStatusCodes.ERROR_UNKNOWN;
        };
    }

    /**********************************************************************************************/
    /******************************************* native *******************************************/
    /**********************************************************************************************/

    private native void initializeNative();

    private native void cleanupNative();

    private native void startDistanceMeasurementNative(
            int appUid, String address, int interval, int method, int sightType, int locationType);

    private native void stopDistanceMeasurementNative(String address, int method);
}
