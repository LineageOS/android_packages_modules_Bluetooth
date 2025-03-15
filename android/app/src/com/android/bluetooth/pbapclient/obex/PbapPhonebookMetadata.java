/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

record PbapPhonebookMetadata(
        String phonebook,
        // 2 byte number
        int size,
        // 16 byte number as string
        String databaseIdentifier,
        // 16 byte number as string
        String primaryVersionCounter,
        // 16 byte number as string
        String secondaryVersionCounter) {
    private static final String TAG = PbapPhonebookMetadata.class.getSimpleName();

    public static final int INVALID_SIZE = -1;
    public static final String INVALID_DATABASE_IDENTIFIER = null;
    public static final String DEFAULT_DATABASE_IDENTIFIER = "0";
    public static final String INVALID_VERSION_COUNTER = null;

    @Override
    public String toString() {
        return "<"
                + TAG
                + (" phonebook=" + phonebook)
                + (" size=" + size)
                + (" databaseIdentifier=" + databaseIdentifier)
                + (" primaryVersionCounter=" + primaryVersionCounter)
                + (" secondaryVersionCounter=" + secondaryVersionCounter)
                + ">";
    }
}
