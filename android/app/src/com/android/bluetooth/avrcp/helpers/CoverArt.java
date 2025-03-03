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

import android.graphics.Bitmap;
import android.util.Log;

import com.android.bluetooth.audio_util.Image;
import com.android.bluetooth.avrcpcontroller.BipEncoding;
import com.android.bluetooth.avrcpcontroller.BipImageDescriptor;
import com.android.bluetooth.avrcpcontroller.BipImageFormat;
import com.android.bluetooth.avrcpcontroller.BipImageProperties;
import com.android.bluetooth.avrcpcontroller.BipPixel;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * An object to represent a piece of cover artwork/
 *
 * <p>This object abstracts away the actual storage method and provides a means for others to
 * understand available formats and get the underlying image in a particular format.
 *
 * <p>All return values are ready to use by a BIP server.
 */
public class CoverArt {
    private static final String TAG = CoverArt.class.getSimpleName();

    // The size in pixels of the thumbnail sides.
    private static final int THUMBNAIL_SIZE = 200;

    private static final BipPixel PIXEL_THUMBNAIL =
            BipPixel.createFixed(THUMBNAIL_SIZE, THUMBNAIL_SIZE);

    private String mImageHandle = null;
    private Bitmap mImage = null;

    /** Create a CoverArt object from an audio_util Image abstraction */
    CoverArt(Image image) {
        // Create a scaled version of the image for now, as consumers don't need
        // anything larger than this at the moment. Also makes each image gathered
        // the same dimensions for hashing purposes.
        mImage = Bitmap.createScaledBitmap(image.getImage(), THUMBNAIL_SIZE, THUMBNAIL_SIZE, false);
    }

    /**
     * Get the image handle that has been associated with this image.
     *
     * <p>If this returns null then you will fail to generate image properties
     */
    public String getImageHandle() {
        return mImageHandle;
    }

    /**
     * Set the image handle that has been associated with this image.
     *
     * <p>This is required to generate image properties
     */
    public void setImageHandle(String handle) {
        mImageHandle = handle;
    }

    /** Covert a Bitmap to a byte array with an image format without lossy compression */
    private static byte[] toByteArray(Bitmap bitmap) {
        if (bitmap == null) return null;
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream(bitmap.getWidth() * bitmap.getHeight());
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer);
        return buffer.toByteArray();
    }

    /** Get a hash code of this CoverArt image */
    public String getImageHash() {
        byte[] image = toByteArray(mImage);
        if (image == null) return null;
        String hash = null;
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(/* Bitmap to input stream */ image);
            byte[] messageDigest = digest.digest();

            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < messageDigest.length; i++) {
                hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
            }
            hash = hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Failed to hash bitmap", e);
        }
        return hash;
    }

    /** Get the cover artwork image bytes in the native format */
    public byte[] getImage() {
        debug("GetImage(native)");
        if (mImage == null) return null;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mImage.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        return outputStream.toByteArray();
    }

    /** Get the cover artwork image bytes in the given encoding and pixel size */
    public byte[] getImage(BipImageDescriptor descriptor) {
        debug("GetImage(descriptor=" + descriptor);
        if (mImage == null) return null;
        if (descriptor == null) return getImage();
        if (!isDescriptorValid(descriptor)) {
            error("Given format isn't available for this image");
            return null;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mImage.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        return outputStream.toByteArray();
    }

    /** Determine if a given image descriptor is valid */
    private static boolean isDescriptorValid(BipImageDescriptor descriptor) {
        debug("isDescriptorValid(descriptor=" + descriptor + ")");
        if (descriptor == null) return false;

        BipEncoding encoding = descriptor.getEncoding();
        BipPixel pixel = descriptor.getPixel();

        int encodingType = encoding.getType();
        if ((encodingType == BipEncoding.JPEG || encodingType == BipEncoding.PNG)
                && PIXEL_THUMBNAIL.equals(pixel)) {
            return true;
        }
        return false;
    }

    /** Get the cover artwork image bytes as a THUMBNAIL_SIZE x THUMBNAIL_SIZE JPEG thumbnail */
    public byte[] getThumbnail() {
        debug("GetImageThumbnail()");
        if (mImage == null) return null;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mImage.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        return outputStream.toByteArray();
    }

    /** Get the set of image properties that the cover artwork can be turned into */
    public BipImageProperties getImageProperties() {
        debug("GetImageProperties()");
        if (mImage == null) {
            error("Can't associate properties with a null image");
            return null;
        }
        if (mImageHandle == null) {
            error("No handle has been associated with this image. Cannot build properties.");
            return null;
        }
        BipImageProperties.Builder builder = new BipImageProperties.Builder();

        BipEncoding jpgEncoding = new BipEncoding(BipEncoding.JPEG);
        BipEncoding pngEncoding = new BipEncoding(BipEncoding.PNG);
        BipPixel jpgPixel = BipPixel.createFixed(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        BipPixel pngPixel = BipPixel.createFixed(THUMBNAIL_SIZE, THUMBNAIL_SIZE);

        BipImageFormat jpgNativeFormat = BipImageFormat.createNative(jpgEncoding, jpgPixel, -1);
        BipImageFormat pngVariantFormat =
                BipImageFormat.createVariant(pngEncoding, pngPixel, THUMBNAIL_SIZE, null);

        builder.setImageHandle(mImageHandle);
        builder.addNativeFormat(jpgNativeFormat);
        builder.addVariantFormat(pngVariantFormat);

        BipImageProperties properties = builder.build();
        return properties;
    }

    /** Get the storage size of this image in bytes */
    public int size() {
        return mImage != null ? mImage.getAllocationByteCount() : 0;
    }

    @Override
    public String toString() {
        return "{handle=" + mImageHandle + ", size=" + size() + " }";
    }

    /** Print a message to DEBUG if debug output is enabled */
    private static void debug(String msg) {
        Log.d(TAG, msg);
    }

    /** Print a message to ERROR */
    private static void error(String msg) {
        Log.e(TAG, msg);
    }
}
