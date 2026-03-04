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

package com.android.bluetooth.le_scan

// All values of this class are accessed from native; see com_android_bluetooth_scan.cpp
data class FilterParams(
    @JvmField val clientInterface: Int,
    @JvmField val filterIndex: Int,
    @JvmField val featureSelection: Int,
    @JvmField val listLogicType: Int,
    @JvmField val filterLogicType: Int,
    @JvmField val rssiHighValue: Int,
    @JvmField val rssiLowValue: Int,
    @JvmField val delayMode: Int,
    @JvmField val foundTimeout: Int,
    @JvmField val lostTimeout: Int,
    @JvmField val foundTimeoutCount: Int,
    @JvmField val numberOfTrackEntries: Int,
)
