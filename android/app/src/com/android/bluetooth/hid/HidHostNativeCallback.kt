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

package com.android.bluetooth.hid

import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.util.Log
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

private const val TAG = "HidHostNativeCallback"

class HidHostNativeCallback(adapterService: AdapterService, private val service: HidHostService) :
    NativeCallback(adapterService) {

    private fun onConnectStateChanged(
        address: ByteArray,
        addressType: Int,
        transport: Int,
        state: Int,
        status: Int,
    ) {
        Log.d(TAG, "onConnectStateChanged: state=$state")
        service.onConnectStateChanged(
            address,
            addressType,
            transport,
            convertHalState(state),
            status,
        )
    }

    private fun onGetProtocolMode(address: ByteArray, addressType: Int, transport: Int, mode: Int) {
        Log.d(TAG, "onGetProtocolMode()")
        service.onGetProtocolMode(address, addressType, transport, mode)
    }

    private fun onGetReport(
        address: ByteArray,
        addressType: Int,
        transport: Int,
        report: ByteArray,
        rptSize: Int,
    ) {
        Log.d(TAG, "onGetReport()")
        service.onGetReport(address, addressType, transport, report, rptSize)
    }

    private fun onHandshake(address: ByteArray, addressType: Int, transport: Int, status: Int) {
        Log.d(TAG, "onHandshake: status=$status")
        service.onHandshake(address, addressType, transport, status)
    }

    private fun onVirtualUnplug(address: ByteArray, addressType: Int, transport: Int, status: Int) {
        Log.d(TAG, "onVirtualUnplug: status=$status")
        service.onVirtualUnplug(address, addressType, transport, status)
    }

    private fun onGetIdleTime(address: ByteArray, addressType: Int, transport: Int, idleTime: Int) {
        Log.d(TAG, "onGetIdleTime()")
        service.onGetIdleTime(address, addressType, transport, idleTime)
    }

    companion object {
        // Constants matching Hal header file bt_hh.h
        // bthh_connection_state_t
        private const val CONN_STATE_CONNECTED = 0
        private const val CONN_STATE_CONNECTING = 1
        private const val CONN_STATE_DISCONNECTED = 2
        private const val CONN_STATE_DISCONNECTING = 3
        private const val CONN_STATE_ACCEPTING = 4

        private fun convertHalState(halState: Int): Int =
            when (halState) {
                CONN_STATE_CONNECTED -> STATE_CONNECTED
                CONN_STATE_CONNECTING -> STATE_CONNECTING
                CONN_STATE_DISCONNECTED -> STATE_DISCONNECTED
                CONN_STATE_DISCONNECTING -> STATE_DISCONNECTING
                CONN_STATE_ACCEPTING -> HidHostService.STATE_ACCEPTING
                else -> {
                    Log.e(TAG, "bad hid connection state: $halState")
                    STATE_DISCONNECTED
                }
            }
    }
}
