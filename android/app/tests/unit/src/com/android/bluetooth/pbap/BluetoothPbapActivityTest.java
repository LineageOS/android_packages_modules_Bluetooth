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

package com.android.bluetooth.pbap;

import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

import static com.android.bluetooth.pbap.BluetoothPbapActivity.DISMISS_TIMEOUT_DIALOG;
import static com.android.bluetooth.pbap.BluetoothPbapActivity.DISMISS_TIMEOUT_DIALOG_DELAY_MS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.SpannableStringBuilder;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.concurrent.atomic.AtomicBoolean;

/** Test cases for {@link BluetoothPbapActivity}. */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class BluetoothPbapActivityTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BluetoothMethodProxy mMethodProxy;

    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();

    private Intent mIntent;

    @BeforeClass
    public static void setUpClass() {
        enableActivity(true);
    }

    @AfterClass
    public static void tearDownClass() {
        enableActivity(false);
    }

    @Before
    public void setUp() {
        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);

        mIntent = new Intent();
        mIntent.setClass(sContext, BluetoothPbapActivity.class);
        mIntent.setAction(BluetoothPbapService.AUTH_CHALL_ACTION);

        enableActivity(true);
    }

    @After
    public void tearDown() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(null);
    }

    @Test
    public void activityIsDestroyed_whenLaunchedWithoutIntentAction() throws Exception {
        mIntent.setAction(null);
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void onPreferenceChange_returnsTrue() throws Exception {
        AtomicBoolean result = new AtomicBoolean(false);
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            activityScenario.onActivity(
                    activity ->
                            result.set(
                                    activity.onPreferenceChange(
                                            /* preference= */ null, /* newValue= */ null)));
        }
        assertThat(result.get()).isTrue();
    }

    @Test
    public void onPositive_finishesActivity() throws Exception {
        AtomicBoolean finishCalled = new AtomicBoolean(false);
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            activityScenario.onActivity(
                    activity -> {
                        activity.onPositive();
                        finishCalled.set(activity.isFinishing());
                    });
        }

        assertThat(finishCalled.get()).isTrue();
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(mMethodProxy).contextSendBroadcast(any(), argument.capture());
        assertThat(argument.getValue().getAction())
                .isEqualTo(BluetoothPbapService.AUTH_RESPONSE_ACTION);
    }

    @Test
    public void onNegative_finishesActivity() throws Exception {
        AtomicBoolean finishCalled = new AtomicBoolean(false);
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            activityScenario.onActivity(
                    activity -> {
                        activity.onNegative();
                        finishCalled.set(activity.isFinishing());
                    });
        }

        assertThat(finishCalled.get()).isTrue();
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(mMethodProxy).contextSendBroadcast(any(), argument.capture());
        assertThat(argument.getValue().getAction())
                .isEqualTo(BluetoothPbapService.AUTH_CANCELLED_ACTION);
    }

    @Test
    public void onReceiveTimeoutIntent_sendsDismissDialogMessage() throws Exception {
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            Intent intent = new Intent(BluetoothPbapService.USER_CONFIRM_TIMEOUT_ACTION);
            activityScenario.onActivity(
                    activity -> {
                        activity.mReceiver.onReceive(activity, intent);
                    });
        }

        verify(mMethodProxy)
                .handlerSendMessageDelayed(
                        any(), eq(DISMISS_TIMEOUT_DIALOG), eq(DISMISS_TIMEOUT_DIALOG_DELAY_MS));
    }

    @Test
    public void afterTextChanged() throws Exception {
        AtomicBoolean result = new AtomicBoolean(false);
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            Editable editable = new SpannableStringBuilder("An editable text");
            activityScenario.onActivity(
                    activity -> {
                        activity.afterTextChanged(editable);
                        result.set(activity.getButton(BUTTON_POSITIVE).isEnabled());
                    });
        }
        assertThat(result.get()).isTrue();
    }

    // TODO: Test onSaveInstanceState and onRestoreInstanceState.
    // Note: Activity.recreate() fails. The Activity just finishes itself when recreated.
    //       Fix the bug and test those methods.

    @Test
    public void emptyMethods_doesNotThrowException() throws Exception {
        try (ActivityScenario<BluetoothPbapActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            try {
                activityScenario.onActivity(
                        activity -> {
                            activity.beforeTextChanged(null, 0, 0, 0);
                            activity.onTextChanged(null, 0, 0, 0);
                        });
            } catch (Exception ex) {
                assertWithMessage("Exception should not happen!").fail();
            }
        }
    }

    private static void enableActivity(boolean enable) {
        int enabledState =
                enable ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DEFAULT;

        sContext.getPackageManager()
                .setApplicationEnabledSetting(
                        sContext.getPackageName(), enabledState, DONT_KILL_APP);

        ComponentName activityName = new ComponentName(sContext, BluetoothPbapActivity.class);
        sContext.getPackageManager()
                .setComponentEnabledSetting(activityName, enabledState, DONT_KILL_APP);
    }
}
