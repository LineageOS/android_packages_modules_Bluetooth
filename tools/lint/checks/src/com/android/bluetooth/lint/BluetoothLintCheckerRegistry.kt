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

package com.android.bluetooth.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.google.auto.service.AutoService

@AutoService(IssueRegistry::class)
@Suppress("UnstableApiUsage")
class BluetoothLintCheckerIssueRegistry : IssueRegistry() {
    override val issues =
        listOf(
            LogEnforcementVariableCreationDetector.ISSUE,
            GuardedLogLineDetector.ISSUE,
            GuardedLogLineDetector.WARNING
        )

    override val api: Int
        get() = CURRENT_API

    override val minApi: Int
        get() = 11

    override val vendor =
        Vendor(
            vendorName = "Android",
            feedbackUrl = "http://b/issues/new?component=27441",
            contact = "android-bluetooth@google.com"
        )
}
