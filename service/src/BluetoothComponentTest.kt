/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may
 * obtain a copy of the License at
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

import android.bluetooth.IAdapter
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.BluetoothComponent
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

private const val PACKAGE_NAME = "com.android.bluetooth"

@RunWith(RobolectricTestRunner::class)
class BluetoothComponentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `can create instance when configuration is ready`() {
        setupBluetoothComponent(context)
        val component = BluetoothComponent(context)

        assertThat(component.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.className).isEqualTo(BluetoothComponent.ADAPTER_CLASS)
    }

    @Test
    fun `will throw exception when misconfigured`() {
        assertFailsWith<IllegalStateException> { BluetoothComponent(context) }
    }

    @Test
    fun `can create instance even when too many packages`() {
        val pm = Shadows.shadowOf(context.packageManager)

        setupBluetoothComponent(context)

        pm.setPackagesForUid(
            Process.BLUETOOTH_UID,
            "random.first.package.name",
            PACKAGE_NAME,
            "random.second.package.name",
        )

        val component = BluetoothComponent(context)

        assertThat(component.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.className).isEqualTo(BluetoothComponent.ADAPTER_CLASS)
    }

    companion object {
        internal fun setupBluetoothComponent(context: Context) {
            val pm = Shadows.shadowOf(context.packageManager)

            val componentName = ComponentName(PACKAGE_NAME, BluetoothComponent.ADAPTER_CLASS)

            val serviceInfo =
                ServiceInfo().apply {
                    this.packageName = PACKAGE_NAME
                    name = BluetoothComponent.ADAPTER_CLASS
                    applicationInfo =
                        ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
                }
            pm.addOrUpdateService(serviceInfo)

            val intentFilter = IntentFilter(IAdapter::class.java.name)
            pm.addIntentFilterForService(componentName, intentFilter)
            pm.setPackagesForUid(Process.BLUETOOTH_UID, PACKAGE_NAME)
        }
    }
}
