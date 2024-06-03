/*
 * Copyright 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Random;

@RunWith(JUnit4.class)
public class BaseDataTest {

    @Test
    public void baseInformation() {
        BaseData.BaseInformation info = new BaseData.BaseInformation();
        assertThat(info.presentationDelay.length).isEqualTo(3);
        assertThat(info.codecId.length).isEqualTo(5);

        assertThat(info.codecId)
                .isEqualTo(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
                        });
        info.codecId[4] = (byte) 0xFE;
        assertThat(info.codecId)
                .isNotEqualTo(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
                        });

        // info.print() with different combination shouldn't crash.
        info.print();

        info.level = 1;
        info.codecConfigLength = 1;
        info.print();

        info.level = 2;
        info.codecConfigLength = 3;
        info.codecConfigInfo = new byte[] {(byte) 0x01, (byte) 0x05};
        info.metaDataLength = 4;
        info.metaData = new byte[] {(byte) 0x04, (byte) 0x80, (byte) 0x79, (byte) 0x76};
        info.print();

        info.level = 3;
        info.print();
    }

    @Test
    public void parseBaseData() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // presentationDelay
                    (byte) 0x01, // numSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numSubGroups
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // UNKNOWN_CODEC
                    (byte) 0x02, // codecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // codecConfigInfo
                    (byte) 0x03, // metaDataLength
                    (byte) 0x06,
                    (byte) 0x07,
                    (byte) 0x08, // metaData
                    // LEVEL 3
                    (byte) 0x04, // index
                    (byte) 0x03, // codecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C' // codecConfigInfo
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.BaseInformation level = data.getLevelOne();
        assertThat(level.presentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.numSubGroups).isEqualTo(1);

        assertThat(data.getLevelTwo().size()).isEqualTo(1);
        level = data.getLevelTwo().get(0);

        assertThat(level.numSubGroups).isEqualTo(1);
        assertThat(level.codecId).isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x00, 0x00});
        assertThat(level.codecConfigLength).isEqualTo(2);
        assertThat(level.metaDataLength).isEqualTo(3);

        assertThat(data.getLevelThree().size()).isEqualTo(1);
        level = data.getLevelThree().get(0);
        assertThat(level.index).isEqualTo(4);
        assertThat(level.codecConfigLength).isEqualTo(3);
    }

    @Test
    public void parseBaseData_longMetaData() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        int metaDataLength = 142;

        byte[] serviceDataLevel1 =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // presentationDelay
                    (byte) 0x01 // numSubGroups
                };

        byte[] serviceDataLevel2 =
                new byte[] {
                    // LEVEL 2
                    (byte) 0x01, // numSubGroups
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // UNKNOWN_CODEC
                    (byte) 0x02, // codecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // codecConfigInfo
                    (byte) metaDataLength, // metaDataLength 142
                };

        byte[] metadataHeader =
                new byte[] {
                    (byte) (metaDataLength - 1), // length 141
                    (byte) 0xFF
                };

        byte[] metadataPayload = new byte[140];
        new Random().nextBytes(metadataPayload);

        byte[] serviceDataLevel3 =
                new byte[] {
                    // LEVEL 3
                    (byte) 0x04, // index
                    (byte) 0x03, // codecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C' // codecConfigInfo
                };

        BaseData data =
                BaseData.parseBaseData(
                        Bytes.concat(
                                serviceDataLevel1,
                                Bytes.concat(serviceDataLevel2, metadataHeader, metadataPayload),
                                serviceDataLevel3));
        BaseData.BaseInformation level = data.getLevelOne();
        assertThat(level.presentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.numSubGroups).isEqualTo(1);

        assertThat(data.getLevelTwo().size()).isEqualTo(1);
        level = data.getLevelTwo().get(0);

        assertThat(level.numSubGroups).isEqualTo(1);
        assertThat(level.codecId).isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x00, 0x00});
        assertThat(level.codecConfigLength).isEqualTo(2);
        assertThat(level.metaDataLength).isEqualTo(metaDataLength);
        assertThat(level.metaData).isEqualTo(Bytes.concat(metadataHeader, metadataPayload));

        assertThat(data.getLevelThree().size()).isEqualTo(1);
        level = data.getLevelThree().get(0);
        assertThat(level.index).isEqualTo(4);
        assertThat(level.codecConfigLength).isEqualTo(3);
    }
}
