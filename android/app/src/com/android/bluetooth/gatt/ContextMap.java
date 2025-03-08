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

import static com.android.bluetooth.util.AttributionSourceUtil.getLastAttributionTag;

import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * Helper class that keeps track of registered GATT applications. This class manages application
 * callbacks and keeps track of GATT connections.
 *
 * @param <C> the callback type for this map
 */
public class ContextMap<C> {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + ContextMap.class.getSimpleName();

    private static final DateTimeFormatter sDateFormat =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_LAST_RECORDS = 5;

    /** Connection class helps map connection IDs to device addresses. */
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

    /** Application entry mapping UUIDs to appIDs and callbacks. */
    public class App {
        /** The UUID of the application */
        public final UUID uuid;

        /** The id of the application */
        public int id;

        /** The uid of the application */
        public final int appUid;

        /** The package name of the application */
        public final String name;

        /** The last attribution tag of the caller */
        @Nullable public final String attributionTag;

        /** Application callbacks */
        public C callback;

        /** Death recipient */
        private IBinder.DeathRecipient mDeathRecipient;

        /** Flag to signal that transport is congested */
        public Boolean isCongested = false;

        /** Internal callback info queue, waiting to be send on congestion clear */
        private final List<CallbackInfo> mCongestionQueue = new ArrayList<>();

        /** Creates a new app context. */
        App(UUID uuid, C callback, int appUid, String name, AttributionSource attrSource) {
            this.uuid = uuid;
            this.callback = callback;
            this.appUid = appUid;
            this.name = name;
            this.attributionTag = getLastAttributionTag(attrSource);
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

    private class AppRecord {
        public final UUID uuid;
        public final String appName;
        @Nullable public final String attributionTag;
        public final Instant registerTime;

        public int clientIf;
        public RemoveReason reason;
        @Nullable public Instant unregisterTime;

        AppRecord(App app) {
            uuid = app.uuid;
            appName = app.name;
            attributionTag = app.attributionTag;

            registerTime = Instant.now();
        }
    }

    public enum RemoveReason {
        REASON_UNREGISTER_ALL,
        REASON_UNREGISTER_CLIENT,
        REASON_UNREGISTER_SERVER,
        REASON_BINDER_DIED,
        REASON_REGISTER_FAILED,

        REASON_UNKNOWN
    }

    /** Our internal application list */
    private final Object mAppsLock = new Object();

    @GuardedBy("mAppsLock")
    private final List<App> mApps = new ArrayList<>();

    @GuardedBy("mAppsLock")
    private final List<AppRecord> mOngoingRecords = new ArrayList<>();

    @GuardedBy("mAppsLock")
    private final List<AppRecord> mLastRecords = new ArrayList<>();

    /** Internal list of connected devices */
    private final List<Connection> mConnections = new ArrayList<>();

    private final Object mConnectionsLock = new Object();

    /** Add an entry to the application context list. */
    public App add(UUID uuid, C callback, Context context, AttributionSource attrSource) {
        int appUid = Binder.getCallingUid();
        String appName = context.getPackageManager().getNameForUid(appUid);
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }
        synchronized (mAppsLock) {
            App app = new App(uuid, callback, appUid, appName, attrSource);
            mApps.add(app);
            recordRegisterApp(app);

            return app;
        }
    }

    /** Remove the context for a given UUID */
    public void remove(UUID uuid, RemoveReason reason) {
        synchronized (mAppsLock) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.uuid.equals(uuid)) {
                    entry.unlinkToDeath();
                    i.remove();
                    recordUnregisterApp(entry, reason);
                    break;
                }
            }
        }
    }

    /** Remove the context for a given application ID. */
    public void remove(int id, RemoveReason reason) {
        boolean find = false;
        synchronized (mAppsLock) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.id == id) {
                    find = true;
                    entry.unlinkToDeath();
                    i.remove();
                    recordUnregisterApp(entry, reason);
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

    /** Add a new connection for a given application ID. */
    void addConnection(int id, int connId, String address) {
        synchronized (mConnectionsLock) {
            App entry = getById(id);
            if (entry != null) {
                mConnections.add(new Connection(connId, address, id));
            }
        }
    }

    /** Remove a connection with the given ID. */
    void removeConnection(int id, int connId) {
        synchronized (mConnectionsLock) {
            mConnections.removeIf(conn -> conn.appId == id && conn.connId == connId);
        }
    }

    /** Remove all connections for a given application ID. */
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

    /** Get the device addresses for all connected devices */
    Set<String> getConnectedDevices() {
        Set<String> addresses = new HashSet<String>();
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                addresses.add(connection.address);
            }
        }
        return addresses;
    }

    /** Get an application context by a connection ID. */
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

    /** Returns a connection ID for a given device address. */
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

    /** Returns the device address for a given connection ID. */
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

    /** Counts the number of applications that have a given app UID. */
    public int countByAppUid(int appUid) {
        synchronized (mAppsLock) {
            return (int) (mApps.stream().filter(app -> app.appUid == appUid).count());
        }
    }

    /** Erases all application context entries. */
    public void clear() {
        synchronized (mAppsLock) {
            for (App entry : mApps) {
                entry.unlinkToDeath();
            }
            mApps.clear();
            mOngoingRecords.clear();
        }

        synchronized (mConnectionsLock) {
            mConnections.clear();
        }
    }

    /** Returns connect device map with addr and appid */
    Map<Integer, String> getConnectedMap() {
        Map<Integer, String> connectedMap = new HashMap<Integer, String>();
        synchronized (mConnectionsLock) {
            for (Connection conn : mConnections) {
                connectedMap.put(conn.appId, conn.address);
            }
        }
        return connectedMap;
    }

    /** Logs debug information. */
    protected void dump(StringBuilder sb) {
        synchronized (mAppsLock) {
            sb.append("  Entries: ").append(mApps.size()).append("\n");
            sb.append("  Last apps: ").append("\n");
            for (AppRecord record : mLastRecords) {
                sb.append("       ")
                        .append(sDateFormat.format(record.registerTime))
                        .append(" ~ ")
                        .append(sDateFormat.format(record.unregisterTime))
                        .append(" app_if: ")
                        .append(record.clientIf)
                        .append(", appName: ")
                        .append(record.appName);
                if (record.attributionTag != null) {
                    sb.append(", tag: ").append(record.attributionTag);
                }
                sb.append(", reason: ").append(record.reason).append("\n");
            }
            sb.append("\n");
        }
    }

    @GuardedBy("mAppsLock")
    private void recordRegisterApp(App app) {
        mOngoingRecords.add(new AppRecord(app));
    }

    @GuardedBy("mAppsLock")
    private void recordUnregisterApp(App app, RemoveReason reason) {
        for (int i = 0; i < mOngoingRecords.size(); i++) {
            if (app.uuid.equals(mOngoingRecords.get(i).uuid)) {
                AppRecord record = mOngoingRecords.remove(i);
                record.clientIf = app.id;
                record.reason = reason;
                record.unregisterTime = Instant.now();

                if (mLastRecords.size() >= MAX_LAST_RECORDS) {
                    mLastRecords.remove(0);
                }
                mLastRecords.add(record);
                break;
            }
        }
    }
}
