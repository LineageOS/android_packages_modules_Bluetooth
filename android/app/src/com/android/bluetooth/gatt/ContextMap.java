/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;


import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.le_scan.AppScanStats;
import com.android.bluetooth.le_scan.TransitionalScanHelper;
import com.android.bluetooth.le_scan.TransitionalScanHelper.PendingIntentInfo;
import com.android.internal.annotations.GuardedBy;

import com.google.common.collect.EvictingQueue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Helper class that keeps track of registered GATT applications.
 * This class manages application callbacks and keeps track of GATT connections.
 * @hide
 */
public class ContextMap<C, T> {
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "ContextMap";

    /**
     * Connection class helps map connection IDs to device addresses.
     */
    public static class Connection {
        public int connId;
        public String address;
        public int appId;
        public long startTime;

        Connection(int connId, String address, int appId) {
            this.connId = connId;
            this.address = address;
            this.appId = appId;
            this.startTime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Application entry mapping UUIDs to appIDs and callbacks.
     */
    public class App {
        /** The UUID of the application */
        public UUID uuid;

        /** The id of the application */
        public int id;

        /** The package name of the application */
        public String name;

        /** Statistics for this app */
        public AppScanStats appScanStats;

        /** Application callbacks */
        public C callback;

        /** Context information */
        public T info;
        /** Death receipient */
        private IBinder.DeathRecipient mDeathRecipient;

        /** Flag to signal that transport is congested */
        public Boolean isCongested = false;

        /** Whether the calling app has location permission */
        public boolean hasLocationPermission;

        /** Whether the calling app has bluetooth privileged permission */
        public boolean hasBluetoothPrivilegedPermission;

        /** The user handle of the app that started the scan */
        public UserHandle mUserHandle;

        /** Whether the calling app has the network settings permission */
        public boolean mHasNetworkSettingsPermission;

        /** Whether the calling app has the network setup wizard permission */
        public boolean mHasNetworkSetupWizardPermission;

        /** Whether the calling app has the network setup wizard permission */
        public boolean mHasScanWithoutLocationPermission;

        /** Whether the calling app has disavowed the use of bluetooth for location */
        public boolean mHasDisavowedLocation;

        public boolean mEligibleForSanitizedExposureNotification;

        public List<String> mAssociatedDevices;

        /** Internal callback info queue, waiting to be send on congestion clear */
        private List<CallbackInfo> mCongestionQueue = new ArrayList<CallbackInfo>();

        /**
         * Creates a new app context.
         */
        App(UUID uuid, C callback, T info, String name, AppScanStats appScanStats) {
            this.uuid = uuid;
            this.callback = callback;
            this.info = info;
            this.name = name;
            this.appScanStats = appScanStats;
        }

        /**
         * Creates a new app context for advertiser.
         */
        App(int id, C callback, String name) {
            this.id = id;
            this.callback = callback;
            this.name = name;
        }

        /** Link death recipient */
        public void linkToDeath(IBinder.DeathRecipient deathRecipient) {
            // It might not be a binder object
            if (callback == null) {
                return;
            }
            try {
                IBinder binder = ((IInterface) callback).asBinder();
                binder.linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + id);
            }
        }

        /** Unlink death recipient */
        public void unlinkToDeath() {
            if (mDeathRecipient != null) {
                try {
                    IBinder binder = ((IInterface) callback).asBinder();
                    binder.unlinkToDeath(mDeathRecipient, 0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + id);
                }
            }
        }

        public void queueCallback(CallbackInfo callbackInfo) {
            mCongestionQueue.add(callbackInfo);
        }

        public CallbackInfo popQueuedCallback() {
            if (mCongestionQueue.size() == 0) {
                return null;
            }
            return mCongestionQueue.remove(0);
        }
    }

    /** Our internal application list */
    private final Object mAppsLock = new Object();
    @GuardedBy("mAppsLock")
    private List<App> mApps = new ArrayList<App>();

    /** Internal map to keep track of logging information by app name */
    private HashMap<Integer, AppScanStats> mAppScanStats = new HashMap<Integer, AppScanStats>();

