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

package com.android.bluetooth.gatt

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import android.util.Log
import java.util.NoSuchElementException
import java.util.UUID

private const val TAG = GattUtil.TAG_PREFIX + "ContextApp"

/** Application entry mapping UUIDs to appIDs and callbacks. */
class ContextApp<C : IInterface>(
    val uid: Int,
    val name: String,
    val uuid: UUID,
    val callback: C?,
    val transport: Int,
    val tag: String?,
) {
    var id = 0
    /** Flag to signal that transport is congested */
    var isCongested = false
    private var deathRecipient: IBinder.DeathRecipient? = null
    /** Internal callback info queue, waiting to be send on congestion clear */
    private val congestionQueue = mutableListOf<CallbackInfo>()

    fun linkToDeath(recipient: IBinder.DeathRecipient) {
        callback?.let { cb ->
            try {
                cb.asBinder().linkToDeath(recipient, 0)
                deathRecipient = recipient
            } catch (e: RemoteException) {
                Log.e(TAG, "Unable to link deathRecipient for app id=$id")
            }
        }
    }

    fun unlinkToDeath() {
        deathRecipient?.let { recipient ->
            callback?.let { cb ->
                try {
                    cb.asBinder().unlinkToDeath(recipient, 0)
                } catch (e: NoSuchElementException) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id=$id")
                }
            }
        }
    }

    fun queueCallback(callbackInfo: CallbackInfo) {
        congestionQueue.add(callbackInfo)
    }

    fun popQueuedCallback(): CallbackInfo? {
        if (congestionQueue.isEmpty()) {
            return null
        }
        return congestionQueue.removeAt(0)
    }
}
