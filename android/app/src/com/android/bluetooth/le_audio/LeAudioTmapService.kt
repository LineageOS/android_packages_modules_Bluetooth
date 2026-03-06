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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothProfile
import android.util.Log
import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.profile.ProfileService
import com.android.bluetooth.profile.ProfileService.IProfileServiceBinder

class LeAudioTmapService(adapterService: AdapterService) :
    ProfileService(BluetoothProfile.TMAP_SERVER, adapterService) {
    private val gattServer: LeAudioTmapGattServer

    init {
        Log.d(TAG, "LeAudioTmapService(): service is starting")
        gattServer = LeAudioTmapGattServer(LeAudioTmapGattServer.BluetoothGattServerProxy(this))
    }

    override fun initBinder(): IProfileServiceBinder? {
        return null
    }

    override fun cleanup() {
        Log.i(TAG, "Cleanup LeAudioTmapService Service")
        gattServer.close()
    }

    override fun dump(sb: StringBuilder) {
        super.dump(sb)
        ProfileService.println(sb, "roleMask: " + gattServer.getRoleMask())
    }

    companion object {
        private val TAG = Util.BT_PREFIX + LeAudioTmapService::class.java.simpleName

        @JvmStatic
        fun isEnabled(): Boolean {
            return Flags.leaudioCentralizeTmap() &&
                (LeAudioService.isEnabled() ||
                    (Flags.leaudioPeripheralFeature() && LeAudioPeripheralService.isEnabled()))
        }
    }
}
