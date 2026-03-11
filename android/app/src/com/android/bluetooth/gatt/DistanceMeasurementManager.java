/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.bluetooth.BluetoothUtils.toAnonymizedAddress;
import static android.bluetooth.le.DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO;
import static android.bluetooth.le.DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING;
import static android.bluetooth.le.DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI;
import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING;

import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BondStatus;
import android.bluetooth.EncryptionStatus;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ChannelSoundingParams;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.DistanceMeasurementResult;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.metrics.MetricsLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Manages distance measurement operations and interacts with Gabeldorsche stack. */
public class DistanceMeasurementManager {
    private static final String TAG =
            GattUtil.TAG_PREFIX + DistanceMeasurementManager.class.getSimpleName();

    private static final long RUN_SYNC_WAIT_TIME_MS = 2000L;

    private static final int RSSI_LOW_FREQUENCY_INTERVAL_MS = 3000;
    private static final int RSSI_MEDIUM_FREQUENCY_INTERVAL_MS = 1000;
    private static final int RSSI_HIGH_FREQUENCY_INTERVAL_MS = 500;
    private static final int CS_LOW_FREQUENCY_INTERVAL_MS = 5000;
    private static final int CS_MEDIUM_FREQUENCY_INTERVAL_MS = 200;
    private static final int CS_HIGH_FREQUENCY_INTERVAL_MS = 100;
    private static final int THREAD_WAIT_TIMEOUT_MS = 2000;

    // sync with system/gd/hci/DistanceMeasurementManager
    private static final int INVALID_AZIMUTH_ANGLE_DEGREE = -1;
    private static final int INVALID_ALTITUDE_ANGLE_DEGREE = -91;

    private final AdapterService mAdapterService;
    private final Handler mHandler;
    private final DistanceMeasurementNativeInterface mNativeInterface;
    private final DistanceMeasurementBinder mDistanceMeasurementBinder;
    private final Map<String, Set<DistanceMeasurementTracker>> mRssiTrackers = new HashMap<>();
    private final Map<String, Set<DistanceMeasurementTracker>> mCsTrackers = new HashMap<>();
    private final boolean mHasChannelSoundingFeature;

    private volatile boolean mIsTurnedOff = false;

