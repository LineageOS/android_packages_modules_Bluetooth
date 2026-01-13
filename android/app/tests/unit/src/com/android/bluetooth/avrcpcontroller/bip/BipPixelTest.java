/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.bluetooth.avrcpcontroller;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link BipPixel}. */
@RunWith(AndroidJUnit4.class)
public class BipPixelTest {

    private static void testParse(
            String input,
            int pixelType,
            int minWidth,
            int minHeight,
            int maxWidth,
            int maxHeight,
            String pixelStr) {
        BipPixel pixel = new BipPixel(input);
        assertThat(pixel.getType()).isEqualTo(pixelType);
        assertThat(pixel.getMinWidth()).isEqualTo(minWidth);
        assertThat(pixel.getMinHeight()).isEqualTo(minHeight);
        assertThat(pixel.getMaxWidth()).isEqualTo(maxWidth);
        assertThat(pixel.getMaxHeight()).isEqualTo(maxHeight);
        assertThat(pixel.toString()).isEqualTo(pixelStr);
    }

    private static void testFixed(int width, int height, String pixelStr) {
        BipPixel pixel = BipPixel.createFixed(width, height);
        assertThat(pixel.getType()).isEqualTo(BipPixel.TYPE_FIXED);
        assertThat(pixel.getMinWidth()).isEqualTo(width);
        assertThat(pixel.getMinHeight()).isEqualTo(height);
        assertThat(pixel.getMaxWidth()).isEqualTo(width);
        assertThat(pixel.getMaxHeight()).isEqualTo(height);
        assertThat(pixel.toString()).isEqualTo(pixelStr);
    }

    private static void testResizableModified(
            int minWidth, int minHeight, int maxWidth, int maxHeight, String pixelStr) {
        BipPixel pixel = BipPixel.createResizableModified(minWidth, minHeight, maxWidth, maxHeight);
        assertThat(pixel.getType()).isEqualTo(BipPixel.TYPE_RESIZE_MODIFIED_ASPECT_RATIO);
        assertThat(pixel.getMinWidth()).isEqualTo(minWidth);
        assertThat(pixel.getMinHeight()).isEqualTo(minHeight);
        assertThat(pixel.getMaxWidth()).isEqualTo(maxWidth);
        assertThat(pixel.getMaxHeight()).isEqualTo(maxHeight);
        assertThat(pixel.toString()).isEqualTo(pixelStr);
    }

    private static void testResizableFixed(
            int minWidth, int maxWidth, int maxHeight, String pixelStr) {
        int minHeight = (minWidth * maxHeight) / maxWidth; // spec defined
        BipPixel pixel = BipPixel.createResizableFixed(minWidth, maxWidth, maxHeight);
        assertThat(pixel.getType()).isEqualTo(BipPixel.TYPE_RESIZE_FIXED_ASPECT_RATIO);
        assertThat(pixel.getMinWidth()).isEqualTo(minWidth);
        assertThat(pixel.getMinHeight()).isEqualTo(minHeight);
        assertThat(pixel.getMaxWidth()).isEqualTo(maxWidth);
        assertThat(pixel.getMaxHeight()).isEqualTo(maxHeight);
        assertThat(pixel.toString()).isEqualTo(pixelStr);
    }

    @Test
    public void testParseFixed() {
        testParse("0*0", BipPixel.TYPE_FIXED, 0, 0, 0, 0, "0*0");
        testParse("200*200", BipPixel.TYPE_FIXED, 200, 200, 200, 200, "200*200");
        testParse("12*67", BipPixel.TYPE_FIXED, 12, 67, 12, 67, "12*67");
        testParse("1000*1000", BipPixel.TYPE_FIXED, 1000, 1000, 1000, 1000, "1000*1000");
    }

    @Test
    public void testParseResizableModified() {
        testParse(
                "0*0-200*200",
                BipPixel.TYPE_RESIZE_MODIFIED_ASPECT_RATIO,
                0,
                0,
                200,
                200,
                "0*0-200*200");
        testParse(
                "200*200-600*600",
                BipPixel.TYPE_RESIZE_MODIFIED_ASPECT_RATIO,
                200,
                200,
                600,
                600,
                "200*200-600*600");
        testParse(
                "12*67-34*89",
                BipPixel.TYPE_RESIZE_MODIFIED_ASPECT_RATIO,
                12,
                67,
                34,
                89,
                "12*67-34*89");
        testParse(
                "123*456-1000*1000",
                BipPixel.TYPE_RESIZE_MODIFIED_ASPECT_RATIO,
                123,
                456,
                1000,
                1000,
                "123*456-1000*1000");
    }

    @Test
    public void testParseResizableFixed() {
        testParse(
                "0**-200*200",
                BipPixel.TYPE_RESIZE_FIXED_ASPECT_RATIO,
                0,
                0,
                200,
                200,
                "0**-200*200");
        testParse(
                "200**-600*600",
                BipPixel.TYPE_RESIZE_FIXED_ASPECT_RATIO,
                200,
                200,
                600,
                600,
                "200**-600*600");
        testParse(
                "123**-1000*1000",
                BipPixel.TYPE_RESIZE_FIXED_ASPECT_RATIO,
                123,
                123,
                1000,
                1000,
                "123**-1000*1000");
        testParse(
                "12**-35*89",
                BipPixel.TYPE_RESIZE_FIXED_ASPECT_RATIO,
                12,
                (12 * 89) / 35,
                35,
                89,
                "12**-35*89");
    }

    @Test
    public void testParseDimensionAtMax() {
        testParse("65535*65535", BipPixel.TYPE_FIXED, 65535, 65535, 65535, 65535, "65535*65535");
    }

    @Test
    public void testCreateFixed() {
        testFixed(0, 0, "0*0");
        testFixed(200, 200, "200*200");
        testFixed(17, 43, "17*43");
        testFixed(20000, 23456, "20000*23456");
    }

    @Test
    public void testCreateResizableModified() {
        testResizableModified(0, 0, 200, 200, "0*0-200*200");
        testResizableModified(200, 200, 600, 600, "200*200-600*600");
        testResizableModified(12, 67, 34, 89, "12*67-34*89");
        testResizableModified(123, 456, 1000, 1000, "123*456-1000*1000");
    }

    @Test
    public void testCreateResizableFixed() {
        testResizableFixed(0, 200, 200, "0**-200*200");
        testResizableFixed(200, 600, 600, "200**-600*600");
        testResizableFixed(123, 1000, 1000, "123**-1000*1000");
        testResizableFixed(12, 35, 89, "12**-35*89");
    }

    @Test
    public void testCreateDimensionsAtMax() {
        testFixed(65535, 65535, "65535*65535");
    }

    @Test(expected = ParseException.class)
    public void testParseNull_throwsException() {
        testParse(null, BipPixel.TYPE_ERROR, -1, -1, -1, -1, null);
    }

    @Test(expected = ParseException.class)
    public void testParseTooLong_throwsException() {
        testParse("000000000000000000000000", BipPixel.TYPE_ERROR, -1, -1, -1, -1, null);
    }
}
