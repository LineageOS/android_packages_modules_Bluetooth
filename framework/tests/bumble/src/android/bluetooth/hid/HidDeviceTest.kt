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

package android.bluetooth.hid

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.PandoraDevice
import android.bluetooth.adapter
import android.bluetooth.cts.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf.allOf
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
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import pandora.HIDGrpc
import pandora.HidProto.HidServiceType
import pandora.HidProto.ServiceRequest

/** Test cases for [BluetoothHidDevice]. */
@RunWith(AndroidJUnit4::class)
class HidDeviceTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var callback: BluetoothHidDevice.Callback
    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var hidBlockingStub: HIDGrpc.HIDBlockingStub
    private lateinit var executor: ExecutorService
    private lateinit var device: BluetoothDevice
    private lateinit var hidService: BluetoothHidHost
    private lateinit var hidDeviceService: BluetoothHidDevice
    private lateinit var a2dpService: BluetoothA2dp
    private lateinit var hfpService: BluetoothHeadset
    private lateinit var inOrder: InOrder

    private val sdpSettings =
        BluetoothHidDeviceAppSdpSettings(
            SDP_NAME,
            SDP_DESCRIPTION,
            SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HIDD_REPORT_DESC,
        )

    private val sdpSettingsBadDescriptor =
        BluetoothHidDeviceAppSdpSettings(
            SDP_NAME,
            SDP_DESCRIPTION,
            SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO,
            BAD_HIDD_REPORT_DESC,
        )

    private val outQos =
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            QOS_TOKEN_RATE,
            QOS_TOKEN_BUCKET_SIZE,
            QOS_PEAK_BANDWIDTH,
            QOS_LATENCY,
            BluetoothHidDeviceAppQosSettings.MAX,
        )

    @Before
    fun setUp() {
        doAnswer {
                bumble.remoteDevice.setPairingConfirmation(true)
                null
            }
            .whenever(receiver)
            .onReceive(any(), argThat(hasAction(ACTION_PAIRING_REQUEST)))

        inOrder = inOrder(receiver, callback)

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(ACTION_PAIRING_REQUEST)
                addAction(ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        context.registerReceiver(receiver, filter)

        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_HOST)
        hidService = verifyProfileServiceConnected(BluetoothProfile.HID_HOST) as BluetoothHidHost
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_DEVICE)
        hidDeviceService =
            verifyProfileServiceConnected(BluetoothProfile.HID_DEVICE) as BluetoothHidDevice
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.A2DP)
        a2dpService = verifyProfileServiceConnected(BluetoothProfile.A2DP) as BluetoothA2dp
        adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HEADSET)
        hfpService = verifyProfileServiceConnected(BluetoothProfile.HEADSET) as BluetoothHeadset

        hidBlockingStub = bumble.hidBlocking()
        hidBlockingStub.registerService(
            ServiceRequest.newBuilder().setServiceType(HidServiceType.SERVICE_TYPE_HID).build()
        )

        executor = Executors.newSingleThreadExecutor()

        device = bumble.remoteDevice
        if (device.bondState == BOND_BONDED) {
            removeBond(device)
        }
        assertThat(hidDeviceService.registerApp(sdpSettings, null, outQos, executor, callback))
            .isTrue()
        verifyAppStatusChanged(null, true)
    }

    @After
    fun tearDown() {
        assertThat(hidDeviceService.unregisterApp()).isTrue()
        verifyAppStatusChanged(null, false)
        context.unregisterReceiver(receiver)
    }

    /**
     * Test enable HID Device role and connect with a remote device.
     * 1. Create bond with a remote device and connect HID Device profile
     * 2. Disconnect HID Device profile
     * 3. Remove bond with the remote device
     */
    @Test
    fun remoteDeviceConnectToHidDeviceServiceTest() {
        createBond(device)

        verifyConnectHidDeviceService()
        verifyDisconnectHidDeviceService()

        if (device.bondState == BOND_BONDED) {
            removeBond(device)
        }
    }

    /**
     * Test disable HID Device role and connect a remote device to HID Host service.
     * 1. Unregister the app, connect a remote device to HID Host service
     * 2. Remove bond with the remote device
     * 3. Register the app
     */
    @Test
    fun switchHidDeviceToHidHostTest() {
        assertThat(hidDeviceService.unregisterApp()).isTrue()
        verifyAppStatusChanged(null, false)

        verifyRemoteDeviceConnectToHidHostService()

        callback = mock<BluetoothHidDevice.Callback>()
        inOrder = inOrder(receiver, callback)
        assertThat(hidDeviceService.registerApp(sdpSettings, null, outQos, executor, callback))
            .isTrue()
        verifyAppStatusChanged(null, true)
    }

    /**
     * Test disable HID Device role and connect a remote device to HID Host service.
     * 1. Unregister the app.
     * 2. Bond and remove a remote device.
     * 3. Register the app with a bad descriptor.
     */
    @Test
    fun badDescriptorHidDeviceTest() {
        assertThat(hidDeviceService.unregisterApp()).isTrue()
        verifyAppStatusChanged(null, false)

        verifyRemoteDeviceBondToHidHostService()

        callback = mock<BluetoothHidDevice.Callback>()
        inOrder = inOrder(receiver, callback)
        assertThat(
                hidDeviceService.registerApp(
                    sdpSettingsBadDescriptor,
                    null,
                    outQos,
                    executor,
                    callback,
                )
            )
            .isTrue()
        verifyAppStatusChanged(null, true)
    }

    /**
     * Test transmit data to remote device.
     * 1. Create bond and connect to HID Device service.
     * 2. Call sendReport, replyReport and reportError to remote device.
     * 3. Remove bond.
     */
    @Test
    fun sendDataTest() {
        createBond(device)
        verifyConnectHidDeviceService()
        val type: Byte = 0
        val id = 100
        val data = byteArrayOf(0x00, 0x01)
        val error: Byte = 0

        assertThat(hidDeviceService.sendReport(device, id, data)).isTrue()
        assertThat(hidDeviceService.replyReport(device, type, id.toByte(), data)).isTrue()
        assertThat(hidDeviceService.reportError(device, error)).isTrue()

        removeBond(device)
    }

    private fun verifyConnectHidDeviceService() {
        assertThat(a2dpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()
        assertThat(hfpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()
        assertThat(hidService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()

        assertThat(hidDeviceService.connect(device)).isTrue()
        verifyHidDeviceConnectionStateChanged(device, STATE_CONNECTING)
        verifyHidDeviceConnectionStateChanged(device, STATE_CONNECTED)

        assertThat(hidDeviceService.getConnectionState(device)).isEqualTo(STATE_CONNECTED)
    }

    private fun verifyDisconnectHidDeviceService() {
        assertThat(hidDeviceService.disconnect(device)).isTrue()
        verifyHidDeviceConnectionStateChanged(device, STATE_DISCONNECTING)
        verifyHidDeviceConnectionStateChanged(device, STATE_DISCONNECTED)

        assertThat(hidDeviceService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED)
    }

    private fun verifyRemoteDeviceBondToHidHostService() {
        createBond(device)
        removeBond(device)
    }

    private fun verifyRemoteDeviceConnectToHidHostService() {
        createBond(device)
        assertThat(a2dpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()
        assertThat(hfpService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN)).isTrue()
        assertThat(hidService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED)).isTrue()

        assertThat(device.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTING))
        verifyConnectionState(device, equalTo(TRANSPORT_BREDR), equalTo(STATE_CONNECTED))
        assertThat(hidService.getPreferredTransport(device)).isEqualTo(TRANSPORT_BREDR)
        removeBond(device)
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
            hasExtra(BluetoothProfile.EXTRA_STATE, state),
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

    private fun createBond(device: BluetoothDevice) {
        assertThat(device.createBond(TRANSPORT_BREDR)).isTrue()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDING),
        )
        verifyIntentReceived(hasAction(ACTION_PAIRING_REQUEST), hasExtra(EXTRA_DEVICE, device))
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, device),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDED),
        )
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    private fun verifyAppStatusChanged(device: BluetoothDevice?, status: Boolean) {
        inOrder
            .verify(callback, timeout(INTENT_TIMEOUT.toMillis()))
            .onAppStatusChanged(eq(device), eq(status))
    }

    private fun verifyHidDeviceConnectionStateChanged(device: BluetoothDevice, state: Int) {
        inOrder
            .verify(callback, timeout(INTENT_TIMEOUT.toMillis()))
            .onConnectionStateChanged(eq(device), eq(state))
    }

    private fun verifyProfileServiceConnected(profile: Int): BluetoothProfile {
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(profileServiceListener, timeout(INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)

        // HID Device role
        private const val SDP_NAME = "BumbleBluetooth"
        private const val SDP_DESCRIPTION = "BumbleBluetooth HID Device test"
        private const val SDP_PROVIDER = "Android"
        private const val QOS_TOKEN_RATE = 800 // 9 bytes * 1000000 us / 11250 us
        private const val QOS_TOKEN_BUCKET_SIZE = 9
        private const val QOS_PEAK_BANDWIDTH = 0
        private const val QOS_LATENCY = 11250

        private const val ID_KEYBOARD: Byte = 0x01
        private const val ID_MOUSE: Byte = 0x02
        private val BAD_HIDD_REPORT_DESC: ByteArray =
            byteArrayOf(
                0xFE.toByte(), // Long item
                0xFE.toByte(), // Long item
                0xFE.toByte(), // Long item
            )
        private val HIDD_REPORT_DESC: ByteArray =
            byteArrayOf(
                0x05,
                0x01, // Usage page (Generic Desktop)
                0x09,
                0x06, // Usage (Keyboard)
                0xA1.toByte(),
                0x01, // Collection (Application)
                0x85.toByte(),
                ID_KEYBOARD, // Report ID
                0x05,
                0x07, // Usage page (Key Codes)
                0x19,
                0xE0.toByte(), // Usage minimum (224)
                0x29,
                0xE7.toByte(), // Usage maximum (231)
                0x15,
                0x00, // Logical minimum (0)
                0x25,
                0x01, // Logical maximum (1)
                0x75,
                0x01, // Report size (1)
                0x95.toByte(),
                0x08, // Report count (8)
                0x81.toByte(),
                0x02, // Input (Data, Variable, Absolute) ; Modifier byte
                0x75,
                0x08, // Report size (8)
                0x95.toByte(),
                0x01, // Report count (1)
                0x81.toByte(),
                0x01, // Input (Constant)              ; Reserved byte
                0x75,
                0x08, // Report size (8)
                0x95.toByte(),
                0x06, // Report count (6)
                0x15,
                0x00, // Logical Minimum (0)
                0x25,
                0x65, // Logical Maximum (101)
                0x05,
                0x07, // Usage page (Key Codes)
                0x19,
                0x00, // Usage Minimum (0)
                0x29,
                0x65, // Usage Maximum (101)
                0x81.toByte(),
                0x00, // Input (Data, Array)           ; Key array (6 keys)
                0xC0.toByte(), // End Collection
                0x05,
                0x01, // Usage Page (Generic Desktop)
                0x09,
                0x02, // Usage (Mouse)
                0xA1.toByte(),
                0x01, // Collection (Application)
                0x85.toByte(),
                ID_MOUSE, // Report ID
                0x09,
                0x01, // Usage (Pointer)
                0xA1.toByte(),
                0x00, // Collection (Physical)
                0x05,
                0x09, // Usage Page (Buttons)
                0x19,
                0x01, // Usage minimum (1)
                0x29,
                0x03, // Usage maximum (3)
                0x15,
                0x00, // Logical minimum (0)
                0x25,
                0x01, // Logical maximum (1)
                0x75,
                0x01, // Report size (1)
                0x95.toByte(),
                0x03, // Report count (3)
                0x81.toByte(),
                0x02, // Input (Data, Variable, Absolute)
                0x75,
                0x05, // Report size (5)
                0x95.toByte(),
                0x01, // Report count (1)
                0x81.toByte(),
                0x01, // Input (constant)              ; 5 bit padding
                0x05,
                0x01, // Usage page (Generic Desktop)
                0x09,
                0x30, // Usage (X)
                0x09,
                0x31, // Usage (Y)
                0x09,
                0x38, // Usage (Wheel)
                0x15,
                0x81.toByte(), // Logical minimum (-127)
                0x25,
                0x7F, // Logical maximum (127)
                0x75,
                0x08, // Report size (8)
                0x95.toByte(),
                0x03, // Report count (3)
                0x81.toByte(),
                0x06, // Input (Data, Variable, Relative)
                0xC0.toByte(), // End Collection
                0xC0.toByte(), // End Collection
            )
    }
}
