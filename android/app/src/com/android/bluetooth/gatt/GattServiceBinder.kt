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

import android.Manifest
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.GattOffloadSession
import android.bluetooth.IBluetoothGatt
import android.bluetooth.IBluetoothGattCallback
import android.bluetooth.IBluetoothGattServerCallback
import android.content.AttributionSource
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.android.bluetooth.Utils
import com.android.bluetooth.gatt.GattUtil.isHidCharUuid
import com.android.bluetooth.profile.ProfileService

private const val TAG = GattUtil.TAG_PREFIX + "GattServiceBinder"

class GattServiceBinder(private var service: GattService?) :
    IBluetoothGatt.Stub(), ProfileService.IProfileServiceBinder {

    override fun cleanup() {
        service = null
    }

    private fun getGattService(): GattService? {
        val service = service
        if (!Utils.checkServiceAvailable(service, TAG)) {
            return null
        }
        return service
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getServiceAndEnforceConnect(source: AttributionSource): GattService? {
        val service = getGattService()
        if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null
        }
        return service
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getServerManagerAndEnforceConnect(source: AttributionSource): GattServerManager? {
        return getServiceAndEnforceConnect(source)?.serverManager
    }

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_PRIVILEGED]
    )
    private fun getServerManagerAndEnforceConnectAndPrivileged(
        source: AttributionSource
    ): GattServerManager? {
        val service = getServiceAndEnforceConnect(source) ?: return null
        service.enforceCallingOrSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED, null)
        return service.serverManager
    }

    override fun getDevicesMatchingConnectionStates(
        states: IntArray,
        source: AttributionSource,
    ): List<BluetoothDevice> {
        val service = getServiceAndEnforceConnect(source) ?: return emptyList()
        return service.getDevicesMatchingConnectionStates(states)
    }

    override fun registerClient(
        uuid: ParcelUuid,
        callback: IBluetoothGattCallback,
        eattSupport: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)
            ?.registerClient(uuid.uuid, callback, eattSupport, transport, source)
    }

    override fun unregisterClient(callback: IBluetoothGattCallback, source: AttributionSource) {
        getServiceAndEnforceConnect(source)
            ?.unregisterClient(callback, source, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)
    }

    override fun clientConnect(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        addressType: Int,
        isDirect: Boolean,
        transport: Int,
        opportunistic: Boolean,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)
            ?.clientConnect(
                callback,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                source,
            )
    }

    override fun clientDisconnect(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.clientDisconnect(callback, device, source)
    }

    override fun clientSetPreferredPhy(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        phyOptions: Int,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)
            ?.clientSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions)
    }

    override fun clientReadPhy(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.clientReadPhy(callback, device)
    }

    override fun refreshDevice(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.refreshDevice(callback, device)
    }

    override fun discoverServices(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.discoverServices(callback, device)
    }

    override fun discoverServiceByUuid(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        uuid: ParcelUuid,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.discoverServiceByUuid(callback, device, uuid.uuid)
    }

    override fun readCharacteristic(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        authReq: Int,
        source: AttributionSource,
    ) {
        val service = getServiceAndEnforceConnect(source) ?: return
        try {
            enforcePrivilegedPermissionIfNeededForHandle(service, callback, device, handle)
        } catch (ex: SecurityException) {
            val callingPackage = source.packageName
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (Utils.checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "readCharacteristic() - permission check failed!")
            return
        }
        service.readCharacteristic(callback, device, handle, authReq)
    }

    override fun readUsingCharacteristicUuid(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        uuid: ParcelUuid,
        startHandle: Int,
        endHandle: Int,
        authReq: Int,
        source: AttributionSource,
    ) {
        val service = getServiceAndEnforceConnect(source) ?: return
        try {
            if (isHidCharUuid(uuid.uuid)) {
                service.enforceCallingOrSelfPermission(
                    Manifest.permission.BLUETOOTH_PRIVILEGED,
                    null,
                )
            }
        } catch (ex: SecurityException) {
            val callingPackage = source.packageName
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (Utils.checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "readUsingCharacteristicUuid() - permission check failed!")
            return
        }
        service.readUsingCharacteristicUuid(
            callback,
            device,
            uuid.uuid,
            startHandle,
            endHandle,
            authReq,
        )
    }

    override fun writeCharacteristic(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        writeType: Int,
        authReq: Int,
        value: ByteArray,
        source: AttributionSource,
    ): Int {
        val service =
            getServiceAndEnforceConnect(source)
                ?: return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND
        enforcePrivilegedPermissionIfNeededForHandle(service, callback, device, handle)
        return service.writeCharacteristic(callback, device, handle, writeType, authReq, value)
    }

    override fun readDescriptor(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        authReq: Int,
        source: AttributionSource,
    ) {
        val service = getServiceAndEnforceConnect(source) ?: return
        try {
            enforcePrivilegedPermissionIfNeededForHandle(service, callback, device, handle)
        } catch (ex: SecurityException) {
            val callingPackage = source.packageName
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (Utils.checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "readDescriptor() - permission check failed!")
            return
        }
        service.readDescriptor(callback, device, handle, authReq)
    }

    override fun writeDescriptor(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        authReq: Int,
        value: ByteArray,
        source: AttributionSource,
    ): Int {
        val service =
            getServiceAndEnforceConnect(source)
                ?: return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND
        enforcePrivilegedPermissionIfNeededForHandle(service, callback, device, handle)
        return service.writeDescriptor(callback, device, handle, authReq, value)
    }

    override fun beginReliableWrite(device: BluetoothDevice, source: AttributionSource) {
        getServiceAndEnforceConnect(source)?.beginReliableWrite(device)
    }

    override fun endReliableWrite(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        execute: Boolean,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.endReliableWrite(callback, device, execute)
    }

    override fun registerForNotification(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        enable: Boolean,
        source: AttributionSource,
    ) {
        val service = getServiceAndEnforceConnect(source) ?: return
        try {
            enforcePrivilegedPermissionIfNeededForHandle(service, callback, device, handle)
        } catch (ex: SecurityException) {
            val callingPackage = source.packageName
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (Utils.checkCallerTargetSdk(service, callingPackage, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "registerForNotification() - permission check failed!")
            return
        }

        service.registerForNotification(callback, device, handle, enable)
    }

    override fun readRemoteRssi(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.readRemoteRssi(callback, device)
    }

    override fun configureMTU(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        mtu: Int,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)?.configureMTU(callback, device, mtu)
    }

    override fun connectionParameterUpdate(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        connectionPriority: Int,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)
            ?.connectionParameterUpdate(callback, device, connectionPriority)
    }

    override fun leConnectionUpdate(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        minConnectionInterval: Int,
        maxConnectionInterval: Int,
        peripheralLatency: Int,
        supervisionTimeout: Int,
        minConnectionEventLen: Int,
        maxConnectionEventLen: Int,
        source: AttributionSource,
    ) {
        getServiceAndEnforceConnect(source)
            ?.leConnectionUpdate(
                callback,
                device,
                minConnectionInterval,
                maxConnectionInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
            )
    }

    override fun subrateModeRequest(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        subrateMode: Int,
        source: AttributionSource,
    ): Int {
        val service = getGattService() ?: return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED
        if (!Utils.callerIsSystemOrActiveOrManagedUser(service, TAG, "subrateModeRequest")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED
        }
        if (
            !Utils.checkConnectPermissionForDataDelivery(service, source, TAG, "subrateModeRequest")
        ) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
        Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
            service,
            service.companionDeviceManager,
            source,
            device,
        )
        if (
            subrateMode < BluetoothGatt.SUBRATE_MODE_OFF ||
                subrateMode > BluetoothGatt.SUBRATE_MODE_HIGH
        ) {
            throw IllegalArgumentException("Subrate Mode not within valid range")
        }
        return service.subrateModeRequest(callback, device, subrateMode)
    }

    override fun disconnectAll(source: AttributionSource) {
        getServiceAndEnforceConnect(source)?.disconnectAll(source)
    }

    override fun registerServer(
        uuid: ParcelUuid,
        callback: IBluetoothGattServerCallback,
        eattSupport: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)
            ?.registerServer(uuid.uuid, callback, eattSupport, transport, source)
    }

    override fun unregisterServer(
        callback: IBluetoothGattServerCallback,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)?.unregisterServer(callback)
    }

    override fun serverConnect(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        addressType: Int,
        isDirect: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)
            ?.serverConnect(callback, device, addressType, isDirect, transport, source)
    }

    override fun serverDisconnect(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)?.serverDisconnect(callback, device)
    }

    override fun serverSetPreferredPhy(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        phyOptions: Int,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)
            ?.serverSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions)
    }

    override fun serverReadPhy(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)?.serverReadPhy(callback, device)
    }

    override fun addService(
        callback: IBluetoothGattServerCallback,
        svc: BluetoothGattService,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)?.addService(callback, svc)
    }

    override fun removeService(
        callback: IBluetoothGattServerCallback,
        handle: Int,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)?.removeService(callback, handle)
    }

    override fun clearServices(callback: IBluetoothGattServerCallback, source: AttributionSource) {
        getServerManagerAndEnforceConnect(source)?.clearServices(callback)
    }

    override fun sendResponse(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray,
        source: AttributionSource,
    ) {
        getServerManagerAndEnforceConnect(source)
            ?.sendResponse(callback, device, requestId, status, offset, value)
    }

    override fun sendNotification(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        handle: Int,
        confirm: Boolean,
        value: ByteArray,
        source: AttributionSource,
    ): Int {
        val serverManager =
            getServerManagerAndEnforceConnect(source)
                ?: return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND
        return serverManager.sendNotification(callback, device, handle, confirm, value)
    }

    override fun offloadClientCharacteristics(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        gattService: BluetoothGattService,
        characteristics: MutableList<BluetoothGattCharacteristic>,
        endpointId: Long,
        hubId: Long,
        source: AttributionSource,
    ): GattOffloadSession.InnerParcel {
        val serverManager =
            getServerManagerAndEnforceConnectAndPrivileged(source)
                ?: throw IllegalArgumentException("Service is null")
        return serverManager.offloadClientCharacteristics(
            callback,
            device,
            gattService,
            characteristics,
            endpointId,
            hubId,
        )
    }

    override fun unoffloadClientCharacteristics(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        sessionId: Int,
        source: AttributionSource,
    ) {
        val serverManager =
            getServerManagerAndEnforceConnectAndPrivileged(source)
                ?: throw IllegalArgumentException("Service is null")
        serverManager.unoffloadClientCharacteristics(callback, device, sessionId)
    }

    override fun offloadServerCharacteristics(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        gattService: BluetoothGattService,
        characteristics: MutableList<BluetoothGattCharacteristic>,
        endpointId: Long,
        hubId: Long,
        source: AttributionSource,
    ): GattOffloadSession.InnerParcel {
        val serverManager =
            getServerManagerAndEnforceConnectAndPrivileged(source)
                ?: throw IllegalArgumentException("Service is null")
        return serverManager.offloadServerCharacteristics(
            callback,
            device,
            gattService,
            characteristics,
            endpointId,
            hubId,
        )
    }

    override fun unoffloadServerCharacteristics(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        sessionId: Int,
        source: AttributionSource,
    ) {
        val serverManager =
            getServerManagerAndEnforceConnectAndPrivileged(source)
                ?: throw IllegalArgumentException("Service is null")
        serverManager.unoffloadServerCharacteristics(callback, device, sessionId)
    }

    @SuppressWarnings("IncorrectRequiresPermissionPropagation")
    private fun enforcePrivilegedPermissionIfNeededForHandle(
        service: GattService,
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
    ) {
        if (Utils.isInstrumentationTestMode()) {
            return
        }

        val clientApp = service.clientMap.getByCallbackId(callback)
        if (clientApp == null) {
            Log.w(TAG, "($callback) - App not registered")
            return
        }
        val connId = service.getFirstConnectionIdForDevice(clientApp.id, device)
        if (connId == null) {
            Log.e(TAG, "($device) - No connection")
            return
        }

        if (!isHandleRestricted(service, connId, handle)) {
            return
        }
        service.enforceCallingOrSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED, null)
    }

    private fun isHandleRestricted(service: GattService, connId: Int, handle: Int) =
        service.mRestrictedHandles[connId]?.contains(handle) ?: false
}
