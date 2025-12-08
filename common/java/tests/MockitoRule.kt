/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.tests.bluetooth

import org.junit.rules.MethodRule
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.quality.Strictness

/** Wrapper around MockitoJUnit.rule() to clear the inline mock at the end of the test. */
class MockitoRule : MethodRule {
    private val mockitoRule = MockitoJUnit.rule()

    fun strictness(strictness: Strictness): MockitoRule {
        mockitoRule.strictness(strictness)
        return this
    }

    override fun apply(base: Statement, method: FrameworkMethod, target: Any): Statement {
        val nestedStatement = mockitoRule.apply(base, method, target)

        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                nestedStatement.evaluate()

                // Prevent OutOfMemory errors due to mock maker leaks.
                // See https://github.com/mockito/mockito/issues/1614, b/259280359, b/396177821
                Mockito.framework().clearInlineMocks()
            }
        }
    }
}
