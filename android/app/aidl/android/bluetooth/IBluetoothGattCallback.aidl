/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattService;

/** Callback definitions for interacting with GATT */
@JavaPassthrough(annotation="@android.annotation.Hide")
oneway interface IBluetoothGattCallback {
    void onClientRegistered(in int status);
    void onClientConnectionState(in int status,
                                 in boolean connected, in BluetoothDevice device);
    void onPhyUpdate(in BluetoothDevice device, in int txPhy, in int rxPhy, in int status);
    void onPhyRead(in BluetoothDevice device, in int txPhy, in int rxPhy, in int status);
    void onSearchComplete(in BluetoothDevice device, in List<BluetoothGattService> services, in int status);
    void onCharacteristicRead(in BluetoothDevice device, in int status, in int handle, in byte[] value);
    void onCharacteristicWrite(in BluetoothDevice device, in int status, in int handle, in byte[] value);
    void onExecuteWrite(in BluetoothDevice device, in int status);
    void onDescriptorRead(in BluetoothDevice device, in int status, in int handle, in byte[] value);
    void onDescriptorWrite(in BluetoothDevice device, in int status, in int handle, in byte[] value);
    void onNotify(in BluetoothDevice device, in int handle, in byte[] value);
    void onReadRemoteRssi(in BluetoothDevice device, in int rssi, in int status);
    void onConfigureMTU(in BluetoothDevice device, in int mtu, in int status);
    void onConnectionUpdated(in BluetoothDevice device, in int interval, in int latency,
                             in int timeout, in int status);
    void onServiceChanged(in BluetoothDevice device);
    void onSubrateChange(in BluetoothDevice device, in int subrateMode, in int status);
    void onCharacteristicsUnoffloaded(in BluetoothDevice device, in int sessionId, in int status);
}
