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

package com.android.bluetooth.bass_client;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;

import com.google.common.primitives.Bytes;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

/** Test cases for {@link BaseData}. */
@RunWith(AndroidJUnit4.class)
public class BaseDataTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

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
                    (byte) 0x07, // mMetaDataLength
                    (byte) 0x02, // Length
                    (byte) 0x08, // Type: AUDIO_ACTIVE_STATE
                    (byte) 0x01, // mMetaData: Audio Active State = TRUE
                    (byte) 0x03, // Length
                    (byte) 0x02, // Type: STREAMING_AUDIO_CONTEXTS
                    (byte) 0x04, // Value LSB (Media)
                    (byte) 0x00, // Value MSB
                    // LEVEL 3
                    (byte) 0x04, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 'B',
                    (byte) 'C' // mCodecConfigInfo
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(197121);
        assertThat(base.mSubgroups.size()).isEqualTo(1);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);

        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(0);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(2);
        assertThat(subgroup.mMetadata.length).isEqualTo(7);
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(subgroup.mLtvData).isNotNull();
            assertThat(subgroup.mLtvData.getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.TRUE);
        }
        if (Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
            assertThat(subgroup.mLtvData).isNotNull();
            assertThat(subgroup.mLtvData.getStreamingAudioContexts()).isEqualTo(4);
        }

        BaseData.BaseBis bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(4);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(3);
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
        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(197121);
        assertThat(base.mSubgroups.size()).isEqualTo(1);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);

        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(6);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(2);
        assertThat(subgroup.mMetadata.length).isEqualTo(3);

        BaseData.BaseBis bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(4);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(3);
        if (Flags.leaudioBroadcastExtendAudioActiveState()) {
            assertThat(subgroup.mLtvData).isNotNull();
            assertThat(subgroup.mLtvData.getAudioActiveState())
                    .isEqualTo(LtvData.AudioActiveState.NONE);
        }
        if (Flags.leaudioBroadcastAutoSwitchAnnouncement()) {
            assertThat(subgroup.mLtvData.getStreamingAudioContexts()).isNull();
        }
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
        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(197121);
        assertThat(base.mSubgroups.size()).isEqualTo(1);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);

        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(0xCDBCAB0AFFL);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(4);
        assertThat(subgroup.mMetadata.length).isEqualTo(3);

        BaseData.BaseBis bis = subgroup.mBises.get(0);

        assertThat(bis.mIndex).isEqualTo(4);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(3);
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
        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(197121);
        assertThat(base.mSubgroups.size()).isEqualTo(1);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);

        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(0xCDBCAB0AFFL);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(0);
        assertThat(subgroup.mMetadata.length).isEqualTo(0);

        BaseData.BaseBis bis = subgroup.mBises.get(0);

        assertThat(bis.mIndex).isEqualTo(4);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);
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

        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(197121);
        assertThat(base.mSubgroups.size()).isEqualTo(1);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);

        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(0x0000000000L);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(2);
        assertThat(subgroup.mMetadata.length).isEqualTo(mMetaDataLength);
        assertThat(subgroup.mMetadata).isEqualTo(Bytes.concat(metadataHeader, metadataPayload));

        BaseData.BaseBis bis = subgroup.mBises.get(0);

        assertThat(bis.mIndex).isEqualTo(4);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(3);
    }

    @Test
    public void parseBaseData_multiple_subgroup() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x40,
                    (byte) 0x9C,
                    (byte) 0x00, // mPresentationDelay
                    (byte) 0x03, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x01, // mNumBis
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // LC3
                    (byte) 0x0A, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x04,
                    (byte) 0x28,
                    (byte) 0x00,
                    (byte) 0x04, // mMetaDataLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x00, // mMetaData
                    // LEVEL 3
                    (byte) 0x01, // mIndex
                    (byte) 0x00, // mCodecConfigLength

                    // LEVEL 2
                    (byte) 0x01, // mNumBis
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // LC3
                    (byte) 0x0A, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x04,
                    (byte) 0x28,
                    (byte) 0x00,
                    (byte) 0x04, // mMetaDataLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x00, // mMetaData
                    // LEVEL 3
                    (byte) 0x02, // mIndex
                    (byte) 0x00, // mCodecConfigLength

                    // LEVEL 2
                    (byte) 0x02, // mNumBis
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // LC3
                    (byte) 0x0A, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x04,
                    (byte) 0x28,
                    (byte) 0x00,
                    (byte) 0x04, // mMetaDataLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x00, // mMetaData
                    // LEVEL 3
                    (byte) 0x03, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x03, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(40000);
        assertThat(base.mSubgroups.size()).isEqualTo(3);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);
        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(6);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(10);
        assertThat(subgroup.mMetadata.length).isEqualTo(4);
        BaseData.BaseBis bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(1);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);

        subgroup = base.mSubgroups.get(1);
        assertThat(subgroup.mBises.size()).isEqualTo(1);
        assertThat(subgroup.mCodecId).isEqualTo(6);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(10);
        assertThat(subgroup.mMetadata.length).isEqualTo(4);
        bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(2);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);

        subgroup = base.mSubgroups.get(2);
        assertThat(subgroup.mBises.size()).isEqualTo(2);
        assertThat(subgroup.mCodecId).isEqualTo(6);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(10);
        assertThat(subgroup.mMetadata.length).isEqualTo(4);
        bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(3);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);
    }

    @Test
    public void parseBaseData_multiple_bis() {
        assertThrows(IllegalArgumentException.class, () -> BaseData.parseBaseData(null));

        byte[] serviceData =
                new byte[] {
                    // LEVEL 1
                    (byte) 0x40,
                    (byte) 0x9C,
                    (byte) 0x00, // mPresentationDelay
                    (byte) 0x02, // mNumSubGroups
                    // LEVEL 2
                    (byte) 0x02, // mNumBis
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // LC3
                    (byte) 0x0A, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x04,
                    (byte) 0x28,
                    (byte) 0x00,
                    (byte) 0x04, // mMetaDataLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x00, // mMetaData
                    // LEVEL 3
                    (byte) 0x01, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x02, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01, // opaque vendor data

                    // LEVEL 2
                    (byte) 0x04, // mNumBis
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // LC3
                    (byte) 0x0A, // mCodecConfigLength
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x03,
                    (byte) 0x04,
                    (byte) 0x28,
                    (byte) 0x00,
                    (byte) 0x03, // mMetaDataLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01, // mMetaData
                    // LEVEL 3
                    (byte) 0x03, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x04, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                    (byte) 0x05, // mIndex
                    (byte) 0x03, // mCodecConfigLength
                    (byte) 0x03,
                    (byte) 0x02,
                    (byte) 0x01, // opaque vendor data
                    (byte) 0x06, // mIndex
                    (byte) 0x00, // mCodecConfigLength
                };

        BaseData data = BaseData.parseBaseData(serviceData);
        BaseData.Base base = data.base();
        assertThat(base.mPresentationDelay).isEqualTo(40000);
        assertThat(base.mSubgroups.size()).isEqualTo(2);

        BaseData.BaseSubgroup subgroup = base.mSubgroups.get(0);
        assertThat(subgroup.mBises.size()).isEqualTo(2);
        assertThat(subgroup.mCodecId).isEqualTo(6);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(10);
        assertThat(subgroup.mMetadata.length).isEqualTo(4);
        BaseData.BaseBis bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(1);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);
        bis = subgroup.mBises.get(1);
        assertThat(bis.mIndex).isEqualTo(2);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(3);

        subgroup = base.mSubgroups.get(1);
        assertThat(subgroup.mBises.size()).isEqualTo(4);
        assertThat(subgroup.mCodecId).isEqualTo(6);
        assertThat(subgroup.mCodecSpecificConfiguration.length).isEqualTo(10);
        assertThat(subgroup.mMetadata.length).isEqualTo(3);
        bis = subgroup.mBises.get(0);
        assertThat(bis.mIndex).isEqualTo(3);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);
        bis = subgroup.mBises.get(1);
        assertThat(bis.mIndex).isEqualTo(4);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);
        bis = subgroup.mBises.get(2);
        assertThat(bis.mIndex).isEqualTo(5);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(3);
        bis = subgroup.mBises.get(3);
        assertThat(bis.mIndex).isEqualTo(6);
        assertThat(bis.mCodecSpecificConfiguration.length).isEqualTo(0);
    }
}
