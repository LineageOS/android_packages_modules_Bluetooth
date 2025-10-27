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
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.storage.MigrationFromAvrcpVolume.Companion.VOLUME_MAP_PREFERENCE_FILE as VOLUME_FILE
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class MigrationFromAvrcpVolumeTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var migration: MigrationFromAvrcpVolume
    private lateinit var prefsDir: File
    private val mDevice: BluetoothDevice = getTestDevice(42)

    @Before
    fun setUp() {
        migration = MigrationFromAvrcpVolume(adapterService)

        // Mock the shared_prefs directory
        prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.mkdir()

        doReturn(context.applicationInfo).whenever(adapterService).applicationInfo
        doReturn(context.getSharedPreferences(VOLUME_FILE, Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(VOLUME_FILE, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        // Clear all shared preferences
        context.getSharedPreferences(VOLUME_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        prefsDir.deleteRecursively()
    }

    @Test
    fun shouldMigrate_whenNoFile_returnsFalse() = runBlocking {
        assertThat(migration.shouldMigrate(UserStorage.getDefaultInstance())).isFalse()
    }

    @Test
    fun shouldMigrate_whenFileExists_returnsTrue() = runBlocking {
        // Create the preference file
        context
            .getSharedPreferences(VOLUME_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(mDevice.address, 10)
            .commit()

        assertThat(migration.shouldMigrate(UserStorage.getDefaultInstance())).isTrue()
    }

    @Test
    fun migrate_mapsVolumeCorrectly() = runBlocking {
        val testVolume = 12

        // Set up legacy shared preferences
        context
            .getSharedPreferences(VOLUME_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(mDevice.address, testVolume)
            .commit()

        // Run migration
        val migratedStorage = migration.migrate(UserStorage.getDefaultInstance())

        // Verify the migrated data
        val deviceProto = migratedStorage.devicesMap[mDevice.address]!!
        assertThat(deviceProto.avrcpSettings.volume).isEqualTo(testVolume)
    }

    @Test
    fun cleanUp_deletesFile() = runBlocking {
        // Create the preference file
        context
            .getSharedPreferences(VOLUME_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(mDevice.address, 10)
            .commit()

        val volumeFile = File(prefsDir, "$VOLUME_FILE.xml")
        assertThat(volumeFile.exists()).isTrue()

        migration.cleanUp()

        assertThat(volumeFile.exists()).isFalse()
    }
}
