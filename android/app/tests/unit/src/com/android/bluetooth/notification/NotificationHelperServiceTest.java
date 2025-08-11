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

package com.android.bluetooth.notification;

import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.notification.NotificationHelperService.APM_BT_NOTIFICATION;
import static com.android.bluetooth.notification.NotificationHelperService.NOTIFICATION_ACTION;
import static com.android.bluetooth.notification.NotificationHelperService.NOTIFICATION_EXTRA;
import static com.android.bluetooth.notification.NotificationHelperService.NOTIFICATION_TAG;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.messages.SystemMessageProto.SystemMessage;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/** Test cases for {@link NotificationHelperService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationHelperServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private Context mContext;
    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private NotificationManager mNotificationManager;
    @Mock private PackageManager mPackageManager;
    @Mock private StatusBarNotification mStatusBarNotification;

    private MockContentResolver mMockContentResolver;
    private NotificationHelperService mNotificationHelperService;

    @Before
    public void setUp() {
        final var context = InstrumentationRegistry.getInstrumentation().getContext();
        mMockContentResolver = new MockContentResolver(context);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });
        NotificationHelperService.factoryReset(mMockContentResolver);
        doReturn(mMockContentResolver).when(mContext).getContentResolver();
        doReturn(context.getResources()).when(mContext).getResources();
        doReturn(context.getPackageName()).when(mContext).getPackageName();
        doReturn(mApplicationInfo).when(mContext).getApplicationInfo();
        doReturn(mPackageManager).when(mContext).getPackageManager();
        mockGetSystemService(mContext, NotificationManager.class, mNotificationManager);
        doReturn(new StatusBarNotification[] {})
                .when(mNotificationManager)
                .getActiveNotifications();

        mNotificationHelperService = new NotificationHelperService(mContext);
    }

    @Test
    public void onBind_alwaysReturnsNull() {
        assertThat(mNotificationHelperService.onBind(null)).isNull();
    }

    @Test
    public void onStartCommand_withWrongAction_doesNothing() {
        Intent intent = new Intent("android.bluetooth.some.other.action");
        mNotificationHelperService.onStartCommand(intent, 0, 1);

        verify(mNotificationManager, never())
                .notify(anyString(), anyInt(), any(Notification.class));
    }

    @Test
    public void onStartCommand_withUnknownReason_doesNothing() {
        final String unknownReason = "this_reason_does_not_exist";
        Intent intent = new Intent(NOTIFICATION_ACTION).putExtra(NOTIFICATION_EXTRA, unknownReason);

        mNotificationHelperService.onStartCommand(intent, 0, 1);

        verify(mNotificationManager, never())
                .notify(anyString(), anyInt(), any(Notification.class));
    }

    @Test
    public void onStartCommand_withCorrectAction_sendsNotification() {
        Intent intent =
                new Intent(NOTIFICATION_ACTION).putExtra(NOTIFICATION_EXTRA, APM_BT_NOTIFICATION);
        mNotificationHelperService.onStartCommand(intent, 0, 1);

        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);
        String expectedTag = NOTIFICATION_TAG + "/" + APM_BT_NOTIFICATION;

        verify(mNotificationManager)
                .notify(
                        eq(expectedTag),
                        eq(SystemMessage.ID.NOTE_BT_APM_NOTIFICATION_VALUE),
                        notificationCaptor.capture());

        Notification capturedNotification = notificationCaptor.getValue();
        assertThat(capturedNotification.extras.getString(Notification.EXTRA_TITLE)).isNotNull();
        assertThat(capturedNotification.extras.getString(Notification.EXTRA_TEXT)).isNotNull();
    }

    @Test
    public void sendToggleNotification_cancelsPreviousNotifications() {
        String tag = NOTIFICATION_TAG + "/" + APM_BT_NOTIFICATION;
        doReturn(tag).when(mStatusBarNotification).getTag();
        doReturn(SystemMessage.ID.NOTE_BT_APM_NOTIFICATION_VALUE)
                .when(mStatusBarNotification)
                .getId();
        doReturn(new StatusBarNotification[] {mStatusBarNotification})
                .when(mNotificationManager)
                .getActiveNotifications();

        Intent intent =
                new Intent(NOTIFICATION_ACTION).putExtra(NOTIFICATION_EXTRA, APM_BT_NOTIFICATION);
        mNotificationHelperService.onStartCommand(intent, 0, 1);

        verify(mNotificationManager).cancel(tag, SystemMessage.ID.NOTE_BT_APM_NOTIFICATION_VALUE);
    }
}
