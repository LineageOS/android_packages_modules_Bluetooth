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
import android.annotation.SuppressLint
import android.app.compat.CompatChanges
import android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
import android.bluetooth.BluetoothAdapter.EXTRA_STATE
import android.bluetooth.BluetoothAdapter.STATE_OFF
import android.bluetooth.BluetoothAdapter.nameForState
import android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothSocket.MAKE_SOCKET_READ_BEHAVIOR_CONSISTENT
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.Settings
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.inOrder
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import pandora.BumbleConfigProto
import pandora.HostProto
import pandora.RfcommProto
import pandora.RfcommProto.ServerId

@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
@ExperimentalCoroutinesApi
class RfcommTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1)
    val permissionRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.MODIFY_PHONE_STATE,
        )
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var serviceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var inOrder: InOrder
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var host: Host

    private var connectionCounter = 1

    @OptIn(ExperimentalStdlibApi::class)
    private val bdAddrFormat = HexFormat { bytes { byteSeparator = ":" } }
    @OptIn(ExperimentalStdlibApi::class)
    private val localAddress: ByteString =
        ByteString.copyFrom("DA:4C:10:DE:17:00".hexToByteArray(bdAddrFormat))

    private val BLE_SCAN_ALWAYS_AVAILABLE = "ble_scan_always_enabled"

    /**
     * Setup:
     * - Initialize host and bumbleDevice
     * - Override pairing config (allows insecure tests to run)
     * - Disable A2DP, HFP, and HID profiles
     * - Disconnect devices, if they are connected
     */
    @Before
    fun setUp() {
        Log.d(TAG, "start setUp")
        inOrder = inOrder(receiver)
        bumbleDevice = bumble.remoteDevice
        val filter =
            IntentFilter().apply {
                addAction(ACTION_PAIRING_REQUEST)
                addAction(ACTION_STATE_CHANGED)
            }
        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)
        host = Host(context)

        val bluetoothA2dp = connectToProfile(BluetoothProfile.A2DP) as BluetoothA2dp
        bluetoothA2dp.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN)

        val bluetoothHfp = connectToProfile(BluetoothProfile.HEADSET) as BluetoothHeadset
        bluetoothHfp.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN)

        val bluetoothHidHost = connectToProfile(BluetoothProfile.HID_HOST) as BluetoothHidHost
        bluetoothHidHost.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN)

        if (bumbleDevice.isConnected) {
            host.disconnectAndVerify(bumbleDevice)
        }
        Log.d(TAG, "setUp completed")
    }

    /**
     * TearDown:
     * - Remove Bond
     * - Shutdown host
     */
    @After
    fun tearDown() {
        Log.d(TAG, "start tearDown. Bluetooth state is ${nameForState(adapter.leState)}")
        if (Settings.Global.getInt(context.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 0) == 1) {
            // Recover BLE Scan always available setting
            Settings.Global.putInt(context.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 0)
        }
        if (adapter.bondedDevices.contains(bumbleDevice)) {
            host.removeBondAndVerify(bumbleDevice)
        }
        host.close()
        context.unregisterReceiver(receiver)
    }

    /**
     * Test Steps:
     * - Create an insecure socket
     * - Connect to the socket
     * - Verify that devices are connected.
     */
    @Test
    fun clientConnectToOpenServerSocketInsecure() {
        updateSecurityConfig()
        startServer { serverId -> createConnectAcceptSocket(isSecure = false, serverId) }
    }

    /**
     * Test Steps:
     * - Create an secure socket
     * - Connect to the socket
     * - Verify that devices are connected.
     */
    @Test
    fun clientConnectToOpenServerSocketSecure() {
        updateSecurityConfig()
        startServer { serverId -> createConnectAcceptSocket(isSecure = true, serverId) }
    }

    /**
     * Test Steps:
     * - Create an insecure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Write data to socket output stream
     * - Verify bumble received that data
     */
    @Test
    fun clientSendDataOverInsecureSocket() {
        updateSecurityConfig()
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val data: ByteArray = "Test data for clientSendDataOverInsecureSocket".toByteArray()
            val socketOs = insecureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                bumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /**
     * Test Steps:
     * - Create a secure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Write data to socket output stream
     * - Verify remote device received that data
     */
    @Test
    fun clientSendDataOverSecureSocket() {
        updateSecurityConfig()
        startServer { serverId ->
            val (secureSocket, connection) = createConnectAcceptSocket(isSecure = true, serverId)
            val data: ByteArray = "Test data for clientSendDataOverSecureSocket".toByteArray()
            val socketOs = secureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                bumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /**
     * Test Steps:
     * - Create an insecure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Send data from remote device
     * - Read and verify data from socket input stream
     */
    @Test
    fun clientReceiveDataOverInsecureSocket() {
        updateSecurityConfig()
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val buffer = ByteArray(64)
            val socketIs = insecureSocket.inputStream
            val data: ByteString =
                ByteString.copyFromUtf8("Test data for clientReceiveDataOverInsecureSocket")

            val txRequest =
                RfcommProto.TxRequest.newBuilder().setConnection(connection).setData(data).build()
            bumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble)).isEqualTo(data)
        }
    }

    /**
     * Test Steps:
     * - Create a secure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Send data from remote device
     * - Read and verify data from socket input stream
     */
    @Test
    fun clientReceiveDataOverSecureSocket() {
        updateSecurityConfig()
        startServer { serverId ->
            val (secureSocket, connection) = createConnectAcceptSocket(isSecure = true, serverId)
            val buffer = ByteArray(64)
            val socketIs = secureSocket.inputStream
            val data: ByteString =
                ByteString.copyFromUtf8("Test data for clientReceiveDataOverSecureSocket")

            val txRequest =
                RfcommProto.TxRequest.newBuilder().setConnection(connection).setData(data).build()
            bumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble)).isEqualTo(data)
        }
    }

    /**
     * Test Steps:
     * - Create insecure socket 1
     * - Create insecure socket 2
     * - Remote device initiates connection to socket 1
     * - Remote device initiates connection to socket 2
     * - Accept socket 1 and verify connection
     * - Accept socket 2 and verify connection
     */
    @Test
    fun connectTwoInsecureClientsSimultaneously() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(isSecure = false, TEST_UUID)
                val socket2 = createSocket(isSecure = false, SERIAL_PORT_UUID)

                acceptSocket(serverId1)
                assertThat(socket1.isConnected).isTrue()

                acceptSocket(serverId2)
                assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /**
     * Test Steps:
     * - Create insecure socket 1
     * - Remote device initiates connection to socket 1
     * - Accept socket 1 and verify connection
     * - Repeat for socket 2
     */
    @Test
    fun connectTwoInsecureClientsSequentially() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(isSecure = false, TEST_UUID)
                acceptSocket(serverId1)
                assertThat(socket1.isConnected).isTrue()

                val socket2 = createSocket(isSecure = false, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /**
     * Test Steps:
     * - Create secure socket 1
     * - Create secure socket 2
     * - Remote device initiates connection to socket 1
     * - Remote device initiates connection to socket 2
     * - Accept socket 1 and verify connection
     * - Accept socket 2 and verify connection
     */
    @Test
    fun connectTwoSecureClientsSimultaneously() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(isSecure = true, SERIAL_PORT_UUID)
                val socket1 = createSocket(isSecure = true, TEST_UUID)

                acceptSocket(serverId1)
                assertThat(socket1.isConnected).isTrue()

                acceptSocket(serverId2)
                assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /**
     * Test Steps:
     * - Create insecure socket 1
     * - Remote device initiates connection to socket 1
     * - Accept socket 1 and verify connection
     * - Repeat for socket 2
     */
    @Test
    fun connectTwoSecureClientsSequentially() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(isSecure = true, TEST_UUID)
                acceptSocket(serverId1)
                assertThat(socket1.isConnected).isTrue()

                val socket2 = createSocket(isSecure = true, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /**
     * Test Steps:
     * - Create insecure socket 1
     * - Remote device initiates connection to socket 1
     * - Accept socket 1 and verify connection
     * - Repeat for secure socket 2
     */
    @Test
    @Ignore("b/380091558")
    fun connectTwoMixedClientsInsecureThenSecure() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(isSecure = false, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                assertThat(socket2.isConnected).isTrue()
                Log.i(TAG, "Finished with socket number 2")
                val socket1 = createSocket(isSecure = true, TEST_UUID)
                acceptSocket(serverId1)
                assertThat(socket1.isConnected).isTrue()
            }
        }
    }

    /**
     * Test Steps:
     * - Create secure socket 2
     * - Remote device initiates connection to socket 2
     * - Accept socket 2 and verify connection
     * - Repeat for insecure socket 1
     */
    @Test
    fun connectTwoMixedClientsSecureThenInsecure() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(isSecure = true, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                assertThat(socket2.isConnected).isTrue()

                val socket1 = createSocket(isSecure = false, TEST_UUID)
                acceptSocket(serverId1)
                assertThat(socket1.isConnected).isTrue()
            }
        }
    }

    /**
     * Test Steps:
     * - Create listening socket and connect
     * - Disconnect RFCOMM from remote device
     */
    @RequiresFlagsEnabled("com.android.bluetooth.flags.trigger_sec_proc_on_inc_access_req")
    @Test
    fun serverSecureConnectThenRemoteDisconnect() {
        updateSecurityConfig()
        // step 1
        val (serverSock, connection) = connectRemoteToListeningSocket()
        val disconnectRequest =
            RfcommProto.DisconnectionRequest.newBuilder().setConnection(connection).build()
        // step 2
        bumble.rfcommBlocking().disconnect(disconnectRequest)
        assertThat(serverSock.channel).isEqualTo(-1) // ensure disconnected at RFCOMM Layer
    }

    /**
     * Test Steps:
     * - Create listening socket and connect
     * - Disconnect RFCOMM from local device
     */
    @RequiresFlagsEnabled("com.android.bluetooth.flags.trigger_sec_proc_on_inc_access_req")
    @Test
    fun serverSecureConnectThenLocalDisconnect() {
        updateSecurityConfig()
        // step 1
        val (serverSock, _) = connectRemoteToListeningSocket()
        // step 2
        serverSock.close()
        assertThat(serverSock.channel).isEqualTo(-1) // ensure disconnected at RFCOMM Layer
    }

    /**
     * Test Steps:
     * - Disable inquiry and page scan
     * - Create RFCOMM socket
     * - Attempt to connect to socket: expect not connected
     * - Wait 3 seconds
     * - Before page timeout of 5 seconds, close the socket
     * - Enable page scan
     * - Create and connect to an RFCOMM socket - verify proper connection
     */
    @Test
    fun clientConnectToOpenServerSocketAfterPageTimeout() {
        updateSecurityConfig()
        // 1. Disable inquiry and page scan
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                HostProto.SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(HostProto.DiscoverabilityMode.NOT_DISCOVERABLE)
                    .build()
            )
        bumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.NOT_CONNECTABLE)
                    .build()
            )
        // 2. Create RFCOMM socket
        val socket = bumbleDevice.createRfcommSocketToServiceRecord(UUID.fromString(TEST_UUID))

        // 3. Attempt to connect to socket: expect not connected
        val t = thread {
            try {
                socket.connect()
            } catch (e: IOException) {
                Log.i(TAG, "Expect socket connection failure $e")
            }
            Log.i(TAG, "Done connecting to socket")
        }
        // 4. Wait 3 seconds
        Thread.sleep(3000)

        assertThat(socket.isConnected).isFalse()
        // 5. Before page timeout of 5 seconds, close the socket
        socket.close()

        t.join()

        // 6. Enable page scan
        bumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.CONNECTABLE)
                    .build()
            )
        // 7. Create and connect to an RFCOMM socket - verify proper connection
        startServer { serverId -> createConnectAcceptSocket(isSecure = false, serverId) }
    }

    /**
     * Test Steps:
     * - Update security configuration.
     * - Disable discoverability and connectability on the remote host.
     * - Create an RFCOMM client socket.
     * - Attempt to connect to the socket in a separate thread.
     * - Ensure the connection attempt finishes within a timeout.
     * - Verify an IOException is caught during the connection attempt.
     */
    @Test
    fun clientConnectionFailedRaisesException() {
        updateSecurityConfig()
        // Disable inquiry and page scan
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                HostProto.SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(HostProto.DiscoverabilityMode.NOT_DISCOVERABLE)
                    .build()
            )
        bumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.NOT_CONNECTABLE)
                    .build()
            )

        startServer("ServerPort1", TEST_UUID) { serverId ->
            // Latch to signal connection attempt completion.
            val connectionAttemptFinished = CountDownLatch(1)
            // Flag to track if an IOException was thrown.
            var exceptionThrown = false

            // Start a new thread for the connection attempt.
            val t = thread {
                try {
                    val socket = createSocket(isSecure = false, TEST_UUID)
                    acceptSocket(serverId)
                } catch (e: IOException) {
                    Log.i(TAG, "Expect socket connection failure $e")
                    exceptionThrown = true
                } finally {
                    // Signal that the attempt is complete.
                    connectionAttemptFinished.countDown()
                }
                Log.i(TAG, "Done connecting to socket")
            }

            // Wait for the connection attempt to finish.
            connectionAttemptFinished.await(
                CONNECTION_ATTEMPT_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS,
            )

            // Check assert an rfcomm socket IOException was thrown.
            assertThat(exceptionThrown).isTrue()

            t.join()
        }
    }

    /**
     * Test Steps:
     * - Disable inquiry and page scan
     * - Initialize latches and flags for two concurrent connection attempts.
     * - Create two distinct RFCOMM client sockets.
     * - Attempt to connect each socket in separate threads.
     * - Verify an IOException is caught for both attempts.
     * - Wait for both concurrent attempts to complete.
     * - Assert both attempts threw exceptions.
     */
    @Test
    fun clientConcurrentConnectionFailedRaisesException() {
        updateSecurityConfig()
        // Disable inquiry and page scan
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                HostProto.SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(HostProto.DiscoverabilityMode.NOT_DISCOVERABLE)
                    .build()
            )
        bumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.NOT_CONNECTABLE)
                    .build()
            )

        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                // Latch for two concurrent attempts.
                val connectionAttemptFinished = CountDownLatch(2)
                // Flag for socket1's exception.
                var exceptionThrown1 = false
                // Flag for socket2's exception.
                var exceptionThrown2 = false

                val t1 = thread {
                    try {
                        val socket1 = createSocket(isSecure = false, TEST_UUID)
                        acceptSocket(serverId1)
                    } catch (e: IOException) {
                        Log.i(TAG, "Expect socket1 connection failure $e")
                        exceptionThrown1 = true
                    } finally {
                        // Signal that the attempt is complete.
                        connectionAttemptFinished.countDown()
                    }
                    Log.i(TAG, "Done connecting to socket1")
                }

                val t2 = thread {
                    try {
                        val socket2 = createSocket(isSecure = false, SERIAL_PORT_UUID)
                        acceptSocket(serverId2)
                    } catch (e: IOException) {
                        Log.i(TAG, "Expect socket2 connection failure $e")
                        exceptionThrown2 = true
                    } finally {
                        // Signal that the attempt is complete.
                        connectionAttemptFinished.countDown()
                    }
                    Log.i(TAG, "Done connecting to socket2")
                }

                // Wait for both attempts to finish.
                connectionAttemptFinished.await(
                    CONNECTION_ATTEMPT_TIMEOUT.toMillis() * 2,
                    TimeUnit.MILLISECONDS,
                )

                // Check assert both rfcomm socket IOExceptions were thrown.
                assertThat(exceptionThrown1).isTrue()
                assertThat(exceptionThrown2).isTrue()

                t1.join()
                t2.join()
            }
        }
    }

    /**
     * Test Steps:
     * - Create an insecure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Write data to socket output stream
     * - Verify bumble received that data
     */
    @Test
    fun clientSendDataOverInsecureSocketUsingSocketSettings() {
        updateSecurityConfig()
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocketUsingSettings(serverId)
            val data: ByteArray =
                "Test data for clientSendDataOverInsecureSocketUsingSocketSettings".toByteArray()
            val socketOs = insecureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                bumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /**
     * Test Steps:
     * - Create an encrypt only socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Write data to socket output stream
     * - Verify bumble received that data
     */
    @Test
    fun clientSendDataOverEncryptedOnlySocketUsingSocketSettings() {
        updateSecurityConfig(true, false)
        startServer { serverId ->
            val (encryptOnlySocket, connection) =
                createConnectAcceptSocketUsingSettings(serverId, TEST_UUID, true, false)

            val data: ByteArray =
                "Test data for clientSendDataOverEncryptedOnlySocketUsingSocketSettings"
                    .toByteArray()
            val socketOs = encryptOnlySocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                bumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /**
     * Test Steps:
     * - Create an secure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Write data to socket output stream
     * - Verify bumble received that data
     */
    @Test
    fun clientSendDataOverSecureSocketUsingSocketSettings() {
        updateSecurityConfig(true, true)
        startServer { serverId ->
            val (secureSocket, connection) =
                createConnectAcceptSocketUsingSettings(serverId, TEST_UUID, true, false)
            val data: ByteArray =
                "Test data for clientSendDataOverSecureSocketUsingSocketSettings".toByteArray()
            val socketOs = secureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                bumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /**
     * Test Steps:
     * - Create an Rfcomm insecure socket
     * - Verify that Rfcomm socket is connected
     * - Disable Bluetooth to BLE_ON mode
     * - Verify remote devices disconnected based on successful data transmission
     */
    @Test
    fun clientRfcommDeviceDisconnectedOnBleOnMode() {
        // Enable BLE_ON mode if disable Bluetooth
        Settings.Global.putInt(context.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 1)
        // Must wait for BLE_SCAN_ALWAYS_AVAILABLE to be enabled and then enable BLE_ON mode
        for (i in 1..10) {
            if (adapter.isBleScanAlwaysAvailable()) {
                // Enable BLE_ON mode
                adapter.enableBLE()
                break
            }
            Log.d(TAG, "Ble scan not yet available... Sleeping 50 ms $i/10")
            Thread.sleep(50)
        }

        updateSecurityConfig()

        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocketUsingSettings(serverId)

            // Verify that Rfcomm Socket is connected
            assertThat(insecureSocket.isConnected).isTrue()

            // Disable Bluetooth classic
            adapter.disable()
            verifyIntentReceived(hasAction(ACTION_STATE_CHANGED), hasExtra(EXTRA_STATE, STATE_OFF))

            // Note: There is no guarantee that BT will stay in BLE_ON state for the duration of the
            // test. The test is intended to verify ACL is already disconnected at this point,
            // even before reaching OFF state

            // 1. In Bluetooth disabled state, under BLE_ON mode, it's impossible to determine the
            // device's connection status.
            // 2. Determine whether the Rfcomm Socket or ACL link has been disconnected based on
            // successful data transmission.
            val data: ByteArray =
                "Test data for clientRfcommDeviceDisconnectedOnBleOnMode".toByteArray()
            val socketOs = insecureSocket.outputStream

            // Verify that Rfcomm Socket is disconnected
            assertThrows(IOException::class.java) { socketOs.write(data) }
        }
    }

    /**
     * Test Steps: Test Steps:
     * - Create an insecure socket
     * - Connect to the socket
     * - Verify that devices are connected
     * - Let Server thread wait on read()
     * - Disconnect the socket from remote side (Bumble)
     * - read() should return -1 on socket disconnection (reaching EOF)
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.make_socket_read_behavior_consistent")
    // @EnableFlags("com.android.bluetooth.flags.make_socket_read_behavior_consistent")
    fun clientReadDataAfterRfcommConnectionDisconnected_afterChange() {
        assumeTrue(CompatChanges.isChangeEnabled(MAKE_SOCKET_READ_BEHAVIOR_CONSISTENT))
        assumeTrue(Build.VERSION.SDK_INT >= 37)

        updateSecurityConfig()
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val inputStream = insecureSocket.inputStream
            val readThread = Thread {
                val ret = inputStream.read()
                Log.d(
                    TAG,
                    "clientReadDataAfterRfcommConnectionDisconnected: isConnected() : " +
                        insecureSocket!!.isConnected(),
                )
                assertThat(ret).isEqualTo(-1)
                assertThat(insecureSocket!!.isConnected()).isFalse()
            }
            readThread.start()
            Thread.sleep(1000 * 2)
            Log.d(TAG, "clientReadDataAfterRfcommConnectionDisconnected: disconnect after 2 secs")

            val disconnectRequest =
                RfcommProto.DisconnectionRequest.newBuilder().setConnection(connection).build()
            bumble.rfcommBlocking().disconnect(disconnectRequest)

            inputStream.close()
            insecureSocket?.close()
        }
    }

    // helper to update the security config for remote bumble device
    private fun updateSecurityConfig(
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ) {
        val pairingConfig =
            BumbleConfigProto.PairingConfig.newBuilder()
                .setBonding(isEncrypted)
                .setMitm(isAuthenticated)
                .setSc(isEncrypted)
                .setIdentityAddressType(HostProto.OwnAddressType.PUBLIC)
                .build()
        val overrideRequest =
            BumbleConfigProto.OverrideRequest.newBuilder().setPairingConfig(pairingConfig).build()
        bumble.bumbleConfigBlocking().override(overrideRequest)
    }

    private fun createConnectAcceptSocketUsingSettings(
        server: ServerId,
        uuid: String = TEST_UUID,
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ): Pair<BluetoothSocket, RfcommProto.RfcommConnection> {
        val socket = createClientSocketUsingSocketSettings(uuid, isEncrypted, isAuthenticated)

        val connection = acceptSocket(server)

        assertThat(socket.isConnected).isTrue()

        return Pair(socket, connection)
    }

    private fun createClientSocketUsingSocketSettings(
        uuid: String,
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ): BluetoothSocket {
        var socket =
            bumbleDevice.createUsingSocketSettings(
                BluetoothSocketSettings.Builder()
                    .setSocketType(BluetoothSocket.TYPE_RFCOMM)
                    .setEncryptionRequired(isEncrypted)
                    .setAuthenticationRequired(isAuthenticated)
                    .setRfcommUuid(UUID.fromString(uuid))
                    .build()
            )

        // We need to reply to the pairing request in the case where the devices aren't bonded yet
        if ((isEncrypted || isAuthenticated) && !adapter.bondedDevices.contains(bumbleDevice)) {
            thread {
                verifyIntentReceived(
                    hasAction(ACTION_PAIRING_REQUEST),
                    hasExtra(EXTRA_DEVICE, bumbleDevice),
                )
                bumbleDevice.setPairingConfirmation(true)
            }
        }
        socket.connect()
        return socket
    }

    private fun createSocket(isSecure: Boolean, uuid: String): BluetoothSocket {
        val socket =
            if (isSecure) {
                bumbleDevice.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
            } else {
                bumbleDevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid))
            }

        // We need to reply to the pairing request in the case where the devices aren't bonded yet
        if (isSecure && !adapter.bondedDevices.contains(bumbleDevice)) {
            thread {
                verifyIntentReceived(
                    hasAction(ACTION_PAIRING_REQUEST),
                    hasExtra(EXTRA_DEVICE, bumbleDevice),
                )
                bumbleDevice.setPairingConfirmation(true)
            }
        }
        socket.connect()
        return socket
    }

    private fun acceptSocket(server: ServerId): RfcommProto.RfcommConnection {
        val connectionResponse =
            bumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .acceptConnection(
                    RfcommProto.AcceptConnectionRequest.newBuilder().setServer(server).build()
                )
        assertThat(connectionResponse.connection.id).isEqualTo(connectionCounter)

        connectionCounter += 1
        return connectionResponse.connection
    }

    private fun createConnectAcceptSocket(
        isSecure: Boolean,
        server: ServerId,
        uuid: String = TEST_UUID,
    ): Pair<BluetoothSocket, RfcommProto.RfcommConnection> {
        val socket = createSocket(isSecure, uuid)
        val connection = acceptSocket(server)
        assertThat(socket.isConnected).isTrue()

        return Pair(socket, connection)
    }

    private fun startServer(
        name: String = TEST_SERVER_NAME,
        uuid: String = TEST_UUID,
        block: (ServerId) -> Unit,
    ) {
        val request =
            RfcommProto.StartServerRequest.newBuilder().setName(name).setUuid(uuid).build()
        assertThat(request).isNotNull()
        assertThat(request.uuid).isNotNull()
        assertThat(request.uuid).isNotEmpty()
        val response = bumble.rfcommBlocking().startServer(request)

        try {
            block(response.server)
        } finally {
            bumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .stopServer(
                    RfcommProto.StopServerRequest.newBuilder().setServer(response.server).build()
                )
        }
    }

    private fun connectRemoteToListeningSocket(
        name: String = TEST_SERVER_NAME,
        uuid: String = TEST_UUID,
    ): Pair<BluetoothServerSocket, RfcommProto.RfcommConnection> {
        var connection: RfcommProto.RfcommConnection? = null
        val connectRequest =
            RfcommProto.ConnectionRequest.newBuilder()
                .setAddress(localAddress)
                .setUuid(uuid)
                .build()
        val t = thread {
            val connectResponse = bumble.rfcommBlocking().connectToServer(connectRequest)
            connection = connectResponse.connection
        }
        val socket = adapter.listenUsingRfcommWithServiceRecord(name, UUID.fromString(uuid))

        // We need to reply to the pairing request in the case where the devices aren't bonded yet
        if (!adapter.bondedDevices.contains(bumbleDevice)) {
            thread {
                verifyIntentReceived(
                    hasAction(ACTION_PAIRING_REQUEST),
                    hasExtra(EXTRA_DEVICE, bumbleDevice),
                )
                bumbleDevice.setPairingConfirmation(true)
            }
        }
        socket.accept(ACCEPT_TIMEOUT.toMillis().toInt())
        t.join()
        assertThat(connection).isNotNull()

        return Pair(socket, connection!!)
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(GRPC_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    private fun connectToProfile(profile: Int): BluetoothProfile {
        adapter.getProfileProxy(context, serviceListener, profile)
        val proxyCaptor = argumentCaptor<BluetoothProfile>()
        verify(serviceListener, timeout(GRPC_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.firstValue
    }

    companion object {
        private const val TAG = "RfcommTest"
        private val GRPC_TIMEOUT = Duration.ofSeconds(10)
        private val CONNECT_TIMEOUT = Duration.ofSeconds(7)
        private val STATE_CHANGE_TIMEOUT = Duration.ofSeconds(5)
        private val CONNECTION_ATTEMPT_TIMEOUT = Duration.ofSeconds(10)
        private val ACCEPT_TIMEOUT = Duration.ofSeconds(7)
        private const val TEST_UUID = "2ac5d8f1-f58d-48ac-a16b-cdeba0892d65"
        private const val SERIAL_PORT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val TEST_SERVER_NAME = "RFCOMM Server"
    }
}
