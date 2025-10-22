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

import android.content.AttributionSource
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import android.util.Log
import com.android.bluetooth.util.getLastAttributionTag
import java.util.NoSuchElementException
import java.util.UUID

private const val TAG = GattUtil.TAG_PREFIX + "ContextApp"

/** Application entry mapping UUIDs to appIDs and callbacks. */
class ContextApp<C : IInterface>(
    uuid: UUID,
    callback: C?,
    uid: Int,
    packageName: String,
    transport: Int,
    source: AttributionSource?,
) {
    @JvmField val mUuid = uuid
    private val mCallback = callback
    @JvmField val mUid = uid
    @JvmField val mPackageName = packageName
    private val mTransport = transport
    @JvmField val mAttributionTag = source.getLastAttributionTag()

    @JvmField var id = 0

    /** Flag to signal that transport is congested */
    @JvmField var isCongested: Boolean = false

    private var mDeathRecipient: IBinder.DeathRecipient? = null

    /** Internal callback info queue, waiting to be send on congestion clear */
    private val mCongestionQueue = mutableListOf<CallbackInfo>()

    fun getCallback(): C? {
        return mCallback
    }

    fun getPackageName(): String {
        return mPackageName
    }

    fun getTransport(): Int {
        return mTransport
    }

    fun linkToDeath(deathRecipient: IBinder.DeathRecipient) {
        mCallback?.let { cb ->
            try {
                cb.asBinder().linkToDeath(deathRecipient, 0)
                mDeathRecipient = deathRecipient
            } catch (e: RemoteException) {
                Log.e(TAG, "Unable to link deathRecipient for app id=$id")
            }
        }
    }

    fun unlinkToDeath() {
        mDeathRecipient?.let { recipient ->
            mCallback?.let { cb ->
                try {
                    cb.asBinder().unlinkToDeath(recipient, 0)
                } catch (e: NoSuchElementException) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id=$id")
                }
            }
        }
    }

    fun queueCallback(callbackInfo: CallbackInfo) {
        mCongestionQueue.add(callbackInfo)
    }

    fun popQueuedCallback(): CallbackInfo? {
        if (mCongestionQueue.isEmpty()) {
            return null
        }
        return mCongestionQueue.removeAt(0)
    }
}
