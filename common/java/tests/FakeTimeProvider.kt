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

package com.android.tests.bluetooth

import com.android.bluetooth.util.TimeProvider
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toKotlinDuration

@kotlin.time.ExperimentalTime
class FakeTimeProvider : TimeProvider {
    private var currentTime: Instant = Instant.DISTANT_PAST

    override fun elapsedRealtime(): Long = currentTime.toEpochMilliseconds()

    override fun uptimeMillis(): Long = currentTime.toEpochMilliseconds()

    fun advanceTime(jDuration: java.time.Duration) = advanceTime(jDuration.toKotlinDuration())

    fun advanceTime(amountToAdvance: Duration) {
        currentTime += amountToAdvance
    }
}
