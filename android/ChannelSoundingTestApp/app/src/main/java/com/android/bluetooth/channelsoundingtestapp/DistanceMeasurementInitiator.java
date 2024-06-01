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
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
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
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class DistanceMeasurementInitiator {

    private static final int DISTANCE_MEASUREMENT_DURATION_SEC = 3600;
    private static final int GATT_MTU_SIZE = 512;
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
    private String mTargetBtAddress = "";
    @Nullable private BluetoothGatt mBluetoothGatt = null;
    @Nullable private DistanceMeasurementSession mSession = null;

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

    void setTargetBtAddress(String btAddress) {
        mTargetBtAddress = btAddress;
    }

    @SuppressLint("MissingPermission") // permissions are checked upfront
    List<String> updatePairedDevice() {
        List<String> arrayList = new ArrayList<>();
        Set<BluetoothDevice> bonded_devices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bonded_devices) {
            arrayList.add(device.getAddress());
        }
        printLog("Num of paired Devices: " + arrayList.size());
        return arrayList;
    }

    @SuppressLint("MissingPermission") // permissions are checked upfront
    void connectGatt() {
        if (mTargetBtAddress == null) {
            printLog("A paired device must be selected first.");
            return;
        }
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetBtAddress);
        BluetoothGattCallback gattCallback =
                new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(
                            BluetoothGatt gatt, int status, int newState) {
                        printLog(
                                "onConnectionStateChange status:"
                                        + status
                                        + ", newState:"
                                        + newState);
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            printLog(gatt.getDevice().getName() + " is connected");
                            gatt.requestMtu(GATT_MTU_SIZE);
                            mBluetoothGatt = gatt;
                            mBtDistanceMeasurementCallback.onGattConnected();
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            printLog("disconnected from " + gatt.getDevice().getName());
                            mBtDistanceMeasurementCallback.onGattDisconnected();
                            mBluetoothGatt.close();
                            mBluetoothGatt = null;
                        }
                    }

                    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            printLog("MTU changed to: " + mtu);
                        } else {
                            printLog("MTU change failed: " + status);
                        }
                    }
                };
        printLog("Connect gatt to " + device.getAddress());

        device.connectGatt(mApplicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission") // permissions are checked upfront
    void disconnectGatt() {
        if (mBluetoothGatt != null) {
            printLog("disconnect from " + mBluetoothGatt.getDevice().getName());
            mBluetoothGatt.disconnect();
        }
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

    @SuppressLint("MissingPermission") // permissions are checked upfront
    void startDistanceMeasurement(String distanceMeasurementMethodName) {

        if (mTargetBtAddress == null) {
            printLog("pair and select a valid address.");
            return;
        }

        printLog("start CS with address: " + mTargetBtAddress);

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetBtAddress);
        DistanceMeasurementParams params =
                new DistanceMeasurementParams.Builder(device)
                        .setDurationSeconds(DISTANCE_MEASUREMENT_DURATION_SEC)
                        .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
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

        void onGattConnected();

        void onGattDisconnected();
    }
}
