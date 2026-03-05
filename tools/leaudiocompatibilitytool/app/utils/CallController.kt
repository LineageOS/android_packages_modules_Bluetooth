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

import android.content.Context
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import com.google.common.annotations.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A controller for call related events. */
@Singleton
class CallController
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val mediaController: IMediaController,
) {
    @VisibleForTesting val callsManager = CallsManager(context)
    @VisibleForTesting val callScopes = mutableMapOf<String, CallControlScope>()
    private var currentCallCookie: String? = null
    private val callStateChangeListeners = mutableListOf<CallStateChangeListener>()
    private var currentCallStates = mutableMapOf<String, CallState>()

    init {
        Log.d(TAG, "init CallController")
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }

    /** Updates the call state and notifies all listeners. */
    fun updateCallState(cookie: String, newCallState: CallState) {
        currentCallStates[cookie] = newCallState
        for (listener in callStateChangeListeners) {
            listener.onCallStateChanged(cookie, newCallState)
        }
    }

    /**
     * Makes a call.
     *
     * @param callDirection Whether the call is incoming or outgoing.
     * @param onCallAddedAction The action to be called when the call is added.
     * @param onAnswerAction The action to be called when the call is answered.
     * @param onDisconnectAction The action to be called when the call is disconnected.
     * @return The cookie of the call.
     */
    suspend fun addCall(
        callDirection: CallDirection,
        onCallAddedAction: CallControlScope.(cookie: String) -> Unit,
        onAnswerAction: (cookie: String) -> Unit,
        onDisconnectAction: (cookie: String) -> Unit,
    ): String {
        val direction =
            if (callDirection == CallDirection.INCOMING) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            }
        val cookie = UUID.randomUUID().toString()

        callsManager.addCall(
            CallAttributesCompat(
                CALLER_NAME,
                CALLER_ADDRESS.toUri(),
                direction = direction,
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            ),
            onAnswer = {
                Log.d(TAG, "onAnswer")
                onAnswerAction(cookie)
                updateCallState(cookie, CallState.CALL_ANSWERED)
            },
            onDisconnect = {
                Log.d(TAG, "onDisconnect")
                onDisconnectAction(cookie)
                callScopes.remove(cookie)
                currentCallCookie = null
                updateCallState(cookie, CallState.CALL_DISCONNECTED)
            },
            onSetActive = {},
            onSetInactive = {},
        ) {
            Log.d(TAG, "onCallAdded")
            callScopes[cookie] = this
            onCallAddedAction(cookie)
            currentCallCookie = cookie
            updateCallState(cookie, CallState.CALL_RINGING)
        }

        return cookie
    }

    /** Ends the current call. */
    suspend fun endCurrentCall() {
        currentCallCookie?.let { cookie ->
            callScopes[cookie]?.disconnect(DisconnectCause(DisconnectCause.LOCAL))
            mediaController.resetMediaSession(cookie)
            mediaController.stopRingtone()
            mediaController.pauseMusic()
            callScopes.remove(cookie)
            currentCallCookie = null
            updateCallState(cookie, CallState.CALL_DISCONNECTED)
        }
    }

    /** Ends all calls and pauses the music in case the call is answered. */
    suspend fun endAllCalls() {
        for ((cookie, callScope) in callScopes) {
            callScope.disconnect(DisconnectCause(DisconnectCause.LOCAL))
            mediaController.resetMediaSession(cookie)
            updateCallState(cookie, CallState.CALL_DISCONNECTED)
        }
        mediaController.pauseMusic()
        callScopes.clear()
        currentCallCookie = null
    }

    /** The listener for call state changes. */
    interface CallStateChangeListener {
        /** Called when the call state changes. */
        fun onCallStateChanged(cookie: String, newState: CallState)
    }

    /** Adds a call state change listener. */
    fun addCallStateChangeListener(listener: CallStateChangeListener) {
        callStateChangeListeners.add(listener)
    }

    /** Removes a call state change listener. */
    fun removeCallStateChangeListener(listener: CallStateChangeListener) {
        callStateChangeListeners.remove(listener)
    }

    companion object {
        const val TAG = "Call Testing"
        const val CALLER_NAME = "Test Caller"
        const val CALLER_ADDRESS = "https://dummy_address"

        enum class CallDirection {
            INCOMING,
            OUTGOING,
        }

        enum class CallState {
            CALL_RINGING,
            CALL_ANSWERED,
            CALL_DISCONNECTED,
        }
    }
}
