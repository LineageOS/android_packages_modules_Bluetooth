/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.pandora

import android.content.Context
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import pandora.MediaControlGrpc.MediaControlImplBase

@kotlinx.coroutines.ExperimentalCoroutinesApi
class MediaControl(val context: Context) : MediaControlImplBase(), Closeable {

    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val mediaSessionManager =
        checkNotNull(context.getSystemService(MediaSessionManager::class.java)) {
            "Failed to get MediaSessionManager"
        }

    override fun play(request: Empty, responseObserver: StreamObserver<Empty>) {
        executeMediaCommand("Play", KeyEvent.KEYCODE_MEDIA_PLAY, responseObserver)
    }

    override fun pause(request: Empty, responseObserver: StreamObserver<Empty>) {
        executeMediaCommand("Pause", KeyEvent.KEYCODE_MEDIA_PAUSE, responseObserver)
    }

    override fun stop(request: Empty, responseObserver: StreamObserver<Empty>) {
        executeMediaCommand("Stop", KeyEvent.KEYCODE_MEDIA_STOP, responseObserver)
    }

    override fun forward(request: Empty, responseObserver: StreamObserver<Empty>) {
        executeMediaCommand("Forward", KeyEvent.KEYCODE_MEDIA_NEXT, responseObserver)
    }

    override fun backward(request: Empty, responseObserver: StreamObserver<Empty>) {
        executeMediaCommand("Backward", KeyEvent.KEYCODE_MEDIA_PREVIOUS, responseObserver)
    }

    private fun executeMediaCommand(label: String, keyCode: Int, observer: StreamObserver<Empty>) {
        grpcUnary(scope, observer) {
            Log.i(TAG, "MediaControl: $label")
            dispatchMediaKey(keyCode)
            Empty.getDefaultInstance()
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()

        // Dispatch DOWN
        mediaSessionManager.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0),
            true,
        )

        // Dispatch UP
        mediaSessionManager.dispatchMediaKeyEvent(
            KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0),
            true,
        )
    }

    override fun close() {
        Log.d(TAG, "Closing MediaControl scope")
        scope.cancel()
    }

    companion object {
        private const val TAG = "PandoraMediaControl"
    }
}
