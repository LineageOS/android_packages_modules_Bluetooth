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

import android.bluetooth.BluetoothProfile.getConnectionStateName
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import com.google.common.io.BaseEncoding.base16
import com.google.protobuf.ByteString
import java.util.Locale
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.whenever

fun Intent.getBluetoothDeviceExtra(): BluetoothDevice =
    this.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)!!

object Utils {
    @JvmField val BUMBLE_DEVICE_NAME = "Bumble"

    @JvmField val BUMBLE_RANDOM_ADDRESS = "51:F7:A8:75:AC:5E"

    @JvmField val BUMBLE_IRK = base16().decode("1F66F4B5F0C742F807DD0DDBF64E9213")

    @JvmStatic
    fun addressStringFromByteString(bs: ByteString) =
        bs.toByteArray().joinToString(":") { "%02X".format(it) }

    @JvmStatic
    fun addressBytesFromString(address: String): ByteArray {
        return base16().upperCase().withSeparator(":", 2).decode(address.uppercase(Locale.US))
    }

    fun intentLogger(tag: String, intent: Intent) {
        val action = intent.getAction()
        when (action) {
            BluetoothDevice.ACTION_UUID -> {
                val uuids: Array<ParcelUuid> =
                    intent.getParcelableArrayExtra(
                        BluetoothDevice.EXTRA_UUID,
                        ParcelUuid::class.java,
                    )!!
                Log.d("intentLogger", "$tag/$action: Uuid=${uuids.contentToString()}")
            }
            BluetoothDevice.ACTION_FOUND -> {
                val device = intent.getBluetoothDeviceExtra()
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                Log.d("intentLogger", "$tag/$action: device=$device - name=$name")
            }
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = intent.getBluetoothDeviceExtra()
                val transport =
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_TRANSPORT,
                        BluetoothDevice.TRANSPORT_AUTO,
                    )
                Log.d("intentLogger", "$tag/$action: device=$device - transport=$transport")
            }
            BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED -> {
                val device = intent.getBluetoothDeviceExtra()
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR)
                val transport =
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_TRANSPORT,
                        BluetoothDevice.TRANSPORT_AUTO,
                    )
                Log.d(
                    "intentLogger",
                    "$tag/$action: Hid: device=$device - state=${getConnectionStateName(state)} - transport=$transport",
                )
            }
            else -> throw IllegalArgumentException("Missing implementation for $action")
        }
    }

    @JvmStatic
    fun setupIntentLogger(tag: String, receiver: BroadcastReceiver) {
        doAnswer { invocation ->
                intentLogger(tag, invocation.getArgument(1))
                null
            }
            .whenever(receiver)
            .onReceive(any(), any())
    }
}
