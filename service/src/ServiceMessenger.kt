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

import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST
import android.bluetooth.IBluetoothManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcelable
import android.os.RemoteException

private const val TAG = "Messenger"

internal class ServiceMessenger(
    looper: Looper,
    private val checker: PermissionChecker,
    private val managerService: BluetoothManagerService,
) : Handler(looper) {
    val messenger = Messenger(this)

    override fun handleMessage(msg: Message) {
        Log.i(TAG, "handleMessage: ${msg}")
        val reply = Message.obtain()
        try {
            reply.obj = handleMessage(msg.sendingUid, msg.obj as Parcelable)
        } catch (e: RuntimeException) {
            reply.data = Bundle().apply { putSerializable("exception", e) }
        } finally {
            try {
                msg.replyTo?.send(reply)
            } catch (e: RemoteException) {
                Log.e(TAG, "handleMessage($msg): Failed to send reply=${reply}", e)
            }
        }
    }

    private fun handleMessage(sendingUid: Int, obj: Parcelable): Parcelable {
        return when (obj) {
            is SystemServiceMessage.RegisterAdapter -> {
                SystemServiceMessage.RegisterAdapter.Reply().apply {
                    value = managerService.registerAdapter(obj.binder)
                }
            }
            is SystemServiceMessage.UnregisterAdapter -> {
                managerService.unregisterAdapter(obj.binder)
                SystemServiceMessage.UnregisterAdapter.Reply()
            }
            is SystemServiceMessage.Enable -> {
                val source = obj.attributionSource
                val isQuiet = obj.isQuiet
                val bleToken = obj.bleToken

                val foregroundRequired = isQuiet == false || bleToken == null
                SystemServiceMessage.Enable.Reply().apply {
                    value =
                        try {
                            checker.enableAllowed(sendingUid, source, foregroundRequired)
                            if (bleToken != null) {
                                managerService.enableBle(source.getPackageName(), bleToken)
                            } else if (isQuiet) {
                                managerService.enableNoAutoConnect(source.getPackageName())
                            } else {
                                managerService.enable(
                                    ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                                    source.getPackageName(),
                                )
                            }
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "${obj}: FAILED", e)
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
                            checker.disableAllowed(sendingUid, source, foregroundRequired)
                            if (bleToken != null) {
                                managerService.disableBle(source.getPackageName(), bleToken)
                            } else {
                                managerService.disable(source.getPackageName(), persist)
                            }
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "${obj}: FAILED", e)
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
                            managerService.factoryReset(0)
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "${obj}: FAILED", e)
                            false
                        }
                }
            }
            is SystemServiceMessage.GetAddress -> {
                val source = obj.attributionSource

                SystemServiceMessage.GetAddress.Reply().apply {
                    value =
                        try {
                            checker.getAddressAllowed(sendingUid, source)
                            managerService.getAddress()
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "${obj}: FAILED", e)
                            IBluetoothManager.DEFAULT_MAC_ADDRESS
                        }
                }
            }
            is SystemServiceMessage.GetName -> {
                val source = obj.attributionSource

                SystemServiceMessage.GetName.Reply().apply {
                    value =
                        try {
                            checker.getNameAllowed(sendingUid, source)
                            managerService.getName()
                        } catch (e: PermissionChecker.BluetoothPermissionException) {
                            Log.e(TAG, "${obj}: FAILED", e)
                            null
                        }
                }
            }
            is SystemServiceMessage.IsBleScanAvailable -> {
                SystemServiceMessage.IsBleScanAvailable.Reply().apply {
                    value = managerService.isBleScanAvailable()
                }
            }
            else -> throw IllegalArgumentException("Invalid command: [${obj}] from ${sendingUid}")
        }
    }
}
