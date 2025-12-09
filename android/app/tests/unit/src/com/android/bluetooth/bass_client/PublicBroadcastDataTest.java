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

package com.android.bluetooth.bass_client;

import static android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;

import com.google.common.primitives.Bytes;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

/** Test cases for {@link PublicBroadcastData}. */
@RunWith(AndroidJUnit4.class)
public class PublicBroadcastDataTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void publicBroadcastInfo() {
        PublicBroadcastData.PublicBroadcastInfo info =
                new PublicBroadcastData.PublicBroadcastInfo();

        info.print();

        info.isEncrypted = true;
        info.audioConfigQuality =
                (BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD
                        | BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH);
        info.metaData = new byte[] {0x06, 0x07, 0x08};
        info.print();
    }

    BluetoothLeBroadcastSubgroup createBroadcastSubgroup() {
        BluetoothLeAudioCodecConfigMetadata codecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder().build();
        BluetoothLeAudioContentMetadata contentMetadata =
                new BluetoothLeAudioContentMetadata.Builder().build();
        BluetoothLeBroadcastSubgroup.Builder builder =
                new BluetoothLeBroadcastSubgroup.Builder()
                        .setCodecSpecificConfig(codecMetadata)
                        .setContentMetadata(contentMetadata);

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder().build();

        // builder expect at least one channel
        BluetoothLeBroadcastChannel channel =
                new BluetoothLeBroadcastChannel.Builder()
                        .setChannelIndex(0)
                        .setCodecMetadata(channelCodecMetadata)
                        .build();
        builder.addChannel(channel);
        return builder.build();
    }

    @Test
    public void buildPublicBroadcastData() {
        PublicBroadcastData.PublicBroadcastInfo publicBroadcastInfo =
                new PublicBroadcastData.PublicBroadcastInfo();
        publicBroadcastInfo.audioConfigQuality =
                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH;
        publicBroadcastInfo.isEncrypted = true;
        publicBroadcastInfo.metaData = new byte[] {0x02, 0x08, 0x01}; // Audio Active State = TRUE
        PublicBroadcastData publicBroadcastData = new PublicBroadcastData(publicBroadcastInfo);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setSourceDevice(getTestDevice(0), ADDRESS_TYPE_RANDOM)
                        .addSubgroup(createBroadcastSubgroup())
                        .setEncrypted(publicBroadcastData.isEncrypted())
                        .setPublicBroadcast(true)
                        .setAudioConfigQuality(publicBroadcastData.getAudioConfigQuality())
                        .setPublicBroadcastMetadata(
                                BluetoothLeAudioContentMetadata.fromRawBytes(
                                        publicBroadcastData.getMetadata()));
        BluetoothLeBroadcastMetadata metadata = builder.build();

        PublicBroadcastData pbData = PublicBroadcastData.buildPublicBroadcastData(metadata);

        assertThat(pbData).isNotNull();
        assertThat(pbData.isEncrypted()).isEqualTo(publicBroadcastData.isEncrypted());
        assertThat(pbData.getAudioConfigQuality())
                .isEqualTo(publicBroadcastData.getAudioConfigQuality());
        assertThat(pbData.getMetadata()).isEqualTo(publicBroadcastData.getMetadata());
        assertThat(pbData.getMetadataLength()).isEqualTo(publicBroadcastData.getMetadataLength());
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(pbData.getLtvData()).isNotNull();
            assertThat(pbData.getLtvData().getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.TRUE);
        }
    }

    @Test
    public void buildPublicBroadcastData_withNullPublicMetadata_doesNotCrash() {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setSourceDevice(getTestDevice(0), ADDRESS_TYPE_RANDOM)
                        .addSubgroup(createBroadcastSubgroup())
                        .setEncrypted(true)
                        .setPublicBroadcast(true)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD)
                        .setPublicBroadcastMetadata(null);
        BluetoothLeBroadcastMetadata metadata = builder.build();

        // This should not throw a NullPointerException
        PublicBroadcastData pbData = PublicBroadcastData.buildPublicBroadcastData(metadata);

        assertThat(pbData).isNotNull();
        assertThat(pbData.isEncrypted()).isTrue();
        assertThat(pbData.getAudioConfigQuality())
                .isEqualTo(BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD);
        assertThat(pbData.getMetadata()).isEqualTo(new byte[0]);
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(pbData.getLtvData()).isNotNull();
            assertThat(pbData.getLtvData().getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.NONE);
        }
    }

    @Test
    public void parsePublicBroadcastData() {
        assertThat(PublicBroadcastData.parsePublicBroadcastData(null)).isNull();

        byte[] serviceDataInvalid =
                new byte[] {
                    (byte) 0x02, // features, non-encrypted, standard quality preset
                };
        assertThat(PublicBroadcastData.parsePublicBroadcastData(serviceDataInvalid)).isNull();

        byte[] serviceDataInvalid2 =
                new byte[] {
                    (byte) 0x02, // features, non-encrypted, standard quality preset
                    (byte) 0x03, // metaDataLength
                    (byte) 0x06,
                    (byte) 0x07, // invalid metaData
                };
        assertThat(PublicBroadcastData.parsePublicBroadcastData(serviceDataInvalid2)).isNull();

        byte[] serviceData =
                new byte[] {
                    (byte) 0x07, // features
                    (byte) 0x03, // metaDataLength
                    (byte) 0x02,
                    (byte) 0x08,
                    (byte) 0x01, // metaData: Audio Active State = TRUE
                };
        PublicBroadcastData data = PublicBroadcastData.parsePublicBroadcastData(serviceData);
        assertThat(data.isEncrypted()).isTrue();
        assertThat(data.getAudioConfigQuality()).isEqualTo(3);
        assertThat(data.getMetadataLength()).isEqualTo(3);
        assertThat(data.getMetadata()).isEqualTo(new byte[] {0x02, 0x08, 0x01});
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(data.getLtvData()).isNotNull();
            assertThat(data.getLtvData().getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.TRUE);
        }

        byte[] serviceDataNoMetaData =
                new byte[] {
                    (byte) 0x02, // features, non-encrypted, standard quality preset
                    (byte) 0x00, // metaDataLength
                };
        PublicBroadcastData dataNoMetaData =
                PublicBroadcastData.parsePublicBroadcastData(serviceDataNoMetaData);
        assertThat(dataNoMetaData.isEncrypted()).isFalse();
        assertThat(dataNoMetaData.getAudioConfigQuality()).isEqualTo(1);
        assertThat(dataNoMetaData.getMetadataLength()).isEqualTo(0);
        assertThat(dataNoMetaData.getMetadata()).isEqualTo(new byte[] {});
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(dataNoMetaData.getLtvData()).isNotNull();
            assertThat(dataNoMetaData.getLtvData().getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.NONE);
        }
    }

    @Test
    public void parsePublicBroadcastData_longMetaData() {
        assertThat(PublicBroadcastData.parsePublicBroadcastData(null)).isNull();

        int metaDataLength = 142;
        byte[] serviceDataInvalid =
                new byte[] {
                    (byte) 0x02, // features, non-encrypted, standard quality preset
                };
        assertThat(PublicBroadcastData.parsePublicBroadcastData(serviceDataInvalid)).isNull();

        byte[] serviceDataInvalid2 =
                new byte[] {
                    (byte) 0x02, // features, non-encrypted, standard quality preset
                    (byte) 0x03, // metaDataLength
                    (byte) 0x06,
                    (byte) 0x07, // invalid metaData
                };
        assertThat(PublicBroadcastData.parsePublicBroadcastData(serviceDataInvalid2)).isNull();

        byte[] serviceData =
                new byte[] {
                    (byte) 0x07, // features
                    (byte) metaDataLength, // metaDataLength
                };

        byte[] metadataHeader =
                new byte[] {
                    (byte) (metaDataLength - 1), // length 141
                    (byte) 0xFF
                };

        byte[] metadataPayload = new byte[140];
        new Random().nextBytes(metadataPayload);

        PublicBroadcastData data =
                PublicBroadcastData.parsePublicBroadcastData(
                        Bytes.concat(serviceData, metadataHeader, metadataPayload));
        assertThat(data.isEncrypted()).isTrue();
        assertThat(data.getAudioConfigQuality()).isEqualTo(3);
        assertThat(data.getMetadataLength()).isEqualTo(metaDataLength);
        assertThat(data.getMetadata()).isEqualTo(Bytes.concat(metadataHeader, metadataPayload));
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(data.getLtvData()).isNotNull();
            assertThat(data.getLtvData().getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.NONE);
        }

        byte[] serviceDataNoMetaData =
                new byte[] {
                    (byte) 0x02, // features, non-encrypted, standard quality preset
                    (byte) 0x00, // metaDataLength
                };
        PublicBroadcastData dataNoMetaData =
                PublicBroadcastData.parsePublicBroadcastData(serviceDataNoMetaData);
        assertThat(dataNoMetaData.isEncrypted()).isFalse();
        assertThat(dataNoMetaData.getAudioConfigQuality()).isEqualTo(1);
        assertThat(dataNoMetaData.getMetadataLength()).isEqualTo(0);
        assertThat(dataNoMetaData.getMetadata()).isEqualTo(new byte[] {});
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(dataNoMetaData.getLtvData()).isNotNull();
            assertThat(dataNoMetaData.getLtvData().getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.NONE);
        }
    }
}
