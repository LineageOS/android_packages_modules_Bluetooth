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

package android.bluetooth

import android.bluetooth.BluetoothAdapter.STATE_OFF
import android.bluetooth.BluetoothProfile.getConnectionStateName
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.common.io.BaseEncoding.base16
import com.google.protobuf.ByteString
import java.util.Locale
import java.util.UUID
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.whenever

internal val manager: BluetoothManager by lazy {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSystemService(BluetoothManager::class.java)
}

internal val adapter: BluetoothAdapter by lazy { manager.adapter }

internal val leAdvertiser: BluetoothLeAdvertiser
    get() = adapter.bluetoothLeAdvertiser ?: error("LeAdvertiser is null. Bluetooth is off")

internal val leScanner: BluetoothLeScanner
    get() = adapter.bluetoothLeScanner ?: error("LeScanner is null. Bluetooth is off")

fun ByteString.toAddressString() = toByteArray().joinToString(":") { "%02X".format(it) }

fun String.toAddressBytes() =
    base16().upperCase().withSeparator(":", 2).decode(uppercase(Locale.US))

fun Intent.getBluetoothDeviceExtra(): BluetoothDevice =
    this.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)!!

// Prevents `ClassCastException` that occurs when the extra contains an empty list
fun Intent.getParcelUuidArray(key: String): Array<ParcelUuid> {
    @Suppress("DEPRECATION") val extras = getParcelableArrayExtra(key)
    if (extras == null || extras.isEmpty()) return emptyArray()
    return extras.mapNotNull { it as? ParcelUuid }.toTypedArray()
}

fun BroadcastReceiver.setupIntentLogger(tag: String) {
    doAnswer { invocation ->
            intentLogger(tag, invocation.getArgument(1))
            null
        }
        .whenever(this)
        .onReceive(any(), any())
}

private fun intentLogger(tag: String, intent: Intent) {
    when (val action = intent.action) {
        BluetoothAdapter.ACTION_BLE_STATE_CHANGED,
        BluetoothAdapter.ACTION_STATE_CHANGED -> {
            val fromState =
                BluetoothAdapter.nameForState(
                    intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, STATE_OFF)
                )
            val toState =
                BluetoothAdapter.nameForState(
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, STATE_OFF)
                )
            Log.d("intentLogger", "$tag/$action $fromState -> $toState")
        }
        BluetoothAdapter.ACTION_DISCOVERY_STARTED,
        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> Log.d("intentLogger", "$tag/$action")
        BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
            val device = intent.getBluetoothDeviceExtra()
            val transport =
                intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
            Log.d("intentLogger", "$tag/$action: $device - transport=$transport")
        }
        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
            val device = intent.getBluetoothDeviceExtra()
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR)
            Log.d("intentLogger", "$tag/$action: $device - state=$state")
        }
        BluetoothDevice.ACTION_FOUND -> {
            val device = intent.getBluetoothDeviceExtra()
            val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            Log.d("intentLogger", "$tag/$action: $device - $name")
        }
        BluetoothDevice.ACTION_PAIRING_REQUEST -> {
            val device = intent.getBluetoothDeviceExtra()
            Log.d("intentLogger", "$tag/$action: $device")
        }
        BluetoothDevice.ACTION_UUID -> {
            val uuids = intent.getParcelUuidArray(BluetoothDevice.EXTRA_UUID)
            Log.d("intentLogger", "$tag/$action: Uuid=${uuids.contentToString()}")
        }
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
            val device = intent.getBluetoothDeviceExtra()
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.d(
                "intentLogger",
                "$tag/$action: Headset: $device - ${getConnectionStateName(state)}",
            )
        }
        BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED -> {
            val device = intent.getBluetoothDeviceExtra()
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
            val transport =
                intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
            Log.d(
                "intentLogger",
                "$tag/$action: Hid: $device - ${getConnectionStateName(state)} - transport=$transport",
            )
        }
        BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
            val device = intent.getBluetoothDeviceExtra()
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.d(
                "intentLogger",
                "$tag/$action: Headset: $device - ${getAudioConnectionStateName(state)} - $state ",
            )
        }
        else -> throw IllegalArgumentException("Missing implementation for $action")
    }
}

private fun getAudioConnectionStateName(state: Int) =
    when (state) {
        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "AUDIO_STATE_DISCONNECTED"
        BluetoothHeadset.STATE_AUDIO_CONNECTING -> "AUDIO_STATE_CONNECTING"
        BluetoothHeadset.STATE_AUDIO_CONNECTED -> "AUDIO_STATE_CONNECTED"
        else -> "STATE_UNKNOWN"
    }

object Utils {
    const val TAG = "Utils"

    const val BUMBLE_DEVICE_NAME = "Bumble"
    const val BUMBLE_DEVICE_NAME_2 = "Bumble_2"

    const val BUMBLE_RANDOM_ADDRESS = "51:F7:A8:75:AC:5E"
    const val BUMBLE_RANDOM_ADDRESS_2 = "51:F7:A8:75:AC:5F"

    val BUMBLE_IRK = base16().decode("1F66F4B5F0C742F807DD0DDBF64E9213")

    fun addresStringFromBytes(b: ByteArray): String {
        val reversedBytes = b.reversedArray()
        return reversedBytes.joinToString(separator = ":") { byte -> String.format("%02X", byte) }
    }

    fun uuidFromString(uuidString: String): UUID? {
        val baseUuidPostfix = "-0000-1000-8000-00805F9B34FB"
        return when (uuidString.length) {
            4 -> {
                UUID.fromString("0000$uuidString$baseUuidPostfix")
            }
            8 -> {
                UUID.fromString("$uuidString$baseUuidPostfix")
            }
            36 -> {
                UUID.fromString(uuidString)
            }
            else -> {
                Log.w(TAG, "Invalid UUID string. Returning null")
                null
            }
        }
    }
}
