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

import android.app.PendingIntent
import android.bluetooth.le.IScannerCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.android.bluetooth.ActionOnDeathRecipient
import com.android.internal.annotations.VisibleForTesting
import java.util.UUID

private const val TAG = ScanUtil.TAG_PREFIX + "ScannerApp"

class ScannerApp(
    val appScanStats: AppScanStats,
    val uuid: UUID,
    val userHandle: UserHandle?, // User handle of the scanning app
    val attributionTag: String?, // Final attribution tag in chain
    val callback: IScannerCallback?,
    val settings: ScanSettings,
    val filters: List<ScanFilter>,
    val source: AttributionSource,
    val pendingIntent: PendingIntent?,
    val isInternal: Boolean,
) {
    var scannerId = 0
    var hasLocationPermission = false
    var hasNetworkSettingsPermission = false
    var hasNetworkSetupWizardPermission = false
    var hasScanWithoutLocationPermission = false
    var hasDisavowedLocation = false
    var eligibleForSanitizedExposureNotification = false
    var associatedDevices: MutableList<String>? = null
    @VisibleForTesting var deathRecipient: ActionOnDeathRecipient? = null

    val uid = appScanStats.uid
    val pid = appScanStats.pid
    val name = appScanStats.name

    override fun toString() = "ScannerApp(uid=$uid, name=$name)"

    fun linkToDeath(recipient: ActionOnDeathRecipient) {
        callback?.let { cb ->
            try {
                cb.asBinder().linkToDeath(recipient, 0)
                deathRecipient = recipient
            } catch (_: RemoteException) {
                Log.e(TAG, "Failed to linkToDeath for $this with scannerId=$scannerId")
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
                    Log.e(TAG, "Failed to unlinkToDeath for $this with scannerId=$scannerId")
                }
            }
        }
        appScanStats.isRegistered = false
    }
}
