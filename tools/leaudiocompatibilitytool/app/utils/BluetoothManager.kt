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
package android.bluetooth.tools.leaudiocompatibilitytool.app.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.ServiceListener
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import com.google.common.annotations.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BluetoothManager for managing Bluetooth related operations.
 *
 * Code reference: Scanning -
 * packages/apps/Settings/src/com/android/settings/bluetooth/DeviceListPreferenceFragment.kt
 * Pairing -
 * frameworks/base/packages/SettingsLib/src/com/android/settingslib/bluetooth/CachedBluetoothDevice.java
 */
@Singleton
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
class BluetoothManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val testStateManager: TestStateManager,
) : IBluetoothManager {
    private val bluetoothAdapter: BluetoothAdapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("Bluetooth is not supported!")
    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()
    @VisibleForTesting val bondStateListeners = mutableListOf<BondStateListener>()
    @VisibleForTesting val connectionStateListeners = mutableListOf<ConnectionStateListener>()
    @VisibleForTesting val deviceFoundListeners = mutableListOf<DeviceFoundListener>()
    @VisibleForTesting val discoveryFinishedListeners = mutableListOf<DiscoveryFinishedListener>()
    override var targetDevice: List<BluetoothDevice> = listOf()
        private set

    private val bluetoothIntentReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                // Do not handle the broadcast if the assessment is not started.
                if (testStateManager.currentAssessmentId == TestStateManager.INVALID_ID) return
                when (action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device =
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            ) ?: return
                        Log.d(TAG, "device scanned -> ${device.address} type: ${device.type}")
                        if (
                            targetDevice.any { device.address.toString() == it.address.toString() }
                        ) {
                            if (
                                device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL
                            ) {
                                bluetoothAdapter.cancelDiscovery()
                                Log.d(TAG, "stop scanning because device found: ${device.address}")
                                deviceFoundListeners.forEach { it.onDeviceFound(device) }
                            } else {
                                Log.d(
                                    TAG,
                                    "continue scanning because device is not LE or dual: ${device.address}",
                                )
                            }
                        }
                    }
                    BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> {
                        val device =
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            ) ?: return
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        val isConnected = state == BluetoothProfile.STATE_CONNECTED
                        Log.d(
                            TAG,
                            "connection state changed -> ${device.address} state: ${state} isConnected: ${isConnected}",
                        )
                        connectionStateListeners.forEach {
                            it.onConnectionStateChange(device, isConnected)
                        }
                        // Check if all target devices have the same connection state.
                        checkAllTargetDeviceConnectionState()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device =
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            ) ?: return
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val isBonded = state == BluetoothDevice.BOND_BONDED
                        Log.d(
                            TAG,
                            "bond state changed -> ${device.address} state: ${state} isBonded: ${isBonded}",
                        )
                        bondStateListeners.forEach { it.onBondStateChange(device, isBonded) }
                    }
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val device =
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            ) ?: return

                        // Prevent the pairing dialog from showing up.
                        abortBroadcast()
                        device.setPairingConfirmation(true)
                        Log.d(TAG, "paired request -> ${device.address}")
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        discoveryFinishedListeners.forEach { it.onDiscoveryFinished() }
                    }
                }
            }
        }

    private val profileListener: ServiceListener =
        object : ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val oldPoxy: BluetoothProfile? = profileProxies[profile]
                Log.d(TAG, "connected profile -> $profile | proxy -> $proxy")
                if (oldPoxy != null && oldPoxy !== proxy) {
                    bluetoothAdapter.closeProfileProxy(profile, oldPoxy)
                }
                profileProxies[profile] = proxy
            }

            override fun onServiceDisconnected(profile: Int) {
                val proxy: BluetoothProfile = profileProxies[profile] ?: return
                // To prevent infinite recursive call, we need to delete profile first.
                profileProxies.remove(profile)
                Log.d(TAG, "delete profile -> $profile")
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }
        }

    /** Register a broadcast receiver to listen to connection state changes. */
    @SuppressLint("UnprotectedReceiver")
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    override fun registerBroadcastReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        context.registerReceiver(bluetoothIntentReceiver, filter)
    }

    /** Unregister the broadcast receiver. */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    override fun unregisterBroadcastReceiver() {
        context.unregisterReceiver(bluetoothIntentReceiver)
    }

    private fun isLeAudioSupported(): Boolean {
        return (bluetoothAdapter.isLeAudioSupported() == BluetoothStatusCodes.FEATURE_SUPPORTED)
    }

    /* Turn on Bluetooth and connect to LE_AUDIO profile */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun checkAndEnableBluetooth(
        startBluetoothIntentForResult: ActivityResultLauncher<Intent>
    ) {
        if (!bluetoothAdapter.isEnabled) {
            startBluetoothIntentForResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        if (isLeAudioSupported()) {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.LE_AUDIO)
        } else {
            throw IllegalStateException("Bluetooth LeAudio not supported.")
        }
    }

    /**
     * get all bonded devices grouped by csip group id.
     *
     * @return a map of csip group id to a list of bonded devices.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getBondedDeviceGroupedByCsipId(): Map<String, List<BluetoothDevice>> {
        val leAudioProfile = profileProxies[BluetoothProfile.LE_AUDIO] as BluetoothLeAudio?
        if (leAudioProfile == null) {
            Log.d(TAG, "getBondedDeviceGroupedByCsipId LEA profile not supported")
            return emptyMap()
        }
        return bluetoothAdapter.bondedDevices.groupBy { getCsipGroupId(leAudioProfile, it) }
    }

    /**
     * get csip group id of a device.
     *
     * @param bluetoothLeAudio BluetoothLeAudio profile proxy.
     * @param device BluetoothDevice to get csip group id.
     * @return csip group id or address (if csip group id is invalid) of the device.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun getCsipGroupId(
        bluetoothLeAudio: BluetoothLeAudio?,
        device: BluetoothDevice,
    ): String {
        if (bluetoothLeAudio == null) {
            Log.d(TAG, "getCsipGroupId LEA profile not supported")
            return BluetoothLeAudio.GROUP_ID_INVALID.toString()
        }
        val groupId = bluetoothLeAudio.getGroupId(device)
        if (groupId == BluetoothLeAudio.GROUP_ID_INVALID) {
            Log.d(TAG, "getCsipGroupId groupId is not valid")
            return device.address
        }
        return groupId.toString()
    }

    /**
     * set target devices.
     *
     * @param devices a list of BluetoothDevice to be tested.
     */
    override fun setTargetDevice(devices: List<BluetoothDevice>) {
        targetDevice = devices
    }

    /** Check if all target devices have the same connection state. */
    private fun checkAllTargetDeviceConnectionState() {
        if (targetDevice.isEmpty()) return

        try {
            val allConnected =
                isAllTargetDeviceConnectionStateEqualsTo(BluetoothProfile.STATE_CONNECTED)
            val allDisconnected =
                isAllTargetDeviceConnectionStateEqualsTo(BluetoothProfile.STATE_DISCONNECTED)

            for (listener in connectionStateListeners) {
                if (allConnected) {
                    listener.onAllDevicesConnected()
                }
                if (allDisconnected) {
                    listener.onAllDevicesDisconnected()
                }
                if (!allConnected && !allDisconnected) {
                    listener.onPartialDevicesConnected()
                }
            }
        } catch (e: IllegalStateException) {
            Log.d(TAG, "Exception: ${e.message}")
            connectionStateListeners.forEach { it.onError(e) }
        }
    }

    /**
     * Check if all target devices have the same connection state.
     *
     * @param state the target connection state.
     * @return true if all target devices have the same connection state, false otherwise.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    override fun isAllTargetDeviceConnectionStateEqualsTo(state: Int): Boolean {
        if (targetDevice.isEmpty()) return false

        // Handle the case where LE_AUDIO profile is not supported.
        if (bluetoothAdapter.isLeAudioSupported() != BluetoothStatusCodes.FEATURE_SUPPORTED) {
            Log.d(TAG, "isAllTargetDeviceConnectionStateEqualsTo LE_AUDIO profile not supported")
            throw IllegalStateException("Bluetooth LeAudio not supported.")
        }

        val leAudioProfile = profileProxies[BluetoothProfile.LE_AUDIO] as BluetoothLeAudio?
        if (leAudioProfile == null) {
            Log.d(TAG, "isAllTargetDeviceConnectionStateEqualsTo LE_AUDIO profile is null")
            throw IllegalStateException("Bluetooth LeAudio not supported.")
        }

        return targetDevice.all { leAudioProfile.getConnectionState(it) == state }
    }

    /**
     * Check if all target devices have the same bond state.
     *
     * @param state the target bond state.
     * @return true if all target devices have the same bond state, false otherwise.
     */
    override fun isAllTargetDeviceBondStateEqualsTo(state: Int): Boolean {
        if (targetDevice.isEmpty()) return false
        return targetDevice.all { it.bondState == state }
    }

    /** Start scanning for the target device. */
    override fun scanTargetDevice() {
        bluetoothAdapter.startDiscovery()
    }

    /** Cancel scanning for the target device. */
    override fun cancelDiscovery() {
        bluetoothAdapter.cancelDiscovery()
    }

    /**
     * Reset the bond state of the target device.
     *
     * @return true if the bond state is reset successfully, false otherwise.
     */
    override fun resetTargetDeviceBondState(): Boolean {
        if (targetDevice.isEmpty()) {
            Log.d(TAG, "No target device.")
            return false
        }

        // remove bond for the first device in the CSIP group that is not bonded
        return targetDevice.firstOrNull { it.bondState != BluetoothDevice.BOND_NONE }?.removeBond()
            ?: true
    }

    /**
     * Bond the target device.
     *
     * @return true if the bond state is set successfully, false otherwise.
     */
    override fun bondTargetDevice(device: BluetoothDevice): Boolean {
        Log.d(TAG, "bondTargetDevice -> ${device.address}")
        return device.createBond()
    }

    /**
     * Add a listener to be called when connection state changes.
     *
     * @param listener the listener to be called when bond state changes.
     */
    override fun addBondStateListener(listener: BondStateListener) {
        this.bondStateListeners.add(listener)
    }

    /**
     * Remove a listener from the list of listeners to be called when bond state changes.
     *
     * @param listener the listener to be removed.
     */
    override fun removeBondStateListener(listener: BondStateListener) {
        this.bondStateListeners.remove(listener)
    }

    /**
     * Add a listener to be called when device is found.
     *
     * @param listener the listener to be called when device is found.
     */
    override fun addDeviceFoundListener(listener: DeviceFoundListener) {
        this.deviceFoundListeners.add(listener)
    }

    /**
     * Remove a listener from the list of listeners to be called when device is found.
     *
     * @param listener the listener to be removed.
     */
    override fun removeDeviceFoundListener(listener: DeviceFoundListener) {
        this.deviceFoundListeners.remove(listener)
    }

    /**
     * Add a listener to be called when connection state changes.
     *
     * @param listener the listener to be called when connection state changes.
     */
    override fun addConnectionStateListener(listener: ConnectionStateListener) {
        this.connectionStateListeners.add(listener)
    }

    /**
     * Remove a listener from the list of listeners to be called when connection state changes.
     *
     * @param listener the listener to be removed.
     */
    override fun removeConnectionStateListener(listener: ConnectionStateListener) {
        this.connectionStateListeners.remove(listener)
    }

    /**
     * Add a listener to be called when discovery is finished.
     *
     * @param listener the listener to be called when discovery is finished.
     */
    override fun addDiscoveryFinishedListener(listener: DiscoveryFinishedListener) {
        this.discoveryFinishedListeners.add(listener)
    }

    /**
     * Remove a listener from the list of listeners to be called when discovery is finished.
     *
     * @param listener the listener to be removed.
     */
    override fun removeDiscoveryFinishedListener(listener: DiscoveryFinishedListener) {
        this.discoveryFinishedListeners.remove(listener)
    }

    /**
     * Remove all listeners, including connection state change, bond state change and device found.
     */
    override fun removeAllListeners() {
        this.connectionStateListeners.clear()
        this.bondStateListeners.clear()
        this.deviceFoundListeners.clear()
        this.discoveryFinishedListeners.clear()
    }

    companion object {
        const val TAG = "Bluetooth Testing"

        /**
         * Returns the string for the bond state.
         *
         * @param state the bond state.
         */
        fun getBondStateString(state: Int): String {
            return when (state) {
                BluetoothDevice.BOND_NONE -> "BOND_NONE"
                BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                else -> "UNKNOWN"
            }
        }
    }
}
