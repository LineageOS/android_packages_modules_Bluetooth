/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.channelsoundingtestapp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.DistanceMeasurementManager;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.DistanceMeasurementResult;
import android.bluetooth.le.DistanceMeasurementSession;
import android.content.Context;
import android.util.Pair;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class DistanceMeasurementInitiator {

    enum Freq {
        HIGH(DistanceMeasurementParams.REPORT_FREQUENCY_HIGH),
        MEDIUM(DistanceMeasurementParams.REPORT_FREQUENCY_MEDIUM),
        LOW(DistanceMeasurementParams.REPORT_FREQUENCY_LOW);
        private final int freq;

        Freq(int freq) {
            this.freq = freq;
        }

        int getFreq() {
            return freq;
        }

        @Override
        public String toString() {
            return name();
        }

        public static Freq fromName(String name) {
            try {
                return Freq.valueOf(name);
            } catch (IllegalArgumentException e) {
                return MEDIUM;
            }
        }
    }

    private static final List<Pair<Integer, String>> mDistanceMeasurementMethodMapping =
            List.of(
                    new Pair<>(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO, "AUTO"),
                    new Pair<>(DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI, "RSSI"),
                    new Pair<>(
                            DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING,
                            "Channel Sounding"));

    private final BluetoothAdapter mBluetoothAdapter;
    private final LoggingListener mLoggingListener;

    private final Context mApplicationContext;
    private final Executor mBtExecutor;
    private final BtDistanceMeasurementCallback mBtDistanceMeasurementCallback;
    @Nullable private DistanceMeasurementSession mSession = null;
    @Nullable private BluetoothDevice mTargetDevice = null;

    DistanceMeasurementInitiator(
            Context applicationContext,
            BtDistanceMeasurementCallback btDistanceMeasurementCallback,
            LoggingListener loggingListener) {
        mApplicationContext = applicationContext;
        mBtDistanceMeasurementCallback = btDistanceMeasurementCallback;
        mLoggingListener = loggingListener;

        BluetoothManager bluetoothManager =
                mApplicationContext.getSystemService(BluetoothManager.class);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        mBtExecutor = Executors.newSingleThreadExecutor();
    }

    void setTargetDevice(BluetoothDevice targetDevice) {
        mTargetDevice = targetDevice;
    }

    private void printLog(String log) {
        mLoggingListener.onLog(log);
    }

    private String getDistanceMeasurementMethodName(int methodId) {
        for (Pair<Integer, String> methodMapping : mDistanceMeasurementMethodMapping) {
            if (methodMapping.first == methodId) {
                return methodMapping.second;
            }
        }
        throw new IllegalArgumentException("unknown distance measurement method id" + methodId);
    }

    private int getDistanceMeasurementMethodId(String methodName) {
        for (Pair<Integer, String> methodMapping : mDistanceMeasurementMethodMapping) {
            if (methodMapping.second.equals(methodName)) {
                return methodMapping.first;
            }
        }
        throw new IllegalArgumentException("unknown distance measurement method name" + methodName);
    }

    @SuppressLint("MissingPermission") // permissions are checked upfront
    List<String> getDistanceMeasurementMethods() {
        List<String> methods = new ArrayList<>();
        if (mBluetoothAdapter.isDistanceMeasurementSupported()
                != BluetoothStatusCodes.FEATURE_SUPPORTED) {
            printLog("No distance measurement is supported");
            return methods;
        }
        DistanceMeasurementManager distanceMeasurementManager =
                mBluetoothAdapter.getDistanceMeasurementManager();
        List<DistanceMeasurementMethod> list = distanceMeasurementManager.getSupportedMethods();

        StringBuilder dbgMessage = new StringBuilder("getDistanceMeasurementMethods: ");
        for (DistanceMeasurementMethod method : list) {
            String methodName = getDistanceMeasurementMethodName((int) method.getId());
            dbgMessage.append(methodName).append(", ");
            methods.add(methodName);
        }
        printLog(dbgMessage.toString());
        return methods;
    }

    List<String> getMeasurementFreqs() {
        return List.of(Freq.MEDIUM.toString(), Freq.HIGH.toString(), Freq.LOW.toString());
    }

    List<String> getMeasureDurationsInSeconds() {
        return List.of("3600", "300", "60", "10");
    }

    @SuppressLint("MissingPermission") // permissions are checked upfront
    void startDistanceMeasurement(
            String distanceMeasurementMethodName, String selectedFreq, int duration) {

        if (mTargetDevice == null) {
            printLog("do Gatt connect first");
            return;
        }

        printLog("start CS with device: " + mTargetDevice.getName());

        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(mTargetDevice)
                        .setDurationSeconds(duration)
                        .setFrequency(Freq.fromName(selectedFreq).getFreq())
                        .setMethodId(getDistanceMeasurementMethodId(distanceMeasurementMethodName))
                        .build();
        DistanceMeasurementManager distanceMeasurementManager =
                mBluetoothAdapter.getDistanceMeasurementManager();
        distanceMeasurementManager.startMeasurementSession(params, mBtExecutor, mTestcallback);
    }

    void stopDistanceMeasurement() {
        if (mSession == null) {
            return;
        }
        mSession.stopSession();
        mSession = null;
    }

    private DistanceMeasurementSession.Callback mTestcallback =
            new DistanceMeasurementSession.Callback() {
                public void onStarted(DistanceMeasurementSession session) {
                    printLog("DistanceMeasurement onStarted ! ");
                    mSession = session;
                    mBtDistanceMeasurementCallback.onStartSuccess();
                }

                public void onStartFail(int reason) {
                    printLog("DistanceMeasurement onStartFail ! reason " + reason);
                    mBtDistanceMeasurementCallback.onStartFail();
                }

                public void onStopped(DistanceMeasurementSession session, int reason) {
                    printLog("DistanceMeasurement onStopped ! reason " + reason);
                    mBtDistanceMeasurementCallback.onStop();
                    mSession = null;
                }

                public void onResult(BluetoothDevice device, DistanceMeasurementResult result) {
                    printLog(
                            "DistanceMeasurement onResult ! "
                                    + result.getResultMeters()
                                    + ", "
                                    + result.getErrorMeters());
                    mBtDistanceMeasurementCallback.onDistanceResult(result.getResultMeters());
                }
            };

    interface BtDistanceMeasurementCallback {

        void onStartSuccess();

        void onStartFail();

        void onStop();

        void onDistanceResult(double distanceMeters);
    }
}
