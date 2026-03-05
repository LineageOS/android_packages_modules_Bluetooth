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

package com.android.bluetooth.opp

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.R
import com.android.bluetooth.opp.BluetoothOppTestUtils.enableActivity
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/** Test cases for [BluetoothOppBtEnableActivity]. */
@RunWith(AndroidJUnit4::class)
class BluetoothOppBtEnableActivityTest {

    private lateinit var intent: Intent

    @Before
    fun setUp() {
        intent = Intent()
        intent.setClass(context, BluetoothOppBtEnableActivity::class.java)
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun onCreate_clickOnEnable_launchEnablingActivity() {
        ActivityScenario.launch<BluetoothOppBtEnableActivity>(intent).use { activityScenario ->
            activityScenario.onActivity { activity ->
                activity.mOppManager = mock<BluetoothOppManager>()
            }
            onView(withText(R.string.bt_enable_ok))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
                .perform(scrollTo(), click())
            intended(hasComponent(BluetoothOppBtEnablingActivity::class.java.name))
        }
    }

    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().context

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            enableActivity(BluetoothOppBtEnableActivity::class.java, true, context)
            enableActivity(BluetoothOppBtEnablingActivity::class.java, true, context)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            enableActivity(BluetoothOppBtEnableActivity::class.java, false, context)
            enableActivity(BluetoothOppBtEnablingActivity::class.java, false, context)
        }
    }
}
