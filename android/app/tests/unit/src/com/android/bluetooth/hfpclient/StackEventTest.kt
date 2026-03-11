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

package com.android.bluetooth.hfpclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [StackEvent]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class StackEventTest {

    @Test
    fun toString_returnsInfo() {
        val type = StackEvent.EVENT_TYPE_RING_INDICATION

        val event = StackEvent(type)
        val expectedString =
            "StackEvent {type:${StackEvent.eventTypeToString(type)}" +
                ", value1:${event.valueInt}" +
                ", value2:${event.valueInt2}" +
                ", value3:${event.valueInt3}" +
                ", value4:${event.valueInt4}" +
                ", string: \"${event.valueString}\"" +
                ", device:${event.device}}"

        assertThat(event.toString()).isEqualTo(expectedString)
    }

    @Test
    fun toString_allEventFields_toStringMatchesName() {
        val stackEventClass = StackEvent::class.java
        for (field in stackEventClass.fields) {
            val type = field.type
            val fieldName = field.name
            if (fieldName.startsWith("EVENT_TYPE")) {
                if (type == Int::class.javaPrimitiveType) {
                    val stackEventType = field.getInt(null)
                    if (fieldName == "EVENT_TYPE_UNKNOWN_EVENT") {
                        assertThat(StackEvent.eventTypeToString(stackEventType))
                            .isEqualTo("EVENT_TYPE_UNKNOWN:$stackEventType")
                    } else {
                        val eventTypeToString = StackEvent.eventTypeToString(stackEventType)
                        assertThat(eventTypeToString).isEqualTo(fieldName)
                    }
                }
            }
        }
    }
}
