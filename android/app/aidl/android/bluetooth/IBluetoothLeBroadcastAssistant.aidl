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

package android.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.ScanFilter;
import android.content.AttributionSource;

/**
 * APIs for Bluetooth LE Audio Broadcast Assistant service
 *
 * @hide
 */
interface IBluetoothLeBroadcastAssistant {
    // Public API
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    int getConnectionState(in BluetoothDevice sink, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    List<BluetoothDevice> getConnectedDevices(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean setConnectionPolicy(in BluetoothDevice device, int connectionPolicy, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    int getConnectionPolicy(in BluetoothDevice device, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void registerCallback(in IBluetoothLeBroadcastAssistantCallback cb, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void unregisterCallback(in IBluetoothLeBroadcastAssistantCallback cb, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void startSearchingForSources(in List<ScanFilter> filters, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void stopSearchingForSources(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean isSearchInProgress(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void addSource(in BluetoothDevice sink, in BluetoothLeBroadcastMetadata sourceMetadata, in boolean isGroupOp, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void modifySource(in BluetoothDevice sink, in int sourceId, in BluetoothLeBroadcastMetadata updatedMetadata, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void removeSource(in BluetoothDevice sink, in int sourceId, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    List<BluetoothLeBroadcastReceiveState> getAllSources(in BluetoothDevice sink, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    int getMaximumSourceCapacity(in BluetoothDevice sink, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    BluetoothLeBroadcastMetadata getSourceMetadata(in BluetoothDevice sink, in int sourceId, in AttributionSource source);
}
