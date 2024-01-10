/*
 * Copyright 2023 The Android Open Source Project
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
package com.android.bluetooth.content_profiles;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ContentProfileErrorReportUtilsTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void noOpWhenFlagIsDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_CONTENT_PROFILES_ERRORS_METRICS);
        long previousReportTimeMillis = ContentProfileErrorReportUtils.sLastReportTime;

        assertThat(ContentProfileErrorReportUtils.report(0, 0, 0, 0)).isFalse();
        // The last report time should not be changed.
        assertThat(ContentProfileErrorReportUtils.sLastReportTime)
                .isEqualTo(previousReportTimeMillis);
    }

    @Test
    public void tooFrequentErrorReportIsSkipped() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CONTENT_PROFILES_ERRORS_METRICS);
        // Set the last report time to the current time.
        long lastReportTimeMillisToSet = SystemClock.uptimeMillis();
        ContentProfileErrorReportUtils.sLastReportTime = lastReportTimeMillisToSet;

        assertThat(ContentProfileErrorReportUtils.report(0, 0, 0, 0)).isFalse();
        // The last report time should not be changed.
        assertThat(ContentProfileErrorReportUtils.sLastReportTime)
                .isEqualTo(lastReportTimeMillisToSet);
    }

    @Test
    public void successfulReport() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CONTENT_PROFILES_ERRORS_METRICS);
        // Set the last report time to much earlier than the current time.
        long lastReportTimeMillisToSet =
                SystemClock.uptimeMillis()
                        - (ContentProfileErrorReportUtils
                                        .MIN_PERIOD_BETWEEN_TWO_ERROR_REPORTS_MILLIS
                                * 2);
        ContentProfileErrorReportUtils.sLastReportTime = lastReportTimeMillisToSet;

        assertThat(ContentProfileErrorReportUtils.report(0, 0, 0, 0)).isTrue();
        // After the successful report, the last report time should be changed.
        assertThat(ContentProfileErrorReportUtils.sLastReportTime)
                .isGreaterThan(lastReportTimeMillisToSet);
    }
}
