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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.storage.MigrationFromAccessPermissions.Companion.MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE as MESSAGE_FILE
import com.android.bluetooth.storage.MigrationFromAccessPermissions.Companion.PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE as PHONEBOOK_FILE
import com.android.bluetooth.storage.MigrationFromAccessPermissions.Companion.SIM_ACCESS_PERMISSION_PREFERENCE_FILE as SIM_FILE
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
class MigrationFromAccessPermissionsTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var migration: MigrationFromAccessPermissions
    private lateinit var prefsDir: File
    private val device = getTestDevice(42)

    @Before
    fun setUp() {
        migration = MigrationFromAccessPermissions(adapterService)

        // Mock the shared_prefs directory
        prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.mkdir()

        doReturn(context.applicationInfo).whenever(adapterService).applicationInfo
        doReturn(context.getSharedPreferences(PHONEBOOK_FILE, Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(PHONEBOOK_FILE, Context.MODE_PRIVATE)
        doReturn(context.getSharedPreferences(MESSAGE_FILE, Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(MESSAGE_FILE, Context.MODE_PRIVATE)
        doReturn(context.getSharedPreferences(SIM_FILE, Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(SIM_FILE, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        // Clear all shared preferences
        context.getSharedPreferences(PHONEBOOK_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(MESSAGE_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(SIM_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        prefsDir.deleteRecursively()
    }

    @Test
    fun shouldMigrate_whenNoFiles_returnsFalse() = runBlocking {
        assertThat(migration.shouldMigrate(UserStorage.getDefaultInstance())).isFalse()
    }

    @Test
    fun shouldMigrate_whenOneFileExists_returnsTrue() = runBlocking {
        // Create one of the preference files
        context
            .getSharedPreferences(PHONEBOOK_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(device.address, true)
            .commit()

        assertThat(migration.shouldMigrate(UserStorage.getDefaultInstance())).isTrue()
    }

    @Test
    fun migrate_mapsPermissionsCorrectly() = runBlocking {
        // Set up legacy shared preferences
        context
            .getSharedPreferences(PHONEBOOK_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(device.address, true)
            .commit()

        context
            .getSharedPreferences(MESSAGE_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(device.address, false)
            .commit()

        // SIM permission is not set for this device

        // Run migration
        val migratedStorage = migration.migrate(UserStorage.getDefaultInstance())

        // Verify the migrated data
        val deviceProto = migratedStorage.devicesMap[device.address]!!
        assertThat(deviceProto.permissions.phonebook).isTrue()
        assertThat(deviceProto.permissions.message).isFalse()
        assertThat(deviceProto.permissions.hasSim()).isFalse() // Should not be set
    }

    @Test
    fun cleanUp_deletesAllFiles() = runBlocking {
        // Create all preference files
        context
            .getSharedPreferences(PHONEBOOK_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(device.address, true)
            .commit()
        context
            .getSharedPreferences(MESSAGE_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(device.address, true)
            .commit()
        context
            .getSharedPreferences(SIM_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(device.address, true)
            .commit()

        val phonebookFile = File(prefsDir, "$PHONEBOOK_FILE.xml")
        val messageFile = File(prefsDir, "$MESSAGE_FILE.xml")
        val simFile = File(prefsDir, "$SIM_FILE.xml")

        assertThat(phonebookFile.exists()).isTrue()
        assertThat(messageFile.exists()).isTrue()
        assertThat(simFile.exists()).isTrue()

        migration.cleanUp()

        assertThat(phonebookFile.exists()).isFalse()
        assertThat(messageFile.exists()).isFalse()
        assertThat(simFile.exists()).isFalse()
    }
}
