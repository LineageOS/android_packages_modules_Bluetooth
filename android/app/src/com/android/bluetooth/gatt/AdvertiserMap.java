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
package com.android.bluetooth.gatt;

import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.PeriodicAdvertisingParameters;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;

/** Helper class that keeps track of advertiser stats. */
class AdvertiserMap {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + AdvertiserMap.class.getSimpleName();

    /** Internal map to keep track of logging information by advertise id */
    @GuardedBy("this")
    private final HashMap<Integer, AppAdvertiseStats> mAppAdvertiseStats = new HashMap<>();

    private final BluetoothEventLogger mLastAdvertises =
            new BluetoothEventLogger(5, "Last Advertising");

    /** Add an entry to the stats map if it doesn't already exist. */
    void addAppAdvertiseStats(int id, Context context, AttributionSource attrSource) {
        int appUid = Binder.getCallingUid();
        String appName = context.getPackageManager().getNameForUid(appUid);
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }

        synchronized (this) {
            if (!mAppAdvertiseStats.containsKey(id)) {
                addAppAdvertiseStats(id, new AppAdvertiseStats(appUid, id, appName, attrSource));
            }
        }
    }

    @VisibleForTesting
    synchronized void addAppAdvertiseStats(int id, AppAdvertiseStats stats) {
        mAppAdvertiseStats.put(id, stats);
    }

    /** Remove the context for a given application ID. */
    synchronized void removeAppAdvertiseStats(int id) {
        mAppAdvertiseStats.remove(id);
    }

    /** Get Logging info by ID */
    synchronized AppAdvertiseStats getAppAdvertiseStatsById(int id) {
        return mAppAdvertiseStats.get(id);
    }

    /** update the advertiser ID by the register ID */
    synchronized void setAdvertiserIdByRegId(int regId, int advertiserId) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(regId);
        if (stats == null) {
            return;
        }
        stats.setId(advertiserId);
        mAppAdvertiseStats.remove(regId);
        mAppAdvertiseStats.put(advertiserId, stats);
    }

    synchronized void recordAdvertiseStart(
            int id,
            AdvertisingSetParameters parameters,
            AdvertiseData advertiseData,
            AdvertiseData scanResponse,
            PeriodicAdvertisingParameters periodicParameters,
            AdvertiseData periodicData,
            int duration,
            int maxExtAdvEvents) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        int advertiseInstanceCount = mAppAdvertiseStats.size();
        Log.d(TAG, "advertiseInstanceCount is " + advertiseInstanceCount);
        AppAdvertiseStats.recordAdvertiseInstanceCount(advertiseInstanceCount);
        stats.recordAdvertiseStart(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                advertiseInstanceCount);
    }

    synchronized void recordAdvertiseStop(int id) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.recordAdvertiseStop(mAppAdvertiseStats.size());
        mAppAdvertiseStats.remove(id);

        StringBuilder sb = new StringBuilder();
        AppAdvertiseStats.dumpToString(sb, stats);
        mLastAdvertises.add(sb.toString());
    }

    synchronized void enableAdvertisingSet(
            int id, boolean enable, int duration, int maxExtAdvEvents) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.enableAdvertisingSet(enable, duration, maxExtAdvEvents, mAppAdvertiseStats.size());
    }

    synchronized void setAdvertisingData(int id, AdvertiseData data) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.setAdvertisingData(data);
    }

    synchronized void setScanResponseData(int id, AdvertiseData data) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.setScanResponseData(data);
    }

    synchronized void setAdvertisingParameters(int id, AdvertisingSetParameters parameters) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.setAdvertisingParameters(parameters);
    }

    synchronized void setPeriodicAdvertisingParameters(
            int id, PeriodicAdvertisingParameters parameters) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.setPeriodicAdvertisingParameters(parameters);
    }

    synchronized void setPeriodicAdvertisingData(int id, AdvertiseData data) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.setPeriodicAdvertisingData(data);
    }

    synchronized void onPeriodicAdvertiseEnabled(int id, boolean enable) {
        AppAdvertiseStats stats = mAppAdvertiseStats.get(id);
        if (stats == null) {
            return;
        }
        stats.onPeriodicAdvertiseEnabled(enable);
    }

    /** Erases all entries. */
    synchronized void clear() {
        mAppAdvertiseStats.clear();
        mLastAdvertises.clear();
    }

    /** Logs advertiser debug information. */
    synchronized void dump(StringBuilder sb) {
        mLastAdvertises.dump(sb);

        if (!mAppAdvertiseStats.isEmpty()) {
            sb.append("  Total number of ongoing advertising                   : ")
                    .append(mAppAdvertiseStats.size());
            sb.append("\n  Ongoing advertising:");
            for (Integer key : mAppAdvertiseStats.keySet()) {
                AppAdvertiseStats stats = mAppAdvertiseStats.get(key);
                AppAdvertiseStats.dumpToString(sb, stats);
            }
        }
        sb.append("\n");
        Log.d(TAG, sb.toString());
    }
}
