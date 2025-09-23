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

package com.android.server.bluetooth.test

import android.bluetooth.IAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.BluetoothComponent
import com.android.server.bluetooth.SharingRestriction
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSystemProperties

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class SharingRestrictionTest {
    private val looper = Looper.getMainLooper()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val userManager = context.getSystemService(UserManager::class.java)
    private val user = UserHandle.of(0)
    private lateinit var bluetoothComponent: BluetoothComponent
    private lateinit var restriction: SharingRestriction

    @Before
    fun setUp() {
        BluetoothRestrictionTest.setup()
        val pm = Shadows.shadowOf(context.packageManager)

        val packageName = "com.android.bluetooth"
        val className = "com.android.bluetooth.btservice.AdapterService"
        val componentName = ComponentName(packageName, className)

        val serviceInfo =
            ServiceInfo().apply {
                this.packageName = packageName
                name = className
                applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
            }
        pm.addOrUpdateService(serviceInfo)

        val intentFilter = IntentFilter(IAdapter::class.java.name)
        pm.addIntentFilterForService(componentName, intentFilter)
        pm.setPackagesForUid(Process.BLUETOOTH_UID, packageName)

        bluetoothComponent = BluetoothComponent(context)
        BluetoothRestrictionTest.allowBluetooth()
    }

    @Suppress("DEPRECATION")
    private fun setUserRestriction(
        restriction: String,
        status: Boolean,
        user: UserHandle = UserHandle.SYSTEM,
    ) {
        Shadows.shadowOf(userManager).setUserRestriction(user, restriction, status)
    }

    private fun disallowSharing() = setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, true)

    private fun allowSharing() = setUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, false)

    private fun createSharingRestriction() {
        restriction = SharingRestriction(context, looper, bluetoothComponent, user)
        Shadows.shadowOf(looper).idle()
    }

    @Test
    fun disallowUserSharing_whenAllowed_sharingIsDisableAndNoCallback() {
        createSharingRestriction()

        disallowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        Shadows.shadowOf(looper).idle()

        assertThat(restriction.sharingState).isEqualTo(COMPONENT_ENABLED_STATE_DISABLED)
    }

    @Test
    fun disallowUserSharing_whenDisallowed_doNothing() {
        BluetoothRestrictionTest.disallowBluetooth()

        createSharingRestriction()

        disallowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        Shadows.shadowOf(looper).idle()

        assertThat(restriction.sharingState).isEqualTo(COMPONENT_ENABLED_STATE_DISABLED)
    }

    @Test
    fun allowUserSharing_whenSharingDisallowed_sharingAllowedAndNoCallback() {
        ShadowSystemProperties.override("bluetooth.profile.opp.enabled", "true")
        disallowSharing()

        createSharingRestriction()

        allowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        Shadows.shadowOf(looper).idle()

        assertThat(restriction.sharingState).isEqualTo(COMPONENT_ENABLED_STATE_ENABLED)
    }

    @Test
    fun allowUserSharing_whenDisallowed_sharingStayDisableAndNoCallback() {
        ShadowSystemProperties.override("bluetooth.profile.opp.enabled", "true")
        disallowSharing()
        BluetoothRestrictionTest.disallowBluetooth()

        createSharingRestriction()

        allowSharing()
        context.sendBroadcast(Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED))
        Shadows.shadowOf(looper).idle()

        assertThat(restriction.sharingState).isEqualTo(COMPONENT_ENABLED_STATE_DISABLED)
    }
}
