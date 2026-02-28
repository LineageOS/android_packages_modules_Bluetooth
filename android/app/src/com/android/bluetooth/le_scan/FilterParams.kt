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
    @get:JvmName("clientInterface") val clientInterface: Int,
    @get:JvmName("filterIndex") val filterIndex: Int,
    @get:JvmName("featureSelection") val featureSelection: Int,
    @get:JvmName("listLogicType") val listLogicType: Int,
    @get:JvmName("filterLogicType") val filterLogicType: Int,
    @get:JvmName("rssiHighValue") val rssiHighValue: Int,
    @get:JvmName("rssiLowValue") val rssiLowValue: Int,
    @get:JvmName("delayMode") val delayMode: Int,
    @get:JvmName("foundTimeout") val foundTimeout: Int,
    @get:JvmName("lostTimeout") val lostTimeout: Int,
    @get:JvmName("foundTimeoutCount") val foundTimeoutCount: Int,
    @get:JvmName("numberOfTrackEntries") val numberOfTrackEntries: Int,
)
