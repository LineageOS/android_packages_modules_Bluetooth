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

package com.android.bluetooth.scanningapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.bluetooth.scanningapp.extensions.toScanErrorMessage
import com.android.bluetooth.scanningapp.extensions.toScanModeString
import com.android.bluetooth.scanningapp.extensions.toast

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val leScanner: BluetoothLeScanner? by lazy {
        val attributionContext = createAttributionContext(getString(R.string.attribution_tag))
        val bluetoothManager = attributionContext.getSystemService(BluetoothManager::class.java)
        bluetoothManager.adapter.bluetoothLeScanner
    }

    private val REQUIRED_PERMISSIONS =
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissions ->
            val allPermissionsGranted =
                permissions.entries
                    .filter { !it.value }
                    .onEach { Log.w(TAG, "Permission not granted: ${it.key}") }
                    .isEmpty()

            if (allPermissionsGranted) {
                startScan()
            }
        }

    private val scanResults = mutableStateListOf<ScanResult>()
    private var rssiThreshold by mutableFloatStateOf(-100f)
    private var batchScan by mutableStateOf(false)
    private var scanMode by mutableIntStateOf(ScanSettings.SCAN_MODE_LOW_POWER)
    private var isScanning by mutableStateOf(false)
    private var showScanModeMenu by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }

    @Composable
    @Preview
    fun MainScreen() {
        Scaffold(modifier = Modifier.fillMaxSize().systemBarsPadding()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).padding(8.dp).fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(scanResults) { result ->
                        ScanResultItem(result)
                        HorizontalDivider()
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "RSSI threshold: ${rssiThreshold.toInt()} dBm")
                    Slider(
                        value = rssiThreshold,
                        onValueChange = {
                            rssiThreshold = it
                            if (isScanning) stopScan()
                        },
                        valueRange = -120f..-50f,
                        steps = 13, // Calculated from stepSize 5.0
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = batchScan, onCheckedChange = { batchScan = it })
                            Text("Batch Scan")
                        }

                        Box {
                            Button(
                                onClick = { showScanModeMenu = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                            ) {
                                Text(scanMode.toScanModeString())
                            }

                            DropdownMenu(
                                expanded = showScanModeMenu,
                                onDismissRequest = { showScanModeMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Low Power") },
                                    onClick = { updateScanMode(ScanSettings.SCAN_MODE_LOW_POWER) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Balanced") },
                                    onClick = { updateScanMode(ScanSettings.SCAN_MODE_BALANCED) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Low Latency") },
                                    onClick = { updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) },
                                )
                            }
                        }

                        Button(
                            onClick = { toggleScan() },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = if (isScanning) Red else Blue
                                ),
                        ) {
                            Text(if (isScanning) "Stop Scan" else "Start Scan")
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun ScanResultItem(result: ScanResult) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = result.device?.name ?: "N/A",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Text(text = result.device?.address ?: "N/A", fontSize = 14.sp)
            Text(text = "RSSI: ${result.rssi} dBm", fontSize = 14.sp)
        }
    }

    private fun updateScanMode(mode: Int) {
        scanMode = mode
        showScanModeMenu = false
        if (isScanning) stopScan()
    }

    private fun toggleScan() {
        if (isScanning) {
            stopScan()
        } else {
            if (checkPermissions()) {
                startScan()
            } else {
                requestBluetoothPermissions.launch(REQUIRED_PERMISSIONS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!checkPermissions()) return

        scanResults.clear()

        val scanSettings =
            ScanSettings.Builder()
                .setReportDelay(if (batchScan) 5000 else 0)
                .setRssiThreshold(rssiThreshold.toInt())
                .setScanMode(scanMode)
                .build()

        isScanning = true
        leScanner?.startScan(emptyList(), scanSettings, leScanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        if (!checkPermissions()) return

        toast("Scan stopped")
        isScanning = false
        leScanner?.stopScan(leScanCallback)
    }

    private val leScanCallback: ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    toast("Scan failed: ${errorCode.toScanErrorMessage()}")
                    stopScan()
                }
            }
        }

    private fun handleScanResult(result: ScanResult) {
        val index = scanResults.indexOfFirst { it.device?.address == result.device?.address }
        if (index == -1) {
            scanResults.add(result)
        } else {
            scanResults[index] = result
        }
    }

    private fun checkPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
