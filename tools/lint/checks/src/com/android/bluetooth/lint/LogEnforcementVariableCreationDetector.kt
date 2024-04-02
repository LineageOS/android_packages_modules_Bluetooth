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

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UUnaryExpression
import org.jetbrains.uast.skipParenthesizedExprDown

/**
 * Lint check for creation of log enforcement variables in the Bluetooth stack
 *
 * Logging enforcement variables are not allowed to be _defined_, i.e.:
 *
 *     private static final Boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
 *     private static final Boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
 *     private static final Boolean DBG = true;
 *     private static final Boolean VDBG = false;
 *     private static final Boolean BDG = FooConstants.DBG;
 *
 * This is because the BT stack defines a process default log level, which allows the Android Log
 * framework (For Java, Kotlin, _and_ Native) to properly enforce log levels for us. Using our own
 * variables for enforcement causes confusion and inconsistency on log output, and worst case causes
 * double checks against the log level each time we log. Plus, leveraging the Log framework allows
 * things like runtime log level switches.
 *
 * The recommended fix is to _remove_ the creation of the variable. This fix is easy to make, but it
 * is hard to fix all the downstream usages of the variable.
 */
class LogEnforcementVariableCreationDetector : Detector(), SourceCodeScanner {
    private val LOG_ENFORCEMENT_VARS = listOf("DBG", "DEBUG", "VDBG", "VERBOSE", "D", "V")
    private val LOG_ENFORCEMENT_VAR_ENDINGS = listOf("DBG", "VDBG", "DEBUG", "VERBOSE")

    companion object {
        const val LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR =
            "Dont create log enforcement variables to enforce when a log should be made. The Log" +
                " framework does this check for you. Remove this variable and update any log" +
                " invocations to be unguarded."

        val ISSUE =
            Issue.create(
                id = "LogEnforcementVariableCreation",
                briefDescription = "Do not create log enforcement variables",
                explanation =
                    "The BT stack defines a process default log level, which allows the Android" +
                        " Log framework (For Java, Kotlin, _and_ Native) to properly enforce log" +
                        " levels for us. Using our own variables for enforcement causes inconsistency" +
                        " in log output and double checks against the log level each time we log." +
                        " Please delete this variable and use the Log functions unguarded in your" +
                        " code.",
                category = Category.CORRECTNESS,
                severity = Severity.ERROR,
                implementation =
                    Implementation(
                        LogEnforcementVariableCreationDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    ),
                androidSpecific = true,
            )
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UClass::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        return object : UElementHandler() {
            override fun visitClass(node: UClass) {
                if (isBluetoothClass(node)) {
                    for (field in node.fields) {
                        if (checkFieldForLogEnforcementVariable(field)) {
                            context.report(
                                issue = ISSUE,
                                scopeClass = field,
                                location = context.getNameLocation(field),
                                message = LOG_ENFORCEMENT_VARIABLE_USAGE_ERROR,
                                quickfixData =
                                    LintFix.create()
                                        .name("Remove log enforcement variable declaration")
                                        .replace()
                                        .range(context.getLocation(field))
                                        .with("")
                                        .build()
                            )
                        }
                    }
                }
            }
        }
    }

    /*
     * Checks a UField to see if its likely a declaration of a log enforcement variable
     *
     * Three checks are made to see if the field is a log enforcement variable:
     *     1. Is the name any of the common names used in the stack. These include things like
     *        DBG, VDBG, DEBUG, VERBOSE, D, V, etc., or variables that _end_ in _DBG or _VDBG
     *     2. Is the variable created assigned the a value based on a call to Log.isLoggable()
     *     3. Is the variable created assigned the value of another log enforcement variable, based
     *        on the naming scheme described in check 1 above.
     */
    private fun checkFieldForLogEnforcementVariable(field: UField): Boolean {
        val fieldType = field.getType()
        val fieldName = field.getName()
        val fieldInitializer = field.uastInitializer?.skipParenthesizedExprDown()
        return (fieldType.canonicalText == "boolean" ||
            fieldType.canonicalText == "java.lang.Boolean") &&
            (isLogEnforcementVariable(fieldName) ||
                (fieldInitializer != null &&
                    (checkExpressionForIsLoggableUsages(fieldInitializer) ||
                        checkExpressionForDebugVariableUsages(fieldInitializer))))
    }

    /*
     * Checks a string variable name to see if its one of the common names used in the stack for
     * log enforcement variables.
     *
     * These include things like DBG, VDBG, DEBUG, VERBOSE, D, V, etc., or variables that _end_ in
     * _DBG or _VDBG
     */
    private fun isLogEnforcementVariable(name: String): Boolean {
        val nameUpper = name.uppercase()
        return nameUpper in LOG_ENFORCEMENT_VARS ||
            LOG_ENFORCEMENT_VAR_ENDINGS.any { nameUpper.endsWith(it) }
    }

    /*
     * Checks an expression to see if it uses a log enforcement variable, based on the common names
     * used for log enforcement variables in the stack.
     */
    private fun checkExpressionForDebugVariableUsages(expression: UExpression): Boolean {
        when (expression) {
            is USimpleNameReferenceExpression -> {
                if (isLogEnforcementVariable(expression.identifier)) {
                    return true
                }
            }
            is UUnaryExpression -> {
                return checkExpressionForDebugVariableUsages(expression.operand)
            }
            is UBinaryExpression -> {
                return checkExpressionForDebugVariableUsages(expression.leftOperand) ||
                    checkExpressionForDebugVariableUsages(expression.rightOperand)
            }
            is UPolyadicExpression -> {
                for (subExpression in expression.operands) {
                    if (checkExpressionForDebugVariableUsages(subExpression)) {
                        return true
                    }
                }
                return false
            }
            is UQualifiedReferenceExpression -> {
                return checkExpressionForDebugVariableUsages(expression.selector)
            }
            is UParenthesizedExpression -> {
                return checkExpressionForDebugVariableUsages(expression.expression)
            }
        }

        return false
    }

    /*
     * Checks an expression to see if it uses the Log.isLoggable() function
     */
    private fun checkExpressionForIsLoggableUsages(expression: UExpression): Boolean {
        when (expression) {
            is UCallExpression -> {
                val resolvedMethod = expression.resolve()
                return resolvedMethod?.name == "isLoggable" &&
                    resolvedMethod.containingClass?.qualifiedName == "android.util.Log"
            }
            is UUnaryExpression -> {
                return checkExpressionForIsLoggableUsages(expression.operand)
            }
            is UBinaryExpression -> {
                return checkExpressionForIsLoggableUsages(expression.leftOperand) ||
                    checkExpressionForIsLoggableUsages(expression.rightOperand)
            }
            is UPolyadicExpression -> {
                for (subExpression in expression.operands) {
                    if (checkExpressionForIsLoggableUsages(subExpression)) {
                        return true
                    }
                }
                return false
            }
            is UQualifiedReferenceExpression -> {
                return checkExpressionForIsLoggableUsages(expression.receiver) ||
                    checkExpressionForIsLoggableUsages(expression.selector)
            }
            is UParenthesizedExpression -> {
                return checkExpressionForIsLoggableUsages(expression.expression)
            }
        }
        return false
    }
}
