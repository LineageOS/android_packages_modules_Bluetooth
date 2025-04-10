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
import pandora.l2cap.L2CAPProto.Channel
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

    data class SocketServerDetails(
        val listenSocket: BluetoothServerSocket,
        val bluetoothSocket: BluetoothSocket,
        val channel: Channel,
    )

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
        host.close()
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create L2CAP server on Bumble (DCK L2cap Server)
     * - create Insecure Client Bluetooth Socket & initiate connection
     * - Ensure socket is connected
     */
    fun testConnectInsecure() {
        Log.d(TAG, "testConnectInsecure")
        val (socket, channel) = clientSocketConnectUtil(false)
        Log.d(TAG, "testConnectInsecure: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create L2CAP server on Bumble (DCK L2cap Server)
     * - create insecure Client Bluetooth Socket & initiate connection on phone
     * - Ensure socket is connected
     * - Initiate disconnection from phone side
     */
    fun testConnectInsecureClientLocalDisconnect() {
        Log.d(TAG, "testConnectInsecureClientLocalDisconnect")
        val (bluetoothSocket, channel) = clientSocketConnectUtil(false)

        assertThat((bluetoothSocket).isConnected()).isTrue()
        Log.d(TAG, "testConnectInsecureClientLocalDisconnect: close/disconnect")
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()
        Log.d(TAG, "testConnectInsecureClientLocalDisconnect: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create L2CAP server on Bumble (DCK L2cap Server)
     * - create insecure Client Bluetooth Socket & initiate connection on phone
     * - Ensure socket is connected
     * - Initiate disconnection from Bumble side
     */
    fun testConnectInsecureClientRemoteDisconnect() {
        Log.d(TAG, "testConnectInsecureClientRemoteDisconnect")
        val (bluetoothSocket, channel) = clientSocketConnectUtil(false)

        assertThat((bluetoothSocket).isConnected()).isTrue()
        Log.d(TAG, "testConnectInsecureClientRemoteDisconnect: close/disconnect")
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel, true)

        // Todo: b/401106684
        // assertThat((bluetoothSocket).isConnected()).isFalse()
        Log.d(TAG, "testConnectInsecureClientRemoteDisconnect: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone
     * - Use Bumble as client and trigger connection to L2cap server on Phone
     * - Ensure connection is established
     */
    fun testAcceptInsecure() {
        Log.d(TAG, "testAcceptInsecure: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)
        assertThat((bluetoothSocket)?.isConnected()).isTrue()

        bluetoothSocket.close()
        l2capServer.close()
        Log.d(TAG, "testAcceptInsecure: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone
     * - Use Bumble as client and trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - trigger disconnection by closing the socket handle from Phone side
     * - Ensure L2cap connection is disconnected
     */
    fun testAcceptInsecureLocalDisconnect() {
        Log.d(TAG, "testAcceptInsecureLocalDisconnect: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)
        Log.d(TAG, "testAcceptInsecureLocalDisconnect: close/disconnect")
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket)?.isConnected()).isFalse()
        Log.d(TAG, "testAcceptInsecureLocalDisconnect: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone
     * - Use Bumble as client nd trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - trigger disconnection by closing the socket handle from Bumble/Remote side
     * - Ensure L2cap connection is disconnected
     */
    fun testAcceptInsecureRemoteDisconnect() {
        Log.d(TAG, "testAcceptInsecureRemoteDisconnect: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)
        Log.d(TAG, "testAcceptInsecureRemoteDisconnect: close/disconnect from remote")
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel, true)

        // Todo: b/401106684
        // assertThat((bluetoothSocket)?.isConnected()).isFalse()

        l2capServer.close()
        Log.d(TAG, "testAcceptInsecureRemoteDisconnect: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create L2CAP Socket server on Bumble (DCK server)
     * - create insecure l2cap client and connect to l2cap on Bumble server
     * - Ensure connection is established
     * - Send sample data from Phone to bumble
     * - ensure the Data received on Bumble side as expected
     * - disconnect the socket by invoking close
     * - Ensure L2cap connection is disconnected
     */
    fun testSendOverInsecureSocketAsClient() {
        Log.d(TAG, "testSendOverInsecureSocketAsClient")
        val (bluetoothSocket, channel) = clientSocketConnectUtil(false)

        assertThat((bluetoothSocket).isConnected()).isTrue()
        Log.d(TAG, "testSendOverInsecureSocketAsClient: close/disconnect")

        sendDataFromPhoneToBumbleAndVerifyUtil(bluetoothSocket, channel)
        // disconnect socket from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        bluetoothSocket?.close()
        Log.d(TAG, "testSendOverInsecureSocketAsClient: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create L2CAP Socket server on Bumble (DCK server)
     * - create insecure l2cap client and connect to l2cap on Bumble server
     * - Ensure connection is established
     * - Send sample data from Bumble to Phone
     * - ensure the Data received on Phone side as expected
     * - disconnect the socket by invoking close
     * - Ensure L2cap connection is disconnected
     */
    fun testReceiveOverInsecureSocketAsClient() {
        Log.d(TAG, "testReceiveOverInsecureSocketAsClient")
        val (bluetoothSocket, channel) = clientSocketConnectUtil(false)

        assertThat((bluetoothSocket).isConnected()).isTrue()
        Log.d(TAG, "testReceiveOverInsecureSocketAsClient: close/disconnect")

        sendDataFromBumbleToPhoneAndVerifyUtil(bluetoothSocket, channel)
        // disconnect socket from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        bluetoothSocket?.close()
        Log.d(TAG, "testReceiveOverInsecureSocketAsClient: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone
     * - Use Bumble as client nd trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - Send sample data from Phone to Bumble
     * - Ensure data is received on Bumble side as expected
     * - close the socket connection from phone
     * - Ensure L2cap connection is disconnected
     */
    fun testSendOverInsecureSocketAsServer() {
        Log.d(TAG, "testReceiveOverInsecureSocketAsServer: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)

        sendDataFromPhoneToBumbleAndVerifyUtil(bluetoothSocket, channel)
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        bluetoothSocket?.close()
        Log.d(TAG, "testReceiveOverInsecureSocketAsServer: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone
     * - Use Bumble as client nd trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - Send sample data from Bumble to phone
     * - Ensure data is received on phone side as expected
     * - close the socket connection from phone
     * - Ensure L2cap connection is disconnected
     */
    fun testReceiveOverInsecureSocketAsServer() {
        Log.d(TAG, "testReceiveOverInsecureSocketAsServer: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)

        sendDataFromBumbleToPhoneAndVerifyUtil(bluetoothSocket, channel)
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        bluetoothSocket?.close()
        Log.d(TAG, "testReceiveOverInsecureSocketAsServer: done")
    }

    @Test
    @VirtualOnly
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone
     * - Use Bumble as client nd trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - Start reading on phone side
     * - Trigger disconnect from Bumble side
     * - Ensure read() on returns -1
     * - close the socket connection from phone
     * - Ensure L2cap connection is disconnected and Socket state is disconnected
     */
    fun testReadReturnOnRemoteSocketDisconnect() {
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)

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
        Thread.sleep(1000 * 2)
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: disconnect after 10 secs")
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel, true)
        assertThat((bluetoothSocket).isConnected()).isFalse()
        inputStream.close()
        bluetoothSocket?.close()
        l2capServer.close()
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: done")
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SOCKET_SETTINGS_API)
    /**
     * Test:
     * - Create Bond between Phone and Bumble (Just works)
     * - Create L2cap Server on Bumble side (DCK server) and wait for connection
     * - Create Encrypt Only socket using BluetoothSocketSettings interface
     * - trigger connection from client socket on phone to l2cap server on Bumble
     * - Ensure connection is established
     * - Send sample data from phone to bumble & ensure It is received on bumble side as expected
     * - close the connection
     * - Ensure L2cap connection is disconnected and Socket state is disconnected
     * - remote bonding
     */
    fun testSendOverEncryptedOnlySocketAsClient() {
        Log.d(TAG, "testSendOverEncryptedOnlySocketAsClient")
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        host.createBondAndVerify(remoteDevice)

        Log.d(TAG, "testSendOverEncryptedOnlySocket: Connect L2CAP")
        val (bluetoothSocket, channel) = clientSocketConnectUtilUsingSocketSettings(false, true)

        Log.d(TAG, "testSendOverEncryptedOnlySocket: send data from phone to bumble")
        sendDataFromPhoneToBumbleAndVerifyUtil(bluetoothSocket, channel)

        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        host.removeBondAndVerify(remoteDevice)
        Log.d(TAG, "testSendOverEncryptedOnlySocket: done")
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SOCKET_SETTINGS_API)
    /**
     * Test:
     * - Create Bond between Phone and Bumble (Just works)
     * - Create L2cap Server on Bumble side (DCK server) and wait for connection
     * - Create Encrypt Only socket using BluetoothSocketSettings interface
     * - trigger connection from client socket on phone to l2cap server on Bumble
     * - Ensure connection is established
     * - Send sample data from Bumble to phone & ensure It is received on bumble side as expected
     * - close the connection
     * - Ensure L2cap connection is disconnected and Socket state is disconnected
     * - remote bonding
     */
    fun testReceiveOverEncryptedOnlySocketAsClient() {
        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsClient")
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        host.createBondAndVerify(remoteDevice)

        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsClient: Connect L2CAP")
        val (bluetoothSocket, channel) = clientSocketConnectUtilUsingSocketSettings(false, true)

        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsClient: send data from phone to bumble")
        sendDataFromBumbleToPhoneAndVerifyUtil(bluetoothSocket, channel)

        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        host.removeBondAndVerify(remoteDevice)
        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsClient: done")
    }

    // Utility functions
    private fun clientSocketConnectUtil(isSecure: Boolean = false): Pair<BluetoothSocket, Channel> {
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        Log.d(TAG, "clientConnect: Connect L2CAP")
        val bluetoothSocket = createSocket(dckSpsm, remoteDevice, isSecure)
        runBlocking {
            val waitFlow = flow { emit(waitConnection(dckSpsm, remoteDevice)) }
            val connectJob =
                scope.launch {
                    // give some time for Bumble to host the socket server
                    Thread.sleep(200)
                    bluetoothSocket.connect()
                    Log.d(TAG, "clientConnect: Bluetooth socket connected")
                }
            connectionResponse = waitFlow.first()
            // Wait for the connection to complete
            connectJob.join()
        }
        assertThat(connectionResponse).isNotNull()
        assertThat(connectionResponse.hasChannel()).isTrue()
        assertThat((bluetoothSocket).isConnected()).isTrue()

        val channel = connectionResponse.channel
        return Pair(bluetoothSocket, channel)
    }

    private fun clientSocketConnectUtilUsingSocketSettings(
        isAuthenticated: Boolean = false,
        isEncrypted: Boolean = false,
    ): Pair<BluetoothSocket, Channel> {
        val remoteDevice =
            bluetoothAdapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        Log.d(TAG, "clientConnect: Connect L2CAP")
        val bluetoothSocket =
            createClientSocketUsingSocketSettings(
                dckSpsm,
                remoteDevice,
                isEncrypted,
                isAuthenticated,
            )
        runBlocking {
            val waitFlow = flow { emit(waitConnection(dckSpsm, remoteDevice)) }
            val connectJob =
                scope.launch {
                    // give some time for Bumble to host the socket server
                    Thread.sleep(200)
                    bluetoothSocket.connect()
                    Log.d(TAG, "clientConnect: Bluetooth socket connected")
                }
            connectionResponse = waitFlow.first()
            // Wait for the connection to complete
            connectJob.join()
        }
        assertThat(connectionResponse).isNotNull()
        assertThat(connectionResponse.hasChannel()).isTrue()
        assertThat((bluetoothSocket).isConnected()).isTrue()

        val channel = connectionResponse.channel
        return Pair(bluetoothSocket, channel)
    }

    private fun disconnectSocketAndWaitForDisconnectUtil(
        bluetoothSocket: BluetoothSocket,
        channel: Channel,
        isRemote: Boolean = false,
    ) {
        if (isRemote == false) {
            bluetoothSocket.close()
        } else {
            val disconnectRequest = DisconnectRequest.newBuilder().setChannel(channel).build()
            val disconnectResponse = mBumble.l2capBlocking().disconnect(disconnectRequest)
            assertThat(disconnectResponse.hasSuccess()).isTrue()
        }
        Log.d(TAG, "disconnectSocketAndWaitForDisconnectUtil: waitDisconnection")
        val waitDisconnectionRequest =
            WaitDisconnectionRequest.newBuilder().setChannel(channel).build()
        val disconnectionResponse =
            mBumble.l2capBlocking().waitDisconnection(waitDisconnectionRequest)
        assertThat(disconnectionResponse.hasSuccess()).isTrue()
    }

    private fun tearDownServerSocketAndWaitForDisconnectUtil(
        l2capServer: BluetoothServerSocket,
        channel: Channel,
    ) {
        l2capServer.close()
        Log.d(TAG, "tearDownServerSocketAndWaitForDisconnectUtil: waitDisconnection")
        val waitDisconnectionRequest =
            WaitDisconnectionRequest.newBuilder().setChannel(channel).build()
        val disconnectionResponse =
            mBumble.l2capBlocking().waitDisconnection(waitDisconnectionRequest)
        assertThat(disconnectionResponse.hasSuccess()).isTrue()
    }

    private fun l2capServerOnPhoneAndConnectionFromBumbleUtil(
        isSecure: Boolean = false
    ): SocketServerDetails {
        var bluetoothSocket: BluetoothSocket
        val channel: Channel
        val l2capServer = bluetoothAdapter.listenUsingInsecureL2capChannel()
        val socketFlow = flow { emit(l2capServer.accept()) }
        val connectResponse = createAndConnectL2capChannelWithBumble(l2capServer.psm)
        runBlocking {
            bluetoothSocket = socketFlow.first()
            assertThat(connectResponse.hasChannel()).isTrue()
        }

        return SocketServerDetails(l2capServer, bluetoothSocket, connectResponse.channel)
    }

    private fun sendDataFromPhoneToBumbleAndVerifyUtil(
        bluetoothSocket: BluetoothSocket,
        channel: Channel,
    ) {
        val sampleData = "cafe-baguette".toByteArray()

        val receiveObserver = StreamObserverSpliterator<ReceiveResponse>()
        mBumble
            .l2cap()
            .receive(ReceiveRequest.newBuilder().setChannel(channel).build(), receiveObserver)

        Log.d(TAG, "sendDataFromPhoneToBumbleAndVerify: Send data from Android to Bumble")
        val outputStream = bluetoothSocket.outputStream
        outputStream.write(sampleData)
        outputStream.flush()

        Log.d(TAG, "sendDataFromPhoneToBumbleAndVerify: waitReceive data on Bumble")
        val receiveData = receiveObserver.iterator().next()
        assertThat(receiveData.data.toByteArray()).isEqualTo(sampleData)
        outputStream.close()
    }

    private fun sendDataFromBumbleToPhoneAndVerifyUtil(
        bluetoothSocket: BluetoothSocket,
        channel: Channel,
    ) {
        val inputStream = bluetoothSocket!!.inputStream
        val sampleData: ByteString = ByteString.copyFromUtf8("cafe-baguette")
        val buffer = ByteArray(sampleData.size())

        val sendRequest = SendRequest.newBuilder().setChannel(channel).setData(sampleData).build()
        Log.d(TAG, "sendDataFromBumbleToPhoneAndVerifyUtil: Send data from Bumble to Android")
        mBumble.l2capBlocking().send(sendRequest)

        Log.d(TAG, "sendDataFromBumbleToPhoneAndVerifyUtil: Receive data on Android")
        val read = inputStream.read(buffer)
        assertThat(ByteString.copyFrom(buffer).substring(0, read)).isEqualTo(sampleData)
        inputStream.close()
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
        // Vehicle SPSM
        private val SPSM_UUID = UUID.fromString("D3B5A130-9E23-4B3A-8BE4-6B1EE5F980A3")
    }
}
