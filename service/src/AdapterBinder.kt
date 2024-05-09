/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.bluetooth

import android.bluetooth.IBluetooth
import android.bluetooth.IBluetoothCallback
import android.content.AttributionSource
import android.os.IBinder
import android.os.RemoteException
import com.android.server.bluetooth.BluetoothManagerService.timeToLog

class AdapterBinder(rawBinder: IBinder) {
    private val TAG = "AdapterBinder"
    val adapterBinder: IBluetooth = IBluetooth.Stub.asInterface(rawBinder)
    private val createdAt = System.currentTimeMillis()

    override fun toString(): String =
        "[Binder=" + adapterBinder.hashCode() + ", createdAt=" + timeToLog(createdAt) + "]"

    @Throws(RemoteException::class)
    fun disable(source: AttributionSource) {
        adapterBinder.disable(source)
    }

    @Throws(RemoteException::class)
    fun enable(quietMode: Boolean, source: AttributionSource) {
        adapterBinder.enable(quietMode, source)
    }

    @Throws(RemoteException::class)
    fun getAddress(source: AttributionSource): String? {
        return adapterBinder.getAddress(source)
    }

    @Throws(RemoteException::class)
    fun getName(source: AttributionSource): String? {
        return adapterBinder.getName(source)
    }

    @Throws(RemoteException::class)
    fun stopBle(source: AttributionSource) {
        adapterBinder.stopBle(source)
    }

    @Throws(RemoteException::class)
    fun startBrEdr(source: AttributionSource) {
        adapterBinder.startBrEdr(source)
    }

    @Throws(RemoteException::class)
    fun registerCallback(callback: IBluetoothCallback, source: AttributionSource) {
        adapterBinder.registerCallback(callback, source)
    }

    @Throws(RemoteException::class)
    fun unregisterCallback(callback: IBluetoothCallback, source: AttributionSource) {
        adapterBinder.unregisterCallback(callback, source)
    }

    @Throws(RemoteException::class)
    fun setForegroundUserId(userId: Int, source: AttributionSource) {
        adapterBinder.setForegroundUserId(userId, source)
    }

    @Throws(RemoteException::class)
    fun unregAllGattClient(source: AttributionSource) {
        adapterBinder.unregAllGattClient(source)
    }

    fun isMediaProfileConnected(source: AttributionSource): Boolean {
        try {
            return adapterBinder.isMediaProfileConnected(source)
        } catch (ex: RemoteException) {
            Log.e(TAG, "Error when calling isMediaProfileConnected", ex)
        }
        return false
    }

    @Throws(RemoteException::class)
    fun killBluetoothProcess() {
        adapterBinder.killBluetoothProcess()
    }
}
