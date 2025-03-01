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
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.Settings
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.flags.Flags
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth
import com.google.protobuf.ByteString
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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
    private val mContext = ApplicationProvider.getApplicationContext<Context>()
    private val mManager = mContext.getSystemService(BluetoothManager::class.java)
    private val mAdapter = mManager!!.adapter

    @Rule(order = 0)
    @JvmField
    val mCheckFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    // Gives shell permissions during the test.
    @Rule(order = 1)
    @JvmField
    val mPermissionsRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.MODIFY_PHONE_STATE,
        )

    // Set up a Bumble Pandora device for the duration of the test.
    @Rule(order = 2) @JvmField val mBumble = PandoraDevice()

    @Rule(order = 3) @JvmField val enableBluetoothRule = EnableBluetoothRule(false, true)

    private lateinit var mRemoteDevice: BluetoothDevice
    private lateinit var mHost: Host
    private var mConnectionCounter = 1
    private var mProfileServiceListener = mock<BluetoothProfile.ServiceListener>()

    private val mFlow: Flow<Intent>
    private val mScope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(2))

    @OptIn(ExperimentalStdlibApi::class)
    private val bdAddrFormat = HexFormat { bytes { byteSeparator = ":" } }
    @OptIn(ExperimentalStdlibApi::class)
    private val mLocalAddress: ByteString =
        ByteString.copyFrom("DA:4C:10:DE:17:00".hexToByteArray(bdAddrFormat))

    private val BLE_SCAN_ALWAYS_AVAILABLE = "ble_scan_always_enabled"

    init {
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothAdapter.ACTION_BLE_STATE_CHANGED)
        }
        mFlow = intentFlow(mContext, intentFilter, mScope).shareIn(mScope, SharingStarted.Eagerly)
    }

    /*
        Setup:
        1. Initialize host and mRemoteDevice
        2. Override pairing config to enable insecure tests
        3. Disable A2DP, HFP, and HID profiles
        4. Disconnect devices, if they are connected
    */
    @Before
    fun setUp() {
        mRemoteDevice = mBumble.remoteDevice
        mHost = Host(mContext)

        val bluetoothA2dp = getProfileProxy(mContext, BluetoothProfile.A2DP) as BluetoothA2dp
        bluetoothA2dp.setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_FORBIDDEN)
        val bluetoothHfp = getProfileProxy(mContext, BluetoothProfile.HEADSET) as BluetoothHeadset
        bluetoothHfp.setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_FORBIDDEN)
        val bluetoothHidHost =
            getProfileProxy(mContext, BluetoothProfile.HID_HOST) as BluetoothHidHost
        bluetoothHidHost.setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_FORBIDDEN)
        if (mRemoteDevice.isConnected) {
            mHost.disconnectAndVerify(mRemoteDevice)
        }
    }

    /*
        TearDown:
        1. remove bond
        2. shutdown host
    */
    @After
    fun tearDown() {
        if (Settings.Global.getInt(mContext.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 0) == 1) {
            // Recover BLE Scan always available setting
            Settings.Global.putInt(mContext.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 0)
        }
        if (mAdapter.bondedDevices.contains(mRemoteDevice)) {
            mHost.removeBondAndVerify(mRemoteDevice)
        }
        mHost.close()
    }

    /*
       Test Steps:
       1. Create an insecure socket
       2. Connect to the socket
       3. Verify that devices are connected.
    */
    @Test
    fun clientConnectToOpenServerSocketInsecure() {
        updateSecurityConfig()
        startServer { serverId -> createConnectAcceptSocket(isSecure = false, serverId) }
    }

    /*
       Test Steps:
       1. Create an secure socket
       2. Connect to the socket
       3. Verify that devices are connected.
    */
    @Test
    fun clientConnectToOpenServerSocketSecure() {
        updateSecurityConfig()
        startServer { serverId -> createConnectAcceptSocket(isSecure = true, serverId) }
    }

    /*
        Test Steps:
        1. Create an insecure socket
        2. Connect to the socket
        3. Verify that devices are connected
        4. Write data to socket output stream
        5. Verify bumble received that data
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
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /*
        Test Steps:
        1. Create a secure socket
        2. Connect to the socket
        3. Verify that devices are connected
        4. Write data to socket output stream
        5. Verify remote device received that data
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
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /*
        Test Steps:
        1. Create an insecure socket
        2. Connect to the socket
        3. Verify that devices are connected
        4. Send data from remote device
        5. Read and verify data from socket input stream
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
            mBumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            Truth.assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble))
                .isEqualTo(data)
        }
    }

    /*
        Test Steps:
        1. Create a secure socket
        2. Connect to the socket
        3. Verify that devices are connected
        4. Send data from remote device
        5. Read and verify data from socket input stream
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
            mBumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            Truth.assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble))
                .isEqualTo(data)
        }
    }

    /*
        Test Steps:
        1. Create insecure socket 1
        2. Create insecure socket 2
        3. Remote device initiates connection to socket 1
        4. Remote device initiates connection to socket 2
        5. Accept socket 1 and verify connection
        6. Accept socket 2 and verify connection
    */
    @Test
    fun connectTwoInsecureClientsSimultaneously() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(mRemoteDevice, isSecure = false, TEST_UUID)
                val socket2 = createSocket(mRemoteDevice, isSecure = false, SERIAL_PORT_UUID)

                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /*
        Test Steps:
        1. Create insecure socket 1
        2. Remote device initiates connection to socket 1
        3. Accept socket 1 and verify connection
        4. Repeat for socket 2
    */
    @Test
    fun connectTwoInsecureClientsSequentially() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(mRemoteDevice, isSecure = false, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                val socket2 = createSocket(mRemoteDevice, isSecure = false, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /*
        Test Steps:
        1. Create secure socket 1
        2. Create secure socket 2
        3. Remote device initiates connection to socket 1
        4. Remote device initiates connection to socket 2
        5. Accept socket 1 and verify connection
        6. Accept socket 2 and verify connection
    */
    @Test
    fun connectTwoSecureClientsSimultaneously() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(mRemoteDevice, isSecure = true, SERIAL_PORT_UUID)
                val socket1 = createSocket(mRemoteDevice, isSecure = true, TEST_UUID)

                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /*
        Test Steps:
        1. Create insecure socket 1
        2. Remote device initiates connection to socket 1
        3. Accept socket 1 and verify connection
        4. Repeat for socket 2
    */
    @Test
    fun connectTwoSecureClientsSequentially() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket1 = createSocket(mRemoteDevice, isSecure = true, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()

                val socket2 = createSocket(mRemoteDevice, isSecure = true, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
            }
        }
    }

    /*
        Test Steps:
        1. Create insecure socket 1
        2. Remote device initiates connection to socket 1
        3. Accept socket 1 and verify connection
        4. Repeat for secure socket 2
    */
    @Test
    @Ignore("b/380091558")
    fun connectTwoMixedClientsInsecureThenSecure() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(mRemoteDevice, isSecure = false, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()
                Log.i(TAG, "Finished with socket number 2")
                val socket1 = createSocket(mRemoteDevice, isSecure = true, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()
            }
        }
    }

    /*
        Test Steps:
        1. Create secure socket 2
        2. Remote device initiates connection to socket 2
        3. Accept socket 2 and verify connection
        4. Repeat for insecure socket 1
    */
    @Test
    fun connectTwoMixedClientsSecureThenInsecure() {
        updateSecurityConfig()
        startServer("ServerPort1", TEST_UUID) { serverId1 ->
            startServer("ServerPort2", SERIAL_PORT_UUID) { serverId2 ->
                val socket2 = createSocket(mRemoteDevice, isSecure = true, SERIAL_PORT_UUID)
                acceptSocket(serverId2)
                Truth.assertThat(socket2.isConnected).isTrue()

                val socket1 = createSocket(mRemoteDevice, isSecure = false, TEST_UUID)
                acceptSocket(serverId1)
                Truth.assertThat(socket1.isConnected).isTrue()
            }
        }
    }

    /*
      Test Steps:
      1. Create listening socket and connect
      2. Disconnect RFCOMM from remote device
    */
    @RequiresFlagsEnabled(Flags.FLAG_TRIGGER_SEC_PROC_ON_INC_ACCESS_REQ)
    @Test
    fun serverSecureConnectThenRemoteDisconnect() {
        updateSecurityConfig()
        // step 1
        val (serverSock, connection) = connectRemoteToListeningSocket()
        val disconnectRequest =
            RfcommProto.DisconnectionRequest.newBuilder().setConnection(connection).build()
        // step 2
        mBumble.rfcommBlocking().disconnect(disconnectRequest)
        Truth.assertThat(serverSock.channel).isEqualTo(-1) // ensure disconnected at RFCOMM Layer
    }

    /*
      Test Steps:
      1. Create listening socket and connect
      2. Disconnect RFCOMM from local device
    */
    @RequiresFlagsEnabled(Flags.FLAG_TRIGGER_SEC_PROC_ON_INC_ACCESS_REQ)
    @Test
    fun serverSecureConnectThenLocalDisconnect() {
        updateSecurityConfig()
        // step 1
        val (serverSock, _) = connectRemoteToListeningSocket()
        // step 2
        serverSock.close()
        Truth.assertThat(serverSock.channel).isEqualTo(-1) // ensure disconnected at RFCOMM Layer
    }

    /*
       Test Steps:
       1. Disable inquiry and page scan
       2. Create RFCOMM socket
       3. Attempt to connect to socket: expect not connected
       4. Wait 3 seconds
       5. Before page timeout of 5 seconds, close the socket
       6. Enable page scan
       7. Create and connect to an RFCOMM socket - verify proper connection
    */
    @RequiresFlagsEnabled(Flags.FLAG_RFCOMM_CANCEL_ONGOING_SDP_ON_CLOSE)
    @Test
    fun clientConnectToOpenServerSocketAfterPageTimeout() {
        updateSecurityConfig()
        // 1. Disable inquiry and page scan
        mBumble
            .hostBlocking()
            .setDiscoverabilityMode(
                HostProto.SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(HostProto.DiscoverabilityMode.NOT_DISCOVERABLE)
                    .build()
            )
        mBumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.NOT_CONNECTABLE)
                    .build()
            )
        // 2. Create RFCOMM socket
        val socket = mRemoteDevice.createRfcommSocketToServiceRecord(UUID.fromString(TEST_UUID))

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

        Truth.assertThat(socket.isConnected).isFalse()
        // 5. Before page timeout of 5 seconds, close the socket
        socket.close()

        t.join()

        // 6. Enable page scan
        mBumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.CONNECTABLE)
                    .build()
            )
        // 7. Create and connect to an RFCOMM socket - verify proper connection
        startServer { serverId -> createConnectAcceptSocket(isSecure = false, serverId) }
    }

    /*
      Test Steps:
        1. Create an insecure socket
        2. Connect to the socket
        3. Verify that devices are connected
        4. Write data to socket output stream
        5. Verify bumble received that data
    */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SOCKET_SETTINGS_API)
    fun clientSendDataOverInsecureSocketUsingSocketSettings() {
        updateSecurityConfig()
        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocketUsingSettings(serverId)
            val data: ByteArray =
                "Test data for clientSendDataOverInsecureSocketUsingSocketSettings".toByteArray()
            val socketOs = insecureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /*
      Test Steps:
        1. Create an encrypt only socket
        2. Connect to the socket
        3. Verify that devices are connected
        4. Write data to socket output stream
        5. Verify bumble received that data
    */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SOCKET_SETTINGS_API)
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
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /*
     Test Steps:
       1. Create an secure socket
       2. Connect to the socket
       3. Verify that devices are connected
       4. Write data to socket output stream
       5. Verify bumble received that data
    */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SOCKET_SETTINGS_API)
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
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    /*
     Test Steps:
       1. Create an Rfcomm insecure socket
       2. Verify that Rfcomm socket is connected
       3. Disable Bluetooth to BLE_ON mode
       4. Verify remote devices disconnected based on successful data transmission
    */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DISCONNECT_ACLS_BY_BREDR_DISABLED)
    fun clientRfcommDeviceDisconnectedOnBleOnMode() {
        // Enable BLE_ON mode if disable Bluetooth
        Settings.Global.putInt(mContext.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE, 1)
        // Must wait for BLE_SCAN_ALWAYS_AVAILABLE to be enabled and then enable BLE_ON mode
        for (i in 1..10) {
            if (mAdapter.isBleScanAlwaysAvailable()) {
                // Enable BLE_ON mode
                mAdapter.enableBLE()
                break
            }
            Log.d(TAG, "Ble scan not yet available... Sleeping 50 ms $i/10")
            Thread.sleep(50)
        }

        updateSecurityConfig()

        startServer { serverId ->
            val (insecureSocket, connection) = createConnectAcceptSocketUsingSettings(serverId)

            // Verify that Rfcomm Socket is connected
            Truth.assertThat(insecureSocket.isConnected).isTrue()

            // disable Bluetooth to BLE_ON mode
            mAdapter.disable()
            waittingBluetoothLeStates(BluetoothAdapter.STATE_BLE_ON)

            // 1. In Bluetooth disabled state, under BLE_ON mode, it's impossible to determine the device's connection status.
            // 2. Determine whether the Rfcomm Socket or ACL link has been disconnected based on successful data transmission.
            val data: ByteArray =
                "Test data for clientRfcommDeviceDisconnectedOnBleOnMode".toByteArray()
            val socketOs = insecureSocket.outputStream

            // Verify that Rfcomm Socket is disconnected
            assertThrows(IOException::class.java) { socketOs.write(data) }

            // Validate that the test run while Bluetooth stayed in BLE_ON all along
            Truth.assertThat(mAdapter.leState).isEqualTo(BluetoothAdapter.STATE_BLE_ON)
        }
    }

    // helper to wait for Bluetooth BLE state change
    private fun waittingBluetoothLeStates(state: Int) {
        runBlocking(mScope.coroutineContext) {
            withTimeout(STATE_CHANGE_TIMEOUT.toMillis()) {
                // wait for Bluetooth states
                launch {
                    Log.i(TAG, "Waiting for waittingBluetoothLeStates: " + state)
                    mFlow
                        .filter { it.action == BluetoothAdapter.ACTION_BLE_STATE_CHANGED }
                        .filter { it.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == state }
                        .first()
                }
            }
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
        mBumble.bumbleConfigBlocking().override(overrideRequest)
    }

    private fun createConnectAcceptSocketUsingSettings(
        server: ServerId,
        uuid: String = TEST_UUID,
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ): Pair<BluetoothSocket, RfcommProto.RfcommConnection> {
        val socket =
            createClientSocketUsingSocketSettings(uuid, mRemoteDevice, isEncrypted, isAuthenticated)

        val connection = acceptSocket(server)

        Truth.assertThat(socket.isConnected).isTrue()

        return Pair(socket, connection)
    }

    private fun createConnectAcceptSocket(
        isSecure: Boolean,
        server: ServerId,
        uuid: String = TEST_UUID,
    ): Pair<BluetoothSocket, RfcommProto.RfcommConnection> {
        val socket = createSocket(mRemoteDevice, isSecure, uuid)
        val connection = acceptSocket(server)
        Truth.assertThat(socket.isConnected).isTrue()

        return Pair(socket, connection)
    }

    private fun createClientSocketUsingSocketSettings(
        uuid: String,
        remoteDevice: BluetoothDevice,
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false,
    ): BluetoothSocket {
        var socket: BluetoothSocket

        socket =
            remoteDevice.createUsingSocketSettings(
                BluetoothSocketSettings.Builder()
                    .setSocketType(BluetoothSocket.TYPE_RFCOMM)
                    .setEncryptionRequired(isEncrypted)
                    .setAuthenticationRequired(isAuthenticated)
                    .setRfcommUuid(UUID.fromString(uuid))
                    .build()
            )

        runBlocking(mScope.coroutineContext) {
            withTimeout(CONNECT_TIMEOUT.toMillis()) {
                // We need to reply to the pairing request in the case where the devices aren't
                // bonded yet
                if (
                    (isEncrypted || isAuthenticated) &&
                        !mAdapter.bondedDevices.contains(remoteDevice)
                ) {
                    launch {
                        Log.i(TAG, "Waiting for ACTION_PAIRING_REQUEST")
                        mFlow
                            .filter { it.action == BluetoothDevice.ACTION_PAIRING_REQUEST }
                            .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                            .first()
                        remoteDevice.setPairingConfirmation(true)
                    }
                }
                socket.connect()
            }
        }
        return socket
    }

    private fun createSocket(
        device: BluetoothDevice,
        isSecure: Boolean,
        uuid: String,
    ): BluetoothSocket {
        val socket =
            if (isSecure) {
                device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
            } else {
                device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid))
            }

        runBlocking(mScope.coroutineContext) {
            withTimeout(CONNECT_TIMEOUT.toMillis()) {
                // We need to reply to the pairing request in the case where the devices aren't
                // bonded yet
                if (isSecure && !mAdapter.bondedDevices.contains(device)) {
                    launch {
                        Log.i(TAG, "Waiting for ACTION_PAIRING_REQUEST")
                        mFlow
                            .filter { it.action == BluetoothDevice.ACTION_PAIRING_REQUEST }
                            .filter { it.getBluetoothDeviceExtra() == device }
                            .first()
                        device.setPairingConfirmation(true)
                    }
                }
                socket.connect()
            }
        }
        return socket
    }

    private fun acceptSocket(server: ServerId): RfcommProto.RfcommConnection {
        val connectionResponse =
            mBumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .acceptConnection(
                    RfcommProto.AcceptConnectionRequest.newBuilder().setServer(server).build()
                )
        Truth.assertThat(connectionResponse.connection.id).isEqualTo(mConnectionCounter)

        mConnectionCounter += 1
        return connectionResponse.connection
    }

    private fun startServer(
        name: String = TEST_SERVER_NAME,
        uuid: String = TEST_UUID,
        block: (ServerId) -> Unit,
    ) {
        val request =
            RfcommProto.StartServerRequest.newBuilder().setName(name).setUuid(uuid).build()
        Truth.assertThat(request).isNotNull()
        Truth.assertThat(request.uuid).isNotNull()
        Truth.assertThat(request.uuid).isNotEmpty()
        val response = mBumble.rfcommBlocking().startServer(request)

        try {
            block(response.server)
        } finally {
            mBumble
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
                .setAddress(mLocalAddress)
                .setUuid(uuid)
                .build()
        val t = thread {
            val connectResponse = mBumble.rfcommBlocking().connectToServer(connectRequest)
            connection = connectResponse.connection
        }
        val socket = mAdapter.listenUsingRfcommWithServiceRecord(name, UUID.fromString(uuid))

        try {
            socket.accept(3000) // 3 second timeout
        } catch (e: IOException) {
            Log.e(TAG, "Unexpected IOException: $e")
        }
        t.join()
        Truth.assertThat(connection).isNotNull()

        return Pair(socket, connection!!)
    }

    private fun getProfileProxy(context: Context, profile: Int): BluetoothProfile {
        mAdapter.getProfileProxy(context, mProfileServiceListener, profile)
        val proxyCaptor = argumentCaptor<BluetoothProfile>()
        verify(mProfileServiceListener, timeout(GRPC_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.lastValue
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
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)

            awaitClose { context.unregisterReceiver(broadcastReceiver) }
        }

    companion object {
        private val TAG = RfcommTest::class.java.getSimpleName()
        private val GRPC_TIMEOUT = Duration.ofSeconds(10)
        private val CONNECT_TIMEOUT = Duration.ofSeconds(7)
        private val STATE_CHANGE_TIMEOUT = Duration.ofSeconds(5)
        private const val TEST_UUID = "2ac5d8f1-f58d-48ac-a16b-cdeba0892d65"
        private const val SERIAL_PORT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val TEST_SERVER_NAME = "RFCOMM Server"
    }
}
