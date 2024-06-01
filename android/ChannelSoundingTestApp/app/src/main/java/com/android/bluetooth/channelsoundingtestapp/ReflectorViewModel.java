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
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** ViewModel for the Reflector. */
@SuppressLint("MissingPermission")
public class ReflectorViewModel extends AndroidViewModel {

    private final BluetoothAdapter mBluetoothAdapter;
    private MutableLiveData<Boolean> mIsAdvertising = new MutableLiveData<>(false);
    private MutableLiveData<String> mLogText = new MutableLiveData<>();
    private AdvertisingSet mAdvertisingSet = null;

    /** Constructor */
    public ReflectorViewModel(@NonNull Application application) {
        super(application);
        BluetoothManager bluetoothManager = application.getSystemService(BluetoothManager.class);
        mBluetoothAdapter = bluetoothManager.getAdapter();
    }

    LiveData<Boolean> getIsAdvertising() {
        return mIsAdvertising;
    }

    LiveData<String> getLogText() {
        return mLogText;
    }

    void toggleAdvertising() {
        if (mIsAdvertising.getValue()) {
            stopAdvertising();
        } else {
            startConnectableAdvertising();
        }
    }

    private void startConnectableAdvertising() {
        if (mAdvertisingSet != null) {
            mAdvertisingSet.enableAdvertising(!mIsAdvertising.getValue(), 0, 0);
            return;
        }

        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        AdvertisingSetParameters parameters =
                (new AdvertisingSetParameters.Builder())
                        .setLegacyMode(false) // True by default, but set here as a reminder.
                        .setConnectable(true)
                        .setScannable(false)
                        .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                        .build();

        AdvertiseData response = (new AdvertiseData.Builder()).setIncludeDeviceName(true).build();

        AdvertisingSetCallback callback =
                new AdvertisingSetCallback() {
                    @Override
                    public void onAdvertisingSetStarted(
                            AdvertisingSet advertisingSet, int txPower, int status) {
                        printLog(
                                "onAdvertisingSetStarted(): txPower:"
                                        + txPower
                                        + " , status: "
                                        + status);
                        mAdvertisingSet = advertisingSet;
                        if (status == 0) {
                            mIsAdvertising.setValue(true);
                        }
                    }

                    @Override
                    public void onAdvertisingEnabled(
                            AdvertisingSet advertisingSet, boolean enable, int status) {
                        printLog("enable: " + enable + ", status:" + status);
                        if (enable == false) {
                            mIsAdvertising.setValue(false);
                        } else {
                            mIsAdvertising.setValue(true);
                        }
                    }

                    @Override
                    public void onAdvertisingDataSet(AdvertisingSet advertisingSet, int status) {
                        printLog("onAdvertisingDataSet() :status:" + status);
                    }

                    @Override
                    public void onScanResponseDataSet(AdvertisingSet advertisingSet, int status) {
                        printLog("onScanResponseDataSet(): status:" + status);
                    }

                    @Override
                    public void onAdvertisingSetStopped(AdvertisingSet advertisingSet) {
                        printLog("onAdvertisingSetStopped():");
                    }
                };

        printLog("Start connectable advertising");
        advertiser.startAdvertisingSet(parameters, response, null, null, null, 0, 0, callback);
    }

    private void stopAdvertising() {
        if (mAdvertisingSet != null) {
            printLog("advertising is stopped.");
            mAdvertisingSet.enableAdvertising(false, 0, 0);
        }
    }

    private void printLog(@NonNull String logMsg) {
        mLogText.setValue("BT Log: " + logMsg);
    }
}
