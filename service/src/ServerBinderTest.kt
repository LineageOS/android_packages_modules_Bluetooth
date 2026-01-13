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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.Manifest.permission.DUMP
import android.Manifest.permission.LOCAL_MAC_ADDRESS
import android.app.Application
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_DISABLED
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FILTERED
import android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FULL
import android.bluetooth.IBluetoothManagerCallback
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import android.permission.PermissionManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.bluetooth.flags.Flags
import com.android.server.bluetooth.BluetoothManagerServiceApi
import com.android.server.bluetooth.ServerBinder
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Shadows.shadowOf

@SmallTest
@RunWith(ParameterizedRobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class ServerBinderTest(private val flags: FlagsWrapper) {
    @get:Rule val mSetFlagsRule = SetFlagsRule(flags.flags)

    private val callback: IBluetoothManagerCallback.Stub = mock()
    private val tokenBinder: IBinder = mock()
    private val api: BluetoothManagerServiceApi = mock()
    private val permissionManager: PermissionManager = mock()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val source = AttributionSource.myAttributionSource()
    private val looper = HandlerThread("ServerBinderTest").apply { start() }.looper

    private val userManager = context.getSystemService(UserManager::class.java)
    private lateinit var binder: ServerBinder

    @Before
    fun setUp() {
        BluetoothComponentTest.setup()
        BluetoothRestrictionTest.setup()

        doReturn(PermissionManager.PERMISSION_HARD_DENIED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())

        binder = ServerBinder(looper, api, context, permissionManager)
    }

    @Test
    fun registerAdapter() {
        binder.registerAdapter(callback)
        verify(api).registerAdapter(any())
    }

    @Test
    fun unregisterAdapter() {
        binder.unregisterAdapter(callback)
        verify(api).unregisterAdapter(any())
    }

    @Test
    fun enable() {
        grantConnect()
        binder.enable(source)
        verify(api).enable(any(), eq(source.packageName!!))

        checkDisallowed { binder.enable(source) }
    }

    @Test
    fun enableNoAutoConnect() {
        grantConnect()
        binder.enableNoAutoConnect(source)
        verify(api).enableNoAutoConnect(eq(source.packageName!!))

        checkDisallowed { binder.enableNoAutoConnect(source) }
    }

    @Test
    fun disable() {
        assertFailsWith<SecurityException> { binder.disable(source, true) }
        grantConnect()
        binder.disable(source, true)
        verify(api).disable(eq(source.packageName!!), eq(true))

        assertFailsWith<SecurityException> { binder.disable(source, false) }
        grantPrivileged()
        binder.disable(source, false)
        verify(api).disable(eq(source.packageName!!), eq(false))

        checkDisallowed { binder.disable(source, true) }
    }

    @Test
    fun getStateFromSystemServer() {
        binder.getState()
    }

    @Test
    fun isBleScanAvailable() {
        binder.isBleScanAvailable()
    }

    @Test
    fun enableBle() {
        checkDisallowed { binder.enableBle(source, tokenBinder) }
    }

    @Test
    fun factoryReset() {
        grantPrivileged()
        grantConnect()
        binder.factoryReset(source)
    }

    @Test
    fun disableBle() {
        checkDisallowed { binder.disableBle(source, tokenBinder) }
    }

    @Test
    fun isHearingAidProfileSupported() {
        binder.isHearingAidProfileSupported()
    }

    @Test
    fun setGetBtHciSnoopLogMode() {
        assertFailsWith<SecurityException> { binder.setBtHciSnoopLogMode(BT_SNOOP_LOG_MODE_FULL) }
        assertFailsWith<SecurityException> { binder.getBtHciSnoopLogMode() }

        grantPrivileged()

        binder.setBtHciSnoopLogMode(BT_SNOOP_LOG_MODE_DISABLED)
        assertThat(binder.getBtHciSnoopLogMode()).isEqualTo(BT_SNOOP_LOG_MODE_DISABLED)
        binder.setBtHciSnoopLogMode(BT_SNOOP_LOG_MODE_FILTERED)
        assertThat(binder.getBtHciSnoopLogMode()).isEqualTo(BT_SNOOP_LOG_MODE_FILTERED)
        binder.setBtHciSnoopLogMode(BT_SNOOP_LOG_MODE_FULL)
        assertThat(binder.getBtHciSnoopLogMode()).isEqualTo(BT_SNOOP_LOG_MODE_FULL)
        binder.setBtHciSnoopLogMode(99)
        assertThat(binder.getBtHciSnoopLogMode()).isEqualTo(BT_SNOOP_LOG_MODE_DISABLED)
    }

    @Test
    fun getAddress() {
        grantConnect()
        binder.getAddress(source)
        grantLocalMacAddress()
        binder.getAddress(source)
    }

    @Test
    fun name() {
        grantConnect()
        assertFailsWith<IllegalArgumentException> { binder.setName("hey".repeat(300), source) }
        binder.setName(null, source)
        binder.setName("hey", source)
        binder.getName(source)
    }

    @Test
    fun name_permissionDenied() {
        doReturn(PermissionManager.PERMISSION_SOFT_DENIED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())

        assertThat(binder.getName(source)).isNull()
        binder.setName("name", source)
    }

    @Test
    fun factoryReset_permissionDenied() {
        grantPrivileged()
        doReturn(PermissionManager.PERMISSION_SOFT_DENIED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())

        assertThat(binder.factoryReset(source)).isFalse()
    }

    @Test
    fun enable_nullPackage() {
        val nullPkgSource =
            AttributionSource.Builder(Process.SYSTEM_UID).setPackageName(null).build()

        assertFailsWith<IllegalArgumentException> { binder.enable(nullPkgSource) }
    }

    @EnableFlags(
        Flags.FLAG_REJECT_ENABLE_FROM_UNKNOWN_REQUESTER,
        android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER,
    )
    @Test
    fun enable_strictConfig() {
        grantConnect()

        val sourceNoTag =
            AttributionSource.Builder(Process.SYSTEM_UID)
                .setPackageName("android")
                .setAttributionTag(null)
                .build()

        assertFailsWith<IllegalArgumentException> { binder.enable(sourceNoTag) }

        val sourceWithTag =
            AttributionSource.Builder(Process.SYSTEM_UID)
                .setPackageName("android")
                .setAttributionTag("tag")
                .build()

        binder.enable(sourceWithTag)
        verify(api).enable(any(), eq("android/tag"))
    }

    @DisableFlags(Flags.FLAG_REJECT_ENABLE_FROM_UNKNOWN_REQUESTER)
    @Test
    fun enable_rejectUnknownRequesterDisabled() {
        grantConnect()
        val packageName = "some.pkg"
        val uid = 12345
        shadowOf(context.packageManager).setPackagesForUid(uid, packageName)
        shadowOf(context.packageManager)
            .installPackage(
                PackageInfo().apply {
                    this.packageName = packageName
                    this.applicationInfo = ApplicationInfo().apply { this.uid = uid }
                }
            )

        val source = AttributionSource.Builder(uid).setPackageName(packageName).build()

        binder.enable(source)
        verify(api).enable(any(), eq(packageName))
    }

    @Test
    fun autoOn() {
        assertFailsWith<SecurityException> { binder.isAutoOnSupported() }
        assertFailsWith<SecurityException> { binder.isAutoOnEnabled() }
        assertFailsWith<SecurityException> { binder.setAutoOnEnabled(true) }

        grantPrivileged()

        binder.isAutoOnSupported()
        binder.isAutoOnEnabled()
        binder.setAutoOnEnabled(true)
    }

    @DisableFlags(Flags.FLAG_REJECT_ENABLE_FROM_UNKNOWN_REQUESTER)
    @Test
    fun enable_rejectUnknownRequesterDisabled_nullPackage() {
        // Use SYSTEM_UID to bypass PermissionChecker's null package check
        val nullPkgSource =
            AttributionSource.Builder(Process.SYSTEM_UID).setPackageName(null).build()

        assertFailsWith<IllegalArgumentException> { binder.enable(nullPkgSource) }
    }

    @EnableFlags(Flags.FLAG_REJECT_ENABLE_FROM_UNKNOWN_REQUESTER)
    @DisableFlags(android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER)
    @Test
    fun enable_strictConfigDisabled_androidPackage() {
        grantConnect()

        val sourceNoTag =
            AttributionSource.Builder(Process.SYSTEM_UID)
                .setPackageName("android")
                .setAttributionTag(null)
                .build()

        binder.enable(sourceNoTag)
        verify(api).enable(any(), eq("android"))
    }

    @Test
    fun constructor_defaultPermissionManager() {
        assertThat(ServerBinder(looper, api, context)).isNotNull()
    }

    @Test
    fun dump() {
        val fd = FileDescriptor.out
        val args = emptyArray<String>()

        assertFailsWith<SecurityException> { binder.dump(fd, args) }

        shadowOf(application).grantPermissions(DUMP)

        binder.dump(fd, args)
        verify(api).dump(eq(fd), any(), any())
    }

    private fun checkDisallowed(binderCall: () -> Boolean) {
        BluetoothRestrictionTest.disallowBluetooth()
        assertThat(binderCall()).isFalse()
        BluetoothRestrictionTest.allowBluetooth()
    }

    private fun grantConnect() {
        shadowOf(application).grantPermissions(BLUETOOTH_CONNECT)
        doReturn(PermissionManager.PERMISSION_GRANTED)
            .whenever(permissionManager)
            .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())
    }

    private fun grantLocalMacAddress() = shadowOf(application).grantPermissions(LOCAL_MAC_ADDRESS)

    private fun grantPrivileged() = shadowOf(application).grantPermissions(BLUETOOTH_PRIVILEGED)

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() =
            FlagsWrapper.progressionOf(
                Flags.FLAG_REJECT_ENABLE_FROM_UNKNOWN_REQUESTER,
                android.bluetooth.platform.flags.Flags.FLAG_STRICT_CONFIGURATION_IN_SYSTEM_SERVER,
            )
    }
}
