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
import android.app.Application
import android.bluetooth.IBluetoothManagerCallback
import android.content.AttributionSource
import android.content.Context
import android.os.HandlerThread
import android.os.IBinder
import android.os.UserManager
import android.permission.PermissionManager
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.server.bluetooth.BluetoothManagerServiceApi
import com.android.server.bluetooth.ServerBinder
import com.android.tests.bluetooth.FlagsWrapper
import com.google.common.truth.Truth.assertThat
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

        checkDisallowed { binder.enableNoAutoConnect(source) }
    }

    @Test
    fun disable() {
        assertFailsWith<SecurityException> { binder.disable(source, false) }

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
        assertFailsWith<SecurityException> { binder.setBtHciSnoopLogMode(0) }
        assertFailsWith<SecurityException> { binder.getBtHciSnoopLogMode() }

        grantPrivileged()

        binder.setBtHciSnoopLogMode(0)
        assertThat(binder.getBtHciSnoopLogMode()).isEqualTo(0)
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

    private fun grantPrivileged() = shadowOf(application).grantPermissions(BLUETOOTH_PRIVILEGED)

    companion object {
        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
