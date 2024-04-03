/*
 * Copyright 2018 The Android Open Source Project
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

import java.util.List;

/** Unit test cases for {@link BluetoothCodecStatus}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothCodecStatusTest {

    @Rule public Expect expect = Expect.create();

    // Codec configs: A and B are same; C is different
    private static final BluetoothCodecConfig CONFIG_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig CONFIG_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig CONFIG_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    // Local capabilities: A and B are same; C is different
    private static final BluetoothCodecConfig LOCAL_CAPABILITY_1_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_1_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_1_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_2_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_2_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_2_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_3_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_3_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_3_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_4_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_4_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_4_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_48000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_5_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100
                            | BluetoothCodecConfig.SAMPLE_RATE_48000
                            | BluetoothCodecConfig.SAMPLE_RATE_88200
                            | BluetoothCodecConfig.SAMPLE_RATE_96000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_5_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100
                            | BluetoothCodecConfig.SAMPLE_RATE_48000
                            | BluetoothCodecConfig.SAMPLE_RATE_88200
                            | BluetoothCodecConfig.SAMPLE_RATE_96000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig LOCAL_CAPABILITY_5_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100
                            | BluetoothCodecConfig.SAMPLE_RATE_48000
                            | BluetoothCodecConfig.SAMPLE_RATE_88200
                            | BluetoothCodecConfig.SAMPLE_RATE_96000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    // Selectable capabilities: A and B are same; C is different
    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_1_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_1_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_1_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_2_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_2_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_2_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_3_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_3_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_3_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_4_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_4_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_4_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_5_A =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100
                            | BluetoothCodecConfig.SAMPLE_RATE_48000
                            | BluetoothCodecConfig.SAMPLE_RATE_88200
                            | BluetoothCodecConfig.SAMPLE_RATE_96000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_5_B =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100
                            | BluetoothCodecConfig.SAMPLE_RATE_48000
                            | BluetoothCodecConfig.SAMPLE_RATE_88200
                            | BluetoothCodecConfig.SAMPLE_RATE_96000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            | BluetoothCodecConfig.CHANNEL_MODE_MONO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final BluetoothCodecConfig SELECTABE_CAPABILITY_5_C =
            buildBluetoothCodecConfig(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                    BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                    BluetoothCodecConfig.SAMPLE_RATE_44100
                            | BluetoothCodecConfig.SAMPLE_RATE_48000
                            | BluetoothCodecConfig.SAMPLE_RATE_88200
                            | BluetoothCodecConfig.SAMPLE_RATE_96000,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_16
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                            | BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                    BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                    1000,
                    2000,
                    3000,
                    4000);

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_A =
            List.of(
                    LOCAL_CAPABILITY_1_A,
                    LOCAL_CAPABILITY_2_A,
                    LOCAL_CAPABILITY_3_A,
                    LOCAL_CAPABILITY_4_A,
                    LOCAL_CAPABILITY_5_A);

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_B =
            List.of(
                    LOCAL_CAPABILITY_1_B,
                    LOCAL_CAPABILITY_2_B,
                    LOCAL_CAPABILITY_3_B,
                    LOCAL_CAPABILITY_4_B,
                    LOCAL_CAPABILITY_5_B);

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_B_REORDERED =
            List.of(
                    LOCAL_CAPABILITY_5_B,
                    LOCAL_CAPABILITY_4_B,
                    LOCAL_CAPABILITY_2_B,
                    LOCAL_CAPABILITY_3_B,
                    LOCAL_CAPABILITY_1_B);

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_C =
            List.of(
                    LOCAL_CAPABILITY_1_C,
                    LOCAL_CAPABILITY_2_C,
                    LOCAL_CAPABILITY_3_C,
                    LOCAL_CAPABILITY_4_C,
                    LOCAL_CAPABILITY_5_C);

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_A =
            List.of(
                    SELECTABE_CAPABILITY_1_A,
                    SELECTABE_CAPABILITY_2_A,
                    SELECTABE_CAPABILITY_3_A,
                    SELECTABE_CAPABILITY_4_A,
                    SELECTABE_CAPABILITY_5_A);

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_B =
            List.of(
                    SELECTABE_CAPABILITY_1_B,
                    SELECTABE_CAPABILITY_2_B,
                    SELECTABE_CAPABILITY_3_B,
                    SELECTABE_CAPABILITY_4_B,
                    SELECTABE_CAPABILITY_5_B);

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_B_REORDERED =
            List.of(
                    SELECTABE_CAPABILITY_5_B,
                    SELECTABE_CAPABILITY_4_B,
                    SELECTABE_CAPABILITY_2_B,
                    SELECTABE_CAPABILITY_3_B,
                    SELECTABE_CAPABILITY_1_B);

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_C =
            List.of(
                    SELECTABE_CAPABILITY_1_C,
                    SELECTABE_CAPABILITY_2_C,
                    SELECTABE_CAPABILITY_3_C,
                    SELECTABE_CAPABILITY_4_C,
                    SELECTABE_CAPABILITY_5_C);

    private static final BluetoothCodecStatus BCS_A =
            new BluetoothCodecStatus(CONFIG_A, LOCAL_CAPABILITY_A, SELECTABLE_CAPABILITY_A);
    private static final BluetoothCodecStatus BCS_B =
            new BluetoothCodecStatus(CONFIG_B, LOCAL_CAPABILITY_B, SELECTABLE_CAPABILITY_B);
    private static final BluetoothCodecStatus BCS_B_REORDERED =
            new BluetoothCodecStatus(
                    CONFIG_B, LOCAL_CAPABILITY_B_REORDERED, SELECTABLE_CAPABILITY_B_REORDERED);
    private static final BluetoothCodecStatus BCS_C =
            new BluetoothCodecStatus(CONFIG_C, LOCAL_CAPABILITY_C, SELECTABLE_CAPABILITY_C);

    @Test
    public void testBluetoothCodecStatus_get_methods() {
        expect.that(BCS_A.getCodecConfig()).isEqualTo(CONFIG_A);
        expect.that(BCS_A.getCodecConfig()).isEqualTo(CONFIG_B);
        expect.that(BCS_A.getCodecConfig()).isNotEqualTo(CONFIG_C);

        expect.that(BCS_A.getCodecsLocalCapabilities()).isEqualTo(LOCAL_CAPABILITY_A);
        expect.that(BCS_A.getCodecsLocalCapabilities()).isEqualTo(LOCAL_CAPABILITY_B);
        expect.that(BCS_A.getCodecsLocalCapabilities()).isNotEqualTo(LOCAL_CAPABILITY_C);

        expect.that(BCS_A.getCodecsSelectableCapabilities()).isEqualTo(SELECTABLE_CAPABILITY_A);
        expect.that(BCS_A.getCodecsSelectableCapabilities()).isEqualTo(SELECTABLE_CAPABILITY_B);
        expect.that(BCS_A.getCodecsSelectableCapabilities()).isNotEqualTo(SELECTABLE_CAPABILITY_C);
    }

    @Test
    public void testBluetoothCodecStatus_equals() {
        expect.that(BCS_A).isEqualTo(BCS_B);
        expect.that(BCS_B).isEqualTo(BCS_A);
        expect.that(BCS_A).isEqualTo(BCS_B_REORDERED);
        expect.that(BCS_B_REORDERED).isEqualTo(BCS_A);
        expect.that(BCS_A).isNotEqualTo(BCS_C);
        expect.that(BCS_C).isNotEqualTo(BCS_A);
    }

    private static BluetoothCodecConfig buildBluetoothCodecConfig(
            int sourceCodecType,
            int codecPriority,
            int sampleRate,
            int bitsPerSample,
            int channelMode,
            long codecSpecific1,
            long codecSpecific2,
            long codecSpecific3,
            long codecSpecific4) {
        return new BluetoothCodecConfig.Builder()
                .setCodecType(sourceCodecType)
                .setCodecPriority(codecPriority)
                .setSampleRate(sampleRate)
                .setBitsPerSample(bitsPerSample)
                .setChannelMode(channelMode)
                .setCodecSpecific1(codecSpecific1)
                .setCodecSpecific2(codecSpecific2)
                .setCodecSpecific3(codecSpecific3)
                .setCodecSpecific4(codecSpecific4)
                .build();
    }
}
