/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.bluetooth.opp;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.Spy;

import java.util.concurrent.atomic.AtomicBoolean;

/** Test cases for {@link BluetoothOppBtEnablingActivity}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothOppBtEnablingActivityTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    // Activity tests can sometimes flaky because of external factors like system dialog, etc.
    // making the expected Espresso's root not focused or the activity doesn't show up.
    // Add retry rule to resolve this problem.
    @Rule public TestUtils.RetryTestRule mRetryTestRule = new TestUtils.RetryTestRule();

    @Spy BluetoothMethodProxy mBluetoothMethodProxy;

    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();

    private Intent mIntent;
    private int mRealTimeoutValue;

    @BeforeClass
    public static void setUpClass() {
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnablingActivity.class, true, sContext);
    }

    @AfterClass
    public static void tearDownClass() {
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnablingActivity.class, false, sContext);
    }

    @Before
    public void setUp() throws Exception {
        mBluetoothMethodProxy = Mockito.spy(BluetoothMethodProxy.getInstance());
        BluetoothMethodProxy.setInstanceForTesting(mBluetoothMethodProxy);

        mIntent = new Intent();
        mIntent.setClass(sContext, BluetoothOppBtEnablingActivity.class);

        mRealTimeoutValue = BluetoothOppBtEnablingActivity.sBtEnablingTimeoutMs;
        TestUtils.setUpUiTest();
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.tearDownUiTest();
        BluetoothMethodProxy.setInstanceForTesting(null);
        BluetoothOppBtEnablingActivity.sBtEnablingTimeoutMs = mRealTimeoutValue;
    }

    @Ignore("b/277594572")
    @Test
    public void onCreate_bluetoothEnableTimeout_finishAfterTimeout() throws Exception {
        final int spedUpTimeoutValue = 500;
        // To speed up the test
        BluetoothOppBtEnablingActivity.sBtEnablingTimeoutMs = spedUpTimeoutValue;
        doReturn(false).when(mBluetoothMethodProxy).bluetoothAdapterIsEnabled(any());

        try (ActivityScenario<BluetoothOppBtEnablingActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            final BluetoothOppManager[] mOppManager = new BluetoothOppManager[1];
            activityScenario.onActivity(
                    activity -> {
                        // Should be cancelled after timeout
                        mOppManager[0] = BluetoothOppManager.getInstance(activity);
                    });
            Thread.sleep(spedUpTimeoutValue);
            assertThat(mOppManager[0].mSendingFlag).isFalse();
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void onKeyDown_cancelProgress() throws Exception {
        doReturn(false).when(mBluetoothMethodProxy).bluetoothAdapterIsEnabled(any());
        AtomicBoolean finishCalled = new AtomicBoolean(false);

        try (ActivityScenario<BluetoothOppBtEnablingActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            activityScenario.onActivity(
                    activity -> {
                        activity.onKeyDown(
                                KeyEvent.KEYCODE_BACK,
                                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                        // Should be cancelled immediately
                        BluetoothOppManager mOppManager = BluetoothOppManager.getInstance(activity);
                        assertThat(mOppManager.mSendingFlag).isFalse();

                        finishCalled.set(activity.isFinishing());
                    });
        }
        assertThat(finishCalled.get()).isTrue();
    }

    @Test
    public void onCreate_bluetoothAlreadyEnabled_finishImmediately() throws Exception {
        doReturn(true).when(mBluetoothMethodProxy).bluetoothAdapterIsEnabled(any());
        try (ActivityScenario<BluetoothOppBtEnablingActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void broadcastReceiver_onReceive_finishImmediately() throws Exception {
        doReturn(false).when(mBluetoothMethodProxy).bluetoothAdapterIsEnabled(any());
        AtomicBoolean finishCalled = new AtomicBoolean(false);
        try (ActivityScenario<BluetoothOppBtEnablingActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            activityScenario.onActivity(
                    activity -> {
                        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
                        intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
                        activity.mBluetoothReceiver.onReceive(sContext, intent);

                        finishCalled.set(activity.isFinishing());
                    });
        }
        assertThat(finishCalled.get()).isTrue();
    }
}
