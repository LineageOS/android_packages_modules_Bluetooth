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

import android.bluetooth.IBluetoothManager
import android.bluetooth.State
import android.bluetooth.SystemServiceMessenger
import android.content.AttributionSource
import android.os.Binder
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.android.bluetooth.flags.Flags
import com.android.modules.utils.BasicShellCommandHandler
import java.io.PrintWriter

private const val TAG = "ShellCommand"

class ShellCommand(
    private val binder: IBluetoothManager.Stub,
    rawMessenger: Messenger,
    private val waitForState: (Int) -> Boolean,
) : BasicShellCommandHandler() {

    private val messenger = SystemServiceMessenger(rawMessenger)
    private val source = AttributionSource.myAttributionSource()

    data class Command(
        private val name: String,
        val isPrivileged: Boolean = false,
        val help: (PrintWriter) -> Unit,
        val isMatch: (String) -> Boolean = { it == name },
        val exec: (String) -> Int,
    )

    private val commands =
        listOf(
            Command(
                name = "enable",
                help = { pw ->
                    pw.println("  enable")
                    pw.println("    Enable Bluetooth on this device.")
                },
                exec = {
                    if (Flags.systemServerMessenger()) {
                        val reply =
                            messenger.send(
                                SystemServiceMessage.Enable().apply { attributionSource = source }
                            )
                        if (reply.value == true) 0 else -1
                    } else {
                        if (binder.enable(source)) 0 else -1
                    }
                },
            ),
            Command(
                name = "disable",
                help = { pw ->
                    pw.println("  disable")
                    pw.println("    Disable Bluetooth on this device.")
                },
                exec = {
                    if (Flags.systemServerMessenger()) {
                        val reply =
                            messenger.send(
                                SystemServiceMessage.Disable().apply {
                                    attributionSource = source
                                    persist = true
                                }
                            )
                        if (reply.value == true) 0 else -1
                    } else {
                        if (binder.disable(source, true)) 0 else -1
                    }
                },
            ),
            Command(
                name = "enableBle",
                isPrivileged = true,
                help = { pw ->
                    pw.println("  enableBle")
                    pw.println("    Call enableBle to activate ble only mode on this device.")
                },
                exec = {
                    if (Flags.systemServerMessenger()) {
                        val reply =
                            messenger.send(
                                SystemServiceMessage.Enable().apply {
                                    attributionSource = source
                                    bleToken = binder
                                }
                            )
                        if (reply.value == true) 0 else -1
                    } else {
                        if (binder.enableBle(source, binder)) 0 else -1
                    }
                },
            ),
            Command(
                name = "disableBle",
                isPrivileged = true,
                help = { pw ->
                    pw.println("  disableBle")
                    pw.println("    undo the call to enableBle.")
                },
                exec = {
                    if (Flags.systemServerMessenger()) {
                        val reply =
                            messenger.send(
                                SystemServiceMessage.Disable().apply {
                                    attributionSource = source
                                    bleToken = binder
                                }
                            )
                        if (reply.value == true) 0 else -1
                    } else {
                        if (binder.disableBle(source, binder)) 0 else -1
                    }
                },
            ),
            Command(
                name = "factoryReset",
                isPrivileged = true,
                help = { pw ->
                    pw.println("  factoryReset")
                    pw.println("    Perform a factory reset of Bluetooth settings.")
                },
                exec = {
                    if (Flags.systemServerMessenger()) {
                        val reply =
                            messenger.send(
                                SystemServiceMessage.FactoryReset().apply {
                                    attributionSource = source
                                }
                            )
                        if (reply.value == true) 0 else -1
                    } else {
                        if (binder.factoryReset(source)) 0 else -1
                    }
                },
            ),
            Command(
                name = "wait-for-state",
                help = { pw ->
                    pw.println("  wait-for-state:<STATE>")
                    pw.println("    Wait until the adapter state is one of [STATE_OFF|STATE_ON]")
                    pw.println("    Note: This command can timeout and failed")
                },
                isMatch = { it.startsWith("wait-for-state:") },
                exec = { cmd ->
                    try {
                        val state = getWaitingState(cmd)
                        if (waitForState(state)) 0 else -1
                    } catch (e: IllegalArgumentException) {
                        -1
                    }
                },
            ),
        )

    private fun getWaitingState(inCmd: String): Int {
        val stateStr = inCmd.substringAfter("wait-for-state:")
        return when (stateStr) {
            "STATE_OFF" -> State.OFF
            "STATE_ON" -> State.ON
            else -> {
                val msg = "wait-for-state: Invalid state value: $stateStr. From: $inCmd"
                Log.e(TAG, msg)
                val pw = errPrintWriter
                pw.println("$TAG: $msg")
                printHelp(pw)
                throw IllegalArgumentException(msg)
            }
        }
    }

    @Throws(RemoteException::class)
    override fun onCommand(cmd: String?): Int {
        if (cmd == null) {
            return handleDefaultCommands(null)
        }

        val command = commands.find { it.isMatch(cmd) }
        if (command == null) {
            Log.w(TAG, "Unknown command: $cmd")
            errPrintWriter.println("Unknown command: $cmd")
            printHelp(errPrintWriter)
            return -1
        }
        if (command.isPrivileged && Binder.getCallingUid() != Process.ROOT_UID) {
            throw SecurityException("Command $cmd requires a Root shell")
        }
        outPrintWriter.println("$TAG: Exec $cmd")
        Log.d(TAG, "Exec $cmd")
        val ret = command.exec(cmd)
        if (ret == 0) {
            val msg = "$cmd: Success"
            Log.d(TAG, msg)
            outPrintWriter.println(msg)
        } else {
            val msg = "$cmd: Failed with status=$ret"
            Log.e(TAG, msg)
            errPrintWriter.println("$TAG: $msg")
        }
        return ret
    }

    private fun printHelp(pw: PrintWriter) {
        pw.println("Bluetooth Manager Commands:")
        pw.println("  help or -h")
        pw.println("    Print this help text.")
        for (command in commands) {
            command.help(pw)
        }
    }

    override fun onHelp() = printHelp(outPrintWriter)
}
