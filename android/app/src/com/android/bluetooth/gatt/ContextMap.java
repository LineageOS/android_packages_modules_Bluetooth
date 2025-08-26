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

import static com.android.bluetooth.Utils.transportToString;
import static com.android.bluetooth.util.AttributionSourceUtils.getLastAttributionTag;

import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
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
 * @param <C> the callback type (must implement {@link IInterface}) for this map
 */
class ContextMap<C extends IInterface> {
    private static final String TAG = GattUtil.TAG_PREFIX + ContextMap.class.getSimpleName();

    private static final int MAX_LAST_RECORDS = 5;

    /** Our internal application list */
    private final Object mAppsLock = new Object();

    @GuardedBy("mAppsLock")
    private final List<App> mApps = new ArrayList<>();

    @GuardedBy("mAppsLock")
    private final List<AppRecord> mOngoingRecords = new ArrayList<>();

    @GuardedBy("mAppsLock")
    private final List<AppRecord> mLastRecords = new ArrayList<>();

    private final Object mConnectionsLock = new Object();

    /** Internal list of connected devices */
    @GuardedBy("mConnectionsLock")
    private final List<Connection> mConnections = new ArrayList<>();

    /** Application entry mapping UUIDs to appIDs and callbacks. */
    class App {
        final UUID mUuid;
        private final C mCallback;
        final int mUid;
        private final String mPackageName;
        private final int mTransport;
        @Nullable final String mAttributionTag;

        public int id;

        /** Flag to signal that transport is congested */
        public Boolean isCongested = false;

        private IBinder.DeathRecipient mDeathRecipient;

        /** Internal callback info queue, waiting to be send on congestion clear */
        private final List<CallbackInfo> mCongestionQueue = new ArrayList<>();

        /** Creates a new app context. */
        private App(
                UUID uuid,
                C callback,
                int appUid,
                String packageName,
                int transport,
                AttributionSource source) {
            mUuid = uuid;
            mCallback = callback;
            mUid = appUid;
            mPackageName = packageName;
            mTransport = transport;
            mAttributionTag = getLastAttributionTag(source);
        }

        C getCallback() {
            return mCallback;
        }

        String getPackageName() {
            return mPackageName;
        }

        int getTransport() {
            return mTransport;
        }

        void linkToDeath(IBinder.DeathRecipient deathRecipient) {
            // It might not be a binder object
            if (mCallback == null) {
                return;
            }
            try {
                mCallback.asBinder().linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + id);
            }
        }

