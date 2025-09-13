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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.Utils.getSystemClock;
import static com.android.bluetooth.Utils.transportToString;
import static com.android.bluetooth.gatt.GattUtil.gattStatusToString;
import static com.android.bluetooth.gatt.GattUtil.isAndroidHeadtrackerSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isAndroidTvRemoteSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isAppleNotificationCenterSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isFidoSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isHidCharUuid;
import static com.android.bluetooth.gatt.GattUtil.isHidSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isLeAudioSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.translateHciCode;
import static com.android.bluetooth.util.AttributionSourceUtils.getLastAttributionTag;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

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
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemProperties;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils.TimeProvider;
import com.android.bluetooth.btservice.AbstractionLayer;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.flags.Flags;
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
    private static final String TAG = GattUtil.TAG_PREFIX + GattService.class.getSimpleName();

    private final int[] mSubrateHighParameters;
    private final int[] mSubrateBalancedParameters;
    private final int[] mSubrateLowParameters;
    private final int[] mSubrateOffParameters;

    public static final int GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX = 0;
    public static final int GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX = 1;
    public static final int GATT_SUBRATE_LATENCY_INDEX = 2;
    public static final int GATT_SUBRATE_CONT_NUM_INDEX = 3;

    public static final int SUBRATE_LOW_MODE_SUBRATE_MIN_DEFAULT = 2;
    public static final int SUBRATE_LOW_MODE_SUBRATE_MAX_DEFAULT = 4;
    public static final int SUBRATE_LOW_MODE_LATENCY_DEFAULT = 0;
    public static final int SUBRATE_LOW_MODE_CONT_NUM_DEFAULT = 1;

    public static final int SUBRATE_BALANCED_MODE_SUBRATE_MIN_DEFAULT = 5;
    public static final int SUBRATE_BALANCED_MODE_SUBRATE_MAX_DEFAULT = 7;
    public static final int SUBRATE_BALANCED_MODE_LATENCY_DEFAULT = 0;
    public static final int SUBRATE_BALANCED_MODE_CONT_NUM_DEFAULT = 4;

    public static final int SUBRATE_HIGH_MODE_SUBRATE_MIN_DEFAULT = 8;
    public static final int SUBRATE_HIGH_MODE_SUBRATE_MAX_DEFAULT = 10;
    public static final int SUBRATE_HIGH_MODE_LATENCY_DEFAULT = 0;
    public static final int SUBRATE_HIGH_MODE_CONT_NUM_DEFAULT = 6;

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

    // Remote RSSI read throttle time
    private static final String RSSI_READ_THROTTLE_MS =
            "bluetooth.ble.rssi_read_throttle_ms.config";

    private static final int RSSI_READ_THROTTLE_MS_DEFAULT = 75;
    @VisibleForTesting static final int RSSI_READ_THROTTLE_MS_MAX = 200;
    @VisibleForTesting static final int GATT_CLIENT_LIMIT_PER_APP = 32;

    /** List of our registered clients. */
    ContextMap<IBluetoothGattCallback> mClientMap = new ContextMap<>();

    /** List of our registered server apps. */
    @VisibleForTesting ContextMap<IBluetoothGattServerCallback> mServerMap = new ContextMap<>();

    /** Reliable write queue */
    @VisibleForTesting Set<BluetoothDevice> mReliableQueue = new HashSet<>();

    /**
     * Set of restricted (which require a BLUETOOTH_PRIVILEGED permission) handles per connectionId.
     */
    final Map<Integer, Set<Integer>> mRestrictedHandles = new HashMap<>();

    /** Server handle map. */
    private final HandleMap mHandleMap = new HandleMap();

    /**
     * HashMap used to synchronize writeCharacteristic calls mapping remote device to available
     * permit (connectId or -1).
     */
    private final HashMap<BluetoothDevice, Integer> mPermits = new HashMap<>();

    private final Map<BluetoothDevice, Integer> mCachedPeripheralLatency = new HashMap<>();

    /** Record data class for RSSI caching */
    record RssiCacheEntry(long readTimeStamp, int rssi) {}

    /** HashMap used for storing RSSI cache entries */
    @VisibleForTesting final Map<String, RssiCacheEntry> mRssiCache = new HashMap<>();

    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final CompanionDeviceManager mCompanionDeviceManager;
    private final GattNativeInterface mNativeInterface;
    private final HandlerThread mHandlerThread;
    private final AdvertiseManager mAdvertiseManager;
    private final DistanceMeasurementManager mDistanceMeasurementManager;
    private final TimeProvider mTimeProvider;
    @VisibleForTesting int mRssiReadThrottleMs;

    public GattService(
            AdapterService adapterService,
            GattNativeInterface nativeInterface,
            AdvertiseManagerNativeInterface advertiseManagerNativeInterface,
            DistanceMeasurementNativeInterface distanceMeasurementNativeInterface,
            CompanionDeviceManager companionDeviceManager) {
        this(
                adapterService,
                nativeInterface,
                advertiseManagerNativeInterface,
                distanceMeasurementNativeInterface,
                companionDeviceManager,
                getSystemClock());
    }

    @VisibleForTesting
    GattService(
            AdapterService adapterService,
            GattNativeInterface nativeInterface,
            AdvertiseManagerNativeInterface advertiseManagerNativeInterface,
            DistanceMeasurementNativeInterface distanceMeasurementNativeInterface,
            CompanionDeviceManager companionDeviceManager,
            TimeProvider timeProvider) {
        super(BluetoothProfile.GATT, requireNonNull(adapterService));
        mActivityManager = requireNonNull(obtainSystemService(ActivityManager.class));
        mPackageManager = requireNonNull(mAdapterService.getPackageManager());
        mCompanionDeviceManager = companionDeviceManager;
        mTimeProvider = timeProvider;

        Settings.Global.putInt(
                getContentResolver(), "bluetooth_sanitized_exposure_notification_supported", 1);

        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new GattNativeInterface(mAdapterService, this));
        mNativeInterface.init();

        // Create a thread to handle LE operations
        mHandlerThread = new HandlerThread("Bluetooth LE");
        mHandlerThread.start();
        final var looper = mHandlerThread.getLooper();

        mAdvertiseManager =
                new AdvertiseManager(
                        mAdapterService, this, advertiseManagerNativeInterface, looper);

        mRssiReadThrottleMs =
                SystemProperties.getInt(RSSI_READ_THROTTLE_MS, RSSI_READ_THROTTLE_MS_DEFAULT);
        if (mRssiReadThrottleMs > RSSI_READ_THROTTLE_MS_MAX) {
            Log.w(
                    TAG,
                    "RSSI read throttle ms exceeds max, clipping to max: "
                            + RSSI_READ_THROTTLE_MS_MAX
                            + "ms");
            mRssiReadThrottleMs = RSSI_READ_THROTTLE_MS_MAX;
        }

        mDistanceMeasurementManager =
                new DistanceMeasurementManager(
                        mAdapterService, distanceMeasurementNativeInterface, looper);

        mSubrateLowParameters =
                new int[] {
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_low_min_subrate.config",
                            SUBRATE_LOW_MODE_SUBRATE_MIN_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_low_max_subrate.config",
                            SUBRATE_LOW_MODE_SUBRATE_MAX_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_low_latency.config",
                            SUBRATE_LOW_MODE_LATENCY_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_low_cont_number.config",
                            SUBRATE_LOW_MODE_CONT_NUM_DEFAULT),
                };
        mSubrateBalancedParameters =
                new int[] {
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_balanced_min_subrate.config",
                            SUBRATE_BALANCED_MODE_SUBRATE_MIN_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_balanced_max_subrate.config",
                            SUBRATE_BALANCED_MODE_SUBRATE_MAX_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_balanced_latency.config",
                            SUBRATE_BALANCED_MODE_LATENCY_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_balanced_cont_number.config",
                            SUBRATE_BALANCED_MODE_CONT_NUM_DEFAULT),
                };
        mSubrateHighParameters =
                new int[] {
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_high_min_subrate.config",
                            SUBRATE_HIGH_MODE_SUBRATE_MIN_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_high_max_subrate.config",
                            SUBRATE_HIGH_MODE_SUBRATE_MAX_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_high_latency.config",
                            SUBRATE_HIGH_MODE_LATENCY_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_high_cont_number.config",
                            SUBRATE_HIGH_MODE_CONT_NUM_DEFAULT),
                };
        mSubrateOffParameters = new int[] {1, 1, 0, 0};
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileGattEnabled().orElse(true);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new GattServiceBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        mClientMap.clear();
        mRestrictedHandles.clear();
        mServerMap.clear();
        mHandleMap.clear();
        mRssiCache.clear();
        mReliableQueue.clear();
        mNativeInterface.cleanup();
        mAdvertiseManager.cleanup();
        mDistanceMeasurementManager.cleanup();
        mHandlerThread.quit();
    }

    ContextMap<IBluetoothGattServerCallback> getServerMap() {
        return mServerMap;
    }

    CompanionDeviceManager getCompanionDeviceManager() {
        return mCompanionDeviceManager;
    }

    public AdvertiseManager getAdvertiseManager() {
        return mAdvertiseManager;
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

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onClientRegisteredFromNative(int status, int clientIf, UUID uuid) {
        Log.d(TAG, "onClientRegistered() - UUID=" + uuid + ", clientIf=" + clientIf);
        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            mClientMap.remove(uuid, ContextMap.RemoveReason.REASON_REGISTER_FAILED);
        } else {
            app.id = clientIf;
            app.linkToDeath(new ClientDeathRecipient(app.getCallback(), app.getPackageName()));
        }
        callbackToApp(() -> app.getCallback().onClientRegistered(status));
    }

    void onConnectedFromNative(
            int clientIf, int connId, int transport, int status, BluetoothDevice device) {
        Log.d(
                TAG,
                "onConnected() -"
                        + (" clientIf=" + clientIf)
                        + (" connId=" + connId)
                        + (" transport=" + transportToString(transport))
                        + (" status=" + gattStatusToString(status))
                        + (" device=" + device));
        int connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        if (status != BluetoothGatt.GATT_SUCCESS) {
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

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        statsLogGattConnectionStateChange(mProfileId, device, clientIf, connectionState, status);
        if (app == null) {
            return;
        }
        final var connected = status == BluetoothGatt.GATT_SUCCESS;
        callbackToApp(() -> app.getCallback().onClientConnectionState(status, connected, device));
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
                        connectionStatusToState(status),
                        app.mUid);
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
        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
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
                mProfileId,
                device,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED,
                status);
        if (app == null) {
            return;
        }
        final int disconnectStatus;
        if (status == 0x16 // HCI_ERR_CONN_CAUSE_LOCAL_HOST
                && mAdapterService.getKeyMissingCount(device) > 0) {
            // Native stack disconnects the link on detecting the bond loss. Native GATT would
            // return HCI_ERR_CONN_CAUSE_LOCAL_HOST in such case, but the apps should see
            // HCI_ERR_AUTH_FAILURE.
            Log.d(TAG, "onDisconnected() - disconnected due to bond loss for device=" + device);
            disconnectStatus = 0x05 /* HCI_ERR_AUTH_FAILURE */;
        } else {
            disconnectStatus = status;
        }
        callbackToApp(
                () -> app.getCallback().onClientConnectionState(disconnectStatus, false, device));
        MetricsLogger.getInstance()
                .logBluetoothEvent(
                        device,
                        BluetoothStatsLog
                                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS,
                        app.mUid);
    }

    void onClientPhyUpdateFromNative(int connId, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onClientPhyUpdate() -"
                        + (" connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onPhyUpdate(device, txPhy, rxPhy, status));
    }

    void onClientPhyReadFromNative(
            int clientIf, BluetoothDevice device, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onClientPhyRead() -"
                        + (" clientIf=" + clientIf)
                        + (", device=" + device)
                        + (", status=" + gattStatusToString(status)));

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "onClientPhyRead() - no connection to " + device);
            return;
        }

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onPhyRead(device, txPhy, rxPhy, status));
    }

    void onClientConnUpdateFromNative(
            int connId, int interval, int latency, int timeout, int status) {
        Log.d(
                TAG,
                "onClientConnUpdate() -"
                        + (" connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        mCachedPeripheralLatency.put(device, latency); // cache new peripheral latency
        callbackToApp(
                () ->
                        app.getCallback()
                                .onConnectionUpdated(device, interval, latency, timeout, status));
    }

    void onServiceChangedFromNative(int connId) {
        Log.d(TAG, "onServiceChanged - connId=" + connId);

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onServiceChanged(device));
    }

    void onClientSubrateChangeFromNative(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status) {
        Log.d(
                TAG,
                "onClientSubrateChange() -"
                        + (" connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        int subrateMode;

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (status == BluetoothStatusCodes.SUCCESS) {
            subrateMode = verifyGattSubratingMode(subrateFactor, latency, contNum);
        } else {
            subrateMode = BluetoothGatt.SUBRATE_MODE_NOT_UPDATED;
        }
        callbackToApp(
                () ->
                        app.getCallback()
                                .onSubrateChange(device, subrateMode, translateHciCode(status)));
    }

    GattDbElement getSampleGattDbElement() {
        return new GattDbElement();
    }

    void onGetGattDbFromNative(int connId, List<GattDbElement> db) {
        final var device = mClientMap.deviceByConnId(connId);
        Log.d(TAG, "onGetGattDb() - device=" + device);

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null || app.getCallback() == null) {
            Log.e(TAG, "onGetGattDb() - app or callback is null");
            return;
        }

        final List<BluetoothGattService> dbOut = new ArrayList<>();
        final Set<Integer> restrictedIds = new HashSet<>();

        BluetoothGattService currSrvc = null;
        BluetoothGattCharacteristic currChar = null;
        boolean isRestrictedSrvc = false;
        boolean isHidSrvc = false;
        boolean isRestrictedChar = false;

        for (GattDbElement el : db) {
            switch (el.type) {
                case GattDbElement.TYPE_PRIMARY_SERVICE, GattDbElement.TYPE_SECONDARY_SERVICE -> {
                    Log.d(TAG, "got service with UUID=" + el.uuid + " id: " + el.id);

                    currSrvc = new BluetoothGattService(el.uuid, el.id, el.type);
                    dbOut.add(currSrvc);
                    isRestrictedSrvc = isRestrictedSrvcUuid(el.uuid, device);
                    isHidSrvc = isHidSrvcUuid(el.uuid);
                    if (isRestrictedSrvc) {
                        restrictedIds.add(el.id);
                    }
                }
                case GattDbElement.TYPE_CHARACTERISTIC -> {
                    Log.d(TAG, "got characteristic with UUID=" + el.uuid + " id: " + el.id);

                    currChar = new BluetoothGattCharacteristic(el.uuid, el.id, el.properties, 0);
                    currSrvc.addCharacteristic(currChar);
                    isRestrictedChar = isRestrictedSrvc || (isHidSrvc && isHidCharUuid(el.uuid));
                    if (isRestrictedChar) {
                        restrictedIds.add(el.id);
                    }
                }
                case GattDbElement.TYPE_DESCRIPTOR -> {
                    Log.d(TAG, "got descriptor with UUID=" + el.uuid + " id: " + el.id);

                    currChar.addDescriptor(new BluetoothGattDescriptor(el.uuid, el.id, 0));
                    if (isRestrictedChar) {
                        restrictedIds.add(el.id);
                    }
                }
                case GattDbElement.TYPE_INCLUDED_SERVICE -> {
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
                }
                default -> {
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
        }

        if (!restrictedIds.isEmpty()) {
            mRestrictedHandles.put(connId, restrictedIds);
        }
        // Search is complete when there was error, or nothing more to process
        callbackToApp(() -> app.getCallback().onSearchComplete(device, dbOut, 0 /* status */));
    }

    void onRegisterForNotificationsFromNative(int connId, int status, int registered, int handle) {
        final var device = mClientMap.deviceByConnId(connId);
        Log.d(
                TAG,
                "onRegisterForNotifications() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status))
                        + (", registered=" + registered)
                        + (", handle=" + handle));
    }

    void onNotifyFromNative(
            int connId, BluetoothDevice device, int handle, boolean isNotify, byte[] data) {
        Log.v(
                TAG,
                "onNotify() - device=" + device + ", handle=" + handle + ", length=" + data.length);

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onNotify(device, handle, data));
    }

    void onReadCharacteristicFromNative(int connId, int status, int handle, byte[] data) {
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onReadCharacteristic() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status))
                        + (", length=" + data.length));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onCharacteristicRead(device, status, handle, data));
    }

    void onWriteCharacteristicFromNative(int connId, int status, int handle, byte[] data) {
        final var device = mClientMap.deviceByConnId(connId);
        synchronized (mPermits) {
            Log.d(TAG, "onWriteCharacteristic() - increasing permit for device=" + device);
            mPermits.put(device, -1);
        }

        Log.v(
                TAG,
                "onWriteCharacteristic() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status))
                        + (", length=" + data.length));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            callbackToApp(
                    () -> app.getCallback().onCharacteristicWrite(device, status, handle, data));
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
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onExecuteCompleted() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status)));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onExecuteWrite(device, status));
    }

    void onReadDescriptorFromNative(int connId, int status, int handle, byte[] data) {
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onReadDescriptor() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status))
                        + (", length=" + data.length));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onDescriptorRead(device, status, handle, data));
    }

    void onWriteDescriptorFromNative(int connId, int status, int handle, byte[] data) {
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                "onWriteDescriptor() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status))
                        + (", length=" + data.length));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onDescriptorWrite(device, status, handle, data));
    }

    void onReadRemoteRssiFromNative(int clientIf, BluetoothDevice device, int rssi, int status) {
        Log.d(
                TAG,
                "onReadRemoteRssi() -"
                        + (" clientIf=" + clientIf)
                        + (", device=" + device)
                        + (", rssi=" + rssi)
                        + (", status=" + gattStatusToString(status)));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getById(clientIf);
        if (app == null) {
            return;
        }

        if (Flags.readRssiThrottling() && status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "onReadRemoteRssi() - putting timestamp and rssi into cache");
            mRssiCache.put(
                    device.getAddress(), new RssiCacheEntry(mTimeProvider.elapsedRealtime(), rssi));
        }

        callbackToApp(() -> app.getCallback().onReadRemoteRssi(device, rssi, status));
    }

    void onConfigureMTUFromNative(int connId, int status, int mtu) {
        final var device = mClientMap.deviceByConnId(connId);
        Log.d(
                TAG,
                "onConfigureMTU() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status))
                        + (", mtu=" + mtu));

        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onConfigureMTU(device, mtu, status));
    }

    void onClientCongestionFromNative(int connId, boolean congested) {
        Log.v(TAG, "onClientCongestion() - connId=" + connId + ", congested=" + congested);
        final ContextMap<IBluetoothGattCallback>.App app = mClientMap.getByConnId(connId);

        if (app == null) {
            return;
        }
        app.isCongested = congested;
        while (!app.isCongested) {
            final var callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) {
                return;
            }
            callbackToApp(
                    () ->
                            app.getCallback()
                                    .onCharacteristicWrite(
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
        final Map<BluetoothDevice, Integer> deviceStates = new HashMap<>();

        // Add paired LE devices
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            if (getDeviceType(device) != AbstractionLayer.BT_DEVICE_TYPE_BREDR) {
                deviceStates.put(device, STATE_DISCONNECTED);
            }
        }

        // Add connected deviceStates
        final Set<BluetoothDevice> connectedDevices = new HashSet<>();
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
        final Map<Integer, BluetoothDevice> connMap = mClientMap.getConnectedMap();
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

    /**
     * Returns the first connection ID with a device for a particular app, if that device has a
     * bearer.
     *
     * <p>While the specifications allow for multiple bearers, and our native stack strictly can
     * allow for it, clients *try* to have a limit of one bearer with a remote device. In the case
     * there's multiple connection IDs with a device for a client app, this utility will grab the
     * first connection ID found with that device.
     */
    Integer getFirstConnectionIdForDevice(int clientIf, BluetoothDevice device) {
        final List<ContextMap.Connection> connections =
                mClientMap.getConnectionsByDevice(clientIf, device);
        return connections.isEmpty() ? null : connections.get(0).connId();
    }

    void registerClient(
            UUID uuid,
            IBluetoothGattCallback callback,
            boolean eattSupport,
            int transport,
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

        Log.d(
                TAG,
                "registerClient() -"
                        + (" UUID=" + uuid)
                        + (" name=" + name)
                        + (" transport=" + transportToString(transport)));
        mClientMap.add(uuid, callback, transport, this, source);
        mNativeInterface.gattClientRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), name, eattSupport);
    }

    void unregisterClient(
            IBluetoothGattCallback callback,
            AttributionSource source,
            ContextMap.RemoveReason reason) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "unregisterClient(" + callback + ") - Already unregistered");
            return;
        }
        final var clientIf = clientApp.id;
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientConnect(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.d(
                TAG,
                "clientConnect() -"
                        + (" device=" + device)
                        + (", transport=" + transportToString(transport))
                        + (", addressType=" + addressType)
                        + (", isDirect=" + isDirect)
                        + (", opportunistic=" + opportunistic)
                        + (", phy=" + phy));
        statsLogAppPackage(device, source.getUid(), clientIf);

        logClientForegroundInfo(source.getUid(), isDirect);

        statsLogGattConnectionStateChange(
                mProfileId, device, clientIf, BluetoothProtoEnums.CONNECTION_STATE_CONNECTING, -1);

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

        final var packageName = source.getPackageName();
        boolean preferRelaxMode = false;
        final var tag = getLastAttributionTag(source);
        if (tag != null && GATT_CLIENTS_PREFER_RELAX_MODE.stream().anyMatch(tag::endsWith)) {
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

        if (transport != TRANSPORT_BREDR && isDirect && !opportunistic) {
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientDisconnect(" + callback + ") - App not registered");
            return;
        }
        clientDisconnectInternal(clientApp.id, device, source);
    }

    private void clientDisconnectInternal(
            int clientIf, BluetoothDevice device, AttributionSource source) {
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        Log.d(TAG, "clientDisconnectInternal() - device=" + device + ", connId=" + connId);
        statsLogGattConnectionStateChange(
                mProfileId,
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientSetPreferredPhy(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "clientSetPreferredPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "clientSetPreferredPhy() - device=" + device + ", connId=" + connId);
        mNativeInterface.gattClientSetPreferredPhy(clientIf, device, txPhy, rxPhy, phyOptions);
    }

    void clientReadPhy(IBluetoothGattCallback callback, BluetoothDevice device) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientReadPhy(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "clientReadPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "clientReadPhy() - device=" + device + ", connId=" + connId);
        mNativeInterface.gattClientReadPhy(clientIf, device);
    }

    void refreshDevice(IBluetoothGattCallback callback, BluetoothDevice device) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "refreshDevice(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.d(TAG, "refreshDevice() - device=" + device);
        mNativeInterface.gattClientRefresh(clientIf, device);
    }

    void discoverServices(IBluetoothGattCallback callback, BluetoothDevice device) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "discoverServices(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        Log.d(TAG, "discoverServices() - device=" + device + ", connId=" + connId);

        if (connId != null) {
            mNativeInterface.gattClientSearchService(connId, true, 0, 0);
        } else {
            Log.e(TAG, "discoverServices() - No connection for " + device);
        }
    }

    void discoverServiceByUuid(IBluetoothGattCallback callback, BluetoothDevice device, UUID uuid) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "discoverServiceByUuid(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readCharacteristic(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.v(TAG, "readCharacteristic(" + device + ")");
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readCharacteristic(" + device + ") - No connection");
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readUsingCharacteristicUuid(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.v(TAG, "readUsingCharacteristicUuid() - device=" + device);
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeCharacteristic(" + callback + ") - App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var clientIf = clientApp.id;
        Log.v(TAG, "writeCharacteristic(" + device + ")");
        if (mReliableQueue.contains(device)) {
            writeType = 3; // Prepared write
        }
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "writeCharacteristic(" + device + ") - No connection");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }

        Log.d(TAG, "writeCharacteristic() - trying to acquire permit.");
        // Lock the thread until onCharacteristicWrite callback comes back.
        synchronized (mPermits) {
            final var permit = mPermits.get(device);
            if (permit == null) {
                Log.d(TAG, "writeCharacteristic() - atomicBoolean uninitialized!");
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
            }

            final var success = (permit == -1);
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readDescriptor(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.v(TAG, "readDescriptor() - device=" + device);

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readDescriptor() - No connection for " + device);
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeDescriptor(" + callback + ") - App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var clientIf = clientApp.id;
        Log.v(TAG, "writeDescriptor() - device=" + device);

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "writeDescriptor() - No connection for " + device);
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }

        mNativeInterface.gattClientWriteDescriptor(connId, handle, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    void beginReliableWrite(BluetoothDevice device) {
        Log.d(TAG, "beginReliableWrite() - device=" + device);
        mReliableQueue.add(device);
    }

    void endReliableWrite(
            IBluetoothGattCallback callback, BluetoothDevice device, boolean execute) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "endReliableWrite(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.d(TAG, "endReliableWrite() - device=" + device + " execute: " + execute);
        mReliableQueue.remove(device);

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeDescriptor(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.d(TAG, "registerForNotification() - device=" + device + " enable: " + enable);
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "registerForNotification() - No connection for " + device);
            return;
        }

        mNativeInterface.gattClientRegisterForNotifications(clientIf, device, handle, enable);
    }

    void readRemoteRssi(IBluetoothGattCallback callback, BluetoothDevice device) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readRemoteRssi(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.d(TAG, "readRemoteRssi() - device=" + device);
        if (Flags.readRssiThrottling() && mRssiReadThrottleMs > 0) {
            final var entry = mRssiCache.get(device.getAddress());
            if (entry != null
                    && (mTimeProvider.elapsedRealtime() - entry.readTimeStamp)
                            < mRssiReadThrottleMs) {
                Log.d(TAG, "readRemoteRssi() - rssi value found in cache, returning to callback");
                callbackToApp(
                        () ->
                                clientApp
                                        .getCallback()
                                        .onReadRemoteRssi(
                                                device, entry.rssi, BluetoothGatt.GATT_SUCCESS));
                return;
            }
        }
        mNativeInterface.gattClientReadRemoteRssi(clientIf, device);
    }

    void configureMTU(IBluetoothGattCallback callback, BluetoothDevice device, int mtu) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "configureMTU(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        Log.d(TAG, "configureMTU() - device=" + device + " mtu=" + mtu);
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientConfigureMTU(connId, mtu);
        } else {
            Log.e(TAG, "configureMTU() - No connection for " + device);
        }
    }

    void connectionParameterUpdate(
            IBluetoothGattCallback callback, BluetoothDevice device, int connectionPriority) {
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "connectionParameterUpdate(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
        final var companionManager = mAdapterService.getCompanionManager();
        final int minInterval =
                companionManager.getGattConnParameters(
                        device, CompanionManager.GATT_CONN_INTERVAL_MIN, connectionPriority);
        final int maxInterval =
                companionManager.getGattConnParameters(
                        device, CompanionManager.GATT_CONN_INTERVAL_MAX, connectionPriority);
        // Peripheral latency
        final int latency =
                companionManager.getGattConnParameters(
                        device, CompanionManager.GATT_CONN_LATENCY, connectionPriority);

        final int timeout = 500; // 5s. Link supervision timeout is measured in N * 10ms
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "leConnectionUpdate(" + callback + ") - App not registered");
            return;
        }
        final var clientIf = clientApp.id;
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
        final ContextMap<IBluetoothGattCallback>.App clientApp =
                mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "subrateModeRequest(" + callback + ") - App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var clientIf = clientApp.id;

        int subrateMin =
                getGattSubratingParameters(GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX, subrateMode);
        int subrateMax =
                getGattSubratingParameters(GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX, subrateMode);
        int contNumber = getGattSubratingParameters(GATT_SUBRATE_CONT_NUM_INDEX, subrateMode);
        int maxLatency = getGattSubratingParameters(GATT_SUBRATE_LATENCY_INDEX, subrateMode);

        // Restore to cached Peripheral Latency
        if (subrateMode == BluetoothGatt.SUBRATE_MODE_OFF) {
            maxLatency = mCachedPeripheralLatency.getOrDefault(device, 0);
        }

        int supervisionTimeout = 500; // 5s. Link supervision timeout is measured in N * 10ms

        Log.d(
                TAG,
                ("subrateModeRequest(" + device + ", " + subrateMode + ") - ")
                        + (" subrate min/max=" + subrateMin + "/" + subrateMax)
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
        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        app.id = serverIf;
        app.linkToDeath(new ServerDeathRecipient(app.getCallback(), app.getPackageName()));
        callbackToApp(() -> app.getCallback().onServerRegistered(status));
    }

    void onServiceAddedFromNative(int status, int serverIf, List<GattDbElement> service) {
        Log.d(
                TAG,
                "onServiceAdded() -"
                        + (" serverIf=" + serverIf)
                        + (" status=" + gattStatusToString(status)));

        if (status != BluetoothGatt.GATT_SUCCESS) {
            return;
        }

        final GattDbElement svcEl = service.get(0);
        final var srvcHandle = svcEl.attributeHandle;

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

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(serverIf);
        if (app == null) {
            return;
        }
        final BluetoothGattService serviceAdded = svc;
        callbackToApp(() -> app.getCallback().onServiceAdded(status, serviceAdded));
    }

    void onServiceStoppedFromNative(int status, int serverIf, int srvcHandle) {
        Log.d(
                TAG,
                "onServiceStopped() -"
                        + (" srvcHandle=" + srvcHandle)
                        + (", status=" + gattStatusToString(status)));
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mHandleMap.setStarted(serverIf, srvcHandle, false);
        }
        stopNextService(serverIf, status);
    }

    void onServiceDeletedFromNative(int status, int serverIf, int srvcHandle) {
        Log.d(
                TAG,
                "onServiceDeleted() -"
                        + (" srvcHandle=" + srvcHandle)
                        + (", status=" + gattStatusToString(status)));
        mHandleMap.deleteService(serverIf, srvcHandle);
    }

    void onClientConnectedFromNative(
            BluetoothDevice device, int transport, boolean connected, int connId, int serverIf) {
        Log.d(
                TAG,
                "onClientConnected() -"
                        + (" connId=" + connId)
                        + (", device=" + device)
                        + (", transport=" + transportToString(transport))
                        + (", connected=" + connected));

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getById(serverIf);
        if (app == null) {
            Log.w(TAG, "onClientConnected() - received connection event for unregistered app");
            return;
        }

        // The native stack reports connection state changes for *all* bearer connections,
        // multiplexed across all applications. It's possible for an app to have more than one
        // bearer with a remote device. Since we don't expose per-bearer information to the
        // applications, we need to abstract this info away. We send "connected" when we grow from
        // zero to one connection, and disconnected when there are *no more* connections.

        // Are we connected currently?
        final var previouslyConnected =
                !mServerMap.getConnectionsByDevice(serverIf, device).isEmpty();

        // Add or remove a connection from our records
        if (connected) {
            mServerMap.addConnection(serverIf, connId, transport, device);
        } else {
            mServerMap.removeConnection(serverIf, connId);
        }

        // Look at new set of connections to determine overall connection state to share outward
        final int connectionState;
        final boolean stateToReport;
        if (Flags.gattMultiBearerConnections()) {
            boolean currentlyConnected =
                    !mServerMap.getConnectionsByDevice(serverIf, device).isEmpty();
            if (!previouslyConnected && currentlyConnected) {
                Log.i(
                        TAG,
                        "onClientConnected() -"
                                + (" serverIf=" + serverIf)
                                + (", device=" + device)
                                + " has its first bearer and is now connected");
                stateToReport = true;
                connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
            } else if (previouslyConnected && !currentlyConnected) {
                Log.i(
                        TAG,
                        "onClientConnected() -"
                                + (" serverIf=" + serverIf)
                                + (", device=" + device)
                                + " has no more bearers and is disconnected");
                stateToReport = false;
                connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
            } else {
                Log.d(
                        TAG,
                        "onClientConnected() -"
                                + ("serverIf=" + serverIf)
                                + (", device=" + device)
                                + (" event dropped, previouslyConnected=" + previouslyConnected)
                                + (", currentlyConnected=" + currentlyConnected));
                return;
            }
        } else {
            stateToReport = connected;
            connectionState =
                    connected
                            ? BluetoothProtoEnums.CONNECTION_STATE_CONNECTED
                            : BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        }

        int applicationUid = -1;
        try {
            applicationUid =
                    getPackageManager().getPackageUid(app.getPackageName(), PackageInfoFlags.of(0));
        } catch (NameNotFoundException e) {
            Log.d(TAG, "onClientConnected() - uid_not_found=" + app.getPackageName());
        }

        // Lambdas require an effectively final variable. This should be removed when the
        // gattMultiBearerConnections flag is removed.
        final boolean state = stateToReport;
        callbackToApp(() -> app.getCallback().onServerConnectionState((byte) 0, state, device));
        statsLogAppPackage(device, applicationUid, serverIf);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT_SERVER, device, serverIf, connectionState, -1);
    }

    void onServerPhyUpdateFromNative(int connId, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onServerPhyUpdate() -"
                        + (" connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        final var device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onPhyUpdate(device, txPhy, rxPhy, status));
    }

    void onServerPhyReadFromNative(
            int serverIf, BluetoothDevice device, int txPhy, int rxPhy, int status) {
        Log.d(
                TAG,
                "onServerPhyRead() -"
                        + (" device=" + device)
                        + (", status=" + gattStatusToString(status)));

        final List<ContextMap.Connection> connections =
                mServerMap.getConnectionsByDevice(serverIf, device);
        final var connId = connections.isEmpty() ? null : connections.get(0).connId();
        if (connId == null) {
            Log.d(TAG, "onServerPhyRead() - no connection to " + device);
            return;
        }

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            Log.w(TAG, "onServerPhyReadFromNative() - received phy read for unregistered app");
            return;
        }

        // Lambdas require an effectively final variable. This should be removed when the
        // gattMultiBearerConnections flag is removed.
        final ContextMap<IBluetoothGattServerCallback>.App finalApp = app;
        callbackToApp(() -> finalApp.getCallback().onPhyRead(device, txPhy, rxPhy, status));
    }

    void onServerConnUpdateFromNative(
            int connId, int interval, int latency, int timeout, int status) {
        Log.d(
                TAG,
                "onServerConnUpdate() -"
                        + (" connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        final var device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        mCachedPeripheralLatency.put(device, latency); // cache new peripheral latency

        callbackToApp(
                () ->
                        app.getCallback()
                                .onConnectionUpdated(device, interval, latency, timeout, status));
    }

    void onServerSubrateChangeFromNative(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status) {
        Log.d(
                TAG,
                "onServerSubrateChange() -"
                        + (" connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        int subrateMode;

        final var device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (status == BluetoothStatusCodes.SUCCESS) {
            subrateMode = verifyGattSubratingMode(subrateFactor, latency, contNum);
        } else {
            subrateMode = BluetoothGatt.SUBRATE_MODE_NOT_UPDATED;
        }
        callbackToApp(
                () ->
                        app.getCallback()
                                .onSubrateChange(device, subrateMode, translateHciCode(status)));
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
        final var entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        final int requestId;
        if (Flags.gattMultiBearerTransactions()) {
            requestId = mHandleMap.addRequestContext(entry.mServerIf, connId, transId, handle);
        } else {
            requestId = transId;
            mHandleMap.addRequest(connId, transId, handle);
        }

        final ContextMap<IBluetoothGattServerCallback>.App app =
                mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.getCallback()
                                .onCharacteristicReadRequest(
                                        device, requestId, offset, isLong, handle));
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

        final var entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        final int requestId;
        if (Flags.gattMultiBearerTransactions()) {
            requestId = mHandleMap.addRequestContext(entry.mServerIf, connId, transId, handle);
        } else {
            requestId = transId;
            mHandleMap.addRequest(connId, transId, handle);
        }

        final ContextMap<IBluetoothGattServerCallback>.App app =
                mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.getCallback()
                                .onDescriptorReadRequest(
                                        device, requestId, offset, isLong, handle));
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

        final var entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        final int requestId;
        if (Flags.gattMultiBearerTransactions()) {
            requestId = mHandleMap.addRequestContext(entry.mServerIf, connId, transId, handle);
        } else {
            requestId = transId;
            mHandleMap.addRequest(connId, transId, handle);
        }

        final ContextMap<IBluetoothGattServerCallback>.App app =
                mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.getCallback()
                                .onCharacteristicWriteRequest(
                                        device, requestId, offset, length, isPrep, needRsp, handle,
                                        data));
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

        final var entry = mHandleMap.getByHandle(handle);
        if (entry == null) {
            return;
        }

        final int requestId;
        if (Flags.gattMultiBearerTransactions()) {
            requestId = mHandleMap.addRequestContext(entry.mServerIf, connId, transId, handle);
        } else {
            requestId = transId;
            mHandleMap.addRequest(connId, transId, handle);
        }

        final ContextMap<IBluetoothGattServerCallback>.App app =
                mServerMap.getById(entry.mServerIf);
        if (app == null) {
            return;
        }

        callbackToApp(
                () ->
                        app.getCallback()
                                .onDescriptorWriteRequest(
                                        device, requestId, offset, length, isPrep, needRsp, handle,
                                        data));
    }

    void onExecuteWriteFromNative(BluetoothDevice device, int connId, int transId, int execWrite) {
        Log.d(
                TAG,
                "onExecuteWrite() -"
                        + (" device=" + device)
                        + (", connId=" + connId)
                        + (", transId=" + transId)
                        + (", operation=" + (execWrite == 1 ? "WRITE" : "CANCEL")));

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        final int requestId;
        final int handle = HandleMap.HANDLE_PREPARED_WRITE;
        if (Flags.gattMultiBearerTransactions()) {
            requestId = mHandleMap.addRequestContext(app.id, connId, transId, handle);
        } else {
            requestId = transId;
            mHandleMap.addRequest(connId, transId, handle);
        }

        callbackToApp(() -> app.getCallback().onExecuteWrite(device, requestId, execWrite == 1));
    }

    void onResponseSendCompletedFromNative(int status, int attrHandle) {
        Log.d(TAG, "onResponseSendCompleted() - handle=" + attrHandle);
    }

    void onNotificationSentFromNative(int connId, int status) {
        Log.v(
                TAG,
                "onNotificationSent() -"
                        + ("connId=" + connId)
                        + (", status=" + gattStatusToString(status)));

        final var device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            callbackToApp(() -> app.getCallback().onNotificationSent(device, status));
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

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.isCongested = congested;
        while (!app.isCongested) {
            final var callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) {
                return;
            }
            callbackToApp(
                    () ->
                            app.getCallback()
                                    .onNotificationSent(
                                            callbackInfo.device(), callbackInfo.status()));
        }
    }

    void onMtuChangedFromNative(int connId, int mtu) {
        Log.d(TAG, "onMtuChanged() - connId=" + connId + ", mtu=" + mtu);

        final var device = mServerMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        final ContextMap<IBluetoothGattServerCallback>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onMtuChanged(device, mtu));
    }

    /**************************************************************************
     * GATT Service functions - SERVER
     *************************************************************************/

    void registerServer(
            UUID uuid,
            IBluetoothGattServerCallback callback,
            boolean eattSupport,
            int transport,
            AttributionSource source) {
        String name = source.getPackageName();
        final var tag = getLastAttributionTag(source);
        final var myPackage = AttributionSource.myAttributionSource().getPackageName();
        if (myPackage.equals(name) && tag != null) {
            /* For servers created by Bluetooth stack, use just tag as name */
            name = tag;
        } else if (tag != null) {
            name = name + "[" + tag + "]";
        }

        Log.d(
                TAG,
                "registerServer() -"
                        + (" UUID=" + uuid)
                        + (" name=" + name)
                        + (" transport=" + transportToString(transport)));
        mServerMap.add(uuid, callback, transport, this, source);
        mNativeInterface.gattServerRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), eattSupport);
    }

    void unregisterServer(IBluetoothGattServerCallback callback) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "unregisterServer(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
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
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverConnect(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
        Log.d(
                TAG,
                "serverConnect() -"
                        + (" device=" + device)
                        + (" transport=" + transportToString(transport)));

        logServerForegroundInfo(source.getUid(), isDirect);

        mNativeInterface.gattServerConnect(serverIf, device, addressType, isDirect, transport);
    }

    void serverDisconnect(IBluetoothGattServerCallback callback, BluetoothDevice device) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverDisconnect(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
        if (Flags.gattMultiBearerConnections()) {
            final List<ContextMap.Connection> connections =
                    mServerMap.getConnectionsByDevice(serverIf, device);

            // If we don't have any known connection IDs, we could have a pending connection. We can
            // use connId => 0 to cancel all pending connections with the given device. Otherwise,
            // disconnect all bearers
            if (connections.isEmpty()) {
                Log.d(TAG, "serverDisconnect() - cancel pending connections for device=" + device);
                mNativeInterface.gattServerDisconnect(serverIf, device, 0);
            } else {
                for (ContextMap.Connection connection : connections) {
                    int id = connection.connId();
                    Log.d(TAG, "serverDisconnect() - device=" + device + ", connId=" + id);
                    mNativeInterface.gattServerDisconnect(serverIf, device, id);
                }
            }
        } else {
            final List<ContextMap.Connection> connections =
                    mServerMap.getConnectionsByDevice(serverIf, device);
            final var connId = connections.isEmpty() ? null : connections.get(0).connId();
            Log.d(TAG, "serverDisconnect() - device=" + device + ", connId=" + connId);
            mNativeInterface.gattServerDisconnect(serverIf, device, connId != null ? connId : 0);
        }
    }

    void serverSetPreferredPhy(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int txPhy,
            int rxPhy,
            int phyOptions) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverSetPreferredPhy(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
        final List<ContextMap.Connection> connections =
                mServerMap.getConnectionsByDevice(serverIf, device);
        if (connections.isEmpty()) {
            Log.d(TAG, "serverSetPreferredPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "serverSetPreferredPhy() device=" + device + ", connections=" + connections);
        mNativeInterface.gattServerSetPreferredPhy(serverIf, device, txPhy, rxPhy, phyOptions);
    }

    void serverReadPhy(IBluetoothGattServerCallback callback, BluetoothDevice device) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "serverReadPhy(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
        final List<ContextMap.Connection> connections =
                mServerMap.getConnectionsByDevice(serverIf, device);
        if (connections.isEmpty()) {
            Log.d(TAG, "serverReadPhy() - no connection to " + device);
            return;
        }

        Log.d(TAG, "serverReadPhy() - device=" + device + ", connections=" + connections);

        mNativeInterface.gattServerReadPhy(serverIf, device);
    }

    void addService(IBluetoothGattServerCallback callback, BluetoothGattService service) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "addService(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
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
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "removeService(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
        Log.d(TAG, "removeService() - handle=" + handle);
        mNativeInterface.gattServerDeleteService(serverIf, handle);
    }

    void clearServices(IBluetoothGattServerCallback callback) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "clearServices(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;
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
        Log.v(
                TAG,
                "sendResponse() -"
                        + (" device=" + device)
                        + (", requestId=" + requestId)
                        + (", status=" + gattStatusToString(status)));

        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "sendResponse(" + callback + ") - App not registered");
            return;
        }
        final var serverIf = serverApp.id;

        int handle = 0;
        int connId = 0;
        int transId = -1;

        HandleMap.RequestContext requestContext = null;
        HandleMap.RequestData requestData = null;

        if (Flags.gattMultiBearerTransactions()) {
            requestContext = mHandleMap.getRequestContext(serverIf, requestId);
            if (requestContext != null) {
                connId = requestContext.connId();
                transId = requestContext.transactionId();
                handle = requestContext.handle();
            }
        } else {
            transId = requestId;
            requestData = mHandleMap.getRequestDataByRequestId(requestId);
            if (requestData != null) {
                handle = requestData.handle();
                connId = requestData.connId();
            }
        }

        if (requestContext == null && requestData == null) {
            Log.w(TAG, "sendResponse(" + callback + ") - no record of request we're responding to");
            if (Flags.gattMultiBearerTransactions()) {
                return;
            } else {
                final List<ContextMap.Connection> connections =
                        mServerMap.getConnectionsByDevice(serverIf, device);
                connId = connections.isEmpty() ? 0 : connections.get(0).connId();
            }
        }

        mNativeInterface.gattServerSendResponse(
                serverIf, connId, transId, (byte) status, handle, offset, value, (byte) 0);

        if (Flags.gattMultiBearerTransactions()) {
            mHandleMap.deleteRequestContext(serverIf, requestId);
        } else {
            mHandleMap.deleteRequest(requestId);
        }
    }

    int sendNotification(
            IBluetoothGattServerCallback callback,
            BluetoothDevice device,
            int handle,
            boolean confirm,
            byte[] value) {
        final ContextMap<IBluetoothGattServerCallback>.App serverApp =
                mServerMap.getByCallbackId(callback);
        if (serverApp == null) {
            Log.w(TAG, "sendNotification(" + callback + ") - App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var serverIf = serverApp.id;
        final var transportPreference = serverApp.getTransport();

        Log.v(
                TAG,
                "sendNotification() -"
                        + (" device=" + device)
                        + (", handle=" + handle)
                        + (", transport=" + transportPreference));

        // The specifications do not insist that we must use the same bearer that wrote to the CCCD
        // to request notifications or indications. We only need to send to the same client.
        // We pick the first connection that matches the transport preference of the server, or the
        // oldest connection when transport is AUTO
        Integer connId = null;
        final List<ContextMap.Connection> connections =
                mServerMap.getConnectionsByDevice(serverIf, device);

        if (Flags.gattMultiBearerConnections()) {
            // The list is sorted by oldest first. Grab the oldest bearer that matches our transport
            // preference. If the transport is AUTO then use the oldest bearer available
            for (ContextMap.Connection connection : connections) {
                if (transportPreference == TRANSPORT_AUTO
                        || transportPreference == connection.transport()) {
                    connId = connection.connId();
                    break;
                }
            }

            // If there was no transport that matches the preference, use the oldest bearer
            if (connId == null && !connections.isEmpty()) {
                connId = connections.get(0).connId();
            }
        } else {
            if (!connections.isEmpty()) {
                connId = connections.get(0).connId();
            }
        }

        if (connId == null || connId == 0) {
            Log.d(TAG, "sendNotification() - no connection to " + device);
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }

        Log.d(
                TAG,
                "sendNotification() -"
                        + (" device=" + device)
                        + (", handle=" + handle)
                        + (", connId=" + connId)
                        + (", confirm=" + confirm));

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

    private boolean isRestrictedSrvcUuid(final UUID uuid, BluetoothDevice device) {
        return isFidoSrvcUuid(uuid)
                || isAndroidTvRemoteSrvcUuid(uuid)
                || isLeAudioSrvcUuid(uuid)
                || isAndroidHeadtrackerSrvcUuid(uuid)
                || (Flags.gattMessagingPermissions()
                        && isAppleNotificationCenterSrvcUuid(uuid)
                        && mAdapterService.getMessageAccessPermission(device)
                                != BluetoothDevice.ACCESS_ALLOWED);
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
        Log.d(
                TAG,
                "stopNextService() -"
                        + (" serverIf=" + serverIf)
                        + (", status=" + gattStatusToString(status)));

        if (status != BluetoothGatt.GATT_SUCCESS) {
            return;
        }
        final List<HandleMap.Entry> entries = mHandleMap.getEntries();
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
        final List<Integer> handleList = new ArrayList<>();
        final List<HandleMap.Entry> entries = mHandleMap.getEntries();
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

    /**
     * Verifies the GATT connection subrating parameters of the device
     *
     * @param subrateFactor for this LE connection.
     * @param latency Worker latency for this LE connection in number of connection events.
     * @param contNum Continuation Number for this LE connection.
     * @return the connection subrating priority in integer
     */
    public int verifyGattSubratingMode(int subrateFactor, int latency, int contNum) {
        int returnSubrateMode = BluetoothGatt.SUBRATE_MODE_SYSTEM_UPDATE;
        if (mSubrateLowParameters[GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX] <= subrateFactor
                && subrateFactor <= mSubrateLowParameters[GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX]
                && latency == mSubrateLowParameters[GATT_SUBRATE_LATENCY_INDEX]
                && contNum <= mSubrateLowParameters[GATT_SUBRATE_CONT_NUM_INDEX]) {
            returnSubrateMode = BluetoothGatt.SUBRATE_MODE_LOW;
        }
        if (mSubrateBalancedParameters[GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX] <= subrateFactor
                && subrateFactor
                        <= mSubrateBalancedParameters[GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX]
                && latency == mSubrateBalancedParameters[GATT_SUBRATE_LATENCY_INDEX]
                && contNum <= mSubrateBalancedParameters[GATT_SUBRATE_CONT_NUM_INDEX]) {
            returnSubrateMode = BluetoothGatt.SUBRATE_MODE_BALANCED;
        }
        if (mSubrateHighParameters[GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX] <= subrateFactor
                && subrateFactor <= mSubrateHighParameters[GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX]
                && latency == mSubrateHighParameters[GATT_SUBRATE_LATENCY_INDEX]
                && contNum <= mSubrateHighParameters[GATT_SUBRATE_CONT_NUM_INDEX]) {
            returnSubrateMode = BluetoothGatt.SUBRATE_MODE_HIGH;
        }
        if (mSubrateOffParameters[GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX] == subrateFactor
                && subrateFactor == mSubrateOffParameters[GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX]
                && latency == mSubrateOffParameters[GATT_SUBRATE_LATENCY_INDEX]
                && contNum == mSubrateOffParameters[GATT_SUBRATE_CONT_NUM_INDEX]) {
            returnSubrateMode = BluetoothGatt.SUBRATE_MODE_OFF;
        }
        return returnSubrateMode;
    }

    /**
     * Gets the GATT connection subrating mode of the device
     *
     * @param type type of the parameter, can be GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX,
     *     GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX, GATT_SUBRATE_LATENCY_INDEX or
     *     GATT_SUBRATE_CONT_NUM_INDEX
     * @param mode the priority of the connection, can be BluetoothGatt.SUBRATE_MODE_HIGH,
     *     BluetoothGatt.SUBRATE_MODE_LOW or BluetoothGatt.SUBRATE_MODE_BALANCED
     * @return the connection parameter in integer
     */
    private int getGattSubratingParameters(int type, @BluetoothGatt.SubrateMode int mode) {
        return switch (mode) {
            case BluetoothGatt.SUBRATE_MODE_LOW -> mSubrateLowParameters[type];
            case BluetoothGatt.SUBRATE_MODE_BALANCED -> mSubrateBalancedParameters[type];
            case BluetoothGatt.SUBRATE_MODE_HIGH -> mSubrateHighParameters[type];
            default -> mSubrateOffParameters[type];
        };
    }

    void dumpRegisterId(StringBuilder sb) {
        sb.append("  Client:\n");
        for (Integer appId : mClientMap.getAllAppsIds()) {
            final ContextMap.App app = mClientMap.getById(appId);
            println(
                    sb,
                    ("    app_if: " + appId)
                            + (", appName: " + app.getPackageName())
                            + (", transport: " + transportToString(app.getTransport()))
                            + (app.mAttributionTag == null ? "" : ", tag: " + app.mAttributionTag));
            final List<ContextMap.Connection> clientConnections =
                    mClientMap.getConnectionByApp(appId);
            for (ContextMap.Connection connection : clientConnections) {
                println(sb, "        " + connection);
            }
        }
        sb.append("  Server:\n");
        for (Integer appId : mServerMap.getAllAppsIds()) {
            final ContextMap.App app = mServerMap.getById(appId);
            println(
                    sb,
                    ("    app_if: " + appId)
                            + (", appName: " + app.getPackageName())
                            + (", transport: " + transportToString(app.getTransport()))
                            + (app.mAttributionTag == null ? "" : ", tag: " + app.mAttributionTag));
            final List<ContextMap.Connection> serverConnections =
                    mServerMap.getConnectionByApp(appId);
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
                "Logging:"
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
                "Logging:"
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
