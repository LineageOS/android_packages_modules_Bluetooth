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
import com.android.bluetooth.Util.Transport
import com.android.internal.annotations.GuardedBy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
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
        private val tagString
            get() = tag?.let { ", tag=$it" } ?: ""

        override fun toString() =
            "AppRecord(${dtf.format(registerTime)} ~ ${dtf.format(unregisterTime)}, id=$id" +
                ", appName=$appName, ${Transport(transport)}$tagString, reason=$removeReason)"
    }

    /** Connection class helps map connection IDs to devices. */
    data class Connection(
        val connId: Int,
        val device: BluetoothDevice,
        val transport: Int,
        val appId: Int,
    ) {
        override fun toString() =
            "Connection(connId=$connId, $device, ${Transport(transport)}, appId=$appId)"
    }

    enum class RemoveReason {
        REASON_UNREGISTER_ALL,
        REASON_UNREGISTER_CLIENT,
        REASON_UNREGISTER_SERVER,
        REASON_BINDER_DIED,
        REASON_REGISTER_FAILED,
        REASON_UNKNOWN,
    }

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

    fun add(
        appUid: Int,
        appName: String,
        uuid: UUID,
        callback: C,
        transport: Int,
        tag: String?,
    ): ContextApp<C> =
        synchronized(appsLock) {
            val app = ContextApp(appUid, appName, uuid, callback, transport, tag)
            apps.add(app)
            recordRegisterApp(app)
            return app
        }

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

    fun getAllApps(): List<ContextApp<C>> =
        synchronized(appsLock) {
            return Collections.unmodifiableList(apps)
        }

    fun addConnection(id: Int, connId: Int, transport: Int, device: BluetoothDevice) =
        synchronized(connectionsLock) {
            getById(id)?.let { _ -> connections.add(Connection(connId, device, transport, id)) }
        }

    fun removeConnection(id: Int, connId: Int) =
        synchronized(connectionsLock) {
            connections.removeIf { conn -> conn.appId == id && conn.connId == connId }
        }

    fun removeConnectionsByAppId(appId: Int) =
        synchronized(connectionsLock) { connections.removeIf { conn -> conn.appId == appId } }

    fun getById(id: Int) = findBy("ID=$id") { it.id == id }

    fun getByCallbackId(callbackId: C) =
        findBy("callbackId=$callbackId") { it.callback.asBinder() == callbackId.asBinder() }

    fun getByUuid(uuid: UUID) = findBy("UUID=$uuid") { it.uuid == uuid }

    private fun findBy(criteria: String, predicate: (ContextApp<C>) -> Boolean): ContextApp<C>? =
        synchronized(appsLock) {
            val app = apps.find(predicate)
            if (app == null) {
                Log.e(TAG, "Context not found for $criteria")
            }
            return app
        }

    fun getConnectedDevices(): Set<BluetoothDevice> =
        synchronized(connectionsLock) {
            return connections.map { it.device }.toSet()
        }

    fun getByConnId(connId: Int): ContextApp<C>? {
        val connection: Connection
        synchronized(connectionsLock) {
            connection = connections.firstOrNull { it.connId == connId } ?: return null
        }
        return getById(connection.appId)
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
    fun getConnectionsByDevice(appId: Int, device: BluetoothDevice): List<Connection> =
        synchronized(connectionsLock) {
            return connections.filter { it.appId == appId && it.device == device }
        }

    fun deviceByConnId(connId: Int): BluetoothDevice? =
        synchronized(connectionsLock) {
            return connections.firstOrNull { it.connId == connId }?.device
        }

    fun getConnectionByApp(appId: Int): List<Connection> =
        synchronized(connectionsLock) {
            return connections.filter { it.appId == appId }
        }

    fun countByAppUid(appUid: Int): Int =
        synchronized(appsLock) {
            return apps.count { it.uid == appUid }
        }

    /** Returns connect device map with addr and appid */
    fun getConnectedMap(): Map<Int, BluetoothDevice> =
        synchronized(connectionsLock) {
            return connections.associate { it.appId to it.device }
        }

    fun dump(sb: StringBuilder) {
        synchronized(appsLock) {
            sb.appendLine("  Entries: ${getAllApps().size}")
            sb.appendLine("  Last apps:")
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
