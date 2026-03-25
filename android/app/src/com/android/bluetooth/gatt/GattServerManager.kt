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
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProtoEnums
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.GattOffloadSession
import android.bluetooth.IBluetoothGattCallback
import android.bluetooth.IBluetoothGattServerCallback
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.os.Binder
import android.util.Log
import com.android.bluetooth.ActionOnDeathRecipient
import com.android.bluetooth.Util.Transport
import com.android.bluetooth.Util.appNameOrUnknown
import com.android.bluetooth.Util.callbackToApp
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.gatt.GattUtil.Status
import com.android.bluetooth.gatt.GattUtil.translateHciCode
import com.android.bluetooth.util.getLastAttributionTag
import java.util.UUID

private const val TAG = GattUtil.TAG_PREFIX + "GattServerManager"

class GattServerManager(
    private val adapterService: AdapterService,
    private val gatt: GattService,
    private val metricsReporter: GattMetricsReporter,
) {
    val serverMap = ContextMap<IBluetoothGattServerCallback>()

    internal val handleMap = HandleMap()

    private val nativeInterface: GattNativeInterface
        get() = gatt.nativeInterface

    private val offloadLock = Any()

    fun cleanup() {
        Log.i(TAG, "cleanup()")
        serverMap.clear()
        handleMap.clear()
    }

    fun onServerRegisteredFromNative(status: Int, serverIf: Int, uuid: UUID) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServerRegistered(${Status(status)}, serverIf=$serverIf, uuid=$uuid)")
        val app = serverMap.getByUuid(uuid) ?: return
        app.id = serverIf
        val message = "Unregistering server for $app, callback=${app.callback}"
        val onDeathAction = { gatt.doOnGattThread { unregisterServer(app.callback) } }
        app.linkToDeath(ActionOnDeathRecipient(TAG, message, onDeathAction))
        callbackToApp { app.callback.onServerRegistered(status) }
    }

    fun onServiceAddedFromNative(status: Int, serverIf: Int, service: List<GattDbElement>) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServiceAdded(${Status(status)}, serverIf=$serverIf, service=$service)")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return
        }

        val svcEl = service[0]
        val srvcHandle = svcEl.attributeHandle

        var svc: BluetoothGattService? = null
        for (el in service) {
            when (el.type) {
                GattDbElement.TYPE_PRIMARY_SERVICE -> {
                    handleMap.addService(
                        serverIf,
                        el.attributeHandle,
                        el.uuid,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY,
                        0,
                        false,
                    )
                    svc =
                        BluetoothGattService(
                            svcEl.uuid,
                            svcEl.attributeHandle,
                            BluetoothGattService.SERVICE_TYPE_PRIMARY,
                        )
                }
                GattDbElement.TYPE_SECONDARY_SERVICE -> {
                    handleMap.addService(
                        serverIf,
                        el.attributeHandle,
                        el.uuid,
                        BluetoothGattService.SERVICE_TYPE_SECONDARY,
                        0,
                        false,
                    )
                    svc =
                        BluetoothGattService(
                            svcEl.uuid,
                            svcEl.attributeHandle,
                            BluetoothGattService.SERVICE_TYPE_SECONDARY,
                        )
                }
                GattDbElement.TYPE_CHARACTERISTIC -> {
                    handleMap.addCharacteristic(serverIf, el.attributeHandle, el.uuid, srvcHandle)
                    svc?.addCharacteristic(
                        BluetoothGattCharacteristic(
                            el.uuid,
                            el.attributeHandle,
                            el.properties,
                            el.permissions,
                        )
                    )
                }
                GattDbElement.TYPE_DESCRIPTOR -> {
                    handleMap.addDescriptor(serverIf, el.attributeHandle, el.uuid, srvcHandle)
                    svc?.characteristics?.let { chars ->
                        chars[chars.size - 1].addDescriptor(
                            BluetoothGattDescriptor(el.uuid, el.attributeHandle, el.permissions)
                        )
                    }
                }
            }
        }

        val app = serverMap.getById(serverIf) ?: return
        callbackToApp { app.callback.onServiceAdded(status, svc) }
    }

    fun onServiceDeletedFromNative(status: Int, serverIf: Int, srvcHandle: Int) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServiceDeleted(${Status(status)}, serverIf=$serverIf, handle=$srvcHandle)")
        handleMap.deleteService(serverIf, srvcHandle)
    }

    fun onClientConnectedFromNative(
        device: BluetoothDevice,
        transport: Int,
        connected: Boolean,
        connId: Int,
        serverIf: Int,
    ) {
        gatt.enforceGattThread()
        val header =
            "onClientConnected($device, ${Transport(transport)}, connected=$connected" +
                ", connId=$connId, serverIf=$serverIf):"
        val app = serverMap.getById(serverIf)
        if (app == null) {
            Log.w(TAG, "$header Received connection event for unregistered app")
            return
        }
        Log.d(TAG, header)

        // The native stack reports connection state changes for *all* bearer connections,
        // multiplexed across all applications. It's possible for an app to have more than one
        // bearer with a remote device. Since we don't expose per-bearer information to the
        // applications, we need to abstract this info away. We send "connected" when we grow from
        // zero to one connection, and disconnected when there are *no more* connections.

        // Are we connected currently?
        val previouslyConnected = !serverMap.getConnectionsByDevice(serverIf, device).isEmpty()

        // Add or remove a connection from our records
        if (connected) {
            serverMap.addConnection(serverIf, connId, transport, device)
        } else {
            Log.d(TAG, "Reset server congestion connId=$connId")
            onServerCongestionFromNative(connId, false)
            serverMap.removeConnection(serverIf, connId)
        }

        // Look at new set of connections to determine overall connection state to share outward
        val connectionState: Int
        val stateToReport: Boolean
        val currentlyConnected = !serverMap.getConnectionsByDevice(serverIf, device).isEmpty()
        if (!previouslyConnected && currentlyConnected) {
            Log.i(TAG, "$header Has its first bearer and is now connected")
            stateToReport = true
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_CONNECTED
        } else if (previouslyConnected && !currentlyConnected) {
            Log.i(TAG, "$header Has no more bearers and is disconnected")
            stateToReport = false
            connectionState = BluetoothProtoEnums.CONNECTION_STATE_DISCONNECTED
        } else {
            Log.d(
                TAG,
                "$header Event dropped, previouslyConnected=$previouslyConnected" +
                    ", currentlyConnected=$currentlyConnected",
            )
            return
        }

        var applicationUid = -1
        try {
            applicationUid =
                adapterService.packageManager.getPackageUid(
                    app.name,
                    PackageManager.PackageInfoFlags.of(0),
                )
        } catch (_: PackageManager.NameNotFoundException) {
            Log.d(TAG, "$header uid_not_found=${app.name}")
        }

        callbackToApp { app.callback.onServerConnectionState(0, stateToReport, device) }
        metricsReporter.logAppPackage(serverIf, device, applicationUid)
        metricsReporter.logConnectionStateChange(device, serverIf, connectionState, -1)
    }

    fun onServerPhyUpdateFromNative(connId: Int, txPhy: Int, rxPhy: Int, status: Int) {
        gatt.enforceGattThread()
        Log.d(
            TAG,
            "onServerPhyUpdate(connId=$connId, txPhy=$txPhy, rxPhy=$rxPhy, ${Status(status)})",
        )
        val device = serverMap.deviceByConnId(connId) ?: return
        val app = serverMap.getByConnId(connId) ?: return
        callbackToApp { app.callback.onPhyUpdate(device, txPhy, rxPhy, status) }
    }

    fun onServerPhyReadFromNative(
        serverIf: Int,
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        status: Int,
    ) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServerPhyRead($device, ${Status(status)})")
        val connections = serverMap.getConnectionsByDevice(serverIf, device)
        val connId = if (connections.isEmpty()) null else connections[0].connId
        if (connId == null) {
            Log.d(TAG, "onServerPhyRead(): No connection to $device")
            return
        }

        val app = serverMap.getByConnId(connId)
        if (app == null) {
            Log.w(TAG, "onServerPhyRead(): Received phy read for unregistered app")
            return
        }

        callbackToApp { app.callback.onPhyRead(device, txPhy, rxPhy, status) }
    }

    fun onServerConnUpdateFromNative(
        connId: Int,
        interval: Int,
        latency: Int,
        timeout: Int,
        status: Int,
    ) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServerConnUpdate(connId=$connId, ${Status(status)})")
        val device = serverMap.deviceByConnId(connId) ?: return
        val app = serverMap.getByConnId(connId) ?: return

        gatt.cachedPeripheralLatency[device] = latency // cache new peripheral latency

        callbackToApp {
            app.callback.onConnectionUpdated(device, interval, latency, timeout, status)
        }
    }

    fun onServerSubrateChangeFromNative(
        connId: Int,
        subrateFactor: Int,
        latency: Int,
        contNum: Int,
        timeout: Int,
        mode: Int,
        status: Int,
    ) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServerSubrateChange(connId=$connId, ${Status(status)})")
        val device: BluetoothDevice = serverMap.deviceByConnId(connId) ?: return
        val app: ContextApp<IBluetoothGattServerCallback> = serverMap.getByConnId(connId) ?: return

        val subrateMode: Int
        if (status == BluetoothStatusCodes.SUCCESS) {
            if (Flags.leSubrateManager()) {
                subrateMode = gatt.updateGattSubratingMode(mode)
            } else {
                subrateMode = gatt.verifyGattSubratingMode(device, subrateFactor, latency, contNum)
            }
        } else {
            subrateMode = BluetoothGatt.SUBRATE_MODE_NOT_UPDATED
        }
        callbackToApp {
            app.callback.onSubrateChange(device, subrateMode, translateHciCode(status))
        }
    }

    fun onServerReadCharacteristicFromNative(
        device: BluetoothDevice,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        isLong: Boolean,
    ) {
        gatt.enforceGattThread()
        Log.v(
            TAG,
            "onServerReadCharacteristic($device, connId=$connId, transId=$transId" +
                ", handle=$handle, offset=$offset)",
        )
        val entry = handleMap.getByHandle(handle) ?: return

        val requestId = handleMap.addRequestContext(entry.serverIf, connId, transId, handle)

        val app = serverMap.getById(entry.serverIf) ?: return
        callbackToApp {
            app.callback.onCharacteristicReadRequest(device, requestId, offset, isLong, handle)
        }
    }

    fun onServerReadDescriptorFromNative(
        device: BluetoothDevice,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        isLong: Boolean,
    ) {
        gatt.enforceGattThread()
        Log.v(
            TAG,
            "onServerReadDescriptor($device, connId=$connId, transId=$transId" +
                ", handle=$handle, offset=$offset)",
        )
        val entry = handleMap.getByHandle(handle) ?: return

        val requestId = handleMap.addRequestContext(entry.serverIf, connId, transId, handle)

        val app = serverMap.getById(entry.serverIf) ?: return
        callbackToApp {
            app.callback.onDescriptorReadRequest(device, requestId, offset, isLong, handle)
        }
    }

    fun onServerWriteCharacteristicFromNative(
        device: BluetoothDevice,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        length: Int,
        needRsp: Boolean,
        isPrep: Boolean,
        data: ByteArray?,
    ) {
        gatt.enforceGattThread()
        Log.v(
            TAG,
            "onServerWriteCharacteristic($device, connId=$connId, transId=$transId" +
                ", handle=$handle, offset=$offset, isPrep=$isPrep)",
        )
        val entry = handleMap.getByHandle(handle) ?: return

        val requestId = handleMap.addRequestContext(entry.serverIf, connId, transId, handle)

        val app = serverMap.getById(entry.serverIf) ?: return
        callbackToApp {
            app.callback.onCharacteristicWriteRequest(
                device,
                requestId,
                offset,
                length,
                isPrep,
                needRsp,
                handle,
                data,
            )
        }
    }

    fun onServerWriteDescriptorFromNative(
        device: BluetoothDevice,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        length: Int,
        needRsp: Boolean,
        isPrep: Boolean,
        data: ByteArray?,
    ) {
        gatt.enforceGattThread()
        Log.v(
            TAG,
            "onServerWriteDescriptor($device, connId=$connId, transId=$transId, handle=$handle" +
                ", offset=$offset, isPrep=$isPrep)",
        )
        val entry = handleMap.getByHandle(handle) ?: return

        val requestId = handleMap.addRequestContext(entry.serverIf, connId, transId, handle)

        val app = serverMap.getById(entry.serverIf) ?: return
        callbackToApp {
            app.callback.onDescriptorWriteRequest(
                device,
                requestId,
                offset,
                length,
                isPrep,
                needRsp,
                handle,
                data,
            )
        }
    }

    fun onExecuteWriteFromNative(
        device: BluetoothDevice,
        connId: Int,
        transId: Int,
        execWrite: Int,
    ) {
        gatt.enforceGattThread()
        val operation = if (execWrite == 1) "WRITE" else "CANCEL"
        Log.d(
            TAG,
            "onExecuteWrite($device, connId=$connId, transId=$transId, operation=$operation)",
        )
        val app = serverMap.getByConnId(connId) ?: return

        val handle = HandleMap.HANDLE_PREPARED_WRITE
        val requestId = handleMap.addRequestContext(app.id, connId, transId, handle)

        callbackToApp { app.callback.onExecuteWrite(device, requestId, execWrite == 1) }
    }

    fun onNotificationSentFromNative(connId: Int, status: Int) {
        gatt.enforceGattThread()
        Log.v(TAG, "onNotificationSent(connId=$connId, ${Status(status)})")
        val device = serverMap.deviceByConnId(connId) ?: return
        val app = serverMap.getByConnId(connId) ?: return

        if (!app.isCongested) {
            callbackToApp { app.callback.onNotificationSent(device, status) }
        } else {
            var queuedStatus = status
            if (queuedStatus == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                queuedStatus = BluetoothGatt.GATT_SUCCESS
            }
            app.queueCallback(ContextApp.CallbackInfo(device, queuedStatus))
        }
    }

    fun onServerCongestionFromNative(connId: Int, congested: Boolean) {
        gatt.enforceGattThread()
        Log.d(TAG, "onServerCongestion(connId=$connId, congested=$congested)")
        val app = serverMap.getByConnId(connId) ?: return
        app.isCongested = congested
        while (!app.isCongested) {
            val callbackInfo = app.popQueuedCallback() ?: return
            callbackToApp {
                app.callback.onNotificationSent(callbackInfo.device, callbackInfo.status)
            }
        }
    }

    fun onMtuChangedFromNative(connId: Int, mtu: Int) {
        gatt.enforceGattThread()
        Log.d(TAG, "onMtuChanged(connId=$connId, mtu=$mtu)")
        val device = serverMap.deviceByConnId(connId) ?: return
        val app = serverMap.getByConnId(connId) ?: return

        callbackToApp { app.callback.onMtuChanged(device, mtu) }
    }

    fun onServerCharacteristicsUnoffloadedFromNative(connId: Int, sessionId: Int, status: Int) {
        gatt.enforceGattThread()
        Log.d(
            TAG,
            "onServerCharacteristicsUnoffloaded(connId=$connId, sessionId=$sessionId" +
                ", status=${Status(status)})",
        )
        val device = serverMap.deviceByConnId(connId) ?: return
        val app = serverMap.getByConnId(connId) ?: return
        callbackToApp { app.callback.onCharacteristicsUnoffloaded(device, sessionId, status) }
    }

    fun registerServer(
        callback: IBluetoothGattServerCallback,
        eattSupport: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        gatt.enforceGattThread()
        var name = source.packageName
        val tag = source.getLastAttributionTag()
        val myPackage = AttributionSource.myAttributionSource().packageName
        if (myPackage == name && tag != null) {
            /* For servers created by Bluetooth stack, use just tag as name */
            name = tag
        } else if (tag != null) {
            name = "$name[$tag]"
        }

        val uuid = UUID.randomUUID()
        Log.d(TAG, "registerServer(): UUID=$uuid, name=$name, ${Transport(transport)}")
        val uid = if (Flags.gattThread()) source.uid else Binder.getCallingUid()
        val appName = adapterService.appNameOrUnknown(uid)
        serverMap.add(uid, appName, uuid, callback, transport, tag)
        nativeInterface.gattServerRegisterApp(uuid, eattSupport)
    }

    fun unregisterServer(callback: IBluetoothGattServerCallback) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "unregisterServer($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        Log.d(TAG, "unregisterServer(): serverIf=$serverIf")
        deleteServices(serverIf)

        serverMap.remove(serverIf, ContextMap.RemoveReason.REASON_UNREGISTER_SERVER)
        nativeInterface.gattServerUnregisterApp(serverIf)
    }

    fun serverConnect(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        addressType: Int,
        isDirect: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "serverConnect($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        Log.d(TAG, "serverConnect(): $device, ${Transport(transport)}")

        metricsReporter.logServerForegroundInfo(source.uid, isDirect)
        nativeInterface.gattServerConnect(serverIf, device, addressType, isDirect, transport)
    }

    fun serverDisconnect(callback: IBluetoothGattServerCallback, device: BluetoothDevice) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "serverDisconnect($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        val connections = serverMap.getConnectionsByDevice(serverIf, device)

        // If we don't have any known connection IDs, we could have a pending connection. We can
        // use connId => 0 to cancel all pending connections with the given device. Otherwise,
        // disconnect all bearers
        if (connections.isEmpty()) {
            Log.d(TAG, "serverDisconnect(): Cancel pending connections for $device")
            nativeInterface.gattServerDisconnect(serverIf, device, 0)
        } else {
            for (connection in connections) {
                val id = connection.connId
                Log.d(TAG, "serverDisconnect(): $device, connId=$id")
                nativeInterface.gattServerDisconnect(serverIf, device, id)
            }
        }
    }

    fun serverSetPreferredPhy(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        phyOptions: Int,
    ) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "serverSetPreferredPhy($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        val connections = serverMap.getConnectionsByDevice(serverIf, device)
        if (connections.isEmpty()) {
            Log.d(TAG, "serverSetPreferredPhy(): No connection to $device")
            return
        }

        Log.d(TAG, "serverSetPreferredPhy(): $device, connections=$connections")
        nativeInterface.gattServerSetPreferredPhy(serverIf, device, txPhy, rxPhy, phyOptions)
    }

    fun serverReadPhy(callback: IBluetoothGattServerCallback, device: BluetoothDevice) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "serverReadPhy($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        val connections = serverMap.getConnectionsByDevice(serverIf, device)
        if (connections.isEmpty()) {
            Log.d(TAG, "serverReadPhy($callback): No connection to $device")
            return
        }

        Log.d(TAG, "serverReadPhy($callback): $device, connections=$connections")
        nativeInterface.gattServerReadPhy(serverIf, device)
    }

    fun addService(callback: IBluetoothGattServerCallback, service: BluetoothGattService) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "addService($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        Log.d(TAG, "addService(): uuid=${service.uuid}")

        val db = mutableListOf<GattDbElement>()
        if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
            db.add(GattDbElement.createPrimaryService(service.uuid))
        } else {
            db.add(GattDbElement.createSecondaryService(service.uuid))
        }

        for (includedService in service.includedServices) {
            val inclSrvcHandle = includedService.instanceId

            if (handleMap.checkServiceExists(includedService.uuid, inclSrvcHandle)) {
                db.add(GattDbElement.createIncludedService(inclSrvcHandle))
            } else {
                Log.e(TAG, "Included service with UUID ${includedService.uuid} not found!")
            }
        }

        for (characteristic in service.characteristics) {
            val permissionEncodingKeySize = (characteristic.keySize - 7) shl 12
            var permission = permissionEncodingKeySize + characteristic.permissions
            db.add(
                GattDbElement.createCharacteristic(
                    characteristic.uuid,
                    characteristic.properties,
                    permission,
                )
            )

            for (descriptor in characteristic.descriptors) {
                permission = permissionEncodingKeySize + descriptor.permissions
                db.add(GattDbElement.createDescriptor(descriptor.uuid, permission))
            }
        }

        nativeInterface.gattServerAddService(serverIf, db)
    }

    fun removeService(callback: IBluetoothGattServerCallback, handle: Int) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "removeService($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        Log.d(TAG, "removeService($callback, handle=$handle)")
        nativeInterface.gattServerDeleteService(serverIf, handle)
    }

    fun clearServices(callback: IBluetoothGattServerCallback) {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "clearServices($callback): App not registered")
            return
        }
        val serverIf = serverApp.id
        Log.d(TAG, "clearServices($callback)")
        deleteServices(serverIf)
    }

    fun sendResponse(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        gatt.enforceGattThread()
        Log.v(TAG, "sendResponse($device, requestId=$requestId, ${Status(status)})")

        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "sendResponse($callback): App not registered")
            return
        }
        val serverIf = serverApp.id

        var handle = 0
        var connId = 0
        var transId = -1

        var requestContext = handleMap.getRequestContext(serverIf, requestId)
        if (requestContext != null) {
            connId = requestContext.connId
            transId = requestContext.transactionId
            handle = requestContext.handle
        }

        if (requestContext == null) {
            Log.w(TAG, "sendResponse($callback): No record of request we're responding to")
            return
        }

        nativeInterface.gattServerSendResponse(
            serverIf,
            connId,
            transId,
            status,
            handle,
            offset,
            value,
            0,
        )

        handleMap.deleteRequestContext(serverIf, requestId)
    }

    fun sendNotification(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        handle: Int,
        confirm: Boolean,
        value: ByteArray,
    ): Int {
        gatt.enforceGattThread()
        val serverApp = serverMap.getByCallbackId(callback)
        if (serverApp == null) {
            Log.w(TAG, "sendNotification($callback): App not registered")
            return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED
        }
        val serverIf = serverApp.id
        val transportPreference = serverApp.transport

        Log.v(TAG, "sendNotification($device, handle=$handle, transport=$transportPreference)")

        // The specifications do not insist that we must use the same bearer that wrote to the CCCD
        // to request notifications or indications. We only need to send to the same client.
        // We pick the first connection that matches the transport preference of the server, or the
        // oldest connection when transport is AUTO
        var connId: Int? = null
        val connections = serverMap.getConnectionsByDevice(serverIf, device)

        // The list is sorted by oldest first. Grab the oldest bearer that matches our transport
        // preference. If the transport is AUTO then use the oldest bearer available
        for (connection in connections) {
            if (
                transportPreference == BluetoothDevice.TRANSPORT_AUTO ||
                    transportPreference == connection.transport
            ) {
                connId = connection.connId
                break
            }
        }

        // If there was no transport that matches the preference, use the oldest bearer
        if (connId == null && !connections.isEmpty()) {
            connId = connections[0].connId
        }

        if (connId == null || connId == 0) {
            Log.d(TAG, "sendNotification(): no connection to $device")
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED
        }

        Log.d(TAG, "sendNotification(): $device, handle=$handle, connId=$connId, confirm=$confirm")

        if (confirm) {
            nativeInterface.gattServerSendIndication(serverIf, handle, connId, value)
        } else {
            nativeInterface.gattServerSendNotification(serverIf, handle, connId, value)
        }

        return BluetoothStatusCodes.SUCCESS
    }

    fun offloadClientCharacteristics(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        service: BluetoothGattService,
        characteristics: List<BluetoothGattCharacteristic>,
        endpointId: Long,
        hubId: Long,
        uid: Int,
        attributionTag: String?,
    ): GattOffloadSession.InnerParcel {
        gatt.enforceGattThread()
        check(adapterService.isGattClientOffloadSupported()) { "GATT client offload unsupported" }
        val clientApp = gatt.clientMap.getByCallbackId(callback)
        requireNotNull(clientApp) { "$callback: App not registered" }
        val clientIf = clientApp.id
        Log.v(
            TAG,
            "offloadClientCharacteristics(clientIf=$clientIf, $device" +
                ", service uuid=${service.uuid}, endpointId=$endpointId, hubId=$hubId)",
        )

        val connId = gatt.getFirstConnectionIdForDevice(clientIf, device)
        requireNotNull(connId) { "No connection to $device" }

        synchronized(offloadLock) {
            return nativeInterface.gattClientOffloadCharacteristics(
                connId,
                getGattDatabaseForOffload(service, characteristics),
                endpointId,
                hubId,
                uid,
                attributionTag ?: "",
            )
        }
    }

    fun unoffloadClientCharacteristics(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        sessionId: Int,
    ) {
        gatt.enforceGattThread()
        check(adapterService.isGattClientOffloadSupported()) { "GATT client offload unsupported" }
        val clientApp = gatt.clientMap.getByCallbackId(callback)
        requireNotNull(clientApp) { "$callback: App not registered" }
        val clientIf = clientApp.id
        Log.v(
            TAG,
            "unoffloadClientCharacteristics(clientIf=$clientIf, $device, sessionId=$sessionId)",
        )

        val connId = gatt.getFirstConnectionIdForDevice(clientIf, device)
        requireNotNull(connId) { "No connection to $device" }
        synchronized(offloadLock) {
            nativeInterface.gattClientUnoffloadCharacteristics(connId, sessionId)
        }
    }

    fun offloadServerCharacteristics(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        service: BluetoothGattService,
        characteristics: List<BluetoothGattCharacteristic>,
        endpointId: Long,
        hubId: Long,
        uid: Int,
        attributionTag: String?,
    ): GattOffloadSession.InnerParcel {
        gatt.enforceGattThread()
        check(adapterService.isGattServerOffloadSupported()) { "GATT server offload unsupported" }
        val serverApp = serverMap.getByCallbackId(callback)
        requireNotNull(serverApp) { "$callback: App not registered" }
        val serverIf = serverApp.id
        Log.v(
            TAG,
            "offloadServerCharacteristics(serverIf=$serverIf, $device" +
                ", service uuid=${service.uuid}, endpointId=$endpointId, hubId=$hubId",
        )

        val connections = serverMap.getConnectionsByDevice(serverIf, device)
        val connId = (if (connections.isEmpty()) null else connections[0].connId)
        requireNotNull(connId) { "No connection to $device" }

        // Lock the thread until onServerCharacteristicsOffloaded comes back.
        synchronized(offloadLock) {
            return nativeInterface.gattServerOffloadCharacteristics(
                connId,
                getGattDatabaseForOffload(service, characteristics),
                endpointId,
                hubId,
                uid,
                attributionTag ?: "",
            )
        }
    }

    fun unoffloadServerCharacteristics(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        sessionId: Int,
    ) {
        gatt.enforceGattThread()
        check(adapterService.isGattServerOffloadSupported()) { "GATT server offload unsupported" }
        val serverApp = serverMap.getByCallbackId(callback)
        requireNotNull(serverApp) { "$callback: App not registered" }
        val serverIf = serverApp.id
        Log.v(
            TAG,
            "unoffloadServerCharacteristics(serverIf=$serverIf, $device, sessionId=$sessionId)",
        )

        val connections = serverMap.getConnectionsByDevice(serverIf, device)
        val connId = (if (connections.isEmpty()) null else connections.get(0).connId)
        requireNotNull(connId) { "No connection to $device" }
        synchronized(offloadLock) {
            nativeInterface.gattServerUnoffloadCharacteristics(connId, sessionId)
        }
    }

    private fun deleteServices(serverIf: Int) {
        Log.d(TAG, "deleteServices(serverIf=$serverIf)")

        /*
         * Figure out which handles to delete.
         * The handles are copied into a new list to avoid race conditions.
         */
        val handleList = mutableListOf<Int>()
        for (entry in handleMap.entries) {
            if (entry.type != HandleMap.Type.SERVICE || entry.serverIf != serverIf) {
                continue
            }
            handleList.add(entry.handle)
        }

        /* Now actually delete the services.... */
        for (handle in handleList) {
            nativeInterface.gattServerDeleteService(serverIf, handle)
        }
    }

    private fun getGattDatabaseForOffload(
        service: BluetoothGattService,
        characteristics: List<BluetoothGattCharacteristic>,
    ) =
        buildList {
                add(GattDbElement.createPrimaryService(service.uuid))
                addAll(
                    characteristics.map { characteristic ->
                        val permissionEncodingKeySize = (characteristic.keySize - 7) shl 12
                        val permissions = permissionEncodingKeySize + characteristic.permissions
                        GattDbElement.createCharacteristic(
                            characteristic.uuid,
                            characteristic.properties,
                            permissions,
                            characteristic.instanceId,
                        )
                    }
                )
            }
            .also { db ->
                val string = buildString {
                    append("getGattDatabaseForOffload{")
                    append("database size=${db.size}")
                    db.forEach { element ->
                        append(", type=${element.type}")
                        append(", attributeHandle=${element.attributeHandle}")
                        append(", uuid=${element.uuid}")
                        append(", properties=${element.properties}")
                        append(", permissions=${element.permissions}")
                    }
                    append("}")
                }
                Log.d(TAG, string)
            }
}
