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

import android.Manifest
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.Context
import android.platform.test.annotations.RequiresFlagsEnabled
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.flags.Flags
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import io.grpc.Context as GrpcContext
import io.grpc.Deadline
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pandora.HostProto.Connection
import pandora.l2cap.L2CAPProto.ConnectRequest
import pandora.l2cap.L2CAPProto.ConnectResponse
import pandora.l2cap.L2CAPProto.CreditBasedChannelRequest
import pandora.l2cap.L2CAPProto.DisconnectRequest
import pandora.l2cap.L2CAPProto.ReceiveRequest
import pandora.l2cap.L2CAPProto.ReceiveResponse
import pandora.l2cap.L2CAPProto.SendRequest
import pandora.l2cap.L2CAPProto.WaitConnectionRequest
import pandora.l2cap.L2CAPProto.WaitConnectionResponse
import pandora.l2cap.L2CAPProto.WaitDisconnectionRequest

/** DCK L2CAP Tests */
@RunWith(TestParameterInjector::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
public class DckL2capTest() : Closeable {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
    private val bluetoothAdapter = bluetoothManager.adapter
    private val openedGatts: MutableList<BluetoothGatt> = mutableListOf()
    private var serviceDiscoveredFlow = MutableStateFlow(false)
    private var connectionStateFlow = MutableStateFlow(STATE_DISCONNECTED)
    private var dckSpsmFlow = MutableStateFlow(0)
    private var dckSpsm = 0
    private var connectionHandle = BluetoothDevice.ERROR
    private lateinit var advertiseContext: GrpcContext.CancellableContext
    private lateinit var connectionResponse: WaitConnectionResponse
    private lateinit var host: Host

    // Gives shell permissions during the test.
    @Rule(order = 0)
    @JvmField
    val mPermissionRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

    // Setup a Bumble Pandora device for the duration of the test.
    @Rule(order = 1) @JvmField val mBumble = PandoraDevice()

    // Toggles Bluetooth.
    @Rule(order = 2) @JvmField val EnableBluetoothRule = EnableBluetoothRule(false, true)

    /** Wrapper for [BluetoothGatt] along with its [state] and [status] */
    data class GattState(val gatt: BluetoothGatt, val status: Int, val state: Int)

    override fun close() {
        scope.cancel("Cancelling test scope")
    }

    @Before
    fun setUp() {

        host = Host(context)

        mBumble
            .dckBlocking()
            .withDeadline(Deadline.after(GRPC_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS))
            .register(Empty.getDefaultInstance())

        // Advertise the Bumble
        advertiseContext = mBumble.advertise()

        // Connect to GATT (Generic Attribute Profile) on Bumble.
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        val gatt = connectGatt(remoteDevice)
        readDckSpsm(gatt)
        openedGatts.add(gatt)
        assertThat(dckSpsm).isGreaterThan(0)
    }

    @After
    fun tearDown() {
        advertiseContext.cancel(null)
        for (gatt in openedGatts) {
            gatt.disconnect()
            gatt.close()
        }
        openedGatts.clear()
    }

    @Test
    @VirtualOnly
    fun testSend() {
        Log.d(TAG, "testSend")
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        Log.d(TAG, "testSend: Connect L2CAP")
        val bluetoothSocket = createSocket(dckSpsm, remoteDevice)
        runBlocking {
            val waitFlow = flow { emit(waitConnection(dckSpsm, remoteDevice)) }
            val connectJob =
                scope.launch {
                    // give some time for Bumble to host the socket server
                    Thread.sleep(200)
                    bluetoothSocket.connect()
                    Log.d(TAG, "testSend: Bluetooth socket connected")
                }
            connectionResponse = waitFlow.first()
            // Wait for the connection to complete
            connectJob.join()
        }
        assertThat(connectionResponse).isNotNull()
        assertThat(connectionResponse.hasChannel()).isTrue()

        val channel = connectionResponse.channel
        val sampleData = "cafe-baguette".toByteArray()

        val receiveObserver = StreamObserverSpliterator<ReceiveResponse>()
        mBumble
            .l2cap()
            .receive(ReceiveRequest.newBuilder().setChannel(channel).build(), receiveObserver)

        Log.d(TAG, "testSend: Send data from Android to Bumble")
        val outputStream = bluetoothSocket.outputStream
        outputStream.write(sampleData)
        outputStream.flush()

        Log.d(TAG, "testSend: waitReceive data on Bumble")
        val receiveData = receiveObserver.iterator().next()
        assertThat(receiveData.data.toByteArray()).isEqualTo(sampleData)

        bluetoothSocket.close()
        Log.d(TAG, "testSend: waitDisconnection")
        val waitDisconnectionRequest =
            WaitDisconnectionRequest.newBuilder().setChannel(channel).build()
        val disconnectionResponse =
            mBumble.l2capBlocking().waitDisconnection(waitDisconnectionRequest)
        assertThat(disconnectionResponse.hasSuccess()).isTrue()
        Log.d(TAG, "testSend: done")
    }

    @Test
    @VirtualOnly
    fun testReceive() {
        Log.d(TAG, "testReceive: Connect L2CAP")
        var bluetoothSocket: BluetoothSocket?
        val l2capServer = bluetoothAdapter.listenUsingInsecureL2capChannel()
        val socketFlow = flow { emit(l2capServer.accept()) }
        val connectResponse = createAndConnectL2capChannelWithBumble(l2capServer.psm)
        runBlocking {
            bluetoothSocket = socketFlow.first()
            assertThat(connectResponse.hasChannel()).isTrue()
        }

        val inputStream = bluetoothSocket!!.inputStream
        val sampleData: ByteString = ByteString.copyFromUtf8("cafe-baguette")
        val buffer = ByteArray(sampleData.size())

        val sendRequest =
            SendRequest.newBuilder().setChannel(connectResponse.channel).setData(sampleData).build()
        Log.d(TAG, "testReceive: Send data from Bumble to Android")
        mBumble.l2capBlocking().send(sendRequest)

        Log.d(TAG, "testReceive: Receive data on Android")
        val read = inputStream.read(buffer)
        assertThat(ByteString.copyFrom(buffer).substring(0, read)).isEqualTo(sampleData)

        Log.d(TAG, "testReceive: disconnect")
        val disconnectRequest =
            DisconnectRequest.newBuilder().setChannel(connectResponse.channel).build()
        val disconnectResponse = mBumble.l2capBlocking().disconnect(disconnectRequest)
        assertThat(disconnectResponse.hasSuccess()).isTrue()
        inputStream.close()
        bluetoothSocket?.close()
        l2capServer.close()
        Log.d(TAG, "testReceive: done")
    }

    @Test
    @VirtualOnly
    fun testReadReturnOnRemoteSocketDisconnect() {
        Log.d(TAG, "testReadReturnonSocketDisconnect: Connect L2CAP")
        var bluetoothSocket: BluetoothSocket?
        val l2capServer = bluetoothAdapter.listenUsingInsecureL2capChannel()
        val socketFlow = flow { emit(l2capServer.accept()) }
        val connectResponse = createAndConnectL2capChannelWithBumble(l2capServer.psm)
        runBlocking {
            bluetoothSocket = socketFlow.first()
            assertThat(connectResponse.hasChannel()).isTrue()
        }

        val inputStream = bluetoothSocket!!.inputStream

        // block on read() on server thread
        val readThread = Thread {
            Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: Receive data on Android")
            val ret = inputStream.read()
            Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: read returns : " + ret)
            Log.d(
                TAG,
                "testReadReturnOnRemoteSocketDisconnect: isConnected() : " +
                    bluetoothSocket!!.isConnected(),
            )
            assertThat(ret).isEqualTo(-1)
            assertThat(bluetoothSocket!!.isConnected()).isFalse()
        }
        readThread.start()
        // check that socket is still connected
        assertThat(bluetoothSocket!!.isConnected()).isTrue()

        // read() would be blocking till underlying l2cap is disconnected
        Thread.sleep(1000 * 10)
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: disconnect after 10 secs")
        val disconnectRequest =
            DisconnectRequest.newBuilder().setChannel(connectResponse.channel).build()
        val disconnectResponse = mBumble.l2capBlocking().disconnect(disconnectRequest)
        assertThat(disconnectResponse.hasSuccess()).isTrue()
        inputStream.close()
        bluetoothSocket?.close()
        l2capServer.close()
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: done")
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SOCKET_SETTINGS_API)
    fun testSendOverEncryptedOnlySocket() {
        Log.d(TAG, "testSendOverEncryptedOnlySocket")
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        host.createBondAndVerify(remoteDevice)

        Log.d(TAG, "testSendOverEncryptedOnlySocket: Connect L2CAP")
        val bluetoothSocket =
            createClientSocketUsingSocketSettings(dckSpsm, remoteDevice, true, false)
        runBlocking {
            val waitFlow = flow { emit(waitConnection(dckSpsm, remoteDevice)) }
            val connectJob =
                scope.launch {
                    // give some time for Bumble to host the socket server
                    Thread.sleep(200)
                    bluetoothSocket.connect()
                    Log.d(TAG, "testSendOverEncryptedOnlySocket: Bluetooth socket connected")
                }
            connectionResponse = waitFlow.first()
            // Wait for the connection to complete
            connectJob.join()
        }
        assertThat(connectionResponse).isNotNull()
        assertThat(connectionResponse.hasChannel()).isTrue()

        val channel = connectionResponse.channel
        val sampleData = "cafe-baguette".toByteArray()

        val receiveObserver = StreamObserverSpliterator<ReceiveResponse>()
        mBumble
            .l2cap()
            .receive(ReceiveRequest.newBuilder().setChannel(channel).build(), receiveObserver)

        Log.d(TAG, "testSendOverEncryptedOnlySocket: Send data from Android to Bumble")
        val outputStream = bluetoothSocket.outputStream
        outputStream.write(sampleData)
        outputStream.flush()

        Log.d(TAG, "testSendOverEncryptedOnlySocket: waitReceive data on Bumble")
        val receiveData = receiveObserver.iterator().next()
        assertThat(receiveData.data.toByteArray()).isEqualTo(sampleData)

        bluetoothSocket.close()
        Log.d(TAG, "testSendOverEncryptedOnlySocket: waitDisconnection")
        val waitDisconnectionRequest =
            WaitDisconnectionRequest.newBuilder().setChannel(channel).build()
        val disconnectionResponse =
            mBumble.l2capBlocking().waitDisconnection(waitDisconnectionRequest)
        assertThat(disconnectionResponse.hasSuccess()).isTrue()
        host.removeBondAndVerify(remoteDevice)
        Log.d(TAG, "testSendOverEncryptedOnlySocket: done")
    }

    private fun createAndConnectL2capChannelWithBumble(psm: Int): ConnectResponse {
        Log.d(TAG, "createAndConnectL2capChannelWithBumble")
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        val connectionHandle = remoteDevice.getConnectionHandle(BluetoothDevice.TRANSPORT_LE)
        val handle = intToByteArray(connectionHandle, ByteOrder.BIG_ENDIAN)
        val cookie = Any.newBuilder().setValue(ByteString.copyFrom(handle)).build()
        val connection = Connection.newBuilder().setCookie(cookie).build()
        val leCreditBased =
            CreditBasedChannelRequest.newBuilder()
                .setSpsm(psm)
                .setInitialCredit(INITIAL_CREDITS)
                .setMtu(MTU)
                .setMps(MPS)
                .build()
        val connectRequest =
            ConnectRequest.newBuilder()
                .setConnection(connection)
                .setLeCreditBased(leCreditBased)
                .build()
        return mBumble.l2capBlocking().connect(connectRequest)
    }

    private fun readDckSpsm(gatt: BluetoothGatt) = runBlocking {
        Log.d(TAG, "readDckSpsm")
        launch {
            withTimeout(GRPC_TIMEOUT) { connectionStateFlow.first { it == STATE_CONNECTED } }
            Log.i(TAG, "Connected to GATT")
            gatt.discoverServices()
            withTimeout(GRPC_TIMEOUT) { serviceDiscoveredFlow.first { it == true } }
            Log.i(TAG, "GATT services discovered")
            val service = gatt.getService(CCC_DK_UUID)
            assertThat(service).isNotNull()
            val characteristic = service.getCharacteristic(SPSM_UUID)
            gatt.readCharacteristic(characteristic)
            withTimeout(GRPC_TIMEOUT) { dckSpsmFlow.first { it != 0 } }
            dckSpsm = dckSpsmFlow.value
            Log.i(TAG, "spsm read, spsm=$dckSpsm")
        }
    }

    private suspend fun waitConnection(
        psm: Int,
        remoteDevice: BluetoothDevice,
    ): WaitConnectionResponse {
        Log.d(TAG, "waitConnection")
        val connectionHandle = remoteDevice.getConnectionHandle(BluetoothDevice.TRANSPORT_LE)
        val handle = intToByteArray(connectionHandle, ByteOrder.BIG_ENDIAN)
        val cookie = Any.newBuilder().setValue(ByteString.copyFrom(handle)).build()
        val connection = Connection.newBuilder().setCookie(cookie).build()
        val leCreditBased =
            CreditBasedChannelRequest.newBuilder()
                .setSpsm(psm)
                .setInitialCredit(INITIAL_CREDITS)
                .setMtu(MTU)
                .setMps(MPS)
                .build()
        val waitConnectionRequest =
            WaitConnectionRequest.newBuilder()
                .setConnection(connection)
                .setLeCreditBased(leCreditBased)
                .build()
        Log.i(TAG, "Sending request to Bumble to create server and wait for connection")
        return mBumble.l2capBlocking().waitConnection(waitConnectionRequest)
    }

    private fun createListeningChannelUsingSocketSettings(
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ): BluetoothServerSocket {
        var socket: BluetoothServerSocket

        socket =
            bluetoothAdapter.listenUsingSocketSettings(
                BluetoothSocketSettings.Builder()
                    .setSocketType(BluetoothSocket.TYPE_L2CAP_LE)
                    .setEncryptionRequired(isEncrypted)
                    .setAuthenticationRequired(isAuthenticated)
                    .build()
            )

        return socket
    }

    private fun createClientSocketUsingSocketSettings(
        psm: Int,
        remoteDevice: BluetoothDevice,
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ): BluetoothSocket {
        var socket: BluetoothSocket

        socket =
            remoteDevice.createUsingSocketSettings(
                BluetoothSocketSettings.Builder()
                    .setSocketType(BluetoothSocket.TYPE_L2CAP_LE)
                    .setEncryptionRequired(isEncrypted)
                    .setAuthenticationRequired(isAuthenticated)
                    .setL2capPsm(psm)
                    .build()
            )

        return socket
    }

    private fun createSocket(
        psm: Int,
        remoteDevice: BluetoothDevice,
        isSecure: Boolean = false,
    ): BluetoothSocket {
        var socket: BluetoothSocket
        var expectedType: Int
        if (isSecure) {
            socket = remoteDevice.createL2capChannel(psm)
            expectedType = BluetoothSocket.TYPE_L2CAP_LE
        } else {
            socket = remoteDevice.createInsecureL2capChannel(psm)
            expectedType = BluetoothSocket.TYPE_L2CAP
        }
        assertThat(socket.getConnectionType()).isEqualTo(expectedType)
        return socket
    }

    private fun connectGatt(remoteDevice: BluetoothDevice): BluetoothGatt {
        Log.d(TAG, "connectGatt")
        val gattCallback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    Log.i(TAG, "Connection state changed to $newState.")
                    connectionStateFlow.value = newState
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {

                    Log.i(TAG, "Discovering services status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Services have been discovered")
                        serviceDiscoveredFlow.value = true
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    Log.i(TAG, "onCharacteristicRead, status: $status")

                    if (characteristic.getUuid() == SPSM_UUID) {
                        // CCC Specification Digital-Key R3-1.2.3
                        // 19.2.1.6 DK Service
                        dckSpsmFlow.value = byteArrayToInt(value, ByteOrder.BIG_ENDIAN)
                    }
                }
            }

        return remoteDevice.connectGatt(context, false, gattCallback)
    }

    fun byteArrayToInt(byteArray: ByteArray, order: ByteOrder): Int {
        val buffer = ByteBuffer.wrap(byteArray)
        buffer.order(order)
        return buffer.short.toInt()
    }

    private fun intToByteArray(value: Int, order: ByteOrder): ByteArray {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        buffer.order(order)
        buffer.putInt(value)
        return buffer.array()
    }

    companion object {
        private const val TAG = "DckL2capTest"
        private const val INITIAL_CREDITS = 256
        private const val MTU = 2048 // Default Maximum Transmission Unit.
        private const val MPS = 2048 // Default Maximum payload size.

        private val GRPC_TIMEOUT = 10.seconds
        private val CHANNEL_READ_TIMEOUT = 30.seconds

        // CCC DK Specification R3 1.2.0 r14 section 19.2.1.2 Bluetooth Le Pairing
        private val CCC_DK_UUID = UUID.fromString("0000FFF5-0000-1000-8000-00805f9b34fb")
        // Vehicule SPSM
        private val SPSM_UUID = UUID.fromString("D3B5A130-9E23-4B3A-8BE4-6B1EE5F980A3")
    }
}
