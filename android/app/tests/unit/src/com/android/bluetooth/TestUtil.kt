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

package com.android.bluetooth

import android.annotation.IntRange
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.AddressType
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.btservice.AdapterService
import com.google.common.truth.Truth.assertThat
import org.mockito.Mockito.lenient
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private val context: Context
    get() = InstrumentationRegistry.getInstrumentation().context

internal val manager: BluetoothManager
    get() = context.getSystemService(BluetoothManager::class.java)

internal val adapter: BluetoothAdapter
    get() = manager.adapter

internal fun getTestDevice(@IntRange(from = 0x00, to = 0xFF) id: Int): BluetoothDevice {
    assertThat(id).isAtMost(0xFF)
    val address = "00:01:02:03:04:${String.format("%02X", id)}"
    return getTestDevice(address)
}

internal fun getTestDevice(
    address: String,
    addressType: Int = BluetoothDevice.ADDRESS_TYPE_PUBLIC,
): BluetoothDevice =
    mock<BluetoothDevice>().apply {
        doReturn(address).whenever(this).address
        doReturn(address).whenever(this).toString()
        doReturn(addressType).whenever(this).addressType
    }

internal fun getRealDevice(@IntRange(from = 0x00, to = 0xFF) id: Int): BluetoothDevice {
    assertThat(id).isAtMost(0xFF)
    val address = "00:01:02:03:04:${String.format("%02X", id)}"
    return getRealDevice(address)
}

internal fun getRealDevice(
    address: String,
    @AddressType type: Int = BluetoothDevice.ADDRESS_TYPE_PUBLIC,
): BluetoothDevice {
    assertThat(BluetoothAdapter.checkBluetoothAddress(address)).isTrue()
    return adapter.getRemoteLeDevice(address, type)
}

internal fun Context.mockBluetoothManager() = mockGetSystemService(manager)

internal inline fun <reified T : Any> Context.mockGetSystemService(service: T = mock()) {
    val serviceClass = T::class.java
    val serviceName = context.getSystemServiceName(serviceClass)!!
    doReturn(service).whenever(this).getSystemService(serviceClass)
    doReturn(service).whenever(this).getSystemService(serviceName)
    doReturn(serviceName).whenever(this).getSystemServiceName(serviceClass)
}

internal fun Context.mockPackageManager(packageManager: PackageManager = mock<PackageManager>()) =
    doReturn(packageManager).whenever(this).packageManager

internal fun Context.mockResources(resources: Resources = mock<Resources>()) =
    doReturn(resources).whenever(this).resources

internal fun AdapterService.mockGetRemoteDevice(vararg devices: BluetoothDevice) =
    devices.forEach { device ->
        val address = device.address
        val addressType = device.addressType
        lenient().doReturn(device).whenever(this).getRemoteDevice(eq(address))
        lenient().doReturn(device).whenever(this).getRemoteDevice(eq(address), eq(addressType))
    }
