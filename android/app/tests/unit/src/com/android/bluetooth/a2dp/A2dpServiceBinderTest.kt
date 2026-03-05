/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.a2dp

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.content.AttributionSource
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [A2dpServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class A2dpServiceBinderTest {
    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var a2dpService: A2dpService
    @Mock private lateinit var packageManager: PackageManager

    private val device = getTestDevice(0)

    private lateinit var binder: A2dpServiceBinder

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        doReturn(context.packageName).whenever(source).packageName
        doReturn(packageManager).whenever(a2dpService).packageManager
        val appInfo = ApplicationInfo()
        appInfo.targetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT
        doReturn(appInfo).whenever(packageManager).getApplicationInfo(any<String>(), any<Int>())

        binder = A2dpServiceBinder(a2dpService)
    }

    @After
    fun cleanUp() {
        binder.cleanup()
    }

    @Test
    fun connect() {
        binder.connect(device, source)
        verify(a2dpService).connect(device)
    }

    @Test
    fun disconnect() {
        binder.disconnect(device, source)
        verify(a2dpService).disconnect(device)
    }

    @Test
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(a2dpService).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED)

        binder.getDevicesMatchingConnectionStates(states, source)
        verify(a2dpService).getDevicesMatchingConnectionStates(states)
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(a2dpService).getConnectionState(device)
    }

    @Test
    fun setActiveDevice() {
        binder.setActiveDevice(device, source)
        verify(a2dpService).setActiveDevice(device)
    }

    @Test
    fun setActiveDevice_withNull_callsRemoveActiveDevice() {
        binder.setActiveDevice(null, source)
        verify(a2dpService).removeActiveDevice(false)
    }

    @Test
    fun getActiveDevice() {
        binder.getActiveDevice(source)
        verify(a2dpService).activeDevice
    }

    @Test
    fun setConnectionPolicy() {
        val connectionPolicy = CONNECTION_POLICY_ALLOWED

        binder.setConnectionPolicy(device, connectionPolicy, source)
        verify(a2dpService).setConnectionPolicy(device, connectionPolicy)
    }

    @Test
    fun getConnectionPolicy() {
        binder.getConnectionPolicy(device, source)
        verify(a2dpService).getConnectionPolicy(device)
    }

    @Test
    fun setAvrcpAbsoluteVolume() {
        val volume = 3

        binder.setAvrcpAbsoluteVolume(volume, source)
        verify(a2dpService).setAvrcpAbsoluteVolume(volume)
    }

    @Test
    fun isA2dpPlaying() {
        binder.isA2dpPlaying(device, source)
        verify(a2dpService).isA2dpPlaying(device)
    }

    @Test
    fun getCodecStatus() {
        binder.getCodecStatus(device, source)
        verify(a2dpService).getCodecStatus(device)
    }

    @Suppress("DEPRECATION")
    @Test
    fun setCodecConfigPreference() {
        val config = BluetoothCodecConfig(SOURCE_CODEC_TYPE_INVALID)

        binder.setCodecConfigPreference(device, config, source)
        verify(a2dpService).setCodecConfigPreference(device, config)
    }

    @Test
    fun enableOptionalCodecs() {
        binder.enableOptionalCodecs(device, source)
        verify(a2dpService).enableOptionalCodecs(device)
    }

    @Test
    fun disableOptionalCodecs() {
        binder.disableOptionalCodecs(device, source)
        verify(a2dpService).disableOptionalCodecs(device)
    }

    @Test
    fun isOptionalCodecsSupported() {
        binder.isOptionalCodecsSupported(device, source)
        verify(a2dpService).getSupportsOptionalCodecs(device)
    }

    @Test
    fun isOptionalCodecsEnabled() {
        binder.isOptionalCodecsEnabled(device, source)
        verify(a2dpService).getOptionalCodecsEnabled(device)
    }

    @Test
    fun setOptionalCodecsEnabled() {
        val value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN

        binder.setOptionalCodecsEnabled(device, value, source)
        verify(a2dpService).setOptionalCodecsEnabled(device, value)
    }

    @Test
    fun getDynamicBufferSupport() {
        binder.getDynamicBufferSupport(source)
        verify(a2dpService).dynamicBufferSupport
    }

    @Test
    fun getBufferConstraints() {
        binder.getBufferConstraints(source)
        verify(a2dpService).bufferConstraints
    }

    @Test
    fun setBufferLengthMillis() {
        val codec = 0
        val value = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN

        binder.setBufferLengthMillis(codec, value, source)
        verify(a2dpService).setBufferLengthMillis(codec, value)
    }
}
