/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link FilterParams}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class FilterParamsTest {
    @Rule public final Expect expect = Expect.create();

    @Test
    public void filterParamsProperties() {
        int clientInterface = 0;
        int filterIndex = 1;
        int featureSelection = 2;
        int listLogicType = 3;
        int filterLogicType = 4;
        int rssiHighValue = 5;
        int rssiLowValue = 6;
        int delayMode = 7;
        int foundTimeout = 8;
        int lostTimeout = 9;
        int foundTimeoutCount = 10;
        int numberOfTrackEntries = 11;

        FilterParams filterParams =
                new FilterParams(
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
                        numberOfTrackEntries);

        expect.that(filterParams.clientInterface()).isEqualTo(clientInterface);
        expect.that(filterParams.filterIndex()).isEqualTo(filterIndex);
        expect.that(filterParams.featureSelection()).isEqualTo(featureSelection);
        expect.that(filterParams.listLogicType()).isEqualTo(listLogicType);
        expect.that(filterParams.filterLogicType()).isEqualTo(filterLogicType);
        expect.that(filterParams.rssiHighValue()).isEqualTo(rssiHighValue);
        expect.that(filterParams.rssiLowValue()).isEqualTo(rssiLowValue);
        expect.that(filterParams.delayMode()).isEqualTo(delayMode);
        expect.that(filterParams.foundTimeout()).isEqualTo(foundTimeout);
        expect.that(filterParams.lostTimeout()).isEqualTo(lostTimeout);
        expect.that(filterParams.foundTimeoutCount()).isEqualTo(foundTimeoutCount);
        expect.that(filterParams.numberOfTrackEntries()).isEqualTo(numberOfTrackEntries);
    }
}
