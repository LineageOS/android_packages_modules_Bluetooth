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
package android.bluetooth.hfp

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
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
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
import android.bluetooth.Utils
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.time.Duration
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.hamcrest.MockitoHamcrest.argThat
import pandora.HFPGrpc
import pandora.HfpProto
import pandora.HostProto
import pandora.SecurityProto

@RunWith(TestParameterInjector::class)
class HfpTest {

    @get:Rule(order = 0) val permissionRule: AdoptShellPermissionsRule = AdoptShellPermissionsRule()

    @get:Rule(order = 1) val bumble = PandoraDevice()

    @get:Rule(order = 2) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var receiver: BroadcastReceiver
    @Mock private lateinit var serviceListener: BluetoothProfile.ServiceListener

    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter: BluetoothAdapter =
        targetContext.getSystemService(BluetoothManager::class.java).adapter

    private lateinit var hfBlockingStub: HFPGrpc.HFPBlockingStub
    private lateinit var bumbleDevice: BluetoothDevice
    private lateinit var hfpService: BluetoothHeadset
    private lateinit var a2dpService: BluetoothA2dp
    private lateinit var inOrder: InOrder

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        inOrder = inOrder(receiver)

        val filter =
            IntentFilter().apply {
                addAction(ACTION_ACL_DISCONNECTED)
                addAction(ACTION_ACL_CONNECTED)
                addAction(ACTION_BOND_STATE_CHANGED)
                addAction(ACTION_PAIRING_REQUEST)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
        targetContext.registerReceiver(receiver, filter)
        Utils.setupIntentLogger(TAG, receiver)

        hfpService = connectToProfile(BluetoothProfile.HEADSET) as BluetoothHeadset
        a2dpService = connectToProfile(BluetoothProfile.A2DP) as BluetoothA2dp

        hfBlockingStub = bumble.hfBlocking()
        bumbleDevice = bumble.remoteDevice
    }

    @After
    fun tearDown() {
        removeBond()
        targetContext.unregisterReceiver(receiver)
    }

    @Test
    fun connectAndDisconnectFromAg() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        assertThat(hfpService.connect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_CONNECTING)
        verifyConnectionState(STATE_CONNECTED)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        assertThat(hfpService.disconnect(bumbleDevice)).isTrue()
        verifyConnectionState(STATE_DISCONNECTING)
        verifyConnectionState(STATE_DISCONNECTED)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun connectAndDisconnectFromHf() {
        prepareBumbleDeviceAsBondedAndDisconnected()

        // Obtain the connection which will be used for EnableSlc
        val address = ByteString.copyFrom(Utils.addressBytesFromString(adapter.address))
        val connectRequest = HostProto.ConnectRequest.newBuilder().setAddress(address).build()
        val response = bumble.hostBlocking().connect(connectRequest)

        // Enable Slc from HF/Bumble side
        hfBlockingStub.enableSlcAsHandsfree(
            HfpProto.EnableSlcAsHandsfreeRequest.newBuilder()
                .setConnection(response.connection)
                .build()
        )

        verifyConnectionState(STATE_CONNECTING)
        verifyConnectionState(STATE_CONNECTED)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        // Disable Slc from HF/Bumble side
        hfBlockingStub.disableSlcAsHandsfree(
            HfpProto.DisableSlcAsHandsfreeRequest.newBuilder()
                .setConnection(response.connection)
                .build()
        )

        verifyConnectionState(STATE_DISCONNECTING)
        verifyConnectionState(STATE_DISCONNECTED)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
    }

    private fun prepareBumbleDeviceAsBondedAndDisconnected() {
        if (bumbleDevice.bondState == BOND_BONDED) {
            assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
            return
        }

        assertThat(bumbleDevice.createBond(TRANSPORT_BREDR)).isTrue()
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDING),
        )
        verifyIntentReceived(
            hasAction(ACTION_ACL_CONNECTED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )
        restartSettingsApp()
        verifyIntentReceived(
            hasAction(ACTION_PAIRING_REQUEST),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
        )

        bumbleDevice.setPairingConfirmation(true)
        verifyIntentReceived(
            hasAction(ACTION_BOND_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(EXTRA_BOND_STATE, BOND_BONDED),
        )
        if (a2dpService.getConnectionPolicy(bumbleDevice) == CONNECTION_POLICY_ALLOWED) {
            assertThat(a2dpService.setConnectionPolicy(bumbleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue()
        }
        // Connect HFP
        assertThat(bumbleDevice.connect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(STATE_CONNECTING)
        verifyConnectionState(STATE_CONNECTED)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_CONNECTED)

        assertThat(bumbleDevice.disconnect()).isEqualTo(BluetoothStatusCodes.SUCCESS)
        verifyConnectionState(STATE_DISCONNECTING)
        verifyConnectionState(STATE_DISCONNECTED)
        assertThat(hfpService.getConnectionState(bumbleDevice)).isEqualTo(STATE_DISCONNECTED)
        verifyIntentReceived(
            hasAction(ACTION_ACL_DISCONNECTED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, TRANSPORT_BREDR),
        )
    }

    private fun restartSettingsApp() {
        // Restart settings and system UI after ACL connection to avoid auto profile connection
        // which leads test failure
        Runtime.getRuntime().exec("am crash com.android.systemui").waitFor()
        Runtime.getRuntime().exec("am crash com.android.settings").waitFor()
    }

    private fun removeBond() {
        if (bumbleDevice.removeBond()) {
            verifyIntentReceived(
                hasAction(ACTION_BOND_STATE_CHANGED),
                hasExtra(EXTRA_DEVICE, bumbleDevice),
                hasExtra(EXTRA_BOND_STATE, BOND_NONE),
            )
        }
        // Remove the bond on the Bumble device as well.
        val localAddress = ByteString.copyFrom(Utils.addressBytesFromString(adapter.address))
        bumble
            .securityStorageBlocking()
            .deleteBond(
                SecurityProto.DeleteBondRequest.newBuilder().setPublic(localAddress).build()
            )
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    private fun verifyConnectionState(state: Int) {
        verifyIntentReceived(
            hasAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(EXTRA_DEVICE, bumbleDevice),
            hasExtra(EXTRA_STATE, state),
        )
    }

    private fun connectToProfile(profile: Int): BluetoothProfile {
        adapter.getProfileProxy(targetContext, serviceListener, profile)
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(serviceListener, timeout(INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private const val TAG = "HfpTest"
        private val INTENT_TIMEOUT = Duration.ofSeconds(10)
    }
}
