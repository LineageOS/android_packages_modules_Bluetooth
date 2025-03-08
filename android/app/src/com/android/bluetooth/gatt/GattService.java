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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothUtils.toAnonymizedAddress;

import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;
import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.util.AttributionSourceUtil.getLastAttributionTag;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothGatt;
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
import android.os.ParcelUuid;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    @VisibleForTesting static final int GATT_CLIENT_LIMIT_PER_APP = 32;

    @Nullable public final ScanController mScanController;

    /** This is only used when Flags.onlyStartScanDuringBleOn() is true. */
    private static GattService sGattService;

    /** List of our registered clients. */
    @VisibleForTesting ContextMap<IBluetoothGattCallback> mClientMap = new ContextMap<>();

    /** List of our registered server apps. */
    @VisibleForTesting ContextMap<IBluetoothGattServerCallback> mServerMap = new ContextMap<>();

    /** Server handle map. */
    private final HandleMap mHandleMap = new HandleMap();

    /**
     * Set of restricted (which require a BLUETOOTH_PRIVILEGED permission) handles per connectionId.
     */
    @VisibleForTesting final Map<Integer, Set<Integer>> mRestrictedHandles = new HashMap<>();

    /**
     * HashMap used to synchronize writeCharacteristic calls mapping remote device address to
     * available permit (connectId or -1).
     */
    private final HashMap<String, Integer> mPermits = new HashMap<>();

    private final BluetoothAdapter mAdapter;
    private final AdapterService mAdapterService;
    private final AdvertiseManager mAdvertiseManager;
    private final GattNativeInterface mNativeInterface;
    private final CompanionDeviceManager mCompanionDeviceManager;
    private final DistanceMeasurementManager mDistanceMeasurementManager;
    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final HandlerThread mHandlerThread;

    public GattService(AdapterService adapterService) {
        super(requireNonNull(adapterService));
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdapterService = adapterService;
        mActivityManager = requireNonNull(getSystemService(ActivityManager.class));
        mPackageManager = requireNonNull(mAdapterService.getPackageManager());
        mCompanionDeviceManager = requireNonNull(getSystemService(CompanionDeviceManager.class));

        Settings.Global.putInt(
                getContentResolver(), "bluetooth_sanitized_exposure_notification_supported", 1);

        mNativeInterface = GattObjectsFactory.getInstance().getNativeInterface();
        mNativeInterface.init(this);

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

    /** Reliable write queue */
    @VisibleForTesting Set<String> mReliableQueue = new HashSet<>();

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothGattBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Gatt Service");

        if (Flags.onlyStartScanDuringBleOn() && sGattService == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }
        if (Flags.onlyStartScanDuringBleOn()) {
            setGattService(null);
        }
        if (mScanController != null) {
            mScanController.stop();
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

    @Override
    protected void setTestModeEnabled(boolean enableTestMode) {
        if (mScanController != null) {
            mScanController.setTestModeEnabled(enableTestMode);
        }
    }

    // Suppressed because we are conditionally enforcing
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void permissionCheck(UUID characteristicUuid) {
        if (!isHidCharUuid(characteristicUuid)) {
            return;
        }
        this.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
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

    class ServerDeathRecipient implements IBinder.DeathRecipient {
        int mAppIf;
        private final String mPackageName;

        ServerDeathRecipient(int appIf, String packageName) {
            mAppIf = appIf;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(
                    TAG,
                    "Binder is dead - unregistering server (" + mPackageName + " " + mAppIf + ")!");
            unregisterServer(mAppIf, getAttributionSource());
        }
    }

    class ClientDeathRecipient implements IBinder.DeathRecipient {
        int mAppIf;
        private final String mPackageName;

        ClientDeathRecipient(int appIf, String packageName) {
            mAppIf = appIf;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(
                    TAG,
                    "Binder is dead - unregistering client (" + mPackageName + " " + mAppIf + ")!");
            unregisterClient(
                    mAppIf, getAttributionSource(), ContextMap.RemoveReason.REASON_BINDER_DIED);
        }
    }

    /** Handlers for incoming service calls */
    @VisibleForTesting
    static class BluetoothGattBinder extends IBluetoothGatt.Stub implements IProfileServiceBinder {
        private GattService mService;

        BluetoothGattBinder(GattService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        private GattService getService() {
            // Cache mService because it can change while getService is called
            GattService service = mService;

            if (service == null || !service.isAvailable()) {
                Log.e(TAG, "getService() - Service requested, but not available!");
                return null;
            }

            return service;
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states, source);
        }

        @Override
        public void registerClient(
                ParcelUuid uuid,
                IBluetoothGattCallback callback,
                boolean eattSupport,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.registerClient(uuid.getUuid(), callback, eattSupport, source);
        }

        @Override
        public void unregisterClient(int clientIf, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.unregisterClient(
                    clientIf, source, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        }

        @Override
        public void clientConnect(
                int clientIf,
                String address,
                int addressType,
                boolean isDirect,
                int transport,
                boolean opportunistic,
                int phy,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientConnect(
                    clientIf,
                    address,
                    addressType,
                    isDirect,
                    transport,
                    opportunistic,
                    phy,
                    source);
        }

        @Override
        public void clientDisconnect(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientDisconnect(clientIf, address, source);
        }

        @Override
        public void clientSetPreferredPhy(
                int clientIf,
                String address,
                int txPhy,
                int rxPhy,
                int phyOptions,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientSetPreferredPhy(clientIf, address, txPhy, rxPhy, phyOptions, source);
        }

        @Override
        public void clientReadPhy(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientReadPhy(clientIf, address, source);
        }

        @Override
        public void refreshDevice(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.refreshDevice(clientIf, address, source);
        }

        @Override
        public void discoverServices(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.discoverServices(clientIf, address, source);
        }

        @Override
        public void discoverServiceByUuid(
                int clientIf, String address, ParcelUuid uuid, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.discoverServiceByUuid(clientIf, address, uuid.getUuid(), source);
        }

        @Override
        public void readCharacteristic(
                int clientIf, String address, int handle, int authReq, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readCharacteristic(clientIf, address, handle, authReq, source);
        }

        @Override
        public void readUsingCharacteristicUuid(
                int clientIf,
                String address,
                ParcelUuid uuid,
                int startHandle,
                int endHandle,
                int authReq,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readUsingCharacteristicUuid(
                    clientIf, address, uuid.getUuid(), startHandle, endHandle, authReq, source);
        }

        @Override
        public int writeCharacteristic(
                int clientIf,
                String address,
                int handle,
                int writeType,
                int authReq,
                byte[] value,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            }
            return service.writeCharacteristic(
                    clientIf, address, handle, writeType, authReq, value, source);
        }

        @Override
        public void readDescriptor(
                int clientIf, String address, int handle, int authReq, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readDescriptor(clientIf, address, handle, authReq, source);
        }

        @Override
        public int writeDescriptor(
                int clientIf,
                String address,
                int handle,
                int authReq,
                byte[] value,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            }
            return service.writeDescriptor(clientIf, address, handle, authReq, value, source);
        }

        @Override
        public void beginReliableWrite(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.beginReliableWrite(clientIf, address, source);
        }

        @Override
        public void endReliableWrite(
                int clientIf, String address, boolean execute, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.endReliableWrite(clientIf, address, execute, source);
        }

        @Override
        public void registerForNotification(
                int clientIf,
                String address,
                int handle,
                boolean enable,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.registerForNotification(clientIf, address, handle, enable, source);
        }

        @Override
        public void readRemoteRssi(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readRemoteRssi(clientIf, address, source);
        }

        @Override
        public void configureMTU(int clientIf, String address, int mtu, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.configureMTU(clientIf, address, mtu, source);
        }

        @Override
        public void connectionParameterUpdate(
                int clientIf, String address, int connectionPriority, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.connectionParameterUpdate(clientIf, address, connectionPriority, source);
        }

        @Override
        public void leConnectionUpdate(
                int clientIf,
                String address,
                int minConnectionInterval,
                int maxConnectionInterval,
                int peripheralLatency,
                int supervisionTimeout,
                int minConnectionEventLen,
                int maxConnectionEventLen,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.leConnectionUpdate(
                    clientIf,
                    address,
                    minConnectionInterval,
                    maxConnectionInterval,
                    peripheralLatency,
                    supervisionTimeout,
                    minConnectionEventLen,
                    maxConnectionEventLen,
                    source);
        }

        @Override
        public int subrateModeRequest(
                int clientIf, BluetoothDevice device, int subrateMode, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "subrateModeRequest")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }

            if (!Utils.checkConnectPermissionForDataDelivery(
                    service, source, "GattService subrateModeRequest")) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }

            Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                    service, service.mCompanionDeviceManager, source, device);

            if (subrateMode < BluetoothGatt.SUBRATE_REQUEST_MODE_BALANCED
                    || subrateMode > BluetoothGatt.SUBRATE_REQUEST_MODE_LOW_POWER) {
                throw new IllegalArgumentException("Subrate Mode not within valid range");
            }

            requireNonNull(device);
            String address = device.getAddress();
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                throw new IllegalArgumentException("Invalid device address: " + address);
            }

            return service.subrateModeRequest(clientIf, device, subrateMode);
        }

        @Override
        public void registerServer(
                ParcelUuid uuid,
                IBluetoothGattServerCallback callback,
                boolean eattSupport,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.registerServer(uuid.getUuid(), callback, eattSupport, source);
        }

        @Override
        public void unregisterServer(int serverIf, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.unregisterServer(serverIf, source);
        }

        @Override
        public void serverConnect(
                int serverIf,
                String address,
                int addressType,
                boolean isDirect,
                int transport,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverConnect(serverIf, address, addressType, isDirect, transport, source);
        }

        @Override
        public void serverDisconnect(int serverIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverDisconnect(serverIf, address, source);
        }

        @Override
        public void serverSetPreferredPhy(
                int serverIf,
                String address,
                int txPhy,
                int rxPhy,
                int phyOptions,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverSetPreferredPhy(serverIf, address, txPhy, rxPhy, phyOptions, source);
        }

        @Override
        public void serverReadPhy(int clientIf, String address, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverReadPhy(clientIf, address, source);
        }

        @Override
        public void addService(int serverIf, BluetoothGattService svc, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.addService(serverIf, svc, source);
        }

        @Override
        public void removeService(int serverIf, int handle, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.removeService(serverIf, handle, source);
        }

        @Override
        public void clearServices(int serverIf, AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clearServices(serverIf, source);
        }

        @Override
        public void sendResponse(
                int serverIf,
                String address,
                int requestId,
                int status,
                int offset,
                byte[] value,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.sendResponse(serverIf, address, requestId, status, offset, value, source);
        }

        @Override
        public int sendNotification(
                int serverIf,
                String address,
                int handle,
                boolean confirm,
                byte[] value,
                AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            }
            return service.sendNotification(serverIf, address, handle, confirm, value, source);
        }

        @Override
        public void disconnectAll(AttributionSource source) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.disconnectAll(source);
        }
    }

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onClientRegistered(int status, int clientIf, long uuidLsb, long uuidMsb) {
        UUID uuid = new UUID(uuidMsb, uuidLsb);
        Log.d(TAG, "onClientRegistered() - UUID=" + uuid + ", clientIf=" + clientIf);
        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        if (status != 0) {
            mClientMap.remove(uuid, ContextMap.RemoveReason.REASON_REGISTER_FAILED);
        } else {
            app.id = clientIf;
            app.linkToDeath(new ClientDeathRecipient(clientIf, app.name));
        }
        callbackToApp(() -> app.callback.onClientRegistered(status, clientIf));
    }

    void onConnected(int clientIf, int connId, int status, String address) {
        Log.d(
                TAG,
                "onConnected() - clientIf="
                        + clientIf
                        + ", connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status);
        int connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        if (status != 0) {
            mAdapterService.notifyGattClientConnectFailed(clientIf, getDevice(address));
        } else {
            mClientMap.addConnection(clientIf, connId, address);

            // Allow one writeCharacteristic operation at a time for each connected remote device.
            synchronized (mPermits) {
                Log.d(
                        TAG,
                        "onConnected() - adding permit for address="
                                + toAnonymizedAddress(address));
                mPermits.putIfAbsent(address, -1);
            }
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT, address, clientIf, connectionState, status);
        if (app == null) {
            return;
        }
        boolean connected = status == BluetoothGatt.GATT_SUCCESS;
        callbackToApp(
                () -> app.callback.onClientConnectionState(status, clientIf, connected, address));
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        getDevice(address),
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
                        connectionStatusToState(status),
                        app.appUid);
    }

    void onDisconnected(int clientIf, int connId, int status, String address) {
        Log.d(
                TAG,
                "onDisconnected() - clientIf="
                        + clientIf
                        + ", connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address));
        BluetoothDevice device = getDevice(address);
        mClientMap.removeConnection(clientIf, connId);
        mAdapterService.notifyGattClientDisconnect(clientIf, device);
        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);

        mRestrictedHandles.remove(connId);

        // Remove AtomicBoolean representing permit if no other connections rely on this remote
        // device.
        if (!mClientMap.getConnectedDevices().contains(address)) {
            synchronized (mPermits) {
                Log.d(
                        TAG,
                        "onDisconnected() - removing permit for address="
                                + toAnonymizedAddress(address));
                mPermits.remove(address);
            }
        } else {
            synchronized (mPermits) {
                if (mPermits.get(address) == connId) {
                    Log.d(
                            TAG,
                            "onDisconnected() - set permit -1 for address="
                                    + toAnonymizedAddress(address));
                    mPermits.put(address, -1);
                }
            }
        }

        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                address,
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
        callbackToApp(
                () ->
                        app.callback.onClientConnectionState(
                                disconnectStatus, clientIf, false, address));
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS,
                        app.appUid);
    }

    void onClientPhyUpdate(int connId, int txPhy, int rxPhy, int status) {
        Log.d(TAG, "onClientPhyUpdate() - connId=" + connId + ", status=" + status);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyUpdate(address, txPhy, rxPhy, status));
    }

    void onClientPhyRead(int clientIf, String address, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onClientPhyRead() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", clientIf="
                        + clientIf);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.d(TAG, "onClientPhyRead() - no connection to " + toAnonymizedAddress(address));
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyRead(address, txPhy, rxPhy, status));
    }

    void onClientConnUpdate(int connId, int interval, int latency, int timeout, int status) {
        Log.d(TAG, "onClientConnUpdate() - connId=" + connId + ", status=" + status);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onConnectionUpdated(
                                address, interval, latency, timeout, status));
    }

    void onServiceChanged(int connId) {
        Log.d(TAG, "onServiceChanged - connId=" + connId);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onServiceChanged(address));
    }

    void onClientSubrateChange(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status) {
        Log.d(TAG, "onClientSubrateChange() - connId=" + connId + ", status=" + status);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onSubrateChange(
                                address, subrateFactor, latency, contNum, timeout, status));
    }

    void onServerPhyUpdate(int connId, int txPhy, int rxPhy, int status) {
        Log.d(TAG, "onServerPhyUpdate() - connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyUpdate(address, txPhy, rxPhy, status));
    }

    void onServerPhyRead(int serverIf, String address, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onServerPhyRead() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status);

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) {
            Log.d(TAG, "onServerPhyRead() - no connection to " + toAnonymizedAddress(address));
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onPhyRead(address, txPhy, rxPhy, status));
    }

    void onServerConnUpdate(int connId, int interval, int latency, int timeout, int status) {
        Log.d(TAG, "onServerConnUpdate() - connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onConnectionUpdated(
                                address, interval, latency, timeout, status));
    }

    void onServerSubrateChange(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status) {
        Log.d(TAG, "onServerSubrateChange() - connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onSubrateChange(
                                address, subrateFactor, latency, contNum, timeout, status));
    }

    GattDbElement getSampleGattDbElement() {
        return new GattDbElement();
    }

    void onGetGattDb(int connId, List<GattDbElement> db) {
        String address = mClientMap.addressByConnId(connId);

        Log.d(TAG, "onGetGattDb() - address=" + toAnonymizedAddress(address));

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
        callbackToApp(() -> app.callback.onSearchComplete(address, dbOut, 0 /* status */));
    }

    void onRegisterForNotifications(int connId, int status, int registered, int handle) {
        String address = mClientMap.addressByConnId(connId);

        Log.d(
                TAG,
                "onRegisterForNotifications() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", registered="
                        + registered
                        + ", handle="
                        + handle);
    }

    void onNotify(int connId, String address, int handle, boolean isNotify, byte[] data) {

        Log.v(
                TAG,
                "onNotify() - address="
                        + toAnonymizedAddress(address)
                        + ", handle="
                        + handle
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, app.name, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "onNotify() - permission check failed!");
            return;
        }
        callbackToApp(() -> app.callback.onNotify(address, handle, data));
    }

    void onReadCharacteristic(int connId, int status, int handle, byte[] data) {
        String address = mClientMap.addressByConnId(connId);

        Log.v(
                TAG,
                "onReadCharacteristic() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onCharacteristicRead(address, status, handle, data));
    }

    void onWriteCharacteristic(int connId, int status, int handle, byte[] data) {
        String address = mClientMap.addressByConnId(connId);
        synchronized (mPermits) {
            Log.d(
                    TAG,
                    "onWriteCharacteristic() - increasing permit for address="
                            + toAnonymizedAddress(address));
            mPermits.put(address, -1);
        }

        Log.v(
                TAG,
                "onWriteCharacteristic() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            callbackToApp(() -> app.callback.onCharacteristicWrite(address, status, handle, data));
        } else {
            int queuedStatus = status;
            if (queuedStatus == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                queuedStatus = BluetoothGatt.GATT_SUCCESS;
            }
            final ByteString value = ByteString.copyFrom(data);
            CallbackInfo callbackInfo = new CallbackInfo(address, queuedStatus, handle, value);
            app.queueCallback(callbackInfo);
        }
    }

    void onExecuteCompleted(int connId, int status) {
        String address = mClientMap.addressByConnId(connId);
        Log.v(
                TAG,
                "onExecuteCompleted() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onExecuteWrite(address, status));
    }

    void onReadDescriptor(int connId, int status, int handle, byte[] data) {
        String address = mClientMap.addressByConnId(connId);

        Log.v(
                TAG,
                "onReadDescriptor() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onDescriptorRead(address, status, handle, data));
    }

    void onWriteDescriptor(int connId, int status, int handle, byte[] data) {
        String address = mClientMap.addressByConnId(connId);

        Log.v(
                TAG,
                "onWriteDescriptor() - address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onDescriptorWrite(address, status, handle, data));
    }

    void onReadRemoteRssi(int clientIf, String address, int rssi, int status) {
        Log.d(
                TAG,
                "onReadRemoteRssi() - clientIf="
                        + clientIf
                        + " address="
                        + toAnonymizedAddress(address)
                        + ", rssi="
                        + rssi
                        + ", status="
                        + status);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onReadRemoteRssi(address, rssi, status));
    }

    void onConfigureMTU(int connId, int status, int mtu) {
        String address = mClientMap.addressByConnId(connId);

        Log.d(
                TAG,
                "onConfigureMTU() address="
                        + toAnonymizedAddress(address)
                        + ", status="
                        + status
                        + ", mtu="
                        + mtu);

        ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.callback.onConfigureMTU(address, mtu, status));
    }

    void onClientCongestion(int connId, boolean congested) {
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
                                    callbackInfo.address(),
                                    callbackInfo.status(),
                                    callbackInfo.handle(),
                                    callbackInfo.valueByteArray()));
        }
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    @RequiresPermission(BLUETOOTH_CONNECT)
    List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService getDevicesMatchingConnectionStates")) {
            return Collections.emptyList();
        }

        Map<BluetoothDevice, Integer> deviceStates = new HashMap<>();

        // Add paired LE devices
        BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            if (getDeviceType(device) != AbstractionLayer.BT_DEVICE_TYPE_BREDR) {
                deviceStates.put(device, STATE_DISCONNECTED);
            }
        }

        // Add connected deviceStates
        Set<String> connectedDevices = new HashSet<>();
        connectedDevices.addAll(mClientMap.getConnectedDevices());
        connectedDevices.addAll(mServerMap.getConnectedDevices());

        for (String address : connectedDevices) {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
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

    @RequiresPermission(BLUETOOTH_CONNECT)
    void disconnectAll(AttributionSource source) {
        Log.d(TAG, "disconnectAll()");
        Map<Integer, String> connMap = mClientMap.getConnectedMap();
        for (Map.Entry<Integer, String> entry : connMap.entrySet()) {
            Log.d(TAG, "disconnecting addr:" + entry.getValue());
            clientDisconnect(entry.getKey(), entry.getValue(), source);
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    public void unregAll(AttributionSource source) {
        for (Integer appId : mClientMap.getAllAppsIds()) {
            Log.d(TAG, "unreg:" + appId);
            unregisterClient(appId, source, ContextMap.RemoveReason.REASON_UNREGISTER_ALL);
        }
        for (Integer appId : mServerMap.getAllAppsIds()) {
            Log.d(TAG, "unreg:" + appId);
            unregisterServer(appId, source);
        }
    }

    /**************************************************************************
     * GATT Service functions - CLIENT
     *************************************************************************/

    @RequiresPermission(BLUETOOTH_CONNECT)
    void registerClient(
            UUID uuid,
            IBluetoothGattCallback callback,
            boolean eatt_support,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService registerClient")) {
            return;
        }
        if (Flags.gattClientDynamicAllocation()
                && mClientMap.countByAppUid(Binder.getCallingUid()) >= GATT_CLIENT_LIMIT_PER_APP) {
            Log.w(TAG, "registerClient() - failed due to too many clients");
            callbackToApp(() -> callback.onClientRegistered(BluetoothGatt.GATT_FAILURE, 0));
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
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), name, eatt_support);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void unregisterClient(int clientIf, AttributionSource source, ContextMap.RemoveReason reason) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService unregisterClient")) {
            return;
        }

        Log.d(TAG, "unregisterClient() - clientIf=" + clientIf);
        for (ContextMap.Connection conn : mClientMap.getConnectionByApp(clientIf)) {
            MetricsLogger.getInstance()
                    .logBluetoothEvent(
                            getDevice(conn.address),
                            BluetoothStatsLog
                                    .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__END,
                            source.getUid());
        }
        mClientMap.remove(clientIf, reason);
        mNativeInterface.gattClientUnregisterApp(clientIf);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void clientConnect(
            int clientIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int phy,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService clientConnect")) {
            return;
        }

        Log.d(
                TAG,
                "clientConnect() - address="
                        + toAnonymizedAddress(address)
                        + ", addressType="
                        + addressType
                        + ", isDirect="
                        + isDirect
                        + ", opportunistic="
                        + opportunistic
                        + ", phy="
                        + phy);
        statsLogAppPackage(address, source.getUid(), clientIf);

        logClientForegroundInfo(source.getUid(), isDirect);

        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                address,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_CONNECTING,
                -1);

        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        getDevice(address),
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
                        isDirect
                                ? BluetoothStatsLog
                                        .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__DIRECT_CONNECT
                                : BluetoothStatsLog
                                        .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__INDIRECT_CONNECT,
                        source.getUid());

        int preferredMtu = 0;

        // Some applications expect MTU to be exchanged immediately on connections
        String packageName = source.getPackageName();
        if (packageName != null) {
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
                        mAdapterService.notifyDirectLeGattClientConnect(
                                clientIf, getDevice(address));
                        break;
                    }
                }
            }
        }

        mNativeInterface.gattClientConnect(
                clientIf,
                address,
                addressType,
                isDirect,
                transport,
                opportunistic,
                phy,
                preferredMtu);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void clientDisconnect(int clientIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService clientDisconnect")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        Log.d(
                TAG,
                "clientDisconnect() - address="
                        + toAnonymizedAddress(address)
                        + ", connId="
                        + connId);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                address,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTING,
                -1);
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        getDevice(address),
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__START,
                        source.getUid());

        mAdapterService.notifyGattClientDisconnect(clientIf, getDevice(address));

        mNativeInterface.gattClientDisconnect(clientIf, address, connId != null ? connId : 0);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void clientSetPreferredPhy(
            int clientIf,
            String address,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService clientSetPreferredPhy")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.d(
                    TAG,
                    "clientSetPreferredPhy() - no connection to " + toAnonymizedAddress(address));
            return;
        }

        Log.d(
                TAG,
                "clientSetPreferredPhy() - address="
                        + toAnonymizedAddress(address)
                        + ", connId="
                        + connId);
        mNativeInterface.gattClientSetPreferredPhy(clientIf, address, txPhy, rxPhy, phyOptions);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void clientReadPhy(int clientIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService clientReadPhy")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.d(TAG, "clientReadPhy() - no connection to " + toAnonymizedAddress(address));
            return;
        }

        Log.d(
                TAG,
                "clientReadPhy() - address=" + toAnonymizedAddress(address) + ", connId=" + connId);
        mNativeInterface.gattClientReadPhy(clientIf, address);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    synchronized List<ParcelUuid> getRegisteredServiceUuids(AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService getRegisteredServiceUuids")) {
            return Collections.emptyList();
        }
        return mHandleMap.getEntries().stream()
                .map(entry -> new ParcelUuid(entry.uuid))
                .collect(Collectors.toList());
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    List<String> getConnectedDevices(AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService getConnectedDevices")) {
            return Collections.emptyList();
        }

        return Stream.concat(
                        mClientMap.getConnectedDevices().stream(),
                        mServerMap.getConnectedDevices().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void refreshDevice(int clientIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService refreshDevice")) {
            return;
        }

        Log.d(TAG, "refreshDevice() - address=" + toAnonymizedAddress(address));
        mNativeInterface.gattClientRefresh(clientIf, address);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void discoverServices(int clientIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService discoverServices")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        Log.d(
                TAG,
                "discoverServices() - address="
                        + toAnonymizedAddress(address)
                        + ", connId="
                        + connId);

        if (connId != null) {
            mNativeInterface.gattClientSearchService(connId, true, 0, 0);
        } else {
            Log.e(
                    TAG,
                    "discoverServices() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void discoverServiceByUuid(int clientIf, String address, UUID uuid, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService discoverServiceByUuid")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            mNativeInterface.gattClientDiscoverServiceByUuid(
                    connId, uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
        } else {
            Log.e(
                    TAG,
                    "discoverServiceByUuid() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void readCharacteristic(
            int clientIf, String address, int handle, int authReq, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService readCharacteristic")) {
            return;
        }

        Log.v(TAG, "readCharacteristic() - address=" + toAnonymizedAddress(address));

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(
                    TAG,
                    "readCharacteristic() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
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

    @RequiresPermission(BLUETOOTH_CONNECT)
    void readUsingCharacteristicUuid(
            int clientIf,
            String address,
            UUID uuid,
            int startHandle,
            int endHandle,
            int authReq,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService readUsingCharacteristicUuid")) {
            return;
        }

        Log.v(TAG, "readUsingCharacteristicUuid() - address=" + toAnonymizedAddress(address));

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(
                    TAG,
                    "readUsingCharacteristicUuid() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
            return;
        }

        try {
            permissionCheck(uuid);
        } catch (SecurityException ex) {
            String callingPackage = source.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readUsingCharacteristicUuid() - permission check failed!");
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

    @RequiresPermission(BLUETOOTH_CONNECT)
    int writeCharacteristic(
            int clientIf,
            String address,
            int handle,
            int writeType,
            int authReq,
            byte[] value,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService writeCharacteristic")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        Log.v(TAG, "writeCharacteristic() - address=" + toAnonymizedAddress(address));

        if (mReliableQueue.contains(address)) {
            writeType = 3; // Prepared write
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(
                    TAG,
                    "writeCharacteristic() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }
        permissionCheck(connId, handle);

        Log.d(TAG, "writeCharacteristic() - trying to acquire permit.");
        // Lock the thread until onCharacteristicWrite callback comes back.
        synchronized (mPermits) {
            Integer permit = mPermits.get(address);
            if (permit == null) {
                Log.d(TAG, "writeCharacteristic() -  atomicBoolean uninitialized!");
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
            }

            boolean success = (permit == -1);
            if (!success) {
                Log.d(TAG, "writeCharacteristic() - no permit available.");
                return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            }
            mPermits.put(address, connId);
        }

        mNativeInterface.gattClientWriteCharacteristic(connId, handle, writeType, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void readDescriptor(
            int clientIf, String address, int handle, int authReq, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService readDescriptor")) {
            return;
        }

        Log.v(TAG, "readDescriptor() - address=" + toAnonymizedAddress(address));

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(
                    TAG,
                    "readDescriptor() - No connection for " + toAnonymizedAddress(address) + "...");
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

    @RequiresPermission(BLUETOOTH_CONNECT)
    int writeDescriptor(
            int clientIf,
            String address,
            int handle,
            int authReq,
            byte[] value,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService writeDescriptor")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }
        Log.v(TAG, "writeDescriptor() - address=" + toAnonymizedAddress(address));

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(
                    TAG,
                    "writeDescriptor() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }
        permissionCheck(connId, handle);

        mNativeInterface.gattClientWriteDescriptor(connId, handle, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void beginReliableWrite(int clientIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService beginReliableWrite")) {
            return;
        }

        Log.d(TAG, "beginReliableWrite() - address=" + toAnonymizedAddress(address));
        mReliableQueue.add(address);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void endReliableWrite(int clientIf, String address, boolean execute, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService endReliableWrite")) {
            return;
        }

        Log.d(
                TAG,
                "endReliableWrite() - address="
                        + toAnonymizedAddress(address)
                        + " execute: "
                        + execute);
        mReliableQueue.remove(address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            mNativeInterface.gattClientExecuteWrite(connId, execute);
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void registerForNotification(
            int clientIf, String address, int handle, boolean enable, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService registerForNotification")) {
            return;
        }

        Log.d(
                TAG,
                "registerForNotification() - address="
                        + toAnonymizedAddress(address)
                        + " enable: "
                        + enable);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(
                    TAG,
                    "registerForNotification() - No connection for "
                            + toAnonymizedAddress(address)
                            + "...");
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

        mNativeInterface.gattClientRegisterForNotifications(clientIf, address, handle, enable);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void readRemoteRssi(int clientIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService readRemoteRssi")) {
            return;
        }

        Log.d(TAG, "readRemoteRssi() - address=" + toAnonymizedAddress(address));
        mNativeInterface.gattClientReadRemoteRssi(clientIf, address);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void configureMTU(int clientIf, String address, int mtu, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService configureMTU")) {
            return;
        }

        Log.d(TAG, "configureMTU() - address=" + toAnonymizedAddress(address) + " mtu=" + mtu);
        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            mNativeInterface.gattClientConfigureMTU(connId, mtu);
        } else {
            Log.e(
                    TAG,
                    "configureMTU() - No connection for " + toAnonymizedAddress(address) + "...");
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void connectionParameterUpdate(
            int clientIf, String address, int connectionPriority, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService connectionParameterUpdate")) {
            return;
        }

        CompanionManager manager = mAdapterService.getCompanionManager();

        int minInterval =
                manager.getGattConnParameters(
                        address, CompanionManager.GATT_CONN_INTERVAL_MIN, connectionPriority);
        int maxInterval =
                manager.getGattConnParameters(
                        address, CompanionManager.GATT_CONN_INTERVAL_MAX, connectionPriority);
        // Peripheral latency
        int latency =
                manager.getGattConnParameters(
                        address, CompanionManager.GATT_CONN_LATENCY, connectionPriority);

        int timeout = 500; // 5s. Link supervision timeout is measured in N * 10ms

        Log.d(
                TAG,
                "connectionParameterUpdate() - address="
                        + toAnonymizedAddress(address)
                        + " params="
                        + connectionPriority
                        + " interval="
                        + minInterval
                        + "/"
                        + maxInterval
                        + " timeout="
                        + timeout);

        mNativeInterface.gattConnectionParameterUpdate(
                clientIf, address, minInterval, maxInterval, latency, timeout, 0, 0);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void leConnectionUpdate(
            int clientIf,
            String address,
            int minInterval,
            int maxInterval,
            int peripheralLatency,
            int supervisionTimeout,
            int minConnectionEventLen,
            int maxConnectionEventLen,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService leConnectionUpdate")) {
            return;
        }

        Log.d(
                TAG,
                "leConnectionUpdate() - address="
                        + toAnonymizedAddress(address)
                        + ", intervals="
                        + minInterval
                        + "/"
                        + maxInterval
                        + ", latency="
                        + peripheralLatency
                        + ", timeout="
                        + supervisionTimeout
                        + "msec"
                        + ", min_ce="
                        + minConnectionEventLen
                        + ", max_ce="
                        + maxConnectionEventLen);

        mNativeInterface.gattConnectionParameterUpdate(
                clientIf,
                address,
                minInterval,
                maxInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen);
    }

    private int subrateModeRequest(int clientIf, BluetoothDevice device, int subrateMode) {
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
                device.getAddress(),
                subrateMin,
                subrateMax,
                maxLatency,
                contNumber,
                supervisionTimeout);
    }

    /**************************************************************************
     * Callback functions - SERVER
     *************************************************************************/

    void onServerRegistered(int status, int serverIf, long uuidLsb, long uuidMsb) {

        UUID uuid = new UUID(uuidMsb, uuidLsb);
        Log.d(TAG, "onServerRegistered() - UUID=" + uuid + ", serverIf=" + serverIf);
        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        app.id = serverIf;
        app.linkToDeath(new ServerDeathRecipient(serverIf, app.name));
        callbackToApp(() -> app.callback.onServerRegistered(status, serverIf));
    }

    void onServiceAdded(int status, int serverIf, List<GattDbElement> service) {
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

    void onServiceStopped(int status, int serverIf, int srvcHandle) {
        Log.d(TAG, "onServiceStopped() srvcHandle=" + srvcHandle + ", status=" + status);
        if (status == 0) {
            mHandleMap.setStarted(serverIf, srvcHandle, false);
        }
        stopNextService(serverIf, status);
    }

    void onServiceDeleted(int status, int serverIf, int srvcHandle) {
        Log.d(TAG, "onServiceDeleted() srvcHandle=" + srvcHandle + ", status=" + status);
        mHandleMap.deleteService(serverIf, srvcHandle);
    }

    void onClientConnected(String address, boolean connected, int connId, int serverIf) {

        Log.d(
                TAG,
                "onClientConnected() connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", connected="
                        + connected);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(serverIf);
        if (app == null) {
            return;
        }
        int connectionState;
        if (connected) {
            mServerMap.addConnection(serverIf, connId, address);
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
        } else {
            mServerMap.removeConnection(serverIf, connId);
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        }

        int applicationUid = -1;
        try {
            applicationUid =
                    this.getPackageManager().getPackageUid(app.name, PackageInfoFlags.of(0));
        } catch (NameNotFoundException e) {
            Log.d(TAG, "onClientConnected() uid_not_found=" + app.name);
        }

        callbackToApp(
                () -> app.callback.onServerConnectionState((byte) 0, serverIf, connected, address));
        statsLogAppPackage(address, applicationUid, serverIf);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT_SERVER, address, serverIf, connectionState, -1);
    }

    void onServerReadCharacteristic(
            String address, int connId, int transId, int handle, int offset, boolean isLong) {
        Log.v(
                TAG,
                "onServerReadCharacteristic() connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", handle="
                        + handle
                        + ", requestId="
                        + transId
                        + ", offset="
                        + offset);

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onCharacteristicReadRequest(
                                address, transId, offset, isLong, handle));
    }

    void onServerReadDescriptor(
            String address, int connId, int transId, int handle, int offset, boolean isLong) {
        Log.v(
                TAG,
                "onServerReadDescriptor() connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", handle="
                        + handle
                        + ", requestId="
                        + transId
                        + ", offset="
                        + offset);

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onDescriptorReadRequest(
                                address, transId, offset, isLong, handle));
    }

    void onServerWriteCharacteristic(
            String address,
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
                "onServerWriteCharacteristic() connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", handle="
                        + handle
                        + ", requestId="
                        + transId
                        + ", isPrep="
                        + isPrep
                        + ", offset="
                        + offset);

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onCharacteristicWriteRequest(
                                address, transId, offset, length, isPrep, needRsp, handle, data));
    }

    void onServerWriteDescriptor(
            String address,
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
                "onAttributeWrite() connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", handle="
                        + handle
                        + ", requestId="
                        + transId
                        + ", isPrep="
                        + isPrep
                        + ", offset="
                        + offset);

        HandleMap.Entry entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        mHandleMap.addRequest(connId, transId, handle);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.callback.onDescriptorWriteRequest(
                                address, transId, offset, length, isPrep, needRsp, handle, data));
    }

    void onExecuteWrite(String address, int connId, int transId, int execWrite) {
        Log.d(
                TAG,
                "onExecuteWrite() connId="
                        + connId
                        + ", address="
                        + toAnonymizedAddress(address)
                        + ", transId="
                        + transId);

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onExecuteWrite(address, transId, execWrite == 1));
    }

    void onResponseSendCompleted(int status, int attrHandle) {
        Log.d(TAG, "onResponseSendCompleted() handle=" + attrHandle);
    }

    void onNotificationSent(int connId, int status) {
        Log.v(TAG, "onNotificationSent() connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            callbackToApp(() -> app.callback.onNotificationSent(address, status));
        } else {
            int queuedStatus = status;
            if (queuedStatus == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                queuedStatus = BluetoothGatt.GATT_SUCCESS;
            }
            app.queueCallback(new CallbackInfo(address, queuedStatus));
        }
    }

    void onServerCongestion(int connId, boolean congested) {
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
                                    callbackInfo.address(), callbackInfo.status()));
        }
    }

    void onMtuChanged(int connId, int mtu) {
        Log.d(TAG, "onMtuChanged() - connId=" + connId + ", mtu=" + mtu);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.callback.onMtuChanged(address, mtu));
    }

    /**************************************************************************
     * GATT Service functions - SERVER
     *************************************************************************/

    @RequiresPermission(BLUETOOTH_CONNECT)
    void registerServer(
            UUID uuid,
            IBluetoothGattServerCallback callback,
            boolean eatt_support,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService registerServer")) {
            return;
        }

        Log.d(TAG, "registerServer() - UUID=" + uuid);
        mServerMap.add(uuid, callback, this, source);
        mNativeInterface.gattServerRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), eatt_support);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void unregisterServer(int serverIf, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService unregisterServer")) {
            return;
        }

        Log.d(TAG, "unregisterServer() - serverIf=" + serverIf);

        deleteServices(serverIf);

        mServerMap.remove(serverIf, ContextMap.RemoveReason.REASON_UNREGISTER_SERVER);
        mNativeInterface.gattServerUnregisterApp(serverIf);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void serverConnect(
            int serverIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService serverConnect")) {
            return;
        }

        Log.d(TAG, "serverConnect() - address=" + toAnonymizedAddress(address));

        logServerForegroundInfo(source.getUid(), isDirect);

        mNativeInterface.gattServerConnect(serverIf, address, addressType, isDirect, transport);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void serverDisconnect(int serverIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService serverDisconnect")) {
            return;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        Log.d(
                TAG,
                "serverDisconnect() - address="
                        + toAnonymizedAddress(address)
                        + ", connId="
                        + connId);

        mNativeInterface.gattServerDisconnect(serverIf, address, connId != null ? connId : 0);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void serverSetPreferredPhy(
            int serverIf,
            String address,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService serverSetPreferredPhy")) {
            return;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) {
            Log.d(
                    TAG,
                    "serverSetPreferredPhy() - no connection to " + toAnonymizedAddress(address));
            return;
        }

        Log.d(
                TAG,
                "serverSetPreferredPhy() - address="
                        + toAnonymizedAddress(address)
                        + ", connId="
                        + connId);
        mNativeInterface.gattServerSetPreferredPhy(serverIf, address, txPhy, rxPhy, phyOptions);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void serverReadPhy(int serverIf, String address, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService serverReadPhy")) {
            return;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) {
            Log.d(TAG, "serverReadPhy() - no connection to " + toAnonymizedAddress(address));
            return;
        }

        Log.d(
                TAG,
                "serverReadPhy() - address=" + toAnonymizedAddress(address) + ", connId=" + connId);
        mNativeInterface.gattServerReadPhy(serverIf, address);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void addService(int serverIf, BluetoothGattService service, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(this, source, "GattService addService")) {
            return;
        }

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

    @RequiresPermission(BLUETOOTH_CONNECT)
    void removeService(int serverIf, int handle, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService removeService")) {
            return;
        }

        Log.d(TAG, "removeService() - handle=" + handle);

        mNativeInterface.gattServerDeleteService(serverIf, handle);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void clearServices(int serverIf, AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService clearServices")) {
            return;
        }

        Log.d(TAG, "clearServices()");
        deleteServices(serverIf);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    void sendResponse(
            int serverIf,
            String address,
            int requestId,
            int status,
            int offset,
            byte[] value,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService sendResponse")) {
            return;
        }

        Log.v(
                TAG,
                "sendResponse() - address="
                        + toAnonymizedAddress(address)
                        + ", requestId="
                        + requestId);

        int handle = 0;
        Integer connId = 0;

        if (!Flags.gattServerRequestsFix()) {
            HandleMap.Entry entry = mHandleMap.getByRequestId(requestId);
            if (entry != null) {
                handle = entry.handle;
            }
            connId = mServerMap.connIdByAddress(serverIf, address);
        } else {
            HandleMap.RequestData requestData = mHandleMap.getRequestDataByRequestId(requestId);
            if (requestData != null) {
                handle = requestData.mHandle;
                connId = requestData.mConnId;
            } else {
                connId = mServerMap.connIdByAddress(serverIf, address);
            }
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

    @RequiresPermission(BLUETOOTH_CONNECT)
    int sendNotification(
            int serverIf,
            String address,
            int handle,
            boolean confirm,
            byte[] value,
            AttributionSource source) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, source, "GattService sendNotification")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        Log.v(
                TAG,
                "sendNotification() - address="
                        + toAnonymizedAddress(address)
                        + " handle="
                        + handle);

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
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

    private static boolean isHidCharUuid(final UUID uuid) {
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
        int type = mNativeInterface.gattClientGetDeviceType(device.getAddress());
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
            if (entry.type != HandleMap.TYPE_SERVICE
                    || entry.serverIf != serverIf
                    || !entry.started) {
                continue;
            }

            mNativeInterface.gattServerStopService(serverIf, entry.handle);
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
            if (entry.type != HandleMap.TYPE_SERVICE || entry.serverIf != serverIf) {
                continue;
            }
            handleList.add(entry.handle);
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
                            + app.name
                            + (app.attributionTag == null ? "" : ", tag: " + app.attributionTag));
        }
        sb.append("  Server:\n");
        for (Integer appId : mServerMap.getAllAppsIds()) {
            ContextMap.App app = mServerMap.getById(appId);
            println(
                    sb,
                    "    app_if: "
                            + appId
                            + ", appName: "
                            + app.name
                            + (app.attributionTag == null ? "" : ", tag: " + app.attributionTag));
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

    private void statsLogAppPackage(String address, int applicationUid, int sessionIndex) {
        BluetoothDevice device = mAdapter.getRemoteDevice(address);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_GATT_APP_INFO,
                sessionIndex,
                mAdapterService.getMetricId(device),
                applicationUid);
        Log.d(
                TAG,
                "Gatt Logging: metric_id="
                        + mAdapterService.getMetricId(device)
                        + ", app_uid="
                        + applicationUid);
    }

    private void statsLogGattConnectionStateChange(
            int profile,
            String address,
            int sessionIndex,
            int connectionState,
            int connectionStatus) {
        BluetoothDevice device = mAdapter.getRemoteDevice(address);
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
                "Gatt Logging: metric_id="
                        + mAdapterService.getMetricId(device)
                        + ", session_index="
                        + sessionIndex
                        + ", connection state="
                        + connectionState
                        + ", connection status="
                        + connectionStatus);
    }

    @Override
    public void dumpProto(BluetoothMetricsProto.BluetoothLog.Builder builder) {
        if (mScanController != null) {
            mScanController.dumpProto(builder);
        }
    }

    private BluetoothDevice getDevice(String address) {
        byte[] addressBytes = Utils.getBytesFromAddress(address);
        return mAdapterService.getDeviceFromByte(addressBytes);
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
