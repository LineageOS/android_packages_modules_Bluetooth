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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.State
import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.BluetoothMethodProxy
import com.android.bluetooth.opp.BluetoothOppTestUtils.enableActivity
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Spy
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [BluetoothOppBtEnablingActivity]. */
@RunWith(AndroidJUnit4::class)
class BluetoothOppBtEnablingActivityTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Spy lateinit var bluetoothMethodProxy: BluetoothMethodProxy

    private lateinit var intent: Intent
    private var realTimeoutValue = 0

    @Before
    fun setUp() {
        bluetoothMethodProxy = Mockito.spy(BluetoothMethodProxy.getInstance())
        BluetoothMethodProxy.setInstanceForTesting(bluetoothMethodProxy)

        intent = Intent().apply { setClass(context, BluetoothOppBtEnablingActivity::class.java) }

        realTimeoutValue = BluetoothOppBtEnablingActivity.sBtEnablingTimeoutMs
    }

    @After
    fun tearDown() {
        BluetoothMethodProxy.setInstanceForTesting(null)
        BluetoothOppBtEnablingActivity.sBtEnablingTimeoutMs = realTimeoutValue
    }

    @Ignore("b/277594572")
    @Test
    fun onCreate_bluetoothEnableTimeout_finishAfterTimeout() {
        val spedUpTimeoutValue = 500
        // To speed up the test
        BluetoothOppBtEnablingActivity.sBtEnablingTimeoutMs = spedUpTimeoutValue
        doReturn(false).whenever(bluetoothMethodProxy).bluetoothAdapterIsEnabled(any())

        ActivityScenario.launch<BluetoothOppBtEnablingActivity>(intent).use { activityScenario ->
            var oppManager: BluetoothOppManager? = null
            activityScenario.onActivity { activity ->
                // Should be cancelled after timeout
                oppManager = BluetoothOppManager.getInstance(activity)
            }
            Thread.sleep(spedUpTimeoutValue.toLong())
            assertThat(oppManager!!.mSendingFlag).isFalse()
            assertThat(activityScenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }

    @Test
    fun onKeyDown_cancelProgress() {
        doReturn(false).whenever(bluetoothMethodProxy).bluetoothAdapterIsEnabled(any())
        val finishCalled = AtomicBoolean(false)

        ActivityScenario.launch<BluetoothOppBtEnablingActivity>(intent).use { activityScenario ->
            activityScenario.onActivity { activity ->
                activity.onKeyDown(
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
                )
                // Should be cancelled immediately
                val oppManager = BluetoothOppManager.getInstance(activity)
                assertThat(oppManager.mSendingFlag).isFalse()

                finishCalled.set(activity.isFinishing)
            }
        }
        assertThat(finishCalled.get()).isTrue()
    }

    @Test
    fun onCreate_bluetoothAlreadyEnabled_finishImmediately() {
        doReturn(true).whenever(bluetoothMethodProxy).bluetoothAdapterIsEnabled(any())
        ActivityScenario.launch<BluetoothOppBtEnablingActivity>(intent).use { activityScenario ->
            assertThat(activityScenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }

    @Test
    fun broadcastReceiver_onReceive_finishImmediately() {
        doReturn(false).whenever(bluetoothMethodProxy).bluetoothAdapterIsEnabled(any())
        val finishCalled = AtomicBoolean(false)
        ActivityScenario.launch<BluetoothOppBtEnablingActivity>(intent).use { activityScenario ->
            activityScenario.onActivity { activity ->
                val receiveIntent =
                    Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                        putExtra(BluetoothAdapter.EXTRA_STATE, State.ON)
                    }
                activity.mBluetoothReceiver.onReceive(context, receiveIntent)

                finishCalled.set(activity.isFinishing)
            }
        }
        assertThat(finishCalled.get()).isTrue()
    }

    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().context

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            enableActivity(BluetoothOppBtEnablingActivity::class.java, true, context)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            enableActivity(BluetoothOppBtEnablingActivity::class.java, false, context)
        }
    }
}
