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

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudioCodecConfig
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothSinkAudioPolicy
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetRemoteDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.storage.Metadata
import com.android.bluetooth.btservice.storage.MetadataDatabase
import com.android.bluetooth.storage.ActiveAudioPolicy.Type as ActiveAudioPolicy
import com.android.bluetooth.storage.HfpClientSettings.SinkAudioPolicy
import com.android.bluetooth.storage.MediaProfile.Type as MediaProfile
import com.android.bluetooth.storage.VoiceProfile.Type as VoiceProfile
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness.STRICT_STUBS

@RunWith(AndroidJUnit4::class)
@SmallTest
class MigrationFromRoomDatabaseTest {
    @get:Rule val mockitoRule = MockitoRule().strictness(STRICT_STUBS)

    @get:Rule val tempFolder = TemporaryFolder.builder().assureDeletion().build()

    @Mock private lateinit var adapterService: AdapterService

    private lateinit var context: Context
    private lateinit var migration: MigrationFromRoomDatabase
    private lateinit var dbFile: File
    private val device = getTestDevice(85)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        migration = MigrationFromRoomDatabase(adapterService)
        dbFile = tempFolder.newFile(MetadataDatabase.DATABASE_NAME)
        doReturn(dbFile).whenever(adapterService).getDatabasePath(MetadataDatabase.DATABASE_NAME)
        mockGetRemoteDevice(adapterService, device)
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun shouldMigrate_whenDbFileExists_returnsTrue() = runBlocking {
        assertThat(migration.shouldMigrate(UserStorage.getDefaultInstance())).isTrue()
    }

    @Test
    fun shouldMigrate_whenDbFileDoesNotExist_returnsFalse() = runBlocking {
        dbFile.delete()
        assertThat(migration.shouldMigrate(UserStorage.getDefaultInstance())).isFalse()
    }

    @Test
    fun cleanUp_deletesDatabaseFile() = runBlocking {
        assertThat(dbFile.exists()).isTrue()

        migration.cleanUp()

        assertThat(dbFile.exists()).isFalse()
    }

