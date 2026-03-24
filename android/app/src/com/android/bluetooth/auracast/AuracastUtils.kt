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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Base64
import android.util.Log
import com.android.bluetooth.R
import com.android.bluetooth.le_audio.LeAudioConstants

object AuracastUtils {
    const val CHANNEL_ID = "auracast_nfc_channel"
    const val NOTIFICATION_ID = 1001
    // 5mins
    const val NOTIF_AUTO_DISMISS_MILLIS = 300000L
    const val AURACAST_PREFIX = "BLUETOOTH:UUID:184F"
    const val ACTION_CONNECT_STREAM = "com.android.bluetooth.auracast.action.CONNECT_STREAM"
    const val EXTRA_METADATA = "extra_metadata"

    private const val TAG = "AuracastUtils"
    // --- URI Parsing Constants ---
    private const val SCHEME_BT_BROADCAST_METADATA = "BLUETOOTH:UUID:184F;"
    private const val DELIMITER_KEY_VALUE = ":"
    private const val DELIMITER_ELEMENT = ";"
    private const val SUFFIX_QR_CODE = ";;"

    /**
     * Parses the broadcast metadata string to extract the Broadcast Name (BN) and Broadcast Code
     * (BC) and Broadcast ID (BI).
     *
     * @param uriString The raw or stripped URI string
     * @return A [BroadcastStreamInfo] object containing the parsed name, code and broadcast ID, or
     *   null if the name is missing.
     */
    @JvmStatic
    fun parseBroadcastURI(uriString: String): BroadcastStreamInfo? {
        var bName: String? = null
        var bCode: ByteArray? = null
        var bId: Int = LeAudioConstants.INVALID_BROADCAST_ID

        // Safely strip the scheme and suffix if they are present in the string
        val strippedString =
            uriString.removePrefix(SCHEME_BT_BROADCAST_METADATA).removeSuffix(SUFFIX_QR_CODE)

        val parts = strippedString.split(DELIMITER_ELEMENT)
        for (part in parts) {
            if (part.startsWith("BN$DELIMITER_KEY_VALUE")) {
                val encodedName = part.substring(3)
                bName =
                    try {
                        String(Base64.decode(encodedName, Base64.NO_WRAP), Charsets.UTF_8)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Failed to decode Base64 name, using raw string")
                        encodedName // Fallback to raw string
                    }
            } else if (part.startsWith("BC$DELIMITER_KEY_VALUE")) {
                try {
                    bCode = Base64.decode(part.substring(3), Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Failed to decode broadcast code")
                }
            } else if (part.startsWith("BI$DELIMITER_KEY_VALUE")) {
                try {
                    val parsedId = part.substring(3).toInt(16)
                    if (parsedId in 0..0xFFFFFF) {
                        bId = parsedId
                    } else {
                        Log.w(TAG, "Broadcast ID is out of valid 24-bit range")
                    }
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Failed to decode broadcast ID")
                }
            }
        }

        if (bName.isNullOrEmpty()) {
            return null
        }
        return BroadcastStreamInfo(bName, bCode, bId)
    }

    /**
     * Displays a high-priority notification to the user to join or monitor an Auracast broadcast.
     *
     * This notification informs the user about a discovered stream and provides a [PendingIntent]
     * to initiate the connection if a compatible LE Audio device is available.
     *
     * @param context The [Context] used to retrieve resources and system services.
     * @param nm The [NotificationManager] instance responsible for posting the notification.
     * @param title The human-readable name of the Auracast broadcast (e.g., "Airport TV").
     * @param message The descriptive text body of the notification, often indicating the target
     *   device.
     * @param connectPending An optional [PendingIntent] to be triggered when the user taps the
     *   notification; if null, the notification acts as information only.
     */
    @JvmStatic
    fun showNotification(
        context: Context,
        nm: NotificationManager,
        title: String,
        message: String,
        connectPending: PendingIntent?,
    ) {
        val builder =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bt_le_audio_sharing)
                .setSubText(context.getString(R.string.auracast_notification_subtext))
                .setLocalOnly(true)
                .setContentTitle(title)
                .setContentText(message)
                .setTimeoutAfter(NOTIF_AUTO_DISMISS_MILLIS)

        if (connectPending != null) {
            val connectText = context.getString(R.string.auracast_connect_action)
            builder.addAction(
                Notification.Action.Builder(null, connectText, connectPending).build()
            )
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }
}

// Simple data class to hold the parsed Name, Code and Broadcast ID
class BroadcastStreamInfo(val name: String, val code: ByteArray?, val broadcastId: Int)