    DistanceMeasurementManager(
            AdapterService adapterService,
            GattService gattService,
            DistanceMeasurementNativeInterface nativeInterface,
            Looper looper) {
        mAdapterService = adapterService;
        mHandler = new Handler(looper);

        var nativeCallback = new DistanceMeasurementNativeCallback(mAdapterService, this);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () -> new DistanceMeasurementNativeInterface(nativeCallback));
        mNativeInterface.init();
        mDistanceMeasurementBinder =
                new DistanceMeasurementBinder(mAdapterService, gattService, this);
        mHasChannelSoundingFeature =
                adapterService
                        .getPackageManager()
                        .hasSystemFeature(FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING);
        postOnDistanceMeasurementThread(
                () -> {
                    int[] csTypes = {
                        BluetoothStatsLog.CHANNEL_SOUNDING_TYPES_SUPPORTED__CS_TYPES__CS_UNSPECIFIED
                    };
                    if (mHasChannelSoundingFeature) {
                        csTypes[0] =
                                BluetoothStatsLog
                                        .CHANNEL_SOUNDING_TYPES_SUPPORTED__CS_TYPES__CS_BT_CORE60;
                    }
                    MetricsLogger.getInstance().logChannelSoundingTypesSupported(csTypes);
                });
    }

    void cleanup() {
        if (Flags.gattThread()) {
            enforceThread();

            Log.d(TAG, "cleanup()");
            mIsTurnedOff = true;
            mHandler.removeCallbacksAndMessages(null);
            mDistanceMeasurementBinder.cleanup();
            mNativeInterface.cleanup();
            for (String addressForCs : mCsTrackers.keySet()) {
                onDistanceMeasurementStopped(
                        addressForCs,
                        BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                        DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
            }
            for (String addressForRssi : mRssiTrackers.keySet()) {
                onDistanceMeasurementStopped(
                        addressForRssi,
                        BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                        DISTANCE_MEASUREMENT_METHOD_RSSI);
            }
        } else {
            forceRunSyncOnDistanceMeasurementThread(
                    () -> {
                        mIsTurnedOff = true;
                        mHandler.removeCallbacksAndMessages(null);
                        mDistanceMeasurementBinder.cleanup();
                        mNativeInterface.cleanup();
                        Log.d(TAG, "stop all sessions as BT is off");
                        for (String addressForCs : mCsTrackers.keySet()) {
                            onDistanceMeasurementStopped(
                                    addressForCs,
                                    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                                    DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
                        }
                        for (String addressForRssi : mRssiTrackers.keySet()) {
                            onDistanceMeasurementStopped(
                                    addressForRssi,
                                    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                                    DISTANCE_MEASUREMENT_METHOD_RSSI);
                        }
                    });
        }
    }

    DistanceMeasurementBinder getBinder() {
        return mDistanceMeasurementBinder;
    }

    List<DistanceMeasurementMethod> getSupportedDistanceMeasurementMethods() {
        List<DistanceMeasurementMethod> methods = new ArrayList<>();
        methods.add(
                new DistanceMeasurementMethod.Builder(DISTANCE_MEASUREMENT_METHOD_RSSI).build());
        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            methods.add(
                    new DistanceMeasurementMethod.Builder(
                                    DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING)
                            .build());
        }
        return methods;
    }

    void startDistanceMeasurement(
            UUID uuid,
            int appUid,
            DistanceMeasurementParams params,
            IDistanceMeasurementCallback callback) {
        enforceThread();
        final BluetoothDevice device = params.getDevice();

        if (mIsTurnedOff) {
            Log.d(TAG, "startDistanceMeasurement(): BT is turned off, no new requests are allowed");
            invokeStartFail(callback, device, BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
            return;
        }
        Log.i(
                TAG,
                ("startDistanceMeasurement(): device=" + device)
                        + (", method=" + params.getMethodId()));

        int status = checkLinkRequirements(device);
        if (status != BluetoothStatusCodes.SUCCESS) {
            invokeStartFail(callback, device, status);
            return;
        }

        String address = mAdapterService.getIdentityAddress(device.getAddress());
        if (address == null) {
            address = device.getAddress();
        }
        logd(
                "startDistanceMeasurement(): Get identityAddress: "
                        + (device + " => " + toAnonymizedAddress(address)));

        int interval = getIntervalValue(params.getFrequency(), params.getMethodId());
        if (interval == -1) {
            invokeStartFail(callback, device, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
            return;
        }

        DistanceMeasurementTracker tracker =
                new DistanceMeasurementTracker(
                        this, appUid, params, address, uuid, interval, callback);

        switch (params.getMethodId()) {
            case DISTANCE_MEASUREMENT_METHOD_AUTO, DISTANCE_MEASUREMENT_METHOD_RSSI ->
                    startRssiTracker(tracker);
            case DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING -> {
                if (!mHasChannelSoundingFeature
                        || !mAdapterService.isLeChannelSoundingSupported()) {
                    Log.e(TAG, "Channel Sounding is not supported");
                    invokeStartFail(callback, device, BluetoothStatusCodes.FEATURE_NOT_SUPPORTED);
                    return;
                }
                if (mAdapterService.getBondState(device) != BluetoothDevice.BOND_BONDED) {
                    Log.e(TAG, "startDistanceMeasurement(): Target device is not bonded");
                    invokeStartFail(callback, device, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
                    return;
                }
                startCsTracker(tracker);
            }
            default -> invokeStartFail(callback, device, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
        }
    }

    private int checkLinkRequirements(BluetoothDevice device) {
        if (!Flags.enforceSecurityForRanging()) {
            if (!mAdapterService.isConnected(device)) {
                Log.e(TAG, "checkLinkRequirements(): Device " + device + " is not connected");
                return BluetoothStatusCodes.ERROR_NO_LE_CONNECTION;
            }
            return BluetoothStatusCodes.SUCCESS;
        }

        if (mAdapterService.getBondState(device) != BluetoothDevice.BOND_BONDED) {
            Log.e(TAG, "checkLinkRequirements(): " + device + " is not bonded");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        BondStatus bondStatus = mAdapterService.getBondStatus(device, BluetoothDevice.TRANSPORT_LE);
        // BondStatus is null when pairing algorithm is unknown. That can happen for the devices
        // which were bonded before caching of pairing algorithm was introduced in Android Bluetooth
        if (bondStatus != null
                && bondStatus.getPairingAlgorithm() != BluetoothDevice.PAIRING_ALGORITHM_SC) {
            Log.e(TAG, "checkLinkRequirements(): " + device + " is not Secure Connections bonded");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        EncryptionStatus encryptionStatus =
                mAdapterService.getEncryptionStatus(device, BluetoothDevice.TRANSPORT_LE);
        if (encryptionStatus == null) {
            Log.e(TAG, "checkLinkRequirements(): " + device + " is not connected over LE");
            return BluetoothStatusCodes.ERROR_NO_LE_CONNECTION;
        }

        if (encryptionStatus.getAlgorithm() != BluetoothDevice.ENCRYPTION_ALGORITHM_AES) {
            Log.e(TAG, "checkLinkRequirements(): " + device + " is not encrypted with AES");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        if (encryptionStatus.getKeySize() < 16) {
            Log.e(TAG, "checkLinkRequirements(): " + device + " encryption key size is too small");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    private void startRssiTracker(DistanceMeasurementTracker tracker) {
        mRssiTrackers.putIfAbsent(tracker.mIdentityAddress, new ArraySet<>());
        Set<DistanceMeasurementTracker> set = mRssiTrackers.get(tracker.mIdentityAddress);
        if (!set.add(tracker)) {
            Log.w(TAG, "startRssiTracker(): Already registered");
            return;
        }
        mNativeInterface.startDistanceMeasurement(
                tracker.mAppUid,
                tracker.mIdentityAddress,
                tracker.mInterval,
                DISTANCE_MEASUREMENT_METHOD_RSSI,
                tracker.mSightType,
                tracker.mLocationType);
    }

    private void startCsTracker(DistanceMeasurementTracker tracker) {
        mCsTrackers.putIfAbsent(tracker.mIdentityAddress, new ArraySet<>());
        Set<DistanceMeasurementTracker> set = mCsTrackers.get(tracker.mIdentityAddress);
        if (!set.add(tracker)) {
            Log.w(TAG, "startCsTracker(): Already registered");
            return;
        }
        mNativeInterface.startDistanceMeasurement(
                tracker.mAppUid,
                tracker.mIdentityAddress,
                tracker.mInterval,
                DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
                tracker.mSightType,
                tracker.mLocationType);
    }

    int stopDistanceMeasurement(UUID uuid, BluetoothDevice device, int method, boolean timeout) {
        enforceThread();

        Log.i(
                TAG,
                ("stopDistanceMeasurement(): device=" + toAnonymizedAddress(device.getAddress()))
                        + (", method=" + method + ", timeout=" + timeout));
        String address = mAdapterService.getIdentityAddress(device.getAddress());
        if (address == null) {
            address = device.getAddress();
        }
        logd(
                ("Get identityAddress: " + toAnonymizedAddress(device.getAddress()))
                        + (" => " + toAnonymizedAddress(address)));

        return switch (method) {
            case DISTANCE_MEASUREMENT_METHOD_AUTO, DISTANCE_MEASUREMENT_METHOD_RSSI ->
                    stopRssiTracker(uuid, address, timeout);
            case DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING ->
                    stopCsTracker(uuid, address, timeout);
            default -> {
                Log.w(TAG, "stopDistanceMeasurement(): with invalid method=" + method);
                yield BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
            }
        };
    }

    int getChannelSoundingMaxSupportedSecurityLevel(BluetoothDevice remoteDevice) {
        enforceThread();

        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            return ChannelSoundingParams.CS_SECURITY_LEVEL_ONE;
        }
        return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN;
    }

    int getLocalChannelSoundingMaxSupportedSecurityLevel() {
        enforceThread();

        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            return ChannelSoundingParams.CS_SECURITY_LEVEL_ONE;
        }
        return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN;
    }

    Set<Integer> getChannelSoundingSupportedSecurityLevels() {
        enforceThread();

        // TODO(b/378685103): get it from the HAL when level 4 is supported and HAL v2 is available.
        if (mHasChannelSoundingFeature && mAdapterService.isLeChannelSoundingSupported()) {
            return Set.of(ChannelSoundingParams.CS_SECURITY_LEVEL_ONE);
        }
        throw new UnsupportedOperationException("Channel Sounding is not supported");
    }

    private int stopRssiTracker(UUID uuid, String identityAddress, boolean timeout) {
        Set<DistanceMeasurementTracker> set = mRssiTrackers.get(identityAddress);
        if (set == null) {
            Log.w(TAG, "stopRssiTracker(): Can't find rssi tracker");
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
            logd("stopRssiTracker(): No rssi tracker");
            mRssiTrackers.remove(identityAddress);
            mNativeInterface.stopDistanceMeasurement(
                    identityAddress, DISTANCE_MEASUREMENT_METHOD_RSSI);
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    private int stopCsTracker(UUID uuid, String identityAddress, boolean timeout) {
        Set<DistanceMeasurementTracker> set = mCsTrackers.get(identityAddress);
        if (set == null) {
            Log.w(TAG, "stopCsTracker(): Can't find CS tracker");
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
            logd("stopCsTracker(): No CS tracker exists; stop CS");
            mCsTrackers.remove(identityAddress);
            mNativeInterface.stopDistanceMeasurement(
                    identityAddress, DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING);
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
            case DISTANCE_MEASUREMENT_METHOD_AUTO, DISTANCE_MEASUREMENT_METHOD_RSSI -> {
                return switch (frequency) {
                    case DistanceMeasurementParams.REPORT_FREQUENCY_LOW ->
                            RSSI_LOW_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_MEDIUM ->
                            RSSI_MEDIUM_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_HIGH ->
                            RSSI_HIGH_FREQUENCY_INTERVAL_MS;
                    default -> -1;
                };
            }
            case DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING -> {
                return switch (frequency) {
                    case DistanceMeasurementParams.REPORT_FREQUENCY_LOW ->
                            CS_LOW_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_MEDIUM ->
                            CS_MEDIUM_FREQUENCY_INTERVAL_MS;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_HIGH ->
                            CS_HIGH_FREQUENCY_INTERVAL_MS;
                    default -> -1;
                };
            }
            default ->
                    Log.w(
                            TAG,
                            "getFrequencyValue fail frequency:" + frequency + ", method:" + method);
        }
        return -1;
    }

    void onDistanceMeasurementStarted(String address, int method) {
        enforceThread();

        logd(
                ("onDistanceMeasurementStarted(): address=" + toAnonymizedAddress(address))
                        + (", method=" + method));
        switch (method) {
            case DISTANCE_MEASUREMENT_METHOD_RSSI -> handleRssiStarted(address);
            case DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING -> handleCsStarted(address);
            default -> Log.w(TAG, "onDistanceMeasurementStarted(): Invalid method=" + method);
        }
    }

    private void handleRssiStarted(String address) {
        Set<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "handleRssiStarted(): Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            try {
                if (!tracker.mStarted) {
                    tracker.mStarted = true;
                    tracker.mCallback.onStarted(tracker.mDevice);
                    tracker.startTimer(mHandler.getLooper());
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    private void handleCsStarted(String address) {
        Set<DistanceMeasurementTracker> set = mCsTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "handleCsStarted(): Can't find CS tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            try {
                if (!tracker.mStarted) {
                    tracker.mStarted = true;
                    tracker.mCallback.onStarted(tracker.mDevice);
                    tracker.startTimer(mHandler.getLooper());
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    void onDistanceMeasurementStopped(String address, int reason, int method) {
        enforceThread();
        logd(
                ("onDistanceMeasurementStopped(): address=" + toAnonymizedAddress(address))
                        + (", reason=" + reason)
                        + (", method=" + method));
        switch (method) {
            case DISTANCE_MEASUREMENT_METHOD_RSSI -> handleRssiStopped(address, reason);
            case DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING -> handleCsStopped(address, reason);
            default -> Log.w(TAG, "onDistanceMeasurementStopped: invalid method " + method);
        }
    }

    private void handleRssiStopped(String address, int reason) {
        Set<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "handleRssiStopped(): Can't find rssi tracker");
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

    private void handleCsStopped(String address, int reason) {
        Set<DistanceMeasurementTracker> set = mCsTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "handleCsStopped(): Can't find CS tracker");
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
            int remoteTxPowerDbm,
            int rssiDbm,
            int confidenceLevel,
            double delaySpreadMeters,
            int detectedAttackLevel,
            double velocityMetersPerSecond,
            int method) {
        enforceThread();
        logd(
                ("onDistanceMeasurementResult(): " + toAnonymizedAddress(address))
                        + (", centimeter=" + centimeter)
                        + (", confidenceLevel=" + confidenceLevel));
        DistanceMeasurementResult.Builder builder =
                new DistanceMeasurementResult.Builder(centimeter / 100.0, errorCentimeter / 100.0)
                        .setMeasurementTimestampNanos(elapsedRealtimeNanos);

        if (Flags.includePowerAndRssiInDistanceMeasurementResult()) {
            builder.setRemoteTxPowerDbm(remoteTxPowerDbm);
            builder.setRssiDbm(rssiDbm);
        }

        switch (method) {
            case DISTANCE_MEASUREMENT_METHOD_RSSI -> handleRssiResult(address, builder.build());
            case DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING -> {
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
            }
            default -> Log.w(TAG, "onDistanceMeasurementResult: invalid method " + method);
        }
    }

    private void handleRssiResult(String address, DistanceMeasurementResult result) {
        Set<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "handleRssiResult(): Can't find rssi tracker");
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

    private void handleCsResult(String address, DistanceMeasurementResult result) {
        Set<DistanceMeasurementTracker> set = mCsTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "handleCsResult(): Can't find cs tracker");
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

    interface GetResultTask<T> {
        T getResult();
    }

    void postOnDistanceMeasurementThread(Runnable r) {
        mHandler.post(r);
    }

    <T> T runOnDistanceMeasurementThreadAndWaitForResult(GetResultTask<T> task) throws Throwable {
        if (!mHandler.getLooper().isCurrentThread()) {
            CompletableFuture<T> result = new CompletableFuture<>();
            mHandler.post(
                    () -> {
                        try {
                            result.complete(task.getResult());
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    });

            try {
                return result.get(THREAD_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | TimeoutException e) {
                Log.w(TAG, "Exception happened", e);
            } catch (ExecutionException e) {
                // Propagate exception to the caller
                throw e.getCause();
            }
            return null;
        } else {
            return task.getResult();
        }
    }

    private void forceRunSyncOnDistanceMeasurementThread(Runnable r) {
        if (Util.isInstrumentationTestMode()) {
            r.run();
            return;
        }

        final CompletableFuture<Void> future = new CompletableFuture<>();
        mHandler.postAtFrontOfQueue(
                () -> {
                    r.run();
                    future.complete(null);
                });
        try {
            future.get(RUN_SYNC_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            Log.w(TAG, "Unable to complete sync task: " + e);
        }
    }

    private void enforceThread() {
        if (Util.isInstrumentationTestMode()) return;

        if (!mHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Not on distance measurement thread");
        }
    }

    private static void logd(String msg) {
        Log.d(TAG, msg);
    }
}
