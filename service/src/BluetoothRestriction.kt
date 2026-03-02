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

package com.android.server.bluetooth

import android.content.Context
import android.os.Looper
import android.os.UserManager
import com.android.bluetooth.util.registerReceiver
import com.android.internal.annotations.VisibleForTesting

private const val TAG = "BluetoothRestriction"

object BluetoothRestriction {
    @JvmStatic
    var isBluetoothAllowed: Boolean = false
        private set

    @JvmStatic
    fun initialize(context: Context, looper: Looper, onRestrictionChange: () -> Unit) {
        // DISALLOW_BLUETOOTH is a restriction on the system user, so we only need to register for
        // broadcasts to the system user.
        context.registerReceiver(looper, UserManager.ACTION_USER_RESTRICTIONS_CHANGED) { _, _ ->
            handleRestrictionChange(context, onRestrictionChange)
        }

        isBluetoothAllowed = !hasBluetoothRestriction(context)
    }

    @JvmStatic // Static for testing too
    @VisibleForTesting
    fun handleRestrictionChange(context: Context, onRestrictionChange: () -> Unit) {
        val wasBluetoothAllowed = isBluetoothAllowed
        isBluetoothAllowed = !hasBluetoothRestriction(context)

        if (isBluetoothAllowed == wasBluetoothAllowed) {
            Log.v(TAG, "onRestrictionChange: Nothing to do. isBluetoothAllowed=$isBluetoothAllowed")
            return
        }
        Log.v(TAG, "onRestrictionChange: $wasBluetoothAllowed -> $isBluetoothAllowed")
        onRestrictionChange()
    }

    private fun hasBluetoothRestriction(systemContext: Context): Boolean =
        systemContext
            .getSystemService(UserManager::class.java)
            .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH)
}
