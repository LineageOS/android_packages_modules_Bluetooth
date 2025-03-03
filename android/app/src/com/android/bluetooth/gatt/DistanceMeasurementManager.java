/*
 * Copyright 2022 The Android Open Source Project
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

import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.le.ChannelSoundingParams;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.DistanceMeasurementResult;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Manages distance measurement operations and interacts with Gabeldorsche stack. */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class DistanceMeasurementManager {
    private static final String TAG = DistanceMeasurementManager.class.getSimpleName();

    private static final int RSSI_LOW_FREQUENCY_INTERVAL_MS = 3000;
    private static final int RSSI_MEDIUM_FREQUENCY_INTERVAL_MS = 1000;
    private static final int RSSI_HIGH_FREQUENCY_INTERVAL_MS = 500;
    private static final int CS_LOW_FREQUENCY_INTERVAL_MS = 5000;
    private static final int CS_MEDIUM_FREQUENCY_INTERVAL_MS = 200;
    private static final int CS_HIGH_FREQUENCY_INTERVAL_MS = 100;

    // sync with system/gd/hic/DistanceMeasurementManager
    private static final int INVALID_AZIMUTH_ANGLE_DEGREE = -1;
    private static final int INVALID_ALTITUDE_ANGLE_DEGREE = -91;

    private final AdapterService mAdapterService;
    private final HandlerThread mHandlerThread;
    private final DistanceMeasurementNativeInterface mDistanceMeasurementNativeInterface;
    private final DistanceMeasurementBinder mDistanceMeasurementBinder;
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<DistanceMeasurementTracker>>
            mRssiTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<DistanceMeasurementTracker>>
            mCsTrackers = new ConcurrentHashMap<>();
    private final boolean mHasChannelSoundingFeature;

    private volatile boolean mIsTurnedOff = false;

    /** Constructor of {@link DistanceMeasurementManager}. */
    DistanceMeasurementManager(AdapterService adapterService) {
        mAdapterService = adapterService;

        // Start a HandlerThread that handles distance measurement operations
        mHandlerThread = new HandlerThread("DistanceMeasurementManager");
        mHandlerThread.start();
        mDistanceMeasurementNativeInterface = DistanceMeasurementNativeInterface.getInstance();
        mDistanceMeasurementNativeInterface.init(this);
        mDistanceMeasurementBinder = new DistanceMeasurementBinder(adapterService, this);
        if (Flags.channelSounding25q2Apis()) {
            mHasChannelSoundingFeature =
                    adapterService
                            .getPackageManager()
                            .hasSystemFeature(FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING);
        } else {
            mHasChannelSoundingFeature = true;
        }
    }

    void cleanup() {
        mIsTurnedOff = true;
        mDistanceMeasurementBinder.cleanup();
        mDistanceMeasurementNativeInterface.cleanup();
        Log.d(TAG, "stop all sessions as BT is off");
        for (String addressForCs : mCsTrackers.keySet()) {
            onDistanceMeasurementStopped(
                    addressForCs,
                    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                    DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
        }
        for (String addressForRssi : mRssiTrackers.keySet()) {
            onDistanceMeasurementStopped(
                    addressForRssi,
                    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                    DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        }
    }

    DistanceMeasurementBinder getBinder() {
        return mDistanceMeasurementBinder;
    }

    List<DistanceMeasurementMethod> getSupportedDistanceMeasurementMethods() {
        ArrayList<DistanceMeasurementMethod> methods = new ArrayList<DistanceMeasurementMethod>();
        methods.add(
                new DistanceMeasurementMethod.Builder(
                                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI)
                        .build());
        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            methods.add(
                    new DistanceMeasurementMethod.Builder(
                                    DistanceMeasurementMethod
                                            .DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING)
                            .build());
        }
        return methods;
    }

    void startDistanceMeasurement(
            UUID uuid, DistanceMeasurementParams params, IDistanceMeasurementCallback callback) {
        if (mIsTurnedOff) {
            Log.d(TAG, "BT is turned off, no new request is allowed.");
            invokeStartFail(
                    callback, params.getDevice(), BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
            return;
        }
        Log.i(
                TAG,
                "startDistanceMeasurement:"
                        + (" device=" + params.getDevice())
                        + (" method=" + params.getMethodId()));
        if (!mAdapterService.isConnected(params.getDevice())) {
            Log.e(TAG, "Device " + params.getDevice() + " is not connected");
            invokeStartFail(
                    callback, params.getDevice(), BluetoothStatusCodes.ERROR_NO_LE_CONNECTION);
            return;
        }
        String address = mAdapterService.getIdentityAddress(params.getDevice().getAddress());
        if (address == null) {
            address = params.getDevice().getAddress();
        }
        logd(
                "startDistanceMeasurement: Get identityAddress: "
                        + params.getDevice()
                        + " => "
                        + BluetoothUtils.toAnonymizedAddress(address));

        int interval = getIntervalValue(params.getFrequency(), params.getMethodId());
        if (interval == -1) {
            invokeStartFail(
                    callback, params.getDevice(), BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
            return;
        }

        DistanceMeasurementTracker tracker =
                new DistanceMeasurementTracker(this, params, address, uuid, interval, callback);

        switch (params.getMethodId()) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO:
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                startRssiTracker(tracker);
                break;
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING:
                if (!mHasChannelSoundingFeature
                        || !mAdapterService.isLeChannelSoundingSupported()) {
                    Log.e(TAG, "Channel Sounding is not supported.");
                    invokeStartFail(
                            callback,
                            params.getDevice(),
                            BluetoothStatusCodes.FEATURE_NOT_SUPPORTED);
                    return;
                }
                if (mAdapterService.getBondState(params.getDevice())
                        != BluetoothDevice.BOND_BONDED) {
                    Log.e(TAG, "StartDistanceMeasurement: the target device is not bonded.");
                    invokeStartFail(
                            callback,
                            params.getDevice(),
                            BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
                    return;
                }
                startCsTracker(tracker);
                break;
            default:
                invokeStartFail(
                        callback, params.getDevice(), BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
        }
    }

    private synchronized void startRssiTracker(DistanceMeasurementTracker tracker) {
        mRssiTrackers.putIfAbsent(tracker.mIdentityAddress, new CopyOnWriteArraySet<>());
        CopyOnWriteArraySet<DistanceMeasurementTracker> set =
                mRssiTrackers.get(tracker.mIdentityAddress);
        if (!set.add(tracker)) {
            Log.w(TAG, "Already registered");
            return;
        }
        mDistanceMeasurementNativeInterface.startDistanceMeasurement(
                tracker.mIdentityAddress,
                tracker.mInterval,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
    }

    private synchronized void startCsTracker(DistanceMeasurementTracker tracker) {
        mCsTrackers.putIfAbsent(tracker.mIdentityAddress, new CopyOnWriteArraySet<>());
        CopyOnWriteArraySet<DistanceMeasurementTracker> set =
                mCsTrackers.get(tracker.mIdentityAddress);
        if (!set.add(tracker)) {
            Log.w(TAG, "Already registered");
            return;
        }
        mDistanceMeasurementNativeInterface.startDistanceMeasurement(
                tracker.mIdentityAddress,
                tracker.mInterval,
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
    }

    int stopDistanceMeasurement(UUID uuid, BluetoothDevice device, int method, boolean timeout) {
        Log.i(
                TAG,
                "stopDistanceMeasurement device:"
                        + BluetoothUtils.toAnonymizedAddress(device.getAddress())
                        + (" method: " + method)
                        + (" timeout " + timeout));
        String address = mAdapterService.getIdentityAddress(device.getAddress());
        if (address == null) {
            address = device.getAddress();
        }
        logd(
                "Get identityAddress: "
                        + BluetoothUtils.toAnonymizedAddress(device.getAddress())
                        + " => "
                        + BluetoothUtils.toAnonymizedAddress(address));

        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO:
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                return stopRssiTracker(uuid, address, timeout);
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING:
                return stopCsTracker(uuid, address, timeout);
            default:
                Log.w(TAG, "stopDistanceMeasurement with invalid method:" + method);
                return BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
        }
    }

    int getChannelSoundingMaxSupportedSecurityLevel(BluetoothDevice remoteDevice) {
        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            return ChannelSoundingParams.CS_SECURITY_LEVEL_ONE;
        }
        return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN;
    }

    int getLocalChannelSoundingMaxSupportedSecurityLevel() {
        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            return ChannelSoundingParams.CS_SECURITY_LEVEL_ONE;
        }
        return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN;
    }

    Set<Integer> getChannelSoundingSupportedSecurityLevels() {
        // TODO(b/378685103): get it from the HAL when level 4 is supported and HAL v2 is available.
        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            return Set.of(ChannelSoundingParams.CS_SECURITY_LEVEL_ONE);
        }
        throw new UnsupportedOperationException("Channel Sounding is not supported.");
    }

    private synchronized int stopRssiTracker(UUID uuid, String identityAddress, boolean timeout) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mRssiTrackers.get(identityAddress);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
        }

        for (DistanceMeasurementTracker tracker : set) {
            if (tracker.equals(uuid, identityAddress)) {
                int reason =
                        timeout
                                ? BluetoothStatusCodes.ERROR_TIMEOUT
                                : BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST;
                invokeOnStopped(tracker.mCallback, tracker.mDevice, reason);
                tracker.cancelTimer();
                set.remove(tracker);
                break;
            }
        }

        if (set.isEmpty()) {
            logd("no rssi tracker");
            mRssiTrackers.remove(identityAddress);
            mDistanceMeasurementNativeInterface.stopDistanceMeasurement(
                    identityAddress, DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    private synchronized int stopCsTracker(UUID uuid, String identityAddress, boolean timeout) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mCsTrackers.get(identityAddress);
        if (set == null) {
            Log.w(TAG, "Can't find CS tracker");
            return BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
        }

        for (DistanceMeasurementTracker tracker : set) {
            if (tracker.equals(uuid, identityAddress)) {
                int reason =
                        timeout
                                ? BluetoothStatusCodes.ERROR_TIMEOUT
                                : BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST;
                invokeOnStopped(tracker.mCallback, tracker.mDevice, reason);
                tracker.cancelTimer();
                set.remove(tracker);
                break;
            }
        }

        if (set.isEmpty()) {
            logd("No CS tracker exists; stop CS");
            mCsTrackers.remove(identityAddress);
            mDistanceMeasurementNativeInterface.stopDistanceMeasurement(
                    identityAddress,
                    DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    private static void invokeStartFail(
            IDistanceMeasurementCallback callback, BluetoothDevice device, int reason) {
        try {
            callback.onStartFail(device, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    private static void invokeOnStopped(
            IDistanceMeasurementCallback callback, BluetoothDevice device, int reason) {
        try {
            callback.onStopped(device, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /** Convert frequency into interval in ms */
    private static int getIntervalValue(int frequency, int method) {
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO:
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                switch (frequency) {
                    case DistanceMeasurementParams.REPORT_FREQUENCY_LOW:
                        return RSSI_LOW_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_MEDIUM:
                        return RSSI_MEDIUM_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_HIGH:
                        return RSSI_HIGH_FREQUENCY_INTERVAL_MS;
                }
                break;
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING:
                switch (frequency) {
                    case DistanceMeasurementParams.REPORT_FREQUENCY_LOW:
                        return CS_LOW_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_MEDIUM:
                        return CS_MEDIUM_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_HIGH:
                        return CS_HIGH_FREQUENCY_INTERVAL_MS;
                }
                break;
            default:
                Log.w(TAG, "getFrequencyValue fail frequency:" + frequency + ", method:" + method);
        }
        return -1;
    }

    void onDistanceMeasurementStarted(String address, int method) {
        logd(
                "onDistanceMeasurementStarted address:"
                        + BluetoothUtils.toAnonymizedAddress(address)
                        + ", method:"
                        + method);
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                handleRssiStarted(address);
                break;
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING:
                handleCsStarted(address);
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementResult: invalid method " + method);
        }
    }

    void handleRssiStarted(String address) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            try {
                if (!tracker.mStarted) {
                    tracker.mStarted = true;
                    tracker.mCallback.onStarted(tracker.mDevice);
                    tracker.startTimer(mHandlerThread.getLooper());
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    void handleCsStarted(String address) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mCsTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find CS tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            try {
                if (!tracker.mStarted) {
                    tracker.mStarted = true;
                    tracker.mCallback.onStarted(tracker.mDevice);
                    tracker.startTimer(mHandlerThread.getLooper());
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    void onDistanceMeasurementStopped(String address, int reason, int method) {
        logd(
                "onDistanceMeasurementStopped address:"
                        + BluetoothUtils.toAnonymizedAddress(address)
                        + ", reason:"
                        + reason
                        + ", method:"
                        + method);
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                handleRssiStopped(address, reason);
                break;
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING:
                handleCsStopped(address, reason);
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementStopped: invalid method " + method);
        }
    }

    void handleRssiStopped(String address, int reason) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            if (tracker.mStarted) {
                tracker.cancelTimer();
                invokeOnStopped(tracker.mCallback, tracker.mDevice, reason);
            } else {
                invokeStartFail(tracker.mCallback, tracker.mDevice, reason);
            }
        }
        mRssiTrackers.remove(address);
    }

    void handleCsStopped(String address, int reason) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mCsTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find CS tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            if (tracker.mStarted) {
                tracker.cancelTimer();
                invokeOnStopped(tracker.mCallback, tracker.mDevice, reason);
            } else {
                invokeStartFail(tracker.mCallback, tracker.mDevice, reason);
            }
        }
        mCsTrackers.remove(address);
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
            double delaySpreadMeters,
            int detectedAttackLevel,
            double velocityMetersPerSecond,
            int method) {
        logd(
                "onDistanceMeasurementResult "
                        + BluetoothUtils.toAnonymizedAddress(address)
                        + ", centimeter "
                        + centimeter
                        + ", confidenceLevel "
                        + confidenceLevel);
        DistanceMeasurementResult.Builder builder =
                new DistanceMeasurementResult.Builder(centimeter / 100.0, errorCentimeter / 100.0)
                        .setMeasurementTimestampNanos(elapsedRealtimeNanos);

        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                handleRssiResult(address, builder.build());
                break;
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING:
                if (azimuthAngle != INVALID_AZIMUTH_ANGLE_DEGREE) {
                    builder.setAzimuthAngle(azimuthAngle);
                    builder.setErrorAzimuthAngle(errorAzimuthAngle);
                }
                if (altitudeAngle != INVALID_ALTITUDE_ANGLE_DEGREE) {
                    builder.setAltitudeAngle(altitudeAngle);
                    builder.setErrorAltitudeAngle(errorAltitudeAngle);
                }
                if (confidenceLevel != -1) {
                    builder.setConfidenceLevel(confidenceLevel / 100.0);
                }
                if (delaySpreadMeters >= 0) {
                    builder.setDelaySpreadMeters(delaySpreadMeters);
                }
                if (velocityMetersPerSecond >= 0) {
                    builder.setVelocityMetersPerSecond(velocityMetersPerSecond);
                }
                builder.setDetectedAttackLevel(detectedAttackLevel);
                handleCsResult(address, builder.build());
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementResult: invalid method " + method);
        }
    }

    void handleRssiResult(String address, DistanceMeasurementResult result) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            if (!tracker.mStarted) {
                continue;
            }
            try {
                tracker.mCallback.onResult(tracker.mDevice, result);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    void handleCsResult(String address, DistanceMeasurementResult result) {
        CopyOnWriteArraySet<DistanceMeasurementTracker> set = mCsTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find cs tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            if (!tracker.mStarted) {
                continue;
            }
            try {
                tracker.mCallback.onResult(tracker.mDevice, result);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    /** Logs the message in debug ROM. */
    private static void logd(String msg) {
        Log.d(TAG, msg);
    }

}
