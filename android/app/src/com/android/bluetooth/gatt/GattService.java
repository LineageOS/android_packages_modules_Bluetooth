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

import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;
import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.enforceBluetoothPrivilegedPermission;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.ChannelSoundingParams;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.WorkSource;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.text.format.DateUtils;
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
import com.android.bluetooth.le_scan.TransitionalScanHelper;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.HexEncoding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provides Bluetooth Gatt profile, as a service in the Bluetooth application. */
public class GattService extends ProfileService {
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "GattService";

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

    /** Example raw beacons captured from a Blue Charm BC011 */
    private static final String[] TEST_MODE_BEACONS =
            new String[] {
                "020106",
                "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1716AAFE00EE626C7565636861726D31000000000001000009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080EFE13551109426C7565436861726D5F313639363835000000000000000000",
                "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C509168020691E0EFE13551109426C7565436861726D5F31363936383500000000",
            };

    public final TransitionalScanHelper mTransitionalScanHelper =
            new TransitionalScanHelper(this, this::isTestModeEnabled);

    /** This is only used when Flags.scanManagerRefactor() is true. */
    private static GattService sGattService;

    /** List of our registered advertisers. */
    static class AdvertiserMap extends ContextMap<IAdvertisingSetCallback, Void> {}

    private AdvertiserMap mAdvertiserMap = new AdvertiserMap();

    /** List of our registered clients. */
    static class ClientMap extends ContextMap<IBluetoothGattCallback, Void> {}

    ClientMap mClientMap = new ClientMap();

    /** List of our registered server apps. */
    static class ServerMap extends ContextMap<IBluetoothGattServerCallback, Void> {}

    ServerMap mServerMap = new ServerMap();

    /** Server handle map. */
    HandleMap mHandleMap = new HandleMap();

    private List<UUID> mAdvertisingServiceUuids = new ArrayList<UUID>();

    /**
     * Set of restricted (which require a BLUETOOTH_PRIVILEGED permission) handles per connectionId.
     */
    @VisibleForTesting final Map<Integer, Set<Integer>> mRestrictedHandles = new HashMap<>();

    /**
     * HashMap used to synchronize writeCharacteristic calls mapping remote device address to
     * available permit (connectId or -1).
     */
    private final HashMap<String, Integer> mPermits = new HashMap<>();

    private AdapterService mAdapterService;
    AdvertiseManager mAdvertiseManager;
    DistanceMeasurementManager mDistanceMeasurementManager;
    private Handler mTestModeHandler;
    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private final Object mTestModeLock = new Object();

    public GattService(Context ctx) {
        super(ctx);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileGattEnabled().orElse(true);
    }

    /** Reliable write queue */
    @VisibleForTesting Set<String> mReliableQueue = new HashSet<String>();

