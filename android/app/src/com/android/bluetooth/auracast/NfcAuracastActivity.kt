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
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcastAssistant
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
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
                    processMetadata(payload.substring(startIndex))
                    return
                }
            }
        }
    }

    private fun processMetadata(metadataStr: String) {
        val info = AuracastUtils.parseBroadcastNameAndCode(metadataStr)
        val streamName = info?.name

        if (streamName.isNullOrBlank()) {
            Log.d(TAG, "No broadcast name present!")
            return
        }

        showJoinPromptNotificationAsync(metadataStr, streamName)
    }

    private fun showJoinPromptNotificationAsync(metadataStr: String, streamName: String) {
        val appContext = applicationContext

        // Check if Bluetooth is missing or turned off
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "BluetoothAdapter is null or disabled. Posting fallback notification.")
            postNotification(appContext, metadataStr, streamName, null)
            return
        }

        val listener = ProxyListener(metadataStr, streamName)

        val success =
            bluetoothAdapter!!.getProfileProxy(
                appContext,
                listener,
                BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
            )

        if (!success) {
            Log.w(TAG, "Failed to get BASS profile proxy")
            postNotification(appContext, metadataStr, streamName, null)
        }
    }

    private inner class ProxyListener(
        private val metadataStr: String,
        private val streamName: String,
    ) : BluetoothProfile.ServiceListener {

        @RequiresPermission(allOf = [BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED])
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT) {
                val assistant = proxy as BluetoothLeBroadcastAssistant
                val connectedDevice = assistant.connectedDevices.firstOrNull()

                postNotification(applicationContext, metadataStr, streamName, connectedDevice)

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
        metadataStr: String,
        streamName: String,
        connectedDevice: BluetoothDevice?,
    ) {
        val nm = notificationManagerProvider(context)

        val channel =
            NotificationChannel(
                AuracastUtils.CHANNEL_ID,
                "Auracast",
                NotificationManager.IMPORTANCE_HIGH,
            )
        nm.createNotificationChannel(channel)

        val connectIntent =
            Intent(AuracastUtils.ACTION_CONNECT_STREAM).apply {
                setPackage(context.packageName)
                putExtra(AuracastUtils.EXTRA_METADATA, metadataStr)
            }

        val connectPending =
            PendingIntent.getBroadcast(
                context,
                0,
                connectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        if (connectedDevice == null) {
            // No device connected: Pass the testable 'nm' and null for the pending intent
            val message = "Connect an LE Audio device to start listening"
            AuracastUtils.showNotification(context, nm, streamName, message, null)
        } else {
            // Device is connected
            // TODO: (b/491294522): use getAlias() or getName() for notification
            val deviceName = "devices"
            val message = "Listen to $streamName audio stream on your $deviceName"
            AuracastUtils.showNotification(context, nm, streamName, message, connectPending)
        }
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
