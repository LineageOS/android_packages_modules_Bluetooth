/*
 * Copyright 2019 The Android Open Source Project
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

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

/** A test suite for the BipImageFormat class */
@RunWith(AndroidJUnit4.class)
public class BipImageFormatTest {
    @Test
    public void testParseNative_requiredOnly() {
        String expected = "<native encoding=\"JPEG\" pixel=\"1280*1024\" />";
        BipImageFormat format = BipImageFormat.parseNative("JPEG", "1280*1024", null);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_NATIVE);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(new BipTransformation());
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testParseNative_withSize() {
        String expected = "<native encoding=\"JPEG\" pixel=\"1280*1024\" size=\"1048576\" />";
        BipImageFormat format = BipImageFormat.parseNative("JPEG", "1280*1024", "1048576");
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_NATIVE);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(new BipTransformation());
        assertThat(format.getSize()).isEqualTo(1048576);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testParseVariant_requiredOnly() {
        String expected = "<variant encoding=\"JPEG\" pixel=\"1280*1024\" />";
        BipImageFormat format = BipImageFormat.parseVariant("JPEG", "1280*1024", null, null);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(new BipTransformation());
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testParseVariant_withMaxSize() {
        String expected = "<variant encoding=\"JPEG\" pixel=\"1280*1024\" maxsize=\"1048576\" />";
        BipImageFormat format = BipImageFormat.parseVariant("JPEG", "1280*1024", "1048576", null);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(new BipTransformation());
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(1048576);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testParseVariant_withTransformation() {
        String expected =
                "<variant encoding=\"JPEG\" pixel=\"1280*1024\""
                        + " transformation=\"stretch fill crop\" />";
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.STRETCH);
        trans.addTransformation(BipTransformation.FILL);
        trans.addTransformation(BipTransformation.CROP);

        BipImageFormat format =
                BipImageFormat.parseVariant("JPEG", "1280*1024", null, "stretch fill crop");
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.toString()).isEqualTo(expected);

        format = BipImageFormat.parseVariant("JPEG", "1280*1024", null, "stretch crop fill");
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.toString()).isEqualTo(expected);

