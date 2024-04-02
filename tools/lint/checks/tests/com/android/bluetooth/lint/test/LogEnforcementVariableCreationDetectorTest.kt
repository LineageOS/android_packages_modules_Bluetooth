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

package com.android.bluetooth.lint.test

import com.android.bluetooth.lint.LogEnforcementVariableCreationDetector
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@Suppress("UnstableApiUsage")
@RunWith(JUnit4::class)
class LogEnforcementVariableCreationDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = LogEnforcementVariableCreationDetector()

    override fun getIssues(): List<Issue> = listOf(LogEnforcementVariableCreationDetector.ISSUE)

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    @Test
    fun testClassWithDVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean D = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean D = true;"))
    }

    @Test
    fun testClassWithVVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean V = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean V = true;"))
    }

    @Test
    fun testClassWithDbgVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean DBG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean DBG = true;"))
    }

    @Test
    fun testClassWithVdbgVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean VDBG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean VDBG = true;"))
    }

    @Test
    fun testClassWithDebugVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean DEBUG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean DEBUG = true;"))
    }

    @Test
    fun testClassWithVerboseVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean VERBOSE = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean VERBOSE = true;"))
    }

    @Test
    fun testClassWithDbgEndingVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_DBG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean SHOULD_DBG = true;"))
    }

    @Test
    fun testClassWithVdbgEndingVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_VDBG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(7, "    private static final boolean SHOULD_VDBG = true;")
            )
    }

    @Test
    fun testClassWithMemberDbgVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean mDbg = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean mDbg = true;"))
    }

    @Test
    fun testClassWithMemberDebugVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean mDebug = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean mDebug = true;"))
    }

    @Test
    fun testClassWithMemberVdbgVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean mVdbg = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean mVdbg = true;"))
    }

    @Test
    fun testClassWithMemberVerboseVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean mVerbose = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final boolean mVerbose = true;"))
    }

    @Test
    fun testClassWithVarAssignedToAnotherEnforcementVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;
                import com.android.foo.FooConstants;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG = FooConstants.DBG;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(8, "    private static final boolean SHOULD_LOG = FooConstants.DBG;")
            )
    }

    @Test
    fun testClassWithVarAssignedToAnotherEnforcementVarWithBinaryOp_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;
                import com.android.foo.FooConstants;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG =
                            false || FooConstants.DBG;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    8,
                    """    private static final boolean SHOULD_LOG =
            false || FooConstants.DBG;"""
                )
            )
    }

    @Test
    fun testClassWithLogIsLoggableVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG = Log.isLoggable(TAG, Log.DEBUG);

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    7,
                    "    private static final boolean SHOULD_LOG = Log.isLoggable(TAG, Log.DEBUG);"
                )
            )
    }

    @Test
    fun testClassWithUnaryLogIsLoggableVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG = !Log.isLoggable(TAG, Log.DEBUG);

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    7,
                    "    private static final boolean SHOULD_LOG = !Log.isLoggable(TAG, Log.DEBUG);"
                )
            )
    }

    @Test
    fun testClassWithBinaryLogIsLoggableVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG = Log.isLoggable(TAG, Log.DEBUG) || true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    7,
                    "    private static final boolean SHOULD_LOG = Log.isLoggable(TAG, Log.DEBUG) || true;"
                )
            )
    }

    @Test
    fun testClassWithBinaryDoubleLogIsLoggableVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG =
                            Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(TAG, Log.VERBOSE);

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    7,
                    """    private static final boolean SHOULD_LOG =
            Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(TAG, Log.VERBOSE);"""
                )
            )
    }

    @Test
    fun testClassWithMultipleBinaryLogIsLoggableVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG =
                            false || Log.isLoggable(TAG, Log.DEBUG) || true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    7,
                    """    private static final boolean SHOULD_LOG =
            false || Log.isLoggable(TAG, Log.DEBUG) || true;"""
                )
            )
    }

    @Test
    fun testClassWithParenthesizedLogIsLoggableVar_issueFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean SHOULD_LOG =
                            (Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(TAG, Log.VERBOSE));

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(
                createFixDiff(
                    7,
                    """    private static final boolean SHOULD_LOG =
            (Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(TAG, Log.VERBOSE));"""
                )
            )
    }

    @Test
    fun testClassWithNoLogEnforcementVariables_noLintIssuesFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testClassOutsideOfBluetoothPackage_noLintIssuesFound() {
        lint()
            .files(
                java(
                        """
                package com.android.wifi;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final boolean DBG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testVariableCreationWithBooleanWrapperClass_issuesFound() {
        lint()
            .files(
                java(
                        """
                package com.android.bluetooth;

                import android.util.Log;

                public final class Foo {
                    private static final String TAG = Foo.class.getSimpleName();
                    private static final Boolean WHYYYY_DBG = true;

                    public Foo() {
                        Log.d(TAG, "created Foo without an enforcement variable");
                    }
                }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(LogEnforcementVariableCreationDetector.ISSUE)
            .run()
            .expectContains(
                LogEnforcementVariableCreationDetector.LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR
            )
            .expectContains(createErrorCountString(1, 0))
            .expectFixDiffs(createFixDiff(7, "    private static final Boolean WHYYYY_DBG = true;"))
    }

    private val logFramework: TestFile =
        java(
                """
                package android.util;
                public class Log {
                    public static final int ASSERT = 7;
                    public static final int ERROR = 6;
                    public static final int WARN = 5;
                    public static final int INFO = 4;
                    public static final int DEBUG = 3;
                    public static final int VERBOSE = 2;

                    public static Boolean isLoggable(String tag, int level) {
                        return true;
                    }

                    public static int wtf(String msg) {
                        return 1;
                    }

                    public static int e(String msg) {
                        return 1;
                    }

                    public static int w(String msg) {
                        return 1;
                    }

                    public static int i(String msg) {
                        return 1;
                    }

                    public static int d(String msg) {
                        return 1;
                    }

                    public static int v(String msg) {
                        return 1;
                    }
                }
                """
            )
            .indented()

    private val constantsHelper: TestFile =
        java(
                """
                package com.android.foo;

                public class FooConstants {
                    public static final String TAG = "FooConstants";
                    public static final boolean DBG = true;
                    public static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);
                }
                """
            )
            .indented()

    private val stubs = arrayOf(logFramework, constantsHelper)

    private fun createErrorCountString(errors: Int, warnings: Int): String {
        return "%d errors, %d warnings".format(errors, warnings)
    }

    private fun createFixDiff(lineNumber: Int, lines: String): String {
        // All lines are removed. Add enough spaces to match the below indenting
        val minusedlines = lines.replace("\n ", "\n               -  ")
        return """
               Fix for src/com/android/bluetooth/Foo.java line $lineNumber: Remove log enforcement variable declaration:
               @@ -$lineNumber +$lineNumber
               - $minusedlines
               -
               """
            .trimIndent()
    }
}
