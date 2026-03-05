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

import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher

/** Interface for BluetoothManager. */
interface IBluetoothManager {
    /**
     * The target device to be tested. It is a list because there can be multiple devices in a CSIP
     * group.
     */
    val targetDevice: List<BluetoothDevice>

    /**
     * Check if Bluetooth is enabled. If not, enable Bluetooth.
     *
     * @param startBluetoothIntentForResult the ActivityResultLauncher to launch the Bluetooth
     *   enable activity.
     */
    fun checkAndEnableBluetooth(startBluetoothIntentForResult: ActivityResultLauncher<Intent>)

    /** Register a broadcast receiver to listen to bluetooth events. */
    fun registerBroadcastReceiver()

    /** Unregister the broadcast receiver. */
    fun unregisterBroadcastReceiver()

    /**
     * Get all bonded devices grouped by csip group id.
     *
     * @return a map of csip group id to a list of bluetooth devices.
     */
    fun getBondedDeviceGroupedByCsipId(): Map<String, List<BluetoothDevice>>

    /**
     * Set the target device to be tested.
     *
     * @param devices the list of BluetoothDevice to be tested. They should be in the same CSIP
     *   group.
     */
    fun setTargetDevice(devices: List<BluetoothDevice>)

    /**
     * Check if all target devices have the same connection state.
     *
     * @param state the target connection state.
     * @return true if all target devices have the same connection state, false otherwise.
     */
    fun isAllTargetDeviceConnectionStateEqualsTo(state: Int): Boolean

    /**
     * Check if all target devices have the same bond state.
     *
     * @param state the target bond state.
     * @return true if all target devices have the same bond state, false otherwise.
     */
    fun isAllTargetDeviceBondStateEqualsTo(state: Int): Boolean

    /** Start scanning for the target device. */
    fun scanTargetDevice()

    /** Cancel scanning for the target device. */
    fun cancelDiscovery()

    /**
     * Reset the bond state of the target device to BOND_NONE.
     *
     * @return true if the removeBond() is called successfully, false otherwise.
     */
    fun resetTargetDeviceBondState(): Boolean

    /**
     * Bond the target device.
     *
     * @param device the BluetoothDevice to be bonded.
     * @return true if createBond() is called successfully, false otherwise.
     */
    fun bondTargetDevice(device: BluetoothDevice): Boolean

    /**
     * Add a listener to be called when bond state changes.
     *
     * @param listener the listener to be called when bond state changes.
     */
    fun addBondStateListener(listener: BondStateListener)

    /**
     * Remove a listener from the list of listeners to be called when bond state changes.
     *
     * @param listener the listener to be removed.
     */
    fun removeBondStateListener(listener: BondStateListener)

    /**
     * Add a listener to be called when device is found.
     *
     * @param listener the listener to be called when device is found.
     */
    fun addDeviceFoundListener(listener: DeviceFoundListener)

    /**
     * Remove a listener from the list of listeners to be called when device is found.
     *
     * @param listener the listener to be removed.
     */
    fun removeDeviceFoundListener(listener: DeviceFoundListener)

    /**
     * Add a listener to be called when connection state changes.
     *
     * @param listener the listener to be called when connection state changes.
     */
    fun addConnectionStateListener(listener: ConnectionStateListener)

    /**
     * Remove a listener from the list of listeners to be called when connection state changes.
     *
     * @param listener the listener to be removed.
     */
    fun removeConnectionStateListener(listener: ConnectionStateListener)

    /**
     * Add a listener to be called when discovery is finished.
     *
     * @param listener the listener to be called when discovery is finished.
     */
    fun addDiscoveryFinishedListener(listener: DiscoveryFinishedListener)

    /**
     * Remove a listener from the list of listeners to be called when discovery is finished.
     *
     * @param listener the listener to be removed.
     */
    fun removeDiscoveryFinishedListener(listener: DiscoveryFinishedListener)

    /**
     * Remove all listeners, including connection state change, bond state change and device found.
     */
    fun removeAllListeners()
}

/** Listener interface for discovery finished. */
interface DiscoveryFinishedListener {
    /** Called when discovery is finished. */
    fun onDiscoveryFinished()
}

/** Listener interface for device found. */
interface DeviceFoundListener {
    /**
     * Called when a device is found.
     *
     * @param device the BluetoothDevice that has been found.
     */
    fun onDeviceFound(device: BluetoothDevice)

    /** Called when a device is not found. */
    fun onDeviceNotFound()
}

/** Listener interface for bond state changes. */
interface BondStateListener {
    /**
     * Called when a device's bond state changes.
     *
     * @param device the BluetoothDevice that has bond state changed.
     * @param isBonded the new bond state of the device.
     */
    fun onBondStateChange(device: BluetoothDevice, isBonded: Boolean)
}

/** Listener interface for connection state changes. */
interface ConnectionStateListener {
    /**
     * Called when a device's connection state changes.
     *
     * @param device the BluetoothDevice that has connection state changed.
     * @param isConnected the new connection state of the device.
     */
    fun onConnectionStateChange(device: BluetoothDevice, isConnected: Boolean)

    /** Called when all devices are connected. */
    fun onAllDevicesConnected()

    /** Called when some devices are connected but not all. */
    fun onPartialDevicesConnected()

    /** Called when all devices are disconnected. */
    fun onAllDevicesDisconnected()

    /** Called when an error occurs. */
    fun onError(e: Exception)
}
