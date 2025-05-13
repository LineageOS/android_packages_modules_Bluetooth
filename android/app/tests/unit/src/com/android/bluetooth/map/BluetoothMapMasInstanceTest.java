/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.map;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link BluetoothMapMasInstance}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothMapMasInstanceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BluetoothMapService mMapService;

    private static final int TEST_MAS_ID = 1;
    private static final boolean TEST_ENABLE_SMS_MMS = true;
    private static final String TEST_NAME = "test_name";
    private static final String TEST_PACKAGE_NAME = "test.package.name";
    private static final String TEST_ID = "1111";
    private static final String TEST_PROVIDER_AUTHORITY = "test.project.provider";
    private static final BluetoothMapUtils.TYPE TEST_TYPE = BluetoothMapUtils.TYPE.EMAIL;
    private static final String TEST_UCI = "uci";
    private static final String TEST_UCI_PREFIX = "uci_prefix";

    private BluetoothMapAccountItem mAccountItem;

    @Before
    public void setUp() {
        ColorDrawable colorDrawable = mock(ColorDrawable.class);
        mAccountItem =
                BluetoothMapAccountItem.create(
                        TEST_ID,
                        TEST_NAME,
                        TEST_PACKAGE_NAME,
                        TEST_PROVIDER_AUTHORITY,
                        colorDrawable,
                        TEST_TYPE,
                        TEST_UCI,
                        TEST_UCI_PREFIX);
    }

    @Test
    public void toString_returnsInfo() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        assertThat(manager).isNotNull();
        doReturn(manager).when(mMapService).getSystemService(BluetoothManager.class);

        BluetoothMapMasInstance instance =
                new BluetoothMapMasInstance(
                        mMapService, mAccountItem, TEST_MAS_ID, TEST_ENABLE_SMS_MMS);

        String expected =
                "MasId: "
                        + TEST_MAS_ID
                        + " Uri:"
                        + mAccountItem.mBase_uri
                        + " SMS/MMS:"
                        + TEST_ENABLE_SMS_MMS;
        assertThat(instance.toString()).isEqualTo(expected);
    }
}
