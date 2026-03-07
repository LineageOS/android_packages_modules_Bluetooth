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
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [Message]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MessageTest {
    @Test
    fun constructor() {
        val attrs = HashMap<String, String>()

        val handle = "FFAB"
        attrs["handle"] = handle

        val subject = "test_subject"
        attrs["subject"] = subject

        val dateTime = "20221220T165048"
        attrs["datetime"] = dateTime

        val senderName = "test_sender_name"
        attrs["sender_name"] = senderName

        val senderAddr = "test_sender_addressing"
        attrs["sender_addressing"] = senderAddr

        val replytoAddr = "test_replyto_addressing"
        attrs["replyto_addressing"] = replytoAddr

        val recipientName = "test_recipient_name"
        attrs["recipient_name"] = recipientName

        val recipientAddr = "test_recipient_addressing"
        attrs["recipient_addressing"] = recipientAddr

        val type = "MMS"
        attrs["type"] = type

        val size = 23
        attrs["size"] = size.toString()

        val text = "yes"
        attrs["text"] = text

        val receptionStatus = "notification"
        attrs["reception_status"] = receptionStatus

        val attachmentSize = 15
        attrs["attachment_size"] = attachmentSize.toString()

        val isPriority = "yes"
        attrs["priority"] = isPriority

        val isRead = "yes"
        attrs["read"] = isRead

        val isSent = "yes"
        attrs["sent"] = isSent

        val isProtected = "yes"
        attrs["protected"] = isProtected

        val msg = Message(attrs)

        assertThat(msg.handle).isEqualTo(handle)
        assertThat(msg.subject).isEqualTo(subject)
        // TODO: Compare the Date class properly.
        // assertThat(msg.dateTime).isEqualTo(expectedTime);
        assertThat(msg.dateTime).isNotNull()
        assertThat(msg.senderName).isEqualTo(senderName)
        assertThat(msg.senderAddressing).isEqualTo(senderAddr)
        assertThat(msg.replytoAddressing).isEqualTo(replytoAddr)
        assertThat(msg.recipientName).isEqualTo(recipientName)
        assertThat(msg.recipientAddressing).isEqualTo(recipientAddr)
        assertThat(msg.type).isEqualTo(Message.Type.MMS)
        assertThat(msg.size).isEqualTo(size)
        assertThat(msg.receptionStatus).isEqualTo(Message.ReceptionStatus.NOTIFICATION)
        assertThat(msg.attachmentSize).isEqualTo(attachmentSize)
        assertThat(msg.isText).isTrue()
        assertThat(msg.isPriority).isTrue()
        assertThat(msg.isRead).isTrue()
        assertThat(msg.isSent).isTrue()
        assertThat(msg.isProtected).isTrue()
        assertThat(msg.toString()).isNotEmpty()
    }
}
