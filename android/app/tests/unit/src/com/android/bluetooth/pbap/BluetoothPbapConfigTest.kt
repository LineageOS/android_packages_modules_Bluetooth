/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.pbap;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import android.content.Context;
import android.content.res.Resources;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.bluetooth.R;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link BluetoothPbapConfig}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothPbapConfigTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock Context mContext;

    @Mock Resources mResources;

    @Before
    public void setUp() throws Exception {
        doReturn(mResources).when(mContext).getResources();
    }

    @Test
    public void testInit_whenUseProfileForOwnerVcardIsTrue() {
        doReturn(true).when(mResources).getBoolean(R.bool.pbap_use_profile_for_owner_vcard);

        BluetoothPbapConfig.init(mContext);
        assertThat(BluetoothPbapConfig.useProfileForOwnerVcard()).isTrue();
    }

    @Test
    public void testInit_whenUseProfileForOwnerVcardIsFalse() {
        doReturn(false).when(mResources).getBoolean(R.bool.pbap_use_profile_for_owner_vcard);

        BluetoothPbapConfig.init(mContext);
        assertThat(BluetoothPbapConfig.useProfileForOwnerVcard()).isFalse();
    }

    @Test
    public void testInit_whenUseProfileForOwnerVcardThrowsException() {
        doThrow(new RuntimeException())
                .when(mResources)
                .getBoolean(R.bool.pbap_use_profile_for_owner_vcard);

        BluetoothPbapConfig.init(mContext);
        // Test should not crash
    }

    @Test
    public void testInit_whenIncludePhotosInVcardIsTrue() {
        doReturn(true).when(mResources).getBoolean(R.bool.pbap_include_photos_in_vcard);

        BluetoothPbapConfig.init(mContext);
        assertThat(BluetoothPbapConfig.includePhotosInVcard()).isTrue();
    }

    @Test
    public void testInit_whenIncludePhotosInVcardIsFalse() {
        doReturn(false).when(mResources).getBoolean(R.bool.pbap_include_photos_in_vcard);

        BluetoothPbapConfig.init(mContext);
        assertThat(BluetoothPbapConfig.includePhotosInVcard()).isFalse();
    }

    @Test
    public void testInit_whenIncludePhotosInVcardThrowsException() {
        doThrow(new RuntimeException())
                .when(mResources)
                .getBoolean(R.bool.pbap_include_photos_in_vcard);

        BluetoothPbapConfig.init(mContext);
        // Test should not crash
    }
}
