/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.bluetooth.le_scan;

import static com.android.bluetooth.Utils.getSystemClock;
import static com.android.bluetooth.util.AttributionSourceUtil.getLastAttributionTag;

import android.annotation.Nullable;
import android.bluetooth.le.IScannerCallback;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** List of our registered scanners. */
public class ScannerMap {
    private static final String TAG = ScannerMap.class.getSimpleName();

    /** Internal map to keep track of logging information by app name */
    private final HashMap<Integer, AppScanStats> mAppScanStatsMap = new HashMap<>();

    private final ConcurrentLinkedQueue<ScannerApp> mApps = new ConcurrentLinkedQueue<>();

    /** Add an entry to the application context list with a callback. */
    ScannerApp add(
            UUID uuid,
            AttributionSource attributionSource,
            WorkSource workSource,
            IScannerCallback callback,
            AdapterService adapterService,
            ScanController scanController) {
        return add(
                uuid,
                attributionSource,
                workSource,
                callback,
                null,
                adapterService,
                scanController);
    }

    /** Add an entry to the application context list with a pending intent. */
    ScannerApp add(
            UUID uuid,
            AttributionSource attributionSource,
            ScanController.PendingIntentInfo piInfo,
            AdapterService adapterService,
            ScanController scanController) {
        return add(uuid, attributionSource, null, null, piInfo, adapterService, scanController);
    }

