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

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.bluetooth.channelsoundingtestapp.Constants.GattState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The ViewModel for the BLE GATT connection. */
@SuppressLint("MissingPermission") // permissions are checked upfront
public class BleConnectionViewModel extends AndroidViewModel {
    private static final int GATT_MTU_SIZE = 512;

    private final BluetoothAdapter mBluetoothAdapter;
    private final BluetoothManager mBluetoothManager;
    @Nullable private BluetoothGatt mBluetoothGatt = null;
    private MutableLiveData<Boolean> mIsAdvertising = new MutableLiveData<>(false);
    private MutableLiveData<String> mLogText = new MutableLiveData<>();
    private MutableLiveData<BluetoothDevice> mTargetDevice = new MutableLiveData<>();
    // scanner
    private final MutableLiveData<List<String>> mBondedBtDeviceAddresses = new MutableLiveData<>();
    private final MutableLiveData<GattState> mGattState =
            new MutableLiveData<>(GattState.DISCONNECTED);
    private String mTargetBtAddress = "";

    private GattState mExpectedGattState = GattState.DISCONNECTED;

    /** Constructor */
    public BleConnectionViewModel(@NonNull Application application) {
        super(application);
        mBluetoothManager = application.getSystemService(BluetoothManager.class);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
    }

    LiveData<Boolean> getIsAdvertising() {
        return mIsAdvertising;
    }

    LiveData<String> getLogText() {
        return mLogText;
    }

    LiveData<GattState> getGattState() {
        return mGattState;
    }

    LiveData<List<String>> getBondedBtDeviceAddresses() {
        return mBondedBtDeviceAddresses;
    }

    LiveData<BluetoothDevice> getTargetDevice() {
        return mTargetDevice;
    }

    void toggleAdvertising() {
        if (mIsAdvertising.getValue()) {
            stopAdvertising();
        } else {
            startConnectableAdvertising();
        }
    }

    AdvertisingSetCallback mAdvertisingSetCallback =
            new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(
                        AdvertisingSet advertisingSet, int txPower, int status) {
                    printLog(
                            "onAdvertisingSetStarted(): txPower:"
                                    + txPower
                                    + " , status: "
                                    + status);
                    if (status == 0) {
                        mIsAdvertising.postValue(true);
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
                    mIsAdvertising.postValue(false);
                }
            };

    private void startConnectableAdvertising() {
        if (mIsAdvertising.getValue()) {
            return;
        }
        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        AdvertisingSetParameters parameters =
                new AdvertisingSetParameters.Builder()
                        .setLegacyMode(false) // True by default, but set here as a reminder.
                        .setConnectable(true)
                        .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                        .build();

        BluetoothGattServerCallback gattServerCallback =
                new BluetoothGattServerCallback() {
                    @Override
                    public void onConnectionStateChange(
                            BluetoothDevice device, int status, int newState) {
                        super.onConnectionStateChange(device, status, newState);
                        if (newState == STATE_CONNECTED) {
                            printLog("Device connected: " + device.getName());
                            mTargetDevice.postValue(device);
                        } else if (newState == STATE_DISCONNECTED) {
                            printLog("Device disconnected: " + device.getName());
                            mTargetDevice.postValue(null);
                        }
                    }
                };

        BluetoothGattServer bluetoothGattServer =
                mBluetoothManager.openGattServer(
                        getApplication().getApplicationContext(), gattServerCallback);
        AdvertiseData advertiseData =
                new AdvertiseData.Builder()
                        .setIncludeDeviceName(true)
                        .addServiceUuid(new ParcelUuid(Constants.CS_TEST_SERVICE_UUID))
                        .build();

        printLog("Start connectable advertising");

        advertiser.startAdvertisingSet(
                parameters, advertiseData, null, null, null, 0, 0, mAdvertisingSetCallback);
    }

    private void stopAdvertising() {
        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        advertiser.stopAdvertisingSet(mAdvertisingSetCallback);
        printLog("stop advertising");
    }

