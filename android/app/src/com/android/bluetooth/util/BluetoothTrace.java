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

package com.android.bluetooth.util;

import android.os.Trace;

public final class BluetoothTrace {

    private BluetoothTrace() {}

    /**
     * Writes a trace message that a given section of code has begun. Must be followed by a call to
     * {@link #asyncTraceForTrackEnd} using the same track name and cookie.
     *
     * @param tag The tag used to denote the trace track.
     * @param methodName The method name denoting this slice within the track.
     * @param cookie The cookie associated with this track.
     */
    public static void asyncTraceForTrackBegin(String tag, String methodName, int cookie) {
        if (!android.os.Flags.asyncTraceForTrack()) {
            return;
        }
        Trace.asyncTraceForTrackBegin(tag, methodName, cookie);
    }

    /**
     * Must be called exactly once for each call to {@link #asyncTraceForTrackBegin(String, String,
     * int)} using the same track name, and cookie.
     *
     * @param tag The tag used to denote the trace track.
     * @param cookie The cookie associated with this track.
     */
    public static void asyncTraceForTrackEnd(String tag, int cookie) {
        if (!android.os.Flags.asyncTraceForTrack()) {
            return;
        }
        Trace.asyncTraceForTrackEnd(tag, cookie);
    }
}
