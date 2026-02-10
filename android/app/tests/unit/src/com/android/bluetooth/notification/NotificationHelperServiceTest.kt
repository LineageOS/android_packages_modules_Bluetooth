/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.test.mock.MockContentProvider
import android.test.mock.MockContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.bluetooth.notification.NotificationHelperService.APM_BT_NOTIFICATION
import com.android.bluetooth.notification.NotificationHelperService.NOTIFICATION_ACTION
import com.android.bluetooth.notification.NotificationHelperService.NOTIFICATION_EXTRA
import com.android.bluetooth.notification.NotificationHelperService.NOTIFICATION_TAG
import com.android.internal.messages.SystemMessageProto.SystemMessage
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Test cases for [NotificationHelperService]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationHelperServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var applicationInfo: ApplicationInfo
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var statusBarNotification: StatusBarNotification

    private lateinit var mockContentResolver: MockContentResolver
    private lateinit var notificationHelperService: NotificationHelperService

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        mockContentResolver = MockContentResolver(context)
        mockContentResolver.addProvider(
            Settings.AUTHORITY,
            object : MockContentProvider() {
                override fun call(method: String, request: String?, args: Bundle?): Bundle? {
                    return Bundle.EMPTY
                }
            },
        )
        NotificationHelperService.factoryReset(mockContentResolver)
        doReturn(mockContentResolver).whenever(mockContext).contentResolver
        doReturn(context.resources).whenever(mockContext).resources
        doReturn(context.packageName).whenever(mockContext).packageName
        doReturn(applicationInfo).whenever(mockContext).applicationInfo
        mockContext.mockPackageManager(packageManager)
        mockContext.mockGetSystemService<NotificationManager>(notificationManager)
        doReturn(arrayOf<StatusBarNotification>())
            .whenever(notificationManager)
            .getActiveNotifications()

        notificationHelperService = NotificationHelperService(mockContext)
    }

    @Test
    fun onBind_alwaysReturnsNull() {
        assertThat(notificationHelperService.onBind(null)).isNull()
    }

    @Test
    fun onStartCommand_withWrongAction_doesNothing() {
        val intent = Intent("android.bluetooth.some.other.action")
        notificationHelperService.onStartCommand(intent, 0, 1)

        verify(notificationManager, never()).notify(any<String>(), any<Int>(), any<Notification>())
    }

    @Test
    fun onStartCommand_withUnknownReason_doesNothing() {
        val unknownReason = "this_reason_does_not_exist"
        val intent = Intent(NOTIFICATION_ACTION).putExtra(NOTIFICATION_EXTRA, unknownReason)

        notificationHelperService.onStartCommand(intent, 0, 1)

        verify(notificationManager, never()).notify(any<String>(), any<Int>(), any<Notification>())
    }

    @Test
    fun onStartCommand_withCorrectAction_sendsNotification() {
        val capturedNotification = captureNotificationForApmBtNotification()

        assertThat(capturedNotification.extras.getString(Notification.EXTRA_TITLE)).isNotNull()
        assertThat(capturedNotification.extras.getString(Notification.EXTRA_TEXT)).isNotNull()
        assertThat(capturedNotification.contentIntent).isNotNull()
    }

    @Test
    fun sendToggleNotification_cancelsPreviousNotifications() {
        val tag = "$NOTIFICATION_TAG/$APM_BT_NOTIFICATION"
        doReturn(tag).whenever(statusBarNotification).tag
        doReturn(SystemMessage.ID.NOTE_BT_APM_NOTIFICATION_VALUE).whenever(statusBarNotification).id
        doReturn(arrayOf(statusBarNotification)).whenever(notificationManager).activeNotifications

        val intent = Intent(NOTIFICATION_ACTION).putExtra(NOTIFICATION_EXTRA, APM_BT_NOTIFICATION)
        notificationHelperService.onStartCommand(intent, 0, 1)

        verify(notificationManager).cancel(tag, SystemMessage.ID.NOTE_BT_APM_NOTIFICATION_VALUE)
    }

    @Test
    fun onStartCommand_onWatchDevice_doesNotSetContentIntent() {
        // This test verifies that on a watch device, the notification does not include a
        // content intent, as watches cannot display the help webpage.
        doReturn(true).whenever(packageManager).hasSystemFeature(PackageManager.FEATURE_WATCH)

        val capturedNotification = captureNotificationForApmBtNotification()

        // Expect a null contentIntent on watch devices.
        assertThat(capturedNotification.contentIntent).isNull()
    }

    @Test
    fun helper_returnsNotificationWithTitleAndText() {
        val capturedNotification = captureNotificationForApmBtNotification()

        assertThat(capturedNotification.extras.getString(Notification.EXTRA_TITLE)).isNotNull()
        assertThat(capturedNotification.extras.getString(Notification.EXTRA_TEXT)).isNotNull()
    }

    private fun captureNotificationForApmBtNotification(): Notification {
        val intent = Intent(NOTIFICATION_ACTION).putExtra(NOTIFICATION_EXTRA, APM_BT_NOTIFICATION)
        notificationHelperService.onStartCommand(intent, 0, 1)

        val notificationCaptor = argumentCaptor<Notification>()
        val expectedTag = "$NOTIFICATION_TAG/$APM_BT_NOTIFICATION"

        verify(notificationManager)
            .notify(
                eq(expectedTag),
                eq(SystemMessage.ID.NOTE_BT_APM_NOTIFICATION_VALUE),
                notificationCaptor.capture(),
            )

        return notificationCaptor.firstValue
    }
}
