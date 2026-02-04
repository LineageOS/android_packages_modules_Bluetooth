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

package android.bluetooth.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.PandoraDevice
import android.bluetooth.StreamObserverSpliterator
import android.bluetooth.Utils
import android.bluetooth.Utils.BUMBLE_DEVICE_NAME
import android.bluetooth.Utils.BUMBLE_DEVICE_NAME_2
import android.bluetooth.adapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.leAdvertiser
import android.bluetooth.leScanner
import android.bluetooth.pairing.utils.IntentReceiver
import android.bluetooth.pairing.utils.TestUtil
import android.bluetooth.test_utils.BlockingBluetoothAdapter
import android.bluetooth.test_utils.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.ByteString.copyFrom
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import pandora.BumbleConfigProto
import pandora.GattProto
import pandora.HostProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.AdvertiseResponse
import pandora.HostProto.DataTypes
import pandora.HostProto.DiscoverabilityMode
import pandora.HostProto.OwnAddressType
import pandora.HostProto.SetDiscoverabilityModeRequest
import pandora.SecurityProto.PairingEvent
import pandora.SecurityProto.PairingEventAnswer

private const val TAG = "PairingWithDiscoveryTest"

/** Test cases for [PairingWithDiscoveryTest]. */
@RunWith(AndroidJUnit4::class)
class PairingWithDiscoveryTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val secondBumble = PandoraDevice.createSecondPandoraDevice()
    @get:Rule(order = 4) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actionRegistrationCounts = HashMap<String, Int>()

    private var bumbleDevice: BluetoothDevice? = null
    private lateinit var remoteLeDevice: BluetoothDevice
    private var secondBumbleDevice: BluetoothDevice? = null
    private lateinit var inOrder: InOrder
    private lateinit var deviceFound: CompletableFuture<BluetoothDevice>
    private lateinit var secondDeviceFound: CompletableFuture<BluetoothDevice>
    private lateinit var cfName: String

    @SuppressLint("MissingPermission")
    private val intentHandler =
        Answer<Unit?> { inv ->
            Log.i(TAG, "onReceive(): intent=${inv.arguments.contentToString()}")
            val intent = inv.getArgument<Intent>(1)
            when (val action = intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device =
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    val deviceName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    Log.i(TAG, "Discovered device: $device with name: $deviceName")
                    if (
                        deviceName != null &&
                            BUMBLE_DEVICE_NAME == deviceName &&
                            ::deviceFound.isInitialized
                    ) {
                        deviceFound.complete(device)
                    } else if (
                        deviceName != null &&
                            BUMBLE_DEVICE_NAME_2 == deviceName &&
                            ::secondDeviceFound.isInitialized
                    ) {
                        secondDeviceFound.complete(device)
                    }
                }
                else -> Log.i(TAG, "onReceive(): unknown intent action $action")
            }
        }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        doAnswer(intentHandler).whenever(receiver).onReceive(any(), any())

        inOrder = inOrder(receiver)
        cfName = adapter.name

        bumbleDevice = bumble.remoteDevice
        remoteLeDevice =
            adapter.getRemoteLeDevice(
                Utils.BUMBLE_RANDOM_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM,
            )
        secondBumbleDevice = secondBumble.remoteDevice

        for (device in adapter.bondedDevices) {
            removeBond(device)
        }
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        for (device in adapter.bondedDevices) {
            removeBond(device)
        }
        bumbleDevice = null
        if (getTotalActionRegistrationCounts() > 0) {
            context.unregisterReceiver(receiver)
            actionRegistrationCounts.clear()
        }
    }

    /**
     * Test the address type reported for a discovered remote Bluetooth device.
     *
     * Prerequisites:
     * 1. Bluetooth is enabled on both Android and Bumble.
     * 2. Bumble is not bonded with Android.
     *
     * Steps:
     * 1. Bumble starts advertising with its own address type set to OwnAddressType.PUBLIC.
     * 2. Android starts scanning for Bluetooth devices.
     * 3. Android receives a BluetoothDevice.ACTION_FOUND intent for Bumble.
     * 4. The address type of the discovered BluetoothDevice is verified to be
     *    BluetoothDevice.ADDRESS_TYPE_PUBLIC.
     * 5. Android cancels the discovery process.
     *
     * Expectation: The BluetoothDevice object received in the BluetoothDevice.ACTION_FOUND intent
     * reflects the address type with which Bumble was advertising.
     */
    @Test
    @Throws(Exception::class)
    fun testAddressType_AtDeviceDiscovery_typePublic() {
        registerIntentActions(BluetoothDevice.ACTION_FOUND)
        deviceFound = CompletableFuture()

        // Start advertising from bumble side with address type PUBLIC
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .apply {
                        setLegacy(true)
                        setConnectable(true)
                        setOwnAddressType(OwnAddressType.PUBLIC)
                    }
                    .build()
            )
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )
        // Start device discovery from android
        assertThat(adapter.startDiscovery()).isTrue()
        // Verify device to be discovered
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_FOUND),
            hasExtra(BluetoothDevice.EXTRA_NAME, BUMBLE_DEVICE_NAME),
        )

        val device =
            deviceFound.completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS).join()
        // Verify address
        assertThat(device.address).isEqualTo(bumbleDevice?.address)
        // Verify address type
        assertThat(device.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC)
        // Cancel discovery
        assertThat(adapter.cancelDiscovery()).isTrue()

        unregisterIntentActions(BluetoothDevice.ACTION_FOUND)
    }

    /**
     * Test the address type reported for a discovered remote Bluetooth device.
     *
     * Prerequisites:
     * 1. Bluetooth is enabled on both Android and Bumble.
     * 2. Bumble is not bonded with Android.
     *
     * Steps:
     * 1. Bumble starts advertising again with its own address type set to OwnAddressType.RANDOM.
     * 2. Android starts scanning for Bluetooth devices.
     * 3. Android receives a BluetoothDevice.ACTION_FOUND intent for Bumble.
     * 4. The address type of the newly discovered BluetoothDevice is verified to be
     *    BluetoothDevice.ADDRESS_TYPE_RANDOM.
     * 5. Android cancels the discovery process.
     *
     * Expectation: The BluetoothDevice object received in the BluetoothDevice.ACTION_FOUND intent
     * reflects the address type with which Bumble was advertising.
     */
    @Test
    @Throws(Exception::class)
    fun testAddressType_AtDeviceDiscovery_typeRandom() {
        val future = CompletableFuture<ScanResult>()
        // LE Scan setting
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        // LE Scan filter
        val scanFilter = ScanFilter.Builder().build()
        // LE Scan result callback
        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    Log.d(TAG, "onScanResult: result=$result")
                    assertThat(result).isNotNull()
                    assertThat(result.device).isNotNull()
                    if (Utils.BUMBLE_RANDOM_ADDRESS == result.device.address) {
                        future.complete(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.d(TAG, "onScanFailed: errorCode=$errorCode")
                    future.complete(null)
                }
            }
        // Start advertising from bumble side with address type RANDOM
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .apply {
                        setLegacy(true)
                        setConnectable(true)
                        setOwnAddressType(OwnAddressType.RANDOM)
                    }
                    .build()
            )
        // Make Bumble discoverable
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )
        // Start LE scanning
        leScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        // Wait for the LE scan completion
        val result =
            future.completeOnTimeout(null, DISCOVERY_TIMEOUT.toLong(), TimeUnit.MILLISECONDS).join()
        // Stop LE Scan
        leScanner.stopScan(scanCallback)
        // Verify that the result list is not empty
        assertThat(result).isNotNull()
        // Get the first device from the list
        val leDevice = result.device
        // Verify address
        assertThat(leDevice.address).isEqualTo(Utils.BUMBLE_RANDOM_ADDRESS)
        // Verify address type
        assertThat(leDevice.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM)
    }

    /**
     * Test the address type reported upon receiving a connection from a remote device.
     *
     * Prerequisites:
     * 1. Bumble is discoverable.
     * 2. Android Bluetooth is enabled.
     *
     * Steps:
     * 1. Android registers an intent listener for ACL connection events.
     * 2. Bumble initiates a connection to the Android device.
     * 3. Android receives the connection.
     *
     * Expectation: The address type of the connected device is BluetoothDevice.ADDRESS_TYPE_PUBLIC.
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.retain_address_type")
    @Throws(Exception::class)
    fun testAddressType_AtConnectionFromRemote_typePublic() {
        registerIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
        )
        // Connect to android from bumble
        val conn =
            bumble
                .hostBlocking()
                .connect(
                    HostProto.ConnectRequest.newBuilder()
                        .setAddress(copyFrom(adapter.address.toAddressBytes()))
                        .build()
                )
        assertThat(conn.hasConnection()).isTrue()
        // Verify ACL connection
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        assertThat(bumbleDevice?.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC)

        // Disconnect from bumble
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder().setConnection(conn.connection).build()
            )

        // Verify ACL disconnection
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )

        unregisterIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
        )
    }

    /**
     * Test the address type reported upon receiving a connection from a remote device with a random
     * address.
     *
     * Prerequisites:
     * 1. Android Bluetooth is enabled.
     *
     * Steps:
     * 1. Android registers an intent listener for ACL connection events.
     * 2. Bumble's address type is set to random.
     * 3. Bumble initiates a connection to the Android device.
     * 4. Android receives the connection.
     *
     * Expectation: The address type of the connected device is BluetoothDevice.ADDRESS_TYPE_RANDOM.
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.retain_address_type")
    @Throws(Exception::class)
    fun testAddressType_AtConnectionFromRemote_typeRandom() {
        registerIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
        )
        testStep_Advertise(OwnAddressType.RANDOM)
        // Scan for LE advertisement from DUT
        var deviceAddr = ByteString.EMPTY
        var deviceName = ""
        val scanningResponseIterator =
            bumble.hostBlocking().scan(HostProto.ScanRequest.newBuilder().build())
        // Wait till the DUT is discovered by Bumble
        while (true) {
            if (scanningResponseIterator.hasNext()) {
                val scanningResponse = scanningResponseIterator.next()
                deviceAddr = scanningResponse.random
                deviceName = scanningResponse.data.completeLocalName
                if (deviceName.contains(cfName)) {
                    break
                }
            }
        }
        // Check if DUT address is not empty
        assertThat(deviceAddr.isEmpty).isFalse()
        // Create LE connection from REF side
        val leConn =
            bumble
                .hostBlocking()
                .connectLE(
                    HostProto.ConnectLERequest.newBuilder()
                        .apply {
                            setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                            setRandom(deviceAddr)
                        }
                        .build()
                )
        // Verify that connection response has connection
        assertThat(leConn.hasConnection()).isTrue()
        // Verify ACL connection
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )

        // Verify address type
        assertThat(remoteLeDevice.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM)

        // Disconnect from bumble
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .apply { setConnection(leConn.connection) }
                    .build()
            )
        // Verify ACL disconnection
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, remoteLeDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )

        unregisterIntentActions(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
        )
    }

    /**
     * Test the address type of a bonded device after a Bluetooth restart.
     *
     * Prerequisites:
     * 1. Android Bluetooth is enabled.
     * 2. Bumble and Android are bonded over BR/EDR.
     *
     * Steps:
     * 1. Bond Bumble and Android using the testStep_Bond helper method.
     * 2. Restart the Bluetooth adapter using the testStep_restartBt() helper method.
     *
     * Expectation:
     * 1. The set of bonded devices still contains the Bumble device after the restart.
     * 2. The address type of the bonded Bumble device is BluetoothDevice.ADDRESS_TYPE_PUBLIC.
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.retain_address_type")
    @Throws(Exception::class)
    fun testAddressType_onBluetoothOnOff_typePublic() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()

        // Create Bond over classic
        testStep_Bond(intentReceiver, bumbleDevice!!, BluetoothDevice.TRANSPORT_BREDR)
        // Verify address type
        assertThat(bumbleDevice!!.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC)
        // Restart Bluetooth
        testStep_restartBt()
        // Verify address type after restart
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice!!.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC)

        intentReceiver.close()
    }

    /**
     * Test the address type of a bonded device with a random address after a Bluetooth restart.
     *
     * Prerequisites:
     * 1. Android Bluetooth is enabled.
     * 2. Bumble and Android are bonded over BR/EDR.
     * 3. Bumble is configured to use a random address.
     *
     * Steps:
     * 1. Scan for Bumble device with RANDOM address.
     * 2. Create bond with Bumble
     * 3. Restart the Bluetooth adapter using the testStep_restartBt() helper method.
     * 4. Get the BluetoothDevice object for the bonded Bumble device.
     *
     * Expectation: The address type of the bonded Bumble device is
     * BluetoothDevice.ADDRESS_TYPE_RANDOM.
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.retain_address_type")
    @Throws(Exception::class)
    fun testAddressType_onBluetoothOnOff_typeRandom() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .build()
        // Start advertising from bumble side with address type RANDOM
        val future = CompletableFuture<ScanResult>()
        // LE Scan setting
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        // LE Scan filter
        val scanFilter = ScanFilter.Builder().build()
        // LE Scan result callback
        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    Log.d(TAG, "onScanResult: result=$result")
                    assertThat(result).isNotNull()
                    assertThat(result.device).isNotNull()
                    if (Utils.BUMBLE_RANDOM_ADDRESS == result.device.address) {
                        future.complete(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.d(TAG, "onScanFailed: errorCode=$errorCode")
                    future.complete(null)
                }
            }
        // Start advertising from bumble side with address type RANDOM
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .apply {
                        setLegacy(true)
                        setConnectable(true)
                        setOwnAddressType(OwnAddressType.RANDOM)
                    }
                    .build()
            )
        // Make Bumble discoverable
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )
        // Start LE scanning
        leScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        // Wait for the LE scan completion
        val result =
            future.completeOnTimeout(null, DISCOVERY_TIMEOUT.toLong(), TimeUnit.MILLISECONDS).join()
        // Stop LE Scan
        leScanner.stopScan(scanCallback)
        // Verify that the result list is not empty
        assertThat(result).isNotNull()
        // Get the first device from the list
        val leDevice = result.device
        // Create Bond over classic
        testStep_Bond(intentReceiver, leDevice, BluetoothDevice.TRANSPORT_LE)
        // Verify the address type
        assertThat(leDevice.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM)
        // Restart Bluetooth
        testStep_restartBt()
        // Verify address type after restart
        assertThat(adapter.bondedDevices).contains(leDevice)
        // Verify the address type
        assertThat(leDevice.addressType).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM)

        intentReceiver.close()
    }

    /**
     * Test that two separate Bumble devices are discovered by the Android device during a single
     * discovery scan.
     *
     * Steps:
     * 1. Make the first Bumble device (bumble) discoverable in general mode.
     * 2. Make the second Bumble device (secondBumble) discoverable in general mode.
     * 3. Start device discovery on the Android adapter.
     * 4. Wait for both Bumble devices to be discovered within a timeout period.
     * 5. Cancel the device discovery on the Android adapter.
     *
     * Expectation: Both bumbleDevice and secondBumbleDevice are not null, indicating that both
     * Bumble devices were successfully discovered during the scan.
     */
    @Test
    @Throws(Exception::class)
    fun testSecondBumbleDevice_onScan() {
        registerIntentActions(BluetoothDevice.ACTION_FOUND)

        // Make Bumble discoverable
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )
        // Make Second Bumble device discoverable
        secondBumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )

        bumbleDevice = null
        secondBumbleDevice = null
        // Start device discovery from Android
        deviceFound = CompletableFuture()
        secondDeviceFound = CompletableFuture()
        assertThat(adapter.startDiscovery()).isTrue()
        bumbleDevice =
            deviceFound
                .completeOnTimeout(null, DISCOVERY_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                .join()
        secondBumbleDevice =
            secondDeviceFound
                .completeOnTimeout(null, DISCOVERY_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                .join()

        assertThat(bumbleDevice).isNotNull()
        assertThat(secondBumbleDevice).isNotNull()
        assertThat(adapter.cancelDiscovery()).isTrue()

        unregisterIntentActions(BluetoothDevice.ACTION_FOUND)
    }

    /**
     * Test a failure scenario for Cross-Transport Key Derivation (CTKD) where an initial LE pairing
     * attempt from the DUT is interrupted, followed by a successful BR/EDR pairing.
     *
     * Prerequisites:
     * 1. Bumble is configured for MITM protection, Secure Connections (SC), and bonding.
     * 2. Bumble is set to use a public identity address.
     * 3. Bumble is configured to distribute all key types (Encryption Key, Identity Key, Signing
     *    Key, Link Key) for both initiator and responder roles.
     *
     * Steps:
     * 1. Start LE advertising on Bumble with a public address type.
     * 2. Initiate bonding with the Bumble device over LE transport from the DUT.
     * 3. Confirm the pairing on the Android side.
     * 4. Disconnect Bumble to simulate a CTKD failure scenario.
     * 5. Restart device discovery on the Android side.
     * 6. Initiate bonding with the Bumble device over BR/EDR transport from the DUT.
     * 7. Confirm the pairing on the Android side.
     * 8. Verify the bonding success over BREDR.
     *
     * Expectation:
     * 1. The initial LE pairing attempt fails due to intentional disconnection.
     * 2. The subsequent BR/EDR pairing attempt from the DUT successfully bonds with Bumble,
     *    demonstrating that the BR/EDR pairing can proceed independently after a failed LE CTKD
     *    attempt.
     */
    @Test
    @Throws(Exception::class)
    fun testCtkd_FailureScenario() {
        registerIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
            BluetoothDevice.ACTION_FOUND,
        )

        bumble
            .bumbleConfigBlocking()
            .override(
                BumbleConfigProto.OverrideRequest.newBuilder()
                    .apply {
                        setIoCapability(BumbleConfigProto.IoCapability.NO_OUTPUT_NO_INPUT)
                        setPairingConfig(
                            BumbleConfigProto.PairingConfig.newBuilder()
                                .apply {
                                    setSc(true)
                                    setMitm(true)
                                    setBonding(true)
                                    setIdentityAddressType(OwnAddressType.PUBLIC)
                                }
                                .build()
                        )
                        addInitiatorKeyDistribution(
                            BumbleConfigProto.KeyDistribution.ENCRYPTION_KEY
                        )
                        addInitiatorKeyDistribution(BumbleConfigProto.KeyDistribution.IDENTITY_KEY)
                        addInitiatorKeyDistribution(BumbleConfigProto.KeyDistribution.SIGNING_KEY)
                        addInitiatorKeyDistribution(BumbleConfigProto.KeyDistribution.LINK_KEY)
                        addResponderKeyDistribution(
                            BumbleConfigProto.KeyDistribution.ENCRYPTION_KEY
                        )
                        addResponderKeyDistribution(BumbleConfigProto.KeyDistribution.IDENTITY_KEY)
                        addResponderKeyDistribution(BumbleConfigProto.KeyDistribution.SIGNING_KEY)
                        addResponderKeyDistribution(BumbleConfigProto.KeyDistribution.LINK_KEY)
                    }
                    .build()
            )

        val requestBuilder =
            AdvertiseRequest.newBuilder().apply {
                setLegacy(true)
                setConnectable(true)
                setOwnAddressType(OwnAddressType.PUBLIC)
            }

        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        // Start advertising from bumble side with address type PUBLIC
        bumble.host().advertise(requestBuilder.build(), responseObserver)

        // Make Bumble discoverable
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )
        testStepStartDiscovery()

        assertThat(bumbleDevice?.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        val responseObserverIterator = responseObserver.iterator()
        val advertiseResponse = responseObserverIterator.next()
        assertThat(bumbleDevice?.setPairingConfirmation(true)).isTrue()

        // Disconnect from Bumble
        bumble
            .hostBlocking()
            .disconnect(
                HostProto.DisconnectRequest.newBuilder()
                    .apply { setConnection(advertiseResponse.connection) }
                    .build()
            )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        testStepStartDiscovery()

        assertThat(bumbleDevice?.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        bumbleDevice?.setPairingConfirmation(true)

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        unregisterIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
            BluetoothDevice.ACTION_FOUND,
        )
    }

    /**
     * Verify identity retention on BT restart
     *
     * Steps:
     * 1. Put the ref device in pairing mode (connectable advertising) using RPA
     * 2. Discover the ref device on the DUT and pair with it
     * 3. Verify address mapping of ref device
     * 4. Restart BT on DUT
     * 5. Verify address mapping of ref device
     *
     * Expectation: Identity address should retain on BT restart
     */
    @Test
    @Throws(Exception::class)
    fun testIdentityAddressRetentionOnRestart() {
        // Advertise from Bumble using RPA
        testStep_startAdvertiseUsingRpa_onBumble()
        // Start Device Discovery from Android
        testStepStartDiscovery()
        // Bond with Bumble over LE transport with RPA
        testStep_bondWithBumbleOverLe(bumbleDevice!!, OwnAddressType.RANDOM)
        assertThat(bumbleDevice?.identityAddress).isNotNull()
        val savedAddr = bumbleDevice?.identityAddress
        testStep_restartBt()
        assertThat(adapter.bondedDevices).contains(bumbleDevice)
        assertThat(bumbleDevice?.identityAddress).isEqualTo(savedAddr)
    }

    /**
     * Test LE pairing flow with Auto transport
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is non discoverable over BR/EDR and discoverable over LE
     * 2. Bumble LE AD Flags in advertisement support dual mode
     * 3. Android starts discovery of remote devices
     * 4. Android initiates pairing with Bumble using Auto transport
     *
     * Expectation: Pairing succeeds over LE Transport
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.pairing_transport_selection")
    @Throws(Exception::class)
    fun testBondLe_AutoTransport() {
        registerIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )

        // Make Bumble non-discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.NOT_DISCOVERABLE) }
                    .build()
            )

        // Make Bumble non-connectable over BR/EDR
        val request =
            HostProto.SetConnectabilityModeRequest.newBuilder()
                .apply { setMode(HostProto.ConnectabilityMode.NOT_CONNECTABLE) }
                .build()
        bumble.hostBlocking().setConnectabilityMode(request)

        // Start LE advertisement from Bumble
        val requestBuilder =
            AdvertiseRequest.newBuilder().apply {
                setLegacy(true)
                setConnectable(true)
                setOwnAddressType(OwnAddressType.PUBLIC)
            }

        val dataTypeBuilder =
            DataTypes.newBuilder().apply {
                setCompleteLocalName(BUMBLE_DEVICE_NAME)
                setIncludeCompleteLocalName(true)
                // Set LE AD Flags to be LE General discoverable, also supports dual mode
                setLeDiscoverabilityModeValue(DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE)
            }
        requestBuilder.setData(dataTypeBuilder.build())

        val responseObserver = StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse>()
        bumble.host().advertise(requestBuilder.build(), responseObserver)

        // Start Device Discovery from Android
        testStepStartDiscovery()

        // Start pairing from Android with Auto transport
        assertThat(bumbleDevice?.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice?.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        unregisterIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )
    }

    /**
     * Test BR/EDR pairing flow with Auto transport
     *
     * Prerequisites:
     * 1. Bumble and Android are not bonded
     *
     * Steps:
     * 1. Bumble is discoverable over BR/EDR and non discoverable over LE
     * 2. Android starts discovery of remote devices
     * 3. Android initiates pairing with Bumble using Auto transport
     *
     * Expectation: Pairing succeeds over BR/EDR Transport
     */
    @Test
    @RequiresFlagsEnabled("com.android.bluetooth.flags.pairing_transport_selection")
    @Throws(Exception::class)
    fun testBondBrEdr_AutoTransport() {
        registerIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )

        // Make Bumble discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL) }
                    .build()
            )

        // Make Bumble connectable over BR/EDR
        val request =
            HostProto.SetConnectabilityModeRequest.newBuilder()
                .apply { setMode(HostProto.ConnectabilityMode.CONNECTABLE) }
                .build()
        bumble.hostBlocking().setConnectabilityMode(request)

        // Start Device Discovery from Android
        testStepStartDiscovery()

        // Start pairing from Android with Auto transport
        assertThat(bumbleDevice?.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue()

        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(bumbleDevice?.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        unregisterIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )
    }

    // Helper/testStep functions go here
    /**
     * Helper function to start device discovery
     *
     * Steps:
     * 1. Android starts discovery of remote devices
     *
     * Expectation:
     * 1. Android receives discovery started intent
     * 2. Android receives discovery finished intent
     * 3. Checks whether Bumble device was found
     */
    @Throws(Exception::class)
    private fun testStepStartDiscovery() {
        registerIntentActions(BluetoothDevice.ACTION_FOUND)
        bumbleDevice = null

        // Start device discovery from Android
        deviceFound = CompletableFuture()
        assertThat(adapter.startDiscovery()).isTrue()
        bumbleDevice =
            deviceFound
                .completeOnTimeout(null, DISCOVERY_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                .join()
        assertThat(bumbleDevice).isNotNull()
        assertThat(adapter.cancelDiscovery()).isTrue()

        unregisterIntentActions(BluetoothDevice.ACTION_FOUND)
    }

    private fun testStep_Bond(
        parentIntentReceiver: IntentReceiver?,
        device: BluetoothDevice,
        transport: Int,
    ) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                ),
            )

        // Create bond
        assertThat(device.createBond(transport)).isTrue()

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
        )

        intentReceiver.verifyReceived(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(device.setPairingConfirmation(true)).isTrue()

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    private fun testStep_Advertise(ownAddressType: OwnAddressType) {
        // Start advertising
        val addrType =
            if (ownAddressType == OwnAddressType.RANDOM)
                AdvertisingSetParameters.ADDRESS_TYPE_RANDOM
            else AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC
        val parameters =
            AdvertisingSetParameters.Builder()
                .setOwnAddressType(addrType)
                .setConnectable(true)
                .build()
        val advertiseData = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val advertisingSetCallback = object : AdvertisingSetCallback() {}
        leAdvertiser.startAdvertisingSet(
            parameters,
            advertiseData,
            null,
            null,
            null,
            0,
            0,
            advertisingSetCallback,
        )
    }

    @Throws(Exception::class)
    private fun testStep_startAdvertiseUsingRpa_onBumble() {
        // Make Bumble non-discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .apply { setMode(DiscoverabilityMode.NOT_DISCOVERABLE) }
                    .build()
            )
        // Make Bumble connectable using RPA
        val rpa = TestUtil.generateRpa(Utils.BUMBLE_IRK)
        val dataTypeBuilder =
            DataTypes.newBuilder().apply {
                setCompleteLocalName(BUMBLE_DEVICE_NAME)
                setLeDiscoverabilityModeValue(DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE)
            }
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .apply {
                        setLegacy(false)
                        setConnectable(true)
                        setOwnAddressType(OwnAddressType.RESOLVABLE_OR_RANDOM)
                        setData(dataTypeBuilder.build())
                        setRandomAddress(copyFrom(rpa.toAddressBytes()))
                    }
                    .build()
            )
    }

    private fun testStep_bondWithBumbleOverLe(
        device: BluetoothDevice,
        ownAddressType: OwnAddressType,
    ) {
        registerIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )

        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .apply {
                        setService(
                            GattProto.GattServiceParams.newBuilder()
                                .apply { setUuid(BATTERY_UUID.toString()) }
                                .build()
                        )
                    }
                    .build()
            )
        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .apply {
                        setService(
                            GattProto.GattServiceParams.newBuilder()
                                .apply { setUuid(HOGP_UUID.toString()) }
                                .build()
                        )
                    }
                    .build()
            )

        val responseObserver = StreamObserverSpliterator<Unit, PairingEvent>()

        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .apply {
                        setLegacy(true)
                        setConnectable(true)
                        setOwnAddressType(ownAddressType)
                    }
                    .build()
            )

        val pairingEventAnswerObserver =
            bumble
                .security()
                .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(responseObserver)

        assertThat(device.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()

        verifyIntentReceivedUnordered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
        )
        verifyIntentReceivedUnordered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )

        // Approve pairing from Android
        assertThat(device.setPairingConfirmation(true)).isTrue()

        val pairingEvent = responseObserver.iterator().next()
        assertThat(pairingEvent.hasJustWorks()).isTrue()
        pairingEventAnswerObserver.onNext(
            PairingEventAnswer.newBuilder()
                .apply {
                    setEvent(pairingEvent)
                    setConfirm(true)
                }
                .build()
        )

        // Ensure that pairing succeeds
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        unregisterIntentActions(
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_PAIRING_REQUEST,
        )
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
            .onReceive(any<Context>(), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnordered(num: Int, vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(BOND_INTENT_TIMEOUT.toMillis()).times(num))
            .onReceive(any<Context>(), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnordered(vararg matchers: Matcher<Intent>) {
        verifyIntentReceivedUnordered(1, *matchers)
    }

    private fun removeBond(device: BluetoothDevice) {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(receiver, filter)

        assertThat(device.removeBond()).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        context.unregisterReceiver(receiver)
    }

    /**
     * Helper function to add reference count to registered intent actions
     *
     * @param actions new intent actions to add. If the array is empty, it is a no-op.
     */
    private fun registerIntentActions(vararg actions: String) {
        if (actions.isEmpty()) {
            return
        }
        if (getTotalActionRegistrationCounts() > 0) {
            Log.d(TAG, "registerIntentActions(): unregister ALL intents")
            context.unregisterReceiver(receiver)
        }
        for (action in actions) {
            actionRegistrationCounts.merge(action, 1, Integer::sum)
        }
        val filter = IntentFilter()
        actionRegistrationCounts.entries
            .filter { it.value > 0 }
            .forEach { entry ->
                Log.d(TAG, "registerIntentActions(): Registering action = ${entry.key}")
                filter.addAction(entry.key)
            }
        context.registerReceiver(receiver, filter)
    }

    /**
     * Helper function to reduce reference count to registered intent actions If total reference
     * count is zero after removal, no broadcast receiver will be registered.
     *
     * @param actions intent actions to be removed. If some action is not registered, it is no-op
     *   for that action. If the actions array is empty, it is also a no-op.
     */
    private fun unregisterIntentActions(vararg actions: String) {
        if (actions.isEmpty() || getTotalActionRegistrationCounts() <= 0) {
            return
        }
        Log.d(TAG, "unregisterIntentActions(): unregister ALL intents")
        context.unregisterReceiver(receiver)
        for (action in actions) {
            val currentCount = actionRegistrationCounts[action] ?: continue
            val newCount = currentCount - 1
            if (newCount <= 0) {
                actionRegistrationCounts.remove(action)
            } else {
                actionRegistrationCounts[action] = newCount
            }
        }
        if (getTotalActionRegistrationCounts() > 0) {
            val filter = IntentFilter()
            actionRegistrationCounts.entries
                .filter { it.value > 0 }
                .forEach { entry ->
                    Log.d(TAG, "unregisterIntentActions(): Registering action = ${entry.key}")
                    filter.addAction(entry.key)
                }
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * Get sum of reference count from all registered actions
     *
     * @return sum of reference count from all registered actions
     */
    private fun getTotalActionRegistrationCounts(): Int {
        return actionRegistrationCounts.values.sum()
    }

    private fun testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue()
        assertThat(BlockingBluetoothAdapter.enable()).isTrue()
    }

    companion object {
        private val BOND_INTENT_TIMEOUT = Duration.ofSeconds(10)
        private const val DISCOVERY_TIMEOUT = 5000L
        private val BATTERY_UUID = ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        private val HOGP_UUID = ParcelUuid.fromString("00001812-0000-1000-8000-00805F9B34FB")
    }
}
