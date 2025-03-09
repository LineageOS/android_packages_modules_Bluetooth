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

import android.annotation.SuppressLint;
import android.util.Log;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;

/**
 * Represents the set of possible transformations available for a variant of an image to get the
 * image to a particular pixel size.
 *
 * <p>The transformations supported by BIP v1.2.1 include: - Stretch - Fill - Crop
 *
 * <p>Example in an image properties/format: <variant encoding=“GIF” pixel=“80*60-640*480”
 * transformation="stretch fill"/> <variant encoding=“GIF” pixel=“80*60-640*480”
 * transformation="fill"/> <variant encoding=“GIF” pixel=“80*60-640*480” transformation="stretch
 * fill crop"/>
 *
 * <p>Example in an image descriptor: <image-descriptor version=“1.0”> <image encoding=“JPEG”
 * pixel=“1280*960” size=“500000” transformation="stretch"/> </image-descriptor>
 */
public class BipTransformation {
    private static final String TAG =
            AvrcpControllerUtils.TAG_PREFIX_AVRCP_CONTROLLER
                    + BipTransformation.class.getSimpleName();

    public static final int UNKNOWN = -1;
    public static final int STRETCH = 0;
    public static final int FILL = 1;
    public static final int CROP = 2;

    public final HashSet<Integer> mSupportedTransformations = new HashSet<Integer>(3);

    /** Create an empty set of BIP Transformations */
    public BipTransformation() {}

    /** Create a set of BIP Transformations from an attribute value from an Image Format string */
    public BipTransformation(String transformations) {
        if (transformations == null) return;

        transformations = transformations.trim().toLowerCase(Locale.ROOT);
        String[] tokens = transformations.split(" ");
        for (String token : tokens) {
            switch (token) {
                case "stretch":
                    addTransformation(STRETCH);
                    break;
                case "fill":
                    addTransformation(FILL);
                    break;
                case "crop":
                    addTransformation(CROP);
                    break;
                default:
                    Log.e(TAG, "Found unknown transformation '" + token + "'");
                    break;
            }
        }
    }

    /** Create a set of BIP Transformations from a single supported transformation */
    public BipTransformation(int transformation) {
        addTransformation(transformation);
    }

    /** Create a set of BIP Transformations from a set of supported transformations */
    public BipTransformation(int[] transformations) {
        for (int transformation : transformations) {
            addTransformation(transformation);
        }
    }

    /**
     * Add a supported Transformation
     *
     * @param transformation - The transformation you with to support
     */
    public void addTransformation(int transformation) {
        if (!isValid(transformation)) {
            throw new IllegalArgumentException(
                    "Invalid transformation ID '" + transformation + "'");
        }
        mSupportedTransformations.add(transformation);
    }

    /**
     * Remove a supported Transformation
     *
     * @param transformation - The transformation you with to remove support for
     */
    public void removeTransformation(int transformation) {
        if (!isValid(transformation)) {
            throw new IllegalArgumentException(
                    "Invalid transformation ID '" + transformation + "'");
        }
        mSupportedTransformations.remove(transformation);
    }

    /**
     * Determine if a given transformations is valid
     *
     * @param transformation The integer encoding ID of the transformation. Should be one of the
     *     BipTransformation.* constants, but doesn't *have* to be
     * @return True if the transformation constant is valid, False otherwise
     */
    private static boolean isValid(int transformation) {
        return transformation >= STRETCH && transformation <= CROP;
    }

    /**
     * Determine if this set of transformations supports a desired transformation
     *
     * @param transformation The ID of the desired transformation, STRETCH, FILL, or CROP
     * @return True if this set supports the transformation, False otherwise
     */
    public boolean isSupported(int transformation) {
        return mSupportedTransformations.contains(transformation);
    }

    /**
     * Determine if this object supports any transformations at all
     *
     * @return True if any valid transformations are supported, False otherwise
     */
    public boolean supportsAny() {
        return !mSupportedTransformations.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o == null && !supportsAny()) {
            return true;
        }
        if (!(o instanceof BipTransformation t)) {
            return false;
        }

        return mSupportedTransformations.equals(t.mSupportedTransformations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSupportedTransformations);
    }

    @Override
    @SuppressLint("ToStringReturnsNull")
    public String toString() {
        if (!supportsAny()) return null;
        StringBuilder transformations = new StringBuilder();
        if (isSupported(STRETCH)) {
            transformations.append("stretch ");
        }
        if (isSupported(FILL)) {
            transformations.append("fill ");
        }
        if (isSupported(CROP)) {
            transformations.append("crop ");
        }
        return transformations.toString().trim();
    }
}
