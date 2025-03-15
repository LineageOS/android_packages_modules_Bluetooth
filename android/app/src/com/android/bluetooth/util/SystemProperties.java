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

// Helper to override android.os.SystemProperties static methods
public class SystemProperties {

    public static MockableSystemProperties mProperties;

    public interface MockableSystemProperties {
        String get(String key);

        int getInt(String key, int def);

        boolean getBoolean(String key, boolean def);
    }

    public static String get(String key) {
        if (mProperties != null) {
            return mProperties.get(key);
        }
        return android.os.SystemProperties.get(key);
    }

    public static int getInt(String key, int def) {
        if (mProperties != null) {
            return mProperties.getInt(key, def);
        }
        return android.os.SystemProperties.getInt(key, def);
    }

    public static boolean getBoolean(String key, boolean def) {
        if (mProperties != null) {
            return mProperties.getBoolean(key, def);
        }
        return android.os.SystemProperties.getBoolean(key, def);
    }
}
