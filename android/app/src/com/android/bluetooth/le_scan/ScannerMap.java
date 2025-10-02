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

import static com.android.bluetooth.util.AttributionSourceUtils.getLastAttributionTag;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.util.TimeProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            int appUid,
            String appName,
            UUID uuid,
            AttributionSource source,
            WorkSource workSource,
            IScannerCallback callback,
            AdapterService adapterService,
            ScanController scanController) {
        return add(
                appUid,
                appName,
                uuid,
                null,
                source,
                workSource,
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
                pendingIntentInfo.callingUid(),
                ScanUtil.appNameOrUnknown(
                        pendingIntentInfo.callingPackage(), pendingIntentInfo.callingUid()),
                uuid,
                userHandle,
                source,
                null,
                null,
                pendingIntentInfo,
                adapterService,
                scanController);
    }

    private ScannerApp add(
            int appUid,
            String appName,
            UUID uuid,
            @Nullable UserHandle userHandle,
            AttributionSource source,
            @Nullable WorkSource workSource,
            @Nullable IScannerCallback callback,
            @Nullable ScanController.PendingIntentInfo piInfo,
            AdapterService adapterService,
            ScanController scanController) {
        AppScanStats appScanStats = mAppScanStatsMap.get(appUid);
        if (appScanStats == null) {
            appScanStats =
                    new AppScanStats(
                            appName,
                            workSource,
                            appUid,
                            adapterService,
                            scanController,
                            TimeProvider.getSystemClock());
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
        removeByPredicate(app -> app.getId() == id);
    }

    /** Remove the context for a given UUID */
    void remove(UUID uuid) {
        Log.d(TAG, "remove() - uuid: " + uuid);
        removeByPredicate(app -> app.getUuid().equals(uuid));
    }

    private void removeByPredicate(Predicate<ScannerApp> predicate) {
        for (var iterator = mApps.iterator(); iterator.hasNext(); ) {
            var app = iterator.next();
            if (predicate.test(app)) {
                app.cleanup();
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
        ScannerApp temp = getById(id);
        if (temp != null) {
            return temp.getAppScanStats();
        }
        return null;
    }

    /** Get an application context by ID. */
    ScannerApp getById(int id) {
        ScannerApp app = getAppByPredicate(entry -> entry.getId() == id);
        if (app == null) {
            Log.e(TAG, "Context not found for ID " + id);
        }
        return app;
    }

    /** Get an application context by UUID. */
    ScannerApp getByUuid(UUID uuid) {
        ScannerApp app = getAppByPredicate(entry -> entry.getUuid().equals(uuid));
        if (app == null) {
            Log.e(TAG, "Context not found for UUID " + uuid);
        }
        return app;
    }

    /** Get application contexts by the calling app's name. */
    List<ScannerApp> getByName(String name) {
        return mApps.stream().filter(app -> app.getName().equals(name)).toList();
    }

    /** Get an application context by the pending intent info object's intent. */
    ScannerApp getByPendingIntentInfo(PendingIntent intent) {
        ScannerApp app =
                getAppByPredicate(e -> e.getInfo() != null && e.getInfo().intent().equals(intent));
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
            line.append("  app_if: ")
                    .append(entry.getId())
                    .append(", appName: ")
                    .append(entry.getName());

            if (entry.getAttributionTag() != null) {
                line.append(", tag: ").append(entry.getAttributionTag());
            }

            final var settings = settingsMap.get(entry.getId());
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
            appScanStats.dump(sb, getByName(appScanStats.mAppName));
        }
    }
}
