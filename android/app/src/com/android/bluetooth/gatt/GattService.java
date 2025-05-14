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

package com.android.bluetooth.gatt;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.transportToString;
import static com.android.bluetooth.util.AttributionSourceUtil.getLastAttributionTag;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.R;
import com.android.bluetooth.btservice.AbstractionLayer;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.le_scan.ScanController;
import com.android.internal.annotations.VisibleForTesting;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Provides Bluetooth Gatt profile, as a service in the Bluetooth application. */
public class GattService extends ProfileService {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + GattService.class.getSimpleName();

    private static final UUID HID_SERVICE_UUID =
            UUID.fromString("00001812-0000-1000-8000-00805F9B34FB");

    private static final UUID[] HID_UUIDS = {
        UUID.fromString("00002A4A-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("00002A4B-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("00002A4C-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("00002A4D-0000-1000-8000-00805F9B34FB")
    };

    private static final UUID ANDROID_TV_REMOTE_SERVICE_UUID =
            UUID.fromString("AB5E0001-5A21-4F05-BC7D-AF01F617B664");

    private static final UUID FIDO_SERVICE_UUID =
            UUID.fromString("0000FFFD-0000-1000-8000-00805F9B34FB"); // U2F

    private static final UUID[] LE_AUDIO_SERVICE_UUIDS = {
        UUID.fromString("00001844-0000-1000-8000-00805F9B34FB"), // VCS
        UUID.fromString("00001845-0000-1000-8000-00805F9B34FB"), // VOCS
        UUID.fromString("00001843-0000-1000-8000-00805F9B34FB"), // AICS
        UUID.fromString("00001850-0000-1000-8000-00805F9B34FB"), // PACS
        UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB"), // ASCS
        UUID.fromString("0000184F-0000-1000-8000-00805F9B34FB"), // BASS
        UUID.fromString("00001854-0000-1000-8000-00805F9B34FB"), // HAP
        UUID.fromString("00001846-0000-1000-8000-00805F9B34FB"), // CSIS
    };

    private static final Integer GATT_MTU_MAX = 517;
    private static final Map<String, Integer> EARLY_MTU_EXCHANGE_PACKAGES =
            Map.of("com.teslamotors", GATT_MTU_MAX);

    private static final Map<String, String> GATT_CLIENTS_NOTIFY_TO_ADAPTER_PACKAGES =
            Map.of(
                    "com.google.android.gms",
                    "com.google.android.gms.findmydevice",
                    "com.google.android.apps.adm",
                    "");

    private static final Set<String> GATT_CLIENTS_PREFER_RELAX_MODE =
            new HashSet<>(
                    Arrays.asList(
                            "activeunlock_primary",
                            "channelsoundingtestapp",
                            "com.google.android.apps.adm",
                            "channelsounding"));

    @VisibleForTesting static final int GATT_CLIENT_LIMIT_PER_APP = 32;

    /** This is only used when Flags.onlyStartScanDuringBleOn() is true. */
    private static GattService sGattService;

    /** List of our registered clients. */
    @VisibleForTesting ContextMap<IBluetoothGattCallback> mClientMap = new ContextMap<>();

    /** List of our registered server apps. */
    @VisibleForTesting ContextMap<IBluetoothGattServerCallback> mServerMap = new ContextMap<>();

    /** Reliable write queue */
    @VisibleForTesting Set<BluetoothDevice> mReliableQueue = new HashSet<>();

    /**
     * Set of restricted (which require a BLUETOOTH_PRIVILEGED permission) handles per connectionId.
     */
    @VisibleForTesting final Map<Integer, Set<Integer>> mRestrictedHandles = new HashMap<>();

    /** Server handle map. */
    private final HandleMap mHandleMap = new HandleMap();

    /**
     * HashMap used to synchronize writeCharacteristic calls mapping remote device to available
     * permit (connectId or -1).
     */
    private final HashMap<BluetoothDevice, Integer> mPermits = new HashMap<>();

    private final AdapterService mAdapterService;
    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final CompanionDeviceManager mCompanionDeviceManager;
    private final GattNativeInterface mNativeInterface;
    private final HandlerThread mHandlerThread;
    private final AdvertiseManager mAdvertiseManager;
    @Nullable private final ScanController mScanController;
    private final DistanceMeasurementManager mDistanceMeasurementManager;

    public GattService(AdapterService adapterService) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mActivityManager = requireNonNull(getSystemService(ActivityManager.class));
        mPackageManager = requireNonNull(mAdapterService.getPackageManager());
        mCompanionDeviceManager = requireNonNull(getSystemService(CompanionDeviceManager.class));

        Settings.Global.putInt(
                getContentResolver(), "bluetooth_sanitized_exposure_notification_supported", 1);

        mNativeInterface = GattObjectsFactory.getInstance().getNativeInterface();
        mNativeInterface.init(this, mAdapterService);

        // Create a thread to handle LE operations
        mHandlerThread = new HandlerThread("Bluetooth LE");
        mHandlerThread.start();

        mAdvertiseManager = new AdvertiseManager(mAdapterService, mHandlerThread.getLooper());

        if (!Flags.onlyStartScanDuringBleOn()) {
            mScanController = new ScanController(adapterService);
        } else {
            mScanController = null;
        }
        mDistanceMeasurementManager =
                GattObjectsFactory.getInstance()
                        .createDistanceMeasurementManager(
                                mAdapterService, mHandlerThread.getLooper());

        if (Flags.onlyStartScanDuringBleOn()) {
            setGattService(this);
        }
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileGattEnabled().orElse(true);
    }

    @Override
    protected void setTestModeEnabled(boolean enableTestMode) {
        if (mScanController != null) {
            mScanController.setTestModeEnabled(enableTestMode);
        }
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new GattServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        if (Flags.onlyStartScanDuringBleOn() && sGattService == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }
        if (Flags.onlyStartScanDuringBleOn()) {
            setGattService(null);
        }
        if (mScanController != null) {
            mScanController.cleanup();
        }
        mClientMap.clear();
        mRestrictedHandles.clear();
        mServerMap.clear();
        mHandleMap.clear();
        mReliableQueue.clear();
        mNativeInterface.cleanup();
        mAdvertiseManager.cleanup();
        mDistanceMeasurementManager.cleanup();
        mHandlerThread.quit();
    }

    /** This is only used when Flags.onlyStartScanDuringBleOn() is true. */
    public static synchronized GattService getGattService() {
        if (sGattService == null) {
            Log.w(TAG, "getGattService(): service is null");
            return null;
        }
        if (!sGattService.isAvailable()) {
            Log.w(TAG, "getGattService(): service is not available");
            return null;
        }
        return sGattService;
    }

    private static synchronized void setGattService(GattService instance) {
        Log.d(TAG, "setGattService(): set to: " + instance);
        sGattService = instance;
    }

    @Nullable
    public ScanController getScanController() {
        return mScanController;
    }

    ContextMap<IBluetoothGattServerCallback> getServerMap() {
        return mServerMap;
    }

    CompanionDeviceManager getCompanionDeviceManager() {
        return mCompanionDeviceManager;
    }

    private class ServerDeathRecipient implements IBinder.DeathRecipient {
        private final IBluetoothGattServerCallback mCallback;
        private final String mPackageName;

        ServerDeathRecipient(IBluetoothGattServerCallback callback, String packageName) {
            mCallback = callback;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Binder is dead - unregistering server " + mPackageName + " " + mCallback);
            unregisterServer(mCallback);
        }
    }

    private class ClientDeathRecipient implements IBinder.DeathRecipient {
        private final IBluetoothGattCallback mCallback;
        private final String mPackageName;

        ClientDeathRecipient(IBluetoothGattCallback callback, String packageName) {
            mCallback = callback;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Binder is dead - unregistering client " + mPackageName + " " + mCallback);
            unregisterClient(
                    mCallback, getAttributionSource(), ContextMap.RemoveReason.REASON_BINDER_DIED);
        }
    }

    // Suppressed because we are conditionally enforcing
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void permissionCheck(int connId, int handle) {
        if (!isHandleRestricted(connId, handle)) {
            return;
        }
        this.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
    }

    private boolean isHandleRestricted(int connId, int handle) {
        Set<Integer> restrictedHandles = mRestrictedHandles.get(connId);
        return restrictedHandles != null && restrictedHandles.contains(handle);
    }

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onClientRegisteredFromNative(int status, int clientIf, UUID uuid) {
        Log.d(TAG, "onClientRegistered() - UUID=" + uuid + ", clientIf=" + clientIf);
        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        if (status != 0) {
            mClientMap.remove(uuid, ContextMap.RemoveReason.REASON_REGISTER_FAILED);
        } else {
            app.id = clientIf;
            app.linkToDeath(new ClientDeathRecipient(app.callback, app.packageName));
        }
        callbackToApp(() -> app.callback.onClientRegistered(status));
    }

    void onConnectedFromNative(
            int clientIf, int connId, int transport, int status, BluetoothDevice device) {
        Log.d(
                TAG,
                "onConnected() -"
                        + (" clientIf=" + clientIf)
                        + (" connId=" + connId)
                        + (" transport=" + transportToString(transport))
                        + (" status=" + status)
                        + (" device=" + device));
        int connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        if (status != 0) {
            mAdapterService.notifyGattClientConnectFailed(clientIf, device);
        } else {
            mClientMap.addConnection(clientIf, connId, transport, device);

            // Allow one writeCharacteristic operation at a time for each connected remote device.
            synchronized (mPermits) {
                Log.d(TAG, "onConnected() - adding permit for device=" + device);
                mPermits.putIfAbsent(device, -1);
            }
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT, device, clientIf, connectionState, status);
        if (app == null) {
            return;
        }
        boolean connected = status == BluetoothGatt.GATT_SUCCESS;
        callbackToApp(() -> app.callback.onClientConnectionState(status, connected, device));
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
                        connectionStatusToState(status),
                        app.uid);
    }

