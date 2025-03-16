/*
 * Copyright 2023 The Android Open Source Project
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
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.server.bluetooth.BtPermissionUtils.checkConnectPermissionForDataDelivery;
import static com.android.server.bluetooth.BtPermissionUtils.getCallingAppId;
import static com.android.server.bluetooth.BtPermissionUtils.isCallerSystem;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.UserManager;
import android.permission.PermissionManager;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.PrintWriter;

class BluetoothServiceBinder extends IBluetoothManager.Stub {
    private static final String TAG = BluetoothServiceBinder.class.getSimpleName();

    private final BluetoothManagerService mBluetoothManagerService;
    private final Context mContext;
    private final UserManager mUserManager;
    private final AppOpsManager mAppOpsManager;
    private final PermissionManager mPermissionManager;
    private final BtPermissionUtils mPermissionUtils;
    private final Looper unusedmLooper;

    BluetoothServiceBinder(
            BluetoothManagerService bms, Looper looper, Context ctx, UserManager userManager) {
        mBluetoothManagerService = bms;
        unusedmLooper = looper;
        mContext = ctx;
        mUserManager = userManager;
        mAppOpsManager =
                requireNonNull(
                        ctx.getSystemService(AppOpsManager.class),
                        "AppOpsManager system service cannot be null");
        mPermissionManager =
                requireNonNull(
                        ctx.getSystemService(PermissionManager.class),
                        "PermissionManager system service cannot be null");
        mPermissionUtils = new BtPermissionUtils(ctx);
    }

    @Override
    @Nullable
    public IBinder registerAdapter(@NonNull IBluetoothManagerCallback callback) {
        requireNonNull(callback, "Callback cannot be null in registerAdapter");
        IBluetooth bluetooth = mBluetoothManagerService.registerAdapter(callback);
        if (bluetooth == null) {
            return null;
        }
        return bluetooth.asBinder();
    }

    @Override
    public void unregisterAdapter(@NonNull IBluetoothManagerCallback callback) {
        requireNonNull(callback, "Callback cannot be null in unregisterAdapter");
        mBluetoothManagerService.unregisterAdapter(callback);
    }

    @Override
    public boolean enable(@NonNull AttributionSource source) {
        requireNonNull(source, "AttributionSource cannot be null in enable");

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

        Log.d(TAG, "enable()");
        return mBluetoothManagerService.enableFromBinder(source.getPackageName());
    }

    @Override
    public boolean enableNoAutoConnect(AttributionSource source) {
        requireNonNull(source, "AttributionSource cannot be null in enableNoAutoConnect");

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

        Log.d(TAG, "enableNoAutoConnect()");
        return mBluetoothManagerService.enableNoAutoConnectFromBinder(source.getPackageName());
    }

    @Override
    public boolean disable(AttributionSource source, boolean persist) {
        requireNonNull(source, "AttributionSource cannot be null in disable");

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

        Log.d(TAG, "disable(" + persist + ")");
        return mBluetoothManagerService.disableFromBinder(source.getPackageName(), persist);
    }

    @Override
    public int getState() {
        return mBluetoothManagerService.getState();
    }

    @Override
    public String getAddress(AttributionSource source) {
        requireNonNull(source, "AttributionSource cannot be null in getAddress");

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
            return BluetoothAdapter.DEFAULT_MAC_ADDRESS;
        }

        return mBluetoothManagerService.getAddress();
    }

    @Override
    public String getName(AttributionSource source) {
        requireNonNull(source, "AttributionSource cannot be null in getName");

        if (!checkConnectPermissionForDataDelivery(
                mContext, mPermissionManager, source, "getName")) {
            return null;
        }

        if (!isCallerSystem(getCallingAppId())
                && !mPermissionUtils.checkIfCallerIsForegroundUser(mUserManager)) {
            Log.w(TAG, "getName(): not allowed for non-active and non system user");
            return null;
        }

        return mBluetoothManagerService.getName();
    }

    @Override
    public boolean onFactoryReset(AttributionSource source) {
        requireNonNull(source, "AttributionSource cannot be null in onFactoryReset");

        BtPermissionUtils.enforcePrivileged(mContext);

        if (!checkConnectPermissionForDataDelivery(
                mContext, mPermissionManager, source, "onFactoryReset")) {
            return false;
        }

        return mBluetoothManagerService.onFactoryResetFromBinder();
    }

    @Override
    public boolean isBleScanAvailable() {
        return mBluetoothManagerService.isBleScanAvailable();
    }

    @Override
    public boolean enableBle(AttributionSource source, IBinder token) {
        requireNonNull(source, "AttributionSource cannot be null in enableBle");
        requireNonNull(token, "IBinder cannot be null in enableBle");

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

        Log.d(TAG, "enableBle(" + token + ")");
        return mBluetoothManagerService.enableBleFromBinder(source.getPackageName(), token);
    }

    @Override
    public boolean disableBle(AttributionSource source, IBinder token) {
        requireNonNull(source, "AttributionSource cannot be null in disableBle");
        requireNonNull(token, "IBinder cannot be null in disableBle");

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

        Log.d(TAG, "disableBle(" + token + ")");
        return mBluetoothManagerService.disableBleFromBinder(source.getPackageName(), token);
    }

    @Override
    public boolean isHearingAidProfileSupported() {
        return mBluetoothManagerService.isHearingAidProfileSupported();
    }

    @Override
    public int setBtHciSnoopLogMode(int mode) {
        BtPermissionUtils.enforcePrivileged(mContext);

        return mBluetoothManagerService.setBtHciSnoopLogMode(mode);
    }

    @Override
    public int getBtHciSnoopLogMode() {
        BtPermissionUtils.enforcePrivileged(mContext);

        return mBluetoothManagerService.getBtHciSnoopLogMode();
    }

    @Override
    public int handleShellCommand(
            @NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out,
            @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new BluetoothShellCommand(mBluetoothManagerService)
                .exec(
                        this,
                        in.getFileDescriptor(),
                        out.getFileDescriptor(),
                        err.getFileDescriptor(),
                        args);
    }

    @Override
    public boolean isAutoOnSupported() {
        BtPermissionUtils.enforcePrivileged(mContext);
        return mBluetoothManagerService.isAutoOnSupported();
    }

    @Override
    public boolean isAutoOnEnabled() {
        BtPermissionUtils.enforcePrivileged(mContext);
        return mBluetoothManagerService.isAutoOnEnabled();
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void setAutoOnEnabled(boolean status) {
        BtPermissionUtils.enforcePrivileged(mContext);
        mBluetoothManagerService.setAutoOnEnabled(status);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (mContext.checkCallingOrSelfPermission(DUMP) != PERMISSION_GRANTED) {
            // TODO(b/280890575): Throws SecurityException instead
            Log.w(TAG, "dump(): Client does not have DUMP permission");
            return;
        }

        mBluetoothManagerService.dump(fd, writer, args);
    }
}
