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

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

/** A test suite for the AvrcpCoverArtStorage class. */
@RunWith(AndroidJUnit4.class)
public final class AvrcpCoverArtStorageTest {
    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Resources mTestResources = TestUtils.getTestApplicationResources(mTargetContext);
    private final BluetoothDevice mDevice1 = getTestDevice(56);
    private final BluetoothDevice mDevice2 = getTestDevice(57);
    private Bitmap mImage1;
    private Bitmap mImage2;
    private AvrcpCoverArtStorage mAvrcpCoverArtStorage;

    @Before
    public void setUp() {
        InputStream is =
                mTestResources.openRawResource(com.android.bluetooth.tests.R.raw.image_200_200);
        mImage1 = BitmapFactory.decodeStream(is);
        InputStream is2 =
                mTestResources.openRawResource(com.android.bluetooth.tests.R.raw.image_600_600);
        mImage2 = BitmapFactory.decodeStream(is2);

        mAvrcpCoverArtStorage = new AvrcpCoverArtStorage(mTargetContext);
    }

    @After
    public void tearDown() {
        if (mAvrcpCoverArtStorage != null) {
            mAvrcpCoverArtStorage.removeImagesForDevice(mDevice1);
            mAvrcpCoverArtStorage.removeImagesForDevice(mDevice2);
            mAvrcpCoverArtStorage = null;
        }
        mImage1 = null;
        mImage2 = null;
    }

    private void assertImageSame(Bitmap expected, BluetoothDevice device, String handle) {
        Bitmap image = mAvrcpCoverArtStorage.getImage(device, handle);
        assertThat(expected.sameAs(image)).isTrue();
    }

    @Test
    public void addNewImage_imageExists() {
        Uri expectedUri = AvrcpCoverArtProvider.getImageUri(mDevice1, "1");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();

        Uri uri = mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);

        assertThat(uri).isEqualTo(expectedUri);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
    }

    @Test
    public void addExistingImage_imageUpdated() {
        Uri expectedUri = AvrcpCoverArtProvider.getImageUri(mDevice1, "1");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();

        Uri uri = mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(uri).isEqualTo(expectedUri);
        assertImageSame(mImage1, mDevice1, "1");

        uri = mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage2);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(uri).isEqualTo(expectedUri);
        assertImageSame(mImage2, mDevice1, "1");
    }

    @Test
    public void addTwoImageSameDevice_bothExist() {
        Uri expectedUri1 = AvrcpCoverArtProvider.getImageUri(mDevice1, "1");
        Uri expectedUri2 = AvrcpCoverArtProvider.getImageUri(mDevice1, "2");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isFalse();

        Uri uri1 = mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        Uri uri2 = mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage2);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(uri1).isEqualTo(expectedUri1);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isTrue();
        assertThat(uri2).isEqualTo(expectedUri2);
    }

    @Test
    public void addTwoImageDifferentDevices_bothExist() {
        Uri expectedUri1 = AvrcpCoverArtProvider.getImageUri(mDevice1, "1");
        Uri expectedUri2 = AvrcpCoverArtProvider.getImageUri(mDevice2, "1");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice2, "1")).isFalse();

        Uri uri1 = mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        Uri uri2 = mAvrcpCoverArtStorage.addImage(mDevice2, "1", mImage1);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(uri1).isEqualTo(expectedUri1);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(uri2).isEqualTo(expectedUri2);
    }

    @Test
    public void addNullImage_imageNotAdded() {
        Uri uri = mAvrcpCoverArtStorage.addImage(mDevice1, "1", null);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(uri).isNull();
    }

    @Test
    public void addImageNullDevice_imageNotAdded() {
        Uri uri = mAvrcpCoverArtStorage.addImage(null, "1", mImage1);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(uri).isNull();
    }

    @Test
    public void addImageNullHandle_imageNotAdded() {
        Uri uri = mAvrcpCoverArtStorage.addImage(mDevice1, null, mImage1);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(uri).isNull();
    }

    @Test
    public void addImageEmptyHandle_imageNotAdded() {
        Uri uri = mAvrcpCoverArtStorage.addImage(mDevice1, "", mImage1);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(uri).isNull();
    }

    @Test
    public void getImage_canGetImageFromStorage() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertImageSame(mImage1, mDevice1, "1");
    }

    @Test
    public void getImageSameHandleDifferentDevices_canGetImagesFromStorage() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice2, "1", mImage2);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice2, "1")).isTrue();
        assertImageSame(mImage1, mDevice1, "1");
        assertImageSame(mImage2, mDevice2, "1");
    }

    @Test
    public void getImageThatDoesntExist_returnsNull() {
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        Bitmap image = mAvrcpCoverArtStorage.getImage(mDevice1, "1");
        assertThat(image).isNull();
    }

    @Test
    public void getImageNullDevice_returnsNull() {
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        Bitmap image = mAvrcpCoverArtStorage.getImage(null, "1");
        assertThat(image).isNull();
    }

    @Test
    public void getImageNullHandle_returnsNull() {
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        Bitmap image = mAvrcpCoverArtStorage.getImage(mDevice1, null);
        assertThat(image).isNull();
    }

    @Test
    public void getImageEmptyHandle_returnsNull() {
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        Bitmap image = mAvrcpCoverArtStorage.getImage(mDevice1, "");
        assertThat(image).isNull();
    }

    @Test
    public void removeExistingImage_imageDoesntExist() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice2, "1", mImage1);
        mAvrcpCoverArtStorage.removeImage(mDevice1, "1");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isTrue();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice2, "1")).isTrue();
    }

    @Test
    public void removeNonExistentImage_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.removeImage(mDevice1, "2");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
    }

    @Test
    public void removeImageNullDevice_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.removeImage(null, "1");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
    }

    @Test
    public void removeImageNullHandle_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.removeImage(mDevice1, null);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
    }

    @Test
    public void removeImageEmptyHandle_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.removeImage(mDevice1, "");
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
    }

    @Test
    public void removeImageNullInputs_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.removeImage(null, null);
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
    }

    @Test
    public void removeAllImagesForDevice_onlyOneDeviceImagesGone() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice2, "1", mImage1);

        mAvrcpCoverArtStorage.removeImagesForDevice(mDevice1);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice2, "1")).isTrue();
    }

    @Test
    public void removeAllImagesForDeviceDne_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage1);

        mAvrcpCoverArtStorage.removeImagesForDevice(mDevice2);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isTrue();
    }

    @Test
    public void removeAllImagesForNullDevice_nothingHappens() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage1);

        mAvrcpCoverArtStorage.removeImagesForDevice(null);

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isTrue();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isTrue();
    }

    @Test
    public void clearStorageOneDevice_allImagesRemoved() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage1);

        mAvrcpCoverArtStorage.clear();

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isFalse();
    }

    @Test
    public void clearStorageManyDevices_allImagesRemoved() {
        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice1, "2", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice2, "1", mImage1);
        mAvrcpCoverArtStorage.addImage(mDevice2, "2", mImage1);

        mAvrcpCoverArtStorage.clear();

        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice1, "2")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice2, "1")).isFalse();
        assertThat(mAvrcpCoverArtStorage.doesImageExist(mDevice2, "2")).isFalse();
    }

    @Test
    public void toString_returnsDeviceInfo() {
        String expectedString =
                "CoverArtStorage:\n" + "  " + mDevice1 + " (" + 1 + "):" + "\n    " + "1" + "\n";

        mAvrcpCoverArtStorage.addImage(mDevice1, "1", mImage1);

        assertThat(mAvrcpCoverArtStorage.toString()).isEqualTo(expectedString);
    }
}
