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

package com.android.bluetooth.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupManager
import android.app.backup.BackupRestoreEventLogger
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.android.bluetooth.flags.Flags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BluetoothBackupAgent : BackupAgent() {
    companion object {
        private const val TAG = "BluetoothBackupAgent"
        internal const val BLUETOOTH_APM_STATE = "bluetooth_apm_state"
        internal const val BLUETOOTH_APM_USER_TOGGLED = "apm_user_toggled_bluetooth"
        internal const val BLUETOOTH_AUTO_ON = "bluetooth_automatic_turn_on"

        internal const val DATA_TYPE_SETTING = "settings"

        internal const val ERROR_WRITING_TO_SETTINGS = "writing_to_settings"

        internal const val ERROR_DATA_OBJECT_NULL = "data_object_null"

        // List of secure settings to be backed up.
        val SETTINGS_TO_SYNC =
            listOf(BLUETOOTH_APM_STATE, BLUETOOTH_APM_USER_TOGGLED, BLUETOOTH_AUTO_ON)
    }

    lateinit var mLogger: BackupRestoreEventLogger

    override fun onCreate() {
        super.onCreate()
        mLogger = BackupManager(applicationContext).getBackupRestoreEventLogger(this)
    }

    // Since the settings are read/written to SecureSettings, oldState and newState are not used
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) {
        if (!Flags.brSettings()) {
            return
        }

        if (data == null) {
            Log.e(TAG, "Unable to backup settings as data object is null")
            mLogger.logItemsBackupFailed(DATA_TYPE_SETTING, 1, ERROR_DATA_OBJECT_NULL)
            return
        }
        val dataBufStream = ByteArrayOutputStream()
        val dataOutStream = DataOutputStream(dataBufStream)

        for (setting in SETTINGS_TO_SYNC) {
            dataBufStream.reset()
            val settingVal = Settings.Secure.getInt(contentResolver, setting, 0)
            dataOutStream.writeInt(settingVal)
            Log.d(TAG, "Backing up $setting with value $settingVal")
            val buf = dataBufStream.toByteArray()
            data.writeEntityHeader(setting, buf.size)
            data.writeEntityData(buf, buf.size)
            mLogger.logItemsBackedUp(DATA_TYPE_SETTING, 1)
        }
    }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) {
        if (!Flags.brSettings()) {
            return
        }

        if (data == null) {
            Log.e(TAG, "Unable to restore settings as data object is null")
            mLogger.logItemsRestoreFailed(DATA_TYPE_SETTING, 1, ERROR_DATA_OBJECT_NULL)
            return
        }

        while (data.readNextHeader()) {
            val key = data.key
            if (key !in SETTINGS_TO_SYNC) {
                continue
            }

            val dataBuf =
                ByteArray(data.dataSize).also { data.readEntityData(it, 0, data.dataSize) }
            val byteInputStream = ByteArrayInputStream(dataBuf)
            val dataInputStream = DataInputStream(byteInputStream)
            val settingCurrentState = Settings.Secure.getInt(contentResolver, key, 0)
            val settingBackedUpState = dataInputStream.readInt()
            if (settingBackedUpState == settingCurrentState) {
                Log.d(TAG, "Skipping setting '$key' current state: $settingCurrentState")
                continue
            }

            Log.d(
                TAG,
                """
                        Setting '$key' backed up state does not match its current state, updating
                        the current state to: $settingBackedUpState
                        """,
            )

            val status = Settings.Secure.putInt(contentResolver, key, settingBackedUpState)
            if (!status) {
                Log.w(TAG, "Failed to update setting '$key'.")
                mLogger.logItemsRestoreFailed(DATA_TYPE_SETTING, 1, ERROR_WRITING_TO_SETTINGS)
            }
        }
    }
}
