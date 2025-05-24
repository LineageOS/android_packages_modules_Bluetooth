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

import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata
import android.bluetooth.BluetoothLeAudioContentMetadata
import android.bluetooth.BluetoothLeBroadcastChannel
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.bluetooth.BluetoothLeBroadcastSubgroup
import android.os.Build
import android.util.Base64
import android.util.Log

object BluetoothLeBroadcastMetadataExt {
    private const val TAG = "BtLeBroadcastMetadataExt"

    private const val SCHEME_BT_BROADCAST_METADATA = "BLUETOOTH:UUID:184F;"

    // Data Elements for directing Broadcast Assistants
    private const val KEY_BT_BROADCAST_NAME = "BN"
    private const val KEY_BT_ADVERTISER_ADDRESS_TYPE = "AT"
    private const val KEY_BT_ADVERTISER_ADDRESS = "AD"
    private const val KEY_BT_BROADCAST_ID = "BI"
    private const val KEY_BT_BROADCAST_CODE = "BC"
    private const val KEY_BT_STREAM_METADATA = "MD"
    private const val KEY_BT_STANDARD_QUALITY = "SQ"
    private const val KEY_BT_HIGH_QUALITY = "HQ"

    // Extended Bluetooth URI Data Elements
    private const val KEY_BT_ADVERTISING_SID = "AS"
    private const val KEY_BT_PA_INTERVAL = "PI"
    private const val KEY_BT_NUM_SUBGROUPS = "NS"

    // Subgroup data elements
    private const val KEY_BTSG_BIS_SYNC = "BS"
    private const val KEY_BTSG_NUM_BISES = "NB"
    private const val KEY_BTSG_METADATA = "SM"

    // Vendor specific data, not being used
    private const val KEY_BTVSD_VENDOR_DATA = "VS"

    private const val DELIMITER_KEY_VALUE = ":"
    private const val DELIMITER_ELEMENT = ";"

    private const val SUFFIX_QR_CODE = ";;"

    // BT constants
    private const val BIS_SYNC_MAX_CHANNEL = 32
    private const val BIS_SYNC_NO_PREFERENCE = 0xFFFFFFFFu
    private const val SUBGROUP_LC3_CODEC_ID = 0x6L

    /**
     * Converts [BluetoothLeBroadcastMetadata] to QR code string.
     *
     * QR code string will prefix with "BLUETOOTH:UUID:184F".
     */
    fun BluetoothLeBroadcastMetadata.toQrCodeString(): String {
        val entries = mutableListOf<Pair<String, String>>()
        // Generate data elements for directing Broadcast Assistants
        require(this.broadcastName != null) { "Broadcast name is mandatory for QR code" }
        entries.add(
            Pair(
                KEY_BT_BROADCAST_NAME,
                Base64.encodeToString(
                    this.broadcastName?.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                ),
            )
        )
        entries.add(Pair(KEY_BT_ADVERTISER_ADDRESS_TYPE, this.sourceAddressType.toString()))
        entries.add(Pair(KEY_BT_ADVERTISER_ADDRESS, this.sourceDevice.address.replace(":", "")))
        entries.add(Pair(KEY_BT_BROADCAST_ID, String.format("%X", this.broadcastId.toLong())))
        if (this.broadcastCode != null) {
            entries.add(
                Pair(
                    KEY_BT_BROADCAST_CODE,
                    Base64.encodeToString(this.broadcastCode, Base64.NO_WRAP),
                )
            )
        }
        // Smart cast fails here.
        this.publicBroadcastMetadata
            ?.takeIf { it.rawMetadata.isNotEmpty() }
            ?.let {
                entries.add(
                    Pair(
                        KEY_BT_STREAM_METADATA,
                        Base64.encodeToString(it.rawMetadata, Base64.NO_WRAP),
                    )
                )
            }
        if (
            (this.audioConfigQuality and
                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD) != 0
        ) {
            entries.add(Pair(KEY_BT_STANDARD_QUALITY, "1"))
        }
        if (
            (this.audioConfigQuality and BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH) !=
                0
        ) {
            entries.add(Pair(KEY_BT_HIGH_QUALITY, "1"))
        }

        // Generate extended Bluetooth URI data elements
        entries.add(
            Pair(KEY_BT_ADVERTISING_SID, String.format("%X", this.sourceAdvertisingSid.toLong()))
        )
        entries.add(Pair(KEY_BT_PA_INTERVAL, String.format("%X", this.paSyncInterval.toLong())))
        entries.add(Pair(KEY_BT_NUM_SUBGROUPS, String.format("%X", this.subgroups.size.toLong())))

        for (subgroup in this.subgroups) {
            val (bisSync, bisCount) = getBisSyncFromChannels(subgroup.channels)
            entries.add(Pair(KEY_BTSG_BIS_SYNC, String.format("%X", bisSync.toLong())))
            if (bisCount > 0u) {
                entries.add(Pair(KEY_BTSG_NUM_BISES, String.format("%X", bisCount.toLong())))
            }
            if (subgroup.contentMetadata.rawMetadata.isNotEmpty()) {
                entries.add(
                    Pair(
                        KEY_BTSG_METADATA,
                        Base64.encodeToString(subgroup.contentMetadata.rawMetadata, Base64.NO_WRAP),
                    )
                )
            }
        }

        val qrCodeString =
            SCHEME_BT_BROADCAST_METADATA +
                entries.toQrCodeString(DELIMITER_ELEMENT) +
                SUFFIX_QR_CODE
        Log.d(TAG, "Generated QR string : $qrCodeString")
        return qrCodeString
    }

