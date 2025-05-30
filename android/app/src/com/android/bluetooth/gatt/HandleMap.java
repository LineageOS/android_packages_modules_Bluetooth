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

import com.android.bluetooth.flags.Flags;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class HandleMap {
    private static final String TAG =
            GattServiceConfig.TAG_PREFIX + HandleMap.class.getSimpleName();

    enum Type {
        SERVICE,
        CHARACTERISTIC,
        DESCRIPTOR,
    }

    private final List<Entry> mEntries = new CopyOnWriteArrayList<>();
    private int mLastCharacteristic = 0;

    private final Map<Integer, RequestData> mRequestMap = new ConcurrentHashMap<>();
    private final AtomicInteger mNextRequestId = new AtomicInteger(0);
    private final Map<Integer, RequestContext> mRequestContextMap = new ConcurrentHashMap<>();

    void clear() {
        mEntries.clear();
        mRequestMap.clear();
    }

    static class Entry {
        final int mServerIf;
        final Type mType;
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
            mType = Type.SERVICE;
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
            mType = Type.SERVICE;
            mHandle = handle;
            mUuid = uuid;
            mInstance = instance;
            mServiceType = serviceType;
            mAdvertisePreferred = advertisePreferred;
        }

        Entry(int serverIf, Type type, int handle, UUID uuid, int serviceHandle) {
            mServerIf = serverIf;
            mType = type;
            mHandle = handle;
            mUuid = uuid;
            mServiceHandle = serviceHandle;
        }

        Entry(int serverIf, Type type, int handle, UUID uuid, int serviceHandle, int charHandle) {
            mServerIf = serverIf;
            mType = type;
            mHandle = handle;
            mUuid = uuid;
            mServiceHandle = serviceHandle;
            mCharHandle = charHandle;
        }
    }

    record RequestData(int connId, int handle) {}

    /*
     * Represents an in-flight request from a client, that's being processed by a server app
     *
     * <p>A request has a connection ID (indicates the bearer), a transaction ID (indicates the
     * transaction with the server, so the client can correlate the response with the request) and a
     * handle (points to the bit of info being requested).
     *
     * <p>Transaction IDs have a domain private to a given bearer, meaning different bearers for the
     * same device can use the same ID. This means all three bits of info are important context. It
     * also means some bits can be reused, and transaction ID isn't unique for a device.
     *
     * <p>As well, this HandleMap associates request contexts, and thus IDs, with the server app
     * that requested them. Once a request context is created and an ID given out, other server apps
     * are not allowed to get or delete another server's request contexts.
     */
    record RequestContext(int serverIf, int requestId, int connId, int transactionId, int handle) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("RequestContext<")
                    .append("request_id: ")
                    .append(requestId)
                    .append(", server_if: ")
                    .append(serverIf)
                    .append(", conn_id: ")
                    .append(connId)
                    .append(", transaction_id: ")
                    .append(transactionId)
                    .append(", handle: ")
                    .append(handle)
                    .append(">");
            return sb.toString();
        }
    }

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
        mEntries.add(new Entry(serverIf, Type.CHARACTERISTIC, handle, uuid, serviceHandle));
    }

    void addDescriptor(int serverIf, int handle, UUID uuid, int serviceHandle) {
        mEntries.add(
                new Entry(
                        serverIf,
                        Type.DESCRIPTOR,
                        handle,
                        uuid,
                        serviceHandle,
                        mLastCharacteristic));
    }

    void setStarted(int serverIf, int handle, boolean started) {
        for (Entry entry : mEntries) {
            if (entry.mType != Type.SERVICE
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
            if (entry.mType == Type.SERVICE
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

    /*
     * Please do not use. Remove when flag::gatt_multi_bearer_transactions is removed
     */
    void addRequest(int connId, int requestId, int handle) {
        if (Flags.gattMultiBearerTransactions()) {
            throw new IllegalStateException("Not available, gatt_multi_bearer_transactions=true");
        }
        mRequestMap.put(requestId, new RequestData(connId, handle));
    }

    /*
     * Please do not use. Remove when flag::gatt_multi_bearer_transactions is removed
     */
    void deleteRequest(int requestId) {
        if (Flags.gattMultiBearerTransactions()) {
            throw new IllegalStateException("Not available, gatt_multi_bearer_transactions=true");
        }
        mRequestMap.remove(requestId);
    }

    /*
     * Please do not use. Remove when flag::gatt_multi_bearer_transactions is removed
     */
    RequestData getRequestDataByRequestId(int requestId) {
        if (Flags.gattMultiBearerTransactions()) {
            throw new IllegalStateException("Not available, gatt_multi_bearer_transactions=true");
        }
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

    /*
     * Store a request context in this handle map and receive an integer identifier to that
     * request. This requestId will belong to the
     *
     * <p>To recall these bits, this handle map maps the context to an integer identifier that's
     * then returned. This can be sent to the app with the callback. The app will send the same
     * identifier back with its response, with which we can then recall from this handle map with
     * getRequestContextForIdentifier();
     */
    int addRequestContext(int serverIf, int connId, int transactionId, int handle) {
        int requestId = mNextRequestId.getAndIncrement();
        RequestContext context =
                new RequestContext(serverIf, requestId, connId, transactionId, handle);
        mRequestContextMap.put(requestId, context);
        Log.d(
                TAG,
                "addRequestContext() - "
                        + ("serverIf=" + serverIf)
                        + (", requestId=" + requestId)
                        + (", context=" + context));
        return requestId;
    }

    /**
     * Get the request context associated with a given request ID.
     *
     * <p>Request IDs are given out through the above addRequestContext() and removed with the below
     * deleteRequestContext().
     *
     * <p>Request IDs are specific to the requesting server app and its serverIf. One server app is
     * forbidden from fetching the request context of another server app. If the context you request
     * does not belong to your app, or it doesn't exist, this function will return null.
     */
    RequestContext getRequestContext(int serverIf, int requestId) {
        Log.d(TAG, "getRequestContext() - serverIf=" + serverIf + ", requestId=" + requestId);

        RequestContext context = mRequestContextMap.get(requestId);
        if (context == null) {
            Log.w(TAG, "getRequestContext() - requestId=" + requestId + " does not exist");
            return null;
        }

        // Make sure the requesting server owns the request ID
        if (context != null && serverIf != context.serverIf()) {
            Log.w(
                    TAG,
                    "getRequestContext() - "
                            + ("serverIf mismatch for requestId=" + requestId)
                            + (", requester=" + serverIf)
                            + (", context=" + context.serverIf()));
            return null;
        }

        Log.d(
                TAG,
                "getRequestContext() - "
                        + ("serverIf=" + serverIf)
                        + (", requestId=" + requestId)
                        + (", context=" + context));

        return context;
    }

    /**
     * Delete the request context associated with a given request ID.
     *
     * <p>Request IDs are given out through the above addRequestContext() and can be looked up with
     * the above getRequestContext().
     *
     * <p>Request IDs are specific to the requesting server app and its serverIf. One server app is
     * forbidden from deleting a request ID associated with another server app. If the context you
     * request to delete does not belong to your app, or it doesn't exist, this function is a no-op.
     */
    void deleteRequestContext(int serverIf, int requestId) {
        Log.d(TAG, "deleteRequestContext() - serverIf=" + serverIf + ", requestId=" + requestId);

        RequestContext context = mRequestContextMap.get(requestId);
        if (context == null) {
            return;
        }

        if (serverIf != context.serverIf()) {
            Log.w(
                    TAG,
                    "deleteRequestContext() - "
                            + ("serverIf mismatch, requester=" + serverIf)
                            + (", context=" + context.serverIf()));
            return;
        }

        mRequestContextMap.remove(requestId);
    }

    /** Logs debug information. */
    void dump(StringBuilder sb) {
        sb.append("  Entries: ").append(mEntries.size()).append("\n");
        for (Entry entry : mEntries) {
            sb.append("      ")
                    .append(entry.mServerIf)
                    .append(": [")
                    .append(entry.mHandle)
                    .append("] ");
            switch (entry.mType) {
                case Type.SERVICE -> {
                    sb.append("Service ").append(entry.mUuid);
                    sb.append(", started ").append(entry.mStarted);
                }
                case Type.CHARACTERISTIC -> sb.append("  Characteristic ").append(entry.mUuid);
                case Type.DESCRIPTOR -> sb.append("    Descriptor ").append(entry.mUuid);
            }
            sb.append("\n");
        }

        sb.append("  Requests: ").append(mRequestMap.size()).append("\n");
        if (Flags.gattMultiBearerTransactions()) {
            for (RequestContext context : mRequestContextMap.values()) {
                sb.append("      ").append(context).append("\n");
            }
        } else {
            for (Integer key : mRequestMap.keySet()) {
                RequestData request = mRequestMap.get(key);
                sb.append("RequestData<")
                        .append("request_id/transaction_id: ")
                        .append(key)
                        .append(", conn_id: ")
                        .append(request.connId())
                        .append(", handle: ")
                        .append(request.handle())
                        .append(">\n");
            }
        }
    }
}
