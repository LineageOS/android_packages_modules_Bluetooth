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

private const val TAG = "MigrationFromAccessPermissions"

class MigrationFromAccessPermissions(private val adapterService: AdapterService) :
    DataMigration<UserStorage> {

    companion object {
        const val PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE = "phonebook_access_permission"
        const val MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE = "message_access_permission"
        const val SIM_ACCESS_PERMISSION_PREFERENCE_FILE = "sim_access_permission"
    }

    private fun getSharedPrefsFile(name: String): File {
        val prefsDir = File(adapterService.applicationInfo.dataDir, "shared_prefs")
        return File(prefsDir, "$name.xml")
    }

    override suspend fun shouldMigrate(currentData: UserStorage): Boolean {
        val phonebookFile = getSharedPrefsFile(PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE)
        val messageFile = getSharedPrefsFile(MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE)
        val simFile = getSharedPrefsFile(SIM_ACCESS_PERMISSION_PREFERENCE_FILE)
        val shouldMigrate = phonebookFile.exists() || messageFile.exists() || simFile.exists()
        Log.d(TAG, "shouldMigrate: $shouldMigrate")
        return shouldMigrate
    }

    override suspend fun migrate(currentData: UserStorage): UserStorage {
        Log.i(TAG, "Migrating from legacy access permission SharedPreferences")
        val builder = currentData.toBuilder()

        migratePermissions(PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE, builder) {
            permissionsBuilder,
            permission ->
            permissionsBuilder.phonebook = permission
        }
        migratePermissions(MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE, builder) {
            permissionsBuilder,
            permission ->
            permissionsBuilder.message = permission
        }
        migratePermissions(SIM_ACCESS_PERMISSION_PREFERENCE_FILE, builder) {
            permissionsBuilder,
            permission ->
            permissionsBuilder.sim = permission
        }

        return builder.build()
    }

    private fun migratePermissions(
        prefName: String,
        userStorageBuilder: UserStorage.Builder,
        setPermission: (AccessPermission.Builder, Boolean) -> Unit,
    ) {
        val sharedPrefs =
            adapterService.getSharedPreferences(prefName, Context.MODE_PRIVATE) ?: return
        val allEntries = sharedPrefs.all

        if (allEntries.isEmpty()) {
            return
        }

        Log.d(TAG, "Migrating ${allEntries.size} entries from $prefName")

        for ((address, value) in allEntries) {
            val permission = value as? Boolean ?: continue
            val deviceBuilder =
                userStorageBuilder.devicesMap[address]?.toBuilder() ?: Device.newBuilder()
            val permissionsBuilder = deviceBuilder.permissions.toBuilder()

            setPermission(permissionsBuilder, permission)

            deviceBuilder.setPermissions(permissionsBuilder.build())
            userStorageBuilder.putDevices(address, deviceBuilder.build())
        }
    }

    override suspend fun cleanUp() {
        Log.i(TAG, "Migration finished, deleting old permission SharedPreferences")
        getSharedPrefsFile(PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE).delete()
        getSharedPrefsFile(MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE).delete()
        getSharedPrefsFile(SIM_ACCESS_PERMISSION_PREFERENCE_FILE).delete()
    }
}
