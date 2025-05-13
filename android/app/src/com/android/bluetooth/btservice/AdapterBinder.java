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

import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;

import android.bluetooth.IAdapter;
import android.bluetooth.IBluetoothCallback;
import android.os.Process;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.flags.Flags;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;

class AdapterBinder extends IAdapter.Stub {
    private static final String TAG = AdapterBinder.class.getSimpleName();

    private final AdapterService mService;

    AdapterBinder(AdapterService svc) {
        mService = svc;
    }

    public AdapterService getService() {
        if (!mService.isAvailable()) {
            return null;
        }
        return mService;
    }

    @Override
    public void killBluetoothProcess() {
        mService.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

        Runnable killAction =
                () -> {
                    if (Flags.killInsteadOfExit()) {
                        Log.i(TAG, "killBluetoothProcess: Calling killProcess(myPid())");
                        Process.killProcess(Process.myPid());
                    } else {
                        Log.i(TAG, "killBluetoothProcess: Calling System.exit");
                        System.exit(0);
                    }
                };

        // Post on the main handler to let the cleanup complete before calling exit
        mService.getHandler().post(killAction);

        try {
            // Wait for Bluetooth to be killed from its main thread
            Thread.sleep(1_000); // SystemServer is waiting 2000 ms, we need to wait less here
        } catch (InterruptedException e) {
            Log.e(TAG, "killBluetoothProcess: Interrupted while waiting for kill");
        }

        // Bluetooth cannot be killed on the main thread; it is in a deadLock.
        // Trying to recover by killing the Bluetooth from the binder thread.
        // This is bad :(
        Log.wtf(TAG, "Failed to kill Bluetooth using its main thread. Trying from binder");
        killAction.run();
    }

    @Override
    public void offToBleOn(boolean quietMode, String hciInstanceName) {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "offToBleOn")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.offToBleOn(quietMode, hciInstanceName);
    }

    @Override
    public void onToBleOn() {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "onToBleOn")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.onToBleOn();
    }

    @Override
    public void onewayFactoryReset() {
        AdapterService service = getService();
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.factoryReset();
    }

    @Override
    public void registerCallback(IBluetoothCallback callback) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "registerCallback")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.registerRemoteCallback(callback);
    }

    @Override
    public void unregisterCallback(IBluetoothCallback callback) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "unregisterCallback")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.unregisterRemoteCallback(callback);
    }

    @Override
    public void bleOnToOn() {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "bleOnToOn")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.bleOnToOn();
    }

    @Override
    public void bleOnToOff() {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "bleOnToOff")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.bleOnToOff();
    }

    @Override
    public void dump(FileDescriptor fd, String[] args) {
        PrintWriter writer = new PrintWriter(new FileOutputStream(fd));
        AdapterService service = getService();
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(DUMP, null);
        service.dump(fd, writer, args);
        writer.close();
    }

    @Override
    public boolean isMediaProfileConnected() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isMediaProfileConnected();
    }

    @Override
    public void setForegroundUserId(int userId) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        Utils.setForegroundUserId(userId);
    }

    @Override
    public void unregAllGattClient() {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.unregAllGattClient();
    }
}
