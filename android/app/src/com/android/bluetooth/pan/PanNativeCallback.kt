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

package com.android.bluetooth.pan

import android.bluetooth.BluetoothProfile
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback
import com.android.internal.annotations.VisibleForTesting

private const val TAG = "PanNativeCallback"

class PanNativeCallback(adapterService: AdapterService, private val service: PanService) :
    NativeCallback(adapterService) {

    fun onControlStateChanged(localRole: Int, halState: Int, error: Int, ifname: String) {
        service.onControlStateChanged(localRole, convertHalState(halState), error, ifname)
    }

    fun onConnectStateChanged(
        address: ByteArray,
        halState: Int,
        error: Int,
        localRole: Int,
        remoteRole: Int,
    ) {
        service.onConnectStateChanged(
            address,
            convertHalState(halState),
            error,
            localRole,
            remoteRole,
        )
    }

    companion object {
        // Constants matching Hal header file bt_hh.h: bthh_connection_state_t
        @VisibleForTesting const val CONN_STATE_CONNECTED: Int = 0
        @VisibleForTesting const val CONN_STATE_CONNECTING: Int = 1
        @VisibleForTesting const val CONN_STATE_DISCONNECTED: Int = 2
        @VisibleForTesting const val CONN_STATE_DISCONNECTING: Int = 3

        @VisibleForTesting
        @JvmStatic
        fun convertHalState(halState: Int): Int =
            when (halState) {
                CONN_STATE_CONNECTED -> BluetoothProfile.STATE_CONNECTED
                CONN_STATE_CONNECTING -> BluetoothProfile.STATE_CONNECTING
                CONN_STATE_DISCONNECTED -> BluetoothProfile.STATE_DISCONNECTED
                CONN_STATE_DISCONNECTING -> BluetoothProfile.STATE_DISCONNECTING
                else -> {
                    Log.e(TAG, "Invalid pan connection state: $halState")
                    BluetoothProfile.STATE_DISCONNECTED
                }
            }
    }
}
