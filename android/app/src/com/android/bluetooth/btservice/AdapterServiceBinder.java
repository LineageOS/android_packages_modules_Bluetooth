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
import static android.Manifest.permission.LOCAL_MAC_ADDRESS;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE;
import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.ChangeIds.ENFORCE_CONNECT;
import static com.android.bluetooth.Utils.callerIsSystem;
import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;
import static com.android.bluetooth.Utils.checkConnectPermissionForDataDelivery;
import static com.android.bluetooth.Utils.checkScanPermissionForDataDelivery;
import static com.android.bluetooth.Utils.getBytesFromAddress;
import static com.android.bluetooth.Utils.getUidPidString;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.ActiveDeviceProfile;
import android.bluetooth.BluetoothAdapter.ActiveDeviceUse;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDevice.BluetoothAddress;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothActivityEnergyInfoListener;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothConnectionCallback;
import android.bluetooth.IBluetoothHciVendorSpecificCallback;
import android.bluetooth.IBluetoothMetadataListener;
import android.bluetooth.IBluetoothOobDataCallback;
import android.bluetooth.IBluetoothPreferredAudioProfilesCallback;
import android.bluetooth.IBluetoothQualityReportReadyCallback;
import android.bluetooth.IBluetoothSocketManager;
import android.bluetooth.IncomingRfcommSocketInfo;
import android.bluetooth.OobData;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.bluetooth.flags.Flags;
import com.android.modules.expresslog.Counter;

import libcore.util.SneakyThrow;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * There is no leak of this binder since it is never re-used and the process is systematically
 * killed
 */
class AdapterServiceBinder extends IBluetooth.Stub {
    private static final String TAG =
            Utils.TAG_PREFIX_BLUETOOTH + AdapterServiceBinder.class.getSimpleName();

    private static final int MIN_ADVT_INSTANCES_FOR_MA = 5;
    private static final int MIN_OFFLOADED_FILTERS = 10;
    private static final int MIN_OFFLOADED_SCAN_STORAGE_BYTES = 1024;

    private final AdapterService mService;

    AdapterServiceBinder(AdapterService svc) {
        mService = svc;
    }

    public AdapterService getService() {
        if (!mService.isAvailable()) {
            return null;
        }
        return mService;
    }

    @Override
    public int getState() {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothAdapter.STATE_OFF;
        }

