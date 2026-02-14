/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.pbapclient

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock

/** Test cases for [PbapClientAccountAuthenticator]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PbapClientAccountAuthenticatorTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var response: AccountAuthenticatorResponse
    @Mock private lateinit var account: Account

    private lateinit var authenticator: PbapClientAccountAuthenticator

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        authenticator = PbapClientAccountAuthenticator(context)
    }

    @Test
    fun editProperties_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException::class.java) {
            authenticator.editProperties(response, null)
        }
    }

    @Test
    fun addAccount_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException::class.java) {
            authenticator.addAccount(response, null, null, null, null)
        }
    }

    @Test
    fun confirmCredentials_returnsNull() {
        assertThat(authenticator.confirmCredentials(response, account, null)).isNull()
    }

    @Test
    fun getAuthToken_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException::class.java) {
            authenticator.getAuthToken(response, account, null, null)
        }
    }

    @Test
    fun getAuthTokenLabel_returnsNull() {
        assertThat(authenticator.getAuthTokenLabel(null)).isNull()
    }

    @Test
    fun updateCredentials_returnsNull() {
        assertThat(authenticator.updateCredentials(response, account, null, null)).isNull()
    }

    @Test
    fun hasFeatures_notSupported() {
        val result = authenticator.hasFeatures(response, account, null)
        assertThat(result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)).isFalse()
    }
}
