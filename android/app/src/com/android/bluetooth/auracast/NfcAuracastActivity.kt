/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.auracast

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.annotation.RequiresPermission
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothLeBroadcastAssistant
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import com.android.bluetooth.R
import com.android.bluetooth.flags.Flags

/**
 * Invisible Activity to handle NFC taps. It parses the intent, posts a notification, and
 * immediately finishes.
 */
class NfcAuracastActivity : Activity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothAdapterProvider(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Handle the incoming intent immediately
        handleIntent(intent)

        // 2. Close this activity instantly so the user doesn't see a screen
        finish()
    }

    private fun handleIntent(intent: Intent) {
        if (!Flags.leaudioAuracastCredentialExtension()) {
            return
        }

        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            handleNfcDiscovery(intent)
        }
    }

    private fun handleNfcDiscovery(intent: Intent) {
        val rawMsgs =
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        if (rawMsgs == null) {
            return
        }

        for (rawMsg in rawMsgs) {
            val msg = rawMsg as NdefMessage
            for (record in msg.records) {
                val payload = String(record.payload, Charsets.UTF_8)
                if (payload.contains(AuracastUtils.AURACAST_PREFIX)) {
                    val startIndex = payload.indexOf(AuracastUtils.AURACAST_PREFIX)
                    processUri(payload.substring(startIndex))
                    return
                }
            }
        }
    }

    private fun processUri(uriString: String) {
        val info = AuracastUtils.parseBroadcastURI(uriString)
        val streamName = info?.name

        if (streamName.isNullOrBlank()) {
            Log.d(TAG, "No broadcast name present!")
            return
        }

        showJoinPromptNotificationAsync(uriString, streamName)
    }

    private fun showJoinPromptNotificationAsync(uriString: String, streamName: String) {
        val appContext = applicationContext

        // Check if Bluetooth is missing or turned off
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "BluetoothAdapter is null or disabled. Posting fallback notification.")
            postNotification(appContext, uriString, streamName, null)
            return
        }

        val listener = ProxyListener(uriString, streamName)

        val success =
            bluetoothAdapter!!.getProfileProxy(
                appContext,
                listener,
                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
            )

        if (!success) {
            Log.w(TAG, "Failed to get BASS profile proxy")
            postNotification(appContext, uriString, streamName, null)
        }
    }

    private inner class ProxyListener(
        private val uriString: String,
        private val streamName: String,
    ) : BluetoothProfile.ServiceListener {

        @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT) {
                val assistant = proxy as BluetoothLeBroadcastAssistant
                val connectedDevice = assistant.connectedDevices.firstOrNull()
                // If no connected device, deviceName is null.
                // If device exists, it resolves the alias, name, or falls back to "devices".
                val defaultDeviceName =
                    applicationContext.getString(R.string.auracast_default_device_name)
                val deviceName = connectedDevice?.let { it.alias ?: it.name ?: defaultDeviceName }
                postNotification(applicationContext, uriString, streamName, deviceName)

                bluetoothAdapter?.closeProfileProxy(
                    BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                    proxy,
                )
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            // Do nothing
        }
    }

    private fun postNotification(
        context: Context,
        uriString: String,
        streamName: String,
        deviceName: String?,
    ) {
        val nm = notificationManagerProvider(context)

        val channelName = context.getString(R.string.auracast_notification_channel)
        val channel =
            NotificationChannel(
                AuracastUtils.CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_HIGH,
            )
        nm.createNotificationChannel(channel)

        val title = context.getString(R.string.auracast_notification_title, streamName)
        if (deviceName == null) {
            // No device connected: Pass the testable 'nm' and null for the pending intent
            val message = context.getString(R.string.auracast_connect_device_message)
            AuracastUtils.showNotification(context, nm, title, message, null)
            return
        }

        // Device is connected
        val connectIntent =
            Intent(AuracastUtils.ACTION_CONNECT_STREAM).apply {
                setPackage(context.packageName)
                putExtra(AuracastUtils.EXTRA_METADATA, uriString)
            }

        val connectPending =
            PendingIntent.getBroadcast(
                context,
                0,
                connectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val message =
            context.getString(R.string.auracast_listen_on_device_message, streamName, deviceName)
        AuracastUtils.showNotification(context, nm, title, message, connectPending)
    }

    companion object {
        private const val TAG = "NfcAuracastActivity"

        // VisibleForTesting
        var notificationManagerProvider: (Context) -> NotificationManager = {
            it.getSystemService(NotificationManager::class.java)!!
        }

        // VisibleForTesting
        var bluetoothAdapterProvider: (Context) -> BluetoothAdapter? = {
            it.getSystemService(BluetoothManager::class.java)?.adapter
        }
    }
}
