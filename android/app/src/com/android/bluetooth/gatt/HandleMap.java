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

import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class HandleMap {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + HandleMap.class.getSimpleName();

    static final int TYPE_SERVICE = 1;
    private static final int TYPE_CHARACTERISTIC = 2;
    private static final int TYPE_DESCRIPTOR = 3;

    private final List<Entry> mEntries = new CopyOnWriteArrayList<>();
    private final Map<Integer, RequestData> mRequestMap = new ConcurrentHashMap<>();
    private int mLastCharacteristic = 0;

    void clear() {
        mEntries.clear();
        mRequestMap.clear();
    }

    static class Entry {
        final int mServerIf;
        final int mType;
        final int mHandle;
        final UUID mUuid;
        int mInstance = 0;
        int mServiceType = 0;
        int mServiceHandle = 0;
        int mCharHandle = 0;
        boolean mAdvertisePreferred = false;
        boolean mStarted = false;

        Entry(int serverIf, int handle, UUID uuid, int serviceType, int instance) {
            mServerIf = serverIf;
            mType = TYPE_SERVICE;
            mHandle = handle;
            mUuid = uuid;
            mInstance = instance;
            mServiceType = serviceType;
        }

        Entry(
                int serverIf,
                int handle,
                UUID uuid,
                int serviceType,
                int instance,
                boolean advertisePreferred) {
            mServerIf = serverIf;
            mType = TYPE_SERVICE;
            mHandle = handle;
            mUuid = uuid;
            mInstance = instance;
            mServiceType = serviceType;
            mAdvertisePreferred = advertisePreferred;
        }

        Entry(int serverIf, int type, int handle, UUID uuid, int serviceHandle) {
            mServerIf = serverIf;
            mType = type;
            mHandle = handle;
            mUuid = uuid;
            mServiceHandle = serviceHandle;
        }

        Entry(int serverIf, int type, int handle, UUID uuid, int serviceHandle, int charHandle) {
            mServerIf = serverIf;
            mType = type;
            mHandle = handle;
            mUuid = uuid;
            mServiceHandle = serviceHandle;
            mCharHandle = charHandle;
        }
    }

    record RequestData(int connId, int handle) {}

    List<Entry> getEntries() {
        return mEntries;
    }

    void addService(
            int serverIf,
            int handle,
            UUID uuid,
            int serviceType,
            int instance,
            boolean advertisePreferred) {
        mEntries.add(new Entry(serverIf, handle, uuid, serviceType, instance, advertisePreferred));
    }

    void addCharacteristic(int serverIf, int handle, UUID uuid, int serviceHandle) {
        mLastCharacteristic = handle;
        mEntries.add(new Entry(serverIf, TYPE_CHARACTERISTIC, handle, uuid, serviceHandle));
    }

    void addDescriptor(int serverIf, int handle, UUID uuid, int serviceHandle) {
        mEntries.add(
                new Entry(
                        serverIf,
                        TYPE_DESCRIPTOR,
                        handle,
                        uuid,
                        serviceHandle,
                        mLastCharacteristic));
    }

    void setStarted(int serverIf, int handle, boolean started) {
        for (Entry entry : mEntries) {
            if (entry.mType != TYPE_SERVICE
                    || entry.mServerIf != serverIf
                    || entry.mHandle != handle) {
                continue;
            }

            entry.mStarted = started;
            return;
        }
    }

    Entry getByHandle(int handle) {
        for (Entry entry : mEntries) {
            if (entry.mHandle == handle) {
                return entry;
            }
        }
        Log.e(TAG, "getByHandle() - Handle " + handle + " not found!");
        return null;
    }

    boolean checkServiceExists(UUID uuid, int handle) {
        for (Entry entry : mEntries) {
            if (entry.mType == TYPE_SERVICE
                    && entry.mHandle == handle
                    && entry.mUuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    void deleteService(int serverIf, int serviceHandle) {
        mEntries.removeIf(
                entry ->
                        ((entry.mServerIf == serverIf)
                                && (entry.mHandle == serviceHandle
                                        || entry.mServiceHandle == serviceHandle)));
    }

    void addRequest(int connId, int requestId, int handle) {
        mRequestMap.put(requestId, new RequestData(connId, handle));
    }

    void deleteRequest(int requestId) {
        mRequestMap.remove(requestId);
    }

    Entry getByRequestId(int requestId) {
        Integer handle = null;
        RequestData data = mRequestMap.get(requestId);
        if (data != null) {
            handle = data.handle;
        }

        if (handle == null) {
            Log.e(TAG, "getByRequestId() - Request ID " + requestId + " not found!");
            return null;
        }
        return getByHandle(handle);
    }

    RequestData getRequestDataByRequestId(int requestId) {
        RequestData data = mRequestMap.get(requestId);
        if (data == null) {
            Log.e(TAG, "getRequestDataByRequestId() - Request ID " + requestId + " not found!");
        } else {
            Log.d(
                    TAG,
                    ("getRequestDataByRequestId(), requestId=" + requestId)
                            + (", connId=" + data.connId + ",handle=" + data.handle));
        }

        return data;
    }

    /** Logs debug information. */
    void dump(StringBuilder sb) {
        sb.append("  Entries: ").append(mEntries.size()).append("\n");
        sb.append("  Requests: ").append(mRequestMap.size()).append("\n");

        for (Entry entry : mEntries) {
            sb.append("  ")
                    .append(entry.mServerIf)
                    .append(": [")
                    .append(entry.mHandle)
                    .append("] ");
            switch (entry.mType) {
                case TYPE_SERVICE:
                    sb.append("Service ").append(entry.mUuid);
                    sb.append(", started ").append(entry.mStarted);
                    break;
                case TYPE_CHARACTERISTIC:
                    sb.append("  Characteristic ").append(entry.mUuid);
                    break;
                case TYPE_DESCRIPTOR:
                    sb.append("    Descriptor ").append(entry.mUuid);
                    break;
            }
            sb.append("\n");
        }
    }
}