    /** Internal map to keep track of logging information by advertise id */
    private final Map<Integer, AppAdvertiseStats> mAppAdvertiseStats =
            new HashMap<Integer, AppAdvertiseStats>();

    private static final int ADVERTISE_STATE_MAX_SIZE = 5;

    private final EvictingQueue<AppAdvertiseStats> mLastAdvertises =
            EvictingQueue.create(ADVERTISE_STATE_MAX_SIZE);

    /** Internal list of connected devices */
    private List<Connection> mConnections = new ArrayList<Connection>();

    private final Object mConnectionsLock = new Object();

    /** Add an entry to the application context list. */
    public App add(
            UUID uuid,
            WorkSource workSource,
            C callback,
            PendingIntentInfo piInfo,
            Context context,
            TransitionalScanHelper scanHelper) {
        int appUid;
        String appName = null;
        if (piInfo != null) {
            appUid = piInfo.callingUid;
            appName = piInfo.callingPackage;
        } else {
            appUid = Binder.getCallingUid();
            appName = context.getPackageManager().getNameForUid(appUid);
        }
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }
        synchronized (mAppsLock) {
            // TODO(b/327849650): AppScanStats appears to be only needed for the ScannerMap.
            //                    Consider refactoring this.
            AppScanStats appScanStats = mAppScanStats.get(appUid);
            if (appScanStats == null) {
                appScanStats = new AppScanStats(appName, workSource, this, context, scanHelper);
                mAppScanStats.put(appUid, appScanStats);
            }
            App app = new App(uuid, callback, (T) piInfo, appName, appScanStats);
            mApps.add(app);
            appScanStats.isRegistered = true;
            return app;
        }
    }

    /** Add an entry to the application context list for advertiser. */
    public App add(int id, C callback, GattService service) {
        int appUid = Binder.getCallingUid();
        String appName = service.getPackageManager().getNameForUid(appUid);
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }

