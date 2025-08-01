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

package com.android.bluetooth.gatt

import java.util.UUID

private const val TAG = "GattUtil"

object GattUtil {
    @JvmField val TAG_PREFIX = "BtGatt."

    private val HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805F9B34FB")

    private val HID_UUIDS =
        setOf(
            UUID.fromString("00002A4A-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("00002A4B-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("00002A4C-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("00002A4D-0000-1000-8000-00805F9B34FB"),
        )

    private val ANDROID_TV_REMOTE_SERVICE_UUID =
        UUID.fromString("AB5E0001-5A21-4F05-BC7D-AF01F617B664")

    private val FIDO_SERVICE_UUID = UUID.fromString("0000FFFD-0000-1000-8000-00805F9B34FB") // U2F

    private val LE_AUDIO_SERVICE_UUIDS =
        setOf(
            UUID.fromString("00001844-0000-1000-8000-00805F9B34FB"), // VCS
            UUID.fromString("00001845-0000-1000-8000-00805F9B34FB"), // VOCS
            UUID.fromString("00001843-0000-1000-8000-00805F9B34FB"), // AICS
            UUID.fromString("00001850-0000-1000-8000-00805F9B34FB"), // PACS
            UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB"), // ASCS
            UUID.fromString("0000184F-0000-1000-8000-00805F9B34FB"), // BASS
            UUID.fromString("00001854-0000-1000-8000-00805F9B34FB"), // HAP
            UUID.fromString("00001846-0000-1000-8000-00805F9B34FB"), // CSIS
        )

    private val APPLE_NOTIFICATION_CENTER_SERVICE_UUID =
        UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")

    @JvmStatic fun isHidSrvcUuid(uuid: UUID) = uuid == HID_SERVICE_UUID

    @JvmStatic fun isHidCharUuid(uuid: UUID) = uuid in HID_UUIDS

    @JvmStatic fun isAndroidTvRemoteSrvcUuid(uuid: UUID) = uuid == ANDROID_TV_REMOTE_SERVICE_UUID

    @JvmStatic fun isFidoSrvcUuid(uuid: UUID) = uuid == FIDO_SERVICE_UUID

    @JvmStatic fun isLeAudioSrvcUuid(uuid: UUID) = uuid in LE_AUDIO_SERVICE_UUIDS

    @JvmStatic
    fun isAppleNotificationCenterSrvcUuid(uuid: UUID) =
        uuid == APPLE_NOTIFICATION_CENTER_SERVICE_UUID
}
