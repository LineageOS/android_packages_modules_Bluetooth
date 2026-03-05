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

package android.bluetooth.helper.opp

import android.content.Context
import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.util.Log

object SenderUtils {
    fun Intent.resolveBluetoothShareActivityInfo(context: Context): Intent {
        val systemActivities =
            context.packageManager.queryIntentActivities(
                this,
                PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
            )
        val apexActivities =
            context.packageManager.queryIntentActivities(
                this,
                PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_APEX,
            )
        (systemActivities + apexActivities)
            .mapNotNull { it.activityInfo }
            .distinctBy { it.canonicalName }
            .first { it.name.contains("bluetooth") }
            .let { setClassName(it.packageName, it.name) }
        Log.i(TAG, "Resolved to ${component?.packageName}/${component?.className}")
        return this
    }

    private val ComponentInfo.canonicalName
        get() = "${packageName}:${name}"

    const val MIME_TYPE_IMAGE = "image/*"

    private const val TAG = "SenderUtils"
}
