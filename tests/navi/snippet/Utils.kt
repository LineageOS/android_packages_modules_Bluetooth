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

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.MediaItem
import com.google.android.mobly.snippet.event.EventCache
import com.google.android.mobly.snippet.event.SnippetEvent
import com.google.android.mobly.snippet.rpc.TypeConverter
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

object Utils {

    const val TAG = "BtSnippetUtils"

    data class MediaNode(val item: MediaItem, val children: List<MediaNode>)

    /**
     * A [JSONObject] that can be [Parcelable].
     *
     * This is needed to pass a [JSONObject] to [SnippetEvent].
     */
    class ParcelableJsonObject : JSONObject(), Parcelable {
        override fun writeToParcel(dest: Parcel, flags: Int) {}

        override fun describeContents(): Int = 0
    }

    fun getProfileProxy(context: Context, profile: Int): BluetoothProfile {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

        val deferred = CompletableDeferred<BluetoothProfile>()

        bluetoothAdapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    deferred.complete(proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            },
            profile,
        )
        return runBlocking { withTimeoutOrNull(5.seconds) { deferred.await() } }
            ?: throw IllegalStateException(
                "Failed to get profile proxy for profile ${BluetoothProfile.getProfileName(profile)} after 5 seconds."
            )
    }

    /** Converts [JSONArray] to a [List] so that iterators can be used. */
    @Suppress("UNCHECKED_CAST")
    fun <T> JSONArray.toList(): List<T> =
        mutableListOf<T>().also {
            for (i in 0 until this.length()) {
                it.add(this[i] as T)
            }
        }

    /** Converts [JSONObject] to a [Map] so that iterators can be used. */
    @Suppress("UNCHECKED_CAST")
    fun <K, V> JSONObject.toMap(): Map<K, V> =
        keys().asSequence().associate { it as K to this.get(it) as V }

    /**
     * Gets a field of key [name] from JSON Object, or null if not found or the value is not the
     * given type.
     */
    inline fun <reified T> JSONObject.getOrNull(name: String): T? =
        opt(name)?.let { if (it is T) it else null }

    /**
     * Posts an [SnippetEvent] with [callbackId] and [eventName] to the event cache with data bundle
     * [fill] by the given function.
     *
     * This is a helper function to make your client side codes more concise. Sample usage:
     * ```
     *   postSnippetEvent(callbackId, "onReceiverFound") {
     *     putLong("discoveryTimeMs", discoveryTimeMs)
     *     putBoolean("isKnown", isKnown)
     *   }
     * ```
     */
    fun postSnippetEvent(callbackId: String, eventName: String, fill: Bundle.() -> Unit) =
        EventCache.getInstance()
            .postEvent(SnippetEvent(callbackId, eventName).apply { data.apply(fill) })

    /** Converts this to a human readable string. */
    fun Int.toBluetoothStatusDescription(): String =
        BLUETOOTH_STATUS_CODE_DESCRIPTIONS[this] ?: "UNKNOWN($this)"

    private val BLUETOOTH_STATUS_CODE_DESCRIPTIONS =
        mapOf(
            0 to "SUCCESS(0)",
            1 to "ERROR_BLUETOOTH_NOT_ENABLED(1)",
            2 to "ERROR_BLUETOOTH_NOT_ALLOWED(2)",
            3 to "ERROR_DEVICE_NOT_BONDED(3)",
            4 to "ERROR_DEVICE_NOT_CONNECTED(4)",
            6 to "ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION(6)",
            7 to "ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION(7)",
            9 to "ERROR_PROFILE_SERVICE_NOT_BOUND(9)",
            10 to "FEATURE_SUPPORTED(10)",
            11 to "FEATURE_NOT_SUPPORTED(11)",
            12 to "ERROR_NOT_ACTIVE_DEVICE(12)",
            13 to "ERROR_NO_ACTIVE_DEVICES(13)",
            14 to "ERROR_PROFILE_NOT_CONNECTED(14)",
            15 to "ERROR_TIMEOUT(15)",
            16 to "REASON_LOCAL_APP_REQUEST(16)",
            17 to "REASON_LOCAL_STACK_REQUEST(17)",
            18 to "REASON_REMOTE_REQUEST(18)",
            19 to "REASON_SYSTEM_POLICY(19)",
            20 to "ERROR_HARDWARE_GENERIC(20)",
            21 to "ERROR_BAD_PARAMETERS(21)",
            22 to "ERROR_LOCAL_NOT_ENOUGH_RESOURCES(22)",
            23 to "ERROR_REMOTE_NOT_ENOUGH_RESOURCES(23)",
            24 to "ERROR_REMOTE_OPERATION_REJECTED(24)",
            25 to "ERROR_REMOTE_LINK_ERROR(25)",
            26 to "ERROR_ALREADY_IN_TARGET_STATE(26)",
            27 to "ERROR_REMOTE_OPERATION_NOT_SUPPORTED(27)",
            28 to "ERROR_CALLBACK_NOT_REGISTERED(28)",
            29 to "ERROR_ANOTHER_ACTIVE_REQUEST(29)",
            30 to "FEATURE_NOT_CONFIGURED(30)",
            200 to "ERROR_GATT_WRITE_NOT_ALLOWED(200)",
            201 to "ERROR_GATT_WRITE_REQUEST_BUSY(201)",
            400 to "ALLOWED(400)",
            401 to "NOT_ALLOWED(401)",
            1000 to "ERROR_ANOTHER_ACTIVE_OOB_REQUEST(1000)",
            1100 to "ERROR_DISCONNECT_REASON_LOCAL_REQUEST(1100)",
            1101 to "ERROR_DISCONNECT_REASON_REMOTE_REQUEST(1101)",
            1102 to "ERROR_DISCONNECT_REASON_LOCAL(1102)",
            1103 to "ERROR_DISCONNECT_REASON_REMOTE(1103)",
            1104 to "ERROR_DISCONNECT_REASON_TIMEOUT(1104)",
            1105 to "ERROR_DISCONNECT_REASON_SECURITY(1105)",
            1106 to "ERROR_DISCONNECT_REASON_SYSTEM_POLICY(1106)",
            1107 to "ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED(1107)",
            1108 to "ERROR_DISCONNECT_REASON_CONNECTION_ALREADY_EXISTS(1108)",
            1109 to "ERROR_DISCONNECT_REASON_BAD_PARAMETERS(1109)",
            1116 to "ERROR_AUDIO_DEVICE_ALREADY_CONNECTED(1116)",
            1117 to "ERROR_AUDIO_DEVICE_ALREADY_DISCONNECTED(1117)",
            1118 to "ERROR_AUDIO_ROUTE_BLOCKED(1118)",
            1119 to "ERROR_CALL_ACTIVE(1119)",
            1200 to "ERROR_LE_BROADCAST_INVALID_BROADCAST_ID(1200)",
            1201 to "ERROR_LE_BROADCAST_INVALID_CODE(1201)",
            1202 to "ERROR_LE_BROADCAST_ASSISTANT_INVALID_SOURCE_ID(1202)",
            1203 to "ERROR_LE_BROADCAST_ASSISTANT_DUPLICATE_ADDITION(1203)",
            1204 to "ERROR_LE_CONTENT_METADATA_INVALID_PROGRAM_INFO(1204)",
            1205 to "ERROR_LE_CONTENT_METADATA_INVALID_LANGUAGE(1205)",
            1206 to "ERROR_LE_CONTENT_METADATA_INVALID_OTHER(1206)",
            1207 to "ERROR_CSIP_INVALID_GROUP_ID(1207)",
            1208 to "ERROR_CSIP_GROUP_LOCKED_BY_OTHER(1208)",
            1209 to "ERROR_CSIP_LOCKED_GROUP_MEMBER_LOST(1209)",
            1210 to "ERROR_HAP_PRESET_NAME_TOO_LONG(1210)",
            1211 to "ERROR_HAP_INVALID_PRESET_INDEX(1211)",
            1300 to "ERROR_NO_LE_CONNECTION(1300)",
            1301 to "ERROR_DISTANCE_MEASUREMENT_INTERNAL(1301)",
            2000 to "RFCOMM_LISTENER_START_FAILED_UUID_IN_USE(2000)",
            2001 to "RFCOMM_LISTENER_OPERATION_FAILED_NO_MATCHING_SERVICE_RECORD(2001)",
            2002 to "RFCOMM_LISTENER_OPERATION_FAILED_DIFFERENT_APP(2002)",
            2003 to "RFCOMM_LISTENER_FAILED_TO_CREATE_SERVER_SOCKET(2003)",
            2004 to "RFCOMM_LISTENER_FAILED_TO_CLOSE_SERVER_SOCKET(2004)",
            2005 to "RFCOMM_LISTENER_NO_SOCKET_AVAILABLE(2005)",
            3000 to "ERROR_NOT_DUAL_MODE_AUDIO_DEVICE(3000)",
        )

    class IntConverter : TypeConverter<Int> {
        override fun convert(value: String): Int = value.toInt()
    }

    class LongConverter : TypeConverter<Long> {
        override fun convert(value: String): Long = value.toLong()
    }

    class BooleanConverter : TypeConverter<Boolean> {
        override fun convert(value: String): Boolean = value.toBoolean()
    }
}
