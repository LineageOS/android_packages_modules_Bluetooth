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
package android.bluetooth.tools.leaudiocompatibilitytool.app.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
class AdbIntentManager @Inject constructor(@ApplicationContext private val context: Context) {
    val testStartCommandListeners = mutableListOf<TestStartCommandListener>()

    init {
        registerBroadcastReceiver()
        Log.d(TAG, "AdbIntentManager init")
    }

    /** Register a broadcast receiver to listen to adb commands. */
    @SuppressLint("UnprotectedReceiver")
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun registerBroadcastReceiver() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    val testId = intent.getStringExtra(EXTRA_TEST_ID)
                    if (testId == null) {
                        Log.d(TAG, "test_id is missing")
                        return
                    }

                    if (action == ACTION_ADB_COMMAND_START_TEST) {
                        for (listener in testStartCommandListeners) {
                            listener.onTestStartCommandReceived(testId)
                            Log.d(TAG, "onTestStartCommandReceived: $testId")
                        }
                    }
                }
            }
        val filter = IntentFilter().apply { addAction(ACTION_ADB_COMMAND_START_TEST) }
        context.registerReceiver(receiver, filter)
    }

    /** The listener for test start command. */
    interface TestStartCommandListener {
        /** Called when the start test command is received. */
        fun onTestStartCommandReceived(testId: String)
    }

    /** Adds a listener to be called when the start test command is received. */
    fun addTestStartCommandListener(listener: TestStartCommandListener) {
        testStartCommandListeners.add(listener)
    }

    /** Removes a listener from the list to be called when the start test command is received. */
    fun removeTestStartCommandListener(listener: TestStartCommandListener) {
        testStartCommandListeners.remove(listener)
    }

    companion object {
        private const val TAG = "AdbIntentManager Testing"
        const val ACTION_ADB_COMMAND_START_TEST =
            "android.bluetooth.tools.leaudiocompatibilitytool.action.ACTION_START_TEST"
        const val EXTRA_TEST_ID = "test_id"
    }
}
