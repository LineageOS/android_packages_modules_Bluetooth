/*
 * Copyright (C) 2017 The Android Open Source Project
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

/** Callback definitions for interacting with BLE / GATT */
@JavaPassthrough(annotation="@android.annotation.Hide")
oneway interface IBluetoothGattServerCallback {
    void onServerRegistered(in int status);
    void onServerConnectionState(in int status,
                                 in boolean connected, in BluetoothDevice device);
    void onServiceAdded(in int status, in BluetoothGattService service);
    void onCharacteristicReadRequest(in BluetoothDevice device, in int transId, in int offset,
                                     in boolean isLong, in int handle);
    void onDescriptorReadRequest(in BluetoothDevice device, in int transId,
                                     in int offset, in boolean isLong,
                                     in int handle);
    void onCharacteristicWriteRequest(in BluetoothDevice device, in int transId, in int offset,
                                     in int length, in boolean isPrep, in boolean needRsp,
                                     in int handle, in byte[] value);
    void onDescriptorWriteRequest(in BluetoothDevice device, in int transId, in int offset,
                                     in int length, in boolean isPrep, in boolean needRsp,
                                     in int handle, in byte[] value);
    void onExecuteWrite(in BluetoothDevice device, in int transId, in boolean execWrite);
    void onNotificationSent(in BluetoothDevice device, in int status);
    void onMtuChanged(in BluetoothDevice device, in int mtu);
    void onPhyUpdate(in BluetoothDevice device, in int txPhy, in int rxPhy, in int status);
    void onPhyRead(in BluetoothDevice device, in int txPhy, in int rxPhy, in int status);
    void onConnectionUpdated(in BluetoothDevice device, in int interval, in int latency,
                             in int timeout, in int status);
    void onSubrateChange(in BluetoothDevice device, in int subrateMode, in int status);
    void onCharacteristicsUnoffloaded(in BluetoothDevice device, in int sessionId, in int status);
}
