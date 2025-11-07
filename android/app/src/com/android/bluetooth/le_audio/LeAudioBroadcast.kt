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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothProfile
import android.sysprop.BluetoothProperties
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.ProfileService

class LeAudioBroadcast(adapterService: AdapterService) :
    ProfileService(BluetoothProfile.LE_AUDIO_BROADCAST, adapterService) {

    override fun initBinder() = null

    override fun cleanup() {}

    companion object {
        @JvmStatic
        fun isEnabled() = BluetoothProperties.isProfileBapBroadcastSourceEnabled().orElse(false)
    }
}
