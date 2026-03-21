/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.gatt

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = GattUtil.TAG_PREFIX + "HandleMap"

class HandleMap {
    enum class Type {
        SERVICE,
        CHARACTERISTIC,
        DESCRIPTOR,
    }

    val entries = CopyOnWriteArrayList<Entry>()
    private var lastCharacteristic = 0

    private val requestMap = ConcurrentHashMap<Int, RequestData>()
    private val nextRequestId = AtomicInteger(0)
    private val requestContextMap = ConcurrentHashMap<Int, RequestContext>()

    fun clear() {
        entries.clear()
        requestMap.clear()
    }

    data class Entry(
        val serverIf: Int,
        val handle: Int,
        val uuid: UUID,
        val type: Type = Type.SERVICE,
        val instance: Int = 0,
        val serviceType: Int = 0,
        val serviceHandle: Int = 0,
        val charHandle: Int = 0,
        val advertisePreferred: Boolean = false,
    )

    data class RequestData(val connId: Int, val handle: Int)

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
    data class RequestContext(
        val serverIf: Int,
        val requestId: Int,
        val connId: Int,
        val transactionId: Int,
        val handle: Int,
    )

    fun addService(
        serverIf: Int,
        handle: Int,
        uuid: UUID,
        serviceType: Int,
        instance: Int,
        advertisePreferred: Boolean,
    ) {
        entries.add(
            Entry(
                serverIf = serverIf,
                handle = handle,
                uuid = uuid,
                type = Type.SERVICE,
                instance = instance,
                serviceType = serviceType,
                advertisePreferred = advertisePreferred,
            )
        )
    }

    fun addCharacteristic(serverIf: Int, handle: Int, uuid: UUID, serviceHandle: Int) {
        lastCharacteristic = handle
        entries.add(
            Entry(
                serverIf = serverIf,
                handle = handle,
                uuid = uuid,
                type = Type.CHARACTERISTIC,
                serviceHandle = serviceHandle,
            )
        )
    }

    fun addDescriptor(serverIf: Int, handle: Int, uuid: UUID, serviceHandle: Int) {
        entries.add(
            Entry(
                serverIf = serverIf,
                handle = handle,
                uuid = uuid,
                type = Type.DESCRIPTOR,
                serviceHandle = serviceHandle,
                charHandle = lastCharacteristic,
            )
        )
    }

    fun getByHandle(handle: Int): Entry? {
        val entry = entries.firstOrNull { it.handle == handle }
        if (entry == null) {
            Log.e(TAG, "getByHandle($handle): Not found!")
        }
        return entry
    }

    fun checkServiceExists(uuid: UUID, handle: Int) = entries.any {
        it.type == Type.SERVICE && it.handle == handle && it.uuid == uuid
    }

    fun deleteService(serverIf: Int, serviceHandle: Int) = entries.removeIf { entry ->
        (entry.serverIf == serverIf) &&
            (entry.handle == serviceHandle || entry.serviceHandle == serviceHandle)
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
    fun addRequestContext(serverIf: Int, connId: Int, transactionId: Int, handle: Int): Int {
        val requestId = nextRequestId.getAndIncrement()
        val context = RequestContext(serverIf, requestId, connId, transactionId, handle)
        requestContextMap[requestId] = context
        Log.d(
            TAG,
            "addRequestContext(): serverIf=$serverIf, requestId=$requestId, context=$context",
        )
        return requestId
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
    fun getRequestContext(serverIf: Int, requestId: Int): RequestContext? {
        Log.d(TAG, "getRequestContext(serverIf=$serverIf, requestId=$requestId)")

        val context = requestContextMap[requestId]
        if (context == null) {
            Log.w(TAG, "getRequestContext(): requestId=$requestId does not exist")
            return null
        }

        // Make sure the requesting server owns the request ID
        if (serverIf != context.serverIf) {
            Log.w(
                TAG,
                "getRequestContext(): serverIf mismatch for requestId=$requestId" +
                    ", requester=$serverIf, context=${context.serverIf}",
            )
            return null
        }

        Log.d(
            TAG,
            "getRequestContext(): serverIf=$serverIf, requestId=$requestId, context=$context",
        )

        return context
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
    fun deleteRequestContext(serverIf: Int, requestId: Int) {
        Log.d(TAG, "deleteRequestContext(serverIf=$serverIf, requestId=$requestId)")

        val context = requestContextMap[requestId] ?: return
        if (serverIf != context.serverIf) {
            Log.w(
                TAG,
                "deleteRequestContext(): serverIf mismatch, requester=$serverIf" +
                    ", context=${context.serverIf}",
            )
            return
        }

        requestContextMap.remove(requestId)
    }

    fun dump(sb: StringBuilder) {
        sb.appendLine("  Entries: ${entries.size}")
        for (entry in entries) {
            sb.append("    ${entry.serverIf}: [${entry.handle.toString().padStart(3, ' ')}] ")
            when (entry.type) {
                Type.SERVICE -> sb.appendLine("Service ${entry.uuid}")
                Type.CHARACTERISTIC -> sb.appendLine("  Characteristic ${entry.uuid}")
                Type.DESCRIPTOR -> sb.appendLine("    Descriptor ${entry.uuid}")
            }
        }
        sb.appendLine("  Requests: ${requestMap.size}")
        for (context in requestContextMap.values) {
            sb.appendLine("      $context")
        }
    }

    companion object {
        // Prepared writes can be requested by a client, requesting that a server implementation
        // hold all write requests until the client commits or executes the write. This execution is
        // meant to commit all the writes, in the order they were received, on the server. This
        // operations is exposed to server apps via the 'isPrep' field in the various request
        // callbacks, combined with the onExecuteWrite() callback when the client is ready to
        // commit. While both operations have transaction IDs, the characteristics written to
        // usually have attribute handles that are exposed to apps, but the prepared write commit
        // operation itself does not. Despite this, our APIs used to send a response require a
        // handle. We use 0 as a dummy handle to respond to these prepared writes. By specification,
        // this is an invalid handle, so its safe to use without consequence. The stack will ignore
        // the handle down below anyways.
        const val HANDLE_PREPARED_WRITE = 0
    }
}
