/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.bluetooth;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test cases for {@link BluetoothLeAudioCodecConfig}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothLeAudioCodecConfigTest {

    @Rule public Expect expect = Expect.create();

    private int[] mCodecTypeArray =
            new int[] {
                BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3,
                BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID,
            };

    @Test
    public void testBluetoothLeAudioCodecConfig_valid_get_methods() {
        for (int codecIdx = 0; codecIdx < mCodecTypeArray.length; codecIdx++) {
            int codecType = mCodecTypeArray[codecIdx];

            BluetoothLeAudioCodecConfig leAudioCodecConfig =
                    buildBluetoothLeAudioCodecConfig(codecType);

            if (codecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3) {
                expect.that(leAudioCodecConfig.getCodecName()).isEqualTo("LC3");
            }
            if (codecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                expect.that(leAudioCodecConfig.getCodecName()).isEqualTo("INVALID CODEC");
            }

            expect.that(leAudioCodecConfig.getCodecType()).isEqualTo(codecType);
        }
    }

    private BluetoothLeAudioCodecConfig buildBluetoothLeAudioCodecConfig(int sourceCodecType) {
        return new BluetoothLeAudioCodecConfig.Builder().setCodecType(sourceCodecType).build();
    }
}
