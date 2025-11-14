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

import android.bluetooth.IAdapter
import android.bluetooth.IBluetoothCallback
import android.os.IBinder
import android.os.RemoteException

class AdapterBinder(rawBinder: IBinder) {
    private val TAG = "AdapterBinder"
    val adapterBinder: IAdapter = IAdapter.Stub.asInterface(rawBinder)
    var adapterServiceBinder: IBinder? = null
    private val createdAt = System.currentTimeMillis()

    override fun toString(): String =
        "[Binder=" +
            adapterBinder.hashCode() +
            ", createdAt=" +
            Log.timeToStringWithZone(createdAt) +
            "]"

    @Throws(RemoteException::class)
    fun onToBleOn() {
        adapterBinder.onToBleOn()
    }

    @Throws(RemoteException::class)
    fun offToBleOn(quietMode: Boolean, hciInstanceName: String) {
        adapterBinder.offToBleOn(quietMode, hciInstanceName)
    }

    @Throws(RemoteException::class)
    fun bleOnToOff() {
        adapterBinder.bleOnToOff()
    }

    @Throws(RemoteException::class)
    fun bleOnToOn() {
        adapterBinder.bleOnToOn()
    }

    @Throws(RemoteException::class)
    fun registerCallback(callback: IBluetoothCallback) {
        adapterBinder.registerCallback(callback)
    }

    @Throws(RemoteException::class)
    fun unregisterCallback(callback: IBluetoothCallback) {
        adapterBinder.unregisterCallback(callback)
    }

    @Throws(RemoteException::class)
    fun setForegroundUserId(userId: Int) {
        adapterBinder.setForegroundUserId(userId)
    }

    @Throws(RemoteException::class)
    fun unregAllGattClient() {
        adapterBinder.unregAllGattClient()
    }

    @Throws(RemoteException::class)
    fun factoryReset() {
        adapterBinder.onewayFactoryReset()
    }

    fun isMediaProfileConnected(): Boolean {
        try {
            return adapterBinder.isMediaProfileConnected()
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
