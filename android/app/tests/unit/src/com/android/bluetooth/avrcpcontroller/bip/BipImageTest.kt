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

package com.android.bluetooth.avrcpcontroller

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.TestUtils
import com.android.bluetooth.tests.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [BipImage]. */
@RunWith(AndroidJUnit4::class)
class BipImageTest {

    private val testResources = TestUtils.getTestApplicationResources()

    @Test
    fun testParseImage_200by200() {
        val imageInputStream = testResources.openRawResource(R.raw.image_200_200)
        val image = BipImage(IMAGE_HANDLE, imageInputStream)
        val expectedInputStream = testResources.openRawResource(R.raw.image_200_200)
        val bitmap = BitmapFactory.decodeStream(expectedInputStream)

        assertThat(image.imageHandle).isEqualTo(IMAGE_HANDLE)
        assertThat(bitmap.sameAs(image.image)).isTrue()
    }

    @Test
    fun testParseImage_600by600() {
        val imageInputStream = testResources.openRawResource(R.raw.image_600_600)
        val image = BipImage(IMAGE_HANDLE, imageInputStream)
        val expectedInputStream = testResources.openRawResource(R.raw.image_600_600)
        val bitmap = BitmapFactory.decodeStream(expectedInputStream)

        assertThat(image.imageHandle).isEqualTo(IMAGE_HANDLE)
        assertThat(bitmap.sameAs(image.image)).isTrue()
    }

    @Test
    fun testMakeFromImage_200by200() {
        val imageInputStream = testResources.openRawResource(R.raw.image_200_200)
        val bitmap = BitmapFactory.decodeStream(imageInputStream)
        val image = BipImage(IMAGE_HANDLE, bitmap)

        assertThat(image.imageHandle).isEqualTo(IMAGE_HANDLE)
        assertThat(bitmap.sameAs(image.image)).isTrue()
    }

    @Test
    fun testMakeFromImage_600by600() {
        val imageInputStream = testResources.openRawResource(R.raw.image_600_600)
        val bitmap = BitmapFactory.decodeStream(imageInputStream)
        val image = BipImage(IMAGE_HANDLE, bitmap)

        assertThat(image.imageHandle).isEqualTo(IMAGE_HANDLE)
        assertThat(bitmap.sameAs(image.image)).isTrue()
    }

    companion object {
        private const val IMAGE_HANDLE = "123456789"
    }
}
