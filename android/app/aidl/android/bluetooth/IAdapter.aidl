/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.bluetooth.IBluetoothCallback;
import android.content.AttributionSource;

/** AdapterService methods exposed exclusively to the Bluetooth System Server */
@JavaPassthrough(annotation="@android.annotation.Hide")
oneway interface IAdapter {
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void offToBleOn(boolean quietMode, in String hciInstanceName);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void onToBleOn();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void bleOnToOn();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void bleOnToOff();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void registerCallback(in IBluetoothCallback callback);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void unregisterCallback(in IBluetoothCallback callback);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void killBluetoothProcess();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)")
    void setName(in String name);
}
