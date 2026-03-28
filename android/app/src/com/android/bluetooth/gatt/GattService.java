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

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothGatt.GATT_CONNECTION_TIMEOUT;
import static android.bluetooth.BluetoothGatt.GATT_FAILURE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.ChangeIds.DONOT_STEAL_AUDIO_ON_GATT_CONN;
import static com.android.bluetooth.Util.transportToString;
import static com.android.bluetooth.Utils.callbackToApp;
import static com.android.bluetooth.gatt.ContextMap.RemoveReason.REASON_BINDER_DIED;
import static com.android.bluetooth.gatt.GattUtil.isAndroidHeadtrackerSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isAndroidTvRemoteSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isAppleNotificationCenterSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isFidoSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isHidCharUuid;
import static com.android.bluetooth.gatt.GattUtil.isHidSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.isLeAudioSrvcUuid;
import static com.android.bluetooth.gatt.GattUtil.statusToString;
import static com.android.bluetooth.gatt.GattUtil.translateHciCode;
import static com.android.bluetooth.util.AttributionSourceUtils.getLastAttributionTag;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.Nullable;
import android.app.compat.CompatChanges;
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
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.util.ArraySet;
import android.util.Log;

import com.android.bluetooth.ActionOnDeathRecipient;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AbstractionLayer;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.profile.ProfileService;
import com.android.bluetooth.util.Text;
import com.android.bluetooth.util.TimeProvider;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Provides Bluetooth Gatt profile, as a service in the Bluetooth application. */
public class GattService extends ProfileService {
    private static final String TAG = GattUtil.TAG_PREFIX + GattService.class.getSimpleName();

    private static final long RUN_SYNC_WAIT_TIME_MS = 2000L;

    private final int[] mSubrateHighParameters;
    private final int[] mSubrateBalancedParameters;
    private final int[] mSubrateLowParameters;
    private final int[] mSubrateOffParameters;

    private static final int GATT_SUBRATE_MIN_SUBRATE_FACTOR_INDEX = 0;
    private static final int GATT_SUBRATE_MAX_SUBRATE_FACTOR_INDEX = 1;
    private static final int GATT_SUBRATE_LATENCY_INDEX = 2;
    private static final int GATT_SUBRATE_CONT_NUM_INDEX = 3;

    private static final int SUBRATE_HIGH_MODE_SUBRATE_MIN_DEFAULT = 2;
    private static final int SUBRATE_HIGH_MODE_SUBRATE_MAX_DEFAULT = 4;
    private static final int SUBRATE_HIGH_MODE_LATENCY_DEFAULT = 0;
    private static final int SUBRATE_HIGH_MODE_CONT_NUM_DEFAULT = 1;

    private static final int SUBRATE_BALANCED_MODE_SUBRATE_MIN_DEFAULT = 5;
    private static final int SUBRATE_BALANCED_MODE_SUBRATE_MAX_DEFAULT = 7;
    private static final int SUBRATE_BALANCED_MODE_LATENCY_DEFAULT = 0;
    private static final int SUBRATE_BALANCED_MODE_CONT_NUM_DEFAULT = 4;

