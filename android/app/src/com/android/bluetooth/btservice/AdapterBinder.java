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

package com.android.bluetooth.btservice;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.DUMP;

import static com.android.bluetooth.Util.callerIsSystemOrActiveOrManagedUser;

import android.annotation.RequiresPermission;
import android.bluetooth.IAdapter;
import android.bluetooth.IBluetoothCallback;
import android.util.Log;

import com.android.bluetooth.Util;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;

class AdapterBinder extends IAdapter.Stub {
    private static final String TAG = Util.BT_PREFIX + AdapterBinder.class.getSimpleName();

    private final AdapterService mService;

    AdapterBinder(AdapterService svc) {
        mService = svc;
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    private AdapterService getServiceAndEnforcePrivileged() {
        AdapterService service = getService();
        if (service == null) {
            return null;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service;
    }

    private AdapterService getService() {
        AdapterService service = mService;
        if (!service.isAvailable()) {
            return null;
        }
        return service;
    }

    @Override
    public void killBluetoothProcess() {
        Log.v(TAG, "killBluetoothProcess");
        mService.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

        final Runnable killAction =
                () -> {
                    Log.i(TAG, "killBluetoothProcess: Calling System.exit");
                    System.exit(0);
                };

        // Post on the main handler to let the cleanup complete before calling exit
        mService.getHandler().post(killAction);

        try {
            // Wait for Bluetooth to be killed from its main thread
            Thread.sleep(1_000); // SystemServer is waiting 2000 ms, we need to wait less here
        } catch (InterruptedException e) {
            Log.e(TAG, "killBluetoothProcess: Interrupted while waiting for kill");
        }

        // Bluetooth cannot be killed on the main thread; it is in a deadlock.
        // Trying to recover by killing the Bluetooth from the binder thread.
        // This is bad :(
        Log.wtf(TAG, "killBluetoothProcess: Deadlock on main thread. Trying from binder");
        killAction.run();
    }

    @Override
    public void offToBleOn(boolean quietMode, String hciInstanceName) {
        Log.v(TAG, "offToBleOn(" + quietMode + ", " + hciInstanceName + ")");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "offToBleOn")) {
            return;
        }
        service.offToBleOn(quietMode, hciInstanceName);
    }

    @Override
    public void onToBleOn() {
        Log.v(TAG, "onToBleOn()");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "onToBleOn")) {
            return;
        }
        service.onToBleOn();
    }

    @Override
    public void registerCallback(IBluetoothCallback callback) {
        Log.v(TAG, "registerCallback(" + callback + ")");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "registerCallback")) {
            return;
        }
        service.registerRemoteCallback(callback);
    }

    @Override
    public void unregisterCallback(IBluetoothCallback callback) {
        Log.v(TAG, "unregisterCallback(" + callback + ")");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "unregisterCallback")) {
            return;
        }
        service.unregisterRemoteCallback(callback);
    }

    @Override
    public void bleOnToOn() {
        Log.v(TAG, "bleOnToOn()");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "bleOnToOn")) {
            return;
        }
        service.bleOnToOn();
    }

    @Override
    public void bleOnToOff() {
        Log.v(TAG, "bleOnToOff()");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "bleOnToOff")) {
            return;
        }
        service.bleOnToOff();
    }

    @Override
    public void setName(String name) {
        Log.v(TAG, "setName(" + name + ")");
        AdapterService service = getServiceAndEnforcePrivileged();
        if (service == null) {
            return;
        }
        service.getHandler().post(() -> service.setName(name));
    }

    @Override
    public void dump(FileDescriptor fd, String[] args) {
        Log.v(TAG, "dump()");
        PrintWriter writer = new PrintWriter(new FileOutputStream(fd));
        AdapterService service = getService();
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(DUMP, null);
        service.dump(fd, writer, args);
        writer.close();
    }
}
