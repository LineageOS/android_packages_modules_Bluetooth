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

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/** InCallService to monitor and control call states from snippet. */
class InCallServiceImpl : InCallService() {

    interface Callback {
        /** Callback invoked whenever a call state is changed, including any call added. */
        fun onCallStateChanged(call: Call, state: Int)
    }

    /** Local Binder used to get a reference to InCallServiceImpl. */
    inner class LocalBinder : Binder() {
        fun getService() = this@InCallServiceImpl
    }

    internal val callbacks = mutableMapOf<String, Callback>()

    /** Notifies the call state to all registered callbacks. */
    internal fun notifyCallState(call: Call, state: Int) =
        callbacks.values.forEach { it.onCallStateChanged(call, state) }

    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "InCallService bound.")
        if (intent.action == LOCAL_BINDING_LOCATION) {
            return LocalBinder()
        }
        return super.onBind(intent)
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(
            object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    notifyCallState(call, state)
                }
            }
        )

        // Notify the initial state because `registerCallback` doesn't spawn it.
        notifyCallState(call, call.details.state)
    }

    companion object {
        private const val TAG = "SnippetInCallService"
        const val LOCAL_BINDING_LOCATION = "inCallServiceLocalBindingAction"
    }
}
