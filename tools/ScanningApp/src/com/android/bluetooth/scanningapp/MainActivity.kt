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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider

private const val TAG = "MainActivity"

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

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
            var allPermissionsGranted = true
            permissions.entries.forEach {
                if (!it.value) {
                    allPermissionsGranted = false
                    Log.w(TAG, "Permission not granted: ${it.key}")
                }
            }

            if (allPermissionsGranted) {
                startScan()
            }
        }

    private val scanResultAdapter = ScanResultAdapter()
    private val bluetoothLeScanner: BluetoothLeScanner by lazy {
        getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
    }

    private var isScanning = false
    private var rssiThreshold = -100f

    private lateinit var scanButton: Button
    private lateinit var scanResultsRecyclerView: RecyclerView
    private lateinit var rssiSlider: Slider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        scanButton = findViewById(R.id.scanButton)
        scanResultsRecyclerView = findViewById(R.id.scanResultsRecyclerView)
        rssiSlider = findViewById(R.id.rssiSlider)

        ViewCompat.setOnApplyWindowInsetsListener(scanButton) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = insets.bottom }

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(scanResultsRecyclerView) { v, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        rssiSlider.setLabelFormatter { value: Float -> "RSSI threshold: $value dBm" }

        rssiSlider.addOnChangeListener { slider, value, fromUser ->
            Log.d(TAG, "Slider value changed: $value")
            rssiThreshold = value
            if (isScanning) {
                stopScan()
            }
        }

        scanButton.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                if (checkPermissions()) {
                    startScan()
                } else {
                    requestPermissions()
                }
            }
        }

        scanResultsRecyclerView.adapter = scanResultAdapter
        scanResultsRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }

    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        requestBluetoothPermissions.launch(REQUIRED_PERMISSIONS)
    }

    private fun startScan() {
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        scanResultAdapter.clearResults()

        val scanFilters: List<ScanFilter> = emptyList()
        val scanSettings = ScanSettings.Builder().setRssiThreshold(rssiThreshold.toInt()).build()

        Log.i(TAG, "Scan started...")
        isScanning = true
        scanButton.text = "Stop Scan"
        scanButton.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.red))
        bluetoothLeScanner.startScan(scanFilters, scanSettings, leScanCallback)
    }

    private fun stopScan() {
        if (!isScanning) {
            Log.d(TAG, "Scan already stopped.")
            return
        }

        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val scanStoppedMessage = "Scan stopped."
        Log.i(TAG, scanStoppedMessage)
        Toast.makeText(this@MainActivity, scanStoppedMessage, Toast.LENGTH_SHORT).show()
        isScanning = false
        scanButton.text = "Start Scan"
        scanButton.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blue))
        bluetoothLeScanner.stopScan(leScanCallback)
    }

    private val leScanCallback: ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                runOnUiThread { scanResultAdapter.addScanResult(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                super.onBatchScanResults(results)
                results?.forEach { result ->
                    runOnUiThread { scanResultAdapter.addScanResult(result) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "Scan Failed with error code: $errorCode")

                runOnUiThread {
                    Toast.makeText(
                            this@MainActivity,
                            "Scan failed: ${mapScanErrorCodeToMessage(errorCode)}",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    stopScan()
                }
            }
        }

    private fun mapScanErrorCodeToMessage(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started."
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed."
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error."
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported."
            else -> "Unknown error ($errorCode)."
        }
    }
}