        void unlinkToDeath() {
            if (mDeathRecipient != null) {
                try {
                    mCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + id);
                }
            }
        }

        void queueCallback(CallbackInfo callbackInfo) {
            mCongestionQueue.add(callbackInfo);
        }

        CallbackInfo popQueuedCallback() {
            if (mCongestionQueue.size() == 0) {
                return null;
            }
            return mCongestionQueue.remove(0);
        }
    }

    private class AppRecord {
        private final UUID mUuid;
        private final String mPackageName;
        private final int mTransport;
        @Nullable private final String mAttributionTag;
        private final Instant mRegisterTime;

        private int mClientIf;
        private RemoveReason mReason;
        @Nullable private Instant mUnregisterTime;

        AppRecord(App app) {
            mUuid = app.mUuid;
            mPackageName = app.mPackageName;
            mTransport = app.getTransport();
            mAttributionTag = app.mAttributionTag;
            mRegisterTime = Instant.now();
        }

        private static final DateTimeFormatter sDateFormat =
                DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("AppRecord<")
                    .append(sDateFormat.format(mRegisterTime))
                    .append(" ~ ")
                    .append(sDateFormat.format(mUnregisterTime))
                    .append(" app_if: ")
                    .append(mClientIf)
                    .append(", appName: ")
                    .append(mPackageName)
                    .append(", transport: ")
                    .append(transportToString(mTransport));
            if (mAttributionTag != null) {
                sb.append(", tag: ").append(mAttributionTag);
            }
            sb.append(", reason: ").append(mReason).append(">");
            return sb.toString();
        }
    }

    /** Connection class helps map connection IDs to devices. */
    record Connection(
            int connId, BluetoothDevice device, int transport, int appId, long startTime) {
        Connection(int connId, BluetoothDevice device, int transport, int appId) {
            this(connId, device, transport, appId, SystemClock.elapsedRealtime());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Connection<")
                    .append("conn_id: ")
                    .append(connId)
                    .append(", device: ")
                    .append(device)
                    .append(", transport: ")
                    .append(transportToString(transport))
                    .append(", app_id: ")
                    .append(appId)
                    .append(">");
            return sb.toString();
        }
    }

    enum RemoveReason {
        REASON_UNREGISTER_ALL,
        REASON_UNREGISTER_CLIENT,
        REASON_UNREGISTER_SERVER,
        REASON_BINDER_DIED,
        REASON_REGISTER_FAILED,

        REASON_UNKNOWN
    }

    /** Add an entry to the application context list. */
    App add(UUID uuid, C callback, int transport, Context context, AttributionSource source) {
        int appUid = Binder.getCallingUid();
        String appName = context.getPackageManager().getNameForUid(appUid);
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }
        synchronized (mAppsLock) {
            App app = new App(uuid, callback, appUid, appName, transport, source);
            mApps.add(app);
            recordRegisterApp(app);

            return app;
        }
    }

    /** Remove the context for a given UUID */
    void remove(UUID uuid, RemoveReason reason) {
        synchronized (mAppsLock) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.mUuid.equals(uuid)) {
                    entry.unlinkToDeath();
                    i.remove();
                    recordUnregisterApp(entry, reason);
                    break;
                }
            }
        }
    }

    /** Remove the context for a given application ID. */
    void remove(int id, RemoveReason reason) {
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

    List<Integer> getAllAppsIds() {
        List<Integer> appIds = new ArrayList<>();
        synchronized (mAppsLock) {
            for (App entry : mApps) {
                appIds.add(entry.id);
            }
        }
        return appIds;
    }

    /** Get all registered application callbacks. */
    List<C> getAllAppsCallbackId() {
        List<C> appIds = new ArrayList<>();
        synchronized (mAppsLock) {
            for (App entry : mApps) {
                appIds.add(entry.getCallback());
            }
        }
        return appIds;
    }

    /** Add a new connection for a given application ID. */
    void addConnection(int id, int connId, int transport, BluetoothDevice device) {
        synchronized (mConnectionsLock) {
            App entry = getById(id);
            if (entry != null) {
                mConnections.add(new Connection(connId, device, transport, id));
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
    App getById(int id) {
        App app = getAppByPredicate(entry -> entry.id == id);
        if (app == null) {
            Log.e(TAG, "Context not found for ID " + id);
        }
        return app;
    }

    /** Get an application context by its callback object. */
    App getByCallbackId(C callbackId) {
        App app =
                getAppByPredicate(entry -> entry.getCallback().asBinder() == callbackId.asBinder());
        if (app == null) {
            Log.e(TAG, "Context not found for callbackID " + callbackId);
        }
        return app;
    }

    /** Get an application context by UUID. */
    App getByUuid(UUID uuid) {
        App app = getAppByPredicate(entry -> entry.mUuid.equals(uuid));
        if (app == null) {
            Log.e(TAG, "Context not found for UUID " + uuid);
        }
        return app;
    }

    /** Get all connected devices */
    Set<BluetoothDevice> getConnectedDevices() {
        Set<BluetoothDevice> devices = new HashSet<>();
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                devices.add(connection.device);
            }
        }
        return devices;
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

    /**
     * Returns all connection IDs for a given device.
     *
     * <p>Devices are allowed to have multiple underlying connections (ATT bearers) to a remote
     * device. When using BR/EDR, these can be different L2CAP connections targeting the ATT
     * assigned PSM. When using LE, there's typically one underlying link targeting the fixed ATT
     * channel for LE. When a device is dual mode, they can use any combination of these links.
     *
     * <p>One ATT bearer disconnecting doesn't necessarily mean the entire underlying connection is
     * gone. We need to use all connections to carefully communicate state to GATT applications.
     * When requesting a disconnection, we also need to make sure to request a disconnection on all
     * connections, not just a single connection.
     *
     * <p>This function provides a way to get all connections for a device so we can do the above.
     */
    List<Connection> getConnectionsByDevice(int appId, BluetoothDevice device) {
        List<Connection> currentConnections = new ArrayList<>();
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                if (connection.device.equals(device) && connection.appId == appId) {
                    currentConnections.add(connection);
                }
            }
        }
        return currentConnections;
    }

    /** Returns the device for a given connection ID. */
    BluetoothDevice deviceByConnId(int connId) {
        synchronized (mConnectionsLock) {
            for (Connection connection : mConnections) {
                if (connection.connId == connId) {
                    return connection.device;
                }
            }
        }
        return null;
    }

    /** Returns all Connections that have a given app UID. */
    List<Connection> getConnectionByApp(int appId) {
        List<Connection> currentConnections = new ArrayList<>();
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
    int countByAppUid(int appUid) {
        synchronized (mAppsLock) {
            return (int) (mApps.stream().filter(app -> app.mUid == appUid).count());
        }
    }

    /** Erases all application context entries. */
    void clear() {
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
    Map<Integer, BluetoothDevice> getConnectedMap() {
        Map<Integer, BluetoothDevice> connectedMap = new HashMap<>();
        synchronized (mConnectionsLock) {
            for (Connection conn : mConnections) {
                connectedMap.put(conn.appId, conn.device);
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
                sb.append("       ").append(record.toString()).append("\n");
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
            if (app.mUuid.equals(mOngoingRecords.get(i).mUuid)) {
                AppRecord record = mOngoingRecords.remove(i);
                record.mClientIf = app.id;
                record.mReason = reason;
                record.mUnregisterTime = Instant.now();

                if (mLastRecords.size() >= MAX_LAST_RECORDS) {
                    mLastRecords.remove(0);
                }
                mLastRecords.add(record);
                break;
            }
        }
    }
}
