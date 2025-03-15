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

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** A test suite for the BipTransformation class */
@RunWith(AndroidJUnit4.class)
public class BipTransformationTest {

    @Test
    public void testCreateEmpty() {
        BipTransformation trans = new BipTransformation();
        assertThat(trans.supportsAny()).isFalse();
        assertThat(trans.toString()).isNull();
    }

    @Test
    public void testAddTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("crop");

        trans.addTransformation(BipTransformation.STRETCH);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch crop");
    }

    @Test
    public void testAddExistingTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("crop");

        trans.addTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("crop");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddOnlyInvalidTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.UNKNOWN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddNewInvalidTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        trans.addTransformation(BipTransformation.UNKNOWN);
    }

    @Test
    public void testRemoveOnlyTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("crop");

        trans.removeTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.supportsAny()).isFalse();
        assertThat(trans.toString()).isNull();
    }

    @Test
    public void testRemoveOneTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        trans.addTransformation(BipTransformation.STRETCH);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch crop");

        trans.removeTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRemoveInvalidTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        trans.addTransformation(BipTransformation.STRETCH);
        trans.removeTransformation(BipTransformation.UNKNOWN);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch crop");
    }

    @Test
    public void testRemoveUnsupportedTransformation() {
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.CROP);
        trans.addTransformation(BipTransformation.STRETCH);
        trans.removeTransformation(BipTransformation.FILL);
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch crop");
    }

    @Test
    public void testParse_Stretch() {
        BipTransformation trans = new BipTransformation("stretch");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch");
    }

    @Test
    public void testParse_Crop() {
        BipTransformation trans = new BipTransformation("crop");
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("crop");
    }

    @Test
    public void testParse_Fill() {
        BipTransformation trans = new BipTransformation("Fill");
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("fill");
    }

    @Test
    public void testParse_StretchFill() {
        BipTransformation trans = new BipTransformation("stretch fill");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch fill");
    }

    @Test
    public void testParse_StretchCrop() {
        BipTransformation trans = new BipTransformation("stretch crop");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch crop");
    }

    @Test
    public void testParse_FillCrop() {
        BipTransformation trans = new BipTransformation("fill crop");
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.toString()).isEqualTo("fill crop");
    }

    @Test
    public void testParse_StretchFillCrop() {
        BipTransformation trans = new BipTransformation("stretch fill crop");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("stretch fill crop");
    }

    @Test
    public void testParse_CropFill() {
        BipTransformation trans = new BipTransformation("crop fill");
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.toString()).isEqualTo("fill crop");
    }

    @Test
    public void testParse_CropFillStretch() {
        BipTransformation trans = new BipTransformation("crop fill stretch");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("stretch fill crop");
    }

    @Test
    public void testParse_CropFillStretchWithDuplicates() {
        BipTransformation trans = new BipTransformation("stretch crop fill fill crop stretch");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("stretch fill crop");
    }

    @Test
    public void testCreate_stretch() {
        BipTransformation trans = new BipTransformation(BipTransformation.STRETCH);
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch");
    }

    @Test
    public void testCreate_fill() {
        BipTransformation trans = new BipTransformation(BipTransformation.FILL);
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("fill");
    }

    @Test
    public void testCreate_crop() {
        BipTransformation trans = new BipTransformation(BipTransformation.CROP);
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("crop");
    }

    @Test
    public void testCreate_cropArray() {
        BipTransformation trans = new BipTransformation(new int[] {BipTransformation.CROP});
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("crop");
    }

    @Test
    public void testCreate_stretchFill() {
        BipTransformation trans =
                new BipTransformation(
                        new int[] {BipTransformation.STRETCH, BipTransformation.FILL});
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch fill");
    }

    @Test
    public void testCreate_stretchFillCrop() {
        BipTransformation trans =
                new BipTransformation(
                        new int[] {
                            BipTransformation.STRETCH,
                            BipTransformation.FILL,
                            BipTransformation.CROP
                        });
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("stretch fill crop");
    }

    @Test
    public void testCreate_stretchFillCropOrderChanged() {
        BipTransformation trans =
                new BipTransformation(
                        new int[] {
                            BipTransformation.CROP,
                            BipTransformation.FILL,
                            BipTransformation.STRETCH
                        });
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("stretch fill crop");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreate_badTransformationOnly() {
        new BipTransformation(-7);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreate_badTransformation() {
        new BipTransformation(new int[] {BipTransformation.CROP, -7});
    }

    @Test
    public void testParse_badTransformationOnly() {
        BipTransformation trans = new BipTransformation("bad");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isNull();
    }

    @Test
    public void testParse_badTransformationMixedIn() {
        BipTransformation trans = new BipTransformation("crop fill bad stretch");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("stretch fill crop");
    }

    @Test
    public void testParse_badTransformationStart() {
        BipTransformation trans = new BipTransformation("bad crop fill");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isFalse();
        assertThat(trans.isSupported(BipTransformation.FILL)).isTrue();
        assertThat(trans.isSupported(BipTransformation.CROP)).isTrue();
        assertThat(trans.toString()).isEqualTo("fill crop");
    }

    @Test
    public void testParse_badTransformationEnd() {
        BipTransformation trans = new BipTransformation("stretch bad");
        assertThat(trans.isSupported(BipTransformation.STRETCH)).isTrue();
        assertThat(trans.isSupported(BipTransformation.FILL)).isFalse();
        assertThat(trans.isSupported(BipTransformation.CROP)).isFalse();
        assertThat(trans.toString()).isEqualTo("stretch");
    }
}
