/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.UUID;

/** Test cases for {@link MsftAdvMonitorMergedPatternList}. */
@RunWith(JUnit4.class)
public final class MsftAdvMonitorMergedPatternListTest {
    private static final ParcelUuid SERVICE_DATA_UUID =
            new ParcelUuid(UUID.fromString("01234567-890A-BCDE-F123-4567890ABCDE"));
    private static final byte[] SERVICE_DATA = new byte[] {0x01, 0x02, 0x03};

    private static final ParcelUuid ANOTHER_SERVICE_DATA_UUID =
            new ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"));

    @Test
    public void testAddAndRemove() {
        MsftAdvMonitorMergedPatternList patternList = new MsftAdvMonitorMergedPatternList();
        int filterIndex = 0;
        int addedFilterIndex = filterIndex;

        // Ensure returned filter index is the same as passed filter index
        MsftAdvMonitor monitor =
                new MsftAdvMonitor(
                        new ScanFilter.Builder()
                                .setServiceData(SERVICE_DATA_UUID, SERVICE_DATA)
                                .build());
        assertThat(patternList.add(filterIndex, monitor.getPatterns())).isEqualTo(filterIndex);

        // Add a different pattern and ensure returned filter index is the same as passed filter
        // index
        filterIndex++;
        MsftAdvMonitor anotherMonitor =
                new MsftAdvMonitor(
                        new ScanFilter.Builder()
                                .setServiceData(ANOTHER_SERVICE_DATA_UUID, SERVICE_DATA)
                                .build());
        assertThat(patternList.add(filterIndex, anotherMonitor.getPatterns()))
                .isEqualTo(filterIndex);

        // Add the same first pattern with different filter index and confirm previous filter index
        // was returned
        filterIndex++;
        assertThat(patternList.add(filterIndex, monitor.getPatterns())).isEqualTo(addedFilterIndex);

        // Only removing the last filter index should result in successful removal
        assertThat(patternList.remove(addedFilterIndex)).isEqualTo(false);
        assertThat(patternList.remove(addedFilterIndex)).isEqualTo(true);
    }
}