    @Test
    fun migrate_withData_mapsCorrectly(): Unit = runBlocking {
        // 1. Create a temporary Room database and populate it with sample Metadata
        val roomDb =
            Room.databaseBuilder(adapterService, MetadataDatabase::class.java, dbFile.name)
                .allowMainThreadQueries()
                .build()

        val metadata = Metadata(device.address)

        // Set some sample data in the old Metadata format
        metadata.profileConnectionPolicies.a2dp_connection_policy = CONNECTION_POLICY_ALLOWED
        metadata.profileConnectionPolicies.hfp_connection_policy = CONNECTION_POLICY_FORBIDDEN
        metadata.a2dpSupportsOptionalCodecs = BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED
        metadata.a2dpOptionalCodecsEnabled = BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED
        metadata.audioPolicyMetadata.callEstablishAudioPolicy =
            BluetoothSinkAudioPolicy.POLICY_ALLOWED
        metadata.audioPolicyMetadata.connectingTimeAudioPolicy =
            BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED
        metadata.audioPolicyMetadata.inBandRingtoneAudioPolicy =
            BluetoothSinkAudioPolicy.POLICY_UNCONFIGURED
        metadata.preferred_output_only_profile = BluetoothProfile.A2DP
        metadata.preferred_duplex_profile = BluetoothProfile.HEADSET
        metadata.active_audio_device_policy =
            BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_ACTIVE_UPON_CONNECTION
        metadata.is_preferred_microphone_for_calls = false
        metadata.key_missing_count = 2
        metadata.publicMetadata.manufacturer_name = "TestMan".toByteArray()
        metadata.last_active_time = 123L
        metadata.is_active_a2dp_device = true
        metadata.isActiveHfpDevice = true

        // Add LE Audio Codec Preferences
        val inputCodecConfig =
            BluetoothLeAudioCodecConfig.Builder()
                .setCodecType(1)
                .setCodecPriority(1)
                .setSampleRate(16000)
                .build()
        val outputCodecConfig =
            BluetoothLeAudioCodecConfig.Builder()
                .setCodecType(1)
                .setCodecPriority(1)
                .setSampleRate(48000)
                .build()
        metadata.leAudioUnicastClientInputCodecConfigPreference.list.add(inputCodecConfig)
        metadata.leAudioUnicastClientOutputCodecConfigPreference.list.add(outputCodecConfig)

        roomDb.insert(metadata)
        roomDb.close()

        // 2. Run the migrate function
        val migratedStorage = migration.migrate(UserStorage.getDefaultInstance())

        // 3. Assert that the UserStorage proto contains the expected data
        assertThat(migratedStorage.devicesMap).hasSize(1)
        val deviceProto = migratedStorage.devicesMap[device.address]!!

        // Verify Profile Connection Policies
        assertThat(deviceProto.profileConnectionPolicies.a2Dp).isEqualTo(Policy.ALLOWED)
        assertThat(deviceProto.profileConnectionPolicies.hfp).isEqualTo(Policy.FORBIDDEN)

        // Verify A2DP Settings
        assertThat(deviceProto.a2DpSettings.optionalCodecsSupported).isTrue()
        assertThat(deviceProto.a2DpSettings.optionalCodecsEnabled).isTrue()

        // Verify HFP Client Settings
        assertThat(deviceProto.hfpClientSettings.callEstablish).isEqualTo(SinkAudioPolicy.ALLOWED)
        assertThat(deviceProto.hfpClientSettings.setActiveAfterConnection)
            .isEqualTo(SinkAudioPolicy.NOT_ALLOWED)
        assertThat(deviceProto.hfpClientSettings.inBandRingtone)
            .isEqualTo(SinkAudioPolicy.UNCONFIGURED)

        // Verify LE Audio Settings
        assertThat(deviceProto.leAudioSettings.preferredOutputProfile).isEqualTo(MediaProfile.A2DP)
        assertThat(deviceProto.leAudioSettings.preferredInputProfile).isEqualTo(VoiceProfile.HFP)
        assertThat(deviceProto.leAudioSettings.activeAudioPolicy)
            .isEqualTo(ActiveAudioPolicy.ACTIVE)
        assertThat(deviceProto.leAudioSettings.codecPreferencesList).hasSize(1)
        assertThat(deviceProto.leAudioSettings.codecPreferencesList[0].input.type).isEqualTo(1)
        assertThat(deviceProto.leAudioSettings.codecPreferencesList[0].output.type).isEqualTo(1)

        // Verify Microphone Preferred for Calls and Key Missing Count
        assertThat(deviceProto.microphonePreferredForCalls).isFalse()
        assertThat(deviceProto.keyMissingCount).isEqualTo(2)

        // Verify Custom Metadata
        assertThat(deviceProto.customMetadataMap).hasSize(1)
        assertThat(
                deviceProto.customMetadataMap[BluetoothDevice.METADATA_MANUFACTURER_NAME]!!
                    .toByteArray()
            )
            .isEqualTo("TestMan".toByteArray())

        // Verify Connection Counter and Active Devices
        assertThat(deviceProto.connectionCounter).isEqualTo(123L)
        assertThat(migratedStorage.activeA2DpDevicesList).containsExactly(device.address)
        assertThat(migratedStorage.activeHfpDevicesList).containsExactly(device.address)
    }

    @Test
    fun migrate_withLocalStorage_skipsLocalStorageEntry(): Unit = runBlocking {
        // 1. Create a temporary Room database and populate it with sample Metadata
        val roomDb =
            Room.databaseBuilder(adapterService, MetadataDatabase::class.java, dbFile.name)
                .allowMainThreadQueries()
                .build()

        // A regular device metadata
        val regularMetadata = Metadata(device.address)
        regularMetadata.last_active_time = 123L
        roomDb.insert(regularMetadata)

        // The local storage metadata that should be skipped
        val localStorageMetadata = Metadata("LocalStorage")
        localStorageMetadata.last_active_time = 456L // some value to ensure it's not used
        roomDb.insert(localStorageMetadata)

        roomDb.close()

        // 2. Run the migrate function
        val migratedStorage = migration.migrate(UserStorage.getDefaultInstance())

        // 3. Assert that the UserStorage proto contains only the regular device
        assertThat(migratedStorage.devicesMap).hasSize(1)
        assertThat(migratedStorage.devicesMap).containsKey(device.address)
        assertThat(migratedStorage.devicesMap).doesNotContainKey("LocalStorage")
    }
}
