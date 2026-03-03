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

import android.Manifest
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.Manifest.permission.LOCAL_MAC_ADDRESS
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.compat.testing.PlatformCompatChangeRule
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserHandle
import android.permission.PermissionManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.content.pm.PackageInfoBuilder
import androidx.test.filters.SmallTest
import com.android.bluetooth.flags.Flags
import com.android.server.bluetooth.ChangeIds
import com.android.server.bluetooth.PermissionChecker
import com.android.server.bluetooth.PermissionChecker.BluetoothPermissionException
import com.android.tests.bluetooth.FlagsWrapper
import kotlin.test.assertFailsWith
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess

@SmallTest
@RunWith(ParameterizedRobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class PermissionCheckerTest(private val flags: FlagsWrapper) {
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val compatChangeRule = PlatformCompatChangeRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = application
    private val source = AttributionSource.myAttributionSource()
    private val shadowPackageManager by lazy { shadowOf(context.packageManager) }
    private val shadowDevicePolicyManager by lazy {
        shadowOf(context.getSystemService(DevicePolicyManager::class.java))
    }

    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionChecker: PermissionChecker

    @Before
    fun setUp() {
        BluetoothRestrictionTest.setup()
        setup(context)
        ShadowBinder.setCallingUid(TEST_APP_UID)
        ShadowProcess.setUid(0) // Set the current user to 0 (system user)

        permissionManager = initializePermissionManager()
        permissionChecker = PermissionChecker(context, permissionManager)
    }

    @Test
    fun `enforcePrivileged with permission succeeds`() {
        shadowOf(application).grantPermissions(BLUETOOTH_PRIVILEGED)
        permissionChecker.enforcePrivileged() // should not throw
    }

    @Test
    fun `enforcePrivileged without permission fails`() {
        denyBluetoothPrivileged()
        assertFailsWith<SecurityException> { permissionChecker.enforcePrivileged() }
    }

    @Test
    fun `enableAllowed with CONNECT permission succeeds`() {
        grantBluetoothConnect(permissionManager)
        permissionChecker.enableAllowed(source, false) // no throw
    }

    @Test
    fun `enableAllowed without CONNECT permission fails`() {
        assertFailsWith<SecurityException> { permissionChecker.enableAllowed(source, false) }
    }

    @Test
    fun `enableAllowed when bluetooth disallowed fails`() {
        BluetoothRestrictionTest.disallowBluetooth()
        val exception =
            assertFailsWith<BluetoothPermissionException> {
                permissionChecker.enableAllowed(source, true)
            }
        assert(exception.message == "Bluetooth is not allowed")
    }

    @Test
    @DisableFlags(Flags.FLAG_SYSTEM_SERVER_NO_LONGER_PROVIDE_PROCESS_EXEMPTION)
    fun `enableAllowed for special UIDs succeeds without permission`() {
        val specialUids =
            mapOf(
                Process.SYSTEM_UID to "android",
                Process.NFC_UID to "com.android.nfc",
                Process.SHELL_UID to "com.android.shell",
                Process.ROOT_UID to "root",
            )
        for ((uid, pkg) in specialUids) {
            ShadowBinder.setCallingUid(uid)
            val specialSource = AttributionSource.Builder(uid).setPackageName(pkg).build()
            permissionChecker.enableAllowed(specialSource, true) // no throw
        }
    }

    @Test
    fun `enableAllowed with mismatched package name fails`() {
        shadowPackageManager.setPackagesForUid(TEST_APP_UID, "my.correct.package")
        val wrongSource =
            AttributionSource.Builder(TEST_APP_UID).setPackageName("my.wrong.package").build()
        assertFailsWith<SecurityException> { permissionChecker.enableAllowed(wrongSource, true) }
    }

    @Test
    fun `enableAllowed from background user fails when foreground required`() {
        val foregroundUserId = 0
        val backgroundUserId = 10
        ShadowProcess.setUid(
            UserHandle.of(foregroundUserId).getUid(UserHandle.getAppId(Process.myUid()))
        )

        val backgroundUid =
            UserHandle.of(backgroundUserId).getUid(UserHandle.getAppId(TEST_APP_UID))
        ShadowBinder.setCallingUid(backgroundUid)
        val backgroundSource =
            AttributionSource.Builder(backgroundUid).setPackageName(TEST_APP_PACKAGE_NAME).build()
        grantBluetoothConnect(permissionManager)

        val exception =
            assertFailsWith<BluetoothPermissionException> {
                permissionChecker.enableAllowed(backgroundSource, true)
            }
        assert(exception.message!!.contains("Not allowed for non-active and non system user"))
    }

    @Test
    fun `enableAllowed from background user succeeds when foreground not required`() {
        val foregroundUserId = 0
        val backgroundUserId = 10
        ShadowProcess.setUid(
            UserHandle.of(foregroundUserId).getUid(UserHandle.getAppId(Process.myUid()))
        )

        val backgroundUid =
            UserHandle.of(backgroundUserId).getUid(UserHandle.getAppId(TEST_APP_UID))
        ShadowBinder.setCallingUid(backgroundUid)
        val backgroundSource =
            AttributionSource.Builder(backgroundUid).setPackageName(TEST_APP_PACKAGE_NAME).build()
        grantBluetoothConnect(permissionManager)

        permissionChecker.enableAllowed(backgroundSource, false) // no throw
    }

    @Test
    fun `getAddressAllowed succeeds with required permissions`() {
        grantBluetoothConnect(permissionManager)
        shadowOf(application).grantPermissions(LOCAL_MAC_ADDRESS)
        permissionChecker.getAddressAllowed(source) // no throw
    }

    @Test
    fun `getAddressAllowed fails without BLUETOOTH_CONNECT`() {
        shadowOf(application).grantPermissions(LOCAL_MAC_ADDRESS)
        assertFailsWith<SecurityException> { permissionChecker.getAddressAllowed(source) }
    }

    @Test
    fun `getAddressAllowed fails without LOCAL_MAC_ADDRESS`() {
        grantBluetoothConnect(permissionManager)
        denyBluetoothPrivileged()
        val exception =
            assertFailsWith<BluetoothPermissionException> {
                permissionChecker.getAddressAllowed(source)
            }
        assert(
            exception.message ==
                "getAddress enforce android.permission.LOCAL_MAC_ADDRESS. But permission is missing"
        )
    }

    @Test
    fun `setNameAllowed with CONNECT permission succeeds`() {
        grantBluetoothConnect(permissionManager)
        permissionChecker.setNameAllowed(source) // no throw
    }

    @Test
    fun `getNameAllowed with CONNECT permission succeeds`() {
        grantBluetoothConnect(permissionManager)
        permissionChecker.getNameAllowed(source) // no throw
    }

    @Test
    @Config(sdk = [36])
    @EnableCompatChanges(ChangeIds.RESTRICT_ENABLE_DISABLE)
    fun `enableAllowed with compat change enabled succeeds for system app`() {
        grantBluetoothConnect(permissionManager)

        shadowPackageManager.installPackage(
            PackageInfoBuilder.newBuilder()
                .setPackageName(TEST_APP_PACKAGE_NAME)
                .setApplicationInfo(
                    ApplicationInfo().apply {
                        packageName = TEST_APP_PACKAGE_NAME
                        uid = TEST_APP_UID
                        flags = flags or ApplicationInfo.FLAG_SYSTEM
                    }
                )
                .build()
        )

        permissionChecker.enableAllowed(source, true) // no throw
    }

    @Test
    @Config(sdk = [36])
    @EnableCompatChanges(ChangeIds.RESTRICT_ENABLE_DISABLE)
    fun `enableAllowed with compat change enabled succeeds for privileged app`() {
        grantBluetoothConnect(permissionManager)
        shadowOf(application).grantPermissions(BLUETOOTH_PRIVILEGED)
        permissionChecker.enableAllowed(source, true) // no throw
    }

    @Test
    @Config(sdk = [36])
    @EnableCompatChanges(ChangeIds.RESTRICT_ENABLE_DISABLE)
    fun `enableAllowed with compat change enabled succeeds for device owner`() {
        grantBluetoothConnect(permissionManager)
        val componentName = ComponentName(TEST_APP_PACKAGE_NAME, "DeviceAdmin")
        shadowDevicePolicyManager.setDeviceOwner(componentName)

        permissionChecker.enableAllowed(source, true) // no throw
    }

    @Test
    @Config(sdk = [36])
    @EnableCompatChanges(ChangeIds.RESTRICT_ENABLE_DISABLE)
    fun `enableAllowed with compat change enabled succeeds for profile owner`() {
        grantBluetoothConnect(permissionManager)
        val componentName = ComponentName(TEST_APP_PACKAGE_NAME, "DeviceAdmin")
        shadowDevicePolicyManager.setProfileOwner(componentName)

        permissionChecker.enableAllowed(source, true) // no throw
    }

    @Test
    fun `factoryResetAllowed with CONNECT permission succeeds`() {
        grantBluetoothConnect(permissionManager)
        permissionChecker.factoryResetAllowed(source) // no throw
    }

    @Test
    fun `enforceDump with DUMP permission succeeds`() {
        shadowOf(application).grantPermissions(Manifest.permission.DUMP)
        permissionChecker.enforceDump() // no throw
    }

    @Test
    fun `enforceDump without DUMP permission fails`() {
        shadowOf(application).denyPermissions(Manifest.permission.DUMP)
        assertFailsWith<SecurityException> { permissionChecker.enforceDump() }
    }

    @Test
    @Config(sdk = [36])
    @EnableCompatChanges(ChangeIds.RESTRICT_ENABLE_DISABLE)
    fun `enableAllowed with compat change enabled succeeds for updated system app`() {
        grantBluetoothConnect(permissionManager)

        val applicationInfo =
            ApplicationInfo().apply {
                packageName = TEST_APP_PACKAGE_NAME
                uid = TEST_APP_UID
                flags = flags or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            }
        shadowPackageManager.installPackage(
            PackageInfoBuilder.newBuilder()
                .setPackageName(TEST_APP_PACKAGE_NAME)
                .setApplicationInfo(applicationInfo)
                .build()
        )

        permissionChecker.enableAllowed(source, true) // no throw
    }

    @Test
    @Config(sdk = [36])
    @EnableCompatChanges(ChangeIds.RESTRICT_ENABLE_DISABLE)
    fun `enableAllowed with compat change enabled succeeds for app with system signature`() {
        grantBluetoothConnect(permissionManager)
        denyBluetoothPrivileged()

        val sig =
            android.content.pm.SigningInfo(
                1,
                listOf(android.content.pm.Signature("1234")),
                null,
                null,
            )

        val appInfo =
            ApplicationInfo().apply {
                packageName = TEST_APP_PACKAGE_NAME
                uid = TEST_APP_UID
            }
        val appPkgInfo =
            android.content.pm.PackageInfo().apply {
                packageName = TEST_APP_PACKAGE_NAME
                applicationInfo = appInfo
                signingInfo = sig
            }
        shadowPackageManager.installPackage(appPkgInfo)
        shadowPackageManager.setPackagesForUid(TEST_APP_UID, TEST_APP_PACKAGE_NAME)

        val sysInfo =
            ApplicationInfo().apply {
                packageName = "android"
                uid = Process.SYSTEM_UID
            }
        val sysPkgInfo =
            android.content.pm.PackageInfo().apply {
                packageName = "android"
                applicationInfo = sysInfo
                signingInfo = sig
            }
        shadowPackageManager.installPackage(sysPkgInfo)
        shadowPackageManager.setPackagesForUid(Process.SYSTEM_UID, "android")

        permissionChecker.enableAllowed(source, true) // no throw
    }

    companion object {
        private const val TEST_APP_UID = Process.FIRST_APPLICATION_UID + 140
        private const val TEST_APP_PACKAGE_NAME = "com.android.server.bluetooth.test.app"

        fun setup(context: Context) {
            shadowOf(context.packageManager).setPackagesForUid(TEST_APP_UID, TEST_APP_PACKAGE_NAME)
        }

        internal fun initializePermissionManager(): PermissionManager {
            val permissionManager: PermissionManager = mock()
            doReturn(PermissionManager.PERMISSION_HARD_DENIED)
                .whenever(permissionManager)
                .checkPermissionForDataDeliveryFromDataSource(any(), any(), any())
            return permissionManager
        }

        internal fun grantBluetoothConnect(mockManager: PermissionManager) {
            doReturn(PermissionManager.PERMISSION_GRANTED)
                .whenever(mockManager)
                .checkPermissionForDataDeliveryFromDataSource(eq(BLUETOOTH_CONNECT), any(), any())
        }

        internal fun grantBluetoothPrivileged() {
            val application: Application = ApplicationProvider.getApplicationContext()
            shadowOf(application).grantPermissions(BLUETOOTH_PRIVILEGED)
        }

        internal fun denyBluetoothPrivileged() {
            val application: Application = ApplicationProvider.getApplicationContext()
            shadowOf(application).denyPermissions(BLUETOOTH_PRIVILEGED)
        }

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() =
            FlagsWrapper.progressionOf(Flags.FLAG_SYSTEM_SERVER_NO_LONGER_PROVIDE_PROCESS_EXEMPTION)
    }
}