        format = BipImageFormat.parseVariant("JPEG", "1280*1024", null, "crop stretch fill");
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testParseVariant_allFields() {
        String expected =
                "<variant encoding=\"JPEG\" pixel=\"1280*1024\""
                        + " transformation=\"stretch fill crop\" maxsize=\"1048576\" />";
        BipTransformation trans = new BipTransformation();
        trans.addTransformation(BipTransformation.STRETCH);
        trans.addTransformation(BipTransformation.FILL);
        trans.addTransformation(BipTransformation.CROP);

        BipImageFormat format =
                BipImageFormat.parseVariant("JPEG", "1280*1024", "1048576", "stretch fill crop");
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(1048576);
        assertThat(format.toString()).isEqualTo(expected);

        format = BipImageFormat.parseVariant("JPEG", "1280*1024", "1048576", "stretch crop fill");
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(1048576);
        assertThat(format.toString()).isEqualTo(expected);

        format = BipImageFormat.parseVariant("JPEG", "1280*1024", "1048576", "crop stretch fill");
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(1048576);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testCreateNative_requiredOnly() {
        String expected = "<native encoding=\"JPEG\" pixel=\"1280*1024\" />";
        BipImageFormat format =
                BipImageFormat.createNative(
                        new BipEncoding(BipEncoding.JPEG, null),
                        BipPixel.createFixed(1280, 1024),
                        -1);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_NATIVE);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isNull();
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testCreateNative_withSize() {
        String expected = "<native encoding=\"JPEG\" pixel=\"1280*1024\" size=\"1048576\" />";
        BipImageFormat format =
                BipImageFormat.createNative(
                        new BipEncoding(BipEncoding.JPEG, null),
                        BipPixel.createFixed(1280, 1024),
                        1048576);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_NATIVE);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(1280, 1024));
        assertThat(format.getTransformation()).isNull();
        assertThat(format.getSize()).isEqualTo(1048576);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testCreateVariant_requiredOnly() {
        String expected = "<variant encoding=\"JPEG\" pixel=\"32*32\" />";
        BipImageFormat format =
                BipImageFormat.createVariant(
                        new BipEncoding(BipEncoding.JPEG, null),
                        BipPixel.createFixed(32, 32),
                        -1,
                        null);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(32, 32));
        assertThat(format.getTransformation()).isNull();
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testCreateVariant_withTransformations() {
        BipTransformation trans =
                new BipTransformation(
                        new int[] {
                            BipTransformation.STRETCH,
                            BipTransformation.CROP,
                            BipTransformation.FILL
                        });
        String expected =
                "<variant encoding=\"JPEG\" pixel=\"32*32\" "
                        + "transformation=\"stretch fill crop\" />";
        BipImageFormat format =
                BipImageFormat.createVariant(
                        new BipEncoding(BipEncoding.JPEG, null),
                        BipPixel.createFixed(32, 32),
                        -1,
                        trans);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(32, 32));
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(-1);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testCreateVariant_withMaxsize() {
        String expected = "<variant encoding=\"JPEG\" pixel=\"32*32\" maxsize=\"123\" />";
        BipImageFormat format =
                BipImageFormat.createVariant(
                        new BipEncoding(BipEncoding.JPEG, null),
                        BipPixel.createFixed(32, 32),
                        123,
                        null);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(32, 32));
        assertThat(format.getTransformation()).isNull();
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(123);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test
    public void testCreateVariant_allFields() {
        BipTransformation trans =
                new BipTransformation(
                        new int[] {
                            BipTransformation.STRETCH,
                            BipTransformation.CROP,
                            BipTransformation.FILL
                        });
        String expected =
                "<variant encoding=\"JPEG\" pixel=\"32*32\" "
                        + "transformation=\"stretch fill crop\" maxsize=\"123\" />";
        BipImageFormat format =
                BipImageFormat.createVariant(
                        new BipEncoding(BipEncoding.JPEG, null),
                        BipPixel.createFixed(32, 32),
                        123,
                        trans);
        assertThat(format.getType()).isEqualTo(BipImageFormat.FORMAT_VARIANT);
        assertThat(format.getEncoding()).isEqualTo(new BipEncoding(BipEncoding.JPEG, null));
        assertThat(format.getPixel()).isEqualTo(BipPixel.createFixed(32, 32));
        assertThat(format.getTransformation()).isEqualTo(trans);
        assertThat(format.getSize()).isEqualTo(-1);
        assertThat(format.getMaxSize()).isEqualTo(123);
        assertThat(format.toString()).isEqualTo(expected);
    }

    @Test(expected = ParseException.class)
    public void testParseNative_noEncoding() {
        BipImageFormat.parseNative(null, "1024*960", "1048576");
    }

    @Test(expected = ParseException.class)
    public void testParseNative_emptyEncoding() {
        BipImageFormat.parseNative("", "1024*960", "1048576");
    }

    @Test(expected = ParseException.class)
    public void testParseNative_badEncoding() {
        BipImageFormat.parseNative("JIF", "1024*960", "1048576");
    }

    @Test(expected = ParseException.class)
    public void testParseNative_noPixel() {
        BipImageFormat.parseNative("JPEG", null, "1048576");
    }

    @Test(expected = ParseException.class)
    public void testParseNative_emptyPixel() {
        BipImageFormat.parseNative("JPEG", "", "1048576");
    }

    @Test(expected = ParseException.class)
    public void testParseNative_badPixel() {
        BipImageFormat.parseNative("JPEG", "abc*123", "1048576");
    }

    @Test(expected = NullPointerException.class)
    public void testCreateFormat_noEncoding() {
        BipImageFormat.createNative(null, BipPixel.createFixed(1280, 1024), -1);
    }

    @Test(expected = NullPointerException.class)
    public void testCreateFormat_noPixel() {
        BipImageFormat.createNative(new BipEncoding(BipEncoding.JPEG, null), null, -1);
    }

    @Test
    public void testEquals() {
        BipEncoding encoding = new BipEncoding(BipEncoding.JPEG, null);
        BipPixel pixel = BipPixel.createFixed(1280, 1024);

        BipImageFormat format = BipImageFormat.createNative(encoding, pixel, -1);
        BipImageFormat formatEqual = BipImageFormat.createNative(encoding, pixel, -1);

        String notFormat = "notFormat";

        new EqualsTester()
                .addEqualityGroup(format, format, formatEqual)
                .addEqualityGroup(notFormat)
                .testEquals();
    }
}