    void onDisconnectedFromNative(
            int clientIf, int connId, int transport, int status, BluetoothDevice device) {
        Log.d(
                TAG,
                "onDisconnected() - "
                        + ("clientIf=" + clientIf)
                        + (", connId=" + connId)
                        + (", transport=" + transportToString(transport))
                        + (", device=" + device));
        mClientMap.removeConnection(clientIf, connId);
        mAdapterService.notifyGattClientDisconnect(clientIf, device);
        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        mRestrictedHandles.remove(connId);

        // Remove AtomicBoolean representing permit if no other connections rely on this remote
        // device.
        if (!mClientMap.getConnectedDevices().contains(device)) {
            synchronized (mPermits) {
                Log.d(TAG, "onDisconnected() - removing permit for device=" + device);
                mPermits.remove(device);
            }
        } else {
            synchronized (mPermits) {
                if (mPermits.get(device) == connId) {
                    Log.d(TAG, "onDisconnected() - set permit -1 for device=" + device);
                    mPermits.put(device, -1);
                }
            }
        }

        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                device,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED,
                status);
        if (app == null) {
            return;
        }
        final int disconnectStatus;
        if (status == 0x16 // HCI_ERR_CONN_CAUSE_LOCAL_HOST
                && mAdapterService.getDatabase().getKeyMissingCount(device) > 0) {
            // Native stack disconnects the link on detecting the bond loss. Native GATT would
            // return HCI_ERR_CONN_CAUSE_LOCAL_HOST in such case, but the apps should see
            // HCI_ERR_AUTH_FAILURE.
            Log.d(TAG, "onDisconnected() - disconnected due to bond loss for device=" + device);
            disconnectStatus = 0x05 /* HCI_ERR_AUTH_FAILURE */;
        } else {
            disconnectStatus = status;
        }
        callbackToApp(() -> app.callback.onClientConnectionState(disconnectStatus, false, device));
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS,
                        app.uid);
    }

    void onClientPhyUpdateFromNative(int connId, int txPhy, int rxPhy, int status) {
        Log.d(TAG, "onClientPhyUpdate() - connId=" + connId + ", status=" + status);

        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyUpdate(device, txPhy, rxPhy, status));
    }

    void onClientPhyReadFromNative(
            int clientIf, BluetoothDevice device, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onClientPhyRead() -"
                        + (" clientIf=" + clientIf)
                        + (", device=" + device)
                        + (", status=" + status));
        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "onClientPhyRead() - no connection to " + device);
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyRead(device, txPhy, rxPhy, status));
    }

    void onClientConnUpdateFromNative(
            int connId, int interval, int latency, int timeout, int status) {
        Log.d(TAG, "onClientConnUpdate() - connId=" + connId + ", status=" + status);

        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () -> app.callback.onConnectionUpdated(device, interval, latency, timeout, status));
    }

    void onServiceChangedFromNative(int connId) {
        Log.d(TAG, "onServiceChanged - connId=" + connId);

        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onServiceChanged(device));
    }

    void onClientSubrateChangeFromNative(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status) {
        Log.d(TAG, "onClientSubrateChange() - connId=" + connId + ", status=" + status);

        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onSubrateChange(
                                device, subrateFactor, latency, contNum, timeout, status));
    }

    GattDbElement getSampleGattDbElement() {
        return new GattDbElement();
    }

    void onGetGattDbFromNative(int connId, List<GattDbElement> db) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.d(TAG, "onGetGattDb() - device=" + device);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null || app.callback == null) {
            Log.e(TAG, "app or callback is null");
            return;
        }

        List<BluetoothGattService> dbOut = new ArrayList<>();
        Set<Integer> restrictedIds = new HashSet<>();

        BluetoothGattService currSrvc = null;
        BluetoothGattCharacteristic currChar = null;
        boolean isRestrictedSrvc = false;
        boolean isHidSrvc = false;
        boolean isRestrictedChar = false;

        for (GattDbElement el : db) {
            switch (el.type) {
                case GattDbElement.TYPE_PRIMARY_SERVICE:
                case GattDbElement.TYPE_SECONDARY_SERVICE:
                    Log.d(TAG, "got service with UUID=" + el.uuid + " id: " + el.id);

                    currSrvc = new BluetoothGattService(el.uuid, el.id, el.type);
                    dbOut.add(currSrvc);
                    isRestrictedSrvc = isRestrictedSrvcUuid(el.uuid);
                    isHidSrvc = isHidSrvcUuid(el.uuid);
                    if (isRestrictedSrvc) {
                        restrictedIds.add(el.id);
                    }
                    break;
                case GattDbElement.TYPE_CHARACTERISTIC:
                    Log.d(TAG, "got characteristic with UUID=" + el.uuid + " id: " + el.id);

                    currChar = new BluetoothGattCharacteristic(el.uuid, el.id, el.properties, 0);
                    currSrvc.addCharacteristic(currChar);
                    isRestrictedChar = isRestrictedSrvc || (isHidSrvc && isHidCharUuid(el.uuid));
                    if (isRestrictedChar) {
                        restrictedIds.add(el.id);
                    }
                    break;
                case GattDbElement.TYPE_DESCRIPTOR:
                    Log.d(TAG, "got descriptor with UUID=" + el.uuid + " id: " + el.id);

                    currChar.addDescriptor(new BluetoothGattDescriptor(el.uuid, el.id, 0));
                    if (isRestrictedChar) {
                        restrictedIds.add(el.id);
                    }
                    break;
                case GattDbElement.TYPE_INCLUDED_SERVICE:
                    Log.d(
                            TAG,
                            "got included service with UUID="
                                    + el.uuid
                                    + " id: "
                                    + el.id
                                    + " startHandle: "
                                    + el.startHandle);

                    currSrvc.addIncludedService(
                            new BluetoothGattService(el.uuid, el.startHandle, el.type));
                    break;
                default:
                    Log.e(
                            TAG,
                            "got unknown element with type="
                                    + el.type
                                    + " and UUID="
                                    + el.uuid
                                    + " id: "
                                    + el.id);
            }
        }

        if (!restrictedIds.isEmpty()) {
            mRestrictedHandles.put(connId, restrictedIds);
        }
        // Search is complete when there was error, or nothing more to process
        callbackToApp(() -> app.callback.onSearchComplete(device, dbOut, 0 /* status */));
    }

    void onRegisterForNotificationsFromNative(int connId, int status, int registered, int handle) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.d(
                TAG,
                "onRegisterForNotifications() -"
                        + (" device=" + device)
                        + (", status=" + status)
                        + (", registered=" + registered)
                        + (", handle=" + handle));
    }

    void onNotifyFromNative(
            int connId, BluetoothDevice device, int handle, boolean isNotify, byte[] data) {
        Log.v(
                TAG,
                "onNotify() - device=" + device + ", handle=" + handle + ", length=" + data.length);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onNotify(device, handle, data));
    }

    void onReadCharacteristicFromNative(int connId, int status, int handle, byte[] data) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onReadCharacteristic() -"
                        + (" device=" + device)
                        + (", status=" + status)
                        + (", length=" + data.length));

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onCharacteristicRead(device, status, handle, data));
    }

    void onWriteCharacteristicFromNative(int connId, int status, int handle, byte[] data) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        synchronized (mPermits) {
            Log.d(TAG, "onWriteCharacteristic() - increasing permit for device=" + device);
            mPermits.put(device, -1);
        }

        Log.v(
                TAG,
                "onWriteCharacteristic() -"
                        + (" device=" + device)
                        + (", status=" + status)
                        + (", length=" + data.length));

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            callbackToApp(() -> app.callback.onCharacteristicWrite(device, status, handle, data));
        } else {
            int queuedStatus = status;
            if (queuedStatus == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                queuedStatus = BluetoothGatt.GATT_SUCCESS;
            }
            final ByteString value = ByteString.copyFrom(data);
            CallbackInfo callbackInfo = new CallbackInfo(device, queuedStatus, handle, value);
            app.queueCallback(callbackInfo);
        }
    }

    void onExecuteCompletedFromNative(int connId, int status) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.v(TAG, "onExecuteCompleted() - device=" + device + ", status=" + status);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onExecuteWrite(device, status));
    }

    void onReadDescriptorFromNative(int connId, int status, int handle, byte[] data) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onReadDescriptor() -"
                        + (" device=" + device)
                        + (", status=" + status)
                        + (", length=" + data.length));

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onDescriptorRead(device, status, handle, data));
    }

    void onWriteDescriptorFromNative(int connId, int status, int handle, byte[] data) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onWriteDescriptor() -"
                        + (" device=" + device)
                        + (", status=" + status)
                        + (", length=" + data.length));

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onDescriptorWrite(device, status, handle, data));
    }

    void onReadRemoteRssiFromNative(int clientIf, BluetoothDevice device, int rssi, int status) {
        Log.d(
                TAG,
                "onReadRemoteRssi() -"
                        + (" clientIf=" + clientIf)
                        + (", device=" + device)
                        + (", rssi=" + rssi)
                        + (", status=" + status));

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onReadRemoteRssi(device, rssi, status));
    }

    void onConfigureMTUFromNative(int connId, int status, int mtu) {
        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        Log.d(TAG, "onConfigureMTU() device=" + device + ", status=" + status + ", mtu=" + mtu);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onConfigureMTU(device, mtu, status));
    }

    void onClientCongestionFromNative(int connId, boolean congested) {
        Log.v(TAG, "onClientCongestion() - connId=" + connId + ", congested=" + congested);
        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);

        if (app == null) {
            return;
        }
        app.isCongested = congested;
        while (!app.isCongested) {
            CallbackInfo callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) {
                return;
            }
            callbackToApp(
                    () ->
                            app.callback.onCharacteristicWrite(
                                    callbackInfo.device(),
                                    callbackInfo.status(),
                                    callbackInfo.handle(),
                                    callbackInfo.valueByteArray()));
        }
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Map<BluetoothDevice, Integer> deviceStates = new HashMap<>();

        // Add paired LE devices
        BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            if (getDeviceType(device) != AbstractionLayer.BT_DEVICE_TYPE_BREDR) {
                deviceStates.put(device, STATE_DISCONNECTED);
            }
        }

        // Add connected deviceStates
        Set<BluetoothDevice> connectedDevices = new HashSet<>();
        connectedDevices.addAll(mClientMap.getConnectedDevices());
        connectedDevices.addAll(mServerMap.getConnectedDevices());

        for (BluetoothDevice device : connectedDevices) {
            if (device != null) {
                deviceStates.put(device, STATE_CONNECTED);
            }
        }

        // Create matching device sub-set
        return deviceStates.entrySet().stream()
                .filter(e -> Arrays.stream(states).anyMatch(s -> s == e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    void disconnectAll(AttributionSource source) {
        Log.d(TAG, "disconnectAll()");
        Map<Integer, BluetoothDevice> connMap = mClientMap.getConnectedMap();
        for (Map.Entry<Integer, BluetoothDevice> entry : connMap.entrySet()) {
            Log.d(TAG, "disconnecting addr:" + entry.getValue());
            clientDisconnectInternal(entry.getKey(), entry.getValue(), source);
        }
    }

    public void unregAll() {
        for (IBluetoothGattCallback appId : mClientMap.getAllAppsCallbackId()) {
            Log.d(TAG, "unreg:" + appId);
            unregisterClient(
                    appId, getAttributionSource(), ContextMap.RemoveReason.REASON_UNREGISTER_ALL);
        }
        for (IBluetoothGattServerCallback appId : mServerMap.getAllAppsCallbackId()) {
            Log.d(TAG, "unreg:" + appId);
            unregisterServer(appId);
        }
    }

    /**************************************************************************
     * GATT Service functions - CLIENT
     *************************************************************************/

    void registerClient(
            UUID uuid,
            IBluetoothGattCallback callback,
            boolean eattSupport,
            AttributionSource source) {
        if (mClientMap.countByAppUid(Binder.getCallingUid()) >= GATT_CLIENT_LIMIT_PER_APP) {
            Log.w(TAG, "registerClient() - failed due to too many clients");
            callbackToApp(() -> callback.onClientRegistered(BluetoothGatt.GATT_FAILURE));
            return;
        }

        String name = source.getPackageName();
        String tag = getLastAttributionTag(source);
        String myPackage = AttributionSource.myAttributionSource().getPackageName();
        if (myPackage.equals(name) && tag != null) {
            /* For clients created by Bluetooth stack, use just tag as name */
            name = tag;
        } else if (tag != null) {
            name = name + "[" + tag + "]";
        }

        Log.d(TAG, "registerClient() - UUID=" + uuid + " name=" + name);
        mClientMap.add(uuid, callback, this, source);

        mNativeInterface.gattClientRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), name, eattSupport);
    }

    void unregisterClient(
            IBluetoothGattCallback callback,
            AttributionSource source,
            ContextMap.RemoveReason reason) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "unregisterClient(" + callback + "): Already unregistered");
            return;
        }
        int clientIf = clientApp.id;
        Log.d(TAG, "unregisterClient(" + callback + ") - clientIf=" + clientIf);
        for (ContextMap.Connection conn : mClientMap.getConnectionByApp(clientIf)) {
            MetricsLogger.getInstance()
                    .logBluetoothEvent(
                            conn.device(),
                            BluetoothStatsLog
                                    .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__END,
                            source.getUid());
        }
        mClientMap.remove(clientIf, reason);
        mNativeInterface.gattClientUnregisterApp(clientIf);
    }

    void clientConnect(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int phy,
            AttributionSource source) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientConnect(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;

        Log.d(
                TAG,
                "clientConnect() -"
                        + (" device=" + device)
                        + (", addressType=" + addressType)
                        + (", isDirect=" + isDirect)
                        + (", opportunistic=" + opportunistic)
                        + (", phy=" + phy));
        statsLogAppPackage(device, source.getUid(), clientIf);

        logClientForegroundInfo(source.getUid(), isDirect);

        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                device,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_CONNECTING,
                -1);

        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
                        isDirect
                                ? BluetoothStatsLog
                                        .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__DIRECT_CONNECT
                                : BluetoothStatsLog
                                        .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__INDIRECT_CONNECT,
                        source.getUid());

        int preferredMtu = 0;

        String packageName = source.getPackageName();
        boolean preferRelaxMode = false;
        String tag = getLastAttributionTag(source);
        if (tag != null
                && GATT_CLIENTS_PREFER_RELAX_MODE.stream()
                        .anyMatch(suffix -> tag.endsWith(suffix))) {
            preferRelaxMode = true;
        }
        Log.d(TAG, "clientConnect tag: " + tag + ", preferRelaxMode:" + preferRelaxMode);
        if (packageName != null) {
            mAdapterService.addAssociatedPackage(device, packageName);

            // Some apps expect MTU to be exchanged immediately on connections
            for (Map.Entry<String, Integer> entry : EARLY_MTU_EXCHANGE_PACKAGES.entrySet()) {
                if (packageName.contains(entry.getKey())) {
                    preferredMtu = entry.getValue();
                    Log.i(
                            TAG,
                            "Early MTU exchange preference ("
                                    + preferredMtu
                                    + ") requested for "
                                    + packageName);
                    break;
                }
            }
        }

        if (transport != BluetoothDevice.TRANSPORT_BREDR && isDirect && !opportunistic) {
            String attributionTag = getLastAttributionTag(source);
            if (packageName != null) {
                for (Map.Entry<String, String> entry :
                        GATT_CLIENTS_NOTIFY_TO_ADAPTER_PACKAGES.entrySet()) {
                    if (packageName.contains(entry.getKey())
                            && ((attributionTag != null
                                            && attributionTag.contains(entry.getValue()))
                                    || entry.getValue().isEmpty())) {
                        mAdapterService.notifyDirectLeGattClientConnect(clientIf, device);
                        break;
                    }
                }
            }
        }

        mNativeInterface.gattClientConnect(
                clientIf,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                preferredMtu,
                preferRelaxMode);
    }

    void clientDisconnect(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientDisconnect(" + callback + "): App not registered");
            return;
        }
        clientDisconnectInternal(clientApp.id, device, source);
    }

    void clientDisconnectInternal(int clientIf, BluetoothDevice device, AttributionSource source) {
        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        Log.d(TAG, "clientDisconnectInternal() - device=" + device + ", connId=" + connId);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                device,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTING,
                -1);
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__START,
                        source.getUid());

        mAdapterService.notifyGattClientDisconnect(clientIf, device);

        mNativeInterface.gattClientDisconnect(clientIf, device, connId != null ? connId : 0);
    }

    void clientSetPreferredPhy(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int txPhy,
            int rxPhy,
            int phyOptions) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientSetPreferredPhy(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "clientSetPreferredPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "clientSetPreferredPhy() - device=" + device + ", connId=" + connId);
        mNativeInterface.gattClientSetPreferredPhy(clientIf, device, txPhy, rxPhy, phyOptions);
    }

    void clientReadPhy(IBluetoothGattCallback callback, BluetoothDevice device) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientReadPhy(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "clientReadPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "clientReadPhy() - device=" + device + ", connId=" + connId);
        mNativeInterface.gattClientReadPhy(clientIf, device);
    }

    void refreshDevice(IBluetoothGattCallback callback, BluetoothDevice device) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "refreshDevice(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;

        Log.d(TAG, "refreshDevice() - device=" + device);
        mNativeInterface.gattClientRefresh(clientIf, device);
    }

    void discoverServices(IBluetoothGattCallback callback, BluetoothDevice device) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "discoverServices(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        Log.d(TAG, "discoverServices() - device=" + device + ", connId=" + connId);

        if (connId != null) {
            mNativeInterface.gattClientSearchService(connId, true, 0, 0);
        } else {
            Log.e(TAG, "discoverServices() - No connection for " + device);
        }
    }

    void discoverServiceByUuid(IBluetoothGattCallback callback, BluetoothDevice device, UUID uuid) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "discoverServiceByUuid(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientDiscoverServiceByUuid(
                    connId, uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
        } else {
            Log.e(TAG, "discoverServiceByUuid() - No connection for " + device);
        }
    }

    void readCharacteristic(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int authReq,
            AttributionSource source) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readCharacteristic(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.v(TAG, "readCharacteristic(" + device + ")");

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readCharacteristic(" + device + ") - No connection");
            return;
        }

        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readCharacteristic() - permission check failed!");
            return;
        }

        mNativeInterface.gattClientReadCharacteristic(connId, handle, authReq);
    }

    void readUsingCharacteristicUuid(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            UUID uuid,
            int startHandle,
            int endHandle,
            int authReq) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readUsingCharacteristicUuid(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.v(TAG, "readUsingCharacteristicUuid() - device=" + device);

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readUsingCharacteristicUuid() - No connection for " + device);
            return;
        }

        mNativeInterface.gattClientReadUsingCharacteristicUuid(
                connId,
                uuid.getLeastSignificantBits(),
                uuid.getMostSignificantBits(),
                startHandle,
                endHandle,
                authReq);
    }

    int writeCharacteristic(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int writeType,
            int authReq,
            byte[] value) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeCharacteristic(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        int clientIf = clientApp.id;
        Log.v(TAG, "writeCharacteristic(" + device + ")");

        if (mReliableQueue.contains(device)) {
            writeType = 3; // Prepared write
        }

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "writeCharacteristic(" + device + ") - No connection");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }
        permissionCheck(connId, handle);

        Log.d(TAG, "writeCharacteristic() - trying to acquire permit.");
        // Lock the thread until onCharacteristicWrite callback comes back.
        synchronized (mPermits) {
            Integer permit = mPermits.get(device);
            if (permit == null) {
                Log.d(TAG, "writeCharacteristic() -  atomicBoolean uninitialized!");
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
            }

            boolean success = (permit == -1);
            if (!success) {
                Log.d(TAG, "writeCharacteristic() - no permit available.");
                return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            }
            mPermits.put(device, connId);
        }

        mNativeInterface.gattClientWriteCharacteristic(connId, handle, writeType, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    void readDescriptor(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int authReq,
            AttributionSource source) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readDescriptor(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.v(TAG, "readDescriptor() - device=" + device);

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readDescriptor() - No connection for " + device);
            return;
        }

        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readDescriptor() - permission check failed!");
            return;
        }

        mNativeInterface.gattClientReadDescriptor(connId, handle, authReq);
    }

    int writeDescriptor(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int authReq,
            byte[] value) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeDescriptor(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        int clientIf = clientApp.id;
        Log.v(TAG, "writeDescriptor() - device=" + device);

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "writeDescriptor() - No connection for " + device);
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }
        permissionCheck(connId, handle);

        mNativeInterface.gattClientWriteDescriptor(connId, handle, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    void beginReliableWrite(BluetoothDevice device) {
        Log.d(TAG, "beginReliableWrite() - device=" + device);
        mReliableQueue.add(device);
    }

    void endReliableWrite(
            IBluetoothGattCallback callback, BluetoothDevice device, boolean execute) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "endReliableWrite(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.d(TAG, "endReliableWrite() - device=" + device + " execute: " + execute);
        mReliableQueue.remove(device);

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientExecuteWrite(connId, execute);
        }
    }

    void registerForNotification(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            boolean enable,
            AttributionSource source) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeDescriptor(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.d(TAG, "registerForNotification() - device=" + device + " enable: " + enable);

        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "registerForNotification() - No connection for " + device);
            return;
        }

        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "registerForNotification() - permission check failed!");
            return;
        }

        mNativeInterface.gattClientRegisterForNotifications(clientIf, device, handle, enable);
    }

    void readRemoteRssi(IBluetoothGattCallback callback, BluetoothDevice device) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readRemoteRssi(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.d(TAG, "readRemoteRssi() - device=" + device);
        mNativeInterface.gattClientReadRemoteRssi(clientIf, device);
    }

    void configureMTU(IBluetoothGattCallback callback, BluetoothDevice device, int mtu) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readRemoteRssi(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.d(TAG, "configureMTU() - device=" + device + " mtu=" + mtu);
        Integer connId = mClientMap.connIdByDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientConfigureMTU(connId, mtu);
        } else {
            Log.e(TAG, "configureMTU() - No connection for " + device);
        }
    }

    void connectionParameterUpdate(
            IBluetoothGattCallback callback, BluetoothDevice device, int connectionPriority) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "connectionParameterUpdate(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        CompanionManager manager = mAdapterService.getCompanionManager();

        int minInterval =
                manager.getGattConnParameters(
                        device, CompanionManager.GATT_CONN_INTERVAL_MIN, connectionPriority);
        int maxInterval =
                manager.getGattConnParameters(
                        device, CompanionManager.GATT_CONN_INTERVAL_MAX, connectionPriority);
        // Peripheral latency
        int latency =
                manager.getGattConnParameters(
                        device, CompanionManager.GATT_CONN_LATENCY, connectionPriority);

        int timeout = 500; // 5s. Link supervision timeout is measured in N * 10ms
        Log.d(
                TAG,
                "connectionParameterUpdate() -"
                        + (" device=" + device)
                        + (", params=" + connectionPriority)
                        + (", interval=" + minInterval + "/" + maxInterval)
                        + (", timeout=" + timeout));

        mNativeInterface.gattConnectionParameterUpdate(
                clientIf, device, minInterval, maxInterval, latency, timeout, 0, 0);
    }

    void leConnectionUpdate(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int minInterval,
            int maxInterval,
            int peripheralLatency,
            int supervisionTimeout,
            int minConnectionEventLen,
            int maxConnectionEventLen) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "leConnectionUpdate(" + callback + "): App not registered");
            return;
        }
        int clientIf = clientApp.id;
        Log.d(
                TAG,
                "leConnectionUpdate() -"
                        + (" device=" + device)
                        + (", intervals=" + minInterval + "/" + maxInterval)
                        + (", latency=" + peripheralLatency)
                        + (", timeout=" + supervisionTimeout + "msec")
                        + (", min_ce=" + minConnectionEventLen)
                        + (", max_ce=" + maxConnectionEventLen));

        mNativeInterface.gattConnectionParameterUpdate(
                clientIf,
                device,
                minInterval,
                maxInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen);
    }

    int subrateModeRequest(
            IBluetoothGattCallback callback, BluetoothDevice device, int subrateMode) {
        ContextMap<IBluetoothGattCallback>.App clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "subrateModeRequest(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        int clientIf = clientApp.id;

        int subrateMin;
        int subrateMax;
        int maxLatency;
        int contNumber;
        int supervisionTimeout = 500; // 5s. Link supervision timeout is measured in N * 10ms

        Resources res = getResources();

        switch (subrateMode) {
            case BluetoothGatt.SUBRATE_REQUEST_MODE_HIGH:
                subrateMin = res.getInteger(R.integer.subrate_mode_high_priority_min_subrate);
                subrateMax = res.getInteger(R.integer.subrate_mode_high_priority_max_subrate);
                maxLatency = res.getInteger(R.integer.subrate_mode_high_priority_latency);
                contNumber = res.getInteger(R.integer.subrate_mode_high_priority_cont_number);
                break;

            case BluetoothGatt.SUBRATE_REQUEST_MODE_LOW_POWER:
                subrateMin = res.getInteger(R.integer.subrate_mode_low_power_min_subrate);
                subrateMax = res.getInteger(R.integer.subrate_mode_low_power_max_subrate);
                maxLatency = res.getInteger(R.integer.subrate_mode_low_power_latency);
                contNumber = res.getInteger(R.integer.subrate_mode_low_power_cont_number);
                break;

            case BluetoothGatt.SUBRATE_REQUEST_MODE_BALANCED:
            default:
                subrateMin = res.getInteger(R.integer.subrate_mode_balanced_min_subrate);
                subrateMax = res.getInteger(R.integer.subrate_mode_balanced_max_subrate);
                maxLatency = res.getInteger(R.integer.subrate_mode_balanced_latency);
                contNumber = res.getInteger(R.integer.subrate_mode_balanced_cont_number);
                break;
        }

        Log.d(
                TAG,
                ("subrateModeRequest(" + device + ", " + subrateMode + "): ")
                        + (", subrate min/max=" + subrateMin + "/" + subrateMax)
                        + (", maxLatency=" + maxLatency)
                        + (", continuationNumber=" + contNumber)
                        + (", timeout=" + supervisionTimeout));

        return mNativeInterface.gattSubrateRequest(
                clientIf,
                device,
                subrateMin,
                subrateMax,
                maxLatency,
                contNumber,
                supervisionTimeout);
    }

    /**************************************************************************
     * Callback functions - SERVER
     *************************************************************************/

    void onServerRegisteredFromNative(int status, int serverIf, UUID uuid) {
        Log.d(TAG, "onServerRegistered() - UUID=" + uuid + ", serverIf=" + serverIf);
        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        app.id = serverIf;
        app.linkToDeath(new ServerDeathRecipient(app.callback, app.packageName));
        callbackToApp(() -> app.callback.onServerRegistered(status));
    }

    void onServiceAddedFromNative(int status, int serverIf, List<GattDbElement> service) {
        Log.d(TAG, "onServiceAdded(), status=" + status);

        if (status != 0) {
            return;
        }

        GattDbElement svcEl = service.get(0);
        int srvcHandle = svcEl.attributeHandle;

        BluetoothGattService svc = null;

        for (GattDbElement el : service) {
            if (el.type == GattDbElement.TYPE_PRIMARY_SERVICE) {
                mHandleMap.addService(
                        serverIf,
                        el.attributeHandle,
                        el.uuid,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY,
                        0,
                        false);
                svc =
                        new BluetoothGattService(
                                svcEl.uuid,
                                svcEl.attributeHandle,
                                BluetoothGattService.SERVICE_TYPE_PRIMARY);
            } else if (el.type == GattDbElement.TYPE_SECONDARY_SERVICE) {
                mHandleMap.addService(
                        serverIf,
                        el.attributeHandle,
                        el.uuid,
                        BluetoothGattService.SERVICE_TYPE_SECONDARY,
                        0,
                        false);
                svc =
                        new BluetoothGattService(
                                svcEl.uuid,
                                svcEl.attributeHandle,
                                BluetoothGattService.SERVICE_TYPE_SECONDARY);
            } else if (el.type == GattDbElement.TYPE_CHARACTERISTIC) {
                mHandleMap.addCharacteristic(serverIf, el.attributeHandle, el.uuid, srvcHandle);
                svc.addCharacteristic(
                        new BluetoothGattCharacteristic(
                                el.uuid, el.attributeHandle, el.properties, el.permissions));
            } else if (el.type == GattDbElement.TYPE_DESCRIPTOR) {
                mHandleMap.addDescriptor(serverIf, el.attributeHandle, el.uuid, srvcHandle);
                List<BluetoothGattCharacteristic> chars = svc.getCharacteristics();
                chars.get(chars.size() - 1)
                        .addDescriptor(
                                new BluetoothGattDescriptor(
                                        el.uuid, el.attributeHandle, el.permissions));
            }
        }
        mHandleMap.setStarted(serverIf, srvcHandle, true);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(serverIf);
        if (app == null) {
            return;
        }
        final BluetoothGattService serviceAdded = svc;
        callbackToApp(() -> app.callback.onServiceAdded(status, serviceAdded));
    }

    void onServiceStoppedFromNative(int status, int serverIf, int srvcHandle) {
        Log.d(TAG, "onServiceStopped() srvcHandle=" + srvcHandle + ", status=" + status);
        if (status == 0) {
            mHandleMap.setStarted(serverIf, srvcHandle, false);
        }
        stopNextService(serverIf, status);
    }

    void onServiceDeletedFromNative(int status, int serverIf, int srvcHandle) {
        Log.d(TAG, "onServiceDeleted() srvcHandle=" + srvcHandle + ", status=" + status);
        mHandleMap.deleteService(serverIf, srvcHandle);
    }

    void onClientConnectedFromNative(
            BluetoothDevice device, int transport, boolean connected, int connId, int serverIf) {
        Log.d(
                TAG,
                "onClientConnected() connId="
                        + connId
                        + ", device="
                        + device
                        + ", transport="
                        + transportToString(transport)
                        + ", connected="
                        + connected);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(serverIf);
        if (app == null) {
            return;
        }
        int connectionState;
        if (connected) {
            mServerMap.addConnection(serverIf, connId, transport, device);
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
        } else {
            mServerMap.removeConnection(serverIf, connId);
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        }

        int applicationUid = -1;
        try {
            applicationUid =
                    this.getPackageManager().getPackageUid(app.packageName, PackageInfoFlags.of(0));
        } catch (NameNotFoundException e) {
            Log.d(TAG, "onClientConnected() uid_not_found=" + app.packageName);
        }

        callbackToApp(() -> app.callback.onServerConnectionState((byte) 0, connected, device));
        statsLogAppPackage(device, applicationUid, serverIf);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT_SERVER, device, serverIf, connectionState, -1);
    }

    void onServerPhyUpdateFromNative(int connId, int txPhy, int rxPhy, int status) {
        Log.d(TAG, "onServerPhyUpdate() - connId=" + connId + ", status=" + status);

        BluetoothDevice device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyUpdate(device, txPhy, rxPhy, status));
    }

    void onServerPhyReadFromNative(
            int serverIf, BluetoothDevice device, int txPhy, int rxPhy, int status) {
        Log.d(TAG, "onServerPhyRead() - device=" + device + ", status=" + status);

        Integer connId = mServerMap.connIdByDevice(serverIf, device);
        if (connId == null) {
            Log.d(TAG, "onServerPhyRead() - no connection to " + device);
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyRead(device, txPhy, rxPhy, status));
    }

    void onServerConnUpdateFromNative(
            int connId, int interval, int latency, int timeout, int status) {
        Log.d(TAG, "onServerConnUpdate() - connId=" + connId + ", status=" + status);

        BluetoothDevice device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () -> app.callback.onConnectionUpdated(device, interval, latency, timeout, status));
    }

    void onServerSubrateChangeFromNative(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status) {
        Log.d(TAG, "onServerSubrateChange() - connId=" + connId + ", status=" + status);

        BluetoothDevice device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onSubrateChange(
                                device, subrateFactor, latency, contNum, timeout, status));
    }

    void onServerReadCharacteristicFromNative(
            BluetoothDevice device,
            int connId,
            int transId,
            int handle,
            int offset,
            boolean isLong) {
        Log.v(
                TAG,
                "onServerReadCharacteristic() -"
                        + (" device=" + device)
                        + (", connId=" + connId)
                        + (", transId=" + transId)
                        + (", handle=" + handle)
                        + (", offset=" + offset));

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onCharacteristicReadRequest(
                                device, transId, offset, isLong, handle));
    }

    void onServerReadDescriptorFromNative(
            BluetoothDevice device,
            int connId,
            int transId,
            int handle,
            int offset,
            boolean isLong) {
        Log.v(
                TAG,
                "onServerReadDescriptor() -"
                        + (" device=" + device)
                        + (", connId=" + connId)
                        + (", transId=" + transId)
                        + (", handle=" + handle)
                        + (", offset=" + offset));

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onDescriptorReadRequest(
                                device, transId, offset, isLong, handle));
    }

    void onServerWriteCharacteristicFromNative(
            BluetoothDevice device,
            int connId,
            int transId,
            int handle,
            int offset,
            int length,
            boolean needRsp,
            boolean isPrep,
            byte[] data) {
        Log.v(
                TAG,
                "onServerWriteCharacteristic() -"
                        + (" device=" + device)
                        + (", connId=" + connId)
                        + (", transId=" + transId)
                        + (", handle=" + handle)
                        + (", offset=" + offset)
                        + (", isPrep=" + isPrep));

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onCharacteristicWriteRequest(
                                device, transId, offset, length, isPrep, needRsp, handle, data));
    }

    void onServerWriteDescriptorFromNative(
            BluetoothDevice device,
            int connId,
            int transId,
            int handle,
            int offset,
            int length,
            boolean needRsp,
            boolean isPrep,
            byte[] data) {
        Log.v(
                TAG,
                "onServerWriteDescriptor() -"
                        + (" device=" + device)
                        + (", connId=" + connId)
                        + (", transId=" + transId)
                        + (", handle=" + handle)
                        + (", offset=" + offset)
                        + (", isPrep=" + isPrep));

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onDescriptorWriteRequest(
                                device, transId, offset, length, isPrep, needRsp, handle, data));
    }

    void onExecuteWriteFromNative(BluetoothDevice device, int connId, int transId, int execWrite) {
        Log.d(
                TAG,
                "onExecuteWrite() -"
                        + (" device=" + device)
                        + (", connId=" + connId)
                        + (", transId=" + transId));

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onExecuteWrite(device, transId, execWrite == 1));
    }

    void onResponseSendCompletedFromNative(int status, int attrHandle) {
        Log.d(TAG, "onResponseSendCompleted() handle=" + attrHandle);
    }

    void onNotificationSentFromNative(int connId, int status) {
        Log.v(TAG, "onNotificationSent() connId=" + connId + ", status=" + status);

        BluetoothDevice device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            callbackToApp(() -> app.callback.onNotificationSent(device, status));
        } else {
            int queuedStatus = status;
            if (queuedStatus == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                queuedStatus = BluetoothGatt.GATT_SUCCESS;
            }
            app.queueCallback(new CallbackInfo(device, queuedStatus));
        }
    }

    void onServerCongestionFromNative(int connId, boolean congested) {
        Log.d(TAG, "onServerCongestion() - connId=" + connId + ", congested=" + congested);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.isCongested = congested;
        while (!app.isCongested) {
            CallbackInfo callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) {
                return;
            }
            callbackToApp(
                    () ->
                            app.callback.onNotificationSent(
                                    callbackInfo.device(), callbackInfo.status()));
        }
    }

    void onMtuChangedFromNative(int connId, int mtu) {
        Log.d(TAG, "onMtuChanged() - connId=" + connId + ", mtu=" + mtu);

        BluetoothDevice device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onMtuChanged(device, mtu));
    }

    /**************************************************************************
     * GATT Service functions - SERVER
     *************************************************************************/

    void registerServer(
            UUID uuid,
            IBluetoothGattServerCallback callback,
            boolean eattSupport,
            AttributionSource source) {
        Log.d(TAG, "registerServer() - UUID=" + uuid);
        mServerMap.add(uuid, callback, this, source);
        mNativeInterface.gattServerRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), eattSupport);
    }

    void unregisterServer(IBluetoothGattServerCallback callback) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "unregisterServer(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;

        Log.d(TAG, "unregisterServer() - serverIf=" + serverIf);

        deleteServices(serverIf);

        mServerMap.remove(serverIf, ContextMap.RemoveReason.REASON_UNREGISTER_SERVER);
        mNativeInterface.gattServerUnregisterApp(serverIf);
    }

    void serverConnect(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport,
            AttributionSource source) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverConnect(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Log.d(TAG, "serverConnect() - device=" + device);

        logServerForegroundInfo(source.getUid(), isDirect);

        mNativeInterface.gattServerConnect(serverIf, device, addressType, isDirect, transport);
    }

    void serverDisconnect(IBluetoothGattServerCallback callback, BluetoothDevice device) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverDisconnect(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Integer connId = mServerMap.connIdByDevice(serverIf, device);
        Log.d(TAG, "serverDisconnect() - device=" + device + ", connId=" + connId);

        mNativeInterface.gattServerDisconnect(serverIf, device, connId != null ? connId : 0);
    }

    void serverSetPreferredPhy(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int txPhy,
            int rxPhy,
            int phyOptions) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverSetPreferredPhy(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Integer connId = mServerMap.connIdByDevice(serverIf, device);
        if (connId == null) {
            Log.d(TAG, "serverSetPreferredPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "serverSetPreferredPhy() - device=" + device + ", connId=" + connId);
        mNativeInterface.gattServerSetPreferredPhy(serverIf, device, txPhy, rxPhy, phyOptions);
    }

    void serverReadPhy(IBluetoothGattServerCallback callback, BluetoothDevice device) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverReadPhy(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Integer connId = mServerMap.connIdByDevice(serverIf, device);
        if (connId == null) {
            Log.d(TAG, "serverReadPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "serverReadPhy() - device=" + device + ", connId=" + connId);
        mNativeInterface.gattServerReadPhy(serverIf, device);
    }

    void addService(IBluetoothGattServerCallback callback, BluetoothGattService service) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "addService(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Log.d(TAG, "addService() - uuid=" + service.getUuid());

        List<GattDbElement> db = new ArrayList<>();

        if (service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
            db.add(GattDbElement.createPrimaryService(service.getUuid()));
        } else {
            db.add(GattDbElement.createSecondaryService(service.getUuid()));
        }

        for (BluetoothGattService includedService : service.getIncludedServices()) {
            int inclSrvcHandle = includedService.getInstanceId();

            if (mHandleMap.checkServiceExists(includedService.getUuid(), inclSrvcHandle)) {
                db.add(GattDbElement.createIncludedService(inclSrvcHandle));
            } else {
                Log.e(
                        TAG,
                        "included service with UUID " + includedService.getUuid() + " not found!");
            }
        }

        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            int permission =
                    ((characteristic.getKeySize() - 7) << 12) + characteristic.getPermissions();
            db.add(
                    GattDbElement.createCharacteristic(
                            characteristic.getUuid(), characteristic.getProperties(), permission));

            for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                permission =
                        ((characteristic.getKeySize() - 7) << 12) + descriptor.getPermissions();
                db.add(GattDbElement.createDescriptor(descriptor.getUuid(), permission));
            }
        }

        mNativeInterface.gattServerAddService(serverIf, db);
    }

    void removeService(IBluetoothGattServerCallback callback, int handle) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "removeService(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Log.d(TAG, "removeService() - handle=" + handle);
        mNativeInterface.gattServerDeleteService(serverIf, handle);
    }

    void clearServices(IBluetoothGattServerCallback callback) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "clearServices(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Log.d(TAG, "clearServices()");
        deleteServices(serverIf);
    }

    void sendResponse(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int requestId,
            int status,
            int offset,
            byte[] value) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "sendResponse(" + callback + "): App not registered");
            return;
        }
        int serverIf = serverApp.id;
        Log.v(TAG, "sendResponse() - device=" + device + ", requestId=" + requestId);

        int handle = 0;
        Integer connId = 0;

        HandleMap.RequestData requestData = mHandleMap.getRequestDataByRequestId(requestId);
        if (requestData != null) {
            handle = requestData.handle();
            connId = requestData.connId();
        } else {
            connId = mServerMap.connIdByDevice(serverIf, device);
        }

        mNativeInterface.gattServerSendResponse(
                serverIf,
                connId != null ? connId : 0,
                requestId,
                (byte) status,
                handle,
                offset,
                value,
                (byte) 0);
        mHandleMap.deleteRequest(requestId);
    }

    int sendNotification(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int handle,
            boolean confirm,
            byte[] value) {
        ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "sendNotification(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        int serverIf = serverApp.id;
        Log.v(TAG, "sendNotification() - device=" + device + " handle=" + handle);

        Integer connId = mServerMap.connIdByDevice(serverIf, device);
        if (connId == null || connId == 0) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }

        if (confirm) {
            mNativeInterface.gattServerSendIndication(serverIf, handle, connId, value);
        } else {
            mNativeInterface.gattServerSendNotification(serverIf, handle, connId, value);
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    /**************************************************************************
     * Binder functions
     *************************************************************************/

    public IBinder getBluetoothAdvertise() {
        return mAdvertiseManager.getBinder();
    }

    public IBinder getDistanceMeasurement() {
        return mDistanceMeasurementManager.getBinder();
    }

    /**************************************************************************
     * Private functions
     *************************************************************************/

    private static boolean isHidSrvcUuid(final UUID uuid) {
        return HID_SERVICE_UUID.equals(uuid);
    }

    static boolean isHidCharUuid(final UUID uuid) {
        for (UUID hidUuid : HID_UUIDS) {
            if (hidUuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAndroidTvRemoteSrvcUuid(final UUID uuid) {
        return ANDROID_TV_REMOTE_SERVICE_UUID.equals(uuid);
    }

    private static boolean isFidoSrvcUuid(final UUID uuid) {
        return FIDO_SERVICE_UUID.equals(uuid);
    }

    private static boolean isLeAudioSrvcUuid(final UUID uuid) {
        for (UUID leAudioUuid : LE_AUDIO_SERVICE_UUIDS) {
            if (leAudioUuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAndroidHeadtrackerSrvcUuid(final UUID uuid) {
        return HidHostService.ANDROID_HEADTRACKER_UUID.getUuid().equals(uuid);
    }

    private static boolean isRestrictedSrvcUuid(final UUID uuid) {
        return isFidoSrvcUuid(uuid)
                || isAndroidTvRemoteSrvcUuid(uuid)
                || isLeAudioSrvcUuid(uuid)
                || isAndroidHeadtrackerSrvcUuid(uuid);
    }

    private int getDeviceType(BluetoothDevice device) {
        int type = mNativeInterface.gattClientGetDeviceType(device);
        Log.d(TAG, "getDeviceType() - device=" + device + ", type=" + type);
        return type;
    }

    private void logClientForegroundInfo(int uid, boolean isDirect) {
        String packageName = mPackageManager.getPackagesForUid(uid)[0];
        int importance = mActivityManager.getPackageImportance(packageName);
        if (importance == IMPORTANCE_FOREGROUND_SERVICE) {
            MetricsLogger.getInstance()
                    .count(
                            isDirect
                                    ? BluetoothProtoEnums
                                            .GATT_CLIENT_CONNECT_IS_DIRECT_IN_FOREGROUND
                                    : BluetoothProtoEnums
                                            .GATT_CLIENT_CONNECT_IS_AUTOCONNECT_IN_FOREGROUND,
                            1);
        } else {
            MetricsLogger.getInstance()
                    .count(
                            isDirect
                                    ? BluetoothProtoEnums
                                            .GATT_CLIENT_CONNECT_IS_DIRECT_NOT_IN_FOREGROUND
                                    : BluetoothProtoEnums
                                            .GATT_CLIENT_CONNECT_IS_AUTOCONNECT_NOT_IN_FOREGROUND,
                            1);
        }
    }

    private void logServerForegroundInfo(int uid, boolean isDirect) {
        String packageName = mPackageManager.getPackagesForUid(uid)[0];
        int importance = mActivityManager.getPackageImportance(packageName);
        if (importance == IMPORTANCE_FOREGROUND_SERVICE) {
            MetricsLogger.getInstance()
                    .count(
                            isDirect
                                    ? BluetoothProtoEnums
                                            .GATT_SERVER_CONNECT_IS_DIRECT_IN_FOREGROUND
                                    : BluetoothProtoEnums
                                            .GATT_SERVER_CONNECT_IS_AUTOCONNECT_IN_FOREGROUND,
                            1);
        } else {
            MetricsLogger.getInstance()
                    .count(
                            isDirect
                                    ? BluetoothProtoEnums
                                            .GATT_SERVER_CONNECT_IS_DIRECT_NOT_IN_FOREGROUND
                                    : BluetoothProtoEnums
                                            .GATT_SERVER_CONNECT_IS_AUTOCONNECT_NOT_IN_FOREGROUND,
                            1);
        }
    }

    private void stopNextService(int serverIf, int status) {
        Log.d(TAG, "stopNextService() - serverIf=" + serverIf + ", status=" + status);

        if (status != 0) {
            return;
        }
        List<HandleMap.Entry> entries = mHandleMap.getEntries();
        for (HandleMap.Entry entry : entries) {
            if (entry.mType != HandleMap.Type.SERVICE
                    || entry.mServerIf != serverIf
                    || !entry.mStarted) {
                continue;
            }

            mNativeInterface.gattServerStopService(serverIf, entry.mHandle);
            return;
        }
    }

    private void deleteServices(int serverIf) {
        Log.d(TAG, "deleteServices() - serverIf=" + serverIf);

        /*
         * Figure out which handles to delete.
         * The handles are copied into a new list to avoid race conditions.
         */
        List<Integer> handleList = new ArrayList<>();
        List<HandleMap.Entry> entries = mHandleMap.getEntries();
        for (HandleMap.Entry entry : entries) {
            if (entry.mType != HandleMap.Type.SERVICE || entry.mServerIf != serverIf) {
                continue;
            }
            handleList.add(entry.mHandle);
        }

        /* Now actually delete the services.... */
        for (Integer handle : handleList) {
            mNativeInterface.gattServerDeleteService(serverIf, handle);
        }
    }

    void dumpRegisterId(StringBuilder sb) {
        if (mScanController != null) {
            mScanController.dumpRegisterId(sb);
        }
        sb.append("  Client:\n");
        for (Integer appId : mClientMap.getAllAppsIds()) {
            ContextMap.App app = mClientMap.getById(appId);
            println(
                    sb,
                    "    app_if: "
                            + appId
                            + ", appName: "
                            + app.packageName
                            + (app.attributionTag == null ? "" : ", tag: " + app.attributionTag));
            List<ContextMap.Connection> clientConnections = mClientMap.getConnectionByApp(appId);
            for (ContextMap.Connection connection : clientConnections) {
                println(sb, "        " + connection);
            }
        }
        sb.append("  Server:\n");
        for (Integer appId : mServerMap.getAllAppsIds()) {
            ContextMap.App app = mServerMap.getById(appId);
            println(
                    sb,
                    "    app_if: "
                            + appId
                            + ", appName: "
                            + app.packageName
                            + (app.attributionTag == null ? "" : ", tag: " + app.attributionTag));
            List<ContextMap.Connection> serverConnections = mServerMap.getConnectionByApp(appId);
            for (ContextMap.Connection connection : serverConnections) {
                println(sb, "        " + connection);
            }
        }
        sb.append("\n\n");
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        sb.append("\nRegistered App\n");
        dumpRegisterId(sb);

        if (mScanController != null) {
            mScanController.dump(sb);
        }

        sb.append("GATT Advertiser Map\n");
        mAdvertiseManager.dump(sb);

        sb.append("GATT Client Map\n");
        mClientMap.dump(sb);

        sb.append("GATT Server Map\n");
        mServerMap.dump(sb);

        sb.append("GATT Handle Map\n");
        mHandleMap.dump(sb);
    }

    private void statsLogAppPackage(BluetoothDevice device, int applicationUid, int sessionIndex) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_GATT_APP_INFO,
                sessionIndex,
                mAdapterService.getMetricId(device),
                applicationUid);
        Log.d(
                TAG,
                "Gatt Logging:"
                        + (" metric_id=" + mAdapterService.getMetricId(device))
                        + (", app_uid=" + applicationUid));
    }

    private void statsLogGattConnectionStateChange(
            int profile,
            BluetoothDevice device,
            int sessionIndex,
            int connectionState,
            int connectionStatus) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_CONNECTION_STATE_CHANGED,
                connectionState,
                0 /* deprecated */,
                profile,
                new byte[0],
                mAdapterService.getMetricId(device),
                sessionIndex,
                connectionStatus);
        Log.d(
                TAG,
                "Gatt Logging:"
                        + (" metric_id=" + mAdapterService.getMetricId(device))
                        + (", session_index=" + sessionIndex)
                        + (", connectionState=" + connectionState)
                        + (", connectionStatus=" + connectionStatus));
    }

    private static int connectionStatusToState(int status) {
        return switch (status) {
            // GATT_SUCCESS
            case 0x00 -> BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS;
            // GATT_CONNECTION_TIMEOUT
            case 0x93 ->
                    BluetoothStatsLog
                            .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__CONNECTION_TIMEOUT;
            // For now all other errors are bucketed together.
            default -> BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__FAIL;
        };
    }

    /**************************************************************************
     * GATT Test functions
     *************************************************************************/
    void gattTestCommand(
            int command, UUID uuid1, String bda1, int p1, int p2, int p3, int p4, int p5) {
        if (bda1 == null) {
            bda1 = "00:00:00:00:00:00";
        }
        if (uuid1 != null) {
            mNativeInterface.gattTest(
                    command,
                    uuid1.getLeastSignificantBits(),
                    uuid1.getMostSignificantBits(),
                    bda1,
                    p1,
                    p2,
                    p3,
                    p4,
                    p5);
        } else {
            mNativeInterface.gattTest(command, 0, 0, bda1, p1, p2, p3, p4, p5);
        }
    }
}
