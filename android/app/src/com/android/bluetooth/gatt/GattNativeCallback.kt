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
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback
import java.util.UUID

private const val TAG = "GattNativeCallback"

class GattNativeCallback(
    adapterService: AdapterService,
    private val gatt: GattService,
    private val gattServer: GattServerManager,
) : NativeCallback(adapterService) {

    fun onClientRegistered(status: Int, clientIf: Int, uuidMsb: Long, uuidLsb: Long) {
        doOnGattThread { onClientRegisteredFromNative(status, clientIf, UUID(uuidMsb, uuidLsb)) }
    }

    fun onConnected(clientIf: Int, connId: Int, transport: Int, status: Int, address: String) {
        doOnGattThread {
            onConnectedFromNative(clientIf, connId, transport, status, getDevice(address))
        }
    }

    fun onDisconnected(clientIf: Int, connId: Int, transport: Int, status: Int, address: String) {
        doOnGattThread {
            onDisconnectedFromNative(clientIf, connId, transport, status, getDevice(address))
        }
    }

    fun onClientPhyUpdate(connId: Int, txPhy: Int, rxPhy: Int, status: Int) {
        doOnGattThread { onClientPhyUpdateFromNative(connId, txPhy, rxPhy, status) }
    }

    fun onClientPhyRead(clientIf: Int, address: String, txPhy: Int, rxPhy: Int, status: Int) {
        doOnGattThread {
            onClientPhyReadFromNative(clientIf, getDevice(address), txPhy, rxPhy, status)
        }
    }

    fun onClientConnUpdate(connId: Int, interval: Int, latency: Int, timeout: Int, status: Int) {
        doOnGattThread { onClientConnUpdateFromNative(connId, interval, latency, timeout, status) }
    }

    fun onServiceChanged(connId: Int) {
        doOnGattThread { onServiceChangedFromNative(connId) }
    }

    fun onClientSubrateChange(
        connId: Int,
        subrateFactor: Int,
        latency: Int,
        contNum: Int,
        timeout: Int,
        subrateMode: Int,
        status: Int,
    ) {
        doOnGattThread {
            onClientSubrateChangeFromNative(
                connId,
                subrateFactor,
                latency,
                contNum,
                timeout,
                subrateMode,
                status,
            )
        }
    }

    fun onServerPhyUpdate(connId: Int, txPhy: Int, rxPhy: Int, status: Int) {
        serverDoOnGattThread { onServerPhyUpdateFromNative(connId, txPhy, rxPhy, status) }
    }

    fun onServerPhyRead(serverIf: Int, address: String, txPhy: Int, rxPhy: Int, status: Int) {
        serverDoOnGattThread {
            onServerPhyReadFromNative(serverIf, getDevice(address), txPhy, rxPhy, status)
        }
    }

    fun onServerConnUpdate(connId: Int, interval: Int, latency: Int, timeout: Int, status: Int) {
        serverDoOnGattThread {
            onServerConnUpdateFromNative(connId, interval, latency, timeout, status)
        }
    }

    fun onServerSubrateChange(
        connId: Int,
        subrateFactor: Int,
        latency: Int,
        contNum: Int,
        timeout: Int,
        subrateMode: Int,
        status: Int,
    ) {
        serverDoOnGattThread {
            onServerSubrateChangeFromNative(
                connId,
                subrateFactor,
                latency,
                contNum,
                timeout,
                subrateMode,
                status,
            )
        }
    }

    fun getSampleGattDbElement() = GattDbElement()

    fun onGetGattDb(connId: Int, db: List<GattDbElement>) {
        doOnGattThread { onGetGattDbFromNative(connId, db) }
    }

    fun onRegisterForNotifications(connId: Int, status: Int, registered: Int, handle: Int) {
        doOnGattThread { onRegisterForNotificationsFromNative(connId, status, registered, handle) }
    }

    fun onNotify(connId: Int, address: String, handle: Int, isNotify: Boolean, data: ByteArray) {
        doOnGattThread { onNotifyFromNative(connId, getDevice(address), handle, isNotify, data) }
    }

    fun onReadCharacteristic(connId: Int, status: Int, handle: Int, data: ByteArray) {
        doOnGattThread { onReadCharacteristicFromNative(connId, status, handle, data) }
    }

    fun onWriteCharacteristic(connId: Int, status: Int, handle: Int, data: ByteArray) {
        doOnGattThread { onWriteCharacteristicFromNative(connId, status, handle, data) }
    }

    fun onExecuteCompleted(connId: Int, status: Int) {
        doOnGattThread { onExecuteCompletedFromNative(connId, status) }
    }

    fun onReadDescriptor(connId: Int, status: Int, handle: Int, data: ByteArray) {
        doOnGattThread { onReadDescriptorFromNative(connId, status, handle, data) }
    }

    fun onWriteDescriptor(connId: Int, status: Int, handle: Int, data: ByteArray) {
        doOnGattThread { onWriteDescriptorFromNative(connId, status, handle, data) }
    }

    fun onReadRemoteRssi(clientIf: Int, address: String, rssi: Int, status: Int) {
        doOnGattThread { onReadRemoteRssiFromNative(clientIf, getDevice(address), rssi, status) }
    }

    fun onConfigureMTU(connId: Int, status: Int, mtu: Int) {
        doOnGattThread { onConfigureMTUFromNative(connId, status, mtu) }
    }

    fun onClientCongestion(connId: Int, congested: Boolean) {
        doOnGattThread { onClientCongestionFromNative(connId, congested) }
    }

    fun onClientCharacteristicsUnoffloaded(connId: Int, sessionId: Int, status: Int) {
        doOnGattThread { onClientCharacteristicsUnoffloadedFromNative(connId, sessionId, status) }
    }

    /* Server callbacks */

    fun onServerRegistered(status: Int, serverIf: Int, uuidMsb: Long, uuidLsb: Long) {
        serverDoOnGattThread {
            onServerRegisteredFromNative(status, serverIf, UUID(uuidMsb, uuidLsb))
        }
    }

    fun onServiceAdded(status: Int, serverIf: Int, serviceAdded: List<GattDbElement>) {
        serverDoOnGattThread { onServiceAddedFromNative(status, serverIf, serviceAdded) }
    }

    fun onServiceDeleted(status: Int, serverIf: Int, srvcHandle: Int) {
        serverDoOnGattThread { onServiceDeletedFromNative(status, serverIf, srvcHandle) }
    }

    fun onClientConnected(
        address: String,
        transport: Int,
        connected: Boolean,
        connId: Int,
        serverIf: Int,
    ) {
        serverDoOnGattThread {
            onClientConnectedFromNative(getDevice(address), transport, connected, connId, serverIf)
        }
    }

    fun onServerReadCharacteristic(
        address: String,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        isLong: Boolean,
    ) {
        serverDoOnGattThread {
            onServerReadCharacteristicFromNative(
                getDevice(address),
                connId,
                transId,
                handle,
                offset,
                isLong,
            )
        }
    }

    fun onServerReadDescriptor(
        address: String,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        isLong: Boolean,
    ) {
        serverDoOnGattThread {
            onServerReadDescriptorFromNative(
                getDevice(address),
                connId,
                transId,
                handle,
                offset,
                isLong,
            )
        }
    }

    fun onServerWriteCharacteristic(
        address: String,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        length: Int,
        needRsp: Boolean,
        isPrep: Boolean,
        data: ByteArray,
    ) {
        serverDoOnGattThread {
            onServerWriteCharacteristicFromNative(
                getDevice(address),
                connId,
                transId,
                handle,
                offset,
                length,
                needRsp,
                isPrep,
                data,
            )
        }
    }

    fun onServerWriteDescriptor(
        address: String,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        length: Int,
        needRsp: Boolean,
        isPrep: Boolean,
        data: ByteArray,
    ) {
        serverDoOnGattThread {
            onServerWriteDescriptorFromNative(
                getDevice(address),
                connId,
                transId,
                handle,
                offset,
                length,
                needRsp,
                isPrep,
                data,
            )
        }
    }

    fun onExecuteWrite(address: String, connId: Int, transId: Int, execWrite: Int) {
        serverDoOnGattThread {
            onExecuteWriteFromNative(getDevice(address), connId, transId, execWrite)
        }
    }

    fun onResponseSendCompleted(status: Int, attrHandle: Int) {
        Log.d(TAG, "onResponseSendCompleted(status=$status, handle=$attrHandle)")
    }

    fun onNotificationSent(connId: Int, status: Int) {
        serverDoOnGattThread { onNotificationSentFromNative(connId, status) }
    }

    fun onServerCongestion(connId: Int, congested: Boolean) {
        serverDoOnGattThread { onServerCongestionFromNative(connId, congested) }
    }

    fun onMtuChanged(connId: Int, mtu: Int) {
        serverDoOnGattThread { onMtuChangedFromNative(connId, mtu) }
    }

    fun onServerCharacteristicsUnoffloaded(connId: Int, sessionId: Int, status: Int) {
        serverDoOnGattThread {
            onServerCharacteristicsUnoffloadedFromNative(connId, sessionId, status)
        }
    }

    private fun doOnGattThread(block: GattService.() -> Unit) = gatt.doOnGattThread { gatt.block() }

    private fun serverDoOnGattThread(block: GattServerManager.() -> Unit) = gatt.doOnGattThread {
        gattServer.block()
    }
}
