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

package com.android.bluetooth.le_scan

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [MsftAdvMonitorMergedPatternList]. */
@RunWith(AndroidJUnit4::class)
class MsftAdvMonitorMergedPatternListTest {
    @Test
    fun testAddAndRemove() {
        val patternList = MsftAdvMonitorMergedPatternList()
        var filterIndex = 0
        val addedFilterIndex = filterIndex

        // Ensure returned filter index is the same as passed filter index
        val monitor =
            MsftAdvMonitor(
                ScanFilter.Builder().setServiceData(SERVICE_DATA_UUID, SERVICE_DATA).build()
            )
        assertThat(patternList.add(filterIndex, monitor.patterns)).isEqualTo(filterIndex)

        // Add a different pattern and ensure returned filter index is the same as passed filter
        // index
        filterIndex++
        val anotherMonitor =
            MsftAdvMonitor(
                ScanFilter.Builder().setServiceData(ANOTHER_SERVICE_DATA_UUID, SERVICE_DATA).build()
            )
        assertThat(patternList.add(filterIndex, anotherMonitor.patterns)).isEqualTo(filterIndex)

        // Add the same first pattern with different filter index and confirm previous filter index
        // was returned
        filterIndex++
        assertThat(patternList.add(filterIndex, monitor.patterns)).isEqualTo(addedFilterIndex)

        // Only removing the last filter index should result in successful removal
        assertThat(patternList.remove(addedFilterIndex)).isFalse()
        assertThat(patternList.remove(addedFilterIndex)).isTrue()
    }

    companion object {
        private val SERVICE_DATA_UUID =
            ParcelUuid.fromString("01234567-890A-BCDE-F123-4567890ABCDE")
        private val SERVICE_DATA = byteArrayOf(0x01, 0x02, 0x03)

        private val ANOTHER_SERVICE_DATA_UUID =
            ParcelUuid.fromString("12345678-90AB-CDEF-1234-567890ABCDEF")
    }
}
