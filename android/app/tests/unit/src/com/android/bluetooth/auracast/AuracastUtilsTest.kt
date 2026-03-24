/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.auracast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.le_audio.LeAudioConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuracastUtilsTest {

    @Test
    fun parseBroadcastURI_withValidNameAndCode_returnsInfo() {
        // "TestName" -> Base64: "VGVzdE5hbWU="
        // "123456" -> Base64: "MTIzNDU2"
        val uri = "BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;BC:MTIzNDU2;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.code).isEqualTo("123456".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun parseBroadcastURI_withNameOnly_returnsInfoWithNullCode() {
        // "TestName" -> Base64: "VGVzdE5hbWU="
        val uri = "BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.code).isNull()
    }

    @Test
    fun parseBroadcastURI_withoutPrefixAndSuffix_returnsInfo() {
        // Just the raw elements without "BLUETOOTH:UUID:184F;" or ";;"
        val uri = "BN:VGVzdE5hbWU=;BC:MTIzNDU2"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.code).isEqualTo("123456".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun parseBroadcastURI_missingName_returnsNull() {
        // Contains code but no broadcast name
        val uri = "BLUETOOTH:UUID:184F;BC:MTIzNDU2;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNull()
    }

    @Test
    fun parseBroadcastURI_emptyName_returnsNull() {
        // BN prefix is present but empty
        val uri = "BLUETOOTH:UUID:184F;BN:;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNull()
    }

    @Test
    fun parseBroadcastURI_invalidBase64Name_fallsBackToRawString() {
        // "Invalid-Base64-!!!" is not proper base64 padding/characters,
        // it should trigger the exception and fallback to returning the raw string.
        val uri = "BLUETOOTH:UUID:184F;BN:Invalid-Base64-!!!;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("Invalid-Base64-!!!")
        assertThat(info?.code).isNull()
    }

    @Test
    fun parseBroadcastURI_withExtraElements_ignoresExtraAndReturnsInfo() {
        // Includes arbitrary other fields like AT, AD, XX
        val uri = "BLUETOOTH:UUID:184F;AT:1;BN:VGVzdE5hbWU=;XX:YYY;BC:MTIzNDU2;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.code).isEqualTo("123456".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun parseBroadcastURI_invalidBase64Code_returnsNullCode() {
        val uri = "BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;BC:Invalid-Base64-!!!;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        // The code parse catches the IllegalArgumentException and ignores it, leaving code as null
        assertThat(info?.code).isNull()
    }

    @Test
    fun parseBroadcastURI_withValidId_returnsNull() {
        val uri = "BLUETOOTH:UUID:184F;BI:1A2B3C;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNull()
    }

    @Test
    fun parseBroadcastURI_withBothNameAndId() {
        val uri = "BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;BI:1A2B3C;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.broadcastId).isEqualTo(0x1A2B3C)
    }

    @Test
    fun parseBroadcastURI_broadcastIdOutOfRange_returnsInfoWithInvalidId() {
        // "TestName" -> Base64: "VGVzdE5hbWU="
        // Broadcast ID is > 0xFFFFFF
        val uri = "BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;BI:1000000;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.broadcastId).isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID)
    }

    @Test
    fun parseBroadcastURI_malformedBroadcastId_returnsInfoWithInvalidId() {
        // "TestName" -> Base64: "VGVzdE5hbWU="
        // Broadcast ID is not a valid hex string
        val uri = "BLUETOOTH:UUID:184F;BN:VGVzdE5hbWU=;BI:NOT_HEX;;"
        val info = AuracastUtils.parseBroadcastURI(uri)

        assertThat(info).isNotNull()
        assertThat(info?.name).isEqualTo("TestName")
        assertThat(info?.broadcastId).isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID)
    }
}
