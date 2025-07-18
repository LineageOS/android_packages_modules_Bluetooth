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

package com.android.bluetooth.le_scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Expect
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [FilterParams]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class FilterParamsTest {
    @get:Rule val expect = Expect.create()

    @Test
    fun filterParamsProperties() {
        val clientInterface = 0
        val filterIndex = 1
        val featureSelection = 2
        val listLogicType = 3
        val filterLogicType = 4
        val rssiHighValue = 5
        val rssiLowValue = 6
        val delayMode = 7
        val foundTimeout = 8
        val lostTimeout = 9
        val foundTimeoutCount = 10
        val numberOfTrackEntries = 11

        val filterParams =
            FilterParams(
                clientInterface,
                filterIndex,
                featureSelection,
                listLogicType,
                filterLogicType,
                rssiHighValue,
                rssiLowValue,
                delayMode,
                foundTimeout,
                lostTimeout,
                foundTimeoutCount,
                numberOfTrackEntries,
            )

        expect.that(filterParams.clientInterface).isEqualTo(clientInterface)
        expect.that(filterParams.filterIndex).isEqualTo(filterIndex)
        expect.that(filterParams.featureSelection).isEqualTo(featureSelection)
        expect.that(filterParams.listLogicType).isEqualTo(listLogicType)
        expect.that(filterParams.filterLogicType).isEqualTo(filterLogicType)
        expect.that(filterParams.rssiHighValue).isEqualTo(rssiHighValue)
        expect.that(filterParams.rssiLowValue).isEqualTo(rssiLowValue)
        expect.that(filterParams.delayMode).isEqualTo(delayMode)
        expect.that(filterParams.foundTimeout).isEqualTo(foundTimeout)
        expect.that(filterParams.lostTimeout).isEqualTo(lostTimeout)
        expect.that(filterParams.foundTimeoutCount).isEqualTo(foundTimeoutCount)
        expect.that(filterParams.numberOfTrackEntries).isEqualTo(numberOfTrackEntries)
    }
}
