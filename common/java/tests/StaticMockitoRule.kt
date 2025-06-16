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

import com.android.dx.mockito.inline.extended.ExtendedMockito
import org.junit.rules.MethodRule
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.mockito.Mockito
import org.mockito.quality.Strictness

/** Similar to {@link MockitoRule}, but allows mocking static methods. */
class StaticMockitoRule(private vararg val classes: Class<*>) : MethodRule {
    override fun apply(base: Statement, method: FrameworkMethod, target: Any): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val session =
                    ExtendedMockito.mockitoSession()
                        .name("${target::class.simpleName}.${method.name}")
                        .initMocks(target)
                        .strictness(Strictness.LENIENT)
                        .apply { classes.forEach { mockStatic(it) } }
                        .startMocking()

                val testFailure =
                    try {
                        base.evaluate()
                        null
                    } catch (throwable: Throwable) {
                        throwable
                    }

                session.finishMocking(testFailure)

                // Prevent OutOfMemory errors due to mock maker leaks.
                // See https://github.com/mockito/mockito/issues/1614, b/259280359, b/396177821
                Mockito.framework().clearInlineMocks()

                if (testFailure != null) {
                    throw testFailure
                }
            }
        }
    }
}
