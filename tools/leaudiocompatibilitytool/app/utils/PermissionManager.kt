/*
 * Copyright (C) 2026 The Android Open Source Project
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
package android.bluetooth.tools.leaudiocompatibilitytool.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PermissionManager @Inject constructor(@ApplicationContext private val context: Context) {

    private fun isPermissionGranted(permissionToCheck: String): Boolean =
        context.checkSelfPermission(permissionToCheck) == PackageManager.PERMISSION_GRANTED

    /** Request all runtime permissions when the activity starts. */
    fun requestPermissions(requestPermissionLauncher: ActivityResultLauncher<Array<String>>) {
        val allGranted = permissionsToCheck.all { isPermissionGranted(it) }
        if (!allGranted) {
            requestPermissionLauncher.launch(permissionsToCheck)
        } else {
            Log.d(TAG, "All runtime permissions are already granted.")
        }
    }

    companion object {
        const val TAG = "PermissionManager"
        val permissionsToCheck =
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO,
            )
    }
}
