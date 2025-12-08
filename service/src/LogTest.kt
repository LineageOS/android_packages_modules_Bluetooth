/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.bluetooth.test

import com.android.server.bluetooth.Log
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val TAG = "LogTest"

@RunWith(RobolectricTestRunner::class)
class LogTest {
    @Test
    fun log_verbose() {
        Log.v(TAG, "Logging verbose")
        Log.v("Logging verbose")
    }

    @Test
    fun log_debug() {
        Log.d(TAG, "Logging debug")
        Log.d("Logging debug")
    }

    @Test
    fun log_info() {
        Log.i(TAG, "Logging info")
        Log.i("Logging info")
    }

    @Test
    fun log_warning() {
        Log.w(TAG, "Logging warning")
        Log.w("Logging warning")
        Log.w(TAG, "Logging warning", RuntimeException("With a Throwable"))
    }

    @Test
    fun log_error() {
        Log.e(TAG, "Logging error")
        Log.e("Logging error")
        Log.e(TAG, "Logging error... ", RuntimeException("With a Throwable"))
    }

    @Test
    fun log_whatATerribleFailure() {
        Log.wtf(TAG, "Logging error")
        Log.wtf("Logging error")
        Log.wtf(TAG, "Logging error... ", RuntimeException("With a Throwable"))
    }

    @Test
    fun log_timeToStringWithZone() {
        assertThat(Log.timeToStringWithZone(123456789)).isEqualTo("01-02 02:17:36.789")
    }

    @Test
    fun `log address with expected address`() {
        assertThat(Log.address("11:22:33:44:55:66")).isEqualTo("XX:XX:XX:XX:55:66")
    }

    @Test
    fun `log address with null`() {
        assertThat(Log.address(null)).isEqualTo("[address is null]")
    }

    @Test
    fun `log address with unexpected length`() {
        assertThat(Log.address("11:22:33:44")).isEqualTo("[address invalid]")
    }
}
