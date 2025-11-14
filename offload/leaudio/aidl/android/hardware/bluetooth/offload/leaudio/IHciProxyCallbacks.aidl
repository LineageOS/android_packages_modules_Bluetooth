/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.bluetooth.offload.leaudio;

import android.hardware.bluetooth.offload.leaudio.StreamConfiguration;

/**
 * Interface from HCI-Proxy module to the audio module
 */
interface IHciProxyCallbacks {

    /**
     * Start indication of a stream
     */
    oneway void startStream(in int handle, in StreamConfiguration configuration);

    /**
     * Stop indication of a stream
     */
    oneway void stopStream(in int handle);

    /**
     * ISO Link-feedback event
     */
    oneway void linkFeedback(
        in int handle,
        in int sequence_number,
        in int anchor_point_delay,
        in int sdu_input_status
    );
}
