/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.bluetooth

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val TAG = "PandoraHost"

@SuppressLint("MissingPermission")
@kotlinx.coroutines.ExperimentalCoroutinesApi
class Host(context: Context) : Closeable {

    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    private val flow: Flow<Intent>

    init {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND)

        flow = intentFlow(context, intentFilter, scope).shareIn(scope, SharingStarted.Eagerly)
    }

    override fun close() {
        scope.cancel()
    }

    fun createBondAndVerify(remoteDevice: BluetoothDevice) {
        Log.d(TAG, "createBondAndVerify: $remoteDevice")
        if (adapter.bondedDevices.contains(remoteDevice)) {
            Log.d(TAG, "createBondAndVerify: already bonded")
            return
        }

        runBlocking(scope.coroutineContext) {
            withTimeout(TIMEOUT) {
                assertThat(remoteDevice.createBond()).isTrue()
                val pairingRequestJob = launch {
                    Log.d(TAG, "Waiting for ACTION_PAIRING_REQUEST")
                    flow
                        .filter { it.action == BluetoothDevice.ACTION_PAIRING_REQUEST }
                        .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                        .first()

                    remoteDevice.setPairingConfirmation(true)
                }

                Log.d(TAG, "Waiting for ACTION_BOND_STATE_CHANGED")
                flow
                    .filter { it.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                    .filter {
                        it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothDevice.BOND_BONDED
                    }
                    .first()

                if (pairingRequestJob.isActive) {
                    pairingRequestJob.cancel()
                }

                Log.d(TAG, "createBondAndVerify: bonded")
            }
        }
    }

    fun discoverAndVerify(remoteDeviceName: String): BluetoothDevice {
        Log.d(TAG, "discoverAndVerify: $remoteDeviceName")
        return runBlocking(scope.coroutineContext) {
            val foundDevice: BluetoothDevice =
                withTimeout(DISCOVERY_TIMEOUT) {
                    assertThat(adapter.startDiscovery()).isTrue()
                    val discoveredIntent =
                        flow
                            .filter { it.getAction() == BluetoothDevice.ACTION_FOUND }
                            .filter {
                                it.getStringExtra(BluetoothDevice.EXTRA_NAME) == remoteDeviceName
                            }
                            .first()
                    Log.d(TAG, "discoverAndVerify: done")
                    discoveredIntent.getBluetoothDeviceExtra()
                }
            assertThat(adapter.cancelDiscovery()).isTrue()
            foundDevice
        }
    }

    fun removeBondAndVerify(remoteDevice: BluetoothDevice) {
        Log.d(TAG, "removeBondAndVerify: $remoteDevice")
        runBlocking(scope.coroutineContext) {
            withTimeout(TIMEOUT) {
                assertThat(remoteDevice.removeBond()).isTrue()
                flow
                    .filter { it.getAction() == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                    .filter {
                        it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothDevice.BOND_NONE
                    }
                    .first()
                Log.d(TAG, "removeBondAndVerify: done")
            }
        }
    }

    fun disconnectAndVerify(remoteDevice: BluetoothDevice) {
        Log.d(TAG, "disconnectAndVerify: $remoteDevice")
        runBlocking(scope.coroutineContext) {
            withTimeout(TIMEOUT) {
                assertThat(remoteDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
                flow
                    .filter { it.getAction() == BluetoothDevice.ACTION_ACL_DISCONNECTED }
                    .filter {
                        it.getIntExtra(
                            BluetoothDevice.EXTRA_TRANSPORT,
                            BluetoothDevice.TRANSPORT_AUTO,
                        ) == BluetoothDevice.TRANSPORT_BREDR
                    }
                    .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                    .first()
                Log.d(TAG, "disconnectAndVerify: done")
            }
        }
    }

    fun Intent.getBluetoothDeviceExtra(): BluetoothDevice =
        this.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)!!

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun intentFlow(context: Context, intentFilter: IntentFilter, scope: CoroutineScope) =
        callbackFlow {
            val broadcastReceiver: BroadcastReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.d(TAG, "intentFlow: onReceive: ${intent.action}")
                        scope.launch { trySendBlocking(intent) }
                    }
                }
            context.registerReceiver(broadcastReceiver, intentFilter)

            awaitClose { context.unregisterReceiver(broadcastReceiver) }
        }

    companion object {
        private val TIMEOUT = 20.seconds
        private val DISCOVERY_TIMEOUT = 2.seconds
    }
}
