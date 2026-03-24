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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.bluetooth.scanningapp.extensions.toScanErrorMessage
import com.android.bluetooth.scanningapp.extensions.toScanModeString
import com.android.bluetooth.scanningapp.extensions.toast
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

@SuppressLint("MissingPermission")
class ScanSession(val id: Int, val tag: String, private val activity: MainActivity) {
    val scanResults = mutableStateListOf<ScanResult>()
    var rssiThreshold by mutableFloatStateOf(-100f)
    var batchScan by mutableStateOf(false)
    var scanMode by mutableIntStateOf(ScanSettings.SCAN_MODE_LOW_POWER)
    var isScanning by mutableStateOf(false)
    var showScanModeMenu by mutableStateOf(false)

    private val leScanner: BluetoothLeScanner? by lazy {
        val attributionContext = activity.createAttributionContext(tag)
        val bluetoothManager = attributionContext.getSystemService(BluetoothManager::class.java)
        bluetoothManager?.adapter?.bluetoothLeScanner
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
                activity.runOnUiThread {
                    activity.toast("Scan $id failed: ${errorCode.toScanErrorMessage()}")
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

    fun startScan() {
        if (!activity.checkPermissions()) return

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

    fun stopScan() {
        if (!isScanning) return
        if (!activity.checkPermissions()) return

        activity.toast("Scan $id stopped")
        isScanning = false
        leScanner?.stopScan(leScanCallback)
    }

    fun toggleScan() {
        if (isScanning) {
            stopScan()
        } else {
            if (activity.checkPermissions()) {
                startScan()
            } else {
                activity.requestPermissionsAndStartScan(this)
            }
        }
    }

    fun updateScanMode(mode: Int) {
        scanMode = mode
        showScanModeMenu = false
        if (isScanning) stopScan()
    }
}

class MainActivity : ComponentActivity() {

    val requiredPermissions =
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

    private var pendingSession: ScanSession? = null

    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissions ->
            val allPermissionsGranted =
                permissions.entries
                    .filter { !it.value }
                    .onEach { Log.w(TAG, "Permission not granted: ${it.key}") }
                    .isEmpty()

            if (allPermissionsGranted) {
                pendingSession?.startScan()
            }
            pendingSession = null
        }

    fun requestPermissionsAndStartScan(session: ScanSession) {
        pendingSession = session
        requestBluetoothPermissions.launch(requiredPermissions)
    }

    private val scanSessions = mutableStateListOf(ScanSession(0, "scanning_app_0", this))
    private var nextSessionId = 1

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
        scanSessions.forEach { it.stopScan() }
    }

    private fun addScanSession() {
        if (scanSessions.size < 5) {
            val tag = "scanning_app_$nextSessionId"
            scanSessions.add(ScanSession(nextSessionId, tag, this))
            nextSessionId++
        } else {
            toast("Maximum 5 scans allowed")
        }
    }

    private fun removeScanSession(session: ScanSession) {
        session.stopScan()
        scanSessions.remove(session)
    }

    @Preview
    @Composable
    fun MainScreen() {
        val pagerState = rememberPagerState(pageCount = { scanSessions.size })
        val scope = rememberCoroutineScope()

        Scaffold(modifier = Modifier.fillMaxSize().systemBarsPadding()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryTabRow(
                        selectedTabIndex =
                            minOf(pagerState.currentPage, scanSessions.size - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f),
                        divider = {},
                    ) {
                        scanSessions.forEachIndexed { index, session ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = "Scan ${session.id}",
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    if (scanSessions.size > 1) {
                                        IconButton(
                                            onClick = { removeScanSession(session) },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.close),
                                                contentDescription = "Remove Scan",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (scanSessions.size < 5) {
                        IconButton(
                            onClick = {
                                addScanSession()
                                scope.launch {
                                    pagerState.animateScrollToPage(scanSessions.size - 1)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.add),
                                contentDescription = "Add Scan",
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    key = { if (it < scanSessions.size) scanSessions[it].id else it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondViewportPageCount = 5,
                ) { page ->
                    if (page < scanSessions.size) {
                        ScanContent(scanSessions[page])
                    }
                }
            }
        }
    }

    @Composable
    fun ScanContent(session: ScanSession) {
        Column(modifier = Modifier.padding(8.dp).fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(session.scanResults) { result ->
                    ScanResultItem(result)
                    HorizontalDivider()
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "RSSI threshold: ${session.rssiThreshold.toInt()} dBm")
                Slider(
                    value = session.rssiThreshold,
                    onValueChange = {
                        session.rssiThreshold = it
                        if (session.isScanning) session.stopScan()
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
                        Checkbox(
                            checked = session.batchScan,
                            onCheckedChange = { session.batchScan = it },
                        )
                        Text("Batch Scan")
                    }

                    Box {
                        Button(
                            onClick = { session.showScanModeMenu = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                        ) {
                            Text(session.scanMode.toScanModeString())
                        }

                        DropdownMenu(
                            expanded = session.showScanModeMenu,
                            onDismissRequest = { session.showScanModeMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Low Power") },
                                onClick = {
                                    session.updateScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Balanced") },
                                onClick = {
                                    session.updateScanMode(ScanSettings.SCAN_MODE_BALANCED)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Low Latency") },
                                onClick = {
                                    session.updateScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                },
                            )
                        }
                    }

                    Button(
                        onClick = { session.toggleScan() },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = if (session.isScanning) Red else Blue
                            ),
                    ) {
                        Text(if (session.isScanning) "Stop Scan" else "Start Scan")
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

    fun checkPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