    void updateBondedDevices() {
        List<String> addresses = new ArrayList<>();
        Set<BluetoothDevice> bonded_devices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bonded_devices) {
            addresses.add(device.getAddress());
        }
        mBondedBtDeviceAddresses.setValue(addresses);
    }

    void setCsTargetAddress(String btAddress) {
        printLog("set target address: " + btAddress);
        mTargetBtAddress = btAddress;
    }

    void toggleGattConnection() {
        if (mGattState.getValue() == GattState.DISCONNECTED) {
            if (TextUtils.isEmpty(mTargetBtAddress)) {
                printLog("Pair and select a target device first!");
                return;
            }
            connectGatt();
        } else if (mGattState.getValue() == GattState.CONNECTED_DIRECT) {
            disconnectGatt();
        }
    }

    private BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    printLog("onConnectionStateChange status:" + status + ", newState:" + newState);
                    if (newState == STATE_CONNECTED) {
                        printLog(gatt.getDevice().getName() + " is connected");
                        gatt.requestMtu(GATT_MTU_SIZE);
                        mBluetoothGatt = gatt;
                        mGattState.postValue(mExpectedGattState);
                        mTargetDevice.postValue(gatt.getDevice());
                    } else if (newState == STATE_DISCONNECTED) {
                        printLog("disconnected from " + gatt.getDevice().getName());
                        mExpectedGattState = GattState.DISCONNECTED;
                        mGattState.postValue(mExpectedGattState);
                        mBluetoothGatt.close();
                        mBluetoothGatt = null;
                        mTargetDevice.postValue(null);
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

    private void connectGatt() {
        BluetoothDevice btDevice = mBluetoothAdapter.getRemoteDevice(mTargetBtAddress);
        printLog("Connect gatt to " + btDevice.getName());
        mExpectedGattState = GattState.CONNECTED_DIRECT;
        btDevice.connectGatt(
                getApplication().getApplicationContext(),
                false,
                mGattCallback,
                BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnectGatt() {
        if (mBluetoothGatt != null) {
            printLog("disconnect from " + mBluetoothGatt.getDevice().getName());
            mBluetoothGatt.disconnect();
        }
    }

    void toggleScanConnect() {
        if (mGattState.getValue() == GattState.DISCONNECTED) {
            connectGattByScanning();
        } else if (mGattState.getValue() == GattState.SCANNING) {
            stopScanning();
        } else if (mGattState.getValue() == GattState.CONNECTED_SCAN) {
            disconnectGatt();
        }
    }

    private ScanCallback mScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    List<ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
                    if (serviceUuids != null) {
                        for (ParcelUuid parcelUuid : serviceUuids) {
                            BluetoothDevice btDevice = result.getDevice();
                            printLog("found device - " + btDevice.getName());
                            if (parcelUuid.getUuid().equals(Constants.CS_TEST_SERVICE_UUID)) {
                                mExpectedGattState = GattState.CONNECTED_SCAN;
                                stopScanning();
                                printLog("connect GATT to: " + btDevice.getName());
                                // Connect to the GATT server
                                mBluetoothGatt =
                                        btDevice.connectGatt(
                                                getApplication().getApplicationContext(),
                                                false,
                                                mGattCallback,
                                                BluetoothDevice.TRANSPORT_LE);
                            }
                        }
                    }
                }
            };

    private void connectGattByScanning() {
        BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceUuid(
                                new ParcelUuid(
                                        Constants.CS_TEST_SERVICE_UUID)) // Filter by service UUID
                        .build();
        filters.add(filter);

        ScanSettings settings =
                new ScanSettings.Builder()
                        .setLegacy(false)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build();

        printLog("start scanning...");

        // Start scanning
        bluetoothLeScanner.startScan(filters, settings, mScanCallback);
        mExpectedGattState = GattState.SCANNING;
        mGattState.setValue(mExpectedGattState);
    }

    private void stopScanning() {
        BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(mScanCallback);
            if (mExpectedGattState == GattState.SCANNING) {
                mExpectedGattState = GattState.DISCONNECTED;
                mGattState.setValue(mExpectedGattState);
            }
        }
    }

    private void printLog(@NonNull String logMsg) {
        mLogText.postValue("BT Log: " + logMsg);
    }
}