    /**
     * Converts QR code string to [BluetoothLeBroadcastMetadata].
     *
     * QR code string should prefix with "BLUETOOTH:UUID:184F".
     */
    fun convertToBroadcastMetadata(qrCodeString: String): BluetoothLeBroadcastMetadata? {
        if (!qrCodeString.startsWith(SCHEME_BT_BROADCAST_METADATA)) {
            Log.e(
                TAG,
                "String \"$qrCodeString\" does not begin with " +
                    "\"$SCHEME_BT_BROADCAST_METADATA\"",
            )
            return null
        }
        return try {
            Log.d(TAG, "Parsing QR string: $qrCodeString")
            val strippedString =
                qrCodeString.removePrefix(SCHEME_BT_BROADCAST_METADATA).removeSuffix(SUFFIX_QR_CODE)
            Log.d(TAG, "Stripped to: $strippedString")
            parseQrCodeToMetadata(strippedString)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot parse: $qrCodeString", e)
            null
        }
    }

    private fun List<Pair<String, String>>.toQrCodeString(delimiter: String): String {
        val entryStrings = this.map { it.first + DELIMITER_KEY_VALUE + it.second }
        return entryStrings.joinToString(separator = delimiter)
    }

    // Context is not provided, so we need to suppress the deprecation warning
    @Suppress("DEPRECATION")
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun parseQrCodeToMetadata(input: String): BluetoothLeBroadcastMetadata {
        // Split into a list of list
        val elementFields =
            input.split(DELIMITER_ELEMENT).map { it.split(DELIMITER_KEY_VALUE, limit = 2) }

        var sourceAddrType = BluetoothDevice.ADDRESS_TYPE_UNKNOWN
        var sourceAddrString: String? = null
        var sourceAdvertiserSid: Int? = null
        var broadcastId: Int? = null
        var broadcastName: String? = null
        var streamMetadata: BluetoothLeAudioContentMetadata? = null
        var paSyncInterval: Int? = null
        var broadcastCode: ByteArray? = null
        var audioConfigQualityStandard: Int? = null
        var audioConfigQualityHigh: Int? = null
        var numSubgroups: Int? = null

        // List of subgroup data
        val subgroupBisSyncList = mutableListOf<UInt>()
        val subgroupNumOfBisesList = mutableListOf<UInt>()
        val subgroupMetadataList = mutableListOf<ByteArray?>()

        val builder = BluetoothLeBroadcastMetadata.Builder()

        for (field in elementFields) {
            if (field.isEmpty()) {
                continue
            }
            val key = field[0]
            // Ignore 3rd value and after
            val value = if (field.size > 1) field[1] else ""
            when (key) {
                // Parse data elements for directing Broadcast Assistants
                KEY_BT_BROADCAST_NAME -> {
                    require(broadcastName == null) { "Duplicate broadcastName: $input" }
                    broadcastName = String(Base64.decode(value, Base64.NO_WRAP))
                }
                KEY_BT_ADVERTISER_ADDRESS_TYPE -> {
                    require(sourceAddrType == BluetoothDevice.ADDRESS_TYPE_UNKNOWN) {
                        "Duplicate sourceAddrType: $input"
                    }
                    sourceAddrType = value.toInt()
                }
                KEY_BT_ADVERTISER_ADDRESS -> {
                    require(sourceAddrString == null) { "Duplicate sourceAddr: $input" }
                    sourceAddrString = value.chunked(2).joinToString(":")
                }
                KEY_BT_BROADCAST_ID -> {
                    require(broadcastId == null) { "Duplicate broadcastId: $input" }
                    broadcastId = value.toInt(16)
                }
                KEY_BT_BROADCAST_CODE -> {
                    require(broadcastCode == null) { "Duplicate broadcastCode: $input" }
                    broadcastCode =
                        Base64.decode(
                            value.dropLastWhile { it.equals(0.toByte()) }.toByteArray(),
                            Base64.NO_WRAP,
                        )
                }
                KEY_BT_STREAM_METADATA -> {
                    require(streamMetadata == null) { "Duplicate streamMetadata $input" }
                    streamMetadata =
                        BluetoothLeAudioContentMetadata.fromRawBytes(
                            Base64.decode(value, Base64.NO_WRAP)
                        )
                }
                KEY_BT_STANDARD_QUALITY -> {
                    require(audioConfigQualityStandard == null) {
                        "Duplicate audioConfigQualityStandard: $input"
                    }
                    audioConfigQualityStandard =
                        when (value.toInt()) {
                            0 -> 0
                            else -> BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD
                        }
                }
                KEY_BT_HIGH_QUALITY -> {
                    require(audioConfigQualityHigh == null) {
                        "Duplicate audioConfigQualityHigh: $input"
                    }
                    audioConfigQualityHigh =
                        when (value.toInt()) {
                            0 -> 0
                            else -> BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH
                        }
                }

                // Parse extended Bluetooth URI data elements
                KEY_BT_ADVERTISING_SID -> {
                    require(sourceAdvertiserSid == null) { "Duplicate sourceAdvertiserSid: $input" }
                    sourceAdvertiserSid = value.toInt(16)
                }
                KEY_BT_PA_INTERVAL -> {
                    require(paSyncInterval == null) { "Duplicate paSyncInterval: $input" }
                    paSyncInterval = value.toInt(16)
                }
                KEY_BT_NUM_SUBGROUPS -> {
                    require(numSubgroups == null) { "Duplicate numSubgroups: $input" }
                    numSubgroups = value.toInt(16)
                }

                // Repeatable subgroup elements
                KEY_BTSG_BIS_SYNC -> {
                    subgroupBisSyncList.add(value.toUInt(16))
                }
                KEY_BTSG_NUM_BISES -> {
                    subgroupNumOfBisesList.add(value.toUInt(16))
                }
                KEY_BTSG_METADATA -> {
                    subgroupMetadataList.add(Base64.decode(value, Base64.NO_WRAP))
                }
            }
        }
        Log.d(
            TAG,
            "parseQrCodeToMetadata: main data elements sourceAddrType=$sourceAddrType, " +
                "sourceAddr=$sourceAddrString, sourceAdvertiserSid=$sourceAdvertiserSid, " +
                "broadcastId=$broadcastId, broadcastName=$broadcastName, " +
                "streamMetadata=${streamMetadata != null}, " +
                "paSyncInterval=$paSyncInterval, " +
                "broadcastCode=${broadcastCode?.toString(Charsets.UTF_8)}, " +
                "audioConfigQualityStandard=$audioConfigQualityStandard, " +
                "audioConfigQualityHigh=$audioConfigQualityHigh",
        )

        val adapter = BluetoothAdapter.getDefaultAdapter()
        // Check parsed elements data
        require(broadcastName != null) {
            "broadcastName($broadcastName) must present in QR code string"
        }
        val device =
            if (sourceAddrString != null) {
                require(sourceAddrType != BluetoothDevice.ADDRESS_TYPE_UNKNOWN) {
                    "sourceAddrType($sourceAddrType) must present if address present"
                }
                adapter.getRemoteLeDevice(sourceAddrString, sourceAddrType)
            } else {
                // Use placeholder device if not present
                adapter.getRemoteLeDevice("FF:FF:FF:FF:FF:FF", BluetoothDevice.ADDRESS_TYPE_RANDOM)
            }

        // add source device and set broadcast code
        val audioConfigQuality =
            BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_NONE or
                (audioConfigQualityStandard ?: 0) or
                (audioConfigQualityHigh ?: 0)

        // process subgroup data
        // metadata should include at least 1 subgroup for metadata, add a placeholder group if not
        // present
        for (i in 0 until maxOf(numSubgroups ?: 0, 1)) {
            val bisSync = subgroupBisSyncList.getOrNull(i)
            val bisNum = subgroupNumOfBisesList.getOrNull(i)
            val metadata = subgroupMetadataList.getOrNull(i)

            val channels = convertToChannels(bisSync, bisNum)
            val audioCodecConfigMetadata =
                BluetoothLeAudioCodecConfigMetadata.Builder().setAudioLocation(0).build()
            val subgroup =
                BluetoothLeBroadcastSubgroup.Builder()
                    .apply {
                        setCodecId(SUBGROUP_LC3_CODEC_ID)
                        setCodecSpecificConfig(audioCodecConfigMetadata)
                        setContentMetadata(
                            BluetoothLeAudioContentMetadata.fromRawBytes(metadata ?: ByteArray(0))
                        )
                        channels.forEach(::addChannel)
                    }
                    .build()

            Log.d(
                TAG,
                "parseQrCodeToMetadata: subgroup $i elements bisSync=$bisSync, " +
                    "bisNum=$bisNum, metadata=${metadata != null}",
            )

            builder.addSubgroup(subgroup)
        }

        builder.apply {
            setSourceDevice(device, device.addressType)
            sourceAdvertiserSid?.let { setSourceAdvertisingSid(it) }
            broadcastId?.let { setBroadcastId(it) }
            setBroadcastName(broadcastName)
            // QR code should set PBP(public broadcast profile) for auracast
            setPublicBroadcast(true)
            setPublicBroadcastMetadata(streamMetadata)
            paSyncInterval?.let { setPaSyncInterval(it) }
            setEncrypted(broadcastCode != null)
            setBroadcastCode(broadcastCode)
            // Presentation delay is unknown and not useful when adding source
            // Broadcast sink needs to sync to the Broadcast source to get presentation delay
            setPresentationDelayMicros(0)
            setAudioConfigQuality(audioConfigQuality)
        }
        return builder.build()
    }

