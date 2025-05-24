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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.Intent;
import android.sysprop.BluetoothProperties;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.intent.Intents;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link BluetoothOppBtEnableActivity}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothOppBtEnableActivityTest {
    // Activity tests can sometimes flaky because of external factors like system dialog, etc.
    // making the expected Espresso's root not focused or the activity doesn't show up.
    // Add retry rule to resolve this problem.
    @Rule public TestUtils.RetryTestRule mRetryTestRule = new TestUtils.RetryTestRule();

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private Intent mIntent;

    @Before
    public void setUp() throws Exception {
        assumeTrue(BluetoothProperties.isProfileOppEnabled().orElse(false));

        mIntent = new Intent();
        mIntent.setClass(mContext, BluetoothOppBtEnableActivity.class);

        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnableActivity.class, true, mContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnablingActivity.class, true, mContext);
        Intents.init();
        TestUtils.setUpUiTest();
    }

    @After
    public void tearDown() throws Exception {
        if (!BluetoothProperties.isProfileOppEnabled().orElse(false)) {
            return;
        }
        Intents.release();
        TestUtils.tearDownUiTest();
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnableActivity.class, false, mContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnablingActivity.class, false, mContext);
    }

    @Test
    public void onCreate_clickOnEnable_launchEnablingActivity() {
        ActivityScenario<BluetoothOppBtEnableActivity> activityScenario =
                ActivityScenario.launch(mIntent);
        activityScenario.onActivity(
                activity -> activity.mOppManager = mock(BluetoothOppManager.class));
        onView(withText(mContext.getText(R.string.bt_enable_ok).toString()))
                .inRoot(isDialog())
                .perform(ViewActions.scrollTo());
        onView(withText(mContext.getText(R.string.bt_enable_ok).toString()))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
                .perform(click());
        intended(hasComponent(BluetoothOppBtEnablingActivity.class.getName()));
    }
}
