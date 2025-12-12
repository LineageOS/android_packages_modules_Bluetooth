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

package com.android.server.bluetooth

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.Manifest.permission.LOCAL_MAC_ADDRESS
import android.annotation.RequiresPermission
import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST
import android.bluetooth.IBluetoothManager
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_DISABLED
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FILTERED
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FULL
import android.content.AttributionSource
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcelable
import android.os.RemoteException
import android.sysprop.BluetoothProperties
import com.android.bluetooth.flags.Flags
import kotlin.text.Charsets.UTF_8

private const val TAG = "Messenger"

private fun getCallerIdentity(source: AttributionSource): String =
    if (!Flags.rejectEnableFromUnknownRequester()) {
        requireNotNull(source.packageName) { "Unknown package caller. Identify yourself" }
    } else {
        val pkg = requireNotNull(source.packageName) { "Unknown package caller. Identify yourself" }
        val tag = source.attributionTag

        // When called from android system, we must set an attribution tag to allow tracking.
        //      Ex: context.createAttributionContext(SERVICE_NAME)

        if ("android" == pkg) {
            "$pkg/" + requireNotNull(tag) { "System generic caller must set the Attribution tag" }
        } else {
            tag?.let { "$pkg/$it" } ?: pkg
        }
    }

internal class ServiceMessenger(
    looper: Looper,
    private val checker: PermissionChecker,
    private val api: BluetoothManagerServiceApi,
) : Handler(looper) {
    val messenger = Messenger(this)

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED, LOCAL_MAC_ADDRESS])
    override fun handleMessage(msg: Message) {
        Log.i(TAG, "handleMessage: $msg")
        val reply = Message.obtain()
        try {
            reply.obj = handleMessage(msg.sendingUid, msg.obj as Parcelable)
        } catch (e: RuntimeException) {
            reply.data = Bundle().apply { putSerializable("exception", e) }
        } finally {
            try {
                msg.replyTo?.send(reply)
            } catch (e: RemoteException) {
                Log.e(TAG, "handleMessage($msg): Failed to send reply=$reply", e)
            }
        }
    }

    @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED, LOCAL_MAC_ADDRESS])
    private fun handleMessage(sendingUid: Int, obj: Parcelable): Parcelable {
        return when (obj) {
            is SystemServiceMessage.RegisterAdapter -> {
                SystemServiceMessage.RegisterAdapter.Reply().apply {
                    value = api.registerAdapter(obj.binder)
                }
            }
            is SystemServiceMessage.UnregisterAdapter -> {
                api.unregisterAdapter(obj.binder)
                SystemServiceMessage.UnregisterAdapter.Reply()
            }
            is SystemServiceMessage.Enable -> {
                val source = obj.attributionSource
                val isQuiet = obj.isQuiet
                val bleToken = obj.bleToken

                val foregroundRequired = bleToken == null && isQuiet == false
                SystemServiceMessage.Enable.Reply().apply {
                    value =
                        try {
                            checker.enableAllowed(source, foregroundRequired)
                            val callerIdentity = getCallerIdentity(source)
                            if (bleToken != null) {
                                api.enableBle(callerIdentity, bleToken)
                            } else if (isQuiet) {
                                api.enableNoAutoConnect(callerIdentity)
                            } else {
                                api.enable(
                                    ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                                    callerIdentity,
                                )
                            }
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "$obj: FAILED", e)
                            false
                        }
                }
            }
            is SystemServiceMessage.Disable -> {
                val source = obj.attributionSource
                val persist = obj.persist
                val bleToken = obj.bleToken
                val foregroundRequired = bleToken == null
                SystemServiceMessage.Disable.Reply().apply {
                    value =
                        try {
                            checker.disableAllowed(source, foregroundRequired)
                            val callerIdentity = getCallerIdentity(source)
                            if (bleToken != null) {
                                api.disableBle(callerIdentity, bleToken)
                            } else {
                                api.disable(callerIdentity, persist)
                            }
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "$obj: FAILED", e)
                            false
                        }
                }
            }
            is SystemServiceMessage.FactoryReset -> {
                checker.enforcePrivileged(sendingUid)

                val source = obj.attributionSource
                SystemServiceMessage.FactoryReset.Reply().apply {
                    value =
                        try {
                            checker.factoryResetAllowed(source)
                            api.factoryReset()
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "$obj: FAILED", e)
                            false
                        }
                }
            }
            is SystemServiceMessage.GetAddress -> {
                val source = obj.attributionSource

                SystemServiceMessage.GetAddress.Reply().apply {
                    value =
                        try {
                            checker.getAddressAllowed(source)
                            api.getAddress()
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "$obj: FAILED", e)
                            IBluetoothManager.DEFAULT_MAC_ADDRESS
                        }
                }
            }
            is SystemServiceMessage.SetName -> {
                val source = obj.attributionSource
                val name = obj.name

                try {
                    checker.setNameAllowed(source)
                    // The Bluetooth Device Name can be up to 248 bytes (see [Vol 2] Part C, Section
                    // 4.3.5).
                    if (name != null && name.toByteArray(UTF_8).size > 248) {
                        throw IllegalArgumentException("Name is too long: $name")
                    }

                    api.setName(name)
                } catch (e: PermissionChecker.BluetoothPermissionException) {
                    Log.e(TAG, "${obj}: FAILED", e)
                }
                SystemServiceMessage.SetName.Reply()
            }
            is SystemServiceMessage.GetName -> {
                val source = obj.attributionSource

                SystemServiceMessage.GetName.Reply().apply {
                    value =
                        try {
                            checker.getNameAllowed(source)
                            api.getName()
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "$obj: FAILED", e)
                            null
                        }
                }
            }
            is SystemServiceMessage.IsBleScanAvailable -> {
                SystemServiceMessage.IsBleScanAvailable.Reply().apply {
                    value = api.isBleScanAvailable()
                }
            }
            is SystemServiceMessage.IsHearingAidSupported -> {
                SystemServiceMessage.IsHearingAidSupported.Reply().apply {
                    value = api.isHearingAidProfileSupported()
                }
            }
            is SystemServiceMessage.SetSnoopLog -> {
                checker.enforcePrivileged(sendingUid)

                val mode = obj.mode

                BluetoothProperties.snoop_log_mode(
                    when (mode) {
                        BT_SNOOP_LOG_MODE_DISABLED ->
                            BluetoothProperties.snoop_log_mode_values.DISABLED
                        BT_SNOOP_LOG_MODE_FILTERED ->
                            BluetoothProperties.snoop_log_mode_values.FILTERED
                        BT_SNOOP_LOG_MODE_FULL -> BluetoothProperties.snoop_log_mode_values.FULL
                        else -> null
                    }
                )
                SystemServiceMessage.SetSnoopLog.Reply()
            }
            is SystemServiceMessage.GetSnoopLog -> {
                checker.enforcePrivileged(sendingUid)
                SystemServiceMessage.GetSnoopLog.Reply().apply {
                    value =
                        when (
                            BluetoothProperties.snoop_log_mode()
                                .orElse(BluetoothProperties.snoop_log_mode_values.DISABLED)
                        ) {
                            BluetoothProperties.snoop_log_mode_values.FILTERED ->
                                BT_SNOOP_LOG_MODE_FILTERED
                            BluetoothProperties.snoop_log_mode_values.FULL -> BT_SNOOP_LOG_MODE_FULL
                            else -> BT_SNOOP_LOG_MODE_DISABLED
                        }
                }
            }
            is SystemServiceMessage.IsAutoSupported -> {
                checker.enforcePrivileged(sendingUid)
                SystemServiceMessage.IsAutoSupported.Reply().apply {
                    value = api.isAutoOnSupported()
                }
            }
            is SystemServiceMessage.SetAutoOnEnabled -> {
                checker.enforcePrivileged(sendingUid)
                api.setAutoOnEnabled(obj.enabledStatus)
                SystemServiceMessage.SetAutoOnEnabled.Reply()
            }
            is SystemServiceMessage.IsAutoEnabled -> {
                checker.enforcePrivileged(sendingUid)
                SystemServiceMessage.IsAutoEnabled.Reply().apply { value = api.isAutoOnEnabled() }
            }
            else -> throw IllegalArgumentException("Invalid command: [$obj] from $sendingUid")
        }
    }
}