    private ScannerApp add(
            UUID uuid,
            AttributionSource attributionSource,
            @Nullable WorkSource workSource,
            @Nullable IScannerCallback callback,
            @Nullable ScanController.PendingIntentInfo piInfo,
            AdapterService adapterService,
            ScanController scanController) {
        int appUid;
        String appName = null;
        if (piInfo != null) {
            appUid = piInfo.callingUid;
            appName = piInfo.callingPackage;
        } else {
            appUid = Binder.getCallingUid();
            appName = adapterService.getPackageManager().getNameForUid(appUid);
        }
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }
        AppScanStats appScanStats = mAppScanStatsMap.get(appUid);
        if (appScanStats == null) {
            appScanStats =
                    new AppScanStats(
                            appName,
                            workSource,
                            this,
                            adapterService,
                            scanController,
                            getSystemClock());
            mAppScanStatsMap.put(appUid, appScanStats);
        }
        ScannerApp app =
                new ScannerApp(
                        uuid,
                        getLastAttributionTag(attributionSource),
                        callback,
                        piInfo,
                        appName,
                        appScanStats);
        mApps.add(app);
        appScanStats.isRegistered = true;
        return app;
    }

    /** Remove the context for a given application ID. */
    void remove(int id) {
        Iterator<ScannerApp> i = mApps.iterator();
        while (i.hasNext()) {
            ScannerApp entry = i.next();
            if (entry.mId == id) {
                entry.cleanup();
                i.remove();
                break;
            }
        }
    }

    /** Erases all application context entries. */
    public void clear() {
        for (ScannerApp entry : mApps) {
            entry.cleanup();
        }
        mApps.clear();
    }

    /** Get Logging info by application UID */
    AppScanStats getAppScanStatsByUid(int uid) {
        return mAppScanStatsMap.get(uid);
    }

    /** Get Logging info by ID */
    AppScanStats getAppScanStatsById(int id) {
        ScannerApp temp = (ScannerApp) getById(id);
        if (temp != null) {
            return temp.mAppScanStats;
        }
        return null;
    }

    /** Get an application context by ID. */
    ScannerApp getById(int id) {
        ScannerApp app = getAppByPredicate(entry -> entry.mId == id);
        if (app == null) {
            Log.e(TAG, "Context not found for ID " + id);
        }
        return app;
    }

    /** Get an application context by UUID. */
    ScannerApp getByUuid(UUID uuid) {
        ScannerApp app = getAppByPredicate(entry -> entry.mUuid.equals(uuid));
        if (app == null) {
            Log.e(TAG, "Context not found for UUID " + uuid);
        }
        return app;
    }

    /** Get application contexts by the calling app's name. */
    List<ScannerApp> getByName(String name) {
        return mApps.stream()
                .filter(app -> app.mName.equals(name))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Get an application context by the pending intent info object. */
    ScannerApp getByPendingIntentInfo(ScanController.PendingIntentInfo info) {
        ScannerApp app =
                getAppByPredicate(entry -> entry.mInfo != null && entry.mInfo.equals(info));
        if (app == null) {
            Log.e(TAG, "Context not found for info " + info);
        }
        return app;
    }

    private ScannerApp getAppByPredicate(Predicate<ScannerApp> predicate) {
        // Intentionally using a for-loop over a stream for performance.
        for (ScannerApp app : mApps) {
            if (predicate.test(app)) {
                return app;
            }
        }
        return null;
    }

    /** Logs debug information. */
    public void dump(StringBuilder sb) {
        sb.append("  Entries: ").append(mAppScanStatsMap.size()).append("\n\n");
        for (AppScanStats appScanStats : mAppScanStatsMap.values()) {
            appScanStats.dumpToString(sb);
        }
    }

    /** Logs all apps for debugging. */
    public void dumpApps(StringBuilder sb, BiConsumer<StringBuilder, String> bf) {
        for (ScannerApp entry : mApps) {
            bf.accept(
                    sb,
                    "    app_if: "
                            + entry.mId
                            + ", appName: "
                            + entry.mName
                            + (entry.mAttributionTag == null
                                    ? ""
                                    : ", tag: " + entry.mAttributionTag));
        }
    }

    public static class ScannerApp {
        /** Context information */
        @Nullable ScanController.PendingIntentInfo mInfo;

        /** Statistics for this app */
        AppScanStats mAppScanStats;

        /** The UUID of the application */
        final UUID mUuid;

        /** The package name of the application */
        final String mName;

        /** The last attribution tag in the attribution source chain */
        @Nullable final String mAttributionTag;

        /** Application callbacks */
        @Nullable IScannerCallback mCallback;

        /** The id of the application */
        int mId;

        /** Whether the calling app has location permission */
        boolean mHasLocationPermission;

        /** The user handle of the app that started the scan */
        @Nullable UserHandle mUserHandle;

        /** Whether the calling app has the network settings permission */
        boolean mHasNetworkSettingsPermission;

        /** Whether the calling app has the network setup wizard permission */
        boolean mHasNetworkSetupWizardPermission;

        /** Whether the calling app has the network setup wizard permission */
        boolean mHasScanWithoutLocationPermission;

        /** Whether the calling app has disavowed the use of bluetooth for location */
        boolean mHasDisavowedLocation;

        boolean mEligibleForSanitizedExposureNotification;

        @Nullable List<String> mAssociatedDevices;

        /** Death recipient */
        @Nullable private IBinder.DeathRecipient mDeathRecipient;

        /** Creates a new app context. */
        ScannerApp(
                UUID uuid,
                @Nullable String attributionTag,
                @Nullable IScannerCallback callback,
                @Nullable ScanController.PendingIntentInfo info,
                String name,
                AppScanStats appScanStats) {
            this.mUuid = uuid;
            this.mAttributionTag = attributionTag;
            this.mCallback = callback;
            this.mName = name;
            this.mInfo = info;
            this.mAppScanStats = appScanStats;
        }

        /** Link death recipient */
        void linkToDeath(IBinder.DeathRecipient deathRecipient) {
            // It might not be a binder object
            if (mCallback == null) {
                return;
            }
            try {
                IBinder binder = ((IInterface) mCallback).asBinder();
                binder.linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + mId);
            }
        }

        /** Unlink death recipient */
        void cleanup() {
            if (mDeathRecipient != null) {
                try {
                    IBinder binder = ((IInterface) mCallback).asBinder();
                    binder.unlinkToDeath(mDeathRecipient, 0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + mId);
                }
            }
            mAppScanStats.isRegistered = false;
        }
    }
}
