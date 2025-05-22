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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.bluetooth.snippet.Utils.postSnippetEvent
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.android.mobly.snippet.rpc.RpcOptional
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.runBlocking

// Permissions are requested with UiAutomation.
@SuppressLint("MissingPermission")
class BluetoothAdapterSnippet : Snippet {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val advertisers = mutableMapOf<String, AdvertiseCallback>()
    private val advertisingSets = mutableMapOf<String, AdvertisingSetCallback>()
    private val scanners = mutableMapOf<String, ScanCallback>()
    private val adapterState = MutableStateFlow(bluetoothAdapter.state)
    private val broadcastReceivers = mutableMapOf<String, BroadcastReceiver>()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothAdapter.ACTION_BLE_STATE_CHANGED ->
                            adapterState.tryEmit(
                                intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE,
                                    BluetoothAdapter.ERROR,
                                )
                            )
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_BLE_STATE_CHANGED),
        )
    }

    /** Resets Bluetooth, waits for auto-restart, and returns whether everything succeeds. */
    @Rpc(description = "Completely reset Bluetooth")
    fun factoryReset(): Boolean = runBlocking {
        val result = bluetoothAdapter.clearBluetooth()
        if (result) {
            adapterState.timeout(BLUETOOTH_ON_OFF_TIMEOUT).firstOrNull {
                it == BluetoothAdapter.STATE_OFF
            }
                ?: throw RuntimeException(
                    "Bluetooth isn't turned off after ${BLUETOOTH_ON_OFF_TIMEOUT}, " +
                        "final state=${BluetoothAdapter.nameForState(adapterState.value)}"
                )
            adapterState.timeout(BLUETOOTH_ON_OFF_TIMEOUT).firstOrNull {
                it == BluetoothAdapter.STATE_ON
            }
                ?: throw RuntimeException(
                    "Bluetooth isn't turned on after ${BLUETOOTH_ON_OFF_TIMEOUT}, " +
                        "final state=${BluetoothAdapter.nameForState(adapterState.value)}"
                )
            // b/266611263: Delay to initialize the Bluetooth completely and to fix flakiness
            delay(1.seconds)
        }
        result
    }

    /** Enables Bluetooth, waits for the enabled state and returns the operation result. */
    @Suppress("DEPRECATION")
    @Rpc(description = "Enable Bluetooth")
    fun enable(): Boolean = runBlocking {
        if (bluetoothAdapter.enable()) {
            // Wait for Bluetooth on
            adapterState.timeout(BLUETOOTH_ON_OFF_TIMEOUT).firstOrNull {
                it == BluetoothAdapter.STATE_ON
            }
                ?: throw RuntimeException(
                    "Bluetooth isn't turned on after ${BLUETOOTH_ON_OFF_TIMEOUT}, " +
                        "final state=${BluetoothAdapter.nameForState(adapterState.value)}"
                )
            true
        } else {
            false
        }
    }

    /** Disables Bluetooth, waits for the disabled state and returns the operation result. */
    @Suppress("DEPRECATION")
    @Rpc(description = "Disable Bluetooth")
    fun disable(): Boolean = runBlocking {
        if (bluetoothAdapter.disable()) {
            // Wait for Bluetooth off
            adapterState.timeout(BLUETOOTH_ON_OFF_TIMEOUT).firstOrNull {
                it == BluetoothAdapter.STATE_OFF
            }
                ?: throw RuntimeException(
                    "Bluetooth isn't turned off after ${BLUETOOTH_ON_OFF_TIMEOUT}, " +
                        "final state=${BluetoothAdapter.nameForState(adapterState.value)}"
                )
            true
        } else {
            false
        }
    }

    /** Creates a [BroadcastReceiver] which redirects intents to the event queue of [callbackId]. */
    @AsyncRpc(description = "Setup callbacks")
    fun adapterSetup(callbackId: String) {
        val intentFilter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED)
            }
        broadcastReceivers[callbackId] =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.i(TAG, "Receive Intent ${intent.action}")
                    val device =
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    val variant =
                        intent.getIntExtra(
                            BluetoothDevice.EXTRA_PAIRING_VARIANT,
                            BluetoothDevice.ERROR,
                        )
                    val pin =
                        intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
                    val state =
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val transport =
                        intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.ERROR)
                    when (intent.action) {
                        BluetoothDevice.ACTION_PAIRING_REQUEST ->
                            postSnippetEvent(callbackId, SnippetConstants.PAIRING_REQUEST) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_VARIANT, variant)
                                putInt(SnippetConstants.FIELD_PIN, pin)
                            }
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                            postSnippetEvent(callbackId, SnippetConstants.BOND_STATE_CHANGE) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_STATE, state)
                            }
                        BluetoothDevice.ACTION_ACL_CONNECTED ->
                            postSnippetEvent(callbackId, SnippetConstants.ACL_CONNECTED) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_TRANSPORT, transport)
                            }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                            postSnippetEvent(callbackId, SnippetConstants.ACL_DISCONNECTED) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(SnippetConstants.FIELD_TRANSPORT, transport)
                            }
                        BluetoothDevice.ACTION_FOUND ->
                            postSnippetEvent(callbackId, SnippetConstants.DEVICE_FOUND) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putString(SnippetConstants.FIELD_NAME, device?.name)
                            }
                        BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED ->
                            postSnippetEvent(callbackId, SnippetConstants.BATTERY_LEVEL_CHANGED) {
                                putString(SnippetConstants.FIELD_DEVICE, device?.address)
                                putInt(
                                    SnippetConstants.FIELD_VALUE,
                                    intent.getIntExtra(
                                        BluetoothDevice.EXTRA_BATTERY_LEVEL,
                                        BluetoothDevice.ERROR,
                                    ),
                                )
                            }
                    }
                }
            }
        context.registerReceiver(broadcastReceivers[callbackId], intentFilter)
    }

    /** Removes a [BroadcastReceiver] of [callbackId]. */
    @Rpc(description = "Teardown callbacks")
    fun adapterTeardown(callbackId: String) {
        broadcastReceivers.remove(callbackId)?.let { context.unregisterReceiver(it) }
    }

    /** Returns addresses of bonded devices. */
    @Rpc(description = "Get all bonded devices")
    fun getBondedDevices(): List<String> {
        return bluetoothAdapter.bondedDevices.map { it.address }.toList()
    }

    /** Get bond state of device [address]. */
    @Rpc(description = "Get bond state of device")
    fun getBondState(address: String): Int = bluetoothAdapter.getRemoteDevice(address).bondState

    /** Returns local Bluetooth Public Address. */
    @Rpc(description = "Get local Bluetooth public address")
    fun getAddress(): String {
        return bluetoothAdapter.address
    }

    /** Sets device of [address]'s alias name to [aliasName] and return the status. */
    @Rpc(description = "Set alias name of remote device")
    fun setAlias(address: String, aliasName: String): Int =
        bluetoothAdapter.getRemoteDevice(address).setAlias(aliasName)

    /** Gets device of [address]'s alias name. */
    @Rpc(description = "Get alias name of remote device")
    fun getAlias(address: String): String? = bluetoothAdapter.getRemoteDevice(address).alias

    /**
     * Creates bond to a remote device with [address] and [addressType] over [transport], and
     * returns true if successful.
     *
     * Note: Mobly Snippet lib cannot invoke Kotlin method with default value, and its @RpcDefault
     * annotation cannot identify Kotlin primitive types. As a workaround, we use @RpcOptional
     * annotation and pass a null value here.
     */
    @Rpc(description = "Create bond to a device")
    fun createBond(address: String, transport: Int, @RpcOptional addressType: Int?): Boolean {
        return when (transport) {
            BluetoothDevice.TRANSPORT_LE ->
                bluetoothAdapter.getRemoteLeDevice(
                    address,
                    addressType ?: BluetoothDevice.ADDRESS_TYPE_RANDOM,
                )
            BluetoothDevice.TRANSPORT_BREDR -> bluetoothAdapter.getRemoteDevice(address)
            else -> throw IllegalArgumentException("Invalid transport type $transport")
        }.createBond(transport)
    }

    @Rpc(description = "Remove bond to a device")
    fun removeBond(address: String): Boolean =
        bluetoothAdapter.getRemoteDevice(address).removeBond()

    /** Cancel a bond process to the remote device in [address]. */
    @Rpc(description = "Cancel bond to a device")
    fun cancelBond(address: String): Boolean =
        bluetoothAdapter.getRemoteDevice(address).cancelBondProcess()

    /**
     * Connects to a remote device of [address] and returns the status defined in
     * [android.bluetooth.BluetoothStatusCodes].
     */
    @Rpc(description = "Connect to a remote device")
    fun connect(address: String): Int = bluetoothAdapter.getRemoteDevice(address).connect()

    /**
     * Disconnects a remote device of [address] and returns the status defined in
     * [android.bluetooth.BluetoothStatusCodes].
     */
    @Rpc(description = "Disconnect from a remote device")
    fun disconnect(address: String): Int = bluetoothAdapter.getRemoteDevice(address).disconnect()

    /** Returns whether a device of [address] is connected on [transport]. (Android U+) */
    @Rpc(description = "Get if a device is connected on given transport (Android U+)")
    fun getDeviceConnected(address: String, transport: Int): Boolean {
        val device = bluetoothAdapter.getRemoteDevice(address)
        return if (Build.VERSION.SDK_INT >= 34) {
            device.getConnectionHandle(transport) != BluetoothDevice.ERROR
        } else {
            // Cannot precisely check connection state by transport before U
            device.isConnected
        }
    }

    /**
     * Sets Bluetooth Pairing confirmation [confirm] to a remote device with [address] and returns
     * true if successful.
     */
    @Rpc(description = "Set Bluetooth Pairing confirmation")
    fun setPairingConfirmation(address: String, confirm: Boolean): Boolean =
        bluetoothAdapter.getRemoteDevice(address).setPairingConfirmation(confirm)

    /**
     * Sets Legacy Pairing PIN Code [pin] to a remote device with [address] and returns true if
     * successful.
     */
    @Rpc(description = "Set Bluetooth Pairing PIN code")
    fun setPin(address: String, pin: String): Boolean =
        bluetoothAdapter.getRemoteDevice(address).setPin(pin)

    /**
     * Sets Bluetooth scan mode to [scanMode] and returns the status defined in
     * [android.bluetooth.BluetoothStatusCodes].
     */
    @Rpc(description = "Set Bluetooth scan mode")
    fun setScanMode(scanMode: Int): Int = bluetoothAdapter.setScanMode(scanMode)

    /** Gets Bluetooth scan mode. */
    @Rpc(description = "Get Bluetooth scan mode") fun getScanMode(): Int = bluetoothAdapter.scanMode

    /**
     * Starts a BLE advertiser using JSON parameters [advertiseSettings], [advertiseData],
     * [scanResponse], and returns the advertising cookie if advertising successfully or null if
     * failed.
     */
    @Rpc(description = "Start BLE Advertising")
    fun startAdvertising(
        advertiseSettings: AdvertiseSettings,
        @RpcOptional advertiseData: AdvertiseData?,
        @RpcOptional scanResponse: AdvertiseData?,
    ): String? {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        var cookie: String? = null

        val flow =
            callbackFlow<AdvertiseCallback?> {
                val advertiseCallback =
                    object : AdvertiseCallback() {
                        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                            super.onStartSuccess(settingsInEffect)
                            val unused = trySendBlocking(this)
                        }

                        override fun onStartFailure(errorCode: Int) {
                            super.onStartFailure(errorCode)
                            val unused = trySendBlocking(null)
                        }
                    }

                advertiser.startAdvertising(
                    advertiseSettings,
                    advertiseData,
                    scanResponse,
                    advertiseCallback,
                )
                awaitClose()
            }
        runBlocking {
            flow.timeout(ADVERTISING_START_TIMEOUT).first()?.let { callback ->
                cookie = UUID.randomUUID().toString().also { advertisers[it] = callback }
            }
        }
        return cookie
    }

    /**
     * Starts a BLE advertising set using parameters [advertiseSettings], [advertiseData],
     * [scanResponse], [periodicAdvertisingParameters], [periodicAdvertisingParameters], and returns
     * the advertising cookie if advertising successfully or null if failed.
     */
    @Rpc(description = "Start a BLE Advertising Set")
    fun startAdvertisingSet(
        advertiseSetParameters: AdvertisingSetParameters,
        @RpcOptional advertiseData: AdvertiseData?,
        @RpcOptional scanResponse: AdvertiseData?,
        @RpcOptional periodicAdvertisingParameters: PeriodicAdvertisingParameters?,
        @RpcOptional periodicAdvertisingData: AdvertiseData?,
    ): String {
        val flow =
            callbackFlow<Pair<Int, AdvertisingSetCallback>> {
                val advertisingSetCallback =
                    object : AdvertisingSetCallback() {
                        override fun onAdvertisingSetStarted(
                            advertisingSet: AdvertisingSet,
                            txPower: Int,
                            status: Int,
                        ) {
                            val unused = trySendBlocking(Pair(status, this))
                        }
                    }

                bluetoothAdapter.bluetoothLeAdvertiser.startAdvertisingSet(
                    advertiseSetParameters,
                    advertiseData,
                    scanResponse,
                    periodicAdvertisingParameters,
                    periodicAdvertisingData,
                    advertisingSetCallback,
                )
                awaitClose()
            }
        return runBlocking {
            val (status, callback) = flow.timeout(ADVERTISING_START_TIMEOUT).first()
            if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                throw RuntimeException("Unable to start advertising, status=$status")
            }
            val cookie = UUID.randomUUID().toString()
            advertisingSets[cookie] = callback
            cookie
        }
    }

    /** Stops BLE advertiser with [cookie]. */
    @Rpc(description = "Stop BLE Advertising")
    fun stopAdvertising(cookie: String) =
        advertisers.remove(cookie)?.let {
            bluetoothAdapter.bluetoothLeAdvertiser.stopAdvertising(it)
        }

    /** Stops a BLE advertising set with [cookie]. */
    @Rpc(description = "Stop BLE Advertising Set")
    fun stopAdvertisingSet(cookie: String) =
        advertisingSets.remove(cookie)?.let {
            bluetoothAdapter.bluetoothLeAdvertiser.stopAdvertisingSet(it)
        }

    /**
     * Starts a BLE scanner with [scanFilter] and [scanSettings]. The scan results will be pushed to
     * the event queue with [callbackId].
     */
    @AsyncRpc(description = "Start BLE scanning")
    fun startScanning(
        callbackId: String,
        @RpcOptional scanFilter: ScanFilter?,
        @RpcOptional scanSettings: ScanSettings?,
    ) {
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    postSnippetEvent(callbackId, SnippetConstants.SCAN_RESULT) {
                        putParcelable(SnippetConstants.SCAN_RESULT, result)
                    }
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    postSnippetEvent(callbackId, SnippetConstants.BATCH_SCAN_RESULTS) {
                        putParcelableArray(
                            SnippetConstants.BATCH_SCAN_RESULTS,
                            results.toTypedArray(),
                        )
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    Log.e(TAG, "onScanFailed $errorCode")
                }
            }

        // Mobly snippet lib cannot pass non-primitive list properly, so we only take 0 or 1 filter
        // here (and it's the most commonly used case).
        val scanFilters = listOfNotNull(scanFilter)
        scanner.startScan(
            /* filters = */ scanFilters,
            /* settings = */ scanSettings ?: ScanSettings.Builder().build(),
            /* callback = */ callback,
        )
        scanners[callbackId] = callback
    }

    /** Stop BLE scanner with [callbackId]. */
    @Rpc(description = "Stop BLE scanning")
    fun stopScanning(callbackId: String) {
        scanners[callbackId]?.let {
            bluetoothAdapter.bluetoothLeScanner.stopScan(it)
            scanners.remove(callbackId)
        } ?: throw IllegalArgumentException("Scanner with cookie $callbackId doesn't exist")
    }

    /** Starts Classic inquiry and returns true if inquiry is successfully started. */
    @Rpc(description = "Start Classic inquiry")
    fun startInquiry(): Boolean = bluetoothAdapter.startDiscovery()

    /** Stops Classic inquiry and returns true if inquiry is successfully stopped. */
    @Rpc(description = "Stop Classic inquiry")
    fun stopInquiry(): Boolean = bluetoothAdapter.cancelDiscovery()

    /**
     * Sets Phonebook Access Permission to a remote device of [address] and returns true if
     * successful.
     */
    @Rpc(description = "Set Phonebook Access Permission")
    fun setPhonebookAccessPermission(address: String, permission: Int): Boolean =
        bluetoothAdapter.getRemoteDevice(address).setPhonebookAccessPermission(permission)

    /**
     * Sets Message Access Permission to a remote device of [address] and returns true if
     * successful.
     */
    @Rpc(description = "Set Message Access Permission")
    fun setMessageAccessPermission(address: String, permission: Int): Boolean =
        bluetoothAdapter.getRemoteDevice(address).setMessageAccessPermission(permission)

    /**
     * Sets SIM Access Permission to a remote device of [address] and returns true if successful.
     */
    @Rpc(description = "Set SIM Access Permission")
    fun setSimAccessPermission(address: String, permission: Int): Boolean =
        bluetoothAdapter.getRemoteDevice(address).setSimAccessPermission(permission)

    companion object {
        const val TAG = "BluetoothAdapterSnippet"
        private val BLUETOOTH_ON_OFF_TIMEOUT = 12.seconds
        private val ADVERTISING_START_TIMEOUT = 3.seconds
    }
}
