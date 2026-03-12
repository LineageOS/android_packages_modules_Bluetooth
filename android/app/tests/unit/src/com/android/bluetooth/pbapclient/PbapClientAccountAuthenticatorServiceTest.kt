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

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [PbapClientAccountAuthenticatorService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class PbapClientAccountAuthenticatorServiceTest {

    @get:Rule val serviceRule = ServiceTestRule()

    private val context = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() {
        enableService(true)
    }

    @After
    fun tearDown() {
        enableService(false)
    }

    @Test
    fun bind() {
        val intent = Intent("android.accounts.AccountAuthenticator")
        intent.setClass(context, PbapClientAccountAuthenticatorService::class.java)

        assertThat(serviceRule.bindService(intent)).isNotNull()
    }

    private fun enableService(enable: Boolean) {
        val enabledState =
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val serviceName = ComponentName(context, PbapClientAccountAuthenticatorService::class.java)
        context.packageManager.setComponentEnabledSetting(
            serviceName,
            enabledState,
            PackageManager.DONT_KILL_APP,
        )
    }
}
