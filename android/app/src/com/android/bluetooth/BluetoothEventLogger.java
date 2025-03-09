/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.bluetooth;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Queue;

/** This class is to store logs for given size. */
public class BluetoothEventLogger {
    private final String mTitle;
    private final Queue<String> mEvents;
    private final int mSize;

    public BluetoothEventLogger(int size, String title) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be > 0");
        }
        mSize = size;
        mEvents = new ArrayDeque<>(size);
        mTitle = title;
    }

    /** Add the event record */
    public synchronized void add(String msg) {
        if (mEvents.size() == mSize) {
            mEvents.remove();
        }
        mEvents.add(Utils.getLocalTimeString() + " " + msg);
    }

    /** Add the event record */
    public synchronized void clear() {
        mEvents.clear();
    }

    /** Add the event record and log message */
    public synchronized void logv(String tag, String msg) {
        add(msg);
        Log.v(tag, msg);
    }

    /** Add the event record and log debug message */
    public synchronized void logd(String tag, String msg) {
        add(msg);
        Log.d(tag, msg);
    }

    /** Add the event record and log warning message */
    public synchronized void logw(String tag, String msg) {
        add(msg);
        Log.w(tag, msg);
    }

    /** Add the event record and log error message */
    public synchronized void loge(String tag, String msg) {
        add(msg);
        Log.e(tag, msg);
    }

    /** Dump all the events */
    public synchronized void dump(StringBuilder sb) {
        sb.append(mTitle).append(":\n");
        for (String msg : mEvents) {
            sb.append("  ").append(msg).append("\n");
        }
    }
}
