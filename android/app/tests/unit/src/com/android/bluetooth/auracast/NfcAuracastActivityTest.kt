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

import android.app.Notification
import android.app.NotificationManager
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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class NfcAuracastActivityTest {
    @get:Rule val setFlagsRule = SetFlagsRule()

    private lateinit var mockNotificationManager: NotificationManager

    @Before
    fun setUp() {
        mockNotificationManager = mock<NotificationManager>()

        // Override the providers in the Activity to return your mocks
        NfcAuracastActivity.notificationManagerProvider = { mockNotificationManager }
    }

    @After
    fun tearDown() {
        // Reset the providers back to real system services
        NfcAuracastActivity.notificationManagerProvider = {
            it.getSystemService(NotificationManager::class.java)!!
        }
    }

    /** Helper function to create an intent mimicking an NFC tap with specific payload */
    private fun createNdefIntent(payloadStr: String): Intent {
        val record =
            NdefRecord(
                NdefRecord.TNF_MIME_MEDIA,
                "application/vnd.bluetooth.le.oob".toByteArray(),
                ByteArray(0),
                payloadStr.toByteArray(),
            )
        val ndefMessage = NdefMessage(arrayOf(record))

        // Grab the target context (the main app package) rather than the test package
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        // Explicitly specify the target component using targetContext
        // to bypass the system PackageManager intent resolution phase.
        return Intent().apply {
            setClassName(targetContext, NfcAuracastActivity::class.java.name)
            action = NfcAdapter.ACTION_NDEF_DISCOVERED
            // Explicitly typing as Array<Parcelable> resolves the Kotlin putExtra inference error
            val messages: Array<Parcelable> = arrayOf(ndefMessage)
            putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, messages)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testActionNotNdefDiscovered_doesNothing() {
        val intent = createNdefIntent("BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;;")
        // Set to an unsupported action
        intent.action = Intent.ACTION_VIEW

        ActivityScenario.launch<NfcAuracastActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }

        verify(mockNotificationManager, never()).notify(anyInt(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testNdefMissingAuracastPrefix_doesNothing() {
        val intent = createNdefIntent("RANDOM_DATA_WITHOUT_PREFIX")

        ActivityScenario.launch<NfcAuracastActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }

        verify(mockNotificationManager, never()).notify(anyInt(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testValidAuracastNdef_postsNotification() {
        // "TestName" -> Base64: "VGVzdE5hbWU="
        val intent = createNdefIntent("BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;;")

        ActivityScenario.launch<NfcAuracastActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }

        // Capture the notification passed to the mock NotificationManager
        val notificationCaptor = ArgumentCaptor.forClass(Notification::class.java)
        verify(mockNotificationManager).notify(anyInt(), notificationCaptor.capture())

        // Extract the captured notification and verify its contents
        val notification = notificationCaptor.value
        val title = notification.extras.getString(Notification.EXTRA_TITLE)

        // Assert the decoded broadcast name appears in the title
        assertThat(title).contains("TestName")
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    fun testValidAuracastNdef_missingName_doesNothing() {
        // Missing BN field, but has other valid format data
        val intent = createNdefIntent("BLUETOOTH:UUID:184F;BC:123456;;")

        ActivityScenario.launch<NfcAuracastActivity>(intent).use { scenario ->
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }

        // Without a name, no notification should be shown
        verify(mockNotificationManager, never()).notify(anyInt(), any())
    }
}
