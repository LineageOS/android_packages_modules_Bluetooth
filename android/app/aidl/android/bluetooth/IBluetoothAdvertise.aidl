/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.bluetooth.IBluetoothGattServerCallback;
import android.content.AttributionSource;

/** Binder method for BLE advertising interaction */
@JavaPassthrough(annotation="@android.annotation.Hide")
interface IBluetoothAdvertise {
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_ADVERTISE,android.Manifest.permission.BLUETOOTH_PRIVILEGED}, conditional=true)")
    void startAdvertisingSet(in AdvertisingSetParameters parameters, in AdvertiseData advertiseData,
                                in AdvertiseData scanResponse, in PeriodicAdvertisingParameters periodicParameters,
                                in AdvertiseData periodicData, in int duration, in int maxExtAdvEvents, IBluetoothGattServerCallback gattServerCallback,
                                in IAdvertisingSetCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void stopAdvertisingSet(in IAdvertisingSetCallback callback, in AttributionSource attributionSource);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_ADVERTISE,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void getOwnAddress(in int advertiserId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void enableAdvertisingSet(in int advertiserId, in boolean enable, in int duration, in int maxExtAdvEvents, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void setAdvertisingData(in int advertiserId, in AdvertiseData data, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void setScanResponseData(in int advertiserId, in AdvertiseData data, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_ADVERTISE,android.Manifest.permission.BLUETOOTH_PRIVILEGED}, conditional=true)")
    void setAdvertisingParameters(in int advertiserId, in AdvertisingSetParameters parameters, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void setPeriodicAdvertisingParameters(in int advertiserId, in PeriodicAdvertisingParameters parameters, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void setPeriodicAdvertisingData(in int advertiserId, in AdvertiseData data, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)")
    void setPeriodicAdvertisingEnable(in int advertiserId, in boolean enable, in AttributionSource attributionSource);
}
