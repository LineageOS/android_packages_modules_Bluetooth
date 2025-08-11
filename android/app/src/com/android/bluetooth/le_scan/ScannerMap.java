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
import static com.android.bluetooth.util.AttributionSourceUtils.getLastAttributionTag;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/** List of our registered scanners. */
class ScannerMap {
    private static final String TAG = ScannerMap.class.getSimpleName();

    /** Internal map to keep track of logging information by app name */
    private final HashMap<Integer, AppScanStats> mAppScanStatsMap = new HashMap<>();

    private final ConcurrentLinkedQueue<ScannerApp> mApps = new ConcurrentLinkedQueue<>();

    ScannerApp addWithCallback(
            UUID uuid,
            AttributionSource source,
            WorkSource workSource,
            int uid,
            IScannerCallback callback,
            AdapterService adapterService,
            ScanController scanController) {
        return add(
                uuid,
                null,
                source,
                workSource,
                uid,
                callback,
                null,
                adapterService,
                scanController);
    }

    ScannerApp addWithPendingIntent(
            UUID uuid,
            UserHandle userHandle,
            AttributionSource source,
            ScanController.PendingIntentInfo pendingIntentInfo,
            AdapterService adapterService,
            ScanController scanController) {
        return add(
                uuid,
                userHandle,
                source,
                null,
                0, // uid is not considered from here as the pendingIntentInfo is set
                null,
                pendingIntentInfo,
                adapterService,
                scanController);
    }

    private ScannerApp add(
            UUID uuid,
            @Nullable UserHandle userHandle,
            AttributionSource source,
            @Nullable WorkSource workSource,
            int uid,
            @Nullable IScannerCallback callback,
            @Nullable ScanController.PendingIntentInfo piInfo,
            AdapterService adapterService,
            ScanController scanController) {
        int appUid;
        String appName;
        if (piInfo != null) {
            appUid = piInfo.callingUid();
            appName = piInfo.callingPackage();
        } else {
            appUid = uid;
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
                            appUid,
                            adapterService,
                            scanController,
                            getSystemClock());
            mAppScanStatsMap.put(appUid, appScanStats);
        }
        ScannerApp app =
                new ScannerApp(
                        uuid,
                        userHandle,
                        getLastAttributionTag(source),
                        callback,
                        piInfo,
                        appName,
                        appScanStats);
        mApps.add(app);
        appScanStats.mIsRegistered = true;
        return app;
    }

    /** Remove the context for a given application ID. */
    void remove(int id) {
        removeByPredicate(app -> app.mId == id);
    }

    /** Remove the context for a given UUID */
    void remove(UUID uuid) {
        Log.d(TAG, "remove() - uuid: " + uuid);
        removeByPredicate(app -> app.mUuid.equals(uuid));
    }

    private void removeByPredicate(Predicate<ScannerApp> predicate) {
        for (var iterator = mApps.iterator(); iterator.hasNext(); ) {
            var scannerApp = iterator.next();
            if (predicate.test(scannerApp)) {
                scannerApp.cleanup();
                iterator.remove();
                break;
            }
        }
    }

    /** Erases all application context entries. */
    void clear() {
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
        return mApps.stream().filter(app -> app.mName.equals(name)).toList();
    }

    /** Get an application context by the pending intent info object's intent. */
    ScannerApp getByPendingIntentInfo(PendingIntent intent) {
        ScannerApp app =
                getAppByPredicate(
                        entry -> entry.mInfo != null && entry.mInfo.intent().equals(intent));
        if (app == null) {
            Log.e(TAG, "Context not found for intent " + intent);
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

    /** Logs debug information for registered apps and their scan statistics. */
    void dump(StringBuilder sb, Map<Integer, ScanSettings> settingsMap) {
        sb.append("LE Scanner:\n");
        for (ScannerApp entry : mApps) {
            StringBuilder line = new StringBuilder();
            line.append("  app_if: ").append(entry.mId).append(", appName: ").append(entry.mName);

            if (entry.mAttributionTag != null) {
                line.append(", tag: ").append(entry.mAttributionTag);
            }

            final var settings = settingsMap.get(entry.mId);
            if (settings != null) {
                long reportDelayMillis = settings.getReportDelayMillis();
                if (reportDelayMillis > 0) {
                    line.append(", reportDelayMillis: ").append(reportDelayMillis);
                }
            }
            sb.append(line).append("\n");
        }

        sb.append("\nLE Scanner Map:\n");
        sb.append("  Entries: ").append(mAppScanStatsMap.size()).append("\n\n");
        for (AppScanStats appScanStats : mAppScanStatsMap.values()) {
            var scannerApps = getByName(appScanStats.mAppName);
            appScanStats.dump(sb, scannerApps);
        }
    }

    static class ScannerApp {
        final UUID mUuid;
        @Nullable final UserHandle mUserHandle; // The user handle of the app that started the scan

        /** The last attribution tag in the attribution source chain */
        @Nullable final String mAttributionTag;

        @Nullable IScannerCallback mCallback;
        final String mName; // The package name of the application

        @Nullable ScanController.PendingIntentInfo mInfo; // Context information
        AppScanStats mAppScanStats;
        int mId;
        boolean mHasLocationPermission;
        boolean mHasNetworkSettingsPermission;
        boolean mHasNetworkSetupWizardPermission;
        boolean mHasScanWithoutLocationPermission;
        boolean mHasDisavowedLocation;
        boolean mEligibleForSanitizedExposureNotification;
        @Nullable List<String> mAssociatedDevices;
        @Nullable private ScanController.ScannerDeathRecipient mDeathRecipient;

        ScannerApp(
                UUID uuid,
                @Nullable UserHandle userHandle,
                @Nullable String attributionTag,
                @Nullable IScannerCallback callback,
                @Nullable ScanController.PendingIntentInfo info,
                String name,
                AppScanStats appScanStats) {
            mUuid = uuid;
            mUserHandle = userHandle;
            mAttributionTag = attributionTag;
            mCallback = callback;
            mName = name;
            mInfo = info;
            mAppScanStats = appScanStats;
        }

        void linkToDeath(ScanController.ScannerDeathRecipient deathRecipient) {
            // It might not be a binder object
            if (mCallback == null) {
                return;
            }
            try {
                mCallback.asBinder().linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + mId);
                cleanup();
            }
        }

        /** Unlink death recipient */
        void cleanup() {
            if (mDeathRecipient != null && mCallback != null) {
                try {
                    mCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + mId);
                }
            }
            mAppScanStats.mIsRegistered = false;
        }
    }
}
