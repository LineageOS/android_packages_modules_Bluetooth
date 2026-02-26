/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.bluetooth.IBluetoothManagerCallback;
import android.content.AttributionSource;

/** Binder method for Bluetooth System Server interaction */
@JavaPassthrough(annotation="@android.annotation.Hide")
interface IBluetoothManager {
    const String DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00";

    const String IPC_CACHE_MODULE_SYSTEM = "system_server"; // See IpcDataCache.MODULE_SYSTEM
    const String GET_SYSTEM_STATE_API = "BluetoothAdapter_getSystemState";

    const String ACTION_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";
    const String ACTION_BLE_STATE_CHANGED = "android.bluetooth.adapter.action.BLE_STATE_CHANGED";
    const String EXTRA_STATE = "android.bluetooth.adapter.extra.STATE";
    const String EXTRA_PREVIOUS_STATE = "android.bluetooth.adapter.extra.PREVIOUS_STATE";

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    const String ACTION_LOCAL_NAME_CHANGED = "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED";
    const String EXTRA_LOCAL_NAME = "android.bluetooth.adapter.extra.LOCAL_NAME";

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    const String ACTION_AUTO_ON_STATE_CHANGED = "android.bluetooth.action.AUTO_ON_STATE_CHANGED";
    const String EXTRA_AUTO_ON_STATE = "android.bluetooth.extra.AUTO_ON_STATE";

    const int AUTO_ON_STATE_DISABLED = 1;
    const int AUTO_ON_STATE_ENABLED = 2;

    const int BT_SNOOP_LOG_MODE_DISABLED = 0;
    const int BT_SNOOP_LOG_MODE_FILTERED = 1;
    const int BT_SNOOP_LOG_MODE_FULL = 2;

    @JavaPassthrough(annotation="@android.annotation.RequiresNoPermission")
    IBinder registerAdapter(in IBluetoothManagerCallback callback);
    @JavaPassthrough(annotation="@android.annotation.RequiresNoPermission")
    void unregisterAdapter(in IBluetoothManagerCallback callback);

    @JavaPassthrough(annotation="@android.annotation.RequiresNoPermission")
    int getState();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.LOCAL_MAC_ADDRESS})")
    String getAddress(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void setName(in String name, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    String getName(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresNoPermission")
    boolean isBleScanAvailable();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean enable(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean enableBle(in AttributionSource source, IBinder b);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean enableNoAutoConnect(in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED}, conditional=true)")
    boolean disable(in AttributionSource source, boolean persist);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean disableBle(in AttributionSource source, IBinder b);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean factoryReset(in AttributionSource source);

    // SnoopLogMode
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void setBtHciSnoopLogMode(int mode);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    int getBtHciSnoopLogMode();

    // AutoOnFeature
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    boolean isAutoOnSupported();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    boolean isAutoOnEnabled();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void setAutoOnEnabled(boolean status);
}
