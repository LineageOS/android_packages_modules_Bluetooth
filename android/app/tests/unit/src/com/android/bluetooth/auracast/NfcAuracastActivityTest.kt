/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.auracast

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcastAssistant
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Parcelable
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.flags.Flags
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class NfcAuracastActivityTest {

    @get:Rule val setFlagsRule = SetFlagsRule()

    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter

    companion object {
        // 10s timeout for async intent handling
        private const val ASYNC_TIMEOUT = 10000L
    }

    @Before
    fun setUp() {
        // Grant system-level permissions to bypass SecurityExceptions
        // when fetching connected devices from the Broadcast Assistant proxy.
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .adoptShellPermissionIdentity(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_PRIVILEGED,
            )

        mockNotificationManager = mock()
        mockBluetoothAdapter = mock {
            on { isEnabled }.thenReturn(true)
            on { getProfileProxy(any(), any(), any()) }.thenReturn(false)
        }
        // Override the providers in the Activity to return mocks
        NfcAuracastActivity.notificationManagerProvider = { mockNotificationManager }
        NfcAuracastActivity.bluetoothAdapterProvider = { mockBluetoothAdapter }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        NfcAuracastActivity.notificationManagerProvider = {
            it.getSystemService(NotificationManager::class.java)!!
        }
        NfcAuracastActivity.bluetoothAdapterProvider = {
            (it.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testActionNotNdefDiscovered_doesNothing() {
        val intent =
            createNdefIntent("BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;;").apply {
                action = Intent.ACTION_VIEW // Set to an unsupported action
            }

        launchAndAssertDestroyed(intent)

        verify(mockNotificationManager, never()).notify(any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testNdefMissingAuracastPrefix_doesNothing() {
        val intent = createNdefIntent("RANDOM_DATA_WITHOUT_PREFIX")

        launchAndAssertDestroyed(intent)

        verify(mockNotificationManager, never()).notify(any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testValidAuracast_withoutConnectedDevice_postsNotification() {
        // Base64 "VGVzdE5hbWU=" decodes to "TestName"
        val intent = createNdefIntent("BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;;")

        launchAndAssertDestroyed(intent)

        // Capture the notification passed to the mock NotificationManager
        val notificationCaptor = argumentCaptor<Notification>()
        verify(mockNotificationManager, timeout(ASYNC_TIMEOUT))
            .notify(any(), notificationCaptor.capture())

        // Extract the captured notification and verify the decoded broadcast name appears
        val notification = notificationCaptor.firstValue
        val title = notification.extras.getString(Notification.EXTRA_TITLE)

        assertThat(title).contains("TestName")
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testValidAuracast_withConnectedDevice_postsNotification() {
        val mockAssistant = mock<BluetoothLeBroadcastAssistant>()
        val mockDevice = mock<BluetoothDevice>()

        val testAlias = "My Earbuds"
        doReturn(testAlias).whenever(mockDevice).alias

        // Using doReturn for the assistant property setup
        doReturn(listOf(mockDevice)).whenever(mockAssistant).connectedDevices

        // Using doReturn for the proxy setup.
        // explicitly typed `any<Context>()` and `any<ServiceListener>()`
        doReturn(true)
            .whenever(mockBluetoothAdapter)
            .getProfileProxy(
                any<Context>(),
                any<BluetoothProfile.ServiceListener>(),
                eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT),
            )

        // Base64 "VGVzdE5hbWU=" decodes to "TestName"
        val intent = createNdefIntent("BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;;")
        launchAndAssertDestroyed(intent)

        // Intercept and capture the listener AFTER the activity runs using verify()
        val listenerCaptor = argumentCaptor<BluetoothProfile.ServiceListener>()
        verify(mockBluetoothAdapter)
            .getProfileProxy(
                any<Context>(),
                listenerCaptor.capture(),
                eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT),
            )

        // Simulate the Bluetooth framework connecting the profile
        val listener = listenerCaptor.firstValue
        listener.onServiceConnected(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT, mockAssistant)

        // Verify the Notification was posted with the correct connected text
        val notificationCaptor = argumentCaptor<Notification>()
        verify(mockNotificationManager, timeout(ASYNC_TIMEOUT))
            .notify(any(), notificationCaptor.capture())

        val notification = notificationCaptor.firstValue
        val text = notification.extras.getString(Notification.EXTRA_TEXT)

        assertThat(text).isEqualTo("Listen to TestName audio stream on your My Earbuds")

        // Verify we didn't leak the proxy
        verify(mockBluetoothAdapter)
            .closeProfileProxy(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT, mockAssistant)
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testValidAuracastNdef_missingName_doesNothing() {
        // Missing BN (Broadcast Name) field, but has other valid format data
        val intent = createNdefIntent("BLUETOOTH:UUID:184F;BC:123456;;")

        launchAndAssertDestroyed(intent)

        verify(mockNotificationManager, never()).notify(any(), any())
    }

    // --- Helper Methods ---

    /** Launches the activity with the intent and verifies it finishes immediately. */
    private fun launchAndAssertDestroyed(intent: Intent) {
        ActivityScenario.launch<NfcAuracastActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }

    /** Creates an intent mimicking an NFC tap with a specific payload string. */
    private fun createNdefIntent(payloadStr: String): Intent {
        val record = NdefRecord.createTextRecord(null, payloadStr)
        val ndefMessage = NdefMessage(arrayOf(record))

        // Bypass the system PackageManager intent resolution phase by explicitly
        // specifying the target component using the target context.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            setClassName(targetContext, NfcAuracastActivity::class.java.name)
            val messages: Array<Parcelable> = arrayOf(ndefMessage)
            putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, messages)
        }
    }
}
