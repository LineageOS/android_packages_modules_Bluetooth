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

package android.bluetooth.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
import android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.EXTRA_STATE
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.PandoraDevice
import android.bluetooth.VirtualOnly
import android.bluetooth.adapter
import android.bluetooth.cts.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf.allOf
import org.hamcrest.core.IsEqual.equalTo
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import pandora.HIDGrpc
import pandora.HidProto
import pandora.HidProto.ReportDataEvent
import pandora.HostProto
import pandora.SecurityProto

/** Test cases for [BluetoothHidHost]. */
@RunWith(TestParameterInjector::class)
@VirtualOnly
class HidHostTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private lateinit var device: BluetoothDevice
    private lateinit var hidService: BluetoothHidHost
    private lateinit var a2dpService: BluetoothA2dp
    private lateinit var hfpService: BluetoothHeadset

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var hidBlockingStub: HIDGrpc.HIDBlockingStub
    private var inOrder: InOrder? = null
    private var reportData = byteArrayOf()
    private var isReportUpdated: CompletableFuture<Boolean>? = null

    @SuppressLint("MissingPermission")
    private val intentHandler = Answer {
        Log.i(TAG, "onReceive(): intent=${it.arguments.contentToString()}")
        val intent = it.getArgument<Intent>(1)
        val action = intent.action
        val device: BluetoothDevice?
        when (action) {
            BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val state = intent.getIntExtra(EXTRA_STATE, BluetoothAdapter.ERROR)
                val transport =
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_TRANSPORT,
                        BluetoothDevice.TRANSPORT_AUTO,
                    )
                Log.i(
                    TAG,
                    "Connection state change: device=$device ${BluetoothProfile.getConnectionStateName(state)}($state), transport: $transport",
                )
            }
            ACTION_PAIRING_REQUEST -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                bumble.remoteDevice.setPairingConfirmation(true)
                Log.i(TAG, "onReceive(): setPairingConfirmation(true) for $device")
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val adapterState =
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.i(TAG, "Adapter state change:$adapterState")
            }
            ACTION_BOND_STATE_CHANGED -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(EXTRA_BOND_STATE, BluetoothAdapter.ERROR)
                val prevBondState =
                    intent.getIntExtra(EXTRA_PREVIOUS_BOND_STATE, BluetoothAdapter.ERROR)
                Log.i(
                    TAG,
                    "onReceive(): device $device bond state changed from $prevBondState to $bondState",
                )
            }
            BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val protocolMode =
                    intent.getIntExtra(
                        BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                        BluetoothHidHost.PROTOCOL_UNSUPPORTED_MODE,
                    )
                Log.i(TAG, "onReceive(): device $device protocol mode $protocolMode")
            }
            BluetoothHidHost.ACTION_HANDSHAKE -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val handShake =
                    intent.getIntExtra(
                        BluetoothHidHost.EXTRA_STATUS,
                        BluetoothHidDevice.ERROR_RSP_UNKNOWN.toInt(),
                    )
                Log.i(TAG, "onReceive(): device $device handshake status:$handShake")
            }
            BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS -> {
                val virtualUnplug =
                    intent.getIntExtra(
                        BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS,
                        BluetoothHidHost.VIRTUAL_UNPLUG_STATUS_FAIL,
                    )
                Log.i(TAG, "onReceive(): Virtual Unplug status:$virtualUnplug")
            }
            BluetoothHidHost.ACTION_REPORT -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                reportData = intent.getByteArrayExtra(BluetoothHidHost.EXTRA_REPORT)!!
                val reportBufferSize =
                    intent.getIntExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, 0)
                Log.i(TAG, "onReceive(): device $device reportBufferSize $reportBufferSize")
                isReportUpdated?.complete(true)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                Log.i(TAG, "onReceive(): ACL Connected with device: $device")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                Log.i(TAG, "onReceive(): ACL Disconnected with device: $device")
            }
            else -> Log.i(TAG, "onReceive(): unknown intent action $action")
        }
    }

    @SuppressLint("MissingPermission")
    @Before
    fun setUp() {
        doAnswer(intentHandler).whenever(receiver).onReceive(any(), any())

        inOrder = inOrder(receiver)

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(ACTION_PAIRING_REQUEST)
                addAction(ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED)
                addAction(BluetoothHidHost.ACTION_HANDSHAKE)
                addAction(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS)
                addAction(BluetoothHidHost.ACTION_REPORT)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
        context.registerReceiver(receiver, filter)
        // Get profile proxies
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_HOST)
        hidService = verifyProfileServiceConnected(BluetoothProfile.HID_HOST) as BluetoothHidHost
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.A2DP)
        a2dpService = verifyProfileServiceConnected(BluetoothProfile.A2DP) as BluetoothA2dp
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HEADSET)
        hfpService = verifyProfileServiceConnected(BluetoothProfile.HEADSET) as BluetoothHeadset

        hidBlockingStub = bumble.hidBlocking()
        hidBlockingStub.registerService(
            HidProto.ServiceRequest.newBuilder()
                .setServiceType(HidProto.HidServiceType.SERVICE_TYPE_HID)
                .build()
        )

        device = bumble.remoteDevice
        // Remove bond if the device is already bonded
        if (device.bondState == BOND_BONDED) {
            removeBond(device)
        }
        assertThat(device.createBond(TRANSPORT_BREDR)).isTrue()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(ACTION_ACL_CONNECTED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )
        restartSettingsApp()
        verifyIntentReceived(hasAction(ACTION_PAIRING_REQUEST), hasExtra(EXTRA_DEVICE, device))
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDED),
        )

        verifyIntentReceived(hasAction(BluetoothDevice.ACTION_UUID), hasExtra(EXTRA_DEVICE, device))

        if (a2dpService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED) {
            assertThat(a2dpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN))
                .isTrue()
        }
        if (hfpService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED) {
            assertThat(hfpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()
        }
        assertThat(device.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING))
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED))
        assertThat(hidService.getPreferredTransport(device)).isEqualTo(TRANSPORT_BREDR)
    }

    @SuppressLint("MissingPermission")
    @After
    fun tearDown() {
        if (device.bondState == BOND_BONDED) {
            removeBond(device)
        }
        context.unregisterReceiver(receiver)
    }

    /**
     * Test HID Disconnection:
     * 1. Android tries to create bond, emitting bonding intent 4. Android confirms the pairing via
     *    pairing request intent
     * 2. Bumble confirms the pairing internally
     * 3. Android tries to HID connect and verifies Connection state intent
     * 4. Bumble Disconnect the HID and Android verifies Connection state intent
     */
    @SuppressLint("MissingPermission")
    @Test
    fun disconnectHidDeviceTest() {
        hidBlockingStub.disconnectHost(Empty.getDefaultInstance())
        verifyProfileDisconnectionState()
    }

    /**
     * Test HID Device reconnection when connection policy change:
     * 1. Android creates bonding and connect the HID Device
     * 2. Android verifies the connection policy
     * 3. Bumble disconnect HID and Android verifies Connection state intent
     * 4. Bumble reconnects and Android verifies Connection state intent
     * 5. Bumble disconnect HID and Android verifies Connection state intent
     * 6. Android disable connection policy
     * 7. Bumble connect the HID and Android verifies Connection state intent
     * 8. Android enable connection policy
     * 9. Bumble disconnect HID and Android verifies Connection state intent
     * 10. Bumble connect the HID and Android verifies Connection state intent
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidReconnectionWhenConnectionPolicyChangeTest() {
        assertThat(hidService.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_ALLOWED)

        hidBlockingStub.disconnectHost(Empty.getDefaultInstance())
        verifyProfileDisconnectionState()

        hidBlockingStub.connectHost(Empty.getDefaultInstance())
        verifyIncomingProfileConnectionState()

        hidBlockingStub.disconnectHost(Empty.getDefaultInstance())
        verifyProfileDisconnectionState()

        assertThat(hidService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()

        reconnectionFromRemoteAndVerifyDisconnectedState()

        assertThat(hidService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED)).isTrue()
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
            hasExtra(EXTRA_STATE, STATE_CONNECTING),
        )
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
            hasExtra(EXTRA_STATE, STATE_CONNECTED),
        )

        hidBlockingStub.disconnectHost(Empty.getDefaultInstance())
        verifyProfileDisconnectionState()

        hidBlockingStub.connectHost(Empty.getDefaultInstance())
        verifyIncomingProfileConnectionState()
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy allowed
     * 1. Android creates bonding and connect the HID Device
     * 2. Android verifies the connection policy
     * 3. BT restart on Android
     * 4. Bumble reconnects and Android verifies Connection state intent
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidReconnectionAfterBTrestartWithConnectionPolicyAllowedTest() {
        assertThat(hidService.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_ALLOWED)

        bluetoothRestart()

        hidBlockingStub.connectHost(Empty.getDefaultInstance())
        verifyIncomingProfileConnectionState()
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy disallowed
     * 1. Android creates bonding and connect the HID Device
     * 2. Android verifies the connection policy
     * 3. Android disable the connection policy
     * 4. BT restart on Android
     * 5. Bumble reconnects and Android verifies Connection state intent
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidReconnectionAfterBTrestartWithConnectionPolicyDisallowedTest() {
        assertThat(hidService.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_ALLOWED)
        assertThat(hidService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()

        bluetoothRestart()
        reconnectionFromRemoteAndVerifyDisconnectedState()
    }

    /**
     * Test HID Device reconnection when device is removed
     * 1. Android creates bonding and connect the HID Device
     * 2. Android verifies the connection policy
     * 3. Android disconnect and remove the bond
     * 4. Bumble reconnects and Android verifies Connection state intent
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidReconnectionAfterDeviceRemovedTest() {
        assertThat(hidService.getConnectionPolicy(device)).isEqualTo(CONNECTION_POLICY_ALLOWED)
        hidBlockingStub.disconnectHost(Empty.getDefaultInstance())
        verifyProfileDisconnectionState()

        device.removeBond()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_NONE),
        )

        // Remove the bond on the Bumble device as well.
        // Not doing so will cause authentication failures because of the
        // incorrect link key.
        val localAddress = ByteString.copyFrom(adapter.address.toAddressBytes())
        bumble
            .securityStorageBlocking()
            .deleteBond(
                SecurityProto.DeleteBondRequest.newBuilder().setPublic(localAddress).build()
            )

        reconnectionFromRemoteAndVerifyDisconnectedState()
    }

    /**
     * Test Virtual Unplug from Hid Host
     * 1. Android creates bonding and connect the HID Device
     * 2. Android Virtual Unplug and verifies Bonding
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidVirtualUnplugFromHidHostTest() {
        hidService.virtualUnplug(device)
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_NONE),
        )
    }

    /**
     * Test Virtual Unplug from Hid Device
     * 1. Android creates bonding and connect the HID Device
     * 2. Bumble Virtual Unplug and Android verifies Bonding
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidVirtualUnplugFromHidDeviceTest() {
        hidBlockingStub.virtualCableUnplugHost(Empty.getDefaultInstance())
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_VIRTUAL_UNPLUG_STATUS),
            hasExtra(
                BluetoothHidHost.EXTRA_VIRTUAL_UNPLUG_STATUS,
                BluetoothHidHost.VIRTUAL_UNPLUG_STATUS_SUCCESS,
            ),
        )
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_NONE),
        )
    }

    /**
     * Test Get Protocol mode
     * 1. Android creates bonding and connect the HID Device
     * 2. Android Gets the Protocol mode and verifies the mode
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidGetProtocolModeTest() {
        hidService.getProtocolMode(device)
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED),
            hasExtra(BluetoothHidHost.EXTRA_PROTOCOL_MODE, BluetoothHidHost.PROTOCOL_REPORT_MODE),
        )
    }

    /**
     * Test Set Protocol mode
     * 1. Android creates bonding and connect the HID Device
     * 2. Android Sets the Protocol mode and verifies the mode
     */
    @SuppressLint("MissingPermission")
    @Test
    @Ignore("b/349351673: sets wrong protocol mode value")
    fun hidSetProtocolModeTest() {
        val hidProtoModeEventObserver =
            hidBlockingStub
                .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onSetProtocolMode(Empty.getDefaultInstance())
        hidService.setProtocolMode(device, BluetoothHidHost.PROTOCOL_BOOT_MODE)
        // Must cast ERROR_RSP_UNSUPPORTED_REQ, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(
                BluetoothHidHost.EXTRA_STATUS,
                BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ.toInt(),
            ),
        )

        if (hidProtoModeEventObserver.hasNext()) {
            val hidProtoModeEvent = hidProtoModeEventObserver.next()
            Log.i(TAG, "Protocol mode:" + hidProtoModeEvent.protocolMode)
            assertThat(hidProtoModeEvent.protocolModeValue)
                .isEqualTo(BluetoothHidHost.PROTOCOL_BOOT_MODE)
        }
    }

    /**
     * Test Get Report
     * 1. Android creates bonding and connect the HID Device
     * 2. Android get report and verifies the report
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidGetReportTest() {
        // Keyboard report
        reportData = byteArrayOf()
        isReportUpdated = CompletableFuture()
        hidService.getReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, KEYBD_RPT_ID.toByte(), 0)
        // Report Buffer = Report ID (1 byte) + Report Data (KEYBD_RPT_SIZE byte)
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_REPORT),
            hasExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, KEYBD_RPT_SIZE + 1),
        )
        isReportUpdated!!
            .completeOnTimeout(null, REPORT_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .join()
        assertThat(reportData).isNotNull()
        assertThat(reportData.size).isGreaterThan(0)
        assertThat(reportData[0]).isEqualTo(KEYBD_RPT_ID.toByte())

        // Mouse report
        reportData = byteArrayOf()
        isReportUpdated = CompletableFuture()
        hidService.getReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, MOUSE_RPT_ID.toByte(), 0)
        // Report Buffer = Report ID (1 byte) + Report Data (MOUSE_RPT_SIZE byte)
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_REPORT),
            hasExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, MOUSE_RPT_SIZE + 1),
        )
        isReportUpdated!!
            .completeOnTimeout(null, REPORT_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .join()
        assertThat(reportData).isNotNull()
        assertThat(reportData.size).isGreaterThan(0)
        assertThat(reportData[0]).isEqualTo(MOUSE_RPT_ID.toByte())

        // Invalid report
        hidService.getReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, INVALID_RPT_ID.toByte(), 0)
        // Must cast ERROR_RSP_INVALID_RPT_ID, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(
                BluetoothHidHost.EXTRA_STATUS,
                BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID.toInt(),
            ),
        )
    }

    /**
     * Test Set Report
     * 1. Android creates bonding and connect the HID Device
     * 2. Android Set report and verifies the report
     */
    @SuppressLint("MissingPermission")
    @Test
    fun hidSetReportTest() {
        val hidReportEventObserver =
            hidBlockingStub
                .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onSetReport(Empty.getDefaultInstance())

        // Todo: as a workaround added 50ms delay.
        // To be removed once root cause is identified for b/382180335
        val future = CompletableFuture<Integer>()
        future.completeOnTimeout(null, 50, TimeUnit.MILLISECONDS).join()

        // Keyboard report
        val kbReportData = "010203040506070809"
        hidService.setReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, kbReportData)
        /// Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(BluetoothHidHost.EXTRA_STATUS, BluetoothHidDevice.ERROR_RSP_SUCCESS.toInt()),
        )

        if (hidReportEventObserver.hasNext()) {
            val hidReportEvent = hidReportEventObserver.next()
            assertThat(hidReportEvent.reportTypeValue).isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT)
            assertThat(hidReportEvent.reportIdValue).isEqualTo(KEYBD_RPT_ID)
            assertThat(hidReportEvent.reportData).isEqualTo(kbReportData.substring(2))
        }
        // Keyboard report - Invalid param
        hidService.setReport(
            device,
            BluetoothHidHost.REPORT_TYPE_INPUT,
            kbReportData.substring(0, 10),
        )
        // Must cast ERROR_RSP_INVALID_PARAM, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(
                BluetoothHidHost.EXTRA_STATUS,
                BluetoothHidDevice.ERROR_RSP_INVALID_PARAM.toInt(),
            ),
        )

        if (hidReportEventObserver.hasNext()) {
            val hidReportEvent = hidReportEventObserver.next()
            assertThat(hidReportEvent.reportTypeValue).isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT)
            assertThat(hidReportEvent.reportIdValue).isEqualTo(KEYBD_RPT_ID)
            assertThat(hidReportEvent.reportData).isEqualTo(kbReportData.substring(2, 10))
        }
        // Mouse report
        val mouseReportData = "02030405"
        hidService.setReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, mouseReportData)
        // Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(BluetoothHidHost.EXTRA_STATUS, BluetoothHidDevice.ERROR_RSP_SUCCESS.toInt()),
        )

        if (hidReportEventObserver.hasNext()) {
            val hidReportEvent = hidReportEventObserver.next()
            assertThat(hidReportEvent.reportTypeValue).isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT)
            assertThat(hidReportEvent.reportIdValue).isEqualTo(MOUSE_RPT_ID)
            assertThat(hidReportEvent.reportData).isEqualTo(mouseReportData.substring(2))
        }
        // Invalid report id
        val inValidReportData = "0304"
        hidService.setReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, inValidReportData)
        // Must cast ERROR_RSP_INVALID_RPT_ID, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(
                BluetoothHidHost.EXTRA_STATUS,
                BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID.toInt(),
            ),
        )
        if (hidReportEventObserver.hasNext()) {
            val hidReportEvent = hidReportEventObserver.next()
            assertThat(hidReportEvent.reportTypeValue).isEqualTo(BluetoothHidHost.REPORT_TYPE_INPUT)
            assertThat(hidReportEvent.reportIdValue).isEqualTo(INVALID_RPT_ID)
            assertThat(hidReportEvent.reportData).isEqualTo(inValidReportData.substring(2))
        }
    }

    /**
     * Test send data
     * 1. DUT sends the data to Bumble remote device using sendData api
     * 2. Verify the data and the report type from bumble side
     */
    @SuppressLint("MissingPermission")
    @Test
    @Throws(Exception::class)
    fun hidSendDataTest() {
        val hidDataEventObserver: Iterator<ReportDataEvent> =
            hidBlockingStub
                .withDeadlineAfter(PROTO_MODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onSendHostData(Empty.getDefaultInstance())

        val future = CompletableFuture<Int?>()
        future.completeOnTimeout(null, 100, TimeUnit.MILLISECONDS).join()
        // Send data
        val Data = "010203040506070809"
        assertThat(hidService.sendData(device, Data)).isTrue()

        if (hidDataEventObserver.hasNext()) {
            val hidDataEvent: ReportDataEvent = hidDataEventObserver.next()
            assertThat(hidDataEvent.reportData).isEqualTo(Data)
            assertThat(hidDataEvent.reportTypeValue).isEqualTo(BluetoothHidHost.REPORT_TYPE_OUTPUT)
        }
    }

    /**
     * Prerequisite REF supports BR/EDR HID DUT and REF are bonded but not connected REF is not
     * connectable
     *
     * TEST - Repair false
     * 1. Initiate profile connections on DUT.
     * 2. Wait for HID profile state to change to CONNECTING.
     * 3. Remove bond.
     * 4. Make REF connectable
     *
     * Expectation: No ACL established between DUT and REF
     *
     * TEST - Repair true
     * 1. Initiate profile connections on DUT.
     * 2. Wait for HID profile state to change to CONNECTING.
     * 3. Remove bond.
     * 4. Make REF connectable and bondable Pair with REF
     *
     * Expectation: HID profile should connect successful after repairing.
     */
    @SuppressLint("MissingPermission")
    @RequiresFlagsEnabled(
        "com.android.bluetooth.flags.reset_state_when_removing_non_connected_hid_device"
    )
    @Test
    fun hidRemoveBondWhenConnectionPendingTest(@TestParameter repair: Boolean) {
        assertThat(device.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_DISCONNECTING))
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_DISCONNECTED))
        // ACL disconnection event might come before profile disconnection, due to race.
        verifyIntentReceivedAnyOrder(
            hasAction(ACTION_ACL_DISCONNECTED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )

        bumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.NOT_CONNECTABLE)
                    .build()
            )

        assertThat(device.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING))
        removeBond(device)
        // 6seconds delay for PAGE_TIMEOUT event
        val future1 = CompletableFuture<Integer>()
        future1.completeOnTimeout(null, 6000, TimeUnit.MILLISECONDS).join()
        bumble
            .hostBlocking()
            .setConnectabilityMode(
                HostProto.SetConnectabilityModeRequest.newBuilder()
                    .setMode(HostProto.ConnectabilityMode.CONNECTABLE)
                    .build()
            )
        if (repair) {
            assertThat(device.createBond(TRANSPORT_BREDR)).isTrue()
            verifyIntentReceived(
                hasAction(ACTION_BOND_STATE_CHANGED),
                hasExtra(EXTRA_DEVICE, device),
                hasExtra(EXTRA_BOND_STATE, BOND_BONDING),
            )
            verifyIntentReceived(
                hasAction(ACTION_ACL_CONNECTED),
                hasExtra(EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
            )
            restartSettingsApp()
            verifyIntentReceived(hasAction(ACTION_PAIRING_REQUEST), hasExtra(EXTRA_DEVICE, device))
            verifyIntentReceived(
                hasAction(ACTION_BOND_STATE_CHANGED),
                hasExtra(EXTRA_DEVICE, device),
                hasExtra(EXTRA_BOND_STATE, BOND_BONDED),
            )
            if (a2dpService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED) {
                assertThat(a2dpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue()
            }
            if (hfpService.getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED) {
                assertThat(hfpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN))
                    .isTrue()
            }
            assertThat(device.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
            verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING))
            verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED))
        } else {
            assertThat(device.isConnected).isFalse()
        }
    }

    private fun verifyConnectionState(
        device: BluetoothDevice,
        transport: Matcher<Int>,
        state: Matcher<Int>,
    ) {
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport),
            hasExtra(EXTRA_STATE, state),
        )
    }

    private fun verifyIncomingProfileConnectionState() {
        // for incoming connection, connection state transit
        // from STATE_ACCEPTING -->STATE_CONNECTED
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
            hasExtra(EXTRA_STATE, STATE_CONNECTED),
        )
    }

    private fun verifyProfileDisconnectionState() {
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
            hasExtra(EXTRA_STATE, STATE_DISCONNECTING),
        )
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
            hasExtra(EXTRA_STATE, STATE_DISCONNECTED),
        )
    }

    private fun reconnectionFromRemoteAndVerifyDisconnectedState() {
        hidBlockingStub.connectHost(Empty.getDefaultInstance())
        val future = CompletableFuture<Integer>()
        future.completeOnTimeout(null, CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS).join()
        assertThat(hidService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED)
    }

    private fun bluetoothRestart() {
        adapter.disable()
        verifyIntentReceived(
            hasAction(BluetoothAdapter.ACTION_STATE_CHANGED),
            hasExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF),
        )
        // Without delay, some time HID auto reconnection
        // triggered by BluetoothAdapterService
        val future = CompletableFuture<Integer>()
        future.completeOnTimeout(null, BT_ON_DELAY_MS, TimeUnit.MILLISECONDS).join()

        adapter.enable()
        verifyIntentReceived(
            hasAction(BluetoothAdapter.ACTION_STATE_CHANGED),
            hasExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON),
        )
    }

    private fun restartSettingsApp() {
        // Restart settings and system UI after ACL connection to avoid auto profile connection
        // which leads test failure
        Runtime.getRuntime().exec("am crash com.android.systemui").waitFor()
        Runtime.getRuntime().exec("am crash com.android.settings").waitFor()
    }

    private fun removeBond(device: BluetoothDevice) {
        assertThat(device.removeBond()).isTrue()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_NONE),
        )
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder!!
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    private fun verifyIntentReceivedAnyOrder(vararg matchers: Matcher<Intent>) {
        verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    private fun verifyProfileServiceConnected(profile: Int): BluetoothProfile {
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(profileServiceListener, timeout(INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private const val TAG = "HidHostTest"
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private const val KEYBD_RPT_ID = 1
        private const val KEYBD_RPT_SIZE = 9
        private const val MOUSE_RPT_ID = 2
        private const val MOUSE_RPT_SIZE = 4
        private const val INVALID_RPT_ID = 3
        private const val CONNECTION_TIMEOUT_MS = 2_000L
        private const val BT_ON_DELAY_MS = 3000L
        private const val REPORT_UPDATE_TIMEOUT_MS = 100L
        private val PROTO_MODE_TIMEOUT = Duration.ofSeconds(10)
    }
}
