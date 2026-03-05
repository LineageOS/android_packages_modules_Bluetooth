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
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class Recorder @Inject constructor(@ApplicationContext private val context: Context) : IRecorder {
    private var recorder: MediaRecorder? = null
    private val pathName: String =
        (context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)?.resolve("recording.m4a")
                ?: context.filesDir.resolve("recording.m4a"))
            .toString()

    override fun startRecording(preferredDevice: AudioDeviceInfo?) {
        recorder =
            MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (preferredDevice != null) {
                    Log.d(TAG, "setPreferredDevice: ${preferredDevice.productName}")
                    setPreferredDevice(preferredDevice)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(pathName)
                Log.d(TAG, "Start recording to: $pathName")

                try {
                    prepare()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to prepare recording: ${e.message}")
                }

                start()
            }
    }

    override fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }

    override fun getMaxAmplitude(): Flow<Int> = flow {
        while (true) {
            if (recorder != null) {
                val amplitude = recorder?.maxAmplitude ?: 0
                emit(amplitude)
                // Sample Rate 10Hz for getting the maximum absolute amplitude to prevent
                // overloading the
                // system.
                delay(100)
            } else {
                Log.d(TAG, "Recorder is not running.")
                break
            }
        }
    }

    private companion object {
        const val TAG = "Recorder Testing"
    }
}
