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

import android.platform.test.flag.junit.FlagsParameterization

/** Improve parametric test name readability by removing the common flag prefix */
class FlagsWrapper(val flags: FlagsParameterization) {
    private val PREFIX = "com.android.bluetooth.flags."

    override fun toString(): String {
        return flags.mOverrides.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key.removePrefix(PREFIX)}=${it.value}" }
    }

    companion object {
        @JvmStatic
        fun progressionOf(vararg flags: String): List<FlagsWrapper> {
            return FlagsParameterization.progressionOf(*flags).map { FlagsWrapper(it) }
        }
    }
}
