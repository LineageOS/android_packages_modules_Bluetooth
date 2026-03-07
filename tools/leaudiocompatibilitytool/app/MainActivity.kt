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
package android.bluetooth.tools.leaudiocompatibilitytool.app

import android.app.Activity
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.navigation.AppNavGraph
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.theme.MyApplicationTheme
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.BluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IBluetoothManager
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.IMediaController
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.PermissionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint(ComponentActivity::class)
class MainActivity : Hilt_MainActivity() {
    @Inject lateinit var bluetoothManager: IBluetoothManager
    @Inject lateinit var mediaController: IMediaController
    @Inject lateinit var permissionManager: PermissionManager

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                permissions ->
                for ((permission, isGranted) in permissions) {
                    Log.d(PermissionManager.TAG, "$permission = $isGranted")
                }
            }
        val startBluetoothIntentForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    Log.d(BluetoothManager.TAG, "Enabled bluetooth")
                } else {
                    Toast.makeText(
                            this,
                            "This app requires bluetooth access. Please turn it on.",
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MyAppScreens()
                }
            }
        }

        try {
            permissionManager.requestPermissions(requestPermissionLauncher)
            bluetoothManager.checkAndEnableBluetooth(startBluetoothIntentForResult)
            bluetoothManager.registerBroadcastReceiver()
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    // release media player when the activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        mediaController.reset()
        bluetoothManager.unregisterBroadcastReceiver()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun MyAppScreens() {
    AppNavGraph()
}
