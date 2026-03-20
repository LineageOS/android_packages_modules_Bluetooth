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

package android.bluetooth.pairing

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.OobDataCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.OobData
import android.bluetooth.PandoraDevice
import android.bluetooth.StreamObserverSpliterator
import android.bluetooth.Utils
import android.bluetooth.adapter
import android.bluetooth.cts.EnableBluetoothRule
import android.bluetooth.pairing.utils.IntentReceiver
import android.bluetooth.pairing.utils.TestUtil
import android.bluetooth.toAddressBytes
import android.bluetooth.toAddressString
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.primitives.Bytes
import com.google.protobuf.ByteString
import io.grpc.Deadline
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.ConnectLERequest
import pandora.HostProto.OwnAddressType
import pandora.HostProto.ScanRequest
import pandora.HostProto.ScanningResponse
import pandora.OobProto.OobDataRequest
import pandora.OobProto.OobDataResponse
import pandora.SecurityProto.LESecurityLevel
import pandora.SecurityProto.SecureRequest
import pandora.SecurityProto.SecureResponse

@RunWith(AndroidJUnit4::class)
class OobPairingTest {
    @get:Rule(order = 0) val permissionRule = AdoptShellPermissionsRule()

    @get:Rule(order = 1) val bumble = PandoraDevice()

    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var device: BluetoothDevice
    private lateinit var remoteOobData: OobDataResponse
    private lateinit var dutAddr: String
    private var remoteInitiator = false
    private lateinit var util: TestUtil

    private val intentListener = IntentReceiver.IntentListener { intent ->
        val action = intent.action
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
            val device =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val bondState =
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR)
            val prevBondState =
                intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothAdapter.ERROR,
                )
            Log.i(
                TAG,
                "onReceive(): device $device bond state changed from $prevBondState to $bondState",
            )
        } else {
            Log.i(TAG, "onReceive(): unknown intent action $action")
        }
    }

    private val generateOobDataCallback =
        object : OobDataCallback {
            override fun onError(error: Int) {
                Log.i(TAG, "onError: $error")
            }

            override fun onOobData(transport: Int, data: OobData) {
                Log.d(TAG, "OobData: $data")
                dutAddr = getReverseAddressString(data.deviceAddressWithType)
                val localData = Bytes.concat(data.confirmationHash, data.randomizerHash)
                val localOobData =
                    OobDataRequest.newBuilder().setOob(ByteString.copyFrom(localData)).build()
                remoteOobData = bumble.oobBlocking().shareOobData(localOobData)
                val p256 = buildOobData()
                if (remoteInitiator) {
                    testStep_initiatePairingFromRemote()
                } else {
                    device.createBondOutOfBand(BluetoothDevice.TRANSPORT_LE, null, p256)
                }
            }
        }

    @Before
    fun setUp() {
        util = TestUtil.Builder(context).build()
        device =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
    }

    @After
    fun tearDown() {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            util.removeBond(null, device)
        }
    }

    @Test
    fun createBondWithRemoteOob() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .setIntentListener(intentListener)
                .setIntentTimeout(INTENT_TIMEOUT)
                .build()

        testStep_startAdvertise()
        val noLocalOobData = OobDataRequest.newBuilder().setOob(ByteString.EMPTY).build()
        remoteOobData = bumble.oobBlocking().shareOobData(noLocalOobData)
        val p256 = buildOobData()
        device.createBondOutOfBand(BluetoothDevice.TRANSPORT_LE, null, p256)
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    @Test
    fun createBondWithRemoteAndLocalOob() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .setIntentListener(intentListener)
                .setIntentTimeout(INTENT_TIMEOUT)
                .build()

        testStep_startAdvertise()
        adapter.generateLocalOobData(
            BluetoothDevice.TRANSPORT_LE,
            context.mainExecutor,
            generateOobDataCallback,
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    @Test
    fun createBondByRemoteDeviceWithLocalOob() {
        val intentReceiver =
            IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .setIntentListener(intentListener)
                .setIntentTimeout(INTENT_TIMEOUT)
                .build()

        remoteInitiator = true
        val deviceName = adapter.name
        // set adapter name for verification
        adapter.name = CF_NAME

        adapter.generateLocalOobData(
            BluetoothDevice.TRANSPORT_LE,
            context.mainExecutor,
            generateOobDataCallback,
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )
        remoteInitiator = false
        // revert adapter name
        adapter.name = deviceName

        intentReceiver.close()
    }

    private fun testStep_startAdvertise() {
        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.RANDOM)
                .build()
        bumble.hostBlocking().advertise(request)
    }

    private fun testStep_initiatePairingFromRemote() {
        val deviceAddr: ByteString
        val scanningResponseObserver = StreamObserverSpliterator<ScanRequest, ScanningResponse>()
        val deadline = Deadline.after(TIMEOUT_ADVERTISING_MS, TimeUnit.MILLISECONDS)
        bumble
            .host()
            .withDeadline(deadline)
            .scan(ScanRequest.newBuilder().build(), scanningResponseObserver)
        val scanningResponseIterator = scanningResponseObserver.iterator()

        while (true) {
            if (scanningResponseIterator.hasNext()) {
                val scanningResponse = scanningResponseIterator.next()
                // Select DUT address from scan results
                val scannedDevice = scanningResponse.random.toAddressString()
                Log.d(TAG, "Scanned Devices: $scannedDevice")
                if (scannedDevice == dutAddr) {
                    deviceAddr = scanningResponse.random
                    break
                }
            }
        }
        com.google.common.truth.Truth.assertThat(deviceAddr).isNotNull()

        val leConn =
            bumble
                .hostBlocking()
                .connectLE(
                    ConnectLERequest.newBuilder()
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .setRandom(deviceAddr)
                        .build()
                )
        // Start pairing from Bumble
        val responseObserver = StreamObserverSpliterator<SecureRequest, SecureResponse>()
        bumble
            .security()
            .secure(
                SecureRequest.newBuilder()
                    .setConnection(leConn.connection)
                    .setLe(LESecurityLevel.LE_LEVEL4)
                    .build(),
                responseObserver,
            )
    }

    private fun buildOobData(): OobData {
        val confirmationHash =
            remoteOobData.oob.substring(HASH_START_POSITION, HASH_END_POSITION).toByteArray()
        val randomizer =
            remoteOobData.oob
                .substring(RANDOMIZER_START_POSITION, RANDOMIZER_END_POSITION)
                .toByteArray()
        val address = Utils.BUMBLE_RANDOM_ADDRESS.toAddressBytes()
        val addressType = byteArrayOf(BluetoothDevice.ADDRESS_TYPE_RANDOM.toByte())

        return OobData.LeBuilder(
                confirmationHash,
                Bytes.concat(address, addressType),
                OobData.LE_DEVICE_ROLE_BOTH_PREFER_CENTRAL,
            )
            .setRandomizerHash(randomizer)
            .build()
    }

    companion object {
        private const val TAG = "OobPairingTest"
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private const val CF_NAME = "Cuttlefish"
        private const val TIMEOUT_ADVERTISING_MS = 1000L
        private const val HASH_START_POSITION = 0
        private const val HASH_END_POSITION = 16
        private const val RANDOMIZER_START_POSITION = 16
        private const val RANDOMIZER_END_POSITION = 32

        private fun getReverseAddressString(address: ByteArray): String {
            return String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                address[5],
                address[4],
                address[3],
                address[2],
                address[1],
                address[0],
            )
        }
    }
}
