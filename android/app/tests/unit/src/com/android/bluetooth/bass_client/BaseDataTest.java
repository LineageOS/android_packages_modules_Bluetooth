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
        assertThat(info.mPresentationDelay.length).isEqualTo(3);
        assertThat(info.mCodecId.length).isEqualTo(5);

        assertThat(info.mCodecId)
                .isEqualTo(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
                        });
        info.mCodecId[4] = (byte) 0xFE;
        assertThat(info.mCodecId)
                .isNotEqualTo(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
                        });

        // info.print() with different combination shouldn't crash.
        info.print();

        info.mLevel = 1;
        info.mCodecConfigLength = 1;
        info.print();

        info.mLevel = 2;
        info.mCodecConfigLength = 3;
        info.mCodecConfigInfo = new byte[] {(byte) 0x01, (byte) 0x05};
        info.mMetaDataLength = 4;
        info.mMetaData = new byte[] {(byte) 0x04, (byte) 0x80, (byte) 0x79, (byte) 0x76};
        info.print();

        info.mLevel = 3;
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
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // mNumSubGroups
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // UNKNOWN_CODEC
                    (byte) 0x02, // mCodecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // mCodecConfigInfo
                    (byte) 0x03, // mMetaDataLength
                    (byte) 0x06,
                    (byte) 0x07,
                    (byte) 0x08, // mMetaData
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C' // mCodecConfigInfo
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.BaseInformation level = data.levelOne();
        assertThat(level.mPresentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.mNumSubGroups).isEqualTo(1);

        assertThat(data.levelTwo()).hasSize(1);
        level = data.levelTwo().get(0);

        assertThat(level.mNumSubGroups).isEqualTo(1);
        assertThat(level.mCodecId).isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x00, 0x00});
        assertThat(level.mCodecConfigLength).isEqualTo(2);
        assertThat(level.mMetaDataLength).isEqualTo(3);

        assertThat(data.levelThree()).hasSize(1);
        level = data.levelThree().get(0);
        assertThat(level.mIndex).isEqualTo(4);
        assertThat(level.mCodecConfigLength).isEqualTo(3);
    }

    @Test
    public void parseBaseDataLvl2TruncatedConfig() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // Lc3
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // mCodecConfigInfo
                };

        assertThat(BaseData.parseBaseData(serviceData)).isNull();
    }

    @Test
    public void parseBaseDataLvl2TruncatedMetadata() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // UNKNOWN_CODEC
                    (byte) 0x02, // mCodecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // mCodecConfigInfo
                    (byte) 0x04, // mMetaDataLength
                    (byte) 0x06,
                    (byte) 0x07,
                    (byte) 0x08, // mMetaData
                };

        assertThat(BaseData.parseBaseData(serviceData)).isNull();
    }

    @Test
    public void parseBaseDataLvl3TruncatedConfig() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // UNKNOWN_CODEC
                    (byte) 0x02, // mCodecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // mCodecConfigInfo
                    (byte) 0x03, // mMetaDataLength
                    (byte) 0x06,
                    (byte) 0x07,
                    (byte) 0x08, // mMetaData
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x04, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C' // mCodecConfigInfo
                };

        assertThat(BaseData.parseBaseData(serviceData)).isNull();
    }

    @Test
    public void parseBaseDataInvalidLtv() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // LC3
                    (byte) 0x02, // mCodecConfigLength
                    (byte) 0x04,
                    (byte) 'A', // mCodecConfigInfo
                    (byte) 0x03, // mMetaDataLength
                    (byte) 0x06,
                    (byte) 0x07,
                    (byte) 0x08, // mMetaData
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x03,
                    (byte) 'B',
                    (byte) 'C' // mCodecConfigInfo
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.BaseInformation level = data.levelOne();
        assertThat(level.mPresentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.mNumSubGroups).isEqualTo(1);

        assertThat(data.levelTwo()).hasSize(1);
        level = data.levelTwo().get(0);

        assertThat(level.mNumSubGroups).isEqualTo(1);
        assertThat(level.mCodecId).isEqualTo(new byte[] {0x06, 0x00, 0x00, 0x00, 0x00});
        assertThat(level.mCodecConfigLength).isEqualTo(2);
        assertThat(level.mMetaDataLength).isEqualTo(3);

        assertThat(data.levelThree()).hasSize(1);
        level = data.levelThree().get(0);
        assertThat(level.mIndex).isEqualTo(4);

        // Got the whole config, without interpreting it as LTV
        assertThat(level.mCodecConfigLength).isEqualTo(3);
    }

    @Test
    public void parseBaseVendorCodecBaseData() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0xFF, // VENDOR_CODEC
                    (byte) 0x0A,
                    (byte) 0xAB,
                    (byte) 0xBC,
                    (byte) 0xCD,
                    (byte) 0x04, // mCodecConfigLength
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03,
                    (byte) 0x04, // opaque vendor data
                    (byte) 0x03, // mMetaDataLength
                    (byte) 0x06,
                    (byte) 0x07,
                    (byte) 0x08, // mMetaData
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01 // opaque vendor data
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.BaseInformation level = data.levelOne();
        assertThat(level.mPresentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.mNumSubGroups).isEqualTo(1);

        assertThat(data.levelTwo()).hasSize(1);
        level = data.levelTwo().get(0);

        assertThat(level.mNumSubGroups).isEqualTo(1);
        assertThat(level.mCodecId)
                .isEqualTo(
                        new byte[] {
                            (byte) 0xFF, (byte) 0x0A, (byte) 0xAB, (byte) 0xBC, (byte) 0xCD
                        });
        assertThat(level.mCodecConfigLength).isEqualTo(4);
        assertThat(level.mMetaDataLength).isEqualTo(3);

        assertThat(data.levelThree()).hasSize(1);
        level = data.levelThree().get(0);
        assertThat(level.mIndex).isEqualTo(4);
        assertThat(level.mCodecConfigLength).isEqualTo(3);
    }

    @Test
    public void parseBaseVendorCodecBaseDataMinimal() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // numBIS
                    (byte) 0xFF, // VENDOR_CODEC
                    (byte) 0x0A,
                    (byte) 0xAB,
                    (byte) 0xBC,
                    (byte) 0xCD,
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x00, // mMetaDataLength
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.BaseInformation level = data.levelOne();
        assertThat(level.mPresentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.mNumSubGroups).isEqualTo(1);

        assertThat(data.levelTwo()).hasSize(1);
        level = data.levelTwo().get(0);

        assertThat(level.mNumSubGroups).isEqualTo(1);
        assertThat(level.mCodecId)
                .isEqualTo(
                        new byte[] {
                            (byte) 0xFF, (byte) 0x0A, (byte) 0xAB, (byte) 0xBC, (byte) 0xCD
                        });
        assertThat(level.mCodecConfigLength).isEqualTo(0);
        assertThat(level.mMetaDataLength).isEqualTo(0);

        assertThat(data.levelThree()).hasSize(1);
        level = data.levelThree().get(0);
        assertThat(level.mIndex).isEqualTo(4);
        assertThat(level.mCodecConfigLength).isEqualTo(0);
    }

    @Test
    public void parseBaseVendorCodecBaseDataInvalid() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x00, // numBIS invalid value
                    (byte) 0xFE, // UNKNOWN CODEC
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x00, // mMetaDataLength
                };

        assertThat(BaseData.parseBaseData(serviceData)).isNull();
    }

    @Test
    public void parseBaseData_longMetaData() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        int mMetaDataLength = 142;

        byte[] serviceDataLevel1 =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01 // mNumSubGroups
                };

        byte[] serviceDataLevel2 =
                new byte[] {
                    // LEVEL 2
                    (byte) 0x01, // mNumSubGroups
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // UNKNOWN_CODEC
                    (byte) 0x02, // mCodecConfigLength
                    (byte) 0x01,
                    (byte) 'A', // mCodecConfigInfo
                    (byte) mMetaDataLength, // mMetaDataLength 142
                };

        byte[] metadataHeader =
                new byte[] {
                    (byte) (mMetaDataLength - 1), // length 141
                    (byte) 0xFF
                };

        byte[] metadataPayload = new byte[140];
        new Random().nextBytes(metadataPayload);

        byte[] serviceDataLevel3 =
                new byte[] {
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C' // mCodecConfigInfo
                };

        BaseData data =
                BaseData.parseBaseData(
                        Bytes.concat(
                                serviceDataLevel1,
                                Bytes.concat(serviceDataLevel2, metadataHeader, metadataPayload),
                                serviceDataLevel3));
        BaseData.BaseInformation level = data.levelOne();
        assertThat(level.mPresentationDelay).isEqualTo(new byte[] {0x01, 0x02, 0x03});
        assertThat(level.mNumSubGroups).isEqualTo(1);

        assertThat(data.levelTwo()).hasSize(1);
        level = data.levelTwo().get(0);

        assertThat(level.mNumSubGroups).isEqualTo(1);
        assertThat(level.mCodecId).isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x00, 0x00});
        assertThat(level.mCodecConfigLength).isEqualTo(2);
        assertThat(level.mMetaDataLength).isEqualTo(mMetaDataLength);
        assertThat(level.mMetaData).isEqualTo(Bytes.concat(metadataHeader, metadataPayload));

        assertThat(data.levelThree()).hasSize(1);
        level = data.levelThree().get(0);
        assertThat(level.mIndex).isEqualTo(4);
        assertThat(level.mCodecConfigLength).isEqualTo(3);
    }
}
