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

package com.android.bluetooth.pbap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.obex.Operation
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.OutputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [HandlerForStringBuffer]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HandlerForStringBufferTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var operation: Operation
    @Mock private lateinit var outputStream: OutputStream

    @Before
    fun setUp() {
        doReturn(outputStream).whenever(operation).openOutputStream()
    }

    @Test
    fun init_withNonNullOwnerVCard_returnsTrue() {
        val ownerVcard = "testOwnerVcard"
        val buffer = HandlerForStringBuffer(operation, ownerVcard)

        assertThat(buffer.init()).isTrue()
        verify(outputStream).write(ownerVcard.toByteArray())
    }

    @Test
    fun init_withNullOwnerVCard_returnsTrue() {
        val ownerVcard: String? = null
        val buffer = HandlerForStringBuffer(operation, ownerVcard)

        assertThat(buffer.init()).isTrue()
        verify(outputStream, never()).write(any<ByteArray>())
    }

    @Test
    fun init_withIOExceptionWhenOpeningOutputStream_returnsFalse() {
        doThrow(IOException()).whenever(operation).openOutputStream()

        val ownerVcard = "testOwnerVcard"
        val buffer = HandlerForStringBuffer(operation, ownerVcard)

        assertThat(buffer.init()).isFalse()
    }

    @Test
    fun writeVCard_withNonNullOwnerVCard_returnsTrue() {
        val ownerVcard: String? = null
        val buffer = HandlerForStringBuffer(operation, ownerVcard)
        buffer.init()

        val newVcard = "newEntryVcard"
        assertThat(buffer.writeVCard(newVcard)).isTrue()
    }

    @Test
    fun writeVCard_withNullOwnerVCard_returnsFalse() {
        val ownerVcard: String? = null
        val buffer = HandlerForStringBuffer(operation, ownerVcard)
        buffer.init()

        val newVcard: String? = null
        assertThat(buffer.writeVCard(newVcard)).isFalse()
    }

    @Test
    fun writeVCard_withIOExceptionWhenWritingToStream_returnsFalse() {
        doThrow(IOException()).whenever(outputStream).write(any<ByteArray>())
        val buffer = HandlerForStringBuffer(operation, /* ownerVCard= */ null)
        buffer.init()

        val newVCard = "newVCard"
        assertThat(buffer.writeVCard(newVCard)).isFalse()
    }

    @Test
    fun terminate() {
        val ownerVcard = "testOwnerVcard"
        val buffer = HandlerForStringBuffer(operation, ownerVcard)
        buffer.init()

        buffer.terminate()
        verify(outputStream).close()
    }
}
