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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PbapPhonebookMetadataTest {
    private static final int SIZE = 5;
    private static final String DATABASE_IDENTIFIER = "dbid";
    private static final String PRIMARY_VERSION_COUNTER = "pvc";
    private static final String SECONDARY_VERSION_COUNTER = "svc";

    @Test
    public void testCreatePhonebookMetadata_forFavorites_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.FAVORITES_PATH,
                        SIZE,
                        DATABASE_IDENTIFIER,
                        PRIMARY_VERSION_COUNTER,
                        SECONDARY_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.FAVORITES_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier()).isEqualTo(DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter()).isEqualTo(PRIMARY_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter()).isEqualTo(SECONDARY_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forLocalPhonebook_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.LOCAL_PHONEBOOK_PATH,
                        SIZE,
                        DATABASE_IDENTIFIER,
                        PRIMARY_VERSION_COUNTER,
                        SECONDARY_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.LOCAL_PHONEBOOK_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier()).isEqualTo(DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter()).isEqualTo(PRIMARY_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter()).isEqualTo(SECONDARY_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forSimLocalPhonebook_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.SIM_PHONEBOOK_PATH,
                        SIZE,
                        DATABASE_IDENTIFIER,
                        PRIMARY_VERSION_COUNTER,
                        SECONDARY_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.SIM_PHONEBOOK_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier()).isEqualTo(DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter()).isEqualTo(PRIMARY_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter()).isEqualTo(SECONDARY_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forIncomingCallHistory_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.ICH_PATH,
                        SIZE,
                        PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.ICH_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forOutgoingCallHistory_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.OCH_PATH,
                        SIZE,
                        PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.OCH_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forMissedCallHistory_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.MCH_PATH,
                        SIZE,
                        PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.MCH_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forSimIncomingCallHistory_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.SIM_ICH_PATH,
                        SIZE,
                        PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.SIM_ICH_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forSimOutgoingCallHistory_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.SIM_OCH_PATH,
                        SIZE,
                        PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.SIM_OCH_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }

    @Test
    public void testCreatePhonebookMetadata_forSimMissedCallHistory_metadataCreated() {
        PbapPhonebookMetadata metadata =
                new PbapPhonebookMetadata(
                        PbapPhonebook.SIM_MCH_PATH,
                        SIZE,
                        PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER,
                        PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        assertThat(metadata.phonebook()).isEqualTo(PbapPhonebook.SIM_MCH_PATH);
        assertThat(metadata.size()).isEqualTo(SIZE);
        assertThat(metadata.databaseIdentifier())
                .isEqualTo(PbapPhonebookMetadata.INVALID_DATABASE_IDENTIFIER);
        assertThat(metadata.primaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);
        assertThat(metadata.secondaryVersionCounter())
                .isEqualTo(PbapPhonebookMetadata.INVALID_VERSION_COUNTER);

        String str = metadata.toString();
        assertThat(str).isNotNull();
        assertThat(str.length()).isNotEqualTo(0);
    }
}
