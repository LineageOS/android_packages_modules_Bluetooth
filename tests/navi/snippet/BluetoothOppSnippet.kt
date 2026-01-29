/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.io.File

@SuppressLint("MissingPermission")
class BluetoothOppSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothPackage =
        context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).first {
            it.packageName in BLUETOOTH_PACKAGES
        } ?: throw RuntimeException("Bluetooth is not installed!?")

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
    }

    /** Shares files of [filePaths] in [mimeType] over OPP. */
    @Rpc(description = "Share files over Bluetooth")
    fun oppShareFiles(filePaths: Array<String>, mimeType: String) {
        val sendIntent =
            Intent().apply {
                action = if (filePaths.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                `package` = bluetoothPackage.packageName
                type = mimeType
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (filePaths.size > 1) {
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList(filePaths.map { wrapUri(it) }),
                    )
                } else {
                    putExtra(Intent.EXTRA_STREAM, wrapUri(filePaths[0]))
                }
            }
        context.startActivity(sendIntent)
    }

    private fun wrapUri(filePath: String): Uri =
        FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, File(filePath))

    private companion object {
        val BLUETOOTH_PACKAGES = setOf("com.google.android.bluetooth", "com.android.bluetooth")
        const val FILE_PROVIDER_AUTHORITY = "com.google.android.bluetooth.snippet.FileProvider"
    }
}
