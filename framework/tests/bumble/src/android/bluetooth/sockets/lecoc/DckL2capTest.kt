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
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import io.grpc.Context as GrpcContext
import io.grpc.Deadline
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.Boolean
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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Ignore
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
class DckL2capTest() : Closeable {

    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1)
    val permissionRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

    @get:Rule(order = 2) val bumble = PandoraDevice()

    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val openedGatts: MutableList<BluetoothGatt> = mutableListOf()
    private var serviceDiscoveredFlow = MutableStateFlow(false)
    private var connectionStateFlow = MutableStateFlow(STATE_DISCONNECTED)
    private var dckSpsmFlow = MutableStateFlow(0)
    private var dckSpsm = 0
    private var connectionHandle = BluetoothDevice.ERROR
    private lateinit var advertiseContext: GrpcContext.CancellableContext
    private lateinit var connectionResponse: WaitConnectionResponse
    private lateinit var host: Host
    private var FIXED_PSM = 0xFD

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

        bumble
            .dckBlocking()
            .withDeadline(Deadline.after(GRPC_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS))
            .register(Empty.getDefaultInstance())

        // Advertise the Bumble
        advertiseContext = bumble.advertise()

        // Connect to GATT (Generic Attribute Profile) on Bumble.
        val remoteDevice =
            adapter.getRemoteLeDevice(
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
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: disconnect after 2 secs")
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel, true)
        assertThat((bluetoothSocket).isConnected()).isFalse()
        inputStream.close()
        bluetoothSocket?.close()
        l2capServer.close()
        Log.d(TAG, "testReadReturnOnRemoteSocketDisconnect: done")
    }

    @Test
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
     * - remove bonding
     */
    fun testSendOverEncryptedOnlySocketAsClient() {
        Log.d(TAG, "testSendOverEncryptedOnlySocketAsClient")
        val remoteDevice =
            adapter.getRemoteLeDevice(
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
     * - remove bonding
     */
    fun testReceiveOverEncryptedOnlySocketAsClient() {
        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsClient")
        val remoteDevice =
            adapter.getRemoteLeDevice(
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

    @Test
    /**
     * Test:
     * - Create Bond between Phone and Bumble (Just works)
     * - Create L2cap Server on Bumble side (DCK server) and wait for connection
     * - Create Encrypt Only socket using BluetoothSocketSettings interface
     * - trigger connection from client socket on phone to l2cap server on Bumble
     * - Ensure connection is established
     * - Send sample data from Phone to Bumble & ensure It is received on bumble side as expected
     * - close the connection
     * - Ensure L2cap connection is disconnected and Socket state is disconnected
     * - remove bonding
     */
    fun testSendOverEncryptedOnlySocketAsServer() {
        Log.d(TAG, "testSendOverEncryptedOnlySocketAsServer")
        val remoteDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        host.createBondAndVerify(remoteDevice)

        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(false, true)

        Log.d(TAG, "testSendOverEncryptedOnlySocketAsServer: send data from Bumble to Phone")
        sendDataFromPhoneToBumbleAndVerifyUtil(bluetoothSocket, channel)
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        l2capServer.close()
        host.removeBondAndVerify(remoteDevice)
        Log.d(TAG, "testSendOverEncryptedOnlySocketAsServer: done")
    }

    @Test
    @Ignore
    @RequiresFlagsEnabled("com.android.bluetooth.flags.lecoc_with_fixed_psm")
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone with given Fixed PSM (0xFD)
     * - Use Bumble as client and trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - Start reading on phone side
     * - Trigger disconnect from Bumble side
     * - close the socket connection from phone
     * - Ensure L2cap connection is disconnected and Socket state is disconnected
     */
    fun testSendOverInsecureSocketAsServerWithFixedPsm() {
        Log.d(TAG, "testSendOverInsecureSocketAsServerWithFixedPsm")

        setSystemProperty(PROPERTY_FIXED_PSM_SLOTS, "8")
        val remoteDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(
                false,
                false,
                FIXED_PSM,
            )

        Log.d(TAG, "testSendOverInsecureSocketAsServerWithFixedPsm: psm " + l2capServer.psm)
        assertThat(l2capServer.psm).isEqualTo(FIXED_PSM)
        Log.d(TAG, "testSendOverEncryptedOnlySocketAsServer: send data from Bumble to Phone")
        sendDataFromPhoneToBumbleAndVerifyUtil(bluetoothSocket, channel)
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        l2capServer.close()

        setSystemProperty(PROPERTY_FIXED_PSM_SLOTS, "0")
        Log.d(TAG, "testSendOverInsecureSocketAsServerWithFixedPsm: done")
    }

    @Test
    @Ignore
    @RequiresFlagsEnabled("com.android.bluetooth.flags.lecoc_with_fixed_psm")
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone with given Fixed PSM (0xFD)
     * - Use Bumble as client and trigger connection to L2cap server on Phone
     * - Ensure connection is established
     * - Try to host another L2CAP socket server on Phone on the same PSM(0xFD) again
     * - Ensure Socket server creation fails with exception
     * - Close the socket server created
     */
    fun testServerWithFixedPsmWhenAlreadyReserved() {
        Log.d(TAG, "testServerWithFixedPsmWhenAlreadyReserved")

        setSystemProperty(PROPERTY_FIXED_PSM_SLOTS, "8")
        val remoteDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(
                false,
                false,
                FIXED_PSM,
            )

        Log.d(TAG, "testServerWithFixedPsmWhenAlreadyReserved: psm " + l2capServer.psm)
        assertThat(l2capServer.psm).isEqualTo(FIXED_PSM)
        // Create another Server with Same L2cap Fixed PSM
        assertThrows(IOException::class.java) {
            val (l2capServer1, bluetoothSocket1, channel1) =
                l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(
                    false,
                    false,
                    FIXED_PSM,
                )
        }

        l2capServer.close()
        setSystemProperty(PROPERTY_FIXED_PSM_SLOTS, "0")
        Log.d(TAG, "testServerWithFixedPsmWhenAlreadyReserved: done")
    }

    @Test
    @Ignore
    @RequiresFlagsEnabled("com.android.bluetooth.flags.lecoc_with_fixed_psm")
    /**
     * Test:
     * - Create insecure L2CAP Socket server on Phone with given Fixed invalid PSM (0x88, which is
     *   not in the valid fixed PSM range)
     * - Ensure SOcket server creation fails with exception
     * - Close the socket server created
     */
    fun testServerWithInvalidFixedPsm() {
        Log.d(TAG, "testServerWithInvalidFixedPsm")
        setSystemProperty(PROPERTY_FIXED_PSM_SLOTS, "8")
        val remoteDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(false, false, 0x88)

        Log.d(TAG, "testServerWithInvalidFixedPsm: psm " + l2capServer.psm)
        assertThat(l2capServer.psm).isNotEqualTo(FIXED_PSM)

        l2capServer.close()
        setSystemProperty(PROPERTY_FIXED_PSM_SLOTS, "0")
        Log.d(TAG, "testServerWithInvalidFixedPsm: done")
    }

    @Test
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
     * - remove bonding
     */
    fun testReceiveOverEncryptedOnlySocketAsServer() {
        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsServer")
        val remoteDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )

        host.createBondAndVerify(remoteDevice)

        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(false, true)

        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsServer: send data from Bumble to Phone")
        sendDataFromBumbleToPhoneAndVerifyUtil(bluetoothSocket, channel)
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)
        assertThat((bluetoothSocket).isConnected()).isFalse()

        l2capServer.close()
        host.removeBondAndVerify(remoteDevice)
        Log.d(TAG, "testReceiveOverEncryptedOnlySocketAsServer: done")
    }

    @Test
    @VirtualOnly
    fun testBluetoothSocketAvailable() {
        Log.d(TAG, "testBluetoothSocketAvailable: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)

        val inputStream = bluetoothSocket!!.inputStream
        val sampleData: ByteString = ByteString.copyFromUtf8("cafe-place")

        sendDataFromBumbleToPhoneUtil(bluetoothSocket, channel, sampleData)

        // Ensure that data len available is equal to what is written from client
        assertThat(inputStream.available()).isEqualTo(sampleData.size())

        Log.d(TAG, "testBluetoothSocketAvailable: Receive data on Android")
        // read 1 byte
        val read = inputStream.read()

        // Ensure that data len available is is 1 less, as 1 byte read in previous call
        assertThat(inputStream.available()).isEqualTo(sampleData.size() - 1)

        // write 5 more bytes from Bumble
        val helloText: ByteString = ByteString.copyFromUtf8("Hello")
        sendDataFromBumbleToPhoneUtil(bluetoothSocket, channel, helloText)

        // Ensure that data len available would return only remaining bytes in the first packet
        // and doesn't consider the next packet "Hello" from the stream
        assertThat(inputStream.available()).isEqualTo((sampleData.size() - 1))

        // try to read 1 more than available
        val remFromSampleData = ByteArray((sampleData.size() - 1) + 1)
        val retBytes = inputStream.read(remFromSampleData)

        Log.d(
            TAG,
            "retBytes: " + retBytes + ":: read buffer: " + String(remFromSampleData, Charsets.UTF_8),
        )

        // Ensure that read() would only read remaining bytes from the first packet (cafe-place)
        // and don't consider the next packet sent as "Hello"
        assertThat((sampleData.size() - 1)).isEqualTo(retBytes)

        // resultant buffer supposed to be last 9 bytes of sampleData
        val expectedResultantString: ByteString = ByteString.copyFromUtf8("afe-place")
        assertThat(expectedResultantString)
            .isEqualTo(
                ByteString.copyFrom(
                    remFromSampleData.copyOfRange(0, expectedResultantString.size())
                )
            )

        // Should return the size of next packet = sizeof("Hello")
        assertThat(inputStream.available()).isEqualTo(helloText.size())

        val readRemaining = ByteArray(inputStream.available())
        val retBytes2 = inputStream.read(readRemaining)

        assertThat(retBytes2).isEqualTo(helloText.size())

        assertThat(helloText)
            .isEqualTo(ByteString.copyFrom(readRemaining.copyOfRange(0, helloText.size())))

        Log.d(TAG, "testBluetoothSocketAvailable: disconnect")
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)

        inputStream.close()
        bluetoothSocket?.close()
        assertThat(inputStream.available()).isEqualTo(0)
        l2capServer.close()
        Log.d(TAG, "testBluetoothSocketAvailable: done")
    }

    @Test
    @VirtualOnly
    fun testBluetoothSocketReadOoB() {
        Log.d(TAG, "testBluetoothSockeReadtOoB: Connect L2CAP")
        val (l2capServer, bluetoothSocket, channel) =
            l2capServerOnPhoneAndConnectionFromBumbleUtil(false)

        val inputStream = bluetoothSocket!!.inputStream
        val maxRxSize = bluetoothSocket!!.getMaxReceivePacketSize()
        val buffer = ByteArray(maxRxSize)
        buffer.fill(65)
        val bufferStr: ByteString = ByteString.copyFrom(buffer)

        sendDataFromBumbleToPhoneUtil(bluetoothSocket, channel, bufferStr, 60000)

        // Ensure that data len available is equal to what is written from client
        assertThat(inputStream.available()).isEqualTo(bufferStr.size())

        Log.d(TAG, "testBluetoothSocketReadOob: Receive data on Android")
        // read 1 byte
        val read = inputStream.read()

        // Ensure that data len available is is 1 less, as 1 byte read in previous call
        assertThat(inputStream.available()).isEqualTo(bufferStr.size() - 1)

        // now try to send buffer full of 2's
        val buffer2 = ByteArray(maxRxSize)
        buffer2.fill(66)
        val bufferStr2: ByteString = ByteString.copyFrom(buffer2)

        sendDataFromBumbleToPhoneUtil(bluetoothSocket, channel, bufferStr2, 60000)

        // Ensure It returns only remaining bytes from the 1st packet is available
        assertThat(inputStream.available()).isEqualTo(maxRxSize - 1)

        // try reading the max possible
        val readResult = ByteArray(maxRxSize)
        val retBytes = inputStream.read(readResult)

        Log.d(
            TAG,
            "retBytes: " + retBytes + ":: read buffer: " + String(readResult, Charsets.UTF_8),
        )

        assertThat(retBytes).isEqualTo(maxRxSize - 1)

        // this call should consider Input stream availability
        assertThat(inputStream.available()).isEqualTo(maxRxSize)

        // try reading the max possible
        val readResult2 = ByteArray(maxRxSize)
        // read should fill this back from Socket stream
        val retBytes2 = inputStream.read(readResult2)

        Log.d(
            TAG,
            "retBytes: " + retBytes2 + ":: read buffer: " + String(readResult2, Charsets.UTF_8),
        )

        // this read should be equal to the second write(bufferStr2) from Bumble side
        assertThat(retBytes2).isEqualTo(maxRxSize)
        assertThat(bufferStr2).isEqualTo(ByteString.copyFrom(readResult2))

        Log.d(TAG, "testBluetoothSocketReadOoB: disconnect")
        // disconnect from local
        disconnectSocketAndWaitForDisconnectUtil(bluetoothSocket, channel)

        inputStream.close()
        bluetoothSocket?.close()
        assertThat(inputStream.available()).isEqualTo(0)
        l2capServer.close()
        Log.d(TAG, "testBluetoothSocketReadOoB: done")
    }

    // Utility functions
    private fun clientSocketConnectUtil(isSecure: Boolean = false): Pair<BluetoothSocket, Channel> {
        val remoteDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        Log.d(TAG, "clientConnect: Connect L2CAP")
        val bluetoothSocket = createSocket(dckSpsm, remoteDevice, isSecure)
        runBlocking {
            val waitFlow = flow { emit(waitConnection(dckSpsm, remoteDevice)) }
            val connectJob = scope.launch {
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
            adapter.getRemoteLeDevice(
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
            val connectJob = scope.launch {
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
            val disconnectResponse = bumble.l2capBlocking().disconnect(disconnectRequest)
            assertThat(disconnectResponse.hasSuccess()).isTrue()
        }
        Log.d(TAG, "disconnectSocketAndWaitForDisconnectUtil: waitDisconnection")
        val waitDisconnectionRequest =
            WaitDisconnectionRequest.newBuilder().setChannel(channel).build()
        val disconnectionResponse =
            bumble.l2capBlocking().waitDisconnection(waitDisconnectionRequest)
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
            bumble.l2capBlocking().waitDisconnection(waitDisconnectionRequest)
        assertThat(disconnectionResponse.hasSuccess()).isTrue()
    }

    private fun l2capServerOnPhoneAndConnectionFromBumbleUtil(
        isSecure: Boolean = false
    ): SocketServerDetails {
        var bluetoothSocket: BluetoothSocket
        val channel: Channel
        val l2capServer = adapter.listenUsingInsecureL2capChannel()
        val socketFlow = flow { emit(l2capServer.accept()) }
        val connectResponse = createAndConnectL2capChannelWithBumble(l2capServer.psm)
        runBlocking {
            bluetoothSocket = socketFlow.first()
            assertThat(connectResponse.hasChannel()).isTrue()
        }

        return SocketServerDetails(l2capServer, bluetoothSocket, connectResponse.channel)
    }

    private fun l2capServerOnPhoneAndConnectionFromBumbleUsingSocketSettingsUtil(
        isAuthenticated: Boolean = false,
        isEncrypted: Boolean = false,
        psm: Int = -1,
    ): SocketServerDetails {
        var bluetoothSocket: BluetoothSocket
        val channel: Channel
        val l2capServer =
            createListeningChannelUsingSocketSettings(isEncrypted, isAuthenticated, psm)
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

        val receiveObserver = StreamObserverSpliterator<ReceiveRequest, ReceiveResponse>()
        bumble
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

    private fun sendDataFromBumbleToPhoneUtil(
        bluetoothSocket: BluetoothSocket,
        channel: Channel,
        data: ByteString,
        waitTime: Long = 500,
    ) {
        val sendRequest = SendRequest.newBuilder().setChannel(channel).setData(data).build()
        Log.d(TAG, "sendDataFromBumbleToPhoneUtil: Send data from Bumble to Android")
        bumble.l2capBlocking().send(sendRequest)

        // delay ensures data is arrived at the server side
        Thread.sleep(waitTime)
    }

    private fun sendDataFromBumbleToPhoneAndVerifyUtil(
        bluetoothSocket: BluetoothSocket,
        channel: Channel,
        waitTime: Long = 500,
    ) {
        val inputStream = bluetoothSocket!!.inputStream
        val sampleData: ByteString = ByteString.copyFromUtf8("cafe-baguette")
        val buffer = ByteArray(sampleData.size())

        val sendRequest = SendRequest.newBuilder().setChannel(channel).setData(sampleData).build()
        Log.d(TAG, "sendDataFromBumbleToPhoneAndVerifyUtil: Send data from Bumble to Android")
        bumble.l2capBlocking().send(sendRequest)

        // delay ensures data is arrived at the server side
        Thread.sleep(waitTime)
        Log.d(TAG, "sendDataFromBumbleToPhoneAndVerifyUtil: Receive data on Android")
        val read = inputStream.read(buffer)
        assertThat(ByteString.copyFrom(buffer).substring(0, read)).isEqualTo(sampleData)
        inputStream.close()
    }

    private fun createAndConnectL2capChannelWithBumble(psm: Int): ConnectResponse {
        Log.d(TAG, "createAndConnectL2capChannelWithBumble")
        val remoteDevice =
            adapter.getRemoteLeDevice(
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
        return bumble.l2capBlocking().connect(connectRequest)
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
        return bumble.l2capBlocking().waitConnection(waitConnectionRequest)
    }

    private fun createListeningChannelUsingSocketSettings(
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
        psm: Int = -1,
    ): BluetoothServerSocket {
        Log.i(TAG, "psm: " + psm)
        var socket: BluetoothServerSocket
        var socketSettings: BluetoothSocketSettings

        if (psm != -1) {
            socketSettings =
                BluetoothSocketSettings.Builder()
                    .setSocketType(BluetoothSocket.TYPE_LE)
                    .setEncryptionRequired(isEncrypted)
                    .setAuthenticationRequired(isAuthenticated)
                    .setL2capPsm(psm)
                    .build()
        } else {
            socketSettings =
                BluetoothSocketSettings.Builder()
                    .setSocketType(BluetoothSocket.TYPE_LE)
                    .setEncryptionRequired(isEncrypted)
                    .setAuthenticationRequired(isAuthenticated)
                    .build()
        }

        socket = adapter.listenUsingSocketSettings(socketSettings)
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
                    .setSocketType(BluetoothSocket.TYPE_LE)
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
            expectedType = BluetoothSocket.TYPE_LE
        } else {
            socket = remoteDevice.createInsecureL2capChannel(psm)
            expectedType = BluetoothSocket.TYPE_L2CAP
        }
        assertThat(socket.getConnectionType()).isEqualTo(expectedType)
        return socket
    }

    private fun setSystemProperty(key: String, value: String) {
        /* Setting system property is not possible from Bumble as It doesn't have
         * all permissions. This property needed only for 'Fixed PSM' testcases
         * (testSendOverInsecureSocketAsServerWithFixedPsm,
         *  testServerWithFixedPsmWhenAlreadyReserved & testServerWithInvalidFixedPsm).
         * Currently these testcases are marked as Ignore & It will need to be run manually
         * by setting the property from command prompt.
         * TODO: find an alternative to set this property so that fixed PSM tests
         * can be enabled as part of pre-submit.
         */
        // SystemProperties.set(key, value)
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
        private const val PROPERTY_FIXED_PSM_SLOTS = "bluetooth.ble.lecoc.fixed_psm_slots.config"

        private val GRPC_TIMEOUT = 10.seconds
        private val CHANNEL_READ_TIMEOUT = 30.seconds

        // CCC DK Specification R3 1.2.0 r14 section 19.2.1.2 Bluetooth Le Pairing
        private val CCC_DK_UUID = UUID.fromString("0000FFF5-0000-1000-8000-00805f9b34fb")
        // Vehicle SPSM
        private val SPSM_UUID = UUID.fromString("D3B5A130-9E23-4B3A-8BE4-6B1EE5F980A3")
    }
}
