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

package com.android.bluetooth.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.IBluetoothGattCallback
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.rule.ServiceTestRule
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [ContextMap]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ContextMapTest {
    @get:Rule val serviceRule = ServiceTestRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var callback: IBluetoothGattCallback
    @Mock private lateinit var mSource: AttributionSource

    private val device1: BluetoothDevice = getTestDevice(34)
    private val device2: BluetoothDevice = getTestDevice(58)

    @Before
    @Throws(Exception::class)
    fun setUp() {
        doReturn(packageManager).whenever(adapterService).packageManager
        doReturn(APP_NAME).whenever(packageManager).getNameForUid(anyInt())
    }

    @Test
    fun getAppMethods() {
        val contextMap: ContextMap<IBluetoothGattCallback> = getMapWithAppAndConnection()
        val contextMapById = contextMap.getById(APP_ID1)
        assertThat(contextMapById.packageName).isEqualTo(APP_NAME)
        assertThat(contextMapById.callback).isEqualTo(callback)
        assertThat(contextMapById.uuid).isEqualTo(RANDOM_UUID1)
        val contextMapByUuid = contextMap.getByUuid(RANDOM_UUID1)
        assertThat(contextMapByUuid.packageName).isEqualTo(APP_NAME)
        val contextMapByConn = contextMap.getByConnId(CONN_ID1)
        assertThat(contextMapByConn.packageName).isEqualTo(APP_NAME)

        val ids = contextMap.allApps.map { it.id }
        assertThat(ids).containsExactly(APP_ID1, APP_ID2)
    }

    @Test
    fun getConnMethods() {
        val contextMap: ContextMap<IBluetoothGattCallback> = getMapWithAppAndConnection()
        assertThat(contextMap.connectedDevices).containsExactly(device1, device2)

        var connections: List<ContextMap.Connection> =
            contextMap.getConnectionsByDevice(APP_ID1, device1)
        assertThat(connections).isNotNull()
        assertThat(connections).hasSize(1)
        assertThat(connections[0].connId()).isEqualTo(CONN_ID1)
        assertThat(connections[0].device()).isEqualTo(device1)
        assertThat(connections[0].transport()).isEqualTo(TRANSPORT_LE)
        assertThat(connections[0].appId()).isEqualTo(APP_ID1)

        connections = contextMap.getConnectionsByDevice(APP_ID2, device2)
        assertThat(connections).isNotNull()
        assertThat(connections).hasSize(2)
        assertThat(connections[0].connId()).isEqualTo(CONN_ID2)
        assertThat(connections[0].device()).isEqualTo(device2)
        assertThat(connections[0].transport()).isEqualTo(TRANSPORT_BREDR)
        assertThat(connections[0].appId()).isEqualTo(APP_ID2)

        assertThat(connections[1].connId()).isEqualTo(CONN_ID3)
        assertThat(connections[1].device()).isEqualTo(device2)
        assertThat(connections[1].transport()).isEqualTo(TRANSPORT_LE)
        assertThat(connections[1].appId()).isEqualTo(APP_ID2)

        assertThat(contextMap.getConnectionsByDevice(APP_ID1, device2)).isEmpty()
        assertThat(contextMap.getConnectionsByDevice(APP_ID2, device1)).isEmpty()
        assertThat(contextMap.getConnectionsByDevice(123456, device1)).isEmpty()
        assertThat(contextMap.getConnectionsByDevice(123456, device2)).isEmpty()

        assertThat(contextMap.deviceByConnId(CONN_ID1)).isEqualTo(device1)
        assertThat(contextMap.deviceByConnId(CONN_ID2)).isEqualTo(device2)
        assertThat(contextMap.deviceByConnId(123456)).isNull()

        connections = contextMap.getConnectionByApp(APP_ID1)
        assertThat(connections).hasSize(1)
        assertThat(connections[0].connId()).isEqualTo(CONN_ID1)
        assertThat(connections[0].device()).isEqualTo(device1)
        assertThat(connections[0].transport()).isEqualTo(TRANSPORT_LE)
        assertThat(connections[0].appId()).isEqualTo(APP_ID1)
        assertThat(contextMap.getConnectionByApp(APP_ID2)).hasSize(2)
        assertThat(contextMap.getConnectionByApp(123456)).isEmpty()
        assertThat(contextMap.connectedMap).containsExactly(APP_ID1, device1, APP_ID2, device2)
    }

    @Test
    fun clear() {
        val contextMap: ContextMap<IBluetoothGattCallback> = getMapWithAppAndConnection()
        contextMap.clear()
        assertThat(contextMap.connectedMap).isEmpty()
        assertThat(contextMap.allApps).isEmpty()
    }

    @Test
    fun removeMethods() {
        var contextMap: ContextMap<IBluetoothGattCallback> = getMapWithAppAndConnection()
        contextMap.remove(APP_ID1, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)
        assertThat(contextMap.allApps).isNotEmpty()
        contextMap.remove(APP_ID2, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)
        assertThat(contextMap.allApps).isEmpty()

        contextMap = getMapWithAppAndConnection()
        contextMap.remove(RANDOM_UUID1, ContextMap.RemoveReason.REASON_REGISTER_FAILED)
        assertThat(contextMap.allApps).isNotEmpty()
        contextMap.remove(RANDOM_UUID2, ContextMap.RemoveReason.REASON_REGISTER_FAILED)
        assertThat(contextMap.allApps).isEmpty()

        contextMap = getMapWithAppAndConnection()
        contextMap.removeConnection(APP_ID1, CONN_ID1)
        assertThat(contextMap.connectedMap).isNotEmpty()
        contextMap.removeConnection(APP_ID2, CONN_ID2)
        assertThat(contextMap.connectedMap).isNotEmpty()
        contextMap.removeConnection(APP_ID2, CONN_ID3)
        assertThat(contextMap.connectedMap).isEmpty()

        contextMap = getMapWithAppAndConnection()
        contextMap.removeConnectionsByAppId(APP_ID1)
        assertThat(contextMap.connectedMap).isNotEmpty()
        contextMap.removeConnectionsByAppId(APP_ID2)
        assertThat(contextMap.connectedMap).isEmpty()
    }

    @Test
    fun dump_withActiveApps_doesNotShowLastApps() {
        val sb = StringBuilder()
        val contextMap: ContextMap<IBluetoothGattCallback> = getMapWithAppAndConnection()
        contextMap.dump(sb)

        val dumpOutput = sb.toString()
        assertThat(dumpOutput).contains("Entries: 2")
        assertThat(dumpOutput).contains("Last apps:")
        // Check that no AppRecord is printed
        assertThat(dumpOutput).doesNotContain("AppRecord<")
    }

    @Test
    fun dump_withRemovedApp_containsAppRecord() {
        val uid = Binder.getCallingUid()
        val contextMap = ContextMap<IBluetoothGattCallback>()
        val app = contextMap.add(uid, RANDOM_UUID1, callback, TRANSPORT_LE, adapterService, mSource)
        app.id = APP_ID1

        // Remove the app to create an AppRecord in mLastRecords
        contextMap.remove(APP_ID1, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)

        val sb = StringBuilder()
        contextMap.dump(sb)

        val dumpOutput = sb.toString()
        assertThat(dumpOutput).contains("Last apps:")
        assertThat(dumpOutput).contains("app_if: $APP_ID1")
        assertThat(dumpOutput).contains("appName: $APP_NAME")
        assertThat(dumpOutput)
            .contains("reason: ${ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT}")
        // Also check that the app is no longer in the main list
        assertThat(dumpOutput).contains("Entries: 0")
    }

    private fun getMapWithAppAndConnection(): ContextMap<IBluetoothGattCallback> {
        val uid = Binder.getCallingUid()
        val contextMap = ContextMap<IBluetoothGattCallback>()
        var app = contextMap.add(uid, RANDOM_UUID1, callback, TRANSPORT_LE, adapterService, mSource)
        app.id = APP_ID1
        app = contextMap.add(uid, RANDOM_UUID2, callback, TRANSPORT_LE, adapterService, mSource)
        app.id = APP_ID2

        contextMap.addConnection(APP_ID1, CONN_ID1, TRANSPORT_LE, device1)

        contextMap.addConnection(APP_ID2, CONN_ID2, TRANSPORT_BREDR, device2)
        contextMap.addConnection(APP_ID2, CONN_ID3, TRANSPORT_LE, device2)

        assertThat(contextMap.connectedMap).isNotEmpty()
        assertThat(contextMap.allApps).isNotEmpty()
        return contextMap
    }

    private companion object {
        private const val APP_NAME = "com.android.what.a.name"
        private const val APP_ID1 = 123
        private const val APP_ID2 = 987
        private const val CONN_ID1 = 321
        private const val CONN_ID2 = 654
        private const val CONN_ID3 = 987
        private val RANDOM_UUID1 = UUID.randomUUID()
        private val RANDOM_UUID2 = UUID.randomUUID()
    }
}
