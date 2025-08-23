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

package com.android.bluetooth.vaps;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

public class VapsServerStackEvent {
    // Event types for STACK_EVENT message (coming from native)
    private static final int EVENT_TYPE_NONE = 0;
    public static final int EVENT_TYPE_ON_INITIALIZED = 1;
    public static final int EVENT_TYPE_ON_START_VA_SESSION = 2;
    public static final int EVENT_TYPE_ON_STOP_VA_SESSION = 3;

    public int type;
    public BluetoothDevice device;

    /* Might need more for other callbacks*/

    VapsServerStackEvent(int type) {
        this.type = type;
    }

    @Override
    public String toString() {
        // event dump
        StringBuilder result = new StringBuilder();
        result.append("VapsServerStackEvent {type:").append(eventTypeToString(type));
        result.append(", device:").append(device);
        result.append("}");
        return result.toString();
    }

    private static String eventTypeToString(int type) {
        return switch (type) {
            case EVENT_TYPE_NONE -> "EVENT_TYPE_NONE";
            case EVENT_TYPE_ON_INITIALIZED -> "EVENT_TYPE_ON_INITIALIZED";
            case EVENT_TYPE_ON_START_VA_SESSION -> "EVENT_TYPE_ON_START_VA_SESSION";
            case EVENT_TYPE_ON_STOP_VA_SESSION -> "EVENT_TYPE_ON_STOP_VA_SESSION";
            default -> "EVENT_TYPE_UNKNOWN:" + type;
        };
    }
}
