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

package com.android.bluetooth.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * Helper for [Context.registerReceiver] to facilitate usage from kotlin code
 *
 * @param actions The actions to register the intent for. Will be passed to [IntentFilter]
 * @param priority optional: will call [IntentFilter.setPriority]
 * @param looper The looper the intent should be handled on
 * @param onReceive the lambda to run when action are received
 */
inline fun Context.registerReceiver(
    looper: Looper,
    vararg actions: String,
    priority: Int = 0,
    crossinline onReceive: (Context, Intent) -> Unit,
) = registerReceiver(Handler(looper), *actions, priority = priority, onReceive = onReceive)

/**
 * Helper for [Context.registerReceiver] to facilitate usage from kotlin code
 *
 * @param actions The actions to register the intent for. Will be passed to [IntentFilter]
 * @param priority optional: will call [IntentFilter.setPriority]
 * @param handler The Handler the intent should be post on
 * @param onReceive the lambda to run when action are received
 */
inline fun Context.registerReceiver(
    handler: Handler,
    vararg actions: String,
    priority: Int = 0,
    crossinline onReceive: (Context, Intent) -> Unit,
): BroadcastReceiver {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(context, intent)
        }
    registerReceiver(
        receiver,
        IntentFilter().apply {
            actions.forEach { addAction(it) }
            this.priority = priority
        },
        null, // broadcastPermission
        handler,
    )
    return receiver
}
