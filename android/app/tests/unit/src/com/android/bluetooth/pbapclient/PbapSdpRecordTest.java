/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.SdpPseRecord;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PbapSdpRecordTest {
    private BluetoothDevice mTestDevice;

    private static final String SERVICE_NAME = "PSE SERVICE NAME";
    private static final int L2CAP_PSM = 4101;
    private static final int RFCOMM_CHANNEL = 5;
    private static final int INVALID_L2CAP = -1;
    private static final int INVALID_RFCOMM = -1;

    private static final int SUPPORTED_REPOSITORIES =
            PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK
                    | PbapSdpRecord.REPOSITORY_SIM_CARD
                    | PbapSdpRecord.REPOSITORY_SPEED_DIAL
                    | PbapSdpRecord.REPOSITORY_FAVORITES;

    private static final int SUPPORTED_FEATURES =
            PbapSdpRecord.FEATURE_DOWNLOADING
                    | PbapSdpRecord.FEATURE_BROWSING
                    | PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER
                    | PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS
                    | PbapSdpRecord.FEATURE_VCARD_SELECTING
                    | PbapSdpRecord.FEATURE_ENHANCED_MISSED_CALLS
                    | PbapSdpRecord.FEATURE_XBT_UCI_VCARD_PROPERTY
                    | PbapSdpRecord.FEATURE_XBT_UID_VCARD_PROPERTY
                    | PbapSdpRecord.FEATURE_CONTACT_REFERENCING
                    | PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT;

    // For utility function testing-- -1 is FIELD_MISSING, and other negatives should be unknown
    private static final int UNRECOGNIZED = -2;

    @Before
    public void setUp() throws Exception {
        mTestDevice = getTestDevice(1);
    }

    @Test
    public void testMakeWithDevice() {
        PbapSdpRecord record =
                makeSdpRecord(INVALID_L2CAP, INVALID_RFCOMM, PbapSdpRecord.VERSION_1_0, 0, 0);
        assertThat(record.getDevice()).isEqualTo(mTestDevice);
    }

    @Test
    public void testMakeWithServiceName() {
        PbapSdpRecord record =
                makeSdpRecord(INVALID_L2CAP, INVALID_RFCOMM, PbapSdpRecord.VERSION_1_0, 0, 0);
        assertThat(record.getServiceName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    public void testMakeVersion10() {
        PbapSdpRecord record =
                makeSdpRecord(INVALID_L2CAP, INVALID_RFCOMM, PbapSdpRecord.VERSION_1_0, 0, 0);
        assertThat(record.getProfileVersion()).isEqualTo(PbapSdpRecord.VERSION_1_0);
    }

    @Test
    public void testMakeVersion11() {
        PbapSdpRecord record =
                makeSdpRecord(INVALID_L2CAP, INVALID_RFCOMM, PbapSdpRecord.VERSION_1_1, 0, 0);
        assertThat(record.getProfileVersion()).isEqualTo(PbapSdpRecord.VERSION_1_1);
    }

    @Test
    public void testMakeVersion12() {
        PbapSdpRecord record =
                makeSdpRecord(INVALID_L2CAP, INVALID_RFCOMM, PbapSdpRecord.VERSION_1_2, 0, 0);
        assertThat(record.getProfileVersion()).isEqualTo(PbapSdpRecord.VERSION_1_2);
    }

    @Test
    public void testMakeL2capTransport() {
        PbapSdpRecord record =
                makeSdpRecord(L2CAP_PSM, INVALID_RFCOMM, PbapSdpRecord.VERSION_1_2, 0, 0);
        assertThat(record.getL2capPsm()).isEqualTo(L2CAP_PSM);
        assertThat(record.getRfcommChannelNumber()).isEqualTo(INVALID_RFCOMM);
    }

    @Test
    public void testMakeRfcommTransport() {
        PbapSdpRecord record =
                makeSdpRecord(INVALID_L2CAP, RFCOMM_CHANNEL, PbapSdpRecord.VERSION_1_2, 0, 0);
        assertThat(record.getL2capPsm()).isEqualTo(INVALID_L2CAP);
        assertThat(record.getRfcommChannelNumber()).isEqualTo(RFCOMM_CHANNEL);
    }

    @Test
    public void testMakeBothTransport() {
        PbapSdpRecord record =
                makeSdpRecord(L2CAP_PSM, RFCOMM_CHANNEL, PbapSdpRecord.VERSION_1_2, 0, 0);
        assertThat(record.getL2capPsm()).isEqualTo(L2CAP_PSM);
        assertThat(record.getRfcommChannelNumber()).isEqualTo(RFCOMM_CHANNEL);
    }

    @Test
    public void testSupportedFeature_featureDownloading() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_DOWNLOADING,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_DOWNLOADING)).isTrue();
        assertThat(record.getSupportedFeatures()).isEqualTo(PbapSdpRecord.FEATURE_DOWNLOADING);
    }

    @Test
    public void testSupportedFeature_featureBrowsing() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_BROWSING,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_BROWSING)).isTrue();
        assertThat(record.getSupportedFeatures()).isEqualTo(PbapSdpRecord.FEATURE_BROWSING);
    }

    @Test
    public void testSupportedFeature_featureDatabaseIdentifier() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER)).isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER);
    }

    @Test
    public void testSupportedFeature_featureFolderVersionCounters() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS))
                .isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS);
    }

    @Test
    public void testSupportedFeature_featureVcardSelecting() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_VCARD_SELECTING,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_VCARD_SELECTING)).isTrue();
        assertThat(record.getSupportedFeatures()).isEqualTo(PbapSdpRecord.FEATURE_VCARD_SELECTING);
    }

    @Test
    public void testSupportedFeature_featureEnhancedMissedCalls() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_ENHANCED_MISSED_CALLS,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_ENHANCED_MISSED_CALLS)).isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_ENHANCED_MISSED_CALLS);
    }

    @Test
    public void testSupportedFeature_featureXbtUciVcardProperty() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_XBT_UCI_VCARD_PROPERTY,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_XBT_UCI_VCARD_PROPERTY))
                .isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_XBT_UCI_VCARD_PROPERTY);
    }

    @Test
    public void testSupportedFeature_featureXbtUidVcardProperty() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_XBT_UID_VCARD_PROPERTY,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_XBT_UID_VCARD_PROPERTY))
                .isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_XBT_UID_VCARD_PROPERTY);
    }

    @Test
    public void testSupportedFeature_featureContactReferencing() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_CONTACT_REFERENCING,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_CONTACT_REFERENCING)).isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_CONTACT_REFERENCING);
    }

    @Test
    public void testSupportedFeature_featureDefaultImageFormat() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT)).isTrue();
        assertThat(record.getSupportedFeatures())
                .isEqualTo(PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT);
    }

    @Test
    public void testSupportedFeatures_allFeaturesSupported() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        SUPPORTED_FEATURES,
                        0);
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_DOWNLOADING)).isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_BROWSING)).isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER)).isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS))
                .isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_VCARD_SELECTING)).isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_ENHANCED_MISSED_CALLS)).isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_XBT_UCI_VCARD_PROPERTY))
                .isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_XBT_UID_VCARD_PROPERTY))
                .isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_CONTACT_REFERENCING)).isTrue();
        assertThat(record.isFeatureSupported(PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT)).isTrue();
        assertThat(record.getSupportedFeatures()).isEqualTo(SUPPORTED_FEATURES);
    }

    @Test
    public void testSupportedRepository_repositoryLocalPhonebook() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        0,
                        PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK);
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK)).isTrue();
        assertThat(record.getSupportedRepositories())
                .isEqualTo(PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK);
    }

    @Test
    public void testSupportedRepository_repositorySimCard() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        0,
                        PbapSdpRecord.REPOSITORY_SIM_CARD);
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_SIM_CARD)).isTrue();
        assertThat(record.getSupportedRepositories()).isEqualTo(PbapSdpRecord.REPOSITORY_SIM_CARD);
    }

    @Test
    public void testSupportedRepository_repositorySpeedDial() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        0,
                        PbapSdpRecord.REPOSITORY_SPEED_DIAL);
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_SPEED_DIAL)).isTrue();
        assertThat(record.getSupportedRepositories())
                .isEqualTo(PbapSdpRecord.REPOSITORY_SPEED_DIAL);
    }

    @Test
    public void testSupportedRepository_repositoryFavorites() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        0,
                        PbapSdpRecord.REPOSITORY_FAVORITES);
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_FAVORITES)).isTrue();
        assertThat(record.getSupportedRepositories()).isEqualTo(PbapSdpRecord.REPOSITORY_FAVORITES);
    }

    @Test
    public void testSupportedRepositories() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        0,
                        SUPPORTED_REPOSITORIES);
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK)).isTrue();
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_SIM_CARD)).isTrue();
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_SPEED_DIAL)).isTrue();
        assertThat(record.isRepositorySupported(PbapSdpRecord.REPOSITORY_FAVORITES)).isTrue();
        assertThat(record.getSupportedRepositories()).isEqualTo(SUPPORTED_REPOSITORIES);
    }

    @Test
    @SuppressLint("UnusedVariable")
    public void testMakeWithNullDevice() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    PbapSdpRecord record =
                            new PbapSdpRecord(null, new SdpPseRecord(0, 0, 0, 0, 0, ""));
                });
    }

    @Test
    @SuppressLint("UnusedVariable")
    public void testMakeWithNullRecord() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    PbapSdpRecord record = new PbapSdpRecord(mTestDevice, null);
                });
    }

    @Test
    public void testRecordToString() {
        PbapSdpRecord record =
                makeSdpRecord(
                        L2CAP_PSM,
                        RFCOMM_CHANNEL,
                        PbapSdpRecord.VERSION_1_2,
                        0,
                        PbapSdpRecord.REPOSITORY_FAVORITES);
        String str = record.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testVersionToStringUtility() {
        assertThat(PbapSdpRecord.versionToString(PbapSdpRecord.VERSION_1_0)).isNotEmpty();
        assertThat(PbapSdpRecord.versionToString(PbapSdpRecord.VERSION_1_1)).isNotEmpty();
        assertThat(PbapSdpRecord.versionToString(PbapSdpRecord.VERSION_1_2)).isNotEmpty();
        assertThat(PbapSdpRecord.versionToString(PbapSdpRecord.FIELD_MISSING)).isNotEmpty();
        assertThat(PbapSdpRecord.versionToString(UNRECOGNIZED)).isNotEmpty();
    }

    @Test
    public void testFeatureToStringUtility() {
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_DOWNLOADING)).isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_BROWSING)).isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_DATABASE_IDENTIFIER))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_FOLDER_VERSION_COUNTERS))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_VCARD_SELECTING))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_ENHANCED_MISSED_CALLS))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_XBT_UCI_VCARD_PROPERTY))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_XBT_UID_VCARD_PROPERTY))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_CONTACT_REFERENCING))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(PbapSdpRecord.FEATURE_DEFAULT_IMAGE_FORMAT))
                .isNotEmpty();
        assertThat(PbapSdpRecord.featureToString(UNRECOGNIZED)).isNotEmpty();
    }

    @Test
    public void testRepositoryToStringUtility() {
        assertThat(PbapSdpRecord.repositoryToString(PbapSdpRecord.REPOSITORY_LOCAL_PHONEBOOK))
                .isNotEmpty();
        assertThat(PbapSdpRecord.repositoryToString(PbapSdpRecord.REPOSITORY_SIM_CARD))
                .isNotEmpty();
        assertThat(PbapSdpRecord.repositoryToString(PbapSdpRecord.REPOSITORY_SPEED_DIAL))
                .isNotEmpty();
        assertThat(PbapSdpRecord.repositoryToString(PbapSdpRecord.REPOSITORY_FAVORITES))
                .isNotEmpty();
        assertThat(PbapSdpRecord.repositoryToString(UNRECOGNIZED)).isNotEmpty();
    }

    // *********************************************************************************************
    // * Test Utilities
    // *********************************************************************************************

    private PbapSdpRecord makeSdpRecord(
            int l2capPsm, int rfcommChnl, int version, int features, int repositories) {
        SdpPseRecord sdpRecord =
                new SdpPseRecord(
                        l2capPsm, rfcommChnl, version, features, repositories, SERVICE_NAME);
        return new PbapSdpRecord(mTestDevice, sdpRecord);
    }
}
