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

import android.app.ActivityManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProtoEnums
import android.util.Log
import com.android.bluetooth.BluetoothStatsLog
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.metrics.MetricsLogger

private const val TAG = GattUtil.TAG_PREFIX + "GattMetricsReporter"

class GattMetricsReporter(private val profileId: Int, private val adapterService: AdapterService) {

    private val activityManager = adapterService.getSystemService(ActivityManager::class.java)
    private val packageManager = adapterService.packageManager
    private val metricsLogger: MetricsLogger
        get() {
            return MetricsLogger.getInstance()
        }

    fun logConnect(device: BluetoothDevice, isDirect: Boolean, uid: Int) =
        metricsLogger.logBluetoothEvent(
            device,
            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
            if (isDirect)
                BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__DIRECT_CONNECT
            else BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__INDIRECT_CONNECT,
            uid,
        )

    fun logConnectStatus(device: BluetoothDevice, status: Int, uid: Int) =
        metricsLogger.logBluetoothEvent(
            device,
            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_CONNECT_JAVA,
            connectionStatusToState(status),
            uid,
        )

    fun logDisconnectStart(device: BluetoothDevice, uid: Int) =
        metricsLogger.logBluetoothEvent(
            device,
            BluetoothStatsLog
                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__START,
            uid,
        )

    fun logDisconnectEnd(device: BluetoothDevice, uid: Int) =
        metricsLogger.logBluetoothEvent(
            device,
            BluetoothStatsLog
                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__END,
            uid,
        )

    fun logDisconnectSuccess(device: BluetoothDevice, uid: Int) =
        metricsLogger.logBluetoothEvent(
            device,
            BluetoothStatsLog
                .BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__GATT_DISCONNECT_JAVA,
            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS,
            uid,
        )

    fun logClientForegroundInfo(uid: Int, isDirect: Boolean) {
        val packageName = packageManager.getPackagesForUid(uid)?.get(0)
        val importance = activityManager.getPackageImportance(packageName)
        if (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
            metricsLogger.count(
                if (isDirect) BluetoothProtoEnums.GATT_CLIENT_CONNECT_IS_DIRECT_IN_FOREGROUND
                else BluetoothProtoEnums.GATT_CLIENT_CONNECT_IS_AUTOCONNECT_IN_FOREGROUND,
                1,
            )
        } else {
            metricsLogger.count(
                if (isDirect) BluetoothProtoEnums.GATT_CLIENT_CONNECT_IS_DIRECT_NOT_IN_FOREGROUND
                else BluetoothProtoEnums.GATT_CLIENT_CONNECT_IS_AUTOCONNECT_NOT_IN_FOREGROUND,
                1,
            )
        }
    }

    fun logAppPackage(sessionIndex: Int, device: BluetoothDevice, uid: Int) {
        val metricId = adapterService.getMetricId(device)
        Log.d(TAG, ("logAppPackage(metricId=$metricId, uid=$uid)"))
        BluetoothStatsLog.write(
            BluetoothStatsLog.BLUETOOTH_GATT_APP_INFO,
            sessionIndex,
            metricId,
            uid,
        )
    }

    fun logConnectionStateChange(
        device: BluetoothDevice,
        sessionIndex: Int,
        connectionState: Int,
        connectionStatus: Int,
    ) {
        val metricId = adapterService.getMetricId(device)
        Log.d(
            TAG,
            ("logConnectionStateChange(metricId=$metricId, sessionIndex=$sessionIndex, " +
                "connectionState=$connectionState, connectionStatus=$connectionStatus)"),
        )
        BluetoothStatsLog.write(
            BluetoothStatsLog.BLUETOOTH_CONNECTION_STATE_CHANGED,
            connectionState,
            0, /* deprecated */
            profileId,
            ByteArray(0),
            metricId,
            sessionIndex,
            connectionStatus,
        )
    }

    fun logServerForegroundInfo(uid: Int, isDirect: Boolean) {
        val packageName = packageManager.getPackagesForUid(uid)?.get(0)
        val importance = activityManager.getPackageImportance(packageName)
        if (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
            metricsLogger.count(
                if (isDirect) BluetoothProtoEnums.GATT_SERVER_CONNECT_IS_DIRECT_IN_FOREGROUND
                else BluetoothProtoEnums.GATT_SERVER_CONNECT_IS_AUTOCONNECT_IN_FOREGROUND,
                1,
            )
        } else {
            metricsLogger.count(
                if (isDirect) BluetoothProtoEnums.GATT_SERVER_CONNECT_IS_DIRECT_NOT_IN_FOREGROUND
                else BluetoothProtoEnums.GATT_SERVER_CONNECT_IS_AUTOCONNECT_NOT_IN_FOREGROUND,
                1,
            )
        }
    }

    private fun connectionStatusToState(status: Int) =
        when (status) {
            // GATT_SUCCESS
            0x00 -> BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS
            // GATT_CONNECTION_TIMEOUT
            0x93 ->
                BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__CONNECTION_TIMEOUT
            // For now all other errors are bucketed together.
            else -> BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__FAIL
        }
}
