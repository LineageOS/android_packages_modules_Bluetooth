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

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import com.android.bluetooth.bass_client.BassClientService
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags

/**
 * Invisible Activity to handle NFC taps. It parses the intent, posts a notification, and
 * immediately finishes.
 */
class NfcAuracastActivity : Activity() {
    // TODO: (b/490499487): remove deprecatedGetAdapterService from NfcAuracastActivity
    private fun getAdapterService(): AdapterService? = AdapterService.deprecatedGetAdapterService()

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

        showJoinPromptNotification(metadataStr, streamName)
    }

    private fun showJoinPromptNotification(metadataStr: String, streamName: String) {
        // Get the NotificationManager using the testable provider
        val nm = notificationManagerProvider(this)

        val channel =
            NotificationChannel(
                AuracastUtils.CHANNEL_ID,
                "Auracast",
                NotificationManager.IMPORTANCE_HIGH,
            )
        nm.createNotificationChannel(channel)

        val connectIntent =
            Intent(AuracastUtils.ACTION_CONNECT_STREAM).apply {
                setPackage(packageName)
                putExtra(AuracastUtils.EXTRA_METADATA, metadataStr)
            }

        val connectPending =
            PendingIntent.getBroadcast(
                this,
                0,
                connectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val service = bassClientServiceProvider()
        val connectedDevice = service?.connectedDevices?.firstOrNull()

        if (connectedDevice == null) {
            // No device connected: Pass the testable 'nm' and null for the pending intent
            val message = "Connect an LE Audio device to start listening"
            AuracastUtils.showNotification(this, nm, streamName, message, null)
        } else {
            // Device is connected
            // TODO: (b/491294522): use getAlias() or getName() for notification
            val deviceName = "devices"
            val message = "Listen to $streamName audio stream on your $deviceName"
            AuracastUtils.showNotification(this, nm, streamName, message, connectPending)
        }
    }

    companion object {
        private const val TAG = "NfcAuracastActivity"

        // VisibleForTesting
        var notificationManagerProvider: (Activity) -> NotificationManager = {
            it.getSystemService(NotificationManager::class.java)!!
        }

        // VisibleForTesting - Inject the BassClientService
        var bassClientServiceProvider: () -> BassClientService? = {
            AdapterService.deprecatedGetAdapterService()?.bassClientService?.orElse(null)
        }
    }
}
