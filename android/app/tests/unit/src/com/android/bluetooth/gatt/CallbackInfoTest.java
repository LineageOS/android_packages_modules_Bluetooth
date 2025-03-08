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

package com.android.bluetooth.gatt;


import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;
import com.google.protobuf.ByteString;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link CallbackInfo}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class CallbackInfoTest {

    @Rule public Expect expect = Expect.create();

    @Test
    public void callbackInfo_default() {
        String address = "TestAddress";
        int status = 0;
        int handle = 1;
        ByteString value = ByteString.copyFrom("Test Value Byte Array".getBytes());

        CallbackInfo callbackInfo = new CallbackInfo(address, status, handle, value);

        expect.that(callbackInfo.address()).isEqualTo(address);
        expect.that(callbackInfo.status()).isEqualTo(status);
        expect.that(callbackInfo.handle()).isEqualTo(handle);
        expect.that(callbackInfo.value()).isEqualTo(value);
    }

    @Test
    public void callbackInfo_nullValue() {
        String address = "TestAddress";
        int status = 0;

        CallbackInfo callbackInfo = new CallbackInfo(address, status);

        expect.that(callbackInfo.address()).isEqualTo(address);
        expect.that(callbackInfo.status()).isEqualTo(status);
        expect.that(callbackInfo.value()).isNull();
        expect.that(callbackInfo.valueByteArray()).isNull();
    }
}
