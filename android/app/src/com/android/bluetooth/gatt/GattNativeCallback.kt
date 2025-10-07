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
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AdapterService
import java.util.UUID

class GattNativeCallback(val adapterService: AdapterService, val service: GattService) {

    fun onClientRegistered(status: Int, clientIf: Int, uuidLsb: Long, uuidMsb: Long) {
        service.onClientRegisteredFromNative(status, clientIf, UUID(uuidMsb, uuidLsb))
    }

    fun onConnected(clientIf: Int, connId: Int, transport: Int, status: Int, address: String) {
        service.onConnectedFromNative(clientIf, connId, transport, status, getDevice(address))
    }

    fun onDisconnected(clientIf: Int, connId: Int, transport: Int, status: Int, address: String) {
        service.onDisconnectedFromNative(clientIf, connId, transport, status, getDevice(address))
    }

    fun onClientPhyUpdate(connId: Int, txPhy: Int, rxPhy: Int, status: Int) {
        service.onClientPhyUpdateFromNative(connId, txPhy, rxPhy, status)
    }

    fun onClientPhyRead(clientIf: Int, address: String, txPhy: Int, rxPhy: Int, status: Int) {
        service.onClientPhyReadFromNative(clientIf, getDevice(address), txPhy, rxPhy, status)
    }

    fun onClientConnUpdate(connId: Int, interval: Int, latency: Int, timeout: Int, status: Int) {
        service.onClientConnUpdateFromNative(connId, interval, latency, timeout, status)
    }

    fun onServiceChanged(connId: Int) {
        service.onServiceChangedFromNative(connId)
    }

    fun onClientSubrateChange(
        connId: Int,
        subrateFactor: Int,
        latency: Int,
        contNum: Int,
        timeout: Int,
        status: Int,
    ) {
        service.onClientSubrateChangeFromNative(
            connId,
            subrateFactor,
            latency,
            contNum,
            timeout,
            status,
        )
    }

    fun onServerPhyUpdate(connId: Int, txPhy: Int, rxPhy: Int, status: Int) {
        service.onServerPhyUpdateFromNative(connId, txPhy, rxPhy, status)
    }

    fun onServerPhyRead(serverIf: Int, address: String, txPhy: Int, rxPhy: Int, status: Int) {
        service.onServerPhyReadFromNative(serverIf, getDevice(address), txPhy, rxPhy, status)
    }

    fun onServerConnUpdate(connId: Int, interval: Int, latency: Int, timeout: Int, status: Int) {
        service.onServerConnUpdateFromNative(connId, interval, latency, timeout, status)
    }

    fun onServerSubrateChange(
        connId: Int,
        subrateFactor: Int,
        latency: Int,
        contNum: Int,
        timeout: Int,
        status: Int,
    ) {
        service.onServerSubrateChangeFromNative(
            connId,
            subrateFactor,
            latency,
            contNum,
            timeout,
            status,
        )
    }

    fun getSampleGattDbElement(): GattDbElement = service.getSampleGattDbElement()

    fun onGetGattDb(connId: Int, db: List<GattDbElement>) {
        service.onGetGattDbFromNative(connId, db)
    }

    fun onRegisterForNotifications(connId: Int, status: Int, registered: Int, handle: Int) {
        service.onRegisterForNotificationsFromNative(connId, status, registered, handle)
    }

    fun onNotify(connId: Int, address: String, handle: Int, isNotify: Boolean, data: ByteArray) {
        service.onNotifyFromNative(connId, getDevice(address), handle, isNotify, data)
    }

    fun onReadCharacteristic(connId: Int, status: Int, handle: Int, data: ByteArray) {
        service.onReadCharacteristicFromNative(connId, status, handle, data)
    }

    fun onWriteCharacteristic(connId: Int, status: Int, handle: Int, data: ByteArray) {
        service.onWriteCharacteristicFromNative(connId, status, handle, data)
    }

    fun onExecuteCompleted(connId: Int, status: Int) {
        service.onExecuteCompletedFromNative(connId, status)
    }