    private fun getBisSyncFromChannels(
        channels: List<BluetoothLeBroadcastChannel>
    ): Pair<UInt, UInt> {
        val bisCount = channels.count { it.channelIndex > 0 }.toUInt()
        val selectedChannels = channels.filter { it.isSelected && it.channelIndex > 0 }
        if (selectedChannels.isEmpty()) {
            // No channel is selected means no preference on Android platform
            return Pair(BIS_SYNC_NO_PREFERENCE, bisCount)
        }
        val bisSync =
            selectedChannels
                .map { 1u shl (it.channelIndex - 1) }
                .reduce { acc, mask -> acc or mask }
        return Pair(bisSync, bisCount)
    }

    private fun convertToChannels(
        bisSync: UInt?,
        bisNum: UInt?,
    ): List<BluetoothLeBroadcastChannel> {
        Log.d(TAG, "convertToChannels: bisSync=$bisSync, bisNum=$bisNum")
        // if no BIS_SYNC or BIS_NUM available or BIS_SYNC is no preference
        // return empty channel map with one placeholder channel
        val selectedChannels = if (bisSync != null && bisNum != null) bisSync else 0u
        val channels = mutableListOf<BluetoothLeBroadcastChannel>()
        val audioCodecConfigMetadata =
            BluetoothLeAudioCodecConfigMetadata.Builder().setAudioLocation(0).build()

        if (bisSync == BIS_SYNC_NO_PREFERENCE || selectedChannels == 0u) {
            // No channel preference means no channel is selected
            // Generate one placeholder channel for metadata
            val channel =
                BluetoothLeBroadcastChannel.Builder().apply {
                    setSelected(false)
                    setChannelIndex(1)
                    setCodecMetadata(audioCodecConfigMetadata)
                }
            return listOf(channel.build())
        }

        for (i in 0 until BIS_SYNC_MAX_CHANNEL) {
            val channelMask = 1u shl i
            if ((selectedChannels and channelMask) != 0u) {
                val channel =
                    BluetoothLeBroadcastChannel.Builder().apply {
                        setSelected(true)
                        setChannelIndex(i + 1)
                        setCodecMetadata(audioCodecConfigMetadata)
                    }
                channels.add(channel.build())
            }
        }
        return channels
    }
}
