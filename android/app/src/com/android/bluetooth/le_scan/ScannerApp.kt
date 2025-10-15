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

package com.android.bluetooth.le_scan

import android.bluetooth.le.IScannerCallback
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import java.util.UUID

private const val TAG = "ScannerApp"

class ScannerApp(
    val uuid: UUID,
    val userHandle: UserHandle?, // User handle of the scanning app
    val attributionTag: String?, // Final attribution tag in chain
    val callback: IScannerCallback?,
    val info: ScanController.PendingIntentInfo?, // Context information
    val name: String, // App package name
    val appScanStats: AppScanStats,
) {
    var id = 0
    var hasLocationPermission = false
    var hasNetworkSettingsPermission = false
    var hasNetworkSetupWizardPermission = false
    var hasScanWithoutLocationPermission = false
    var hasDisavowedLocation = false
    var eligibleForSanitizedExposureNotification = false
    var associatedDevices: MutableList<String>? = null
    private var deathRecipient: ScanController.ScannerDeathRecipient? = null

    override fun toString() = "ScannerApp($name)"

    fun linkToDeath(recipient: ScanController.ScannerDeathRecipient) {
        callback?.let { cb ->
            try {
                cb.asBinder().linkToDeath(recipient, 0)
                deathRecipient = recipient
            } catch (_: RemoteException) {
                Log.e(TAG, "Unable to link deathRecipient for app id=$id")
                cleanup()
            }
        }
    }

    /** Unlink death recipient */
    fun cleanup() {
        deathRecipient?.let { recipient ->
            callback?.let { cb ->
                try {
                    cb.asBinder().unlinkToDeath(recipient, 0)
                } catch (_: NoSuchElementException) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id=$id")
                }
            }
        }
        appScanStats.isRegistered = false
    }
}
