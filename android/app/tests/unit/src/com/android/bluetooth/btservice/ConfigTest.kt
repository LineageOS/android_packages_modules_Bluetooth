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

package com.android.bluetooth.btservice

import android.bluetooth.BluetoothProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [Config]. */
@RunWith(AndroidJUnit4::class)
class ConfigTest {

    @Test
    fun setProfileEnabled() {
        val enabled = Config.getSupportedProfiles().contains(BluetoothProfile.CSIP_SET_COORDINATOR)

        Config.setProfileEnabled(BluetoothProfile.CSIP_SET_COORDINATOR, false)
        assertThat(Config.getSupportedProfiles())
            .asList()
            .doesNotContain(BluetoothProfile.CSIP_SET_COORDINATOR)

        Config.setProfileEnabled(BluetoothProfile.CSIP_SET_COORDINATOR, true)
        assertThat(Config.getSupportedProfiles())
            .asList()
            .contains(BluetoothProfile.CSIP_SET_COORDINATOR)

        Config.setProfileEnabled(BluetoothProfile.CSIP_SET_COORDINATOR, enabled)
    }
}
