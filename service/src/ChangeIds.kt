/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.bluetooth

import android.compat.annotation.ChangeId
import android.compat.annotation.EnabledSince
import android.os.Build

/** All the [ChangeId] used in the Bluetooth system server. */
object ChangeIds {
    /** After TIRAMISU, applications are not allowed to enable/disable Bluetooth. */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    const val RESTRICT_ENABLE_DISABLE = 218493289L
}
