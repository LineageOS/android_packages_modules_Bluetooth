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

package com.android.bluetooth.mapclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [MessagesFilter]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MessagesFilterTest {
    @Test
    fun setOriginator() {
        val filter = MessagesFilter()

        val originator = "test_originator"
        filter.setOriginator(originator)
        assertThat(filter.originator).isEqualTo(originator)

        filter.setOriginator("")
        assertThat(filter.originator).isNull() // Empty string is stored as null

        filter.setOriginator(null)
        assertThat(filter.originator).isNull()
    }

    @Test
    fun setPriority() {
        val filter = MessagesFilter()

        val priority: Byte = 5
        filter.setPriority(priority)

        assertThat(filter.priority).isEqualTo(priority)
    }

    @Test
    fun setReadStatus() {
        val filter = MessagesFilter()

        val readStatus: Byte = 5
        filter.setReadStatus(readStatus)

        assertThat(filter.readStatus).isEqualTo(readStatus)
    }

    @Test
    fun setRecipient() {
        val filter = MessagesFilter()

        val recipient = "test_originator"
        filter.setRecipient(recipient)
        assertThat(filter.recipient).isEqualTo(recipient)

        filter.setRecipient("")
        assertThat(filter.recipient).isNull() // Empty string is stored as null

        filter.setRecipient(null)
        assertThat(filter.recipient).isNull()
    }

    /** Test Builder creates and sets everything correctly. */
    @Test
    fun testBuilder() {
        val originator = "test_originator"
        val recipient = "test_recipient"
        val excludedMessageTypes = MessagesFilter.MESSAGE_TYPE_EMAIL
        val readStatus = MessagesFilter.READ_STATUS_READ
        val priority = MessagesFilter.PRIORITY_HIGH
        val begin = Calendar.getInstance()
        begin.add(Calendar.DATE, -14)
        val end = Calendar.getInstance()
        end.add(Calendar.DATE, -7)

        val filter =
            MessagesFilter.Builder()
                .setOriginator(originator)
                .setRecipient(recipient)
                .setExcludedMessageTypes(excludedMessageTypes)
                .setReadStatus(readStatus)
                .setPriority(priority)
                .setPeriod(begin.getTime(), end.getTime())
                .build()

        assertThat(filter.originator).isEqualTo(originator)
        assertThat(filter.recipient).isEqualTo(recipient)
        assertThat(filter.excludedMessageTypes).isEqualTo(excludedMessageTypes)
        assertThat(filter.readStatus).isEqualTo(readStatus)
        assertThat(filter.priority).isEqualTo(priority)
        assertThat(filter.periodBegin).isEqualTo((ObexTime(begin.getTime())).toString())
        assertThat(filter.periodEnd).isEqualTo((ObexTime(end.getTime())).toString())
    }
}
