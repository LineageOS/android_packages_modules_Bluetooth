/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc

class BluetoothHidHostSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val broadcastReceivers = mutableMapOf<String, BroadcastReceiver>()
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val proxy =
        Utils.getProfileProxy(context, BluetoothProfile.HID_HOST) as BluetoothHidHost

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Register an HID Host callback with [callbackId]. */
    @AsyncRpc(description = "Register HID Host Callback.")
    fun registerHidHostCallback(callbackId: String) {
        val intentFilter =
            IntentFilter().apply {
                addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
                addAction(HID_HOST_ACTION_HANDSHAKE)
                addAction(HID_HOST_ACTION_REPORT)
                addAction(HID_HOST_ACTION_IDLE_TIME_CHANGED)
            }
        broadcastReceivers[callbackId] =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val device =
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    val state =
                        intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothDevice.ERROR)
                    val status = intent.getIntExtra(HID_HOST_EXTRA_STATUS, -1)
                    val report = intent.getByteArrayExtra(HID_HOST_EXTRA_REPORT)
                    val idleTime = intent.getIntExtra(HID_HOST_EXTRA_IDLE_TIME, -1)
                    when (intent.action) {
                        BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED ->
                            Utils.postSnippetEvent(
                                callbackId,
                                SnippetConstants.PROFILE_CONNECTION_STATE_CHANGE,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                        HID_HOST_ACTION_HANDSHAKE ->
                            Utils.postSnippetEvent(
                                callbackId,
                                SnippetConstants.HID_HOST_HANDSHAKE,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATUS, status)
                            }

                        HID_HOST_ACTION_REPORT ->
                            Utils.postSnippetEvent(callbackId, SnippetConstants.HID_HOST_REPORT) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putByteArray(SnippetConstants.FIELD_REPORT, report)
                            }
                        HID_HOST_ACTION_IDLE_TIME_CHANGED ->
                            Utils.postSnippetEvent(
                                callbackId,
                                SnippetConstants.HID_HOST_IDLE_TIME_CHANGED,
                            ) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_IDLE_TIME, idleTime)
                            }
                    }
                }
            }
        context.registerReceiver(
            broadcastReceivers[callbackId],
            intentFilter,
            Context.RECEIVER_EXPORTED,
        )
    }

    /** Unregisters an HID Host callback with ID [callbackId]. */
    @Rpc(description = "Unregister HID Host callbacks.")
    fun unregisterHidHostCallback(callbackId: String) =
        broadcastReceivers.remove(callbackId)?.let { context.unregisterReceiver(it) }

    /** Sets the connection policy for the HID host. */
    @Rpc(description = "Sets the connection policy for the HID host.")
    fun setHidHostConnectionPolicy(address: String, policy: Int): Boolean {
        return proxy.setConnectionPolicy(bluetoothAdapter.getRemoteDevice(address), policy)
    }

    /** Sets the preferred transport for the HID host. */
    @Rpc(description = "Sets the preferred transport for the HID host.")
    fun setHidHostPreferredTransport(address: String, transport: Int): Boolean {
        return proxy.setPreferredTransport(bluetoothAdapter.getRemoteDevice(address), transport)
    }

    /** Gets the connection policy for the HID host. */
    @Rpc(description = "Gets the connection policy for the HID host.")
    fun getHidHostConnectionPolicy(address: String): Int {
        return proxy.getConnectionPolicy(bluetoothAdapter.getRemoteDevice(address))
    }

    /** Gets the preferred transport for the HID host. */
    @Rpc(description = "Gets the preferred transport for the HID host.")
    fun getHidHostPreferredTransport(address: String): Int {
        return proxy.getPreferredTransport(bluetoothAdapter.getRemoteDevice(address))
    }

    /** Unplugs the HID device with the given [address]. */
    @Rpc(description = "Unplugs the HID device with the given address.")
    fun virtualUnplug(address: String): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod("virtualUnplug", BluetoothDevice::class.java)
            .invoke(proxy, device) as Boolean
    }

    /**
     * Initiates a request to get the protocol mode for the HID host.
     *
     * @param address The Bluetooth address of the target device.
     * @return True if the request was successfully initiated; false otherwise. The actual protocol
     *   mode value is delivered via the PROTOCOL_MODE_CHANGED event.
     */
    @Rpc(description = "Gets the protocol mode for the HID host.")
    fun getHidHostProtocolMode(address: String): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod("getProtocolMode", BluetoothDevice::class.java)
            .invoke(proxy, device) as Boolean
    }

    /** Sets the protocol mode for the HID host. */
    @Rpc(description = "Sets the protocol mode for the HID host.")
    fun setHidHostProtocolMode(address: String, protocolMode: Int): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod("setProtocolMode", BluetoothDevice::class.java, Int::class.java)
            .invoke(proxy, device, protocolMode) as Boolean
    }

    /** Gets the report for the HID host. */
    @Rpc(description = "Gets the report for the HID host.")
    fun getHidHostReport(
        address: String,
        reportType: Int,
        reportId: Int,
        bufferSize: Int,
    ): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod(
                "getReport",
                BluetoothDevice::class.java,
                Byte::class.java,
                Byte::class.java,
                Int::class.java,
            )
            .invoke(proxy, device, reportType.toByte(), reportId.toByte(), bufferSize) as Boolean
    }

    /** Sets the report for the HID host. */
    @Rpc(description = "Sets the report for the HID host.")
    fun setHidHostReport(address: String, reportType: Int, report: String): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod(
                "setReport",
                BluetoothDevice::class.java,
                Byte::class.java,
                String::class.java,
            )
            .invoke(proxy, device, reportType.toByte(), report) as Boolean
    }

    /** Sends the data for the HID host. */
    @Rpc(description = "Sends the data for the HID host.")
    fun sendHidHostData(address: String, report: String): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod("sendData", BluetoothDevice::class.java, String::class.java)
            .invoke(proxy, device, report) as Boolean
    }

    /**
     * Initiates a request to get the idle time for the HID host.
     *
     * @param address The Bluetooth address of the target device.
     * @return True if the request was successfully initiated; false otherwise. The actual idle time
     *   value is delivered via the IDLE_TIME_CHANGED event.
     */
    @Rpc(description = "Gets the idle time for the HID host.")
    fun getHidHostIdleTime(address: String): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod("getIdleTime", BluetoothDevice::class.java)
            .invoke(proxy, device) as Boolean
    }

    /** Sets the idle time for the HID host. */
    @Rpc(description = "Sets the idle time for the HID host.")
    fun setHidHostIdleTime(address: String, idleTime: Int): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothHidHost::class
            .java
            .getMethod("setIdleTime", BluetoothDevice::class.java, Byte::class.java)
            .invoke(proxy, device, idleTime.toByte()) as Boolean
    }

    private companion object {
        const val HID_HOST_ACTION_HANDSHAKE = "android.bluetooth.input.profile.action.HANDSHAKE"
        const val HID_HOST_ACTION_REPORT = "android.bluetooth.input.profile.action.REPORT"
        const val HID_HOST_ACTION_IDLE_TIME_CHANGED =
            "android.bluetooth.input.profile.action.IDLE_TIME_CHANGED"
        const val HID_HOST_EXTRA_STATUS = "android.bluetooth.BluetoothHidHost.extra.STATUS"
        const val HID_HOST_EXTRA_REPORT = "android.bluetooth.BluetoothHidHost.extra.REPORT"
        const val HID_HOST_EXTRA_IDLE_TIME = "android.bluetooth.BluetoothHidHost.extra.IDLE_TIME"
    }
}
