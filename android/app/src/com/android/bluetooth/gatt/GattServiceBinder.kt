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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND
import android.bluetooth.GattOffloadSession
import android.bluetooth.IBluetoothGatt
import android.bluetooth.IBluetoothGattCallback
import android.bluetooth.IBluetoothGattServerCallback
import android.content.AttributionSource
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.android.bluetooth.Util
import com.android.bluetooth.Util.callerIsSystemOrActiveOrManagedUser
import com.android.bluetooth.Util.checkCallerHasPrivilegedPermission
import com.android.bluetooth.Util.checkCallerTargetSdk
import com.android.bluetooth.Util.checkProfileAvailable
import com.android.bluetooth.Utils
import com.android.bluetooth.gatt.GattUtil.isHidCharUuid
import com.android.bluetooth.profile.ProfileService
import com.android.bluetooth.util.getLastAttributionTag

private const val TAG = GattUtil.TAG_PREFIX + "GattServiceBinder"

class GattServiceBinder(private var gattService: GattService?) :
    IBluetoothGatt.Stub(), ProfileService.IProfileServiceBinder {

    private val gattUnavailableException = IllegalArgumentException("GattService is null")

    override fun cleanup() {
        gattService = null
    }

    private fun gatt(): GattService? {
        val gatt = gattService ?: return null
        if (!gatt.checkProfileAvailable(TAG)) return null
        return gatt
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun gattEnforceConnect(
        source: AttributionSource,
        allowPccBypass: Boolean = false,
    ): GattService? {
        val gatt = gatt() ?: return null
        if (
            !Util.enforceConnectPermissionForDataDelivery(
                gatt,
                source,
                TAG,
                method = null,
                allowPccBypass,
            )
        ) {
            return null
        }
        return gatt
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
    private fun gattEnforceConnectAndPrivileged(source: AttributionSource): GattService? {
        val gatt = gattEnforceConnect(source) ?: return null
        gatt.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        return gatt
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun onGattThreadEnforceConnect(
        source: AttributionSource,
        block: GattService.() -> Unit,
    ) = gattEnforceConnect(source)?.let { it.runOrDoOnGattThread(it, block) }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun serverOnGattThreadEnforceConnect(
        source: AttributionSource,
        block: GattServerManager.() -> Unit,
    ) = gattEnforceConnect(source)?.let { it.runOrDoOnGattThread(it.serverManager, block) }

    override fun getDevicesMatchingConnectionStates(
        states: IntArray,
        source: AttributionSource,
    ): List<BluetoothDevice> {
        val gatt = gattEnforceConnect(source, allowPccBypass = true) ?: return emptyList()
        return gatt.runOrFetchOnGattThread(gatt, emptyList()) {
            getDevicesMatchingConnectionStates(states)
        }
    }

    override fun registerClient(
        uuid: ParcelUuid,
        callback: IBluetoothGattCallback,
        eattSupport: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) {
            registerClient(uuid.uuid, callback, eattSupport, transport, source)
        }
    }

    override fun unregisterClient(callback: IBluetoothGattCallback, source: AttributionSource) {
        onGattThreadEnforceConnect(source) {
            unregisterClient(callback, source, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)
        }
    }

    override fun clientConnect(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        addressType: Int,
        isDirect: Boolean,
        transport: Int,
        opportunistic: Boolean,
        autoMtuEnabled: Boolean,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) {
            clientConnect(
                callback,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                autoMtuEnabled,
                source,
            )
        }
    }

    override fun clientDisconnect(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { clientDisconnect(callback, device, source) }
    }

    override fun clientSetPreferredPhy(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        phyOptions: Int,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) {
            clientSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions)
        }
    }

    override fun clientReadPhy(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { clientReadPhy(callback, device) }
    }

    override fun refreshDevice(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { refreshDevice(callback, device) }
    }

    override fun discoverServices(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { discoverServices(callback, device) }
    }

    override fun discoverServiceByUuid(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        uuid: ParcelUuid,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { discoverServiceByUuid(callback, device, uuid.uuid) }
    }

    override fun readCharacteristic(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        authReq: Int,
        source: AttributionSource,
    ) {
        val gatt = gattEnforceConnect(source) ?: return
        try {
            onGattThreadAndEnforcePrivilegedOnBinderIfNeeded(
                gatt,
                callback,
                device,
                handle,
                Unit, // Nothing to return
            ) {
                readCharacteristic(callback, device, handle, authReq)
            }
        } catch (ex: SecurityException) {
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (gatt.checkCallerTargetSdk(source, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "readCharacteristic(): Permission check failed!")
        }
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
        val gatt = gattEnforceConnect(source) ?: return
        try {
            if (isHidCharUuid(uuid.uuid)) {
                gatt.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
            }
        } catch (ex: SecurityException) {
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (gatt.checkCallerTargetSdk(source, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "readUsingCharacteristicUuid() - permission check failed!")
            return
        }
        gatt.runOrDoOnGattThread(gatt) {
            readUsingCharacteristicUuid(
                callback,
                device,
                uuid.uuid,
                startHandle,
                endHandle,
                authReq,
            )
        }
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
        val gatt = gattEnforceConnect(source) ?: return ERROR_PROFILE_SERVICE_NOT_BOUND
        return onGattThreadAndEnforcePrivilegedOnBinderIfNeeded(
            gatt,
            callback,
            device,
            handle,
            BluetoothStatusCodes.ERROR_UNKNOWN,
        ) {
            writeCharacteristic(callback, device, handle, writeType, authReq, value)
        }
    }

    override fun readDescriptor(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        authReq: Int,
        source: AttributionSource,
    ) {
        val gatt = gattEnforceConnect(source) ?: return
        try {
            onGattThreadAndEnforcePrivilegedOnBinderIfNeeded(
                gatt,
                callback,
                device,
                handle,
                Unit, // Nothing to return
            ) {
                readDescriptor(callback, device, handle, authReq)
            }
        } catch (ex: SecurityException) {
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (gatt.checkCallerTargetSdk(source, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "readDescriptor(): Permission check failed!")
        }
    }

    override fun writeDescriptor(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        authReq: Int,
        value: ByteArray,
        source: AttributionSource,
    ): Int {
        val gatt = gattEnforceConnect(source) ?: return ERROR_PROFILE_SERVICE_NOT_BOUND
        return onGattThreadAndEnforcePrivilegedOnBinderIfNeeded(
            gatt,
            callback,
            device,
            handle,
            BluetoothStatusCodes.ERROR_UNKNOWN,
        ) {
            writeDescriptor(callback, device, handle, authReq, value)
        }
    }

    override fun beginReliableWrite(device: BluetoothDevice, source: AttributionSource) {
        onGattThreadEnforceConnect(source) { beginReliableWrite(device) }
    }

    override fun endReliableWrite(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        execute: Boolean,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { endReliableWrite(callback, device, execute) }
    }

    override fun registerForNotification(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        enable: Boolean,
        source: AttributionSource,
    ) {
        val gatt = gattEnforceConnect(source) ?: return
        try {
            onGattThreadAndEnforcePrivilegedOnBinderIfNeeded(
                gatt,
                callback,
                device,
                handle,
                Unit, // Nothing to return
            ) {
                registerForNotification(callback, device, handle, enable)
            }
        } catch (ex: SecurityException) {
            // Only throws on apps with target SDK T+ as this old API did not throw prior to T
            if (gatt.checkCallerTargetSdk(source, Build.VERSION_CODES.TIRAMISU)) {
                throw ex
            }
            Log.w(TAG, "registerForNotification(): Permission check failed!")
        }
    }

    override fun readRemoteRssi(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ): Boolean {
        val gatt = gattEnforceConnect(source) ?: return false
        return gatt.runOrFetchOnGattThread(gatt, false) { readRemoteRssi(callback, device) }
    }

    override fun configureMTU(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        mtu: Int,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) { configureMTU(callback, device, mtu) }
    }

    override fun connectionParameterUpdate(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        connectionPriority: Int,
        source: AttributionSource,
    ) {
        onGattThreadEnforceConnect(source) {
            connectionParameterUpdate(callback, device, connectionPriority)
        }
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
        onGattThreadEnforceConnect(source) {
            leConnectionUpdate(
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
    }

    override fun subrateModeRequest(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        subrateMode: Int,
        source: AttributionSource,
    ): Int {
        val gatt = gatt() ?: return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED
        if (!gatt.callerIsSystemOrActiveOrManagedUser(TAG, "subrateModeRequest")) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED
        }
        if (
            !Util.enforceConnectPermissionForDataDelivery(gatt, source, TAG, "subrateModeRequest")
        ) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
        Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
            gatt,
            gatt.companionDeviceManager,
            source,
            device,
        )
        if (
            subrateMode < BluetoothGatt.SUBRATE_MODE_OFF ||
                subrateMode > BluetoothGatt.SUBRATE_MODE_HIGH
        ) {
            throw IllegalArgumentException("Subrate Mode not within valid range")
        }

        return gatt.runOrFetchOnGattThread(gatt, BluetoothStatusCodes.ERROR_UNKNOWN) {
            subrateModeRequest(callback, device, subrateMode)
        }
    }

    override fun disconnectAll(source: AttributionSource) {
        onGattThreadEnforceConnect(source) { disconnectAll(source) }
    }

    override fun registerServer(
        callback: IBluetoothGattServerCallback,
        eattSupport: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) {
            registerServer(callback, eattSupport, transport, source)
        }
    }

    override fun unregisterServer(
        callback: IBluetoothGattServerCallback,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) { unregisterServer(callback) }
    }

    override fun serverConnect(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        addressType: Int,
        isDirect: Boolean,
        transport: Int,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) {
            serverConnect(callback, device, addressType, isDirect, transport, source)
        }
    }

    override fun serverDisconnect(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) { serverDisconnect(callback, device) }
    }

    override fun serverSetPreferredPhy(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        phyOptions: Int,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) {
            serverSetPreferredPhy(callback, device, txPhy, rxPhy, phyOptions)
        }
    }

    override fun serverReadPhy(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) { serverReadPhy(callback, device) }
    }

    override fun addService(
        callback: IBluetoothGattServerCallback,
        svc: BluetoothGattService,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) { addService(callback, svc) }
    }

    override fun removeService(
        callback: IBluetoothGattServerCallback,
        handle: Int,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) { removeService(callback, handle) }
    }

    override fun clearServices(callback: IBluetoothGattServerCallback, source: AttributionSource) {
        serverOnGattThreadEnforceConnect(source) { clearServices(callback) }
    }

    override fun sendResponse(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
        source: AttributionSource,
    ) {
        serverOnGattThreadEnforceConnect(source) {
            sendResponse(callback, device, requestId, status, offset, value)
        }
    }

    override fun sendNotification(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        handle: Int,
        confirm: Boolean,
        value: ByteArray,
        source: AttributionSource,
    ): Int {
        val gatt = gattEnforceConnect(source) ?: return ERROR_PROFILE_SERVICE_NOT_BOUND
        return gatt.runOrFetchOnGattThread(gatt.serverManager, BluetoothStatusCodes.ERROR_UNKNOWN) {
            sendNotification(callback, device, handle, confirm, value)
        }
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
        val gatt = gattEnforceConnectAndPrivileged(source) ?: throw gattUnavailableException
        val result: GattOffloadSession.InnerParcel? =
            gatt.runOrFetchOnGattThread(gatt.serverManager, null) {
                offloadClientCharacteristics(
                    callback,
                    device,
                    gattService,
                    characteristics,
                    endpointId,
                    hubId,
                    source.uid,
                    source.getLastAttributionTag(),
                )
            }
        val message = "Failed to complete offloadClientCharacteristics synchronously on GATT thread"
        return result ?: throw IllegalStateException(message)
    }

    override fun unoffloadClientCharacteristics(
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        sessionId: Int,
        source: AttributionSource,
    ) {
        val gatt = gattEnforceConnectAndPrivileged(source) ?: throw gattUnavailableException
        gatt.runOrDoOnGattThread(gatt.serverManager) {
            unoffloadClientCharacteristics(callback, device, sessionId)
        }
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
        val gatt = gattEnforceConnectAndPrivileged(source) ?: throw gattUnavailableException
        val result: GattOffloadSession.InnerParcel? =
            gatt.runOrFetchOnGattThread(gatt.serverManager, null) {
                offloadServerCharacteristics(
                    callback,
                    device,
                    gattService,
                    characteristics,
                    endpointId,
                    hubId,
                    source.uid,
                    source.getLastAttributionTag(),
                )
            }
        val message = "Failed to complete offloadServerCharacteristics synchronously on GATT thread"
        return result ?: throw IllegalStateException(message)
    }

    override fun unoffloadServerCharacteristics(
        callback: IBluetoothGattServerCallback,
        device: BluetoothDevice,
        sessionId: Int,
        source: AttributionSource,
    ) {
        val gatt = gattEnforceConnectAndPrivileged(source) ?: throw gattUnavailableException
        gatt.runOrDoOnGattThread(gatt.serverManager) {
            unoffloadServerCharacteristics(callback, device, sessionId)
        }
    }

    /**
     * Runs a GATT action in a single thread hop, returning the result of [block] or [defaultValue].
     * If a specific [handle] requires [BLUETOOTH_PRIVILEGED], the enforcement is done on the Binder
     * thread to ensure the delivery of the exception to the requesting app.
     *
     * Permission enforcement for `BLUETOOTH_PRIVILEGED` is complex-conditional. Callers like
     * `readCharacteristic` and `registerForNotification` only require to throw an exception on SDK
     * T+ for specific handles that are stored in [GattService.restrictedHandles] via the code flow
     * found in [GattService.isRestrictedSrvcUuid].
     */
    @Suppress("IncorrectRequiresPermissionPropagation")
    private fun <T> onGattThreadAndEnforcePrivilegedOnBinderIfNeeded(
        gatt: GattService,
        callback: IBluetoothGattCallback,
        device: BluetoothDevice,
        handle: Int,
        defaultValue: T,
        block: GattService.() -> T,
    ): T {
        if (Util.isInstrumentationTestMode) {
            return gatt.block()
        }

        val hasPrivilegedPermission = gatt.checkCallerHasPrivilegedPermission()
        val header = "onGattThreadAndEnforcePrivilegedOnBinderIfNeeded($callback, $device):"

        val (result, isRestricted) =
            gatt.runOrFetchOnGattThread(gatt, defaultValue to false) {
                val clientApp = gatt.clientMap.getByCallbackId(callback)
                if (clientApp == null) {
                    Log.w(TAG, "$header App not registered")
                    return@runOrFetchOnGattThread defaultValue to false
                }

                val connId = gatt.getFirstConnectionIdForDevice(clientApp.id, device)
                if (connId == null) {
                    Log.e(TAG, "$header No connection")
                    return@runOrFetchOnGattThread defaultValue to false
                }

                val isRestricted = gatt.restrictedHandles[connId]?.contains(handle) == true
                if (isRestricted && !hasPrivilegedPermission) {
                    // Restricted handle requires BLUETOOTH_PRIVILEGED but caller lacks it
                    // Return `defaultValue` and exception will be thrown during enforcement
                    defaultValue to true
                } else {
                    gatt.block() to isRestricted
                }
            }

        if (isRestricted) {
            gatt.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null)
        }

        return result
    }

    // Internal clients that use Gatt via framework APIs are already on gatt thread
    // TODO(b/377424060) Remove when "use internal APIs instead of framework APIs" is fixed
    private fun <T> GattService.runOrDoOnGattThread(target: T, block: T.() -> Unit) {
        if (isOnGattThread) {
            target.block()
        } else {
            doOnGattThread { target.block() }
        }
    }

    // Internal clients that use Gatt via framework APIs are already on gatt thread
    // TODO(b/377424060) Remove when "use internal APIs instead of framework APIs" is fixed
    private fun <T, R> GattService.runOrFetchOnGattThread(
        target: T,
        defaultValue: R,
        block: T.() -> R,
    ): R {
        return if (isOnGattThread) {
            target.block()
        } else {
            fetchOnGattThread<R>({ target.block() }, defaultValue)
        }
    }
}
