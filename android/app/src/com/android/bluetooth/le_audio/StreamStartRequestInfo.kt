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

package com.android.bluetooth.le_audio

/**
 * Represents the configuration and context for a single audio stream that a peer device has
 * requested to start.
 *
 * @property streamId The ID of the ASE (Audio Stream Endpoint).
 * @property direction The direction of the stream (Sink or Source).
 * @property audioContextType The intended use case for the stream (e.g., media, conversation).
 * @property codecId The codec being requested for the stream.
 * @property sampleRate The sample rate in Hz for the stream.
 */
data class StreamStartRequestInfo(
    val streamId: Int,
    val direction: StreamDirection,
    val audioContextType: Int,
    val codecId: CodecId,
    val sampleRate: Int,
)
