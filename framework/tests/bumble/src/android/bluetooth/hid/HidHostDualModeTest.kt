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
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.ACTION_FOUND
import android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
import android.bluetooth.BluetoothDevice.ACTION_UUID
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.EXTRA_NAME
import android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_TRANSPORT
import android.bluetooth.BluetoothDevice.EXTRA_UUID
import android.bluetooth.BluetoothDevice.TRANSPORT_AUTO
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
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
import android.bluetooth.BluetoothProfile.getConnectionStateName
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothUuid
import android.bluetooth.PandoraDevice
import android.bluetooth.VirtualOnly
import android.bluetooth.adapter
import android.bluetooth.getParcelUuidArray
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.hamcrest.CustomTypeSafeMatcher
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import org.hamcrest.core.AllOf
import org.hamcrest.core.IsEqual.equalTo
import org.junit.After
import org.junit.Before
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
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import pandora.HIDGrpc
import pandora.HidProto.HidServiceType
import pandora.HidProto.ServiceRequest
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType

private const val TAG = "HidHostDualModeTest"

/** Test cases for [BluetoothHidHost]. */
@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
@VirtualOnly
class HidHostDualModeTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var hidBlockingStub: HIDGrpc.HIDBlockingStub
    private lateinit var device: BluetoothDevice
    private lateinit var hidService: BluetoothHidHost
    private lateinit var inOrder: InOrder
    private var reportData = byteArrayOf()
    private var isReportUpdated: CompletableFuture<Boolean>? = null

    private val intentHandler = Answer {
        Log.i(TAG, "onReceive(): intent=${it.arguments.contentToString()}")
        val intent = it.getArgument<Intent>(1)
        val action = intent.action
        when {
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action -> {
                Log.d(TAG, "onReceive(): discovery finished")
            }
            BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val state = intent.getIntExtra(EXTRA_STATE, BluetoothAdapter.ERROR)
                val transport = intent.getIntExtra(EXTRA_TRANSPORT, TRANSPORT_AUTO)
                Log.i(
                    TAG,
                    "Connection state change: device=$device ${getConnectionStateName(state)}($state), transport: $transport",
                )
            }
            ACTION_PAIRING_REQUEST == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                Log.i(TAG, "onReceive(): setPairingConfirmation(true) for $device")
            }
            ACTION_BOND_STATE_CHANGED == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(EXTRA_BOND_STATE, BluetoothAdapter.ERROR)
                val prevBondState =
                    intent.getIntExtra(EXTRA_PREVIOUS_BOND_STATE, BluetoothAdapter.ERROR)
                Log.i(
                    TAG,
                    "onReceive(): device $device bond state changed from $prevBondState to $bondState",
                )
            }
            ACTION_UUID == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val uuids = intent.getParcelUuidArray(EXTRA_UUID)
                if (uuids.isEmpty()) {
                    Log.e(TAG, "onReceive(): device $device 0 length uuid list")
                } else {
                    Log.d(TAG, "onReceive(): device $device, UUID=${uuids.contentToString()}")
                }
            }
            ACTION_FOUND == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val deviceName = intent.getStringExtra(EXTRA_NAME)
                Log.i(TAG, "Discovered device: $device with name: $deviceName")
            }
            BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val protocolMode =
                    intent.getIntExtra(
                        BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                        BluetoothHidHost.PROTOCOL_UNSUPPORTED_MODE,
                    )
                Log.i(TAG, "onReceive(): device $device protocol mode $protocolMode")
            }
            BluetoothHidHost.ACTION_HANDSHAKE == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                val handShake =
                    intent.getIntExtra(
                        BluetoothHidHost.EXTRA_STATUS,
                        BluetoothHidDevice.ERROR_RSP_UNKNOWN.toInt(),
                    )
                Log.i(TAG, "onReceive(): device $device handshake status:$handShake")
            }
            BluetoothHidHost.ACTION_REPORT == action -> {
                val device = intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                reportData = intent.getByteArrayExtra(BluetoothHidHost.EXTRA_REPORT)!!
                val reportBufferSize =
                    intent.getIntExtra(BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, 0)
                Log.i(TAG, "onReceive(): device $device reportBufferSize $reportBufferSize")
                isReportUpdated?.complete(true)
            }
            else -> Log.i(TAG, "onReceive(): unknown intent action $action")
        }
    }

    @Before
    fun setUp() {
        doAnswer(intentHandler).whenever(receiver).onReceive(any(), any())

        inOrder = inOrder(receiver)

        val filter = IntentFilter()
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        filter.addAction(ACTION_BOND_STATE_CHANGED)
        filter.addAction(ACTION_PAIRING_REQUEST)
        filter.addAction(ACTION_UUID)
        filter.addAction(ACTION_FOUND)
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
        filter.addAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED)
        filter.addAction(BluetoothHidHost.ACTION_HANDSHAKE)
        filter.addAction(BluetoothHidHost.ACTION_REPORT)
        context.registerReceiver(receiver, filter)

        // Get profile proxies
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_HOST)
        hidService = verifyProfileServiceConnected(BluetoothProfile.HID_HOST) as BluetoothHidHost
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.A2DP)
        val a2dpService = verifyProfileServiceConnected(BluetoothProfile.A2DP) as BluetoothA2dp
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HEADSET)
        val hfpService = verifyProfileServiceConnected(BluetoothProfile.HEADSET) as BluetoothHeadset

        hidBlockingStub = bumble.hidBlocking()
        hidBlockingStub.registerService(
            ServiceRequest.newBuilder().setServiceType(HidServiceType.SERVICE_TYPE_BOTH).build()
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
        verifyIntentReceived(hasAction(ACTION_PAIRING_REQUEST), hasExtra(EXTRA_DEVICE, device))
        bumble.remoteDevice.setPairingConfirmation(true)

        // Make Bumble connectable with some delay
        Thread.sleep(300)

        val request =
            AdvertiseRequest.newBuilder()
                .setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.RANDOM)
                .build()
        bumble.hostBlocking().advertise(request)

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
        // Have to use Hamcrest matchers instead of Mockito matchers in MockitoHamcrest context
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING))
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED))
        assertThat(hidService.getPreferredTransport(device)).isEqualTo(TRANSPORT_BREDR)
        // Two ACTION_UUIDs are returned after pairing with dual mode HID device
        // 2nd ACTION_UUID and ACTION_CONNECTION_STATE_CHANGED has race condition, hence unordered
        verifyIntentReceivedUnorderedAtLeast(
            1,
            hasAction(ACTION_UUID),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(
                EXTRA_UUID,
                AllOf.allOf(
                    Matchers.hasItemInArray(BluetoothUuid.HOGP),
                    Matchers.hasItemInArray(BluetoothUuid.HID),
                ),
            ),
        )

        if (hidService.getPreferredTransport(device) == TRANSPORT_BREDR) {
            // Switch to LE transport to prepare for test cases
            hidService.setPreferredTransport(device, TRANSPORT_LE)
            verifyTransportSwitch(device, TRANSPORT_BREDR, TRANSPORT_LE)
        }

        assertThat(hidService.getPreferredTransport(device)).isEqualTo(TRANSPORT_LE)
    }

    @After
    fun tearDown() {
        if (device.bondState == BOND_BONDED) {
            removeBond(device)
        }
        context.unregisterReceiver(receiver)
    }

    @Test
    fun setPreferredTransportTest() {
        // BR/EDR transport
        hidService.setPreferredTransport(device, TRANSPORT_BREDR)
        verifyTransportSwitch(device, TRANSPORT_LE, TRANSPORT_BREDR)
        // Check if the API returns the correct transport
        assertThat(hidService.getPreferredTransport(device)).isEqualTo(TRANSPORT_BREDR)
    }

    @Test
    fun hogpGetReportTest() {
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
    }

    @Test
    fun hogpGetProtocolModeTest() {
        hidService.getProtocolMode(device)
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED),
            hasExtra(BluetoothHidHost.EXTRA_PROTOCOL_MODE, BluetoothHidHost.PROTOCOL_REPORT_MODE),
        )
    }

    @Test
    fun hogpSetProtocolModeTest() {
        hidService.setProtocolMode(device, BluetoothHidHost.PROTOCOL_BOOT_MODE)
        // Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(BluetoothHidHost.EXTRA_STATUS, BluetoothHidDevice.ERROR_RSP_SUCCESS.toInt()),
        )
    }

    @Test
    fun hogpSetReportTest() {
        // Keyboard report
        hidService.setReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, "010203040506070809")
        /// Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(BluetoothHidHost.EXTRA_STATUS, BluetoothHidDevice.ERROR_RSP_SUCCESS.toInt()),
        )
        // Mouse report
        hidService.setReport(device, BluetoothHidHost.REPORT_TYPE_INPUT, "02030405")
        // Must cast ERROR_RSP_SUCCESS, otherwise, it won't match with the int extra
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_HANDSHAKE),
            hasExtra(BluetoothHidHost.EXTRA_STATUS, BluetoothHidDevice.ERROR_RSP_SUCCESS.toInt()),
        )
    }

    @Test
    fun hogpVirtualUnplugFromHidHostTest() {
        hidService.virtualUnplug(device)
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_NONE),
        )
    }

    private fun verifyTransportSwitch(
        device: BluetoothDevice,
        fromTransport: Int,
        toTransport: Int,
    ) {
        assertThat(fromTransport).isNotEqualTo(toTransport)

        // Capture the next intent with filter
        // Filter is necessary as otherwise it will corrupt all other unordered verifications
        val savedIntent = arrayOfNulls<Intent>(1)
        verifyIntentReceived(
            object : CustomTypeSafeMatcher<Intent>("Intent Matcher") {
                override fun matchesSafely(intent: Intent): Boolean {
                    savedIntent[0] = intent
                    return AllOf.allOf(
                            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
                            hasExtra(EXTRA_DEVICE, device),
                            hasExtra(EXTRA_TRANSPORT, equalTo(fromTransport)),
                            hasExtra(EXTRA_STATE, equalTo(STATE_DISCONNECTED)),
                        )
                        .matches(intent)
                }
            }
        )

        // Verify saved intent is correct
        assertThat(savedIntent[0]).isNotNull()
        val intent = savedIntent[0]!!
        assertThat(intent.action).isNotNull()
        assertThat(intent.action).isEqualTo(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
        assertThat(intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java))
            .isEqualTo(device)
        assertThat(intent.hasExtra(EXTRA_STATE)).isTrue()
        val state = intent.getIntExtra(EXTRA_STATE, STATE_CONNECTED)
        assertThat(state).isAnyOf(STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(intent.hasExtra(EXTRA_TRANSPORT)).isTrue()
        val transport = intent.getIntExtra(EXTRA_TRANSPORT, TRANSPORT_AUTO)
        assertThat(transport).isAnyOf(TRANSPORT_BREDR, TRANSPORT_LE)

        // Conditionally verify the next intent
        if (transport == fromTransport) {
            assertThat(state).isEqualTo(STATE_DISCONNECTED)
            verifyConnectionState(device, equalTo(toTransport), equalTo(STATE_CONNECTING))
        } else {
            assertThat(state).isEqualTo(STATE_CONNECTING)
            verifyConnectionState(device, equalTo(fromTransport), equalTo(STATE_DISCONNECTED))
        }
        verifyConnectionState(device, equalTo(toTransport), equalTo(STATE_CONNECTED))
    }

    private fun verifyConnectionState(
        device: BluetoothDevice,
        transport: Matcher<Int>,
        state: Matcher<Int>,
    ) {
        verifyIntentReceived(
            hasAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_TRANSPORT, transport),
            hasExtra(EXTRA_STATE, state),
        )
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
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyIntentReceivedUnorderedAtLeast(
        atLeast: Int,
        vararg matchers: Matcher<Intent>,
    ) {
        verify(receiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(atLeast))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    private fun verifyProfileServiceConnected(profile: Int): BluetoothProfile {
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(profileServiceListener, timeout(INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
        private const val REPORT_UPDATE_TIMEOUT_MS = 100L
        private const val KEYBD_RPT_ID = 1
        private const val KEYBD_RPT_SIZE = 9
        private const val MOUSE_RPT_ID = 2
        private const val MOUSE_RPT_SIZE = 4
    }
}
