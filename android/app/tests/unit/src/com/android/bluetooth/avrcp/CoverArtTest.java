/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.audio_util.Image;
import com.android.bluetooth.avrcpcontroller.BipEncoding;
import com.android.bluetooth.avrcpcontroller.BipImageDescriptor;
import com.android.bluetooth.avrcpcontroller.BipImageFormat;
import com.android.bluetooth.avrcpcontroller.BipImageProperties;
import com.android.bluetooth.avrcpcontroller.BipPixel;
import com.android.bluetooth.tests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Test cases for {@link CoverArt}. */
@RunWith(AndroidJUnit4.class)
public class CoverArtTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final BipPixel PIXEL_THUMBNAIL = BipPixel.createFixed(200, 200);
    private static final String IMAGE_HANDLE_1 = "0000001";

    private final Resources mTestResources = TestUtils.getTestApplicationResources();

    private Bitmap m200by200Image;
    private Bitmap m200by200ImageBlue;
    private Image mImage;
    private Image mImage2;

    @Before
    public void setUp() throws Exception {
        m200by200Image = loadImage(R.raw.image_200_200);
        m200by200ImageBlue = loadImage(R.raw.image_200_200_blue);
        mImage = new Image(null, m200by200Image);
        mImage2 = new Image(null, m200by200ImageBlue);
    }

    @After
    public void tearDown() throws Exception {
        mImage2 = null;
        mImage = null;
        m200by200ImageBlue = null;
        m200by200Image = null;
    }

    private Bitmap loadImage(int resId) {
        InputStream imageInputStream = mTestResources.openRawResource(resId);
        return BitmapFactory.decodeStream(imageInputStream);
    }

    private static Bitmap toBitmap(byte[] imageBytes) {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);
        return BitmapFactory.decodeStream(inputStream);
    }

    private static BipImageDescriptor getDescriptor(int encoding, int width, int height) {
        return new BipImageDescriptor.Builder()
                .setEncoding(encoding)
                .setFixedDimensions(width, height)
                .build();
    }

    private static boolean containsThumbnailFormat(BipImageProperties properties) {
        if (properties == null) return false;

        for (BipImageFormat format : properties.getNativeFormats()) {
            BipEncoding encoding = format.getEncoding();
            BipPixel pixel = format.getPixel();
            if (encoding == null || pixel == null) continue;
            if (encoding.getType() == BipEncoding.JPEG && PIXEL_THUMBNAIL.equals(pixel)) {
                return true;
            }
        }

        for (BipImageFormat format : properties.getVariantFormats()) {
            BipEncoding encoding = format.getEncoding();
            BipPixel pixel = format.getPixel();
            if (encoding == null || pixel == null) continue;
            if (encoding.getType() == BipEncoding.JPEG && PIXEL_THUMBNAIL.equals(pixel)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isThumbnailFormat(Bitmap image) {
        if (image == null) return false;
        return (200 == image.getHeight() && 200 == image.getWidth());
    }

    /** Make sure you can create an image from an Image object */
    @Test
    public void testCreateCoverArtFromImage() {
        CoverArt artwork = new CoverArt(mImage);
        assertThat(artwork.getImage()).isNotNull();
    }

    /** Make sure you get an image hash from a valid image */
    @Test
    public void testGetImageHash() {
        CoverArt artwork = new CoverArt(mImage);
        String hash = artwork.getImageHash();
        assertThat(hash).isNotNull();
    }

    /** Make sure you get the same image hash from several calls to the same object */
    @Test
    public void testGetImageHashSameForMultipleCalls() {
        CoverArt artwork = new CoverArt(mImage);
        String hash = artwork.getImageHash();
        assertThat(hash).isNotNull();
        assertThat(artwork.getImageHash()).isEqualTo(hash); // extra call 1
        assertThat(artwork.getImageHash()).isEqualTo(hash); // extra call 2
    }

    /** Make sure you get the same image hash from separate objects created from the same image */
    @Test
    public void testGetImageHashSameForSameImages() {
        CoverArt artwork = new CoverArt(mImage);
        CoverArt artwork2 = new CoverArt(mImage);
        String hash = artwork.getImageHash();
        String hash2 = artwork2.getImageHash();

        assertThat(hash).isNotNull();
        assertThat(hash2).isNotNull();
        assertThat(hash).isEqualTo(hash2);
    }

    /**
     * Make sure you get different image hashes from separate objects created from different images
     */
    @Test
    public void testGetImageHashDifferentForDifferentImages() {
        CoverArt artwork = new CoverArt(mImage);
        CoverArt artwork2 = new CoverArt(mImage2);
        String hash = artwork.getImageHash();
        String hash2 = artwork2.getImageHash();

        assertThat(hash).isNotNull();
        assertThat(hash2).isNotNull();
        assertThat(hash).isNotEqualTo(hash2);
    }

    /** Make sure you get an image when asking for the native image */
    @Test
    public void testGetNativeImage() {
        CoverArt artwork = new CoverArt(mImage);
        byte[] image = artwork.getImage();
        assertThat(image).isNotNull();
    }

    /** Make sure you getThumbnailImage returns an image as a 200 by 200 JPEG */
    @Test
    public void testGetThumbnailImage() {
        CoverArt artwork = new CoverArt(mImage);
        byte[] imageBytes = artwork.getThumbnail();
        assertThat(imageBytes).isNotNull();
        Bitmap image = toBitmap(imageBytes);
        assertThat(isThumbnailFormat(image)).isTrue();
    }

    /** Make sure you can set the image handle associated with this object */
    @Test
    public void testGetAndSetImageHandle() {
        CoverArt artwork = new CoverArt(mImage);
        assertThat(artwork.getImageHandle()).isNull();
        artwork.setImageHandle(IMAGE_HANDLE_1);
        assertThat(artwork.getImageHandle()).isEqualTo(IMAGE_HANDLE_1);
    }

    /**
     * Make sure a getImageProperties() yields a set of image properties. The thumbnail format MUST
     * be contained in that set
     */
    @Test
    public void testGetImageProperties() {
        CoverArt artwork = new CoverArt(mImage);
        artwork.setImageHandle(IMAGE_HANDLE_1);
        BipImageProperties properties = artwork.getImageProperties();
        assertThat(properties).isNotNull();
        assertThat(containsThumbnailFormat(properties)).isTrue();
    }

    /** Make sure a getImage(<valid descriptor>) yield an image in the format you asked for */
    @Test
    public void testGetImageWithValidDescriptor() {
        CoverArt artwork = new CoverArt(mImage);
        BipImageDescriptor descriptor = getDescriptor(BipEncoding.JPEG, 200, 200);
        byte[] image = artwork.getImage(descriptor);
        assertThat(image).isNotNull();
    }

    /** Make sure a getImage(<thumbnail descriptor>) yields the image in the thumbnail format */
    @Test
    public void testGetImageWithThumbnailDescriptor() {
        CoverArt artwork = new CoverArt(mImage);
        BipImageDescriptor descriptor = getDescriptor(BipEncoding.JPEG, 200, 200);
        byte[] imageBytes = artwork.getImage(descriptor);
        assertThat(imageBytes).isNotNull();
        Bitmap image = toBitmap(imageBytes);
        assertThat(isThumbnailFormat(image)).isTrue();
    }

    /** Make sure a getImage(<invalid descriptor>) yields a null */
    @Test
    public void testGetImageWithInvalidDescriptor() {
        CoverArt artwork = new CoverArt(mImage);
        BipImageDescriptor descriptor = getDescriptor(BipEncoding.BMP, 1200, 1200);
        byte[] image = artwork.getImage(descriptor);
        assertThat(image).isNull();
    }

    /** Make sure a getImage(<null descriptor>) yields the native image */
    @Test
    public void testGetImageWithoutDescriptor() {
        CoverArt artwork = new CoverArt(mImage);
        byte[] image = artwork.getImage(null);
        byte[] nativeImage = artwork.getImage();
        assertThat(Arrays.equals(nativeImage, image)).isTrue();
    }

    /** Make sure we can get a valid string representation of the CoverArt */
    @Test
    public void testGetSize() {
        CoverArt artwork = new CoverArt(mImage);
        assertThat(artwork.size() > 0).isTrue();
    }

    /** Make sure we can get a valid string representation of the CoverArt */
    @Test
    public void testToString() {
        CoverArt artwork = new CoverArt(mImage);
        assertThat(artwork.toString()).isNotNull();
    }

    /**
     * Make sure getImage(<smaller descriptor>) yields an image when the feature flag is enabled.
     * This verifies that descriptors for sizes smaller than the thumbnail are accepted.
     */
    @Test
    public void testGetImageWithSmallerDescriptor_whenFlagEnabled() {
        CoverArt artwork = new CoverArt(mImage);
        BipImageDescriptor descriptor = getDescriptor(BipEncoding.JPEG, 100, 100);
        assertThat(artwork.getImage(descriptor)).isNotNull();
    }
}
