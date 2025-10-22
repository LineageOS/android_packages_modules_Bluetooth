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

package com.android.bluetooth.map

import java.util.Locale

object DeviceWorkArounds {
    const val PCM_CARKIT = "9c:df:03"
    const val FORD_SYNC_CARKIT = "00:1e:ae"
    const val HONDA_CARKIT = "64:d4:bd"
    const val SYNC_CARKIT = "d0:39:72"
    const val BREZZA_ZDI_CARKIT = "28:a1:83"
    const val MERCEDES_BENZ_CARKIT = "00:26:e8"

    @JvmStatic
    fun addressStartsWith(bdAddr: String, carkitAddr: String): Boolean {
        return bdAddr.lowercase(Locale.ROOT).startsWith(carkitAddr)
    }
}
