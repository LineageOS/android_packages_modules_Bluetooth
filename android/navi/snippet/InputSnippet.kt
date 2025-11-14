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

package com.google.android.bluetooth.snippet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.bluetooth.snippet.Utils.postSnippetEvent
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc

class InputSnippet : Snippet {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    InputActivity.ACTION_DISPATCH_KEY_EVENT -> {
                        val keyEvent =
                            intent.getParcelableExtra(
                                InputActivity.FIELD_KEY_EVENT,
                                KeyEvent::class.java,
                            ) ?: throw RuntimeException("Cannot retrieve key event!")
                        for (callbackId in callbackIds) {
                            postSnippetEvent(callbackId, SnippetConstants.KEY_EVENT) {
                                putInt(SnippetConstants.KEY_EVENT_FIELD_ACTION, keyEvent.action)
                                putInt(SnippetConstants.KEY_EVENT_FIELD_KEY_CODE, keyEvent.keyCode)
                            }
                        }
                    }
                    InputActivity.ACTION_DISPATCH_MOTION_EVENT -> {
                        val motionEvent =
                            intent.getParcelableExtra(
                                InputActivity.FIELD_MOTION_EVENT,
                                MotionEvent::class.java,
                            ) ?: throw RuntimeException("Cannot retrieve motion event!")
                        for (callbackId in callbackIds) {
                            postSnippetEvent(callbackId, SnippetConstants.MOTION_EVENT) {
                                putInt(SnippetConstants.KEY_EVENT_FIELD_ACTION, motionEvent.action)
                                putInt(
                                    SnippetConstants.MOTION_EVENT_FIELD_BUTTON_STATE,
                                    motionEvent.buttonState,
                                )
                                putFloat(SnippetConstants.MOTION_EVENT_FIELD_X, motionEvent.x)
                                putFloat(SnippetConstants.MOTION_EVENT_FIELD_Y, motionEvent.y)
                            }
                        }
                    }
                }
            }
        }
    private val context = instrumentation.targetContext
    private val callbackIds = mutableSetOf<String>()

    init {
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(InputActivity.ACTION_DISPATCH_KEY_EVENT)
                addAction(InputActivity.ACTION_DISPATCH_MOTION_EVENT)
            },
            Context.RECEIVER_EXPORTED,
        )
    }

    /** Register input event callback of [callbackId] */
    @AsyncRpc(description = "Register an input event callback")
    fun registerInputEventCallback(callbackId: String) {
        callbackIds.add(callbackId)
        context.startActivity(
            Intent(context, InputActivity::class.java).apply {
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Unregister input event callback of [callbackId] */
    @Rpc(description = "Unregister an input event callback")
    fun unregisterInputEventCallback(callbackId: String) {
        callbackIds.remove(callbackId)
    }
}
