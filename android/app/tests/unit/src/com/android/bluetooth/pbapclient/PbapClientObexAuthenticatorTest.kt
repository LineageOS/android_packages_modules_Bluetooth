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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [PbapClientObexAuthenticator]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PbapClientObexAuthenticatorTest {

    private lateinit var authenticator: PbapClientObexAuthenticator

    @Before
    fun setUp() {
        authenticator = PbapClientObexAuthenticator()
    }

    @Test
    fun onAuthenticationChallenge() {
        // Note: onAuthenticationChallenge() does not use any arguments
        val passwordAuthentication =
            authenticator.onAuthenticationChallenge(
                /* description= */ null,
                /* isUserIdRequired= */ false,
                /* isFullAccess= */ false,
            )

        assertThat(passwordAuthentication.password)
            .isEqualTo(authenticator.mSessionKey.toByteArray())
    }

    @Test
    fun onAuthenticationResponse() {
        val userName = byteArrayOf()
        // Note: onAuthenticationResponse() does not use any arguments
        assertThat(authenticator.onAuthenticationResponse(userName)).isNull()
    }
}
