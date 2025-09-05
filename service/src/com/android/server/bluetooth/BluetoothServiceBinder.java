/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.bluetooth;

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.LOCAL_MAC_ADDRESS;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.server.bluetooth.BtPermissionUtils.checkConnectPermissionForDataDelivery;
import static com.android.server.bluetooth.BtPermissionUtils.getCallingAppId;
import static com.android.server.bluetooth.BtPermissionUtils.isCallerSystem;

import static java.util.Objects.requireNonNull;

import android.app.AppOpsManager;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.bluetooth.flags.Flags;

import libcore.util.SneakyThrow;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BluetoothServiceBinder extends IBluetoothManager.Stub {
    private static final String TAG = BluetoothServiceBinder.class.getSimpleName();

    private final BluetoothManagerServiceApi mApi;
    private final Context mContext;
    private final UserManager mUserManager;
    private final AppOpsManager mAppOpsManager;
    private final PermissionManager mPermissionManager;
    private final BtPermissionUtils mPermissionUtils;
    private final Handler mHandler;
    private final Messenger mMessenger;

    BluetoothServiceBinder(Looper looper, BluetoothManagerServiceApi api, Context ctx) {
        mApi = api;
        mContext = ctx;
        mUserManager = requireNonNull(ctx.getSystemService(UserManager.class));
        mAppOpsManager = requireNonNull(ctx.getSystemService(AppOpsManager.class));
        mPermissionManager = requireNonNull(ctx.getSystemService(PermissionManager.class));
        var packageManager = ctx.createContextAsUser(UserHandle.SYSTEM, 0).getPackageManager();
        mPermissionUtils = new BtPermissionUtils(ctx);
        mHandler = new Handler(looper);
        var permissionChecker =
                new PermissionChecker(
                        mContext,
                        mUserManager,
                        packageManager,
                        mPermissionManager,
                        mContext.getAttributionSource());
        mMessenger = new ServiceMessenger(looper, permissionChecker, mApi).getMessenger();
    }

    private void postFromBinder(Runnable runnable) {
        postFromBinder(
                () -> {
                    runnable.run();
                    return null;
                });
    }

    private <T> T postFromBinder(Callable<T> callable) {
        FutureTask<T> task = new FutureTask<>(callable);

        mHandler.post(task);
        try {
            // Any method calling postFromBinder should most likely be done in under 1 seconds.
            // But real life shows that the system server thread may sometimes be unwillingly busy.
            // By putting a 10 seconds timeout we make sure this will generate an ANR (on purpose).
            // ANR will be investigated and fixed
            return task.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            SneakyThrow.sneakyThrow(e);
        } catch (ExecutionException e) {
            SneakyThrow.sneakyThrow(e.getCause());
        }
        return null; // Unreachable due to SneakyThrow
    }

    @Override
    public @NonNull Messenger getServiceMessenger() {
        return mMessenger;
    }

    @Override
    @Nullable
    public IBinder registerAdapter(@NonNull IBluetoothManagerCallback callback) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(callback);
        return postFromBinder(() -> mApi.registerAdapter(callback));
    }

    @Override
    public void unregisterAdapter(@NonNull IBluetoothManagerCallback callback) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(callback);
        postFromBinder(() -> mApi.unregisterAdapter(callback));
    }

    @Override
    public int getState() {
        return mApi.getState(); // This method is designed to work concurrently
    }

    @Override
    public String getAddress(AttributionSource source) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);

        if (!checkConnectPermissionForDataDelivery(
                mContext, mPermissionManager, source, "getAddress")) {
            return null;
        }

        if (!isCallerSystem(getCallingAppId())
                && !mPermissionUtils.checkIfCallerIsForegroundUser(mUserManager)) {
            Log.w(TAG, "getAddress(): Not allowed for non-active and non system user");
            return null;
        }

        if (mContext.checkCallingOrSelfPermission(LOCAL_MAC_ADDRESS) != PERMISSION_GRANTED) {
            // TODO(b/280890575): Throws a SecurityException instead
            Log.w(TAG, "getAddress(): Client does not have LOCAL_MAC_ADDRESS permission");
            return IBluetoothManager.DEFAULT_MAC_ADDRESS;
        }

        return postFromBinder(() -> mApi.getAddress());
    }

    @Override
    public String getName(AttributionSource source) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);

        if (!checkConnectPermissionForDataDelivery(
                mContext, mPermissionManager, source, "getName")) {
            return null;
        }

        if (!isCallerSystem(getCallingAppId())
                && !mPermissionUtils.checkIfCallerIsForegroundUser(mUserManager)) {
            Log.w(TAG, "getName(): not allowed for non-active and non system user");
            return null;
        }

        return postFromBinder(() -> mApi.getName());
    }

    @Override
    public boolean isBleScanAvailable() {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        return postFromBinder(() -> mApi.isBleScanAvailable());
    }

    @Override
    public boolean isHearingAidProfileSupported() {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        return postFromBinder(() -> mApi.isHearingAidProfileSupported());
    }

    @Override
    public boolean enable(@NonNull AttributionSource source) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);

        final String errorMsg =
                mPermissionUtils.callerCanToggle(
                        mContext,
                        source,
                        mUserManager,
                        mAppOpsManager,
                        mPermissionManager,
                        "enable",
                        true);
        if (!errorMsg.isEmpty()) {
            Log.d(TAG, "enable(): FAILED: " + errorMsg);
            return false;
        }

        var reason = ENABLE_DISABLE_REASON_APPLICATION_REQUEST;
        var packageName = source.getPackageName();

        Log.d(TAG, "enable(" + reason + ", " + packageName + ")");
        return postFromBinder(() -> mApi.enable(reason, packageName));
    }

    @Override
    public boolean enableBle(AttributionSource source, IBinder token) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);
        requireNonNull(token);

        final String errorMsg =
                mPermissionUtils.callerCanToggle(
                        mContext,
                        source,
                        mUserManager,
                        mAppOpsManager,
                        mPermissionManager,
                        "enableBle",
                        false);
        if (!errorMsg.isEmpty()) {
            Log.d(TAG, "enableBle(): FAILED: " + errorMsg);
            return false;
        }

        var packageName = source.getPackageName();

        Log.d(TAG, "enableBle(" + packageName + ", " + token + ")");
        return postFromBinder(() -> mApi.enableBle(packageName, token));
    }

    @Override
    public boolean enableNoAutoConnect(AttributionSource source) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);

        final String errorMsg =
                mPermissionUtils.callerCanToggle(
                        mContext,
                        source,
                        mUserManager,
                        mAppOpsManager,
                        mPermissionManager,
                        "enableNoAutoConnect",
                        false);
        if (!errorMsg.isEmpty()) {
            Log.d(TAG, "enableNoAutoConnect(): FAILED: " + errorMsg);
            return false;
        }

        if (!BtPermissionUtils.isCallerNfc(getCallingAppId())) {
            throw new SecurityException("No permission to enable Bluetooth quietly");
        }

        var packageName = source.getPackageName();

        Log.d(TAG, "enableNoAutoConnect(" + packageName + ")");
        return postFromBinder(() -> mApi.enableNoAutoConnect(packageName));
    }

    @Override
    public boolean disable(AttributionSource source, boolean persist) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);

        if (!persist) {
            BtPermissionUtils.enforcePrivileged(mContext);
        }

        final String errorMsg =
                mPermissionUtils.callerCanToggle(
                        mContext,
                        source,
                        mUserManager,
                        mAppOpsManager,
                        mPermissionManager,
                        "disable",
                        true);
        if (!errorMsg.isEmpty()) {
            Log.d(TAG, "disable(): FAILED: " + errorMsg);
            return false;
        }

        var packageName = source.getPackageName();

        Log.d(TAG, "disable(" + packageName + ", " + persist + ")");
        return postFromBinder(() -> mApi.disable(packageName, persist));
    }

    @Override
    public boolean disableBle(AttributionSource source, IBinder token) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);
        requireNonNull(token);

        final String errorMsg =
                mPermissionUtils.callerCanToggle(
                        mContext,
                        source,
                        mUserManager,
                        mAppOpsManager,
                        mPermissionManager,
                        "disableBle",
                        false);
        if (!errorMsg.isEmpty()) {
            Log.d(TAG, "disableBle(): FAILED: " + errorMsg);
            return false;
        }

        var packageName = source.getPackageName();

        Log.d(TAG, "disableBle(" + packageName + ", " + token + ")");
        return postFromBinder(() -> mApi.disableBle(packageName, token));
    }

    @Override
    public boolean factoryReset(AttributionSource source) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        requireNonNull(source);

        BtPermissionUtils.enforcePrivileged(mContext);

        if (!checkConnectPermissionForDataDelivery(
                mContext, mPermissionManager, source, "factoryReset")) {
            return false;
        }

        Log.d(TAG, "factoryReset(0)");
        return postFromBinder(() -> mApi.factoryReset(0));
    }

    @Override
    public int setBtHciSnoopLogMode(int mode) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        BtPermissionUtils.enforcePrivileged(mContext);

        return postFromBinder(() -> mApi.setBtHciSnoopLogMode(mode));
    }

    @Override
    public int getBtHciSnoopLogMode() {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        BtPermissionUtils.enforcePrivileged(mContext);

        return postFromBinder(() -> mApi.getBtHciSnoopLogMode());
    }

    @Override
    public boolean isAutoOnSupported() {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        BtPermissionUtils.enforcePrivileged(mContext);
        Log.d(TAG, "isAutoOnSupported()");
        return postFromBinder(() -> mApi.isAutoOnSupported());
    }

    @Override
    public boolean isAutoOnEnabled() {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        BtPermissionUtils.enforcePrivileged(mContext);
        Log.d(TAG, "isAutoOnEnabled()");
        return postFromBinder(() -> mApi.isAutoOnEnabled());
    }

    @Override
    public void setAutoOnEnabled(boolean status) {
        if (Flags.systemServerMessenger()) {
            throw new IllegalStateException("Binder call unavailable when using messenger");
        }
        BtPermissionUtils.enforcePrivileged(mContext);
        Log.d(TAG, "setAutoOnEnabled(" + status + ")");
        postFromBinder(() -> mApi.setAutoOnEnabled(status));
    }

    @Override
    public int handleShellCommand(
            @NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out,
            @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new ShellCommand(this, mMessenger, mApi::waitForState)
                .exec(
                        this,
                        in.getFileDescriptor(),
                        out.getFileDescriptor(),
                        err.getFileDescriptor(),
                        args);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (mContext.checkCallingOrSelfPermission(DUMP) != PERMISSION_GRANTED) {
            // TODO(b/280890575): Throws SecurityException instead
            Log.w(TAG, "dump(): Client does not have DUMP permission");
            return;
        }

        postFromBinder(() -> mApi.dump(fd, writer, args));
    }
}
