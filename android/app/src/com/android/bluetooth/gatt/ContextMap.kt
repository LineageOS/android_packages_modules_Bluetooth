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

import android.bluetooth.BluetoothDevice
import android.os.IInterface
import android.util.Log
import com.android.bluetooth.Utils.transportToString
import com.android.internal.annotations.GuardedBy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
import java.util.function.Predicate
import kotlin.text.appendLine

private const val TAG = GattUtil.TAG_PREFIX + "ContextMap"
private const val MAX_LAST_RECORDS = 5

/**
 * Helper class that keeps track of registered GATT applications. This class manages application
 * callbacks and keeps track of GATT connections.
 *
 * @param <C> the callback type (must implement {@link IInterface}) for this map
 */
class ContextMap<C : IInterface> {

    /** Our internal application list */
    private val appsLock = Any()

    @GuardedBy("appsLock") private val apps = mutableListOf<ContextApp<C>>()

    @GuardedBy("appsLock") private val ongoingRecords = mutableListOf<AppRecord>()

    @GuardedBy("appsLock") internal val lastRecords = mutableListOf<AppRecord>()

    private val connectionsLock = Any()

    /** Internal list of connected devices */
    @GuardedBy("connectionsLock") private val connections = mutableListOf<Connection>()

    inner class AppRecord(app: ContextApp<C>) {
        internal val uuid = app.uuid
        private val appName = app.name
        private val transport = app.transport
        private val tag = app.tag
        private val registerTime = Instant.now()

        internal var id = 0
        internal var removeReason: RemoveReason? = null
        internal var unregisterTime: Instant? = null

        private val dtf =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        override fun toString() =
            "AppRecord(${dtf.format(registerTime)} ~ ${dtf.format(unregisterTime)}, " +
                "id=$id, appName=$appName, transport=${transportToString(transport)}" +
                (tag?.let { ", tag=$it" } ?: "") +
                ", reason=$removeReason)"
    }

    /** Connection class helps map connection IDs to devices. */
    data class Connection(
        val connId: Int,
        val device: BluetoothDevice,
        val transport: Int,
        val appId: Int,
    ) {
        override fun toString() =
            "Connection(connId=$connId, device=$device" +
                ", transport=${transportToString(transport)}, appId=$appId)"
    }

    enum class RemoveReason {
        REASON_UNREGISTER_ALL,
        REASON_UNREGISTER_CLIENT,
        REASON_UNREGISTER_SERVER,
        REASON_BINDER_DIED,
        REASON_REGISTER_FAILED,
        REASON_UNKNOWN,
    }

    /** Add an entry to the application context list. */
    fun add(
        appUid: Int,
        appName: String,
        uuid: UUID,
        callback: C,
        transport: Int,
        tag: String?,
    ): ContextApp<C> {
        synchronized(appsLock) {
            val app = ContextApp(appUid, appName, uuid, callback, transport, tag)
            apps.add(app)
            recordRegisterApp(app)
            return app
        }
    }

    /** Remove the context for a given UUID */
    fun remove(uuid: UUID, reason: RemoveReason): ContextApp<C>? {
        synchronized(appsLock) {
            val i = apps.iterator()
            while (i.hasNext()) {
                val entry = i.next()
                if (entry.uuid == uuid) {
                    entry.unlinkToDeath()
                    i.remove()
                    recordUnregisterApp(entry, reason)
                    return entry
                }
            }
        }
        return null
    }

    /** Remove the context for a given application ID. */
    fun remove(id: Int, reason: RemoveReason): ContextApp<C>? {
        var removedApp: ContextApp<C>? = null
        synchronized(appsLock) {
            val i = apps.iterator()
            while (i.hasNext()) {
                val entry = i.next()
                if (entry.id == id) {
                    removedApp = entry
                    entry.unlinkToDeath()
                    i.remove()
                    recordUnregisterApp(entry, reason)
                    break
                }
            }
        }
        if (removedApp != null) {
            removeConnectionsByAppId(id)
        }
        return removedApp
    }

    fun getAllApps(): List<ContextApp<C>> {
        synchronized(appsLock) {
            return Collections.unmodifiableList(apps)
        }
    }

    /** Add a new connection for a given application ID. */
    fun addConnection(id: Int, connId: Int, transport: Int, device: BluetoothDevice) {
        synchronized(connectionsLock) {
            val entry = getById(id)
            if (entry != null) {
                connections.add(Connection(connId, device, transport, id))
            }
        }
    }

    /** Remove a connection with the given ID. */
    fun removeConnection(id: Int, connId: Int) {
        synchronized(connectionsLock) {
            connections.removeIf { conn -> conn.appId == id && conn.connId == connId }
        }
    }

    /** Remove all connections for a given application ID. */
    fun removeConnectionsByAppId(appId: Int) {
        synchronized(connectionsLock) { connections.removeIf { conn -> conn.appId == appId } }
    }

