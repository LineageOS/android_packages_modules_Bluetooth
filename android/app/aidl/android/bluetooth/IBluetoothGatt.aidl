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
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.GattOffloadSession;
import android.content.AttributionSource;
import android.os.ParcelUuid;

/** Binder method for GATT interaction */
@JavaPassthrough(annotation="@android.annotation.Hide")
interface IBluetoothGatt {
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states, in AttributionSource attributionSource);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void registerClient(in ParcelUuid appId, in IBluetoothGattCallback callback, boolean eatt_support, in int transport, in AttributionSource attributionSource);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void unregisterClient(in IBluetoothGattCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void clientConnect(in IBluetoothGattCallback callback, in BluetoothDevice device, in int addressType, in boolean isDirect, in int transport, in boolean opportunistic, in boolean isAutoMtuEnabled, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void clientDisconnect(in IBluetoothGattCallback callback, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void clientSetPreferredPhy(in IBluetoothGattCallback callback, in BluetoothDevice device, in int txPhy, in int rxPhy, in int phyOptions, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void clientReadPhy(in IBluetoothGattCallback callback, in BluetoothDevice device, in AttributionSource attributionSources);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void refreshDevice(in IBluetoothGattCallback callback, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void discoverServices(in IBluetoothGattCallback callback, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void discoverServiceByUuid(in IBluetoothGattCallback callback, in BluetoothDevice device, in ParcelUuid uuid, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void readCharacteristic(in IBluetoothGattCallback callback, in BluetoothDevice device, in int handle, in int authReq, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED}, conditional=true)")
    void readUsingCharacteristicUuid(in IBluetoothGattCallback callback, in BluetoothDevice device, in ParcelUuid uuid,
                           in int startHandle, in int endHandle, in int authReq, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    int writeCharacteristic(in IBluetoothGattCallback callback, in BluetoothDevice device, in int handle,
                            in int writeType, in int authReq, in byte[] value, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void readDescriptor(in IBluetoothGattCallback callback, in BluetoothDevice device, in int handle, in int authReq, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    int writeDescriptor(in IBluetoothGattCallback callback, in BluetoothDevice device, in int handle,
                            in int authReq, in byte[] value, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void registerForNotification(in IBluetoothGattCallback callback, in BluetoothDevice device, in int handle, in boolean enable, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void beginReliableWrite(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void endReliableWrite(in IBluetoothGattCallback callback, in BluetoothDevice device, in boolean execute, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean readRemoteRssi(in IBluetoothGattCallback callback, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void configureMTU(in IBluetoothGattCallback callback, in BluetoothDevice device, in int mtu, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void connectionParameterUpdate(in IBluetoothGattCallback callback, in BluetoothDevice device, in int connectionPriority, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void leConnectionUpdate(in IBluetoothGattCallback callback, in BluetoothDevice device, int minInterval,
                            int maxInterval, int peripheralLatency, int supervisionTimeout,
                            int minConnectionEventLen, int maxConnectionEventLen, in AttributionSource attributionSource);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void registerServer(in IBluetoothGattServerCallback callback, boolean eatt_support, in int transport, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void unregisterServer(in IBluetoothGattServerCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void serverConnect(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in int addressType, in boolean isDirect, in int transport, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void serverDisconnect(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void serverSetPreferredPhy(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in int txPhy, in int rxPhy, in int phyOptions, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void serverReadPhy(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void addService(in IBluetoothGattServerCallback callback, in BluetoothGattService service, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void removeService(in IBluetoothGattServerCallback callback, in int handle, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void clearServices(in IBluetoothGattServerCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void sendResponse(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in int requestId,
                            in int status, in int offset, in byte[] value, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    int sendNotification(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in int handle,
                            in boolean confirm, in byte[] value, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void disconnectAll(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED}, conditional=true)")
    int subrateModeRequest(in IBluetoothGattCallback callback, in BluetoothDevice device, in int subrateMode, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    GattOffloadSession.InnerParcel offloadClientCharacteristics(in IBluetoothGattCallback callback, in BluetoothDevice device, in BluetoothGattService service, in List<BluetoothGattCharacteristic> characteristics, in long endpointId, in long hubId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void unoffloadClientCharacteristics(in IBluetoothGattCallback callback, in BluetoothDevice device, in int sessionId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    GattOffloadSession.InnerParcel offloadServerCharacteristics(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in BluetoothGattService service, in List<BluetoothGattCharacteristic> characteristics, in long endpointId, in long hubId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void unoffloadServerCharacteristics(in IBluetoothGattServerCallback callback, in BluetoothDevice device, in int sessionId, in AttributionSource attributionSource);
}
