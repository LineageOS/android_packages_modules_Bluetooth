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

package com.android.bluetooth

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NETWORK_SETTINGS
import android.annotation.PermissionMethod
import android.annotation.PermissionName
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.content.Context
import android.os.IBinder
import android.permission.PermissionManager
import android.permission.PermissionManager.PERMISSION_GRANTED
import android.permission.PermissionManager.PERMISSION_HARD_DENIED
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.ProfileService

private const val TAG = Util.BT_PREFIX + "Util"

object Util {
    const val BT_PREFIX = "Bluetooth"

    @JvmStatic
    fun ProfileService?.checkProfileAvailable(tag: String): Boolean {
        if (this == null) {
            Log.w(TAG, "$tag - Not present")
            return false
        }
        if (!this.isAvailable) {
            Log.w(TAG, "$tag - Not available")
            return false
        }
        return true
    }

    @JvmStatic
    fun AdapterService.appNameOrUnknown(uid: Int) =
        packageManager.getNameForUid(uid) ?: "Unknown App (UID: $uid)"

    @JvmStatic
    fun addressTypeToString(addressType: Int) =
        when (addressType) {
            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "Public "
            BluetoothDevice.ADDRESS_TYPE_RANDOM -> "Random "
            else -> "Unknown"
        }

    @JvmStatic
    fun deviceTypeToString(deviceType: Int) =
        when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_UNKNOWN -> " ???? "
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "BR/EDR"
            BluetoothDevice.DEVICE_TYPE_LE -> "  LE  "
            BluetoothDevice.DEVICE_TYPE_DUAL -> " DUAL "
            else -> "Invalid device type: $deviceType"
        }

    @JvmInline
    internal value class Transport(val value: Int) {
        override fun toString() = transportToString(value)
    }

    @JvmStatic
    fun transportToString(transport: Int) =
        when (transport) {
            BluetoothDevice.TRANSPORT_AUTO -> "AUTO"
            BluetoothDevice.TRANSPORT_BREDR -> "BR/EDR"
            BluetoothDevice.TRANSPORT_LE -> "LE"
            else -> "Unknown transport ($transport)"
        }

    /** Returns `true` if the caller holds [NETWORK_SETTINGS] */
    @JvmStatic
    @SuppressWarnings("IncorrectRequiresPermissionPropagation") // This method checks the permission
    fun checkCallerHasNetworkSettingsPermission(context: Context) =
        context.checkCallingOrSelfPermission(NETWORK_SETTINGS) == PERMISSION_GRANTED

    /**
     * Returns `true` if the [BLUETOOTH_ADVERTISE] permission is granted for the calling app.
     * Returns `false` if the result is a soft denial. Throws [SecurityException] if the result is a
     * hard denial.
     *
     * Should be used in situations where data will be delivered and hence the app op should be
     * noted.
     */
    @JvmStatic
    @RequiresPermission(BLUETOOTH_ADVERTISE)
    fun enforceAdvertisePermissionForDataDelivery(
        context: Context,
        source: AttributionSource,
        message: String,
    ) = enforcePermissionForDataDelivery(context, BLUETOOTH_ADVERTISE, source, message)

    /**
     * Returns `true` if the [BLUETOOTH_CONNECT] permission is granted for the calling app. Returns
     * `false` if the result is a soft denial. Throws [SecurityException] if the result is a hard
     * denial.
     *
     * Should be used in situations where data will be delivered and hence the app op should be
     * noted.
     */
    @JvmOverloads
    @JvmStatic
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun enforceConnectPermissionForDataDelivery(
        context: Context,
        source: AttributionSource,
        tagOrMessage: String,
        method: String? = null,
    ): Boolean {
        val message = if (method == null) tagOrMessage else "$tagOrMessage.$method()"
        return enforcePermissionForDataDelivery(context, BLUETOOTH_CONNECT, source, message)
    }

    /**
     * Returns `true` if the [BLUETOOTH_SCAN] permission is granted for the calling app. Returns
     * `false` if the result is a soft denial. Throws [SecurityException] if the result is a hard
     * denial.
     *
     * Should be used in situations where data will be delivered and hence the app op should be
     * noted.
     */
    @JvmStatic
    @RequiresPermission(BLUETOOTH_SCAN)
    fun enforceScanPermissionForDataDelivery(
        context: Context,
        source: AttributionSource,
        tag: String,
        method: String,
    ) = enforcePermissionForDataDelivery(context, BLUETOOTH_SCAN, source, "$tag.$method()")

    /**
     * Returns `true` if the [BLUETOOTH_CONNECT] permission is granted for the calling app. Returns
     * `false` if the result is a soft denial. Throws [SecurityException] if the result is a hard
     * denial.
     *
     * Should be used in situations where the app op should not be noted.
     */
    @JvmStatic
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun enforceConnectPermissionForPreflight(context: Context, source: AttributionSource) =
        enforcePermissionForPreflight(context, BLUETOOTH_CONNECT, source)

    @PermissionMethod
    fun enforcePermissionForDataDelivery(
        context: Context,
        @PermissionName permission: String,
        source: AttributionSource,
        message: String?,
    ): Boolean {
        if (Utils.isInstrumentationTestMode()) {
            return true
        }
        val currentAttribution =
            AttributionSource.Builder(context.attributionSource).setNext(source).build()
        val permissionManager =
            context.getSystemService(PermissionManager::class.java) ?: return false
        val result =
            permissionManager.checkPermissionForDataDeliveryFromDataSource(
                permission,
                currentAttribution,
                message,
            )
        if (result == PERMISSION_GRANTED) {
            return true
        }

        val msg = "Need $permission permission for $currentAttribution: $message"
        if (result == PERMISSION_HARD_DENIED) {
            throw SecurityException(msg)
        } else {
            Log.w(TAG, msg)
            return false
        }
    }

    @PermissionMethod
    private fun enforcePermissionForPreflight(
        context: Context,
        @PermissionName permission: String,
        source: AttributionSource,
    ): Boolean {
        val permissionManager =
            context.getSystemService(PermissionManager::class.java) ?: return false
        val result = permissionManager.checkPermissionForPreflight(permission, source)
        if (result == PERMISSION_GRANTED) {
            return true
        }

        val msg = "Need $permission permission"
        if (result == PERMISSION_HARD_DENIED) {
            throw SecurityException(msg)
        } else {
            Log.w(TAG, msg)
            return false
        }
    }
}

class ActionOnDeathRecipient(
    private val tag: String,
    private val message: String,
    private val action: () -> Unit,
) : IBinder.DeathRecipient {
    constructor(
        tag: String,
        message: String,
        actionRunnable: Runnable,
    ) : this(tag, message, { actionRunnable.run() })

    override fun binderDied() {
        Log.d(tag, "binderDied(): $message")
        action()
    }
}