    private static final int SUBRATE_LOW_MODE_SUBRATE_MIN_DEFAULT = 8;
    private static final int SUBRATE_LOW_MODE_SUBRATE_MAX_DEFAULT = 10;
    private static final int SUBRATE_LOW_MODE_LATENCY_DEFAULT = 0;
    private static final int SUBRATE_LOW_MODE_CONT_NUM_DEFAULT = 6;

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
                            "channelsounding",
                            "channelsoundingtestapp",
                            "com.google.android.apps.adm",
                            "crossdeviceaccessservice"));

    // Remote RSSI read throttle time
    private static final String RSSI_READ_THROTTLE_MS =
            "bluetooth.ble.rssi_read_throttle_ms.config";

    private static final int RSSI_READ_THROTTLE_MS_DEFAULT = 75;
    @VisibleForTesting static final int RSSI_READ_THROTTLE_MS_MAX = 200;
    @VisibleForTesting static final int GATT_CLIENT_LIMIT_PER_APP = 32;

    /** List of our registered clients. */
    private final ContextMap<IBluetoothGattCallback> mClientMap;

    /** Reliable write queue */
    private final Set<BluetoothDevice> mReliableQueue;

    /**
     * Set of restricted (which require a BLUETOOTH_PRIVILEGED permission) handles per connectionId.
     */
    private final Map<Integer, Set<Integer>> mRestrictedHandles = new HashMap<>();

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

    /** A remote device RSSI read is requested, null if none */
    private BluetoothDevice mPendingRssiDevice;

    /** Set of clients requesting RSSI */
    @VisibleForTesting final Set<Integer> mClientsPendingRssi = new ArraySet<>();

    private final CompanionDeviceManager mCompanionDeviceManager;
    private final GattServerManager mServerManager;
    private final GattNativeInterface mNativeInterface;
    // TODO(b/449681465) Remove @Nullable on flag cleanup
    @Nullable private final HandlerThread mGattThread;
    @Nullable private final Looper mGattLooper;
    @Nullable private final Handler mGattHandler;
    // TODO(b/449681465) Remove on flag cleanup
    @Nullable private final HandlerThread mHandlerThread;
    private final AdvertiseManager mAdvertiseManager;
    private final DistanceMeasurementManager mDistanceMeasurementManager;
    private final TimeProvider mTimeProvider;
    private final GattMetricsReporter mMetricsReporter;
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
                new ContextMap<>() /* mClientMap */,
                new HashSet<>() /* mReliableQueue */,
                companionDeviceManager,
                null,
                TimeProvider.getSystemClock());
    }

    @VisibleForTesting
    GattService(
            AdapterService adapterService,
            GattNativeInterface nativeInterface,
            AdvertiseManagerNativeInterface advertiseManagerNativeInterface,
            DistanceMeasurementNativeInterface distanceMeasurementNativeInterface,
            ContextMap<IBluetoothGattCallback> clientMap,
            Set<BluetoothDevice> reliableQueue,
            CompanionDeviceManager companionDeviceManager,
            @Nullable Looper gattLooper,
            TimeProvider timeProvider) {
        super(BluetoothProfile.GATT, adapterService);
        mClientMap = requireNonNull(clientMap);
        mReliableQueue = requireNonNull(reliableQueue);
        mCompanionDeviceManager = companionDeviceManager;
        mTimeProvider = timeProvider;
        mMetricsReporter = new GattMetricsReporter(getProfileId(), adapterService);

        Settings.Global.putInt(
                getContentResolver(), "bluetooth_sanitized_exposure_notification_supported", 1);

        mServerManager = new GattServerManager(getAdapterService(), this, mMetricsReporter);
        var nativeCallback = new GattNativeCallback(getAdapterService(), this, mServerManager);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new GattNativeInterface(nativeCallback));
        mNativeInterface.init();

        Looper looper;
        if (Flags.gattThread()) {
            mGattThread = new HandlerThread("BluetoothGatt");
            mGattThread.start();
            mGattLooper = requireNonNullElseGet(gattLooper, mGattThread::getLooper);
            looper = mGattLooper;
            mGattHandler = new Handler(mGattLooper);
            mHandlerThread = null;
        } else {
            mHandlerThread = new HandlerThread("Bluetooth LE");
            mHandlerThread.start();
            looper = mHandlerThread.getLooper();
            mGattThread = null;
            mGattLooper = null;
            mGattHandler = null;
        }

        mAdvertiseManager =
                new AdvertiseManager(
                        getAdapterService(), this, advertiseManagerNativeInterface, looper);

        mRssiReadThrottleMs =
                SystemProperties.getInt(RSSI_READ_THROTTLE_MS, RSSI_READ_THROTTLE_MS_DEFAULT);
        if (mRssiReadThrottleMs > RSSI_READ_THROTTLE_MS_MAX) {
            Log.w(
                    TAG,
                    "RSSI read throttle ms exceeds max"
                            + (", clipping to max: " + RSSI_READ_THROTTLE_MS_MAX + "ms"));
            mRssiReadThrottleMs = RSSI_READ_THROTTLE_MS_MAX;
        }

        mDistanceMeasurementManager =
                new DistanceMeasurementManager(
                        getAdapterService(), this, distanceMeasurementNativeInterface, looper);

        mSubrateLowParameters =
                new int[] {
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_low_min_subrate.config",
                            SUBRATE_LOW_MODE_SUBRATE_MIN_DEFAULT),
                    SystemProperties.getInt(
                            "bluetooth.ble.client.subrate_mode_low_max_subrate.config",
                            SUBRATE_LOW_MODE_SUBRATE_MAX_DEFAULT),
                    SUBRATE_LOW_MODE_LATENCY_DEFAULT,
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
                    SUBRATE_BALANCED_MODE_LATENCY_DEFAULT,
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
                    SUBRATE_HIGH_MODE_LATENCY_DEFAULT,
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

        if (Flags.gattThread()) {
            mGattHandler.removeCallbacksAndMessages(null);
        }
        forceRunSyncOnGattThread(
                () -> {
                    mClientMap.clear();
                    mRestrictedHandles.clear();
                    mServerManager.cleanup();
                    mRssiCache.clear();
                    mClientsPendingRssi.clear();
                    mReliableQueue.clear();
                    mNativeInterface.cleanup();
                    mAdvertiseManager.cleanup();
                    mDistanceMeasurementManager.cleanup();
                    if (Flags.gattThread()) {
                        mGattThread.quitSafely();
                    } else {
                        mHandlerThread.quit();
                    }
                });
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        sb.append(Text.indent(GattUtil.dump(mAdvertiseManager, mClientMap, mServerManager), "  "));
    }

    public IBinder getBluetoothAdvertise() {
        return mAdvertiseManager.getBinder();
    }

    public IBinder getDistanceMeasurement() {
        return mDistanceMeasurementManager.getBinder();
    }

    public AdvertiseManager getAdvertiseManager() {
        return mAdvertiseManager;
    }

    CompanionDeviceManager getCompanionDeviceManager() {
        return mCompanionDeviceManager;
    }

    GattServerManager getServerManager() {
        return mServerManager;
    }

    ContextMap<IBluetoothGattCallback> getClientMap() {
        enforceGattThread();
        return mClientMap;
    }

    ContextMap<IBluetoothGattServerCallback> getServerMap() {
        enforceGattThread();
        return mServerManager.getServerMap();
    }

    Map<Integer, Set<Integer>> getRestrictedHandles() {
        enforceGattThread();
        return mRestrictedHandles;
    }

    Map<BluetoothDevice, Integer> getCachedPeripheralLatency() {
        enforceGattThread();
        return mCachedPeripheralLatency;
    }

    GattNativeInterface getNativeInterface() {
        return mNativeInterface;
    }

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onClientRegisteredFromNative(int status, int clientIf, UUID uuid) {
        enforceGattThread();
        Log.d(TAG, "onClientRegistered(): UUID=" + uuid + ", clientIf=" + clientIf);
        var app = mClientMap.getByUuid(uuid);
        if (app == null) {
            return;
        }
        var callback = app.getCallback();
        if (status != BluetoothGatt.GATT_SUCCESS) {
            mClientMap.remove(uuid, ContextMap.RemoveReason.REASON_REGISTER_FAILED);
        } else {
            app.setId(clientIf);
            var message = "Unregistering client " + app + ", callback=" + callback;
            var source = getAttributionSource();
            var died = REASON_BINDER_DIED;
            Runnable action = () -> doOnGattThread(() -> unregisterClient(callback, source, died));
            app.linkToDeath(new ActionOnDeathRecipient(TAG, message, action));
        }
        callbackToApp(() -> callback.onClientRegistered(status));
    }

    void onConnectedFromNative(
            int clientIf, int connId, int transport, int status, BluetoothDevice device) {
        enforceGattThread();
        Log.d(
                TAG,
                ("onConnected(): clientIf=" + clientIf + " connId=" + connId)
                        + (" transport=" + transportToString(transport))
                        + (" status=" + statusToString(status) + " device=" + device));
        int connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            getAdapterService().notifyGattClientConnectFailed(clientIf, device);
        } else {
            mClientMap.addConnection(clientIf, connId, transport, device);

            // Allow one writeCharacteristic operation at a time for each connected remote device.
            synchronized (mPermits) {
                Log.d(TAG, "onConnected(): Adding permit for device=" + device);
                mPermits.putIfAbsent(device, -1);
            }
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
        }

        var app = mClientMap.getById(clientIf);
        mMetricsReporter.logConnectionStateChange(device, clientIf, connectionState, status);
        if (app == null) {
            return;
        }
        final var connected = status == BluetoothGatt.GATT_SUCCESS;
        callbackToApp(() -> app.getCallback().onClientConnectionState(status, connected, device));
        mMetricsReporter.logConnectStatus(device, status, app.getUid());
    }

    void onDisconnectedFromNative(
            int clientIf, int connId, int transport, int status, BluetoothDevice device) {
        enforceGattThread();
        Log.d(
                TAG,
                ("onDisconnected(): clientIf=" + clientIf + ", connId=" + connId)
                        + (", transport=" + transportToString(transport) + ", device=" + device));
        mClientMap.removeConnection(clientIf, connId);
        getAdapterService().notifyGattClientDisconnect(clientIf, device);
        var app = mClientMap.getById(clientIf);
        mRestrictedHandles.remove(connId);

        // Remove AtomicBoolean representing permit if no other connections rely on this remote
        // device.
        if (!mClientMap.getConnectedDevices().contains(device)) {
            synchronized (mPermits) {
                Log.d(TAG, "onDisconnected(): Removing permit for device=" + device);
                mPermits.remove(device);
            }
        } else {
            synchronized (mPermits) {
                if (mPermits.get(device) == connId) {
                    Log.d(TAG, "onDisconnected(): Set permit -1 for device=" + device);
                    mPermits.put(device, -1);
                }
            }
        }

        mMetricsReporter.logConnectionStateChange(
                device, clientIf, BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED, status);
        if (app == null) {
            return;
        }
        switch (status) {
            case 0x00 -> { // HCI_SUCCESS
                status = GATT_SUCCESS;
            }
            case 0x08 -> { // HCI_ERR_CONNECTION_TOUT
                if (Flags.correctGattErrorCode()) {
                    status = GATT_CONNECTION_TIMEOUT;
                }
            }
            case 0x16 -> { // HCI_ERR_CONN_CAUSE_LOCAL_HOST
                if (getAdapterService().getKeyMissingCount(device) > 0) {
                    Log.d(
                            TAG,
                            "onDisconnected(): disconnected due to bond loss for device=" + device);
                    status = 0x05 /* HCI_ERR_AUTH_FAILURE */;
                }
            }
            default -> {
                if (Flags.correctGattErrorCode()) {
                    Log.w(TAG, "GATT disconnected reason=" + status);
                    status = GATT_FAILURE;
                }
            }
        }
        final int disconnectStatus = status;
        callbackToApp(
                () -> app.getCallback().onClientConnectionState(disconnectStatus, false, device));
        mMetricsReporter.logDisconnectSuccess(device, app.getUid());
    }

    void onClientPhyUpdateFromNative(int connId, int txPhy, int rxPhy, int status) {
        enforceGattThread();
        Log.d(TAG, "onClientPhyUpdate(): connId=" + connId + ", status=" + statusToString(status));

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onPhyUpdate(device, txPhy, rxPhy, status));
    }

    void onClientPhyReadFromNative(
            int clientIf, BluetoothDevice device, int txPhy, int rxPhy, int status) {
        enforceGattThread();
        Log.d(
                TAG,
                ("onClientPhyRead(): clientIf=" + clientIf + ", device=" + device)
                        + (", status=" + statusToString(status)));

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "onClientPhyRead(): no connection to " + device);
            return;
        }

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onPhyRead(device, txPhy, rxPhy, status));
    }

    void onClientConnUpdateFromNative(
            int connId, int interval, int latency, int timeout, int status) {
        enforceGattThread();
        Log.d(TAG, "onClientConnUpdate(): connId=" + connId + ", status=" + statusToString(status));

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        var app = mClientMap.getByConnId(connId);
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
        enforceGattThread();
        Log.d(TAG, "onServiceChanged(): connId=" + connId);

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(() -> app.getCallback().onServiceChanged(device));
    }

    void onClientSubrateChangeFromNative(
            int connId,
            int subrateFactor,
            int latency,
            int contNum,
            int timeout,
            int mode,
            int status) {
        enforceGattThread();
        Log.d(
                TAG,
                "onClientSubrateChange(): connId=" + connId + ", status=" + statusToString(status));

        int subrateMode;

        final var device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (status == BluetoothStatusCodes.SUCCESS) {
            if (Flags.leSubrateManager()) {
                subrateMode = updateGattSubratingMode(mode);
            } else {
                subrateMode = verifyGattSubratingMode(device, subrateFactor, latency, contNum);
            }
        } else {
            subrateMode = BluetoothGatt.SUBRATE_MODE_NOT_UPDATED;
        }
        callbackToApp(
                () ->
                        app.getCallback()
                                .onSubrateChange(device, subrateMode, translateHciCode(status)));
    }

    void onGetGattDbFromNative(int connId, List<GattDbElement> db) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.d(TAG, "onGetGattDb(): device=" + device);

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            Log.e(TAG, "onGetGattDb(): App is null");
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
                    Log.d(TAG, "Got service with UUID=" + el.uuid + " id=" + el.id);

                    currSrvc = new BluetoothGattService(el.uuid, el.id, el.type);
                    dbOut.add(currSrvc);
                    isRestrictedSrvc = isRestrictedSrvcUuid(el.uuid, device);
                    isHidSrvc = isHidSrvcUuid(el.uuid);
                    if (isRestrictedSrvc) {
                        restrictedIds.add(el.id);
                    }
                }
                case GattDbElement.TYPE_CHARACTERISTIC -> {
                    Log.d(TAG, "Got characteristic with UUID=" + el.uuid + " id=" + el.id);

                    currChar = new BluetoothGattCharacteristic(el.uuid, el.id, el.properties, 0);
                    currSrvc.addCharacteristic(currChar);
                    isRestrictedChar = isRestrictedSrvc || (isHidSrvc && isHidCharUuid(el.uuid));
                    if (isRestrictedChar) {
                        restrictedIds.add(el.id);
                    }
                }
                case GattDbElement.TYPE_DESCRIPTOR -> {
                    Log.d(TAG, "Got descriptor with UUID=" + el.uuid + " id=" + el.id);

                    currChar.addDescriptor(new BluetoothGattDescriptor(el.uuid, el.id, 0));
                    if (isRestrictedChar) {
                        restrictedIds.add(el.id);
                    }
                }
                case GattDbElement.TYPE_INCLUDED_SERVICE -> {
                    Log.d(
                            TAG,
                            ("Got included service with UUID=" + el.uuid)
                                    + (" id=" + el.id + " startHandle=" + el.startHandle));
                    currSrvc.addIncludedService(
                            new BluetoothGattService(el.uuid, el.startHandle, el.type));
                }
                default -> {
                    Log.e(
                            TAG,
                            ("Got unknown element with type=" + el.type)
                                    + (" and UUID=" + el.uuid + " id=" + el.id));
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
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.d(
                TAG,
                ("onRegisterForNotifications(): device=" + device)
                        + (", status=" + statusToString(status))
                        + (", registered=" + registered + ", handle=" + handle));
    }

    void onNotifyFromNative(
            int connId, BluetoothDevice device, int handle, boolean isNotify, byte[] data) {
        enforceGattThread();
        Log.v(
                TAG,
                "onNotify(): device=" + device + ", handle=" + handle + ", length=" + data.length);

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onNotify(device, handle, data));
    }

    void onReadCharacteristicFromNative(int connId, int status, int handle, byte[] data) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                ("onReadCharacteristic(): device=" + device)
                        + (", status=" + statusToString(status) + ", length=" + data.length));

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onCharacteristicRead(device, status, handle, data));
    }

    void onWriteCharacteristicFromNative(int connId, int status, int handle, byte[] data) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        synchronized (mPermits) {
            Log.d(TAG, "onWriteCharacteristic(): Increasing permit for device=" + device);
            mPermits.put(device, -1);
        }

        Log.v(
                TAG,
                ("onWriteCharacteristic(): device=" + device)
                        + (", status=" + statusToString(status) + ", length=" + data.length));

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested()) {
            callbackToApp(
                    () -> app.getCallback().onCharacteristicWrite(device, status, handle, data));
        } else {
            int queuedStatus = status;
            if (queuedStatus == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                queuedStatus = BluetoothGatt.GATT_SUCCESS;
            }
            var value = ByteString.copyFrom(data);
            app.queueCallback(new ContextApp.CallbackInfo(device, queuedStatus, handle, value));
        }
    }

    void onExecuteCompletedFromNative(int connId, int status) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(TAG, "onExecuteCompleted(): device=" + device + ", status=" + statusToString(status));

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onExecuteWrite(device, status));
    }

    void onReadDescriptorFromNative(int connId, int status, int handle, byte[] data) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                ("onReadDescriptor(): device=" + device)
                        + (", status=" + statusToString(status) + ", length=" + data.length));

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onDescriptorRead(device, status, handle, data));
    }

    void onWriteDescriptorFromNative(int connId, int status, int handle, byte[] data) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.v(
                TAG,
                ("onWriteDescriptor(): device=" + device)
                        + (", status=" + statusToString(status) + ", length=" + data.length));

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onDescriptorWrite(device, status, handle, data));
    }

    void onReadRemoteRssiFromNative(int clientIf, BluetoothDevice device, int rssi, int status) {
        enforceGattThread();
        Log.d(
                TAG,
                ("onReadRemoteRssi(): clientIf=" + clientIf + ", device=" + device)
                        + (", rssi=" + rssi + ", status=" + statusToString(status)));

        if (Flags.supportMultipleReadRssi()) {
            // TODO(b/449681465): Remove synchronized when the flag is removed.
            synchronized (mClientsPendingRssi) {
                if (!Objects.equals(mPendingRssiDevice, device)) {
                    Log.w(TAG, "Getting unexpected RSSI callback. requested=" + mPendingRssiDevice);
                }
                mPendingRssiDevice = null;
                if (!mClientsPendingRssi.contains(clientIf)) {
                    return;
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "onReadRemoteRssi(): Putting timestamp and rssi into cache");
                    mRssiCache.put(
                            device.getAddress(),
                            new RssiCacheEntry(mTimeProvider.elapsedRealtime(), rssi));
                }

                for (int client : mClientsPendingRssi) {
                    var app = mClientMap.getById(client);
                    if (app == null) {
                        continue;
                    }
                    callbackToApp(() -> app.getCallback().onReadRemoteRssi(device, rssi, status));
                }
                mClientsPendingRssi.clear();
            }
        } else {
            var app = mClientMap.getById(clientIf);
            if (app == null) {
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onReadRemoteRssi(): Putting timestamp and rssi into cache");
                mRssiCache.put(
                        device.getAddress(),
                        new RssiCacheEntry(mTimeProvider.elapsedRealtime(), rssi));
            }

            callbackToApp(() -> app.getCallback().onReadRemoteRssi(device, rssi, status));
        }
    }

    void onConfigureMTUFromNative(int connId, int status, int mtu) {
        enforceGattThread();
        final var device = mClientMap.deviceByConnId(connId);
        Log.d(
                TAG,
                ("onConfigureMTU(): device=" + device)
                        + (", status=" + statusToString(status) + ", mtu=" + mtu));

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        callbackToApp(() -> app.getCallback().onConfigureMTU(device, mtu, status));
    }

    void onClientCongestionFromNative(int connId, boolean congested) {
        enforceGattThread();
        Log.v(TAG, "onClientCongestion(): connId=" + connId + ", congested=" + congested);
        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }
        app.setCongested(congested);
        while (!app.isCongested()) {
            final var callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) {
                return;
            }
            callbackToApp(
                    () ->
                            app.getCallback()
                                    .onCharacteristicWrite(
                                            callbackInfo.getDevice(),
                                            callbackInfo.getStatus(),
                                            callbackInfo.getHandle(),
                                            callbackInfo.valueByteArray()));
        }
    }

    void onClientCharacteristicsUnoffloadedFromNative(int connId, int sessionId, int status) {
        enforceGattThread();
        Log.d(
                TAG,
                ("onClientCharacteristicsUnoffloadedFromNative(): connId=" + connId)
                        + (", sessionId=" + sessionId + ", status=" + status));

        BluetoothDevice device = mClientMap.deviceByConnId(connId);
        if (device == null) {
            return;
        }

        var app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        callbackToApp(
                () -> app.getCallback().onCharacteristicsUnoffloaded(device, sessionId, status));
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceGattThread();
        final Map<BluetoothDevice, Integer> deviceStates = new HashMap<>();

        // Add paired LE devices
        for (BluetoothDevice device : getAdapterService().getBondedDevices()) {
            if (getDeviceType(device) != AbstractionLayer.BT_DEVICE_TYPE_BREDR) {
                deviceStates.put(device, STATE_DISCONNECTED);
            }
        }

        // Add connected deviceStates
        final Set<BluetoothDevice> connectedDevices = new HashSet<>();
        connectedDevices.addAll(mClientMap.getConnectedDevices());
        connectedDevices.addAll(getServerMap().getConnectedDevices());

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
        enforceGattThread();
        Log.d(TAG, "disconnectAll()");
        final Map<Integer, BluetoothDevice> connMap = mClientMap.getConnectedMap();
        for (Map.Entry<Integer, BluetoothDevice> entry : connMap.entrySet()) {
            Log.d(TAG, "disconnecting addr:" + entry.getValue());
            clientDisconnectInternal(entry.getKey(), entry.getValue(), source);
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
        return connections.isEmpty() ? null : connections.get(0).getConnId();
    }

    void registerClient(
            UUID uuid,
            IBluetoothGattCallback callback,
            boolean eattSupport,
            int transport,
            AttributionSource source) {
        enforceGattThread();
        int uid = Flags.gattThread() ? source.getUid() : Binder.getCallingUid();
        if (mClientMap.countByAppUid(uid) >= GATT_CLIENT_LIMIT_PER_APP) {
            Log.w(TAG, "registerClient(): Failed due to too many clients");
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
                ("registerClient(): UUID=" + uuid + " name=" + name)
                        + (" transport=" + transportToString(transport)));
        var appName = Util.appNameOrUnknown(getAdapterService(), uid);
        mClientMap.add(uid, appName, uuid, callback, transport, tag);
        mNativeInterface.gattClientRegisterApp(uuid, name, eattSupport);
    }

    void unregisterClient(
            IBluetoothGattCallback callback,
            AttributionSource source,
            ContextMap.RemoveReason reason) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "unregisterClient(" + callback + "): Already unregistered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(TAG, "unregisterClient(" + callback + "): clientIf=" + clientIf);
        for (ContextMap.Connection conn : mClientMap.getConnectionByApp(clientIf)) {
            mMetricsReporter.logDisconnectEnd(conn.getDevice(), source.getUid());
        }
        if (mClientMap.remove(clientIf, reason) == null) {
            Log.w(TAG, "Failed to remove client: clientIf=" + clientIf);
            return;
        }
        mNativeInterface.gattClientUnregisterApp(clientIf);
    }

    void clientConnect(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            boolean autoMtuEnabled,
            AttributionSource source) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientConnect(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(
                TAG,
                ("clientConnect(): device=" + device)
                        + (", transport=" + transportToString(transport))
                        + (", addressType=" + addressType)
                        + (", isDirect=" + isDirect)
                        + (", opportunistic=" + opportunistic)
                        + (", autoMtuEnabled=" + autoMtuEnabled));
        mMetricsReporter.logAppPackage(clientIf, device, source.getUid());
        mMetricsReporter.logClientForegroundInfo(source.getUid(), isDirect);
        mMetricsReporter.logConnectionStateChange(
                device, clientIf, BluetoothProtoEnums.CONNECTION_STATE_CONNECTING, -1);
        mMetricsReporter.logConnect(device, isDirect, source.getUid());
        int preferredMtu = 0;

        final var packageName = source.getPackageName();
        boolean preferRelaxMode = false;
        final var tag = getLastAttributionTag(source);
        if (tag != null && GATT_CLIENTS_PREFER_RELAX_MODE.stream().anyMatch(tag::endsWith)) {
            preferRelaxMode = true;
        }
        Log.d(TAG, "clientConnect(): tag=" + tag + ", preferRelaxMode=" + preferRelaxMode);
        if (packageName != null) {
            getAdapterService().addAssociatedPackage(device, packageName);

            // Some apps expect MTU to be exchanged immediately on connections
            for (Map.Entry<String, Integer> entry : EARLY_MTU_EXCHANGE_PACKAGES.entrySet()) {
                if (packageName.contains(entry.getKey())) {
                    preferredMtu = entry.getValue();
                    Log.i(
                            TAG,
                            ("Early MTU exchange preference (" + preferredMtu + ")")
                                    + (" requested for " + packageName));
                    break;
                }
            }
        }

        if (transport != TRANSPORT_BREDR && isDirect && !opportunistic) {
            if (!Flags.gattConnSettings()) {
                String attributionTag = getLastAttributionTag(source);
                if (packageName != null) {
                    for (Map.Entry<String, String> entry :
                            GATT_CLIENTS_NOTIFY_TO_ADAPTER_PACKAGES.entrySet()) {
                        if (packageName.contains(entry.getKey())
                                && ((attributionTag != null
                                                && attributionTag.contains(entry.getValue()))
                                        || entry.getValue().isEmpty())) {
                            getAdapterService().notifyDirectLeGattClientConnect(clientIf, device);
                            break;
                        }
                    }
                }
            } else {
                // This logic prevents app-initiated GATT connections from hijacking an active LE
                // Audio stream, controlled by the DONOT_STEAL_AUDIO_ON_GATT_CONN compatibility
                // flag.
                boolean disableLeAudio = false;
                if (Flags.gattThread()) {
                    disableLeAudio =
                            CompatChanges.isChangeEnabled(
                                            DONOT_STEAL_AUDIO_ON_GATT_CONN, source.getUid())
                                    && SdkLevel.isAtLeastC();
                } else {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        disableLeAudio =
                                CompatChanges.isChangeEnabled(
                                                DONOT_STEAL_AUDIO_ON_GATT_CONN, source.getUid())
                                        && SdkLevel.isAtLeastC();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                if (disableLeAudio) {
                    // Notify gatt connection trigger from connectGatt to LeAudio so that It will
                    // mark the device as not available for LeAudio
                    Log.i(TAG, "clientConnect(): notifyDirectLeGattClientConnect");
                    getAdapterService().notifyDirectLeGattClientConnect(clientIf, device);
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
                preferredMtu,
                preferRelaxMode,
                autoMtuEnabled);
    }

    void clientDisconnect(
            IBluetoothGattCallback callback, BluetoothDevice device, AttributionSource source) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientDisconnect(" + callback + "): App not registered");
            return;
        }
        clientDisconnectInternal(clientApp.getId(), device, source);
    }

    private void clientDisconnectInternal(
            int clientIf, BluetoothDevice device, AttributionSource source) {
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        Log.d(TAG, "clientDisconnectInternal(): device=" + device + ", connId=" + connId);
        mMetricsReporter.logConnectionStateChange(
                device, clientIf, BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTING, -1);
        mMetricsReporter.logDisconnectStart(device, source.getUid());
        getAdapterService().notifyGattClientDisconnect(clientIf, device);
        mNativeInterface.gattClientDisconnect(clientIf, device, connId != null ? connId : 0);
    }

    void clientSetPreferredPhy(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int txPhy,
            int rxPhy,
            int phyOptions) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientSetPreferredPhy(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "clientSetPreferredPhy(): No connection to " + device);
            return;
        }

        Log.d(TAG, "clientSetPreferredPhy(): device=" + device + ", connId=" + connId);
        mNativeInterface.gattClientSetPreferredPhy(clientIf, device, txPhy, rxPhy, phyOptions);
    }

    void clientReadPhy(IBluetoothGattCallback callback, BluetoothDevice device) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "clientReadPhy(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.d(TAG, "clientReadPhy(): No connection to " + device);
            return;
        }

        Log.d(TAG, "clientReadPhy(): Device=" + device + ", connId=" + connId);
        mNativeInterface.gattClientReadPhy(clientIf, device);
    }

    void refreshDevice(IBluetoothGattCallback callback, BluetoothDevice device) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "refreshDevice(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(TAG, "refreshDevice(): device=" + device);
        mNativeInterface.gattClientRefresh(clientIf, device);
    }

    void discoverServices(IBluetoothGattCallback callback, BluetoothDevice device) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "discoverServices(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        Log.d(TAG, "discoverServices(): device=" + device + ", connId=" + connId);

        if (connId != null) {
            mNativeInterface.gattClientSearchService(connId, true, new UUID(0, 0));
        } else {
            Log.e(TAG, "discoverServices(): No connection for " + device);
        }
    }

    void discoverServiceByUuid(IBluetoothGattCallback callback, BluetoothDevice device, UUID uuid) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "discoverServiceByUuid(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientDiscoverServiceByUuid(connId, uuid);
        } else {
            Log.e(TAG, "discoverServiceByUuid(): No connection for " + device);
        }
    }

    void readCharacteristic(
            IBluetoothGattCallback callback, BluetoothDevice device, int handle, int authReq) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readCharacteristic(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.v(TAG, "readCharacteristic(" + device + ")");
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readCharacteristic(" + device + "): No connection");
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
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readUsingCharacteristicUuid(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.v(TAG, "readUsingCharacteristicUuid(): device=" + device);
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readUsingCharacteristicUuid(): No connection for " + device);
            return;
        }

        mNativeInterface.gattClientReadUsingCharacteristicUuid(
                connId, uuid, startHandle, endHandle, authReq);
    }

    int writeCharacteristic(
            IBluetoothGattCallback callback,
            BluetoothDevice device,
            int handle,
            int writeType,
            int authReq,
            byte[] value) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeCharacteristic(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var clientIf = clientApp.getId();
        Log.v(TAG, "writeCharacteristic(" + device + ")");
        if (mReliableQueue.contains(device)) {
            writeType = 3; // Prepared write
        }
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "writeCharacteristic(" + device + "): No connection");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }

        Log.d(TAG, "writeCharacteristic(): Trying to acquire permit");
        // Lock the thread until onCharacteristicWrite callback comes back.
        synchronized (mPermits) {
            final var permit = mPermits.get(device);
            if (permit == null) {
                Log.d(TAG, "writeCharacteristic(): atomicBoolean uninitialized!");
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
            }

            final var success = (permit == -1);
            if (!success) {
                Log.d(TAG, "writeCharacteristic(): No permit available");
                return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            }
            mPermits.put(device, connId);
        }

        mNativeInterface.gattClientWriteCharacteristic(connId, handle, writeType, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    void readDescriptor(
            IBluetoothGattCallback callback, BluetoothDevice device, int handle, int authReq) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readDescriptor(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.v(TAG, "readDescriptor(): device=" + device);

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "readDescriptor(): No connection for " + device);
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
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeDescriptor(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var clientIf = clientApp.getId();
        Log.v(TAG, "writeDescriptor(): device=" + device);

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "writeDescriptor(): No connection for " + device);
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }

        mNativeInterface.gattClientWriteDescriptor(connId, handle, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    void beginReliableWrite(BluetoothDevice device) {
        enforceGattThread();
        Log.d(TAG, "beginReliableWrite(): device=" + device);
        mReliableQueue.add(device);
    }

    void endReliableWrite(
            IBluetoothGattCallback callback, BluetoothDevice device, boolean execute) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "endReliableWrite(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(TAG, "endReliableWrite(): device=" + device + " execute=" + execute);
        mReliableQueue.remove(device);

        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientExecuteWrite(connId, execute);
        }
    }

    void registerForNotification(
            IBluetoothGattCallback callback, BluetoothDevice device, int handle, boolean enable) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "writeDescriptor(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(TAG, "registerForNotification(): device=" + device + " enable: " + enable);
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId == null) {
            Log.e(TAG, "registerForNotification(): No connection for " + device);
            return;
        }

        mNativeInterface.gattClientRegisterForNotifications(clientIf, device, handle, enable);
    }

    boolean readRemoteRssi(IBluetoothGattCallback callback, BluetoothDevice device) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "readRemoteRssi(" + callback + "): App not registered");
            return false;
        }
        final var clientIf = clientApp.getId();
        Log.d(TAG, "readRemoteRssi(): device=" + device);
        if (mRssiReadThrottleMs > 0) {
            final var entry = mRssiCache.get(device.getAddress());
            if (entry != null
                    && (mTimeProvider.elapsedRealtime() - entry.readTimeStamp)
                            < mRssiReadThrottleMs) {
                Log.d(TAG, "readRemoteRssi(): Rssi value found in cache, returning to callback");
                callbackToApp(
                        () ->
                                clientApp
                                        .getCallback()
                                        .onReadRemoteRssi(
                                                device, entry.rssi, BluetoothGatt.GATT_SUCCESS));
                return true;
            }
        }
        if (Flags.supportMultipleReadRssi()) {
            // TODO(b/449681465): Remove synchronized when the flag is removed.
            synchronized (mClientsPendingRssi) {
                if (mClientsPendingRssi.isEmpty()) {
                    mPendingRssiDevice = device;
                    mNativeInterface.gattClientReadRemoteRssi(clientIf, device);
                }
                // The controller is reading the RSSI of another device.
                if (!Objects.equals(mPendingRssiDevice, device)) {
                    Log.d(TAG, "Ignore RSSI request because it's busy.");
                    return false;
                } else {
                    mClientsPendingRssi.add(clientIf);
                }
            }
        } else {
            mNativeInterface.gattClientReadRemoteRssi(clientIf, device);
        }
        return true;
    }

    void configureMTU(IBluetoothGattCallback callback, BluetoothDevice device, int mtu) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "configureMTU(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(TAG, "configureMTU(): device=" + device + " mtu=" + mtu);
        final var connId = getFirstConnectionIdForDevice(clientIf, device);
        if (connId != null) {
            mNativeInterface.gattClientConfigureMTU(connId, mtu);
        } else {
            Log.e(TAG, "configureMTU(): No connection for " + device);
        }
    }

    void connectionParameterUpdate(
            IBluetoothGattCallback callback, BluetoothDevice device, int connectionPriority) {
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "connectionParameterUpdate(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        final var companionManager = getAdapterService().getCompanionManager();
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

        final int timeout = companionManager.getGattSupervisionTimeout(device);
        Log.d(
                TAG,
                ("connectionParameterUpdate(): device=" + device + ", params=" + connectionPriority)
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
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "leConnectionUpdate(" + callback + "): App not registered");
            return;
        }
        final var clientIf = clientApp.getId();
        Log.d(
                TAG,
                ("leConnectionUpdate(): device=" + device)
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
        enforceGattThread();
        var clientApp = mClientMap.getByCallbackId(callback);
        if (clientApp == null) {
            Log.w(TAG, "subrateModeRequest(" + callback + "): App not registered");
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
        }
        final var clientIf = clientApp.getId();

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

        final int supervisionTimeout =
                getAdapterService().getCompanionManager().getGattSupervisionTimeout(device);

        // Confirm flag config
        if (Flags.leSubrateManager()) {
            Log.d(TAG, ("subrateModeRequest(" + device + ", " + subrateMode + ") - "));
            return mNativeInterface.gattSubrateModeRequest(clientIf, device, subrateMode);
        } else {
            Log.d(
                    TAG,
                    ("subrateModeRequest(" + device + ", " + subrateMode + "): ")
                            + (" subrate min/max=" + subrateMin + "/" + subrateMax)
                            + (", maxLatency=" + maxLatency + ", continuationNumber=" + contNumber)
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
    }

    private boolean shouldBlockMessaging(BluetoothDevice device) {
        if (Flags.checkMapclientConnectionPolicyForAncs()) {
            return getAdapterService()
                    .getMapClientService()
                    .map(
                            mapClientService ->
                                    mapClientService.getConnectionPolicy(device)
                                            != CONNECTION_POLICY_ALLOWED)
                    .orElse(false);
        } else {
            return getAdapterService().getMessageAccessPermission(device)
                    != BluetoothDevice.ACCESS_ALLOWED;
        }
    }

    private boolean isRestrictedSrvcUuid(final UUID uuid, BluetoothDevice device) {
        return isFidoSrvcUuid(uuid)
                || isAndroidTvRemoteSrvcUuid(uuid)
                || isLeAudioSrvcUuid(uuid)
                || isAndroidHeadtrackerSrvcUuid(uuid)
                || (isAppleNotificationCenterSrvcUuid(uuid) && shouldBlockMessaging(device));
    }

    private int getDeviceType(BluetoothDevice device) {
        int type = mNativeInterface.gattClientGetDeviceType(device);
        Log.d(TAG, "getDeviceType(): device=" + device + ", type=" + type);
        return type;
    }

    /**
     * Updates the GATT connection subrating mode of the device
     *
     * @param subrateMode for this LE connection.
     * @return the connection subrating priority in integer defined in GATT framework
     */
    int updateGattSubratingMode(int subrateMode) {
        int returnSubrateMode = BluetoothGatt.SUBRATE_MODE_SYSTEM_UPDATE;
        if (subrateMode <= BluetoothGatt.SUBRATE_MODE_HIGH) returnSubrateMode = subrateMode;
        return returnSubrateMode;
    }

    /**
     * Verifies the GATT connection subrating parameters of the device
     *
     * @param subrateFactor for this LE connection.
     * @param latency Worker latency for this LE connection in number of connection events.
     * @param contNum Continuation Number for this LE connection.
     * @return the connection subrating priority in integer
     */
    int verifyGattSubratingMode(
            BluetoothDevice device, int subrateFactor, int latency, int contNum) {
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
                && latency == mCachedPeripheralLatency.getOrDefault(device, 0)
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

    void doOnGattThread(Runnable r) {
        if (!Flags.gattThread()) {
            r.run();
            return;
        }
        enforceGattThreadIsNotUsed();
        if (!isAvailable()) return;
        var posted =
                mGattHandler.post(
                        () -> {
                            if (isAvailable()) {
                                r.run();
                            }
                        });
        if (!posted) {
            Log.w(TAG, "Failed to post async task\n" + Log.getStackTraceString(new Throwable()));
        }
    }

    private void forceRunSyncOnGattThread(Runnable r) {
        if (!Flags.gattThread() || Util.isInstrumentationTestMode()) {
            r.run();
            return;
        }
        enforceGattThreadIsNotUsed();
        var future = new CompletableFuture<>();
        var posted =
                mGattHandler.postAtFrontOfQueue(
                        () -> {
                            r.run();
                            future.complete(null);
                        });
        if (!posted) {
            Log.w(TAG, "Failed to post sync task\n" + Log.getStackTraceString(new Throwable()));
            return;
        }
        try {
            future.get(RUN_SYNC_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            Log.w(TAG, "Failed to complete sync task: " + e);
        }
    }

    <T> T fetchOnGattThread(Supplier<T> supplier, T defaultValue) {
        if (!Flags.gattThread()) {
            return supplier.get();
        }
        enforceGattThreadIsNotUsed();
        if (!isAvailable()) return defaultValue;
        final var task =
                new FutureTask<>(
                        () -> {
                            if (!isAvailable()) {
                                return defaultValue;
                            }
                            return supplier.get();
                        });
        if (!mGattHandler.post(task)) {
            Log.w(TAG, "Failed to post async task\n" + Log.getStackTraceString(new Throwable()));
            return defaultValue;
        }
        try {
            return task.get(RUN_SYNC_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.w(TAG, "Failed to complete fetch sync task: " + e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            task.cancel(true);
        }
        return defaultValue;
    }

    // TODO(b/377424060) Remove when "use internal APIs instead of framework APIs" is fixed
    boolean isOnGattThread() {
        if (!Flags.gattThread() || Util.isInstrumentationTestMode()) return false;
        return mGattHandler.getLooper().isCurrentThread();
    }

    void enforceGattThread() {
        if (!Flags.gattThread() || Util.isInstrumentationTestMode()) return;

        if (!mGattHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Not on gatt thread");
        }
    }

    private void enforceGattThreadIsNotUsed() {
        if (!Flags.gattThread() || Util.isInstrumentationTestMode()) return;

        if (mGattHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Must NOT be on gatt thread");
        }
    }
}
