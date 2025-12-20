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

package android.bluetooth

import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED
import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED
import android.bluetooth.BluetoothDevice.ACTION_FOUND
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.junit.MockitoJUnit
import pandora.HostProto.DiscoverabilityMode
import pandora.HostProto.SetDiscoverabilityModeRequest

private const val TAG = "DeviceDiscoveryTest"

@RunWith(AndroidJUnit4::class)
class DeviceDiscoveryTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 1) val bumble = PandoraDevice()

    @Mock private lateinit var receiver: BroadcastReceiver

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var inOrder: InOrder

    @Before
    fun setUp() {
        inOrder = inOrder(receiver)

        val filter =
            IntentFilter().apply {
                addAction(ACTION_DISCOVERY_STARTED)
                addAction(ACTION_DISCOVERY_FINISHED)
                addAction(ACTION_FOUND)
            }
        context.registerReceiver(receiver, filter)
        receiver.setupIntentLogger(TAG)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
    }

    @Test
    fun startDeviceDiscoveryTest() {
        assertThat(adapter.startDiscovery()).isTrue()
        verifyIntentReceived(hasAction(ACTION_DISCOVERY_STARTED))

        // Wait for device discovery to complete
        verifyIntentReceived(hasAction(ACTION_DISCOVERY_FINISHED))
    }

    @Test
    fun cancelDeviceDiscoveryTest() {
        assertThat(adapter.startDiscovery()).isTrue()
        verifyIntentReceived(hasAction(ACTION_DISCOVERY_STARTED))

        // Issue a cancel discovery and wait for device discovery finished
        assertThat(adapter.cancelDiscovery()).isTrue()
        verifyIntentReceived(hasAction(ACTION_DISCOVERY_FINISHED))
    }

    @Test
    fun checkDeviceIsDiscoveredTest() {
        // Ensure remote device is discoverable
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                    .build()
            )

        assertThat(adapter.startDiscovery()).isTrue()
        verifyIntentReceived(hasAction(ACTION_DISCOVERY_STARTED))

        // Ensure we received at least one inquiry response
        verifyIntentReceived(hasAction(ACTION_FOUND))

        // Wait for device discovery to complete
        verifyIntentReceived(hasAction(ACTION_DISCOVERY_FINISHED))
    }

    private fun verifyIntentReceived(vararg matchers: Matcher<Intent>) {
        // TODO: b/433584605 - `atLeast(1)` must be removed
        inOrder
            .verify(receiver, timeout(INTENT_TIMEOUT.toMillis()).atLeast(1))
            .onReceive(any(Context::class.java), argThat(allOf(*matchers)))
    }

    companion object {
        private val INTENT_TIMEOUT = Duration.ofSeconds(60)
    }
}
