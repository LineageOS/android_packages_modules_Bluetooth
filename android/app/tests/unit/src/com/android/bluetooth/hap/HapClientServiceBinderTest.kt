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

package com.android.bluetooth.hap

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.IBluetoothHapClientCallback
import android.content.AttributionSource
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.MockitoRule
import java.util.function.Consumer
import java.util.function.Function
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Test cases for [HapClientServiceBinder]. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class HapClientServiceBinderTest(flags: FlagsWrapper) {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: HapClientService

    private val device: BluetoothDevice = getTestDevice(0)

    private lateinit var binder: HapClientServiceBinder

    @Before
    fun setUp() {
        doCallRealMethod().whenever(service).syncPost(any())
        doAnswer { inv ->
                inv.getArgument<Consumer<HapClientService>>(0).accept(service)
                null
            }
            .whenever(service)
            .post(any())
        doAnswer { inv -> inv.getArgument<Function<HapClientService, Any>>(0).apply(service) }
            .whenever(service)
            .syncPost(any<Function<HapClientService, Any>>(), anyOrNull())
        binder = HapClientServiceBinder(service)
    }

    @Test
    fun getConnectedDevices() {
        assertThrows(NullPointerException::class.java) { binder.getConnectedDevices(null) }
        binder.getConnectedDevices(source)
        verify(service).connectedDevices
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_CONNECTED)
        assertThrows(NullPointerException::class.java) {
            binder.getDevicesMatchingConnectionStates(states, null)
        }
        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(any())
    }

    @Test
    fun getConnectionState() {
        assertThrows(NullPointerException::class.java) { binder.getConnectionState(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getConnectionState(null, source) }

        binder.getConnectionState(device, source)
        verify(service).getConnectionState(eq(device))
    }

    @Test
    fun setConnectionPolicy() {
        assertThrows(NullPointerException::class.java) {
            binder.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED, null)
        }
        assertThrows(NullPointerException::class.java) {
            binder.setConnectionPolicy(null, CONNECTION_POLICY_ALLOWED, source)
        }
        assertThrows(IllegalArgumentException::class.java) {
            binder.setConnectionPolicy(device, CONNECTION_POLICY_UNKNOWN, source)
        }

        binder.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED, source)
        verify(service).setConnectionPolicy(eq(device), eq(CONNECTION_POLICY_ALLOWED))
    }

    @Test
    fun getConnectionPolicy() {
        assertThrows(NullPointerException::class.java) { binder.getConnectionPolicy(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getConnectionPolicy(null, source) }
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(eq(device))
    }

    @Test
    fun getActivePresetIndex() {
        assertThrows(NullPointerException::class.java) { binder.getActivePresetIndex(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getActivePresetIndex(null, source) }
        binder.getActivePresetIndex(device, source)
        verify(service).getActivePresetIndex(eq(device))
    }

    @Test
    fun getActivePresetInfo() {
        assertThrows(NullPointerException::class.java) { binder.getActivePresetInfo(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getActivePresetInfo(null, source) }
        binder.getActivePresetInfo(device, source)
        verify(service).getActivePresetInfo(eq(device))
    }

    @Test
    fun getHapGroup() {
        assertThrows(NullPointerException::class.java) { binder.getHapGroup(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getHapGroup(null, source) }
        binder.getHapGroup(device, source)
        verify(service).getHapGroup(eq(device))
    }

    @Test
    fun selectPreset() {
        val index = 42
        assertThrows(NullPointerException::class.java) { binder.selectPreset(device, index, null) }
        assertThrows(NullPointerException::class.java) { binder.selectPreset(null, index, source) }
        binder.selectPreset(device, index, source)
        verify(service).selectPreset(eq(device), eq(index))
    }

    @Test
    fun selectPresetForGroup() {
        val index = 42
        val groupId = 4242
        assertThrows(NullPointerException::class.java) {
            binder.selectPresetForGroup(groupId, index, null)
        }
        binder.selectPresetForGroup(groupId, index, source)
        verify(service).selectPresetForGroup(eq(groupId), eq(index))
    }

    @Test
    fun switchToNextPreset() {
        assertThrows(NullPointerException::class.java) { binder.switchToNextPreset(device, null) }
        assertThrows(NullPointerException::class.java) { binder.switchToNextPreset(null, source) }
        binder.switchToNextPreset(device, source)
        verify(service).switchToNextPreset(eq(device))
    }

    @Test
    fun switchToNextPresetForGroup() {
        val groupId = 4242
        assertThrows(NullPointerException::class.java) {
            binder.switchToNextPresetForGroup(groupId, null)
        }
        binder.switchToNextPresetForGroup(groupId, source)
        verify(service).switchToNextPresetForGroup(eq(groupId))
    }

    @Test
    fun switchToPreviousPreset() {
        assertThrows(NullPointerException::class.java) {
            binder.switchToPreviousPreset(device, null)
        }
        assertThrows(NullPointerException::class.java) {
            binder.switchToPreviousPreset(null, source)
        }
        binder.switchToPreviousPreset(device, source)
        verify(service).switchToPreviousPreset(eq(device))
    }

    @Test
    fun switchToPreviousPresetForGroup() {
        val groupId = 4242
        assertThrows(NullPointerException::class.java) {
            binder.switchToPreviousPresetForGroup(groupId, null)
        }
        binder.switchToPreviousPresetForGroup(groupId, source)
        verify(service).switchToPreviousPresetForGroup(eq(groupId))
    }

    @Test
    fun getPresetInfo() {
        val index = 42
        assertThrows(NullPointerException::class.java) { binder.getPresetInfo(device, index, null) }
        assertThrows(NullPointerException::class.java) { binder.getPresetInfo(null, index, source) }
        binder.getPresetInfo(device, index, source)
        verify(service).getPresetInfo(eq(device), eq(index))
    }

    @Test
    fun getAllPresetInfo() {
        assertThrows(NullPointerException::class.java) { binder.getAllPresetInfo(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getAllPresetInfo(null, source) }
        binder.getAllPresetInfo(device, source)
        verify(service).getAllPresetInfo(eq(device))
    }

    @Test
    fun getFeatures() {
        assertThrows(NullPointerException::class.java) { binder.getFeatures(device, null) }
        assertThrows(NullPointerException::class.java) { binder.getFeatures(null, source) }
        binder.getFeatures(device, source)
        verify(service).getFeatures(eq(device))
    }

    @Test
    fun setPresetName() {
        val name = "This is a preset name"
        val index = 42
        assertThrows(NullPointerException::class.java) {
            binder.setPresetName(null, index, name, source)
        }
        assertThrows(NullPointerException::class.java) {
            binder.setPresetName(device, index, null, source)
        }
        assertThrows(NullPointerException::class.java) {
            binder.setPresetName(device, index, name, null)
        }
        binder.setPresetName(device, index, name, source)
        verify(service).setPresetName(eq(device), eq(index), eq(name))
    }

    @Test
    fun setPresetNameForGroup() {
        val name = "This is a preset name"
        val index = 42
        val groupId = 4242
        assertThrows(NullPointerException::class.java) {
            binder.setPresetNameForGroup(groupId, index, null, source)
        }
        assertThrows(NullPointerException::class.java) {
            binder.setPresetNameForGroup(groupId, index, name, null)
        }
        binder.setPresetNameForGroup(groupId, index, name, source)
        verify(service).setPresetNameForGroup(eq(groupId), eq(index), eq(name))
    }

    @Test
    fun registerCallback() {
        val callback = mock<IBluetoothHapClientCallback>()
        assertThrows(NullPointerException::class.java) { binder.registerCallback(null, source) }
        assertThrows(NullPointerException::class.java) { binder.registerCallback(callback, null) }
        binder.registerCallback(callback, source)
        verify(service).registerCallback(eq(callback))
    }

    @Test
    fun unregisterCallback() {
        val callback = mock<IBluetoothHapClientCallback>()
        assertThrows(NullPointerException::class.java) { binder.unregisterCallback(null, source) }
        assertThrows(NullPointerException::class.java) { binder.unregisterCallback(callback, null) }
        binder.unregisterCallback(callback, source)
        verify(service).unregisterCallback(eq(callback))
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() = FlagsWrapper.progressionOf(Flags.FLAG_HAP_ON_MAIN_LOOPER)
    }
}