        return service.getState();
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
        Counter.logIncrement("bluetooth.value_kill_from_binder_thread");
        Log.wtf(TAG, "Failed to kill Bluetooth using its main thread. Trying from binder");
        killAction.run();
    }

    @Override
    public void offToBleOn(boolean quietMode, AttributionSource source) {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "offToBleOn")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.offToBleOn(quietMode);
    }

    @Override
    public void onToBleOn(AttributionSource source) {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "onToBleOn")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.onToBleOn();
    }

    @Override
    public String getAddress(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getAddress")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getAddress")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(LOCAL_MAC_ADDRESS, null);
        return Utils.getAddressStringFromByte(service.getAdapterProperties().getAddress());
    }

    @Override
    public List<ParcelUuid> getUuids(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getUuids")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getUuids")) {
            return Collections.emptyList();
        }

        ParcelUuid[] parcels = service.getAdapterProperties().getUuids();
        if (parcels == null) {
            parcels = new ParcelUuid[0];
        }
        return Arrays.asList(parcels);
    }

    @Override
    public String getIdentityAddress(String address) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getIdentityAddress")
                || !checkConnectPermissionForDataDelivery(
                        service,
                        Utils.getCallingAttributionSource(mService),
                        TAG,
                        "getIdentityAddress")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getIdentityAddress(address);
    }

    @Override
    @NonNull
    public BluetoothAddress getIdentityAddressWithType(@NonNull String address) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getIdentityAddressWithType")
                || !checkConnectPermissionForDataDelivery(
                        service,
                        Utils.getCallingAttributionSource(mService),
                        TAG,
                        "getIdentityAddressWithType")) {
            return new BluetoothAddress(null, BluetoothDevice.ADDRESS_TYPE_UNKNOWN);
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getIdentityAddressWithType(address);
    }

    @Override
    public String getName(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getName")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getName")) {
            return null;
        }

        return service.getName();
    }

    @Override
    public int getNameLengthForAdvertise(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getNameLengthForAdvertise")
                || !Utils.checkAdvertisePermissionForDataDelivery(service, source, TAG)) {
            return -1;
        }

        return service.getNameLengthForAdvertise();
    }

    @Override
    public boolean setName(String name, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setName")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "setName")) {
            return false;
        }

        if (Flags.emptyNamesAreInvalid()) {
            requireNonNull(name);
            name = name.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty names are not valid");
            }
        }

        Log.d(TAG, "AdapterServiceBinder.setName(" + name + ")");
        return service.getAdapterProperties().setName(name);
    }

    @Override
    public int getScanMode(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getScanMode")
                || !checkScanPermissionForDataDelivery(service, source, TAG, "getScanMode")) {
            return SCAN_MODE_NONE;
        }

        return service.getScanMode();
    }

    @Override
    public int setScanMode(int mode, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setScanMode")
                || !checkScanPermissionForDataDelivery(service, source, TAG, "setScanMode")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        String logCaller = getUidPidString() + " packageName=" + source.getPackageName();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mService.getHandler()
                .post(
                        () ->
                                future.complete(
                                        service.getState() == BluetoothAdapter.STATE_ON
                                                && service.setScanMode(mode, logCaller)));
        return future.join() ? BluetoothStatusCodes.SUCCESS : BluetoothStatusCodes.ERROR_UNKNOWN;
    }

    @Override
    public long getDiscoverableTimeout(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getDiscoverableTimeout")
                || !checkScanPermissionForDataDelivery(
                        service, source, TAG, "getDiscoverableTimeout")) {
            return -1;
        }

        return service.getAdapterProperties().getDiscoverableTimeout();
    }

    @Override
    public int setDiscoverableTimeout(long timeout, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setDiscoverableTimeout")
                || !checkScanPermissionForDataDelivery(
                        service, source, TAG, "setDiscoverableTimeout")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getAdapterProperties().setDiscoverableTimeout((int) timeout)
                ? BluetoothStatusCodes.SUCCESS
                : BluetoothStatusCodes.ERROR_UNKNOWN;
    }

    @Override
    public boolean startDiscovery(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "startDiscovery")
                || !checkScanPermissionForDataDelivery(service, source, TAG, "startDiscovery")) {
            return false;
        }

        Log.i(TAG, "startDiscovery: from " + getUidPidString());
        return service.startDiscovery(source);
    }

    @Override
    public boolean cancelDiscovery(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "cancelDiscovery")
                || !checkScanPermissionForDataDelivery(service, source, TAG, "cancelDiscovery")) {
            return false;
        }

        Log.i(TAG, "cancelDiscovery: from " + getUidPidString());
        return service.getNative().cancelDiscovery();
    }

    @Override
    public boolean isDiscovering(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "isDiscovering")
                || !checkScanPermissionForDataDelivery(service, source, TAG, "isDiscovering")) {
            return false;
        }

        return service.getAdapterProperties().isDiscovering();
    }

    @Override
    public long getDiscoveryEndMillis(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getDiscoveryEndMillis")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getDiscoveryEndMillis")) {
            return -1;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service.getAdapterProperties().discoveryEndMillis();
    }

    @Override
    public List<BluetoothDevice> getMostRecentlyConnectedDevices(AttributionSource source) {
        // don't check caller, may be called from system UI
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getMostRecentlyConnectedDevices")) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        return service.getDatabaseManager().getMostRecentlyConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getBondedDevices(AttributionSource source) {
        // don't check caller, may be called from system UI
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getBondedDevices")) {
            return Collections.emptyList();
        }

        return Arrays.asList(service.getBondedDevices());
    }

    @Override
    public int getAdapterConnectionState() {
        // don't check caller, may be called from system UI
        AdapterService service = getService();
        if (service == null) {
            return BluetoothAdapter.STATE_DISCONNECTED;
        }

        return service.getAdapterProperties().getConnectionState();
    }

    /**
     * This method has an associated binder cache. The invalidation methods must be changed if the
     * logic behind this method changes.
     */
    @Override
    public int getProfileConnectionState(int profile, AttributionSource source) {
        AdapterService service = getService();
        boolean checkConnect = false;
        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            checkConnect = CompatChanges.isChangeEnabled(ENFORCE_CONNECT, callingUid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getProfileConnectionState")
                || (checkConnect
                        && !checkConnectPermissionForDataDelivery(
                                service, source, TAG, "getProfileConnectionState"))) {
            return STATE_DISCONNECTED;
        }

        return service.getAdapterProperties().getProfileConnectionState(profile);
    }

    @Override
    public boolean createBond(BluetoothDevice device, int transport, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "createBond")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "createBond")) {
            return false;
        }

        Log.i(
                TAG,
                "createBond:"
                        + (" device=" + device)
                        + (" transport=" + transport)
                        + (" from " + getUidPidString()));
        return service.createBond(device, transport, null, null, source.getPackageName());
    }

    @Override
    public boolean createBondOutOfBand(
            BluetoothDevice device,
            int transport,
            OobData remoteP192Data,
            OobData remoteP256Data,
            AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "createBondOutOfBand")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "createBondOutOfBand")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        Log.i(
                TAG,
                "createBondOutOfBand:"
                        + (" device=" + device)
                        + (" transport=" + transport)
                        + (" from " + getUidPidString()));
        return service.createBond(
                device, transport, remoteP192Data, remoteP256Data, source.getPackageName());
    }

    @Override
    public boolean cancelBondProcess(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "cancelBondProcess")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "cancelBondProcess")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        Log.i(TAG, "cancelBondProcess: device=" + device + ", from " + getUidPidString());

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        if (deviceProp != null) {
            deviceProp.setBondingInitiatedLocally(false);
        }

        service.logUserBondResponse(device, false, source);
        return service.getNative().cancelBond(getBytesFromAddress(device.getAddress()));
    }

    @Override
    public boolean removeBond(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "removeBond")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "removeBond")) {
            return false;
        }

        Log.i(TAG, "removeBond: device=" + device + ", from " + getUidPidString());

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDED) {
            Log.w(
                    TAG,
                    device
                            + " cannot be removed since "
                            + ((deviceProp == null)
                                    ? "properties are empty"
                                    : "bond state is " + deviceProp.getBondState()));
            return false;
        }
        service.logUserBondResponse(device, false, source);
        service.getBondAttemptCallerInfo().remove(device.getAddress());
        service.getPhonePolicy().ifPresent(policy -> policy.onRemoveBondRequest(device));
        deviceProp.setBondingInitiatedLocally(false);

        Message msg = service.getBondStateMachine().obtainMessage(BondStateMachine.REMOVE_BOND);
        msg.obj = device;
        service.getBondStateMachine().sendMessage(msg);
        return true;
    }

    @Override
    public int getBondState(BluetoothDevice device, AttributionSource source) {
        // don't check caller, may be called from system UI
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getBondState")) {
            return BluetoothDevice.BOND_NONE;
        }

        return service.getBondState(device);
    }

    @Override
    public boolean isBondingInitiatedLocally(BluetoothDevice device, AttributionSource source) {
        // don't check caller, may be called from system UI
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "isBondingInitiatedLocally")) {
            return false;
        }

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        return deviceProp != null && deviceProp.isBondingInitiatedLocally();
    }

    @Override
    public void generateLocalOobData(
            int transport, IBluetoothOobDataCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "generateLocalOobData")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "generateLocalOobData")) {
            return;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.generateLocalOobData(transport, callback);
    }

    @Override
    public long getSupportedProfiles(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getSupportedProfiles")) {
            return 0;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return Config.getSupportedProfilesBitMask();
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getConnectionState")) {
            return BluetoothDevice.CONNECTION_STATE_DISCONNECTED;
        }

        return service.getConnectionState(device);
    }

    @Override
    public int getConnectionHandle(
            BluetoothDevice device, int transport, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getConnectionHandle")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getConnectionHandle")) {
            return BluetoothDevice.ERROR;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionHandle(device, transport);
    }

    @Override
    public boolean canBondWithoutDialog(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "canBondWithoutDialog")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.canBondWithoutDialog(device);
    }

    @Override
    public String getPackageNameOfBondingApplication(
            BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();

        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getPackageNameOfBondingApplication")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getPackageNameOfBondingApplication(device);
    }

    @Override
    public boolean removeActiveDevice(@ActiveDeviceUse int profiles, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "removeActiveDevice")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "removeActiveDevice")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        Log.i(TAG, "removeActiveDevice: profiles=" + profiles + ", from " + getUidPidString());
        return service.setActiveDevice(null, profiles);
    }

    @Override
    public boolean setActiveDevice(
            BluetoothDevice device, @ActiveDeviceUse int profiles, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setActiveDevice")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "setActiveDevice")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        Log.i(
                TAG,
                "setActiveDevice: device="
                        + device
                        + ", profiles="
                        + profiles
                        + ", from "
                        + getUidPidString());

        return service.setActiveDevice(device, profiles);
    }

    @Override
    public List<BluetoothDevice> getActiveDevices(
            @ActiveDeviceProfile int profile, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getActiveDevices")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getActiveDevices")) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getActiveDevices(profile);
    }

    @Override
    public int connectAllEnabledProfiles(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null || !service.isEnabled()) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "connectAllEnabledProfiles")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        if (device == null) {
            throw new IllegalArgumentException("device cannot be null");
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "connectAllEnabledProfiles")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        Log.i(TAG, "connectAllEnabledProfiles: device=" + device + ", from " + getUidPidString());
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__INITIATOR_CONNECTION,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__START,
                        source.getUid());

        try {
            return service.connectAllEnabledProfiles(device);
        } catch (Exception e) {
            Log.v(TAG, "connectAllEnabledProfiles() failed", e);
            SneakyThrow.sneakyThrow(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public int disconnectAllEnabledProfiles(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "disconnectAllEnabledProfiles")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        if (device == null) {
            throw new IllegalArgumentException("device cannot be null");
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "disconnectAllEnabledProfiles")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        Log.i(
                TAG,
                "disconnectAllEnabledProfiles: device=" + device + ", from " + getUidPidString());

        try {
            return service.disconnectAllEnabledProfiles(device);
        } catch (Exception e) {
            Log.v(TAG, "disconnectAllEnabledProfiles() failed", e);
            SneakyThrow.sneakyThrow(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getRemoteName(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteName")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getRemoteName")) {
            return null;
        }

        return service.getRemoteName(device);
    }

    @Override
    public int getRemoteType(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteType")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getRemoteType")) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }

        return service.getRemoteType(device);
    }

    @Override
    public String getRemoteAlias(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteAlias")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getRemoteAlias")) {
            return null;
        }

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        return deviceProp != null ? deviceProp.getAlias() : null;
    }

    @Override
    public int setRemoteAlias(BluetoothDevice device, String name, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setRemoteAlias")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        if (name != null && name.isEmpty()) {
            throw new IllegalArgumentException("alias cannot be the empty string");
        }

        if (!checkConnectPermissionForDataDelivery(service, source, TAG, "setRemoteAlias")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                service, service.getCompanionDeviceManager(), source, device);

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }
        deviceProp.setAlias(device, name);
        return BluetoothStatusCodes.SUCCESS;
    }

    @Override
    public int getRemoteClass(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteClass")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getRemoteClass")) {
            return 0;
        }

        return service.getRemoteClass(device);
    }

    @Override
    public List<ParcelUuid> getRemoteUuids(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteUuids")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getRemoteUuids")) {
            return Collections.emptyList();
        }

        final ParcelUuid[] parcels = service.getRemoteUuids(device);
        if (parcels == null) {
            return null;
        }
        return Arrays.asList(parcels);
    }

    @Override
    public boolean fetchRemoteUuids(
            BluetoothDevice device, int transport, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "fetchRemoteUuids")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "fetchRemoteUuids")) {
            return false;
        }
        if (transport != TRANSPORT_AUTO) {
            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        }

        Log.i(
                TAG,
                "fetchRemoteUuids: device="
                        + device
                        + ", transport="
                        + transport
                        + ", from "
                        + getUidPidString());

        service.getRemoteDevices().fetchUuids(device, transport);
        MetricsLogger.getInstance().cacheCount(BluetoothProtoEnums.SDP_FETCH_UUID_REQUEST, 1);
        return true;
    }

    @Override
    public boolean setPin(
            BluetoothDevice device,
            boolean accept,
            int len,
            byte[] pinCode,
            AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setPin")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "setPin")) {
            return false;
        }

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        // Only allow setting a pin in bonding state, or bonded state in case of security
        // upgrade.
        if (deviceProp == null || !deviceProp.isBondingOrBonded()) {
            Log.e(TAG, "setPin: device=" + device + ", not bonding");
            return false;
        }
        if (pinCode.length != len) {
            android.util.EventLog.writeEvent(
                    0x534e4554, "139287605", -1, "PIN code length mismatch");
            return false;
        }
        service.logUserBondResponse(device, accept, source);
        Log.i(
                TAG,
                "setPin: device=" + device + ", accept=" + accept + ", from " + getUidPidString());
        return service.getNative()
                .pinReply(getBytesFromAddress(device.getAddress()), accept, len, pinCode);
    }

    @Override
    public boolean setPasskey(
            BluetoothDevice device,
            boolean accept,
            int len,
            byte[] passkey,
            AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setPasskey")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "setPasskey")) {
            return false;
        }

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        if (deviceProp == null || !deviceProp.isBonding()) {
            Log.e(TAG, "setPasskey: device=" + device + ", not bonding");
            return false;
        }
        if (passkey.length != len) {
            android.util.EventLog.writeEvent(
                    0x534e4554, "139287605", -1, "Passkey length mismatch");
            return false;
        }
        service.logUserBondResponse(device, accept, source);
        Log.i(
                TAG,
                "setPasskey: device="
                        + device
                        + ", accept="
                        + accept
                        + ", from "
                        + getUidPidString());

        return service.getNative()
                .sspReply(
                        getBytesFromAddress(device.getAddress()),
                        AbstractionLayer.BT_SSP_VARIANT_PASSKEY_ENTRY,
                        accept,
                        Utils.byteArrayToInt(passkey));
    }

    @Override
    public boolean setPairingConfirmation(
            BluetoothDevice device, boolean accept, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setPairingConfirmation")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "setPairingConfirmation")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        if (deviceProp == null || !deviceProp.isBonding()) {
            Log.e(TAG, "setPairingConfirmation: device=" + device + ", not bonding");
            return false;
        }
        service.logUserBondResponse(device, accept, source);
        Log.i(
                TAG,
                "setPairingConfirmation: device="
                        + device
                        + ", accept="
                        + accept
                        + ", from "
                        + getUidPidString());

        return service.getNative()
                .sspReply(
                        getBytesFromAddress(device.getAddress()),
                        AbstractionLayer.BT_SSP_VARIANT_PASSKEY_CONFIRMATION,
                        accept,
                        0);
    }

    @Override
    public boolean getSilenceMode(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getSilenceMode")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getSilenceMode")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getSilenceDeviceManager().getSilenceMode(device);
    }

    @Override
    public boolean setSilenceMode(
            BluetoothDevice device, boolean silence, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setSilenceMode")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "setSilenceMode")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.getSilenceDeviceManager().setSilenceMode(device, silence);
        return true;
    }

    @Override
    public int getPhonebookAccessPermission(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "getPhonebookAccessPermission")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getPhonebookAccessPermission")) {
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        return service.getPhonebookAccessPermission(device);
    }

    @Override
    public boolean setPhonebookAccessPermission(
            BluetoothDevice device, int value, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "setPhonebookAccessPermission")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "setPhonebookAccessPermission")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setPhonebookAccessPermission(device, value);
        return true;
    }

    @Override
    public int getMessageAccessPermission(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getMessageAccessPermission")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getMessageAccessPermission")) {
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        return service.getMessageAccessPermission(device);
    }

    @Override
    public boolean setMessageAccessPermission(
            BluetoothDevice device, int value, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setMessageAccessPermission")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "setMessageAccessPermission")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setMessageAccessPermission(device, value);
        return true;
    }

    @Override
    public int getSimAccessPermission(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getSimAccessPermission")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getSimAccessPermission")) {
            return BluetoothDevice.ACCESS_UNKNOWN;
        }

        return service.getSimAccessPermission(device);
    }

    @Override
    public boolean setSimAccessPermission(
            BluetoothDevice device, int value, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setSimAccessPermission")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "setSimAccessPermission")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setSimAccessPermission(device, value);
        return true;
    }

    @Override
    public void logL2capcocServerConnection(
            BluetoothDevice device,
            int port,
            boolean isSecured,
            int result,
            long socketCreationTimeMillis,
            long socketCreationLatencyMillis,
            long socketConnectionTimeMillis,
            long timeoutMillis) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        service.logL2capcocServerConnection(
                device,
                port,
                isSecured,
                result,
                socketCreationTimeMillis,
                socketCreationLatencyMillis,
                socketConnectionTimeMillis,
                timeoutMillis,
                Binder.getCallingUid());
    }

    @Override
    public IBluetoothSocketManager getSocketManager() {
        AdapterService service = getService();
        if (service == null) {
            return null;
        }

        return IBluetoothSocketManager.Stub.asInterface(service.getBluetoothSocketManagerBinder());
    }

    @Override
    public void logL2capcocClientConnection(
            BluetoothDevice device,
            int port,
            boolean isSecured,
            int result,
            long socketCreationTimeNanos,
            long socketCreationLatencyNanos,
            long socketConnectionTimeNanos) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        service.logL2capcocClientConnection(
                device,
                port,
                isSecured,
                result,
                socketCreationTimeNanos,
                socketCreationLatencyNanos,
                socketConnectionTimeNanos,
                Binder.getCallingUid());
    }

    @Override
    public void logRfcommConnectionAttempt(
            BluetoothDevice device,
            boolean isSecured,
            int resultCode,
            long socketCreationTimeNanos,
            boolean isSerialPort) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        service.logRfcommConnectionAttempt(
                device,
                isSecured,
                resultCode,
                socketCreationTimeNanos,
                isSerialPort,
                Binder.getCallingUid());
    }

    @Override
    public boolean sdpSearch(BluetoothDevice device, ParcelUuid uuid, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "sdpSearch")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "sdpSearch")) {
            return false;
        }
        return service.sdpSearch(device, uuid);
    }

    @Override
    public int getBatteryLevel(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getBatteryLevel")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getBatteryLevel")) {
            return BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        }

        DeviceProperties deviceProp = service.getRemoteDevices().getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        }
        return deviceProp.getBatteryLevel();
    }

    @Override
    public int getMaxConnectedAudioDevices(AttributionSource source) {
        // don't check caller, may be called from system UI
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getMaxConnectedAudioDevices")) {
            return -1;
        }

        return service.getMaxConnectedAudioDevices();
    }

    @Override
    public boolean factoryReset(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "factoryReset")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.factoryReset();
    }

    @Override
    public void registerBluetoothConnectionCallback(
            IBluetoothConnectionCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "registerBluetoothConnectionCallback")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "registerBluetoothConnectionCallback")) {
            return;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.getBluetoothConnectionCallbacks().register(callback);
    }

    @Override
    public void unregisterBluetoothConnectionCallback(
            IBluetoothConnectionCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "unregisterBluetoothConnectionCallback")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "unregisterBluetoothConnectionCallback")) {
            return;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.getBluetoothConnectionCallbacks().unregister(callback);
    }

    @Override
    public void registerCallback(IBluetoothCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "registerCallback")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "registerCallback")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.registerRemoteCallback(callback);
    }

    @Override
    public void unregisterCallback(IBluetoothCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "unregisterCallback")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "unregisterCallback")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.unregisterRemoteCallback(callback);
    }

    @Override
    public boolean isMultiAdvertisementSupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        int val = service.getAdapterProperties().getNumOfAdvertisementInstancesSupported();
        return val >= MIN_ADVT_INSTANCES_FOR_MA;
    }

    /**
     * This method has an associated binder cache. The invalidation methods must be changed if the
     * logic behind this method changes.
     */
    @Override
    public boolean isOffloadedFilteringSupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        int val = service.getNumOfOffloadedScanFilterSupported();
        return val >= MIN_OFFLOADED_FILTERS;
    }

    @Override
    public boolean isOffloadedScanBatchingSupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        int val = service.getOffloadedScanResultStorage();
        return val >= MIN_OFFLOADED_SCAN_STORAGE_BYTES;
    }

    @Override
    public boolean isLe2MPhySupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        return service.isLe2MPhySupported();
    }

    @Override
    public boolean isLeCodedPhySupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        return service.isLeCodedPhySupported();
    }

    @Override
    public boolean isLeExtendedAdvertisingSupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        return service.isLeExtendedAdvertisingSupported();
    }

    @Override
    public boolean isLePeriodicAdvertisingSupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        return service.isLePeriodicAdvertisingSupported();
    }

    @Override
    public int isLeAudioSupported() {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }

        Set<Integer> supportedProfileServices =
                Arrays.stream(Config.getSupportedProfiles()).boxed().collect(Collectors.toSet());
        int[] leAudioUnicastProfiles = Config.getLeAudioUnicastProfiles();

        if (Arrays.stream(leAudioUnicastProfiles).allMatch(supportedProfileServices::contains)) {
            return BluetoothStatusCodes.FEATURE_SUPPORTED;
        }

        return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
    }

    @Override
    public int isLeAudioBroadcastSourceSupported() {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }

        long supportBitMask = Config.getSupportedProfilesBitMask();
        if ((supportBitMask & (1 << BluetoothProfile.LE_AUDIO_BROADCAST)) != 0) {
            return BluetoothStatusCodes.FEATURE_SUPPORTED;
        }

        return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
    }

    @Override
    public int isLeAudioBroadcastAssistantSupported() {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }

        int[] supportedProfileServices = Config.getSupportedProfiles();

        if (Arrays.stream(supportedProfileServices)
                .anyMatch(
                        profileId -> profileId == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)) {
            return BluetoothStatusCodes.FEATURE_SUPPORTED;
        }

        return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
    }

    @Override
    public int isDistanceMeasurementSupported(AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        } else if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "isDistanceMeasurementSupported")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        } else if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "isDistanceMeasurementSupported")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return BluetoothStatusCodes.FEATURE_SUPPORTED;
    }

    @Override
    public int getLeMaximumAdvertisingDataLength() {
        AdapterService service = getService();
        if (service == null) {
            return 0;
        }

        return service.getLeMaximumAdvertisingDataLength();
    }

    @Override
    public boolean isActivityAndEnergyReportingSupported() {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }

        return service.getAdapterProperties().isActivityAndEnergyReportingSupported();
    }

    @Override
    public BluetoothActivityEnergyInfo reportActivityInfo(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "reportActivityInfo")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.reportActivityInfo();
    }

    @Override
    public boolean registerMetadataListener(
            IBluetoothMetadataListener listener, BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(listener);
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "registerMetadataListener")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "registerMetadataListener")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.getHandler()
                .post(
                        () ->
                                service.getMetadataListeners()
                                        .computeIfAbsent(device, k -> new RemoteCallbackList())
                                        .register(listener));
        return true;
    }

    @Override
    public boolean unregisterMetadataListener(
            IBluetoothMetadataListener listener, BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(listener);
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "unregisterMetadataListener")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "unregisterMetadataListener")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.getHandler()
                .post(
                        () ->
                                service.getMetadataListeners()
                                        .computeIfPresent(
                                                device,
                                                (k, v) -> {
                                                    v.unregister(listener);
                                                    if (v.getRegisteredCallbackCount() == 0) {
                                                        return null;
                                                    }
                                                    return v;
                                                }));
        return true;
    }

    @Override
    public boolean setMetadata(
            BluetoothDevice device, int key, byte[] value, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setMetadata")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "setMetadata")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setMetadata(device, key, value);
    }

    @Override
    public byte[] getMetadata(BluetoothDevice device, int key, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getMetadata")
                || !checkConnectPermissionForDataDelivery(service, source, TAG, "getMetadata")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getMetadata(device, key);
    }

    @Override
    public int isRequestAudioPolicyAsSinkSupported(
            BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "isRequestAudioPolicyAsSinkSupported")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "isRequestAudioPolicyAsSinkSupported")) {
            return BluetoothStatusCodes.FEATURE_NOT_CONFIGURED;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isRequestAudioPolicyAsSinkSupported(device);
    }

    @Override
    public int requestAudioPolicyAsSink(
            BluetoothDevice device, BluetoothSinkAudioPolicy policies, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        } else if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "requestAudioPolicyAsSink")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        } else if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "requestAudioPolicyAsSink")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.requestAudioPolicyAsSink(device, policies);
    }

    @Override
    public BluetoothSinkAudioPolicy getRequestedAudioPolicyAsSink(
            BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "getRequestedAudioPolicyAsSink")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "getRequestedAudioPolicyAsSink")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getRequestedAudioPolicyAsSink(device);
    }

    @Override
    public void requestActivityInfo(
            IBluetoothActivityEnergyInfoListener listener, AttributionSource source) {
        BluetoothActivityEnergyInfo info = reportActivityInfo(source);
        try {
            listener.onBluetoothActivityEnergyInfoAvailable(info);
        } catch (RemoteException e) {
            Log.e(TAG, "onBluetoothActivityEnergyInfo: RemoteException", e);
        }
    }

    @Override
    public void bleOnToOn(AttributionSource source) {
        AdapterService service = getService();
        if (service == null || !callerIsSystemOrActiveOrManagedUser(service, TAG, "bleOnToOn")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.bleOnToOn();
    }

    @Override
    public void bleOnToOff(AttributionSource source) {
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
    public boolean allowLowLatencyAudio(boolean allowed, BluetoothDevice device) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "allowLowLatencyAudio")
                || !checkConnectPermissionForDataDelivery(
                        service,
                        Utils.getCallingAttributionSource(service),
                        TAG,
                        "allowLowLatencyAudio")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.allowLowLatencyAudio(allowed, device);
    }

    @Override
    public int startRfcommListener(
            String name, ParcelUuid uuid, PendingIntent pendingIntent, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "startRfcommListener")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "startRfcommListener")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.startRfcommListener(name, uuid, pendingIntent, source);
    }

    @Override
    public int stopRfcommListener(ParcelUuid uuid, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(service, TAG, "stopRfcommListener")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "stopRfcommListener")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.stopRfcommListener(uuid, source);
    }

    @Override
    public IncomingRfcommSocketInfo retrievePendingSocketForServiceRecord(
            ParcelUuid uuid, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "retrievePendingSocketForServiceRecord")
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "retrievePendingSocketForServiceRecord")) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.retrievePendingSocketForServiceRecord(uuid, source);
    }

    @Override
    public void setForegroundUserId(int userId, AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service,
                        Utils.getCallingAttributionSource(mService),
                        TAG,
                        "setForegroundUserId")) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        Utils.setForegroundUserId(userId);
    }

    @Override
    public int setPreferredAudioProfiles(
            BluetoothDevice device, Bundle modeToProfileBundle, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setPreferredAudioProfiles")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        requireNonNull(device);
        requireNonNull(modeToProfileBundle);
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (service.getBondState(device) != BluetoothDevice.BOND_BONDED) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "setPreferredAudioProfiles")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setPreferredAudioProfiles(device, modeToProfileBundle);
    }

    @Override
    public Bundle getPreferredAudioProfiles(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return Bundle.EMPTY;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "getPreferredAudioProfiles")) {
            return Bundle.EMPTY;
        }
        requireNonNull(device);
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (service.getBondState(device) != BluetoothDevice.BOND_BONDED) {
            return Bundle.EMPTY;
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "getPreferredAudioProfiles")) {
            return Bundle.EMPTY;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getPreferredAudioProfiles(device);
    }

    @Override
    public int notifyActiveDeviceChangeApplied(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystem(TAG, "notifyActiveDeviceChangeApplied")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        requireNonNull(device);
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (service.getBondState(device) != BluetoothDevice.BOND_BONDED) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "notifyActiveDeviceChangeApplied")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.notifyActiveDeviceChangeApplied(device);
    }

    @Override
    public int isDualModeAudioEnabled(AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "isDualModeAudioEnabled")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        if (!Utils.isDualModeAudioEnabled()) {
            return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    @Override
    public int registerPreferredAudioProfilesChangedCallback(
            IBluetoothPreferredAudioProfilesCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "registerPreferredAudioProfilesChangedCallback")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        requireNonNull(callback);
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "registerPreferredAudioProfilesChangedCallback")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        // If LE only mode is enabled, the dual mode audio feature is disabled
        if (!Utils.isDualModeAudioEnabled()) {
            return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }

        service.getPreferredAudioProfilesCallbacks().register(callback);
        return BluetoothStatusCodes.SUCCESS;
    }

    @Override
    public int unregisterPreferredAudioProfilesChangedCallback(
            IBluetoothPreferredAudioProfilesCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "unregisterPreferredAudioProfilesChangedCallback")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        requireNonNull(callback);
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "unregisterPreferredAudioProfilesChangedCallback")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        if (!service.getPreferredAudioProfilesCallbacks().unregister(callback)) {
            Log.e(
                    TAG,
                    "unregisterPreferredAudioProfilesChangedCallback: callback was never "
                            + "registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    @Override
    public int registerBluetoothQualityReportReadyCallback(
            IBluetoothQualityReportReadyCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "registerBluetoothQualityReportReadyCallback")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        requireNonNull(callback);
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "registerBluetoothQualityReportReadyCallback")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.getBluetoothQualityReportReadyCallbacks().register(callback);
        return BluetoothStatusCodes.SUCCESS;
    }

    @Override
    public int unregisterBluetoothQualityReportReadyCallback(
            IBluetoothQualityReportReadyCallback callback, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "unregisterBluetoothQualityReportReadyCallback")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        requireNonNull(callback);
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "unregisterBluetoothQualityReportReadyCallback")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        if (!service.getBluetoothQualityReportReadyCallbacks().unregister(callback)) {
            Log.e(
                    TAG,
                    "unregisterBluetoothQualityReportReadyCallback: callback was never registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    @Override
    public void registerHciVendorSpecificCallback(
            IBluetoothHciVendorSpecificCallback callback, int[] eventCodes) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "registerHciVendorSpecificCallback")) {
            throw new SecurityException("not allowed");
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        requireNonNull(callback);
        requireNonNull(eventCodes);

        Set<Integer> eventCodesSet = Arrays.stream(eventCodes).boxed().collect(Collectors.toSet());
        if (eventCodesSet.stream()
                .anyMatch((n) -> (n < 0) || (n >= 0x52 && n < 0x60) || (n > 0xff))) {
            throw new IllegalArgumentException("invalid vendor-specific event code");
        }

        service.getBluetoothHciVendorSpecificDispatcher().register(callback, eventCodesSet);
    }

    @Override
    public void unregisterHciVendorSpecificCallback(IBluetoothHciVendorSpecificCallback callback) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        if (!callerIsSystemOrActiveOrManagedUser(
                service, TAG, "unregisterHciVendorSpecificCallback")) {
            throw new SecurityException("not allowed");
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        requireNonNull(callback);
        service.getBluetoothHciVendorSpecificDispatcher().unregister(callback);
    }

    @Override
    public void sendHciVendorSpecificCommand(
            int ocf, byte[] parameters, IBluetoothHciVendorSpecificCallback callback) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "sendHciVendorSpecificCommand")) {
            throw new SecurityException("not allowed");
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        // Open this no-op android command for test purpose
        int getVendorCapabilitiesOcf = 0x153;
        if (ocf < 0
                || (ocf >= 0x150 && ocf < 0x160 && ocf != getVendorCapabilitiesOcf)
                || (ocf > 0x3ff)) {
            throw new IllegalArgumentException("invalid vendor-specific event code");
        }
        requireNonNull(parameters);
        if (parameters.length > 255) {
            throw new IllegalArgumentException("Parameters size is too big");
        }

        Optional<byte[]> cookie =
                service.getBluetoothHciVendorSpecificDispatcher().getRegisteredCookie(callback);
        if (!cookie.isPresent()) {
            Log.e(TAG, "send command without registered callback");
            throw new IllegalStateException("callback not registered");
        }

        service.getBluetoothHciVendorSpecificNativeInterface()
                .sendCommand(ocf, parameters, cookie.get());
    }

    @Override
    public int getOffloadedTransportDiscoveryDataScanSupported(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !callerIsSystemOrActiveOrManagedUser(
                        service, TAG, "getOffloadedTransportDiscoveryDataScanSupported")
                || !checkScanPermissionForDataDelivery(
                        service, source, TAG, "getOffloadedTransportDiscoveryDataScanSupported")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getOffloadedTransportDiscoveryDataScanSupported();
    }

    @Override
    public boolean isMediaProfileConnected(AttributionSource source) {
        AdapterService service = getService();
        if (service == null
                || !checkConnectPermissionForDataDelivery(
                        service, source, TAG, "isMediaProfileConnected")) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isMediaProfileConnected();
    }

    @Override
    public IBinder getBluetoothGatt() {
        AdapterService service = getService();
        return service == null ? null : service.getBluetoothGatt();
    }

    @Override
    public IBinder getBluetoothScan() {
        AdapterService service = getService();
        return service == null ? null : service.getBluetoothScan();
    }

    @Override
    public void unregAllGattClient(AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.unregAllGattClient(source);
    }

    @Override
    public IBinder getProfile(int profileId) {
        AdapterService service = getService();
        if (service == null) {
            return null;
        }

        return service.getProfile(profileId);
    }

    @Override
    public int setActiveAudioDevicePolicy(
            BluetoothDevice device, int activeAudioDevicePolicy, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setActiveAudioDevicePolicy")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "setActiveAudioDevicePolicy")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDatabaseManager()
                .setActiveAudioDevicePolicy(device, activeAudioDevicePolicy);
    }

    @Override
    public int getActiveAudioDevicePolicy(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "getActiveAudioDevicePolicy")) {
            throw new IllegalStateException(
                    "Caller is not the system or part of the active/managed user");
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "getActiveAudioDevicePolicy")) {
            return BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDatabaseManager().getActiveAudioDevicePolicy(device);
    }

    @Override
    public int setMicrophonePreferredForCalls(
            BluetoothDevice device, boolean enabled, AttributionSource source) {
        requireNonNull(device);
        AdapterService service = getService();
        if (service == null) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setMicrophonePreferredForCalls")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "setMicrophonePreferredForCalls")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDatabaseManager().setMicrophonePreferredForCalls(device, enabled);
    }

    @Override
    public boolean isMicrophonePreferredForCalls(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        AdapterService service = getService();
        if (service == null) {
            return true;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "isMicrophonePreferredForCalls")) {
            throw new IllegalStateException(
                    "Caller is not the system or part of the active/managed user");
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(
                service, source, TAG, "isMicrophonePreferredForCalls")) {
            return true;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDatabaseManager().isMicrophonePreferredForCalls(device);
    }

    @Override
    public boolean isLeCocSocketOffloadSupported(AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isLeCocSocketOffloadSupported();
    }

    @Override
    public boolean isRfcommSocketOffloadSupported(AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return false;
        }
        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isRfcommSocketOffloadSupported();
    }

    @Override
    public IBinder getBluetoothAdvertise() {
        AdapterService service = getService();
        return service == null ? null : service.getBluetoothAdvertise();
    }

    @Override
    public IBinder getDistanceMeasurement() {
        AdapterService service = getService();
        return service == null ? null : service.getDistanceMeasurement();
    }

    @Override
    public int getKeyMissingCount(BluetoothDevice device, AttributionSource source) {
        AdapterService service = getService();
        if (service == null) {
            return -1;
        }
        if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "getKeyMissingCount")) {
            throw new IllegalStateException(
                    "Caller is not the system or part of the active/managed user");
        }
        if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
            throw new IllegalArgumentException("device cannot have an invalid address");
        }
        if (!checkConnectPermissionForDataDelivery(service, source, TAG, "getKeyMissingCount")) {
            return -1;
        }

        return service.getDatabaseManager().getKeyMissingCount(device);
    }
}
