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
import android.content.res.Resources
import android.os.Process
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import com.android.bluetooth.flags.Flags
import com.android.server.bluetooth.BluetoothComponent
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

private const val PACKAGE_NAME = "com.android.bluetooth"

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(shadows = [ShadowBluetoothResources::class])
class BluetoothComponentTest(flags: FlagsWrapper) {
    @get:Rule val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `create instance with valid values`() {
        val component = setup()

        assertThat(component.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.className).isEqualTo(BLUETOOTH_SERVICE)
    }

    @Test(expected = IllegalStateException::class)
    fun `when missing Bluetooth package - throw exception`() {
        BluetoothComponent(context)
    }

    @EnableFlags(
        Flags.FLAG_VALIDATE_BLUETOOTH_NAME_IN_PLATFORM_CONFIG,
        android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER,
    )
    @Test(expected = IllegalStateException::class)
    fun `when system config is invalid - throw exception`() {
        setup("my.pkg.name")
    }

    @EnableFlags(
        Flags.FLAG_VALIDATE_BLUETOOTH_NAME_IN_PLATFORM_CONFIG,
        android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER,
    )
    @Test
    fun `when system config is missing - can create`() {
        setup("")
    }

    @EnableFlags(
        Flags.FLAG_VALIDATE_BLUETOOTH_NAME_IN_PLATFORM_CONFIG,
        android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER,
    )
    @Test
    fun `when system config is invalid but safe mode is on - can create`() {
        setup("my.pkg.name", true)
    }

    @Test
    fun `when UID has multiples packages - can create with valid values`() {
        setupPackage(context, false)

        // Only the last call to setPackagesForUid is taken into consideration
        shadowOf(context.packageManager)
            .setPackagesForUid(
                Process.BLUETOOTH_UID,
                "random.first.package.name",
                PACKAGE_NAME,
                "random.second.package.name",
            )

        val component = BluetoothComponent(context)

        assertThat(component.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(component.componentName.className).isEqualTo(BLUETOOTH_SERVICE)
    }

    companion object {
        const val BLUETOOTH_SERVICE = "my.awesome.bluetooth.service"

        internal fun setup(
            config_systemBluetoothStack: String = PACKAGE_NAME,
            safeMode: Boolean = false,
        ): BluetoothComponent {
            val context = ApplicationProvider.getApplicationContext<Context>()

            ShadowBluetoothResources.configValue = config_systemBluetoothStack
            setupPackage(context, safeMode)

            return BluetoothComponent(context)
        }

        private fun setupPackage(context: Context, safeMode: Boolean) {
            val pm = shadowOf(context.packageManager)
            pm.setSafeMode(safeMode)

            val componentName = ComponentName(PACKAGE_NAME, BLUETOOTH_SERVICE)

            val serviceInfo =
                ServiceInfo().apply {
                    this.packageName = PACKAGE_NAME
                    name = BLUETOOTH_SERVICE
                    applicationInfo =
                        ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
                }
            pm.addOrUpdateService(serviceInfo)

            val intentFilter = IntentFilter(IAdapter::class.java.name)
            pm.addIntentFilterForService(componentName, intentFilter)
            pm.setPackagesForUid(Process.BLUETOOTH_UID, PACKAGE_NAME)
        }

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() =
            FlagsWrapper.progressionOf(
                android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER,
                Flags.FLAG_VALIDATE_BLUETOOTH_NAME_IN_PLATFORM_CONFIG,
            )
    }
}

@Implements(Resources::class)
class ShadowBluetoothResources {
    @Implementation
    fun getIdentifier(name: String, defType: String, defPackage: String): Int {
        if (name == CONFIG_NAME && defType == CONFIG_TYPE && defPackage == CONFIG_PACKAGE) {
            return if (configValue.isNotEmpty()) FAKE_CONFIG_RES_ID else 0
        }
        // This shadow only handles the Bluetooth stack resource. Return 0 for others.
        return 0
    }

    @Implementation
    fun getString(id: Int): String {
        if (id == FAKE_CONFIG_RES_ID) {
            return configValue
        }
        throw Resources.NotFoundException("resource ID #$id not found by ShadowBluetoothResources")
    }

    companion object {
        private const val FAKE_CONFIG_RES_ID = 12345
        private const val CONFIG_NAME = "config_systemBluetoothStack"
        private const val CONFIG_TYPE = "string"
        private const val CONFIG_PACKAGE = "android"

        lateinit var configValue: String
    }
}