    private GattNativeInterface mNativeInterface;

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothGattBinder(this);
    }

    @Override
    public void start() {
        Log.d(TAG, "start()");

        if (Flags.scanManagerRefactor() && sGattService != null) {
            throw new IllegalStateException("start() called twice");
        }

        Settings.Global.putInt(
                getContentResolver(), "bluetooth_sanitized_exposure_notification_supported", 1);

        mNativeInterface = GattObjectsFactory.getInstance().getNativeInterface();
        mNativeInterface.init(this);
        mAdapterService = AdapterService.getAdapterService();
        mAdvertiseManager =
                new AdvertiseManager(
                        this, AdvertiseManagerNativeInterface.getInstance(), mAdvertiserMap);

        if (!Flags.scanManagerRefactor()) {
            HandlerThread thread = new HandlerThread("BluetoothScanManager");
            thread.start();
            mTransitionalScanHelper.start(thread.getLooper());
        }
        mDistanceMeasurementManager =
                GattObjectsFactory.getInstance().createDistanceMeasurementManager(mAdapterService);

        mActivityManager = getSystemService(ActivityManager.class);
        mPackageManager = mAdapterService.getPackageManager();

        if (Flags.scanManagerRefactor()) {
            setGattService(this);
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop()");
        if (Flags.scanManagerRefactor() && sGattService == null) {
            Log.w(TAG, "stop() called before start()");
            return;
        }

        if (Flags.scanManagerRefactor()) {
            setGattService(null);
        }

        mTransitionalScanHelper.stop();
        mAdvertiserMap.clear();
        mClientMap.clear();
        if (Flags.gattCleanupRestrictedHandles()) {
            mRestrictedHandles.clear();
        }
        mServerMap.clear();
        mHandleMap.clear();
        mReliableQueue.clear();
        cleanup();
    }

    @Override
    public void cleanup() {
        Log.d(TAG, "cleanup()");
        if (mNativeInterface != null) {
            mNativeInterface.cleanup();
            mNativeInterface = null;
        }
        if (mAdvertiseManager != null) {
            mAdvertiseManager.cleanup();
        }
        if (mDistanceMeasurementManager != null) {
            mDistanceMeasurementManager.cleanup();
        }
        mTransitionalScanHelper.cleanup();
    }

    /** This is only used when Flags.scanManagerRefactor() is true. */
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

    TransitionalScanHelper getTransitionalScanHelper() {
        return mTransitionalScanHelper;
    }

    // While test mode is enabled, pretend as if the underlying stack
    // discovered a specific set of well-known beacons every second
    @Override
    protected void setTestModeEnabled(boolean enableTestMode) {
        synchronized (mTestModeLock) {
            if (mTestModeHandler == null) {
                mTestModeHandler =
                        new Handler(getMainLooper()) {
                            public void handleMessage(Message msg) {
                                synchronized (mTestModeLock) {
                                    if (!GattService.this.isTestModeEnabled()) {
                                        return;
                                    }
                                    for (String test : TEST_MODE_BEACONS) {
                                        mTransitionalScanHelper.onScanResultInternal(
                                                0x1b,
                                                0x1,
                                                "DD:34:02:05:5C:4D",
                                                1,
                                                0,
                                                0xff,
                                                127,
                                                -54,
                                                0x0,
                                                HexEncoding.decode(test),
                                                "DD:34:02:05:5C:4E");
                                    }
                                    sendEmptyMessageDelayed(0, DateUtils.SECOND_IN_MILLIS);
                                }
                            }
                        };
            }
            if (enableTestMode && !isTestModeEnabled()) {
                super.setTestModeEnabled(true);
                mTestModeHandler.removeMessages(0);
                mTestModeHandler.sendEmptyMessageDelayed(0, DateUtils.SECOND_IN_MILLIS);
            } else if (!enableTestMode && isTestModeEnabled()) {
                super.setTestModeEnabled(false);
                mTestModeHandler.removeMessages(0);
                mTestModeHandler.sendEmptyMessage(0);
            }
        }
    }

    // Suppressed because we are conditionally enforcing
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void permissionCheck(UUID characteristicUuid) {
        if (!isHidCharUuid(characteristicUuid)) {
            return;
        }
        enforceBluetoothPrivilegedPermission(this);
    }

    // Suppressed because we are conditionally enforcing
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void permissionCheck(int connId, int handle) {
        if (!isHandleRestricted(connId, handle)) {
            return;
        }
        enforceBluetoothPrivilegedPermission(this);
    }

    private boolean isHandleRestricted(int connId, int handle) {
        Set<Integer> restrictedHandles = mRestrictedHandles.get(connId);
        return restrictedHandles != null && restrictedHandles.contains(handle);
    }

    /** Notify Scan manager of bluetooth profile connection state changes */
    public void notifyProfileConnectionStateChange(int profile, int fromState, int toState) {
        mTransitionalScanHelper.notifyProfileConnectionStateChange(profile, fromState, toState);
    }

    class ServerDeathRecipient implements IBinder.DeathRecipient {
        int mAppIf;
        private String mPackageName;

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
        private String mPackageName;

        ClientDeathRecipient(int appIf, String packageName) {
            mAppIf = appIf;
            mPackageName = packageName;
        }

        @Override
        public void binderDied() {
            Log.d(
                    TAG,
                    "Binder is dead - unregistering client (" + mPackageName + " " + mAppIf + ")!");
            unregisterClient(mAppIf, getAttributionSource());
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
            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            Log.e(TAG, "getService() - Service requested, but not available!");
            return null;
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states, attributionSource);
        }

        @Override
        public void registerClient(
                ParcelUuid uuid,
                IBluetoothGattCallback callback,
                boolean eattSupport,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.registerClient(uuid.getUuid(), callback, eattSupport, attributionSource);
        }

        @Override
        public void unregisterClient(int clientIf, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.unregisterClient(clientIf, attributionSource);
        }

        @Override
        public void registerScanner(
                IScannerCallback callback,
                WorkSource workSource,
                AttributionSource attributionSource)
                throws RemoteException {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .registerScanner(callback, workSource, attributionSource);
        }

        @Override
        public void unregisterScanner(int scannerId, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper().unregisterScanner(scannerId, attributionSource);
        }

        @Override
        public void startScan(
                int scannerId,
                ScanSettings settings,
                List<ScanFilter> filters,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .startScan(scannerId, settings, filters, attributionSource);
        }

        @Override
        public void startScanForIntent(
                PendingIntent intent,
                ScanSettings settings,
                List<ScanFilter> filters,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .registerPiAndStartScan(intent, settings, filters, attributionSource);
        }

        @Override
        public void stopScanForIntent(PendingIntent intent, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper().stopScan(intent, attributionSource);
        }

        @Override
        public void stopScan(int scannerId, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper().stopScan(scannerId, attributionSource);
        }

        @Override
        public void flushPendingBatchResults(int scannerId, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .flushPendingBatchResults(scannerId, attributionSource);
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
                AttributionSource attributionSource) {
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
                    attributionSource);
        }

        @Override
        public void clientDisconnect(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientDisconnect(clientIf, address, attributionSource);
        }

        @Override
        public void clientSetPreferredPhy(
                int clientIf,
                String address,
                int txPhy,
                int rxPhy,
                int phyOptions,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientSetPreferredPhy(
                    clientIf, address, txPhy, rxPhy, phyOptions, attributionSource);
        }

        @Override
        public void clientReadPhy(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clientReadPhy(clientIf, address, attributionSource);
        }

        @Override
        public void refreshDevice(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.refreshDevice(clientIf, address, attributionSource);
        }

        @Override
        public void discoverServices(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.discoverServices(clientIf, address, attributionSource);
        }

        @Override
        public void discoverServiceByUuid(
                int clientIf,
                String address,
                ParcelUuid uuid,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.discoverServiceByUuid(clientIf, address, uuid.getUuid(), attributionSource);
        }

        @Override
        public void readCharacteristic(
                int clientIf,
                String address,
                int handle,
                int authReq,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readCharacteristic(clientIf, address, handle, authReq, attributionSource);
        }

        @Override
        public void readUsingCharacteristicUuid(
                int clientIf,
                String address,
                ParcelUuid uuid,
                int startHandle,
                int endHandle,
                int authReq,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readUsingCharacteristicUuid(
                    clientIf,
                    address,
                    uuid.getUuid(),
                    startHandle,
                    endHandle,
                    authReq,
                    attributionSource);
        }

        @Override
        public int writeCharacteristic(
                int clientIf,
                String address,
                int handle,
                int writeType,
                int authReq,
                byte[] value,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            }
            return service.writeCharacteristic(
                    clientIf, address, handle, writeType, authReq, value, attributionSource);
        }

        @Override
        public void readDescriptor(
                int clientIf,
                String address,
                int handle,
                int authReq,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readDescriptor(clientIf, address, handle, authReq, attributionSource);
        }

        @Override
        public int writeDescriptor(
                int clientIf,
                String address,
                int handle,
                int authReq,
                byte[] value,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            }
            return service.writeDescriptor(
                    clientIf, address, handle, authReq, value, attributionSource);
        }

        @Override
        public void beginReliableWrite(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.beginReliableWrite(clientIf, address, attributionSource);
        }

        @Override
        public void endReliableWrite(
                int clientIf,
                String address,
                boolean execute,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.endReliableWrite(clientIf, address, execute, attributionSource);
        }

        @Override
        public void registerForNotification(
                int clientIf,
                String address,
                int handle,
                boolean enable,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.registerForNotification(clientIf, address, handle, enable, attributionSource);
        }

        @Override
        public void readRemoteRssi(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.readRemoteRssi(clientIf, address, attributionSource);
        }

        @Override
        public void configureMTU(
                int clientIf, String address, int mtu, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.configureMTU(clientIf, address, mtu, attributionSource);
        }

        @Override
        public void connectionParameterUpdate(
                int clientIf,
                String address,
                int connectionPriority,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.connectionParameterUpdate(
                    clientIf, address, connectionPriority, attributionSource);
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
                AttributionSource attributionSource) {
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
                    attributionSource);
        }

        @Override
        public void subrateModeRequest(
                int clientIf,
                String address,
                int subrateMode,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.subrateModeRequest(clientIf, address, subrateMode, attributionSource);
        }

        @Override
        public void leSubrateRequest(
                int clientIf,
                String address,
                int subrateMin,
                int subrateMax,
                int maxLatency,
                int contNumber,
                int supervisionTimeout,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.leSubrateRequest(
                    clientIf,
                    address,
                    subrateMin,
                    subrateMax,
                    maxLatency,
                    contNumber,
                    supervisionTimeout,
                    attributionSource);
        }

        @Override
        public void registerServer(
                ParcelUuid uuid,
                IBluetoothGattServerCallback callback,
                boolean eattSupport,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.registerServer(uuid.getUuid(), callback, eattSupport, attributionSource);
        }

        @Override
        public void unregisterServer(int serverIf, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.unregisterServer(serverIf, attributionSource);
        }

        @Override
        public void serverConnect(
                int serverIf,
                String address,
                int addressType,
                boolean isDirect,
                int transport,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverConnect(
                    serverIf, address, addressType, isDirect, transport, attributionSource);
        }

        @Override
        public void serverDisconnect(
                int serverIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverDisconnect(serverIf, address, attributionSource);
        }

        @Override
        public void serverSetPreferredPhy(
                int serverIf,
                String address,
                int txPhy,
                int rxPhy,
                int phyOptions,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverSetPreferredPhy(
                    serverIf, address, txPhy, rxPhy, phyOptions, attributionSource);
        }

        @Override
        public void serverReadPhy(
                int clientIf, String address, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.serverReadPhy(clientIf, address, attributionSource);
        }

        @Override
        public void addService(
                int serverIf, BluetoothGattService svc, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.addService(serverIf, svc, attributionSource);
        }

        @Override
        public void removeService(int serverIf, int handle, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.removeService(serverIf, handle, attributionSource);
        }

        @Override
        public void clearServices(int serverIf, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.clearServices(serverIf, attributionSource);
        }

        @Override
        public void sendResponse(
                int serverIf,
                String address,
                int requestId,
                int status,
                int offset,
                byte[] value,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.sendResponse(
                    serverIf, address, requestId, status, offset, value, attributionSource);
        }

        @Override
        public int sendNotification(
                int serverIf,
                String address,
                int handle,
                boolean confirm,
                byte[] value,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
            }
            return service.sendNotification(
                    serverIf, address, handle, confirm, value, attributionSource);
        }

        @Override
        public void startAdvertisingSet(
                AdvertisingSetParameters parameters,
                AdvertiseData advertiseData,
                AdvertiseData scanResponse,
                PeriodicAdvertisingParameters periodicParameters,
                AdvertiseData periodicData,
                int duration,
                int maxExtAdvEvents,
                int serverIf,
                IAdvertisingSetCallback callback,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.startAdvertisingSet(
                    parameters,
                    advertiseData,
                    scanResponse,
                    periodicParameters,
                    periodicData,
                    duration,
                    maxExtAdvEvents,
                    serverIf,
                    callback,
                    attributionSource);
        }

        @Override
        public void stopAdvertisingSet(
                IAdvertisingSetCallback callback, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.stopAdvertisingSet(callback, attributionSource);
        }

        @Override
        public void getOwnAddress(int advertiserId, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getOwnAddress(advertiserId, attributionSource);
        }

        @Override
        public void enableAdvertisingSet(
                int advertiserId,
                boolean enable,
                int duration,
                int maxExtAdvEvents,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.enableAdvertisingSet(
                    advertiserId, enable, duration, maxExtAdvEvents, attributionSource);
        }

        @Override
        public void setAdvertisingData(
                int advertiserId, AdvertiseData data, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.setAdvertisingData(advertiserId, data, attributionSource);
        }

        @Override
        public void setScanResponseData(
                int advertiserId, AdvertiseData data, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.setScanResponseData(advertiserId, data, attributionSource);
        }

        @Override
        public void setAdvertisingParameters(
                int advertiserId,
                AdvertisingSetParameters parameters,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.setAdvertisingParameters(advertiserId, parameters, attributionSource);
        }

        @Override
        public void setPeriodicAdvertisingParameters(
                int advertiserId,
                PeriodicAdvertisingParameters parameters,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.setPeriodicAdvertisingParameters(advertiserId, parameters, attributionSource);
        }

        @Override
        public void setPeriodicAdvertisingData(
                int advertiserId, AdvertiseData data, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.setPeriodicAdvertisingData(advertiserId, data, attributionSource);
        }

        @Override
        public void setPeriodicAdvertisingEnable(
                int advertiserId, boolean enable, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.setPeriodicAdvertisingEnable(advertiserId, enable, attributionSource);
        }

        @Override
        public void registerSync(
                ScanResult scanResult,
                int skip,
                int timeout,
                IPeriodicAdvertisingCallback callback,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .registerSync(scanResult, skip, timeout, callback, attributionSource);
        }

        @Override
        public void transferSync(
                BluetoothDevice bda,
                int serviceData,
                int syncHandle,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .transferSync(bda, serviceData, syncHandle, attributionSource);
        }

        @Override
        public void transferSetInfo(
                BluetoothDevice bda,
                int serviceData,
                int advHandle,
                IPeriodicAdvertisingCallback callback,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper()
                    .transferSetInfo(bda, serviceData, advHandle, callback, attributionSource);
        }

        @Override
        public void unregisterSync(
                IPeriodicAdvertisingCallback callback, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.getTransitionalScanHelper().unregisterSync(callback, attributionSource);
        }

        @Override
        public void disconnectAll(AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return;
            }
            service.disconnectAll(attributionSource);
        }

        @Override
        public int numHwTrackFiltersAvailable(AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return 0;
            }
            return service.getTransitionalScanHelper()
                    .numHwTrackFiltersAvailable(attributionSource);
        }

        @Override
        public List<DistanceMeasurementMethod> getSupportedDistanceMeasurementMethods(
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "GattService getSupportedDistanceMeasurementMethods")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "GattService getSupportedDistanceMeasurementMethods")) {
                return Collections.emptyList();
            }
            enforceBluetoothPrivilegedPermission(service);
            return Arrays.asList(service.getSupportedDistanceMeasurementMethods());
        }

        @Override
        public void startDistanceMeasurement(
                ParcelUuid uuid,
                DistanceMeasurementParams distanceMeasurementParams,
                IDistanceMeasurementCallback callback,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "startDistanceMeasurement")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "GattService startDistanceMeasurement")) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            service.startDistanceMeasurement(uuid.getUuid(), distanceMeasurementParams, callback);
        }

        @Override
        public int stopDistanceMeasurement(
                ParcelUuid uuid,
                BluetoothDevice device,
                int method,
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            } else if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "stopDistanceMeasurement")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            } else if (!Utils.checkConnectPermissionForDataDelivery(
                    service, attributionSource, "GattService stopDistanceMeasurement")) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.stopDistanceMeasurement(uuid.getUuid(), device, method);
        }

        @Override
        public int getChannelSoundingMaxSupportedSecurityLevel(
                BluetoothDevice remoteDevice, AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "GattService getChannelSoundingMaxSupportedSecurityLevel")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "GattService getChannelSoundingMaxSupportedSecurityLevel")) {
                return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.getChannelSoundingMaxSupportedSecurityLevel(remoteDevice);
        }

        @Override
        public int getLocalChannelSoundingMaxSupportedSecurityLevel(
                AttributionSource attributionSource) {
            GattService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service,
                            TAG,
                            "GattService getLocalChannelSoundingMaxSupportedSecurityLevel")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "GattService getLocalChannelSoundingMaxSupportedSecurityLevel")) {
                return ChannelSoundingParams.CS_SECURITY_LEVEL_UNKNOWN;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.getLocalChannelSoundingMaxSupportedSecurityLevel();
        }
    }
    ;

    /**************************************************************************
     * Callback functions - CLIENT
     *************************************************************************/

    void onClientRegistered(int status, int clientIf, long uuidLsb, long uuidMsb)
            throws RemoteException {
        UUID uuid = new UUID(uuidMsb, uuidLsb);
        Log.d(TAG, "onClientRegistered() - UUID=" + uuid + ", clientIf=" + clientIf);
        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByUuid(uuid);
        if (app != null) {
            if (status == 0) {
                app.id = clientIf;
                app.linkToDeath(new ClientDeathRecipient(clientIf, app.name));
            } else {
                mClientMap.remove(uuid);
            }
            app.callback.onClientRegistered(status, clientIf);
        }
    }

    void onConnected(int clientIf, int connId, int status, String address) throws RemoteException {
        Log.d(
                TAG,
                "onConnected() - clientIf="
                        + clientIf
                        + ", connId="
                        + connId
                        + ", address="
                        + address);
        int connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED;
        if (status == 0) {
            mClientMap.addConnection(clientIf, connId, address);

            // Allow one writeCharacteristic operation at a time for each connected remote device.
            synchronized (mPermits) {
                Log.d(TAG, "onConnected() - adding permit for address=" + address);
                mPermits.putIfAbsent(address, -1);
            }
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED;
        }
        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getById(clientIf);
        if (app != null) {
            app.callback.onClientConnectionState(
                    status, clientIf, (status == BluetoothGatt.GATT_SUCCESS), address);
        }
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT, address, clientIf, connectionState, status);
    }

    void onDisconnected(int clientIf, int connId, int status, String address)
            throws RemoteException {
        Log.d(
                TAG,
                "onDisconnected() - clientIf="
                        + clientIf
                        + ", connId="
                        + connId
                        + ", address="
                        + address);

        mClientMap.removeConnection(clientIf, connId);
        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getById(clientIf);

        if (Flags.gattCleanupRestrictedHandles()) {
            mRestrictedHandles.remove(connId);
        }

        // Remove AtomicBoolean representing permit if no other connections rely on this remote
        // device.
        if (!mClientMap.getConnectedDevices().contains(address)) {
            synchronized (mPermits) {
                Log.d(TAG, "onDisconnected() - removing permit for address=" + address);
                mPermits.remove(address);
            }
        } else {
            synchronized (mPermits) {
                if (mPermits.get(address) == connId) {
                    Log.d(TAG, "onDisconnected() - set permit -1 for address=" + address);
                    mPermits.put(address, -1);
                }
            }
        }

        if (app != null) {
            app.callback.onClientConnectionState(status, clientIf, false, address);
        }
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                address,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED,
                status);
    }

    void onClientPhyUpdate(int connId, int txPhy, int rxPhy, int status) throws RemoteException {
        Log.d(TAG, "onClientPhyUpdate() - connId=" + connId + ", status=" + status);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onPhyUpdate(address, txPhy, rxPhy, status);
    }

    void onClientPhyRead(int clientIf, String address, int txPhy, int rxPhy, int status)
            throws RemoteException {
        Log.d(
                TAG,
                "onClientPhyRead() - address="
                        + address
                        + ", status="
                        + status
                        + ", clientIf="
                        + clientIf);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.d(TAG, "onClientPhyRead() - no connection to " + address);
            return;
        }

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onPhyRead(address, txPhy, rxPhy, status);
    }

    void onClientConnUpdate(int connId, int interval, int latency, int timeout, int status)
            throws RemoteException {
        Log.d(TAG, "onClientConnUpdate() - connId=" + connId + ", status=" + status);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onConnectionUpdated(address, interval, latency, timeout, status);
    }

    void onServiceChanged(int connId) throws RemoteException {
        Log.d(TAG, "onServiceChanged - connId=" + connId);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onServiceChanged(address);
    }

    void onClientSubrateChange(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status)
            throws RemoteException {
        Log.d(TAG, "onClientSubrateChange() - connId=" + connId + ", status=" + status);

        String address = mClientMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onSubrateChange(address, subrateFactor, latency, contNum, timeout, status);
    }

    void onServerPhyUpdate(int connId, int txPhy, int rxPhy, int status) throws RemoteException {
        Log.d(TAG, "onServerPhyUpdate() - connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onPhyUpdate(address, txPhy, rxPhy, status);
    }

    void onServerPhyRead(int serverIf, String address, int txPhy, int rxPhy, int status)
            throws RemoteException {
        Log.d(TAG, "onServerPhyRead() - address=" + address + ", status=" + status);

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) {
            Log.d(TAG, "onServerPhyRead() - no connection to " + address);
            return;
        }

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onPhyRead(address, txPhy, rxPhy, status);
    }

    void onServerConnUpdate(int connId, int interval, int latency, int timeout, int status)
            throws RemoteException {
        Log.d(TAG, "onServerConnUpdate() - connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onConnectionUpdated(address, interval, latency, timeout, status);
    }

    void onServerSubrateChange(
            int connId, int subrateFactor, int latency, int contNum, int timeout, int status)
            throws RemoteException {
        Log.d(TAG, "onServerSubrateChange() - connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onSubrateChange(address, subrateFactor, latency, contNum, timeout, status);
    }

    void onSearchCompleted(int connId, int status) throws RemoteException {
        Log.d(TAG, "onSearchCompleted() - connId=" + connId + ", status=" + status);
        // Gatt DB is ready!

        // This callback was called from the jni_workqueue thread. If we make request to the stack
        // on the same thread, it might cause deadlock. Schedule request on a new thread instead.
        Thread t =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                mNativeInterface.gattClientGetGattDb(connId);
                            }
                        });
        t.start();
    }

    GattDbElement getSampleGattDbElement() {
        return new GattDbElement();
    }

    void onGetGattDb(int connId, ArrayList<GattDbElement> db) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);

        Log.d(TAG, "onGetGattDb() - address=" + address);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null || app.callback == null) {
            Log.e(TAG, "app or callback is null");
            return;
        }

        List<BluetoothGattService> dbOut = new ArrayList<BluetoothGattService>();
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
        app.callback.onSearchComplete(address, dbOut, 0 /* status */);
    }

    void onRegisterForNotifications(int connId, int status, int registered, int handle) {
        String address = mClientMap.addressByConnId(connId);

        Log.d(
                TAG,
                "onRegisterForNotifications() - address="
                        + address
                        + ", status="
                        + status
                        + ", registered="
                        + registered
                        + ", handle="
                        + handle);
    }

    void onNotify(int connId, String address, int handle, boolean isNotify, byte[] data)
            throws RemoteException {

        Log.v(
                TAG,
                "onNotify() - address="
                        + address
                        + ", handle="
                        + handle
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app != null) {
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
            app.callback.onNotify(address, handle, data);
        }
    }

    void onReadCharacteristic(int connId, int status, int handle, byte[] data)
            throws RemoteException {
        String address = mClientMap.addressByConnId(connId);

        Log.v(
                TAG,
                "onReadCharacteristic() - address="
                        + address
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onCharacteristicRead(address, status, handle, data);
        }
    }

    void onWriteCharacteristic(int connId, int status, int handle, byte[] data)
            throws RemoteException {
        String address = mClientMap.addressByConnId(connId);
        synchronized (mPermits) {
            Log.d(TAG, "onWriteCharacteristic() - increasing permit for address=" + address);
            mPermits.put(address, -1);
        }

        Log.v(
                TAG,
                "onWriteCharacteristic() - address="
                        + address
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            app.callback.onCharacteristicWrite(address, status, handle, data);
        } else {
            if (status == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                status = BluetoothGatt.GATT_SUCCESS;
            }
            CallbackInfo callbackInfo =
                    new CallbackInfo.Builder(address, status)
                            .setHandle(handle)
                            .setValue(data)
                            .build();
            app.queueCallback(callbackInfo);
        }
    }

    void onExecuteCompleted(int connId, int status) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);
        Log.v(TAG, "onExecuteCompleted() - address=" + address + ", status=" + status);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onExecuteWrite(address, status);
        }
    }

    void onReadDescriptor(int connId, int status, int handle, byte[] data) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);

        Log.v(
                TAG,
                "onReadDescriptor() - address="
                        + address
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onDescriptorRead(address, status, handle, data);
        }
    }

    void onWriteDescriptor(int connId, int status, int handle, byte[] data) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);

        Log.v(
                TAG,
                "onWriteDescriptor() - address="
                        + address
                        + ", status="
                        + status
                        + ", length="
                        + data.length);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onDescriptorWrite(address, status, handle, data);
        }
    }

    void onReadRemoteRssi(int clientIf, String address, int rssi, int status)
            throws RemoteException {
        Log.d(
                TAG,
                "onReadRemoteRssi() - clientIf="
                        + clientIf
                        + " address="
                        + address
                        + ", rssi="
                        + rssi
                        + ", status="
                        + status);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getById(clientIf);
        if (app != null) {
            app.callback.onReadRemoteRssi(address, rssi, status);
        }
    }

    void onConfigureMTU(int connId, int status, int mtu) throws RemoteException {
        String address = mClientMap.addressByConnId(connId);

        Log.d(TAG, "onConfigureMTU() address=" + address + ", status=" + status + ", mtu=" + mtu);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);
        if (app != null) {
            app.callback.onConfigureMTU(address, mtu, status);
        }
    }

    void onClientCongestion(int connId, boolean congested) throws RemoteException {
        Log.v(TAG, "onClientCongestion() - connId=" + connId + ", congested=" + congested);

        ContextMap<IBluetoothGattCallback, Void>.App app = mClientMap.getByConnId(connId);

        if (app != null) {
            app.isCongested = congested;
            while (!app.isCongested) {
                CallbackInfo callbackInfo = app.popQueuedCallback();
                if (callbackInfo == null) {
                    return;
                }
                app.callback.onCharacteristicWrite(
                        callbackInfo.address,
                        callbackInfo.status,
                        callbackInfo.handle,
                        callbackInfo.value);
            }
        }
    }

    /**************************************************************************
     * GATT Service functions - Shared CLIENT/SERVER
     *************************************************************************/

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService getDevicesMatchingConnectionStates")) {
            return Collections.emptyList();
        }

        Map<BluetoothDevice, Integer> deviceStates = new HashMap<BluetoothDevice, Integer>();

        // Add paired LE devices

        BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            if (getDeviceType(device) != AbstractionLayer.BT_DEVICE_TYPE_BREDR) {
                deviceStates.put(device, BluetoothProfile.STATE_DISCONNECTED);
            }
        }

        // Add connected deviceStates

        Set<String> connectedDevices = new HashSet<String>();
        connectedDevices.addAll(mClientMap.getConnectedDevices());
        connectedDevices.addAll(mServerMap.getConnectedDevices());

        for (String address : connectedDevices) {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
            if (device != null) {
                deviceStates.put(device, BluetoothProfile.STATE_CONNECTED);
            }
        }

        // Create matching device sub-set

        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();

        for (Map.Entry<BluetoothDevice, Integer> entry : deviceStates.entrySet()) {
            for (int state : states) {
                if (entry.getValue() == state) {
                    deviceList.add(entry.getKey());
                }
            }
        }

        return deviceList;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void disconnectAll(AttributionSource attributionSource) {
        Log.d(TAG, "disconnectAll()");
        Map<Integer, String> connMap = mClientMap.getConnectedMap();
        for (Map.Entry<Integer, String> entry : connMap.entrySet()) {
            Log.d(TAG, "disconnecting addr:" + entry.getValue());
            clientDisconnect(entry.getKey(), entry.getValue(), attributionSource);
            // clientDisconnect(int clientIf, String address)
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void unregAll(AttributionSource attributionSource) {
        for (Integer appId : mClientMap.getAllAppsIds()) {
            Log.d(TAG, "unreg:" + appId);
            unregisterClient(appId, attributionSource);
        }
    }

    /**************************************************************************
     * ADVERTISING SET
     *************************************************************************/
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void startAdvertisingSet(
            AdvertisingSetParameters parameters,
            AdvertiseData advertiseData,
            AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters,
            AdvertiseData periodicData,
            int duration,
            int maxExtAdvEvents,
            int serverIf,
            IAdvertisingSetCallback callback,
            AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService startAdvertisingSet")) {
            return;
        }
        if (parameters.getOwnAddressType() != AdvertisingSetParameters.ADDRESS_TYPE_DEFAULT
                || serverIf != 0) {
            Utils.enforceBluetoothPrivilegedPermission(this);
        }
        mAdvertiseManager.startAdvertisingSet(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                serverIf,
                callback);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void stopAdvertisingSet(IAdvertisingSetCallback callback, AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService stopAdvertisingSet")) {
            return;
        }
        mAdvertiseManager.stopAdvertisingSet(callback);
    }

    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            })
    void getOwnAddress(int advertiserId, AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService getOwnAddress")) {
            return;
        }
        enforceBluetoothPrivilegedPermission(this);
        mAdvertiseManager.getOwnAddress(advertiserId);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void enableAdvertisingSet(
            int advertiserId,
            boolean enable,
            int duration,
            int maxExtAdvEvents,
            AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService enableAdvertisingSet")) {
            return;
        }
        mAdvertiseManager.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void setAdvertisingData(
            int advertiserId, AdvertiseData data, AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService setAdvertisingData")) {
            return;
        }
        mAdvertiseManager.setAdvertisingData(advertiserId, data);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void setScanResponseData(
            int advertiserId, AdvertiseData data, AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService setScanResponseData")) {
            return;
        }
        mAdvertiseManager.setScanResponseData(advertiserId, data);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void setAdvertisingParameters(
            int advertiserId,
            AdvertisingSetParameters parameters,
            AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService setAdvertisingParameters")) {
            return;
        }
        mAdvertiseManager.setAdvertisingParameters(advertiserId, parameters);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void setPeriodicAdvertisingParameters(
            int advertiserId,
            PeriodicAdvertisingParameters parameters,
            AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService setPeriodicAdvertisingParameters")) {
            return;
        }
        mAdvertiseManager.setPeriodicAdvertisingParameters(advertiserId, parameters);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void setPeriodicAdvertisingData(
            int advertiserId, AdvertiseData data, AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService setPeriodicAdvertisingData")) {
            return;
        }
        mAdvertiseManager.setPeriodicAdvertisingData(advertiserId, data);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    void setPeriodicAdvertisingEnable(
            int advertiserId, boolean enable, AttributionSource attributionSource) {
        if (!Utils.checkAdvertisePermissionForDataDelivery(
                this, attributionSource, "GattService setPeriodicAdvertisingEnable")) {
            return;
        }
        mAdvertiseManager.setPeriodicAdvertisingEnable(advertiserId, enable);
    }

    /**************************************************************************
     * Distance Measurement
     *************************************************************************/

    DistanceMeasurementMethod[] getSupportedDistanceMeasurementMethods() {
        return mDistanceMeasurementManager.getSupportedDistanceMeasurementMethods();
    }

    void startDistanceMeasurement(
            UUID uuid,
            DistanceMeasurementParams distanceMeasurementParams,
            IDistanceMeasurementCallback callback) {
        mDistanceMeasurementManager.startDistanceMeasurement(
                uuid, distanceMeasurementParams, callback);
    }

    int stopDistanceMeasurement(UUID uuid, BluetoothDevice device, int method) {
        return mDistanceMeasurementManager.stopDistanceMeasurement(uuid, device, method, false);
    }

    int getChannelSoundingMaxSupportedSecurityLevel(BluetoothDevice remoteDevice) {
        return mDistanceMeasurementManager.getChannelSoundingMaxSupportedSecurityLevel(
                remoteDevice);
    }

    int getLocalChannelSoundingMaxSupportedSecurityLevel() {
        return mDistanceMeasurementManager.getLocalChannelSoundingMaxSupportedSecurityLevel();
    }

    /**************************************************************************
     * GATT Service functions - CLIENT
     *************************************************************************/

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void registerClient(
            UUID uuid,
            IBluetoothGattCallback callback,
            boolean eatt_support,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService registerClient")) {
            return;
        }

        Log.d(TAG, "registerClient() - UUID=" + uuid);
        mClientMap.add(uuid, null, callback, null, this, mTransitionalScanHelper);
        mNativeInterface.gattClientRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), eatt_support);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void unregisterClient(int clientIf, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService unregisterClient")) {
            return;
        }

        Log.d(TAG, "unregisterClient() - clientIf=" + clientIf);
        mClientMap.remove(clientIf);
        mNativeInterface.gattClientUnregisterApp(clientIf);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void clientConnect(
            int clientIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            boolean opportunistic,
            int phy,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService clientConnect")) {
            return;
        }

        Log.d(
                TAG,
                "clientConnect() - address="
                        + address
                        + ", addressType="
                        + addressType
                        + ", isDirect="
                        + isDirect
                        + ", opportunistic="
                        + opportunistic
                        + ", phy="
                        + phy);
        statsLogAppPackage(address, attributionSource.getUid(), clientIf);

        logClientForegroundInfo(attributionSource.getUid(), isDirect);

        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                address,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_CONNECTING,
                -1);
        mNativeInterface.gattClientConnect(
                clientIf, address, addressType, isDirect, transport, opportunistic, phy);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void clientDisconnect(int clientIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService clientDisconnect")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        Log.d(TAG, "clientDisconnect() - address=" + address + ", connId=" + connId);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT,
                address,
                clientIf,
                BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTING,
                -1);
        mNativeInterface.gattClientDisconnect(clientIf, address, connId != null ? connId : 0);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void clientSetPreferredPhy(
            int clientIf,
            String address,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService clientSetPreferredPhy")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.d(TAG, "clientSetPreferredPhy() - no connection to " + address);
            return;
        }

        Log.d(TAG, "clientSetPreferredPhy() - address=" + address + ", connId=" + connId);
        mNativeInterface.gattClientSetPreferredPhy(clientIf, address, txPhy, rxPhy, phyOptions);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void clientReadPhy(int clientIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService clientReadPhy")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.d(TAG, "clientReadPhy() - no connection to " + address);
            return;
        }

        Log.d(TAG, "clientReadPhy() - address=" + address + ", connId=" + connId);
        mNativeInterface.gattClientReadPhy(clientIf, address);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    synchronized List<ParcelUuid> getRegisteredServiceUuids(AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService getRegisteredServiceUuids")) {
            return Collections.emptyList();
        }
        List<ParcelUuid> serviceUuids = new ArrayList<ParcelUuid>();
        for (HandleMap.Entry entry : mHandleMap.getEntries()) {
            serviceUuids.add(new ParcelUuid(entry.uuid));
        }
        return serviceUuids;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    List<String> getConnectedDevices(AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService getConnectedDevices")) {
            return Collections.emptyList();
        }

        Set<String> connectedDevAddress = new HashSet<String>();
        connectedDevAddress.addAll(mClientMap.getConnectedDevices());
        connectedDevAddress.addAll(mServerMap.getConnectedDevices());
        List<String> connectedDeviceList = new ArrayList<String>(connectedDevAddress);
        return connectedDeviceList;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void refreshDevice(int clientIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService refreshDevice")) {
            return;
        }

        Log.d(TAG, "refreshDevice() - address=" + address);
        mNativeInterface.gattClientRefresh(clientIf, address);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void discoverServices(int clientIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService discoverServices")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        Log.d(TAG, "discoverServices() - address=" + address + ", connId=" + connId);

        if (connId != null) {
            mNativeInterface.gattClientSearchService(connId, true, 0, 0);
        } else {
            Log.e(TAG, "discoverServices() - No connection for " + address + "...");
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void discoverServiceByUuid(
            int clientIf, String address, UUID uuid, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService discoverServiceByUuid")) {
            return;
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            mNativeInterface.gattClientDiscoverServiceByUuid(
                    connId, uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
        } else {
            Log.e(TAG, "discoverServiceByUuid() - No connection for " + address + "...");
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void readCharacteristic(
            int clientIf,
            String address,
            int handle,
            int authReq,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService readCharacteristic")) {
            return;
        }

        Log.v(TAG, "readCharacteristic() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(TAG, "readCharacteristic() - No connection for " + address + "...");
            return;
        }

        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            String callingPackage = attributionSource.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readCharacteristic() - permission check failed!");
            return;
        }

        mNativeInterface.gattClientReadCharacteristic(connId, handle, authReq);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void readUsingCharacteristicUuid(
            int clientIf,
            String address,
            UUID uuid,
            int startHandle,
            int endHandle,
            int authReq,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService readUsingCharacteristicUuid")) {
            return;
        }

        Log.v(TAG, "readUsingCharacteristicUuid() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(TAG, "readUsingCharacteristicUuid() - No connection for " + address + "...");
            return;
        }

        try {
            permissionCheck(uuid);
        } catch (SecurityException ex) {
            String callingPackage = attributionSource.getPackageName();
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

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    int writeCharacteristic(
            int clientIf,
            String address,
            int handle,
            int writeType,
            int authReq,
            byte[] value,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService writeCharacteristic")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        Log.v(TAG, "writeCharacteristic() - address=" + address);

        if (mReliableQueue.contains(address)) {
            writeType = 3; // Prepared write
        }

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(TAG, "writeCharacteristic() - No connection for " + address + "...");
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

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void readDescriptor(
            int clientIf,
            String address,
            int handle,
            int authReq,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService readDescriptor")) {
            return;
        }

        Log.v(TAG, "readDescriptor() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(TAG, "readDescriptor() - No connection for " + address + "...");
            return;
        }

        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            String callingPackage = attributionSource.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "readDescriptor() - permission check failed!");
            return;
        }

        mNativeInterface.gattClientReadDescriptor(connId, handle, authReq);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    int writeDescriptor(
            int clientIf,
            String address,
            int handle,
            int authReq,
            byte[] value,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService writeDescriptor")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }
        Log.v(TAG, "writeDescriptor() - address=" + address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(TAG, "writeDescriptor() - No connection for " + address + "...");
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED;
        }
        permissionCheck(connId, handle);

        mNativeInterface.gattClientWriteDescriptor(connId, handle, authReq, value);
        return BluetoothStatusCodes.SUCCESS;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void beginReliableWrite(int clientIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService beginReliableWrite")) {
            return;
        }

        Log.d(TAG, "beginReliableWrite() - address=" + address);
        mReliableQueue.add(address);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void endReliableWrite(
            int clientIf, String address, boolean execute, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService endReliableWrite")) {
            return;
        }

        Log.d(TAG, "endReliableWrite() - address=" + address + " execute: " + execute);
        mReliableQueue.remove(address);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            mNativeInterface.gattClientExecuteWrite(connId, execute);
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void registerForNotification(
            int clientIf,
            String address,
            int handle,
            boolean enable,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService registerForNotification")) {
            return;
        }

        Log.d(TAG, "registerForNotification() - address=" + address + " enable: " + enable);

        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId == null) {
            Log.e(TAG, "registerForNotification() - No connection for " + address + "...");
            return;
        }

        try {
            permissionCheck(connId, handle);
        } catch (SecurityException ex) {
            String callingPackage = attributionSource.getPackageName();
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex;
            }
            Log.w(TAG, "registerForNotification() - permission check failed!");
            return;
        }

        mNativeInterface.gattClientRegisterForNotifications(clientIf, address, handle, enable);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void readRemoteRssi(int clientIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService readRemoteRssi")) {
            return;
        }

        Log.d(TAG, "readRemoteRssi() - address=" + address);
        mNativeInterface.gattClientReadRemoteRssi(clientIf, address);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void configureMTU(int clientIf, String address, int mtu, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService configureMTU")) {
            return;
        }

        Log.d(TAG, "configureMTU() - address=" + address + " mtu=" + mtu);
        Integer connId = mClientMap.connIdByAddress(clientIf, address);
        if (connId != null) {
            mNativeInterface.gattClientConfigureMTU(connId, mtu);
        } else {
            Log.e(TAG, "configureMTU() - No connection for " + address + "...");
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void connectionParameterUpdate(
            int clientIf,
            String address,
            int connectionPriority,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService connectionParameterUpdate")) {
            return;
        }

        int minInterval;
        int maxInterval;

        // Peripheral latency
        int latency;

        // Link supervision timeout is measured in N * 10ms
        int timeout = 500; // 5s

        CompanionManager manager = AdapterService.getAdapterService().getCompanionManager();

        minInterval =
                manager.getGattConnParameters(
                        address, CompanionManager.GATT_CONN_INTERVAL_MIN, connectionPriority);
        maxInterval =
                manager.getGattConnParameters(
                        address, CompanionManager.GATT_CONN_INTERVAL_MAX, connectionPriority);
        latency =
                manager.getGattConnParameters(
                        address, CompanionManager.GATT_CONN_LATENCY, connectionPriority);

        Log.d(
                TAG,
                "connectionParameterUpdate() - address="
                        + address
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

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void leConnectionUpdate(
            int clientIf,
            String address,
            int minInterval,
            int maxInterval,
            int peripheralLatency,
            int supervisionTimeout,
            int minConnectionEventLen,
            int maxConnectionEventLen,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService leConnectionUpdate")) {
            return;
        }

        Log.d(
                TAG,
                "leConnectionUpdate() - address="
                        + address
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

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void subrateModeRequest(
            int clientIf, String address, int subrateMode, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService subrateModeRequest")) {
            return;
        }

        int subrateMin;
        int subrateMax;
        int maxLatency;
        int contNumber;
        // Link supervision timeout is measured in N * 10ms
        int supervisionTimeout = 500; // 5s

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
                "subrateModeRequest() - "
                        + "address="
                        + BluetoothUtils.toAnonymizedAddress(address)
                        + ", subrate min/max="
                        + subrateMin
                        + "/"
                        + subrateMax
                        + ", maxLatency="
                        + maxLatency
                        + ", continuation Number="
                        + contNumber
                        + ", timeout="
                        + supervisionTimeout);

        mNativeInterface.gattSubrateRequest(
                clientIf,
                address,
                subrateMin,
                subrateMax,
                maxLatency,
                contNumber,
                supervisionTimeout);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void leSubrateRequest(
            int clientIf,
            String address,
            int subrateMin,
            int subrateMax,
            int maxLatency,
            int contNumber,
            int supervisionTimeout,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService leSubrateRequest")) {
            return;
        }

        Log.d(
                TAG,
                "leSubrateRequest() - "
                        + "address="
                        + BluetoothUtils.toAnonymizedAddress(address)
                        + ", subrate min/max="
                        + subrateMin
                        + "/"
                        + subrateMax
                        + ", maxLatency="
                        + maxLatency
                        + ", continuation Number="
                        + contNumber
                        + ", timeout="
                        + supervisionTimeout);

        mNativeInterface.gattSubrateRequest(
                clientIf,
                address,
                subrateMin,
                subrateMax,
                maxLatency,
                contNumber,
                supervisionTimeout);
    }

    /**************************************************************************
     * Callback functions - SERVER
     *************************************************************************/

    void onServerRegistered(int status, int serverIf, long uuidLsb, long uuidMsb)
            throws RemoteException {

        UUID uuid = new UUID(uuidMsb, uuidLsb);
        Log.d(TAG, "onServerRegistered() - UUID=" + uuid + ", serverIf=" + serverIf);
        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByUuid(uuid);
        if (app != null) {
            app.id = serverIf;
            app.linkToDeath(new ServerDeathRecipient(serverIf, app.name));
            app.callback.onServerRegistered(status, serverIf);
        }
    }

    void onServiceAdded(int status, int serverIf, List<GattDbElement> service)
            throws RemoteException {
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

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getById(serverIf);
        if (app != null) {
            app.callback.onServiceAdded(status, svc);
        }
    }

    void onServiceStopped(int status, int serverIf, int srvcHandle) throws RemoteException {
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

    void onClientConnected(String address, boolean connected, int connId, int serverIf)
            throws RemoteException {

        Log.d(
                TAG,
                "onClientConnected() connId="
                        + connId
                        + ", address="
                        + address
                        + ", connected="
                        + connected);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getById(serverIf);
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

        app.callback.onServerConnectionState((byte) 0, serverIf, connected, address);
        statsLogAppPackage(address, applicationUid, serverIf);
        statsLogGattConnectionStateChange(
                BluetoothProfile.GATT_SERVER, address, serverIf, connectionState, -1);
    }

    void onServerReadCharacteristic(
            String address, int connId, int transId, int handle, int offset, boolean isLong)
            throws RemoteException {
        Log.v(
                TAG,
                "onServerReadCharacteristic() connId="
                        + connId
                        + ", address="
                        + address
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

        mHandleMap.addRequest(transId, handle);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        app.callback.onCharacteristicReadRequest(address, transId, offset, isLong, handle);
    }

    void onServerReadDescriptor(
            String address, int connId, int transId, int handle, int offset, boolean isLong)
            throws RemoteException {
        Log.v(
                TAG,
                "onServerReadDescriptor() connId="
                        + connId
                        + ", address="
                        + address
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

        mHandleMap.addRequest(transId, handle);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        app.callback.onDescriptorReadRequest(address, transId, offset, isLong, handle);
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
            byte[] data)
            throws RemoteException {
        Log.v(
                TAG,
                "onServerWriteCharacteristic() connId="
                        + connId
                        + ", address="
                        + address
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

        mHandleMap.addRequest(transId, handle);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        app.callback.onCharacteristicWriteRequest(
                address, transId, offset, length, isPrep, needRsp, handle, data);
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
            byte[] data)
            throws RemoteException {
        Log.v(
                TAG,
                "onAttributeWrite() connId="
                        + connId
                        + ", address="
                        + address
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

        mHandleMap.addRequest(transId, handle);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getById(entry.serverIf);
        if (app == null) {
            return;
        }

        app.callback.onDescriptorWriteRequest(
                address, transId, offset, length, isPrep, needRsp, handle, data);
    }

    void onExecuteWrite(String address, int connId, int transId, int execWrite)
            throws RemoteException {
        Log.d(
                TAG,
                "onExecuteWrite() connId="
                        + connId
                        + ", address="
                        + address
                        + ", transId="
                        + transId);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onExecuteWrite(address, transId, execWrite == 1);
    }

    void onResponseSendCompleted(int status, int attrHandle) {
        Log.d(TAG, "onResponseSendCompleted() handle=" + attrHandle);
    }

    void onNotificationSent(int connId, int status) throws RemoteException {
        Log.v(TAG, "onNotificationSent() connId=" + connId + ", status=" + status);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        if (!app.isCongested) {
            app.callback.onNotificationSent(address, status);
        } else {
            if (status == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                status = BluetoothGatt.GATT_SUCCESS;
            }
            app.queueCallback(new CallbackInfo.Builder(address, status).build());
        }
    }

    void onServerCongestion(int connId, boolean congested) throws RemoteException {
        Log.d(TAG, "onServerCongestion() - connId=" + connId + ", congested=" + congested);

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.isCongested = congested;
        while (!app.isCongested) {
            CallbackInfo callbackInfo = app.popQueuedCallback();
            if (callbackInfo == null) {
                return;
            }
            app.callback.onNotificationSent(callbackInfo.address, callbackInfo.status);
        }
    }

    void onMtuChanged(int connId, int mtu) throws RemoteException {
        Log.d(TAG, "onMtuChanged() - connId=" + connId + ", mtu=" + mtu);

        String address = mServerMap.addressByConnId(connId);
        if (address == null) {
            return;
        }

        ContextMap<IBluetoothGattServerCallback, Void>.App app = mServerMap.getByConnId(connId);
        if (app == null) {
            return;
        }

        app.callback.onMtuChanged(address, mtu);
    }

    /**************************************************************************
     * GATT Service functions - SERVER
     *************************************************************************/

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void registerServer(
            UUID uuid,
            IBluetoothGattServerCallback callback,
            boolean eatt_support,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService registerServer")) {
            return;
        }

        Log.d(TAG, "registerServer() - UUID=" + uuid);
        mServerMap.add(uuid, null, callback, null, this, mTransitionalScanHelper);
        mNativeInterface.gattServerRegisterApp(
                uuid.getLeastSignificantBits(), uuid.getMostSignificantBits(), eatt_support);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void unregisterServer(int serverIf, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService unregisterServer")) {
            return;
        }

        Log.d(TAG, "unregisterServer() - serverIf=" + serverIf);

        deleteServices(serverIf);

        mServerMap.remove(serverIf);
        mNativeInterface.gattServerUnregisterApp(serverIf);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void serverConnect(
            int serverIf,
            String address,
            int addressType,
            boolean isDirect,
            int transport,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService serverConnect")) {
            return;
        }

        Log.d(TAG, "serverConnect() - address=" + address);

        logServerForegroundInfo(attributionSource.getUid(), isDirect);

        mNativeInterface.gattServerConnect(serverIf, address, addressType, isDirect, transport);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void serverDisconnect(int serverIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService serverDisconnect")) {
            return;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        Log.d(TAG, "serverDisconnect() - address=" + address + ", connId=" + connId);

        mNativeInterface.gattServerDisconnect(serverIf, address, connId != null ? connId : 0);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void serverSetPreferredPhy(
            int serverIf,
            String address,
            int txPhy,
            int rxPhy,
            int phyOptions,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService serverSetPreferredPhy")) {
            return;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) {
            Log.d(TAG, "serverSetPreferredPhy() - no connection to " + address);
            return;
        }

        Log.d(TAG, "serverSetPreferredPhy() - address=" + address + ", connId=" + connId);
        mNativeInterface.gattServerSetPreferredPhy(serverIf, address, txPhy, rxPhy, phyOptions);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void serverReadPhy(int serverIf, String address, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService serverReadPhy")) {
            return;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
        if (connId == null) {
            Log.d(TAG, "serverReadPhy() - no connection to " + address);
            return;
        }

        Log.d(TAG, "serverReadPhy() - address=" + address + ", connId=" + connId);
        mNativeInterface.gattServerReadPhy(serverIf, address);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void addService(
            int serverIf, BluetoothGattService service, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService addService")) {
            return;
        }

        Log.d(TAG, "addService() - uuid=" + service.getUuid());

        List<GattDbElement> db = new ArrayList<GattDbElement>();

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

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void removeService(int serverIf, int handle, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService removeService")) {
            return;
        }

        Log.d(TAG, "removeService() - handle=" + handle);

        mNativeInterface.gattServerDeleteService(serverIf, handle);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void clearServices(int serverIf, AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService clearServices")) {
            return;
        }

        Log.d(TAG, "clearServices()");
        deleteServices(serverIf);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void sendResponse(
            int serverIf,
            String address,
            int requestId,
            int status,
            int offset,
            byte[] value,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService sendResponse")) {
            return;
        }

        Log.v(TAG, "sendResponse() - address=" + address);

        int handle = 0;
        HandleMap.Entry entry = mHandleMap.getByRequestId(requestId);
        if (entry != null) {
            handle = entry.handle;
        }

        Integer connId = mServerMap.connIdByAddress(serverIf, address);
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

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    int sendNotification(
            int serverIf,
            String address,
            int handle,
            boolean confirm,
            byte[] value,
            AttributionSource attributionSource) {
        if (!Utils.checkConnectPermissionForDataDelivery(
                this, attributionSource, "GattService sendNotification")) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        }

        Log.v(TAG, "sendNotification() - address=" + address + " handle=" + handle);

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
     * Private functions
     *************************************************************************/

    private boolean isHidSrvcUuid(final UUID uuid) {
        return HID_SERVICE_UUID.equals(uuid);
    }

    private boolean isHidCharUuid(final UUID uuid) {
        for (UUID hidUuid : HID_UUIDS) {
            if (hidUuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAndroidTvRemoteSrvcUuid(final UUID uuid) {
        return ANDROID_TV_REMOTE_SERVICE_UUID.equals(uuid);
    }

    private boolean isFidoSrvcUuid(final UUID uuid) {
        return FIDO_SERVICE_UUID.equals(uuid);
    }

    private boolean isLeAudioSrvcUuid(final UUID uuid) {
        for (UUID leAudioUuid : LE_AUDIO_SERVICE_UUIDS) {
            if (leAudioUuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRestrictedSrvcUuid(final UUID uuid) {
        return isFidoSrvcUuid(uuid) || isAndroidTvRemoteSrvcUuid(uuid) || isLeAudioSrvcUuid(uuid);
    }

    private int getDeviceType(BluetoothDevice device) {
        int type = mNativeInterface.gattClientGetDeviceType(device.getAddress());
        Log.d(TAG, "getDeviceType() - device=" + device + ", type=" + type);
        return type;
    }

    private void logClientForegroundInfo(int uid, boolean isDirect) {
        if (mPackageManager == null) {
            return;
        }

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
        if (mPackageManager == null) {
            return;
        }

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

    private void stopNextService(int serverIf, int status) throws RemoteException {
        Log.d(TAG, "stopNextService() - serverIf=" + serverIf + ", status=" + status);

        if (status == 0) {
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
    }

    private void deleteServices(int serverIf) {
        Log.d(TAG, "deleteServices() - serverIf=" + serverIf);

        /*
         * Figure out which handles to delete.
         * The handles are copied into a new list to avoid race conditions.
         */
        List<Integer> handleList = new ArrayList<Integer>();
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
        sb.append("  Scanner:\n");
        for (Integer appId : mTransitionalScanHelper.getScannerMap().getAllAppsIds()) {
            println(
                    sb,
                    "    app_if: "
                            + appId
                            + ", appName: "
                            + mTransitionalScanHelper.getScannerMap().getById(appId).name);
        }
        sb.append("  Client:\n");
        for (Integer appId : mClientMap.getAllAppsIds()) {
            println(sb, "    app_if: " + appId + ", appName: " + mClientMap.getById(appId).name);
        }
        sb.append("  Server:\n");
        for (Integer appId : mServerMap.getAllAppsIds()) {
            println(sb, "    app_if: " + appId + ", appName: " + mServerMap.getById(appId).name);
        }
        sb.append("\n\n");
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        println(sb, "mAdvertisingServiceUuids:");
        for (UUID uuid : mAdvertisingServiceUuids) {
            println(sb, "  " + uuid);
        }

        sb.append("\nRegistered App\n");
        dumpRegisterId(sb);

        sb.append("GATT Scanner Map\n");
        mTransitionalScanHelper.getScannerMap().dump(sb);

        sb.append("GATT Advertiser Map\n");
        mAdvertiserMap.dumpAdvertiser(sb);

        sb.append("GATT Client Map\n");
        mClientMap.dump(sb);

        sb.append("GATT Server Map\n");
        mServerMap.dump(sb);

        sb.append("GATT Handle Map\n");
        mHandleMap.dump(sb);
    }

    private void statsLogAppPackage(String address, int applicationUid, int sessionIndex) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
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
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
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
        mTransitionalScanHelper.dumpProto(builder);
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
