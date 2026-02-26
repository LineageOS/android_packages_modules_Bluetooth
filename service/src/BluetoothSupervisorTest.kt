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

package com.android.server.bluetooth.test

import android.app.ActivityManager
import android.app.Application
import android.os.Looper
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.BluetoothSupervisorNew
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// TODO when cleaning systemServerMigrateBmsToKotlin, remove all transition comments from this file

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
@kotlin.time.ExperimentalTime
class BluetoothSupervisorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val looper = Looper.getMainLooper()

    private lateinit var supervisor: BluetoothSupervisorNew

    @Before
    fun setUp() {
        supervisor = BluetoothSupervisorNew(context, looper, BluetoothComponentTest.setup())
    }

    private fun verifyActiveUser(user: UserHandle) {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)

        supervisor.api.dump(null, printWriter, null)
        val dumpOutput = stringWriter.toString()

        assertThat(dumpOutput).contains("BluetoothManagerServiceNew for $user")
    }

    @Test
    fun onSystemShutdown_stopsService() = runTest {
        val user = UserHandle.of(10)
        supervisor.onUserSwitching(user)
        verifyActiveUser(user)

        // Trigger shutdown broadcast
        val intent = android.content.Intent(android.content.Intent.ACTION_SHUTDOWN)
        context.sendBroadcast(intent)
        shadowOf(looper).idle()

        // Verify service is gone
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        supervisor.api.dump(null, printWriter, null)
        val dumpOutput = stringWriter.toString()

        assertThat(dumpOutput).contains("No active user")
    }

    @Test
    fun onUserStarting_delegatesToSwitch() = runTest {
        val user = UserHandle.of(10)
        supervisor.onUserStarting(user)
        verifyActiveUser(user)
    }

    @Test
    fun onUserSwitching_startsNewService() = runTest {
        val user1 = UserHandle.of(10)
        val user2 = UserHandle.of(11)

        supervisor.onUserSwitching(user1)
        verifyActiveUser(user1)

        supervisor.onUserSwitching(user2)
        verifyActiveUser(user2)
    }

    @Test // From supervisor__userStop_whenCurrent_emulateSwitch
    fun onUserStopping_currentUser_stopsService() = runTest {
        val user = UserHandle.of(10)
        supervisor.onUserSwitching(user)
        verifyActiveUser(user)

        supervisor.onUserStopping(user)

        // Should trigger fallback to foreground user
        val foregroundUser = UserHandle.of(ActivityManager.getCurrentUser())
        verifyActiveUser(foregroundUser)
    }

    @Test // From supervisor__userStop_whenNotCurrent_nothingHappen
    fun onUserStopping_otherUser_ignored() = runTest {
        val currentUser = UserHandle.of(10)
        val otherUser = UserHandle.of(11)
        supervisor.onUserSwitching(currentUser)

        supervisor.onUserStopping(otherUser)

        // Should remain on currentUser
        verifyActiveUser(currentUser)
    }

    @Test // From supervisor__foregroundUserStop_whenCurrent_isUnsupported
    fun onUserStopping_onForegroundUser_isNotSupported() = runTest {
        val foregroundUser = UserHandle.of(ActivityManager.getCurrentUser())

        supervisor.onUserSwitching(foregroundUser)

        assertFailsWith<IllegalStateException> { supervisor.onUserStopping(foregroundUser) }
    }

    @Test
    fun rapidUserSwitching_onlyLastUserStarted() = runTest {
        val user1 = UserHandle.of(10)
        val user2 = UserHandle.of(11)
        val user3 = UserHandle.of(12)

        // Since shutdown is instant in the real class currently, this test mostly verifies
        // that sequential calls update the state correctly.
        supervisor.onUserSwitching(user1)
        supervisor.onUserSwitching(user2)
        supervisor.onUserSwitching(user3)

        verifyActiveUser(user3)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            BluetoothAdapterState.disableCacheForTesting = true
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            BluetoothAdapterState.disableCacheForTesting = false
        }
    }
}
