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

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.IBluetoothGattCallback
import android.os.IInterface
import com.android.bluetooth.Util.Transport
import com.android.bluetooth.hid.HidHostService
import java.util.UUID

private const val TAG = "GattUtil"

object GattUtil {
    const val TAG_PREFIX = "BtGatt."

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

    @JvmStatic
    fun isAndroidHeadtrackerSrvcUuid(uuid: UUID) =
        HidHostService.ANDROID_HEADTRACKER_UUID.uuid == uuid

    @JvmStatic
    fun translateHciCode(code: Int) =
        when (code) {
            0 -> BluetoothStatusCodes.SUCCESS
            // Hardware Failure
            3 -> BluetoothStatusCodes.ERROR_HARDWARE_GENERIC
            // Unsupported Command Remote
            26 -> BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED
            // Insufficient Resources
            12,
            13,
            58 -> BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES
            // Invalid Parameters
            17,
            18,
            30,
            32,
            48,
            59 -> BluetoothStatusCodes.ERROR_BAD_PARAMETERS
            else -> BluetoothStatusCodes.ERROR_UNKNOWN
        }

    @JvmInline
    internal value class Status(val value: Int) {
        override fun toString() = statusToString(value)
    }

    /*
     * Print a readable version of the various status codes that can come from the stack or
     * applications.
     *
     * Codes from applications _should_ be from the public constants in BluetoothGatt. These
     * constants are a subset of the greater set of values available inside the stack at
     * system/stack/gatt/gatt_api.h's GattStatus enum.
     *
     * Code from the stack can be any value from the GattStatus enum.
     *
     * This block should be kept in sync with system/stack/gatt/gatt_api.h
     */
    @JvmStatic
    fun statusToString(status: Int) =
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS (0x00)"
            0x01 -> "GATT_INVALID_HANDLE (0x01)"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED (0x02)"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED (0x03)"
            0x04 -> "GATT_INVALID_PDU (0x04)"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ->
                "GATT_INSUFFICIENT_AUTHENTICATION (0x05)"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED (0x06)"
            BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET (0x07)"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION ->
                "GATT_INSUFFICIENT_AUTHORIZATION (0x08)"
            0x09 -> "GATT_PREPARE_Q_FULL (0x09)"
            0x0a -> "GATT_NOT_FOUND (0x0a)"
            0x0b -> "GATT_NOT_LONG (0x0b)"
            0x0c -> "GATT_INSUF_KEY_SIZE (0x0c)"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH (0x0d)"
            0x0e -> "GATT_ERR_UNLIKELY (0x0e)"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION (0x0f)"
            0x10 -> "GATT_UNSUPPORT_GRP_TYPE (0x10)"
            0x11 -> "GATT_INSUF_RESOURCE (0x11)"
            0x12 -> "GATT_DATABASE_OUT_OF_SYNC (0x12)"
            0x13 -> "GATT_VALUE_NOT_ALLOWED (0x13)"
            0x87 -> "GATT_ILLEGAL_PARAMETER (0x87)"
            0x80 -> "GATT_NO_RESOURCES (0x80)"
            0x81 -> "GATT_INTERNAL_ERROR (0x81)"
            0x82 -> "GATT_WRONG_STATE (0x82)"
            0x83 -> "GATT_DB_FULL (0x83)"
            0x84 -> "GATT_BUSY (0x84)"
            0x85 -> "GATT_ERROR (0x85)"
            0x86 -> "GATT_CMD_STARTED (0x86)"
            0x88 -> "GATT_PENDING (0x88)"
            0x89 -> "GATT_AUTH_FAIL (0x89)"
            0x8b -> "GATT_INVALID_CFG (0x8b)"
            0x8c -> "GATT_SERVICE_STARTED (0x8c)"
            0x8d -> "GATT_ENCRYPED_NO_MITM (0x8d)"
            0x8e -> "GATT_NOT_ENCRYPTED (0x8e)"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED (0x8f)"
            0x90 -> "GATT_DUP_REG (0x90)"
            0x91 -> "GATT_ALREADY_OPEN (0x91)"
            0x92 -> "GATT_CANCEL (0x92)"
            BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "GATT_CONNECTION_TIMEOUT (0x93)"
            0xFD -> "GATT_CCC_CFG_ERR (0xFD)"
            0xFE -> "GATT_PRC_IN_PROGRESS (0xFE)"
            0xFF -> "GATT_OUT_OF_RANGE (0xFF)"
            BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE (0x101)"
            else -> "UNKNOWN STATUS ($status)"
        }

    @JvmStatic
    fun dump(
        advertiseManager: AdvertiseManager,
        clientMap: ContextMap<IBluetoothGattCallback>,
        serverManager: GattServerManager,
    ) = buildString {
        appendLine("Registered App:")
        appendLine("  Client:")
        dumpMapDetails(clientMap)
        appendLine("  Server:")
        dumpMapDetails(serverManager.serverMap)
        appendLine()
        appendLine("GATT Advertiser Map:")
        advertiseManager.dump(this)
        appendLine("GATT Client Map:")
        clientMap.dump(this)
        appendLine("GATT Server Map:")
        serverManager.serverMap.dump(this)
        appendLine("GATT Handle Map:")
        serverManager.handleMap.dump(this)
    }

    private fun <C : IInterface> StringBuilder.dumpMapDetails(map: ContextMap<C>) =
        map.getAllApps().forEach { app ->
            append("    app_if: ${app.id}")
            append(", appName: ${app.name}")
            append(", transport: ${Transport(app.transport)}")
            app.tag?.let { tag -> append(", tag: $tag") }
            appendLine()
            map.getConnectionByApp(app.id).forEach { appendLine("      $it") }
        }
}
