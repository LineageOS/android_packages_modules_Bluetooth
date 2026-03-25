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

package com.android.bluetooth.storage

import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.datastore.core.DataMigration
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.storage.Metadata
import com.android.bluetooth.btservice.storage.MetadataDatabase
import com.google.protobuf.ByteString

private const val TAG = "MigrationFromRoomDatabase"

class MigrationFromRoomDatabase(private val adapterService: AdapterService) :
    DataMigration<UserStorage> {

    override suspend fun shouldMigrate(currentData: UserStorage): Boolean {
        val oldDb = adapterService.getDatabasePath(MetadataDatabase.DATABASE_NAME)
        val shouldMigrate = oldDb.exists()
        Log.d(TAG, "shouldMigrate: $shouldMigrate")
        return shouldMigrate
    }

    override suspend fun migrate(currentData: UserStorage): UserStorage {
        Log.i(TAG, "Migrating from legacy Room database")

        val db = MetadataDatabase.createDatabase(adapterService)
        val metadataList: List<Metadata> =
            try {
                db.load()
            } finally {
                db.close()
            }

        if (metadataList.isEmpty()) {
            Log.i(TAG, "Legacy database is empty, nothing to migrate.")
            return currentData
        }

        val builder = currentData.toBuilder()
        var maxConnectionNumber = 0L
        val activeA2dpDevices = mutableListOf<String>()
        val activeHfpDevices = mutableListOf<String>()

        for (metadata in metadataList) {
            if (metadata.address == "LocalStorage") {
                Log.d(TAG, "Skipping legacy device: ${metadata.address}")
                continue
            }
            Log.d(TAG, "Migrating device: ${metadata.address}")
            val deviceBuilder = Device.newBuilder()

            // Migrate connection counter
            deviceBuilder.connectionCounter = metadata.last_active_time
            if (metadata.last_active_time > maxConnectionNumber) {
                maxConnectionNumber = metadata.last_active_time
            }

            // Collect active A2DP and HFP devices
            if (metadata.is_active_a2dp_device) {
                activeA2dpDevices.add(metadata.address)
            }
            if (metadata.isActiveHfpDevice) {
                activeHfpDevices.add(metadata.address)
            }

            val policiesBuilder = ProfileConnectionPolicies.newBuilder()
            metadata.profileConnectionPolicies?.let {
                policiesBuilder.a2Dp = fromConnectionPolicy(it.a2dp_connection_policy)
                policiesBuilder.a2DpSink = fromConnectionPolicy(it.a2dp_sink_connection_policy)
                policiesBuilder.bassClient = fromConnectionPolicy(it.bass_client_connection_policy)
                policiesBuilder.battery = fromConnectionPolicy(it.battery_connection_policy)
                policiesBuilder.csip =
                    fromConnectionPolicy(it.csip_set_coordinator_connection_policy)
                policiesBuilder.hap = fromConnectionPolicy(it.hap_client_connection_policy)
                policiesBuilder.hearingAid = fromConnectionPolicy(it.hearing_aid_connection_policy)
                policiesBuilder.hfp = fromConnectionPolicy(it.hfp_connection_policy)
                policiesBuilder.hfpClient = fromConnectionPolicy(it.hfp_client_connection_policy)
                policiesBuilder.hidHost = fromConnectionPolicy(it.hid_host_connection_policy)
                policiesBuilder.leAudio = fromConnectionPolicy(it.le_audio_connection_policy)
                policiesBuilder.tbs = fromConnectionPolicy(it.le_call_control_connection_policy)
                policiesBuilder.map = fromConnectionPolicy(it.map_connection_policy)
                policiesBuilder.mapClient = fromConnectionPolicy(it.map_client_connection_policy)
                policiesBuilder.pan = fromConnectionPolicy(it.pan_connection_policy)
                policiesBuilder.pbap = fromConnectionPolicy(it.pbap_connection_policy)
                policiesBuilder.pbapClient = fromConnectionPolicy(it.pbap_client_connection_policy)
                policiesBuilder.sap = fromConnectionPolicy(it.sap_connection_policy)
                policiesBuilder.vcp = fromConnectionPolicy(it.volume_control_connection_policy)
            }
            deviceBuilder.setProfileConnectionPolicies(policiesBuilder.build())

            val a2dpSettingsBuilder = A2dpSettings.newBuilder()
            fromSupported(metadata.a2dpSupportsOptionalCodecs)?.let {
                a2dpSettingsBuilder.optionalCodecsSupported = it
            }
            fromPreference(metadata.a2dpOptionalCodecsEnabled)?.let {
                a2dpSettingsBuilder.optionalCodecsEnabled = it
            }
            deviceBuilder.setA2DpSettings(a2dpSettingsBuilder.build())

            val hfpClientSettingsBuilder = HfpClientSettings.newBuilder()
            metadata.audioPolicyMetadata?.let {
                hfpClientSettingsBuilder.callEstablish =
                    fromSinkAudioPolicy(it.callEstablishAudioPolicy)
                hfpClientSettingsBuilder.setActiveAfterConnection =
                    fromSinkAudioPolicy(it.connectingTimeAudioPolicy)
                hfpClientSettingsBuilder.inBandRingtone =
                    fromSinkAudioPolicy(it.inBandRingtoneAudioPolicy)
            }
            deviceBuilder.setHfpClientSettings(hfpClientSettingsBuilder.build())

            val leAudioSettingsBuilder = LeAudioSettings.newBuilder()
            leAudioSettingsBuilder.preferredOutputProfile =
                toMediaProfile(metadata.preferred_output_only_profile)
            leAudioSettingsBuilder.preferredInputProfile =
                toVoiceProfile(metadata.preferred_duplex_profile)
            leAudioSettingsBuilder.activeAudioPolicy =
                toActiveAudioPolicy(metadata.active_audio_device_policy)

            metadata.leAudioUnicastClientInputCodecConfigPreference.list
                .filterNotNull()
                .zip(metadata.leAudioUnicastClientOutputCodecConfigPreference.list.filterNotNull())
                .forEach { (input, output) ->
                    leAudioSettingsBuilder.addCodecPreferences(
                        LeAudioCodecPreference.newBuilder()
                            .setInput(toProtoCodecConfig(input))
                            .setOutput(toProtoCodecConfig(output))
                            .build()
                    )
                }

            deviceBuilder.setLeAudioSettings(leAudioSettingsBuilder.build())

            // Migrate microphone preferred for calls and key missing count
            deviceBuilder.microphonePreferredForCalls = metadata.is_preferred_microphone_for_calls
            deviceBuilder.keyMissingCount = metadata.key_missing_count

            // Migrate custom metadata
            for (key in 0..BluetoothDevice.getMaxMetadataKey()) {
                val value = metadata.getCustomizedMeta(key)
                if (value != null) {
                    deviceBuilder.putCustomMetadata(key, ByteString.copyFrom(value))
                }
            }

            builder.putDevices(metadata.address, deviceBuilder.build())
        }

        builder.addAllActiveA2DpDevices(activeA2dpDevices)
        builder.addAllActiveHfpDevices(activeHfpDevices)

        return builder.build()
    }

    override suspend fun cleanUp() {
        Log.i(TAG, "Migration finished, deleting old database")
        val oldDb = adapterService.getDatabasePath(MetadataDatabase.DATABASE_NAME)
        if (oldDb.exists()) {
            if (oldDb.delete()) {
                Log.i(TAG, "Old database deleted successfully")
            } else {
                Log.e(TAG, "Failed to delete old database")
            }
        }
    }
}