    private fun getAppByPredicate(predicate: Predicate<ContextApp<C>>): ContextApp<C>? {
        synchronized(appsLock) {
            // Intentionally using a for-loop over a stream for performance.
            for (app in apps) {
                if (predicate.test(app)) {
                    return app
                }
            }
            return null
        }
    }

    /** Get an application context by ID. */
    fun getById(id: Int): ContextApp<C>? {
        val app = getAppByPredicate { entry -> entry.id == id }
        if (app == null) {
            Log.e(TAG, "Context not found for ID $id")
        }
        return app
    }

    /** Get an application context by its callback object. */
    fun getByCallbackId(callbackId: C): ContextApp<C>? {
        val app = getAppByPredicate { entry -> entry.callback?.asBinder() == callbackId.asBinder() }
        if (app == null) {
            Log.e(TAG, "Context not found for callbackID $callbackId")
        }
        return app
    }

    /** Get an application context by UUID. */
    fun getByUuid(uuid: UUID): ContextApp<C>? {
        val app = getAppByPredicate { entry -> entry.uuid == uuid }
        if (app == null) {
            Log.e(TAG, "Context not found for UUID $uuid")
        }
        return app
    }

    /** Get all connected devices */
    fun getConnectedDevices(): Set<BluetoothDevice> {
        val devices = mutableSetOf<BluetoothDevice>()
        synchronized(connectionsLock) {
            for (connection in connections) {
                devices.add(connection.device)
            }
        }
        return devices
    }

    /** Get an application context by a connection ID. */
    fun getByConnId(connId: Int): ContextApp<C>? {
        var appId = -1
        synchronized(connectionsLock) {
            for (connection in connections) {
                if (connection.connId == connId) {
                    appId = connection.appId
                    break
                }
            }
        }
        if (appId >= 0) {
            return getById(appId)
        }
        return null
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
    fun getConnectionsByDevice(appId: Int, device: BluetoothDevice): List<Connection> {
        val currentConnections = mutableListOf<Connection>()
        synchronized(connectionsLock) {
            for (connection in connections) {
                if (connection.device == device && connection.appId == appId) {
                    currentConnections.add(connection)
                }
            }
        }
        return currentConnections
    }

    /** Returns the device for a given connection ID. */
    fun deviceByConnId(connId: Int): BluetoothDevice? {
        synchronized(connectionsLock) {
            for (connection in connections) {
                if (connection.connId == connId) {
                    return connection.device
                }
            }
        }
        return null
    }

    /** Returns all Connections that have a given app UID. */
    fun getConnectionByApp(appId: Int): List<Connection> {
        val currentConnections = mutableListOf<Connection>()
        synchronized(connectionsLock) {
            for (connection in connections) {
                if (connection.appId == appId) {
                    currentConnections.add(connection)
                }
            }
        }
        return currentConnections
    }

    /** Counts the number of applications that have a given app UID. */
    fun countByAppUid(appUid: Int): Int {
        synchronized(appsLock) {
            return apps.stream().filter { app -> app.uid == appUid }.count().toInt()
        }
    }

    /** Erases all application context entries. */
    fun clear() {
        synchronized(appsLock) {
            for (entry in apps) {
                entry.unlinkToDeath()
            }
            apps.clear()
            ongoingRecords.clear()
        }

        synchronized(connectionsLock) { connections.clear() }
    }

    /** Returns connect device map with addr and appid */
    fun getConnectedMap(): Map<Int, BluetoothDevice> {
        val connectedMap = mutableMapOf<Int, BluetoothDevice>()
        synchronized(connectionsLock) {
            for (conn in connections) {
                connectedMap[conn.appId] = conn.device
            }
        }
        return connectedMap
    }

    fun dump(sb: StringBuilder) {
        synchronized(appsLock) {
            sb.appendLine("  Entries: ${getAllApps().size}")
            sb.appendLine("  Last apps: ")
            lastRecords.forEach { sb.appendLine("       $it") }
            sb.appendLine()
        }
    }

    @GuardedBy("appsLock")
    private fun recordRegisterApp(app: ContextApp<C>) {
        ongoingRecords.add(AppRecord(app))
    }

    @GuardedBy("appsLock")
    private fun recordUnregisterApp(app: ContextApp<C>, reason: RemoveReason) {
        for (i in 0 until ongoingRecords.size) {
            if (app.uuid == ongoingRecords[i].uuid) {
                val record = ongoingRecords.removeAt(i)
                record.id = app.id
                record.removeReason = reason
                record.unregisterTime = Instant.now()

                if (lastRecords.size >= MAX_LAST_RECORDS) {
                    lastRecords.removeAt(0)
                }
                lastRecords.add(record)
                break
            }
        }
    }
}
