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

package com.android.server.bluetooth.test

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings.Global
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.truth.content.IntentSubject.assertThat
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.BluetoothManagerServiceNew
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemProperties

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
@kotlin.time.ExperimentalTime
class BluetoothManagerServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val looper = Looper.getMainLooper()

    private val user = UserHandle.of(10)
    private val userContext = context.createContextAsUser(user, 0)

    private fun createBms() =
        BluetoothManagerServiceNew(userContext, looper, user, BluetoothComponentTest.setup(), false)

    @Test
    fun initialize_fetchProperName() = runTest {
        ShadowSystemProperties.override("ro.product.model", "")
        var m = createBms()
        assertThat(m.getName()).isEqualTo("Android")

        ShadowSystemProperties.override("ro.product.model", "product_model")
        m = createBms()
        assertThat(m.getName()).isEqualTo("product_model")

        Global.putString(context.contentResolver, Global.DEVICE_NAME, "global_name")
        m = createBms()
        assertThat(m.getName()).isEqualTo("global_name")

        ShadowSystemProperties.override("bluetooth.device.default_name", "bluetooth_default_name")
        m = createBms()
        assertThat(m.getName()).isEqualTo("bluetooth_default_name")
    }

    @Test
    fun setName_thenReboot() = runTest {
        val name = "What a wonderful name"
        var m = createBms()
        m.setName(name)
        assertThat(m.getName()).isEqualTo(name)

        // reboot
        m = createBms()
        assertThat(m.getName()).isEqualTo(name)
    }

    @Test
    fun setNameTwice_onlySentIntentOnce() = runTest {
        val name = "What a wonderful name"
        var m = createBms()
        m.setName(name)
        m.setName(name)
        assertThat(m.getName()).isEqualTo(name)
        val intentBroadcasted = shadowOf(context as ContextWrapper).getBroadcastIntentsForUser(user)
        // TODO add sendBroadcastAsUser(_, _, _, BroadcastOptions) to Robolectric framework
        // assertThat(intentBroadcasted).hasSize(1)
        // assertThat(intentBroadcasted.get(0)).run {
        //     hasAction(ACTION_LOCAL_NAME_CHANGED)
        //     extras().string(EXTRA_LOCAL_NAME).isEqualTo(name)
        // }
    }

    @Test
    fun resetName_useDefault() = runTest {
        val defaultName = "bluetooth_default_name"
        val name = "What a wonderful name"
        var m = createBms()
        m.setName(name)
        assertThat(m.getName()).isEqualTo(name)

        ShadowSystemProperties.override("bluetooth.device.default_name", defaultName)
        m.setName(null)
        assertThat(m.getName()).isEqualTo(defaultName)

        // reboot
        m = createBms()
        assertThat(m.getName()).isEqualTo(defaultName)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            BluetoothAdapterState.disableCacheForTesting = true
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            BluetoothAdapterState.disableCacheForTesting = false
        }
    }
}
