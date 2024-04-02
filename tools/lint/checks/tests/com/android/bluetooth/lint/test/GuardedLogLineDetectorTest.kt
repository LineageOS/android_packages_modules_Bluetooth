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

import com.android.bluetooth.lint.GuardedLogLineDetector
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
class GuardedLogLineDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = GuardedLogLineDetector()

    override fun getIssues(): List<Issue> = listOf(GuardedLogLineDetector.ISSUE)

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    @Test
    fun testUnguardedLogStatements_noIssuesFound() {
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
        init(6);
    }

    public void init(int i) {
        Log.v(TAG, "Log as v");
        Log.d(TAG, "Log as d");
        Log.i(TAG, "Log as i");
        Log.w(TAG, "Log as w");
        Log.e(TAG, "Log as e");
        Log.wtf(TAG, "Log as a");
    }
}
                """
                ),
                *stubs
            )
            .run()
            .expectClean()
    }

    @Test
    fun testUnguardedLogStatements_inSafeIfStatement_noIssuesFound() {
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
        init(6);
    }

    public void init(int i) {
        if (i > 6) {
            Log.v(TAG, "Log as v");
            Log.d(TAG, "Log as d");
            Log.i(TAG, "Log as i");
            Log.w(TAG, "Log as w");
            Log.e(TAG, "Log as e");
            Log.wtf(TAG, "Log as a");
        }
    }
}
                """
                ),
                *stubs
            )
            .run()
            .expectClean()
    }

    @Test
    fun testGuardedLogWithIsLoggable_warningFound() {
        lint()
            .files(
                java(
                    """
package com.android.bluetooth;

import android.util.Log;

public final class Foo {
    private static final String TAG = Foo.class.getSimpleName();

    public Foo() {
        init(6);
    }

    public void init(int i) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Log as v");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.WARNING)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(0, 1))
    }

    @Test
    fun testGuardedLogWithDbgVariable_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (DBG) {
            Log.d(TAG, "Log as v");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogWithUnaryDbgVariable_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (!DBG) {
            Log.d(TAG, "Log as v");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogWithBinaryDbgVariable_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (true || DBG) {
            Log.d(TAG, "Log as v");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogWithPolyadicDbgVariable_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (true || DBG || Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Log as v");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogWithParenthesizedDbgVariable_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if ((true || DBG || Log.isLoggable(TAG, Log.DEBUG))) {
            Log.d(TAG, "Log as v");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogInNestedIfWithDbgVariable_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if ( i > 6) {
            if (DBG) {
                Log.d(TAG, "Log as v");
            }
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogInIfElseWithDbgVariableInElseIf_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (i > 6) {
            return;
        } else if (DBG) {
            Log.d(TAG, "Log as d");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogInIfElseWithDbgVariableInIf_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (!DBG) {
            return;
        } else {
            Log.d(TAG, "Log as d");
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogInNestedIfWithDbgVariableOuterIfAndLogInInner_issueFound() {
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
        init(6);
    }

    public void init(int i) {
        if (DBG) {
            if (i > 6) {
                Log.d(TAG, "Log as d");
            }
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.ISSUE)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(1, 0))
    }

    @Test
    fun testGuardedLogInNestedIfWithIsLoggableOuterIfAndLogInInner_warningFound() {
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
        init(6);
    }

    public void init(int i) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            if (i > 6) {
                Log.d(TAG, "Log as d");
            }
        }
    }
}
                """
                ),
                *stubs
            )
            .issues(GuardedLogLineDetector.WARNING)
            .run()
            .expectContains(GuardedLogLineDetector.GUARDED_LOG_INVOCATION_ERROR)
            .expectContains(createErrorCountString(0, 1))
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
            package com.android.bluetooth;

            public class FooConstants {
                public static final String TAG = "FooConstants";
                public static final boolean DBG = true;
                public static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);
            }
        """
            )
            .indented()

    private val stubs =
        arrayOf(
            logFramework,
            constantsHelper,
        )

    private fun createErrorCountString(errors: Int, warnings: Int): String {
        return "%d errors, %d warnings".format(errors, warnings)
    }

    private fun createFixDiff(lineNumber: Int, lines: String): String {
        // All lines are removed. Add enough spaces to match the below indenting
        val minusedlines = lines.replace("\n ", "\n               -  ")
        return """
               Fix for src/com/android/bluetooth/Foo.java line $lineNumber: Update log tag initialization:
               @@ -$lineNumber +$lineNumber
               - $minusedlines
               -
               """
            .trimIndent()
    }
}