        synchronized (mAppsLock) {
            synchronized (this) {
                if (!mAppAdvertiseStats.containsKey(id)) {
                    AppAdvertiseStats appAdvertiseStats =
                            BluetoothMethodProxy.getInstance()
                                    .createAppAdvertiseStats(id, appName, this, service);
                    mAppAdvertiseStats.put(id, appAdvertiseStats);
                }
            }
            App app = getById(appUid);
            if (app == null) {
                app = new App(appUid, callback, appName);
                mApps.add(app);
            }
            return app;
        }
    }

    /** Remove the context for a given UUID */
    public void remove(UUID uuid) {
        synchronized (mAppsLock) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.uuid.equals(uuid)) {
                    entry.unlinkToDeath();
                    entry.appScanStats.isRegistered = false;
                    i.remove();
                    break;
                }
            }
        }
    }

    /** Remove the context for a given application ID. */
    public void remove(int id) {
        boolean find = false;
        synchronized (mAppsLock) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.id == id) {
                    find = true;
                    entry.unlinkToDeath();
                    entry.appScanStats.isRegistered = false;
                    i.remove();
                    break;
                }
            }
        }
        if (find) {
            removeConnectionsByAppId(id);
        }
    }

    public List<Integer> getAllAppsIds() {
        List<Integer> appIds = new ArrayList();
        synchronized (mAppsLock) {
            for (App entry : mApps) {
                appIds.add(entry.id);
            }
        }
        return appIds;
    }

    /**
     * Add a new connection for a given application ID.
     */
    void addConnection(int id, int connId, String address) {
        synchronized (mConnectionsLock) {
            App entry = getById(id);
            if (entry != null) {
                mConnections.add(new Connection(connId, address, id));
            }
        }
    }

    /**
     * Remove a connection with the given ID.
     */
    void removeConnection(int id, int connId) {
        synchronized (mConnectionsLock) {
            Iterator<Connection> i = mConnections.iterator();
            while (i.hasNext()) {
                Connection connection = i.next();
                if (connection.connId == connId) {
                    i.remove();
                    break;
                }
            }
        }
    }

    /**
     * Remove all connections for a given application ID.
     */
    void removeConnectionsByAppId(int appId) {
        synchronized (mConnectionsLock) {
            mConnections.removeIf(conn -> conn.appId == appId);
        }
    }

    private App getAppByPredicate(Predicate<App> predicate) {
        synchronized (mAppsLock) {
            // Intentionally using a for-loop over a stream for performance.
            for (App app : mApps) {
                if (predicate.test(app)) {
                    return app;
                }
            }
            return null;
        }
    }

    /** Get an application context by ID. */
    public App getById(int id) {
        App app = getAppByPredicate(entry -> entry.id == id);
        if (app == null) {
            Log.e(TAG, "Context not found for ID " + id);
        }
        return app;
    }

    /** Get an application context by UUID. */
    public App getByUuid(UUID uuid) {
        App app = getAppByPredicate(entry -> entry.uuid.equals(uuid));
        if (app == null) {
            Log.e(TAG, "Context not found for UUID " + uuid);
        }
        return app;
    }

    /** Get an application context by the calling Apps name. */
    public App getByName(String name) {
        App app = getAppByPredicate(entry -> entry.name.equals(name));
        if (app == null) {
            Log.e(TAG, "Context not found for name " + name);
        }
        return app;
    }

    /** Get an application context by the context info object. */
    public App getByContextInfo(T contextInfo) {
        App app = getAppByPredicate(entry -> entry.info != null && entry.info.equals(contextInfo));
        if (app == null) {
            Log.e(TAG, "Context not found for info " + contextInfo);
        }
        return app;
    }

    /** Get Logging info by ID */
    public AppScanStats getAppScanStatsById(int id) {
        App temp = getById(id);
        if (temp != null) {
            return temp.appScanStats;
        }
        return null;
    }

    /**
     * Get Logging info by application UID
     */
    public AppScanStats getAppScanStatsByUid(int uid) {
        return mAppScanStats.get(uid);
    }

    /**
     * Remove the context for a given application ID.
     */
    void removeAppAdvertiseStats(int id) {
        synchronized (this) {
            mAppAdvertiseStats.remove(id);
        }
    }

    /**
     * Get Logging info by ID
     */
    AppAdvertiseStats getAppAdvertiseStatsById(int id) {
        synchronized (this) {
            return mAppAdvertiseStats.get(id);
        }
    }

    /**
     * update the advertiser ID by the regiseter ID
     */
    void setAdvertiserIdByRegId(int regId, int advertiserId) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(regId);
            if (stats == null) {
                return;
            }
            stats.setId(advertiserId);
            mAppAdvertiseStats.remove(regId);
            mAppAdvertiseStats.put(advertiserId, stats);
        }
    }

    void recordAdvertiseStart(int id, AdvertisingSetParameters parameters,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters, AdvertiseData periodicData,
            int duration, int maxExtAdvEvents) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.recordAdvertiseStart(parameters, advertiseData, scanResponse,
                    periodicParameters, periodicData, duration, maxExtAdvEvents);
            int advertiseInstanceCount = mAppAdvertiseStats.size();
            Log.d(TAG, "advertiseInstanceCount is " + advertiseInstanceCount);
            AppAdvertiseStats.recordAdvertiseInstanceCount(advertiseInstanceCount);
        }
    }

    void recordAdvertiseStop(int id) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.recordAdvertiseStop();
            mAppAdvertiseStats.remove(id);
            mLastAdvertises.add(stats);
        }
    }

    void enableAdvertisingSet(int id, boolean enable, int duration, int maxExtAdvEvents) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.enableAdvertisingSet(enable, duration, maxExtAdvEvents);
        }
    }

    void setAdvertisingData(int id, AdvertiseData data) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.setAdvertisingData(data);
        }
    }

    void setScanResponseData(int id, AdvertiseData data) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.setScanResponseData(data);
        }
    }

    void setAdvertisingParameters(int id, AdvertisingSetParameters parameters) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.setAdvertisingParameters(parameters);
        }
    }

    void setPeriodicAdvertisingParameters(int id, PeriodicAdvertisingParameters parameters) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.setPeriodicAdvertisingParameters(parameters);
        }
    }

    void setPeriodicAdvertisingData(int id, AdvertiseData data) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.setPeriodicAdvertisingData(data);
        }
    }

    void onPeriodicAdvertiseEnabled(int id, boolean enable) {
        synchronized (this) {
            AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
            if (stats == null) {
                return;
            }
            stats.onPeriodicAdvertiseEnabled(enable);
        }
    }

    /**
     * Get the device addresses for all connected devices
     */
    Set<String> getConnectedDevices() {
        Set<String> addresses = new HashSet<String>();
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                addresses.add(connection.address);
            }
        }
        return addresses;
    }

    /**
     * Get an application context by a connection ID.
     */
    App getByConnId(int connId) {
        int appId = -1;
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                if (connection.connId == connId) {
                    appId = connection.appId;
                    break;
                }
            }
        }
        if (appId >= 0) {
            return getById(appId);
        }
        return null;
    }

    /**
     * Returns a connection ID for a given device address.
     */
    Integer connIdByAddress(int id, String address) {
        App entry = getById(id);
        if (entry == null) {
            return null;
        }
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                if (connection.address.equalsIgnoreCase(address) && connection.appId == id) {
                    return connection.connId;
                }
            }
        }
        return null;
    }

    /**
     * Returns the device address for a given connection ID.
     */
    String addressByConnId(int connId) {
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                if (connection.connId == connId) {
                    return connection.address;
                }
            }
        }
        return null;
    }

    public List<Connection> getConnectionByApp(int appId) {
        List<Connection> currentConnections = new ArrayList<Connection>();
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                if (connection.appId == appId) {
                    currentConnections.add(connection);
                }
            }
        }
        return currentConnections;
    }

    /** Erases all application context entries. */
    public void clear() {
        synchronized (mAppsLock) {
            for (App entry : mApps) {
                entry.unlinkToDeath();
                if (entry.appScanStats != null) {
                    entry.appScanStats.isRegistered = false;
                }
            }
            mApps.clear();
        }

        synchronized (mConnectionsLock) {
            mConnections.clear();
        }

        synchronized (this) {
            mAppAdvertiseStats.clear();
            mLastAdvertises.clear();
        }
    }

    /**
     * Returns connect device map with addr and appid
     */
    Map<Integer, String> getConnectedMap() {
        Map<Integer, String> connectedmap = new HashMap<Integer, String>();
        synchronized (mConnectionsLock) {
            for (Connection conn : mConnections) {
                connectedmap.put(conn.appId, conn.address);
            }
        }
        return connectedmap;
    }

    /**
     * Logs debug information.
     */
    protected void dump(StringBuilder sb) {
        sb.append("  Entries: " + mAppScanStats.size() + "\n\n");
        for (AppScanStats appScanStats : mAppScanStats.values()) {
            appScanStats.dumpToString(sb);
        }
    }

    /**
     * Logs advertiser debug information.
     */
    void dumpAdvertiser(StringBuilder sb) {
        synchronized (this) {
            if (!mLastAdvertises.isEmpty()) {
                sb.append("\n  last " + mLastAdvertises.size() + " advertising:");
                for (AppAdvertiseStats stats : mLastAdvertises) {
                    AppAdvertiseStats.dumpToString(sb, stats);
                }
                sb.append("\n");
            }

            if (!mAppAdvertiseStats.isEmpty()) {
                sb.append("  Total number of ongoing advertising                   : "
                        + mAppAdvertiseStats.size());
                sb.append("\n  Ongoing advertising:");
                for (Integer key : mAppAdvertiseStats.keySet()) {
                    AppAdvertiseStats stats = mAppAdvertiseStats.get(key);
                    AppAdvertiseStats.dumpToString(sb, stats);
                }
            }
            sb.append("\n");
        }
        Log.d(TAG, sb.toString());
    }
}
