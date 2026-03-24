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
import android.media.AudioManager
import android.util.Log
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import pandora.AudioControlGrpc.AudioControlImplBase

@kotlinx.coroutines.ExperimentalCoroutinesApi
class AudioControl(val context: Context) : AudioControlImplBase(), Closeable {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    private val audioManager =
        checkNotNull(context.getSystemService(AudioManager::class.java)) {
            "Failed to get AudioManager"
        }

    override fun volumeUp(request: Empty, responseObserver: StreamObserver<Empty>) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "AudioControl: Volume Up")
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI,
            )
            Empty.getDefaultInstance()
        }
    }

    override fun volumeDown(request: Empty, responseObserver: StreamObserver<Empty>) {
        grpcUnary(scope, responseObserver) {
            Log.i(TAG, "AudioControl: Volume Down")
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
            Empty.getDefaultInstance()
        }
    }

    override fun close() {
        Log.d(TAG, "Closing AudioControl scope")
        scope.cancel()
    }

    companion object {
        private const val TAG = "PandoraAudioControl"
    }
}
