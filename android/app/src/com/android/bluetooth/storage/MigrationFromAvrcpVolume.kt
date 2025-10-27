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
import android.util.Log
import androidx.datastore.core.DataMigration
import com.android.bluetooth.btservice.AdapterService
import java.io.File

private const val TAG = "MigrationFromAvrcpVolume"

class MigrationFromAvrcpVolume(private val adapterService: AdapterService) :
    DataMigration<UserStorage> {

    companion object {
        const val VOLUME_MAP_PREFERENCE_FILE = "bluetooth_volume_map"
    }

    private fun getSharedPrefsFile(): File {
        val prefsDir = File(adapterService.applicationInfo.dataDir, "shared_prefs")
        return File(prefsDir, "$VOLUME_MAP_PREFERENCE_FILE.xml")
    }

    override suspend fun shouldMigrate(currentData: UserStorage): Boolean {
        val prefsFile = getSharedPrefsFile()
        val shouldMigrate = prefsFile.exists()
        Log.d(TAG, "shouldMigrate: $shouldMigrate")
        return shouldMigrate
    }

    override suspend fun migrate(currentData: UserStorage): UserStorage {
        Log.i(TAG, "Migrating from legacy AVRCP volume SharedPreferences")
        val builder = currentData.toBuilder()

        val sharedPrefs =
            adapterService.getSharedPreferences(VOLUME_MAP_PREFERENCE_FILE, Context.MODE_PRIVATE)
                ?: return builder.build()
        val allEntries = sharedPrefs.all

        if (allEntries.isEmpty()) {
            return builder.build()
        }

        Log.d(TAG, "Migrating ${allEntries.size} entries from $VOLUME_MAP_PREFERENCE_FILE")

        for ((address, value) in allEntries) {
            val volume = value as? Int ?: continue
            val deviceBuilder = builder.devicesMap[address]?.toBuilder() ?: Device.newBuilder()

            val avrcpSettingsBuilder = deviceBuilder.avrcpSettings.toBuilder()
            avrcpSettingsBuilder.volume = volume
            deviceBuilder.setAvrcpSettings(avrcpSettingsBuilder.build())

            builder.putDevices(address, deviceBuilder.build())
        }

        return builder.build()
    }

    override suspend fun cleanUp() {
        Log.i(TAG, "Migration finished, deleting old AVRCP volume SharedPreferences")
        getSharedPrefsFile().delete()
    }
}
