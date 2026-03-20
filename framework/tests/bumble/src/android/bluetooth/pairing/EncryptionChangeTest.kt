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

package android.bluetooth.pairing

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.PandoraDevice
import android.bluetooth.StreamObserverSpliterator
import android.bluetooth.adapter
import android.bluetooth.getParcelUuidArray
import android.bluetooth.pairing.utils.IntentReceiver
import android.bluetooth.pairing.utils.TestUtil
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.Context
import android.os.ParcelUuid
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import pandora.GattProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType
import pandora.SecurityProto.PairingEvent
import pandora.SecurityProto.PairingEventAnswer

@RunWith(AndroidJUnit4::class)
class EncryptionChangeTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var profileServiceListener: BluetoothProfile.ServiceListener

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val pairingEventStreamObserver = StreamObserverSpliterator<Void, PairingEvent>()
    private lateinit var util: TestUtil
    private lateinit var bumbleDevice: BluetoothDevice

    private val intentListener = IntentReceiver.IntentListener { intent ->
        val action = intent.action
        if (BluetoothDevice.ACTION_UUID == action) {
            val uuids = intent.getParcelUuidArray(BluetoothDevice.EXTRA_UUID)
            Log.d(TAG, "onReceive(): UUID=${uuids.contentToString()}")
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            Log.d(TAG, "onReceive(): bondState=$bondState")
        }
    }

    @Before
    fun setUp() {
        util =
            TestUtil.Builder(context)
                .setProfileServiceListener(profileServiceListener)
                .setBluetoothAdapter(adapter)
                .build()

        bumbleDevice = bumble.remoteDevice
        if (adapter.bondedDevices.contains(bumbleDevice)) {
            util.removeBond(null, bumbleDevice)
        }
    }

    @After
    fun tearDown() {
        if (adapter.bondedDevices.contains(bumbleDevice)) {
            util.removeBond(null, bumbleDevice)
        }
    }

    @Test
    fun encryptionChangeSecureLeLink() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ENCRYPTION_CHANGE,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .setIntentListener(intentListener)
                .build()

        bumble
            .gattBlocking()
            .registerService(
                GattProto.RegisterServiceRequest.newBuilder()
                    .setService(
                        GattProto.GattServiceParams.newBuilder()
                            .setUuid(HOGP_UUID.toString())
                            .build()
                    )
                    .build()
            )

        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(true)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.PUBLIC)
                    .build()
            )

        val pairingEventAnswerObserver: StreamObserver<PairingEventAnswer> =
            bumble
                .security()
                .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(pairingEventStreamObserver)

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        bumbleDevice.setPairingConfirmation(true)

        val pairingEvent = pairingEventStreamObserver.iterator().next()
        assertThat(pairingEvent.hasJustWorks()).isTrue()
        pairingEventAnswerObserver.onNext(
            PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build()
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true),
            hasExtra(BluetoothDevice.EXTRA_KEY_SIZE, 16),
            hasExtra(
                BluetoothDevice.EXTRA_ENCRYPTION_ALGORITHM,
                BluetoothDevice.ENCRYPTION_ALGORITHM_AES,
            ),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    @Test
    fun encryptionChangeSecureClassicLink() {
        val intentReceiver =
            IntentReceiver.Builder(
                    context,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                    BluetoothDevice.ACTION_ENCRYPTION_CHANGE,
                    BluetoothDevice.ACTION_PAIRING_REQUEST,
                )
                .setIntentListener(intentListener)
                .build()

        val pairingEventAnswerObserver: StreamObserver<PairingEventAnswer> =
            bumble
                .security()
                .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(pairingEventStreamObserver)

        assertThat(bumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_CONSENT),
        )
        bumbleDevice.setPairingConfirmation(true)

        val pairingEvent = pairingEventStreamObserver.iterator().next()
        assertThat(pairingEvent.hasJustWorks()).isTrue()
        pairingEventAnswerObserver.onNext(
            PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build()
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, 0),
            hasExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true),
            hasExtra(BluetoothDevice.EXTRA_KEY_SIZE, 16),
            hasExtra(
                BluetoothDevice.EXTRA_ENCRYPTION_ALGORITHM,
                BluetoothDevice.ENCRYPTION_ALGORITHM_AES,
            ),
        )

        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, bumbleDevice),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED),
        )

        intentReceiver.close()
    }

    companion object {
        private const val TAG = "EncryptionChangeTest"
        private val BOND_INTENT_TIMEOUT = Duration.ofSeconds(10)
        private val HOGP_UUID = ParcelUuid.fromString("00001812-0000-1000-8000-00805F9B34FB")
    }
}
