/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.bluetooth.opp;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.BluetoothMethodProxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;

public class BluetoothOppUtilityTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock Cursor mCursor;

    @Spy BluetoothMethodProxy mCallProxy = BluetoothMethodProxy.getInstance();

    private static final Uri CORRECT_FORMAT_BUT_INVALID_FILE_URI =
            Uri.parse("content://com.android.bluetooth.opp/btopp/0123455343467");

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setUp() {
        BluetoothMethodProxy.setInstanceForTesting(mCallProxy);
    }

    @After
    public void tearDown() {
        BluetoothMethodProxy.setInstanceForTesting(null);
    }

    @Test
    public void fileExists_returnFalse() {
        assertThat(
                        BluetoothOppUtility.fileExists(
                                mTargetContext, CORRECT_FORMAT_BUT_INVALID_FILE_URI))
                .isFalse();
    }
}
