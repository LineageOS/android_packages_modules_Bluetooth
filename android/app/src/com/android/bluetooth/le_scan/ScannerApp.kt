/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
    @JvmField val mUuid: UUID,
    @JvmField val mUserHandle: UserHandle?, // User handle of the scanning app
    @JvmField val mAttributionTag: String?, // Final attribution tag in chain
    @JvmField var mCallback: IScannerCallback?,
    @JvmField var mInfo: ScanController.PendingIntentInfo?, // Context information
    @JvmField val mName: String, // App package name
    @JvmField var mAppScanStats: AppScanStats,
) {
    @JvmField var mId: Int = 0
    @JvmField var mHasLocationPermission = false
    @JvmField var mHasNetworkSettingsPermission = false
    @JvmField var mHasNetworkSetupWizardPermission = false
    @JvmField var mHasScanWithoutLocationPermission = false
    @JvmField var mHasDisavowedLocation = false
    @JvmField var mEligibleForSanitizedExposureNotification = false
    @JvmField var mAssociatedDevices: MutableList<String>? = null
    private var mDeathRecipient: ScanController.ScannerDeathRecipient? = null

    fun linkToDeath(deathRecipient: ScanController.ScannerDeathRecipient) {
        mCallback?.let { callback ->
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
                mDeathRecipient = deathRecipient
            } catch (_: RemoteException) {
                Log.e(TAG, "Unable to link deathRecipient for app id=$mId")
                cleanup()
            }
        }
    }

    /** Unlink death recipient */
    fun cleanup() {
        mDeathRecipient?.let { deathRecipient ->
            mCallback?.let { callback ->
                try {
                    callback.asBinder().unlinkToDeath(deathRecipient, 0)
                } catch (_: NoSuchElementException) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id=$mId")
                }
            }
        }
        mAppScanStats.mIsRegistered = false
    }
}
