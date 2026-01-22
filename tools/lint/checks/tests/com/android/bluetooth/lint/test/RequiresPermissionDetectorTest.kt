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

package com.android.bluetooth.lint.test

import com.android.bluetooth.lint.RequiresPermissionDetector
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class RequiresPermissionDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = RequiresPermissionDetector()

    override fun getIssues(): List<Issue> =
        listOf(
            RequiresPermissionDetector
                .ISSUE_MISSING_OR_MISMATCHED_SEND_BROADCAST_REQUIRES_PERMISSION,
            RequiresPermissionDetector.ISSUE_MISSING_OR_MISMATCHED_REQUIRES_PERMISSION_ANNOTATION,
            RequiresPermissionDetector.ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION,
        )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    fun testAidlMethodWithoutRequiresPermission_NoEnforcementNeeded_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(): IFoo.Stub() {
                override fun foo() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAidlMethodWithValuePermission_MissingImplementationAnnotation_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(): IFoo.Stub() {
                override fun connect() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:3: Error: Method FooBinder.connect must have an equivalent @RequiresPermission annotation to the one in the super method. Expected: {allOf=[android.permission.BLUETOOTH_CONNECT]} but found: [none]. [MissingOrMismatchedRequiresPermissionAnnotation]
                    override fun connect() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithValuePermission_MatchingAnnotationNoEnforcement_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:4: Error: Method FooBinder.connect has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    override fun connect() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithValuePermission_MissingAnnotationButHasEnforcement_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                override fun connect() {
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAidlMethodWithValuePermission_MissingAnnotationButHasWrongEnforcement_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                override fun connect() {
                    // Super method requires BLUETOOTH_CONNECT, but this enforces SCAN
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:4: Error: Method FooBinder.connect must have an equivalent @RequiresPermission annotation to the one in the super method. Expected: {allOf=[android.permission.BLUETOOTH_CONNECT]} but found: [none]. [MissingOrMismatchedRequiresPermissionAnnotation]
                    override fun connect() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithValuePermission_MissingAnnotationButHasEnforcementInHelper_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val util: Util): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun getServiceAndEnforceConnect() {
                    util.checkConnectPermission()
                }
                override fun connect() {
                    getServiceAndEnforceConnect()
                }
                override fun connectSecond() {
                    connect()
                }
            }
            class Util(val context: Context) {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun checkConnectPermission() {
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAidlMethodWithoutRequiresPermission_NoAnnotationButHasEnforcementInHelper_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val util: Util): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun getServiceAndEnforceConnect() {
                    util.checkConnectPermission()
                }
                override fun foo() {
                    getServiceAndEnforceConnect()
                }
            }
            class Util(val context: Context) {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun checkConnectPermission() {
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:8: Error: Method FooBinder.foo is missing a @RequiresPermission annotation or it's too narrow. It calls APIs that require {allOf=[android.permission.BLUETOOTH_CONNECT]} but is only annotated with [none]. [IncorrectRequiresPermissionPropagation]
                    override fun foo() {
                                 ~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithValuePermission_MatchingAnnotationAndEnforcement_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAidlMethodWithShortValuePermission_MatchingAnnotationAndEnforcement_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.annotation.RequiresPermission
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAidlMethodWithAllOfPermission_MismatchedAnnotationValues_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun scanAndAdvertise() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:5: Error: Method FooBinder.scanAndAdvertise must have an equivalent @RequiresPermission annotation to the one in the super method. Expected: {allOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_ADVERTISE]} but found: {allOf=[android.permission.BLUETOOTH_CONNECT]}. [MissingOrMismatchedRequiresPermissionAnnotation]
                    override fun scanAndAdvertise() {
                                 ~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithAllOfPermission_MatchingAnnotationAndFullEnforcement_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(allOf = [
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE
                ])
                override fun scanAndAdvertise() {
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN, null)
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAidlMethodWithAllOfPermission_MatchingAnnotationButPartialEnforcement_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(allOf = [
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE
                ])
                override fun scanAndAdvertise() {
                    // Only enforces one of the two required permissions
                    context.enforceCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:8: Error: Method FooBinder.scanAndAdvertise has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_ADVERTISE]} but only calls APIs requiring {allOf=[android.permission.BLUETOOTH_SCAN]}. [IncorrectRequiresPermissionPropagation]
                    override fun scanAndAdvertise() {
                                 ~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithAnyOfPermission_MismatchedAnnotationValues_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(): IFoo.Stub() {
                @android.annotation.RequiresPermission(anyOf = [
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADMIN
                ])
                override fun disable() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:7: Error: Method FooBinder.disable must have an equivalent @RequiresPermission annotation to the one in the super method. Expected: {anyOf=[android.permission.BLUETOOTH_CONNECT, android.permission.BLUETOOTH_ADMIN]} but found: {anyOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_ADMIN]}. [MissingOrMismatchedRequiresPermissionAnnotation]
                    override fun disable() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testPermissionEnforcementViaPermissionChecker_CorrectlyAnnotatedAndEnforced_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            import android.content.PermissionChecker
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    PermissionChecker.checkCallingOrSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testPermissionEnforcementViaPermissionManager_CorrectlyAnnotatedAndEnforced_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.permission.PermissionManager
            class FooBinder(val permissionManager: PermissionManager): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    permissionManager.checkPermissionForDataDeliveryFromDataSource(android.Manifest.permission.BLUETOOTH_CONNECT, null, "connect")
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testAllOf_MatchingAnnotationButIncorrectAnyOfInsteadOfAllOf_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(): IFoo.Stub() {
                @android.annotation.RequiresPermission(anyOf = [
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE
                ])
                override fun scanAndAdvertise() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:7: Error: Method FooBinder.scanAndAdvertise must have an equivalent @RequiresPermission annotation to the one in the super method. Expected: {allOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_ADVERTISE]} but found: {anyOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_ADVERTISE]}. [MissingOrMismatchedRequiresPermissionAnnotation]
                    override fun scanAndAdvertise() {
                                 ~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithValuePermission_CallsUnrelatedMethod_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    doSomethingElse()
                }
                private fun doSomethingElse() {}
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:5: Error: Method FooBinder.connect has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    override fun connect() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testAidlMethodWithValuePermission_CallsMethodEnforcingPermission_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            import android.annotation.RequiresPermission
            class FooBinder(val context: Context): IFoo.Stub() {
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    enforceBluetoothConnect()
                }

                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                private fun enforceBluetoothConnect() {
                    context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BLUETOOTH_CONNECT, null)
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testBinderClearCallingIdentity_EnforcementInsideBlock_Ignored_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            import android.os.Binder
            class FooBinder(val context: Context): IFoo.Stub() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                override fun connect() {
                    val token = Binder.clearCallingIdentity()
                    try {
                        // This enforcement should be ignored by the detector
                        context.enforceCallingOrSelfPermission(
                            android.Manifest.permission.BLUETOOTH_CONNECT, null)
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:6: Error: Method FooBinder.connect has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    override fun connect() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testBinderClearCallingIdentity_EnforcementOutsideBlock_Recognized_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.content.Context
            import android.os.Binder
            class FooBinder(val context: Context, val scanHelper: ScanHelper): IFoo.Stub() {
                @android.annotation.RequiresPermission(allOf = [
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE
                ])
                override fun scanAndAdvertise() {
                    context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BLUETOOTH_ADVERTISE, null)

                    val token = Binder.clearCallingIdentity()
                    try {
                        // The permission on this helper should be ignored by the detector.
                        scanHelper.doSomethingThatNeedsScan()
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }

                    // Enforce SCAN outside the cleared block.
                    context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BLUETOOTH_SCAN, null)
                }
            }
            interface ScanHelper {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                fun doSomethingThatNeedsScan()
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testClientSidePropagation_UnnecessaryAnnotation_TooBroad_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.annotation.RequiresPermission
            class DataManager {
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun doNothing() {
                    // This method does not call anything requiring a permission.
                }
            }
        """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/DataManager.kt:5: Error: Method DataManager.doNothing has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    fun doNothing() {
                        ~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testClientSidePropagation_CalleeTooBroad_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.annotation.RequiresPermission
            class DataManager {
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun doConnect(helper: ConnectionHelper) {
                    helper.connectToBluetooth()
                }
            }

            class ConnectionHelper {
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun connectToBluetooth() { /* ... */ }
            }
        """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/DataManager.kt:12: Error: Method ConnectionHelper.connectToBluetooth has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    fun connectToBluetooth() { /* ... */ }
                        ~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testClientSidePropagation_CallerTooNarrow_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.annotation.RequiresPermission
            class DataManager {
                fun doConnect(helper: ConnectionHelper) {
                    helper.connectToBluetooth()
                }
            }
            class ConnectionHelper {
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun connectToBluetooth() { /* ... */ }
            }
        """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/DataManager.kt:4: Error: Method DataManager.doConnect is missing a @RequiresPermission annotation or it's too narrow. It calls APIs that require {allOf=[android.permission.BLUETOOTH_CONNECT]} but is only annotated with [none]. [IncorrectRequiresPermissionPropagation]
                    fun doConnect(helper: ConnectionHelper) {
                        ~~~~~~~~~
                src/test/pkg/DataManager.kt:10: Error: Method ConnectionHelper.connectToBluetooth has a broader @RequiresPermission annotation than necessary. It is annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    fun connectToBluetooth() { /* ... */ }
                        ~~~~~~~~~~~~~~~~~~
                2 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testClientSidePropagation_AnyOfAnnotation_Correct_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.annotation.RequiresPermission
            import android.content.Context
            class DataManager {
                @RequiresPermission(anyOf = [
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ])
                fun disableBluetooth(helper: ConnectionHelper) {
                    helper.disable()
                }
            }
            interface ConnectionHelper {
                @RequiresPermission(anyOf = [
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_ADMIN
                ])
                fun disable()
            }
        """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testClientSidePropagation_CalleeHasEnforcePermission_Recognized_Passes() {
        lint()
            .files(
                kotlin(
                        """
                package test.pkg
                import android.annotation.EnforcePermission
                import android.annotation.RequiresPermission
                class DataManager {
                    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    fun doConnect(helper: ConnectionHelper) {
                        helper.enforceAndConnect()
                    }
                }
                interface ConnectionHelper {
                    @EnforcePermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    fun enforceAndConnect()
                }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testClientSidePropagation_AnyOfCallee_CallerTooNarrow_Fails() {
        lint()
            .files(
                kotlin(
                        """
                package test.pkg
                import android.annotation.RequiresPermission
                class DataManager {
                    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    fun disableBluetooth(helper: ConnectionHelper) {
                        helper.disable()
                    }
                }
                class ConnectionHelper {
                    @RequiresPermission(anyOf = [
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_ADMIN
                    ])
                    fun disable() { /* ... */ }
                }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/DataManager.kt:5: Error: Method DataManager.disableBluetooth is missing a @RequiresPermission annotation or it's too narrow. It calls APIs that require {anyOf=[android.permission.BLUETOOTH_CONNECT, android.permission.BLUETOOTH_ADMIN]} but is only annotated with {allOf=[android.permission.BLUETOOTH_SCAN]}. [IncorrectRequiresPermissionPropagation]
                    fun disableBluetooth(helper: ConnectionHelper) {
                        ~~~~~~~~~~~~~~~~
                src/test/pkg/DataManager.kt:14: Error: Method ConnectionHelper.disable has a broader @RequiresPermission annotation than necessary. It is annotated with {anyOf=[android.permission.BLUETOOTH_CONNECT, android.permission.BLUETOOTH_ADMIN]} but only calls APIs requiring [none]. [IncorrectRequiresPermissionPropagation]
                    fun disable() { /* ... */ }
                        ~~~~~~~
                2 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testPermissionEnforcement_ViaPermissionMethodAndPermissionName_MatchingAnnotation_Passes() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(val util: Util): IFoo.Stub() {
                override fun connect() {
                    util.checkConnectPermission()
                }
            }
            class Util() {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun checkConnectPermission() {
                    checkPermissionForDelivery("android.permission.BLUETOOTH_CONNECT")
                }
                @android.annotation.PermissionMethod
                fun checkPermissionForDelivery(@android.annotation.PermissionName permission: String) {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testPermissionEnforcement_ViaPermissionMethodMissingPermissionName_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            class FooBinder(val util: Util): IFoo.Stub() {
                override fun connect() {
                    util.checkConnectPermission()
                }
            }
            class Util() {
                @android.annotation.PermissionMethod
                fun checkConnectPermission() {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:3: Error: Method FooBinder.connect must have an equivalent @RequiresPermission annotation to the one in the super method. Expected: {allOf=[android.permission.BLUETOOTH_CONNECT]} but found: [none]. [MissingOrMismatchedRequiresPermissionAnnotation]
                    override fun connect() {
                                 ~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testPermissionEnforcement_ViaPermissionMethodAndPermissionName_WrongAnnotation_Fails() {
        lint()
            .files(
                kotlin(
                        """
            package test.pkg
            import android.permission.PermissionManager
            class FooBinder(val util: Util): IFoo.Stub() {
                override fun connect() {
                    util.checkConnectPermission()
                }
            }
            class Util(val permissionManager: PermissionManager) {
                @android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                fun checkConnectPermission() {
                    checkPermissionForDelivery("android.permission.BLUETOOTH_SCAN")
                }
                @android.annotation.PermissionMethod
                fun checkPermissionForDelivery(@android.annotation.PermissionName permission: String) {
                }
            }
            """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/FooBinder.kt:10: Error: Method Util.checkConnectPermission is missing a @RequiresPermission annotation or it's too narrow. It calls APIs that require {allOf=[android.permission.BLUETOOTH_SCAN]} but is only annotated with {allOf=[android.permission.BLUETOOTH_CONNECT]}. [IncorrectRequiresPermissionPropagation]
                    fun checkConnectPermission() {
                        ~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testLambdaAndDirectCall_PermissionsAreCorrect_Passes() {
        lint()
            .files(
                java(
                        """
            package test.pkg;
            import android.annotation.RequiresPermission;
            import android.content.Context;
            class FooBinder extends IFoo.Stub {
                private final Context context;
                public FooBinder(Context context) {
                    this.context = context;
                }
                @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                private Service getServiceAndEnforceConnect() {
                    context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BLUETOOTH_CONNECT, null);
                    return new Service();
                }
                public void post(Service service, Consumer<Service> action) {
                    action.run();
                }
                @Override
                public void connect() {
                    post(getServiceAndEnforceConnect(), s -> s.connectNow());
                }
            }
            class Service {
                public void connectNow() {}
            }
            """
                    )
                    .indented(),
                java(
                        """
                package java.util.function;
                public interface Consumer<T> {
                    void accept(T t);
                }
                """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testBroadcastPermissionMismatch_Fails() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            const val ACTION_SCAN = "test.ACTION_SCAN"
                        }
                        fun testMismatch(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            context.sendBroadcast(intent)
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/MyManager.kt:12: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN]} but call is protected with [none]. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendBroadcast(intent)
                                ~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testBroadcastPermissionMismatch_Passes() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            const val ACTION_SCAN = "test.ACTION_SCAN"
                        }
                        fun scanViaIntentDirectConstructor(context: Context) {
                            context.sendBroadcast(Intent(ACTION_SCAN), android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                        fun scanViaIntentConstructor(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            context.sendBroadcast(intent, android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                        fun scanViaIntentDirectConstructorSetAction(context: Context) {
                            context.sendBroadcast(Intent().setAction(ACTION_SCAN), android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                        fun scanViaIntentSetAction(context: Context) {
                            val intent = Intent()
                            intent.setAction(ACTION_SCAN)
                            context.sendBroadcast(intent, android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testBroadcastAsUserPermissionMismatch_Fails() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.os.UserHandle
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            const val ACTION_SCAN = "test.ACTION_SCAN"
                        }
                        fun testMismatch(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            context.sendBroadcastAsUser(intent, null as UserHandle?)
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/MyManager.kt:13: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN]} but call is protected with [none]. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendBroadcastAsUser(intent, null as UserHandle?)
                                ~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testBroadcastAsUserPermissionMismatch_Passes() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.os.UserHandle
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            const val ACTION_SCAN = "test.ACTION_SCAN"
                        }
                        fun testMismatch(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            context.sendBroadcastAsUser(intent, null as UserHandle?, android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testOrderedAndStickyBroadcasts_PermissionMismatch_Fails() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.os.UserHandle
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            const val ACTION_SCAN = "test.ACTION_SCAN"
                        }
                        fun testOrdered(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            // Missing permission argument
                            context.sendOrderedBroadcast(intent, null)
                        }
                        fun testSticky(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            // Method has no permission argument, so this is always a mismatch
                            context.sendStickyBroadcast(intent)
                        }
                        fun testOrderedAsUser(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            // Missing permission argument
                            context.sendOrderedBroadcastAsUser(intent, null as UserHandle?, null)
                        }
                        fun testStickyAsUser(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            // Method has no permission argument, so this is always a mismatch
                            context.sendStickyBroadcastAsUser(intent, null as UserHandle?)
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/MyManager.kt:14: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN]} but call is protected with [none]. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendOrderedBroadcast(intent, null)
                                ~~~~~~~~~~~~~~~~~~~~
                src/test/pkg/MyManager.kt:19: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN]} but call is protected with [none]. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendStickyBroadcast(intent)
                                ~~~~~~~~~~~~~~~~~~~
                src/test/pkg/MyManager.kt:24: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN]} but call is protected with [none]. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendOrderedBroadcastAsUser(intent, null as UserHandle?, null)
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~
                src/test/pkg/MyManager.kt:29: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN]} but call is protected with [none]. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendStickyBroadcastAsUser(intent, null as UserHandle?)
                                ~~~~~~~~~~~~~~~~~~~~~~~~~
                4 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testOrderedBroadcast_PermissionMatch_Passes() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.os.UserHandle
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            const val ACTION_SCAN = "test.ACTION_SCAN"
                        }
                        fun testOrdered(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            context.sendOrderedBroadcast(intent, android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                        fun testOrderedAsUser(context: Context) {
                            val intent = Intent(ACTION_SCAN)
                            context.sendOrderedBroadcastAsUser(intent, null as UserHandle?, android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testBroadcastMultiplePermissions_Mismatch_Fails() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @android.annotation.RequiresPermission(allOf = [
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_ADVERTISE
                            ])
                            const val ACTION_MULTI = "test.ACTION_MULTI"
                        }
                        fun testMismatch(context: Context) {
                            val intent = Intent(ACTION_MULTI)
                            context.sendBroadcastMultiplePermissions(intent, arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT))
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expect(
                """
                src/test/pkg/MyManager.kt:15: Error: Broadcast action requires {allOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_ADVERTISE]} but call is protected with {allOf=[android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_CONNECT]}. [MissingOrMismatchedSendBroadcastRequiresPermission]
                        context.sendBroadcastMultiplePermissions(intent, arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT))
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    fun testBroadcastMultiplePermissions_Match_Passes() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @android.annotation.RequiresPermission(allOf = [
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_ADVERTISE
                            ])
                            const val ACTION_MULTI = "test.ACTION_MULTI"
                        }
                        fun testMatchMultiplePermissions(context: Context) {
                            val intent = Intent(ACTION_MULTI)
                            context.sendBroadcastMultiplePermissions(intent, arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE))
                        }
                        fun testMatchWithMultiplePermissions(context: Context) {
                            val intent = Intent(ACTION_MULTI)
                            context.sendBroadcastWithMultiplePermissions(intent, arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE))
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    fun testBroadcastAsUserMultiplePermissions_Match_Passes() {
        lint()
            .files(
                kotlin(
                        """
                    package test.pkg
                    import android.content.Context
                    import android.content.Intent
                    import android.os.UserHandle
                    import android.annotation.RequiresPermission
                    class MyManager {
                        companion object {
                            @android.annotation.RequiresPermission(allOf = [
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_ADVERTISE
                            ])
                            const val ACTION_MULTI = "test.ACTION_MULTI"
                        }
                        fun testMatch(context: Context) {
                            val intent = Intent(ACTION_MULTI)
                            context.sendBroadcastAsUserMultiplePermissions(intent, null as UserHandle?, arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE))
                        }
                    }
                    """
                    )
                    .indented(),
                *stubs,
            )
            .run()
            .expectClean()
    }

    private val manifestPermissionStub: TestFile =
        java(
                """
        package android.Manifest;
        class permission {
            public static final String BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN";
            public static final String BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE";
            public static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
            public static final String BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";
        }
        """
            )
            .indented()

    private val requiresPermissionAnnotationStub: TestFile =
        java(
                """
        package android.annotation;
        public @interface RequiresPermission {
            String value() default "";
            String[] allOf() default {};
            String[] anyOf() default {};
        }
        """
            )
            .indented()

    private val enforcePermissionAnnotationStub: TestFile =
        java(
                """
        package android.annotation;
        public @interface EnforcePermission {
            String value() default "";
            String[] allOf() default {};
            String[] anyOf() default {};
        }
        """
            )
            .indented()

    private val contextStub: TestFile =
        java(
                """
        package android.content;
        import android.os.UserHandle;
        public class Context {
            public void enforceCallingOrSelfPermission(String permission, String message) {}
            public void sendBroadcast(Intent intent) {}
            public void sendBroadcast(Intent intent, String receiverPermission) {}
            public void sendBroadcastAsUser(Intent intent, UserHandle user) {}
            public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission) {}
            public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions) {}
            public void sendBroadcastWithMultiplePermissions(Intent intent, String[] receiverPermissions) {}
            public void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user, String[] receiverPermissions) {}
            public void sendOrderedBroadcast(Intent intent, String receiverPermission) {}
            public void sendStickyBroadcast(Intent intent) {}
            public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission) {}
            public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {}
        }
        """
            )
            .indented()

    private val userHandleStub: TestFile =
        java(
                """
        package android.os;
        public class UserHandle {}
        """
            )
            .indented()

    private val broadcastStub: TestFile =
        java(
                """
        package android.content;
        public class BroadcastReceiver {
            public void onReceive(Context context, Intent intent) {}
        }
        """
            )
            .indented()

    private val permissionCheckerStub: TestFile =
        java(
                """
        package android.content;
        public class PermissionChecker {
            public static int checkCallingOrSelfPermission(Context context, String permission) { return 0; }
        }
        """
            )
            .indented()

    private val permissionManagerStub: TestFile =
        java(
                """
        package android.permission;
        public class PermissionManager {
            public int checkPermissionForDataDeliveryFromDataSource(
                    String permission,
                    AttributionSource attributionSource,
                    String message) {
                return 0;
            }
        }
        """
            )
            .indented()

    private val permissionMethodAnnotationStub: TestFile =
        java(
                """
        package android.annotation;
        public @interface PermissionMethod {
            String value() default "";
            String[] allOf() default {};
            String[] anyOf() default {};
        }
        """
            )
            .indented()

    private val permissionNameAnnotationStub: TestFile =
        java(
                """
        package android.annotation;
        public @interface PermissionName {}
        """
            )
            .indented()

    private val binderStub: TestFile =
        java(
                """
        package android.os;
        public class Binder {
            public static long clearCallingIdentity() { return 0L; }
            public static void restoreCallingIdentity(long token) {}
        }
        """
            )
            .indented()

    private val intentStub: TestFile =
        java(
                """
        package android.content;
        public class Intent {
            public Intent(String action) {}
            public Intent setAction(String action) { return this; }
        }
        """
            )
            .indented()

    private val interfaceIFooStub: TestFile =
        java(
                """
        package test.pkg;
        import android.annotation.RequiresPermission;
        public interface IFoo extends android.os.IInterface {
            public static class Default extends android.os.Binder implements IFoo {
                @Override public void connect() {}
                @Override public void connectSecond() {}
                @Override public void scanAndAdvertise() {}
                @Override public void disable() {}
            }
            public static abstract class Stub extends android.os.Binder implements IFoo {
            }
            public void foo();
            @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            public void connect();
            @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            public void connectSecond();
            @RequiresPermission(allOf = {
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            })
            public void scanAndAdvertise();
            @RequiresPermission(anyOf = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADMIN
            })
            public void disable();
        }
        """
            )
            .indented()

    private val stubs =
        arrayOf(
            manifestPermissionStub,
            requiresPermissionAnnotationStub,
            enforcePermissionAnnotationStub,
            contextStub,
            userHandleStub,
            broadcastStub,
            intentStub,
            permissionCheckerStub,
            permissionManagerStub,
            permissionMethodAnnotationStub,
            permissionNameAnnotationStub,
            binderStub,
            interfaceIFooStub,
        )
}