    fun onReadDescriptor(connId: Int, status: Int, handle: Int, data: ByteArray) {
        service.onReadDescriptorFromNative(connId, status, handle, data)
    }

    fun onWriteDescriptor(connId: Int, status: Int, handle: Int, data: ByteArray) {
        service.onWriteDescriptorFromNative(connId, status, handle, data)
    }

    fun onReadRemoteRssi(clientIf: Int, address: String, rssi: Int, status: Int) {
        service.onReadRemoteRssiFromNative(clientIf, getDevice(address), rssi, status)
    }

    fun onConfigureMTU(connId: Int, status: Int, mtu: Int) {
        service.onConfigureMTUFromNative(connId, status, mtu)
    }

    fun onClientCongestion(connId: Int, congested: Boolean) {
        service.onClientCongestionFromNative(connId, congested)
    }

    fun onClientCharacteristicsUnoffloaded(connId: Int, sessionId: Int, status: Int) {
        service.onClientCharacteristicsUnoffloadedFromNative(connId, sessionId, status)
    }

    /* Server callbacks */

    fun onServerRegistered(status: Int, serverIf: Int, uuidLsb: Long, uuidMsb: Long) {
        service.onServerRegisteredFromNative(status, serverIf, UUID(uuidMsb, uuidLsb))
    }

    fun onServiceAdded(status: Int, serverIf: Int, serviceAdded: List<GattDbElement>) {
        service.onServiceAddedFromNative(status, serverIf, serviceAdded)
    }

    fun onServiceStopped(status: Int, serverIf: Int, srvcHandle: Int) {
        service.onServiceStoppedFromNative(status, serverIf, srvcHandle)
    }

    fun onServiceDeleted(status: Int, serverIf: Int, srvcHandle: Int) {
        service.onServiceDeletedFromNative(status, serverIf, srvcHandle)
    }

    fun onClientConnected(
        address: String,
        transport: Int,
        connected: Boolean,
        connId: Int,
        serverIf: Int,
    ) {
        service.onClientConnectedFromNative(
            getDevice(address),
            transport,
            connected,
            connId,
            serverIf,
        )
    }

    fun onServerReadCharacteristic(
        address: String,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        isLong: Boolean,
    ) {
        service.onServerReadCharacteristicFromNative(
            getDevice(address),
            connId,
            transId,
            handle,
            offset,
            isLong,
        )
    }

    fun onServerReadDescriptor(
        address: String,
        connId: Int,
        transId: Int,
        handle: Int,
        offset: Int,
        isLong: Boolean,
    ) {
        service.onServerReadDescriptorFromNative(
            getDevice(address),
            connId,
            transId,
            handle,
            offset,
            isLong,
        )
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
        service.onServerWriteCharacteristicFromNative(
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
        service.onServerWriteDescriptorFromNative(
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

    fun onExecuteWrite(address: String, connId: Int, transId: Int, execWrite: Int) {
        service.onExecuteWriteFromNative(getDevice(address), connId, transId, execWrite)
    }

    fun onResponseSendCompleted(status: Int, attrHandle: Int) {
        service.onResponseSendCompletedFromNative(status, attrHandle)
    }

    fun onNotificationSent(connId: Int, status: Int) {
        service.onNotificationSentFromNative(connId, status)
    }

    fun onServerCongestion(connId: Int, congested: Boolean) {
        service.onServerCongestionFromNative(connId, congested)
    }

    fun onMtuChanged(connId: Int, mtu: Int) {
        service.onMtuChangedFromNative(connId, mtu)
    }

    fun onServerCharacteristicsUnoffloaded(connId: Int, sessionId: Int, status: Int) {
        service.onServerCharacteristicsUnoffloadedFromNative(connId, sessionId, status)
    }

    private fun getDevice(address: String): BluetoothDevice {
        val addressBytes = Utils.getBytesFromAddress(address)
        return adapterService.getDeviceFromByte(addressBytes)
    }
}
