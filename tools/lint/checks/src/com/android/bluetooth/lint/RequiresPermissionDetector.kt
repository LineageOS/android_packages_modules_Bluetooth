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

package com.android.bluetooth.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.getUMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * A lint detector that ensures correctness for `@RequiresPermission` annotations.
 *
 * This detector performs two main functions:
 * 1. Override Verification: For a method that overrides a super-method annotated with
 *    `@RequiresPermission` (such as an AIDL interface method), this check verifies that the
 *    permission contract is fulfilled. The contract can be satisfied in two ways:
 * - The overriding method itself has a semantically identical `@RequiresPermission` annotation.
 * - The body of the overriding method calls other APIs that, in aggregate, require or enforce the
 *   same permissions as the super-method. This allows implementations to delegate
 *   permission-sensitive logic to helper methods without redundantly annotating the override
 *   itself.
 *
 * (See `ISSUE_MISSING_OR_MISMATCHED_REQUIRES_PERMISSION_ANNOTATION`)
 * 2. Permission Propagation: For any method, it checks that its `@RequiresPermission` annotation
 *    correctly reflects the permissions required by the methods it calls and the permission checks
 *    it performs.
 * - It reports an error if the annotation is "too narrow" (i.e., it fails to declare a permission
 *   that a called API requires).
 * - It also reports an error if the annotation is "too broad" (i.e., it declares a permission that
 *   is not actually required or enforced by its body).
 *
 * (See `ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION`)
 *
 * The detector correctly handles `allOf` and `anyOf` permission sets and ignores permission checks
 * made within a `Binder.clearCallingIdentity()` block.
 */
class RequiresPermissionDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        RequiresPermissionVisitor(context)

    private inner class RequiresPermissionVisitor(private val context: JavaContext) :
        UElementHandler() {
        override fun visitMethod(node: UMethod) {
            if (context.evaluator.isAbstract(node)) {
                return
            }

            val containingClass = node.containingClass ?: return
            if (context.evaluator.inheritsFrom(containingClass, CLASS_BINDER, true)) {
                val isBinderMethod = node.name == "onTransact" || node.name == "dump"
                val isGeneratedBinderClass =
                    containingClass.name?.matches(BINDER_INTERNALS_REGEX) == true

                if (isBinderMethod || isGeneratedBinderClass) {
                    return
                }
            }

            val superPermissions = getRequiredPermissionsFromSuper(context, node)
            val enforcedPermissions =
                PermissionEnforcementVisitor(context)
                    .apply { node.accept(this) }
                    .enforcedPermissions

            if (!superPermissions.isEmpty() && enforcedPermissions == superPermissions) {
                // Allow an unannotated override if it correctly enforces the required permissions
                return
            }

            val declaredPermissions = getRequiredPermissionsFromMethod(context, node)
            val nodeName = (node.uastParent as? PsiClass)?.name + "." + node.name
            if (!superPermissions.isEmpty() && declaredPermissions != superPermissions) {
                context.report(
                    ISSUE_MISSING_OR_MISMATCHED_REQUIRES_PERMISSION_ANNOTATION,
                    node,
                    context.getNameLocation(node),
                    "Method `$nodeName` must have an equivalent @RequiresPermission annotation to the one in " +
                        "the super method. Expected: $superPermissions but found: $declaredPermissions.",
                )
                return
            }

            if (declaredPermissions.isEmpty() && enforcedPermissions.isEmpty()) {
                return
            }

            val tooNarrow = !declaredPermissions.covers(enforcedPermissions)
            val tooBroad = !enforcedPermissions.covers(declaredPermissions)

            if (tooNarrow) {
                context.report(
                    ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION,
                    node,
                    context.getNameLocation(node),
                    "Method `$nodeName` is missing a @RequiresPermission annotation or it's too narrow. " +
                        "It calls APIs that require $enforcedPermissions but is only annotated with $declaredPermissions.",
                )
            } else if (tooBroad) {
                context.report(
                    ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION,
                    node,
                    context.getNameLocation(node),
                    "Method `$nodeName` has a broader @RequiresPermission annotation than necessary. " +
                        "It is annotated with $declaredPermissions but only calls APIs requiring $enforcedPermissions.",
                )
            }
        }
    }

    private inner class PermissionEnforcementVisitor(private val context: JavaContext) :
        AbstractUastVisitor() {
        val enforcedPermissions = PermissionHolder()
        private var isIdentityCleared = false

        override fun visitCallExpression(node: UCallExpression): Boolean {
            val method = node.resolve() ?: return true

            val qualifiedName = method.containingClass?.qualifiedName
            if (qualifiedName == CLASS_BINDER) {
                when (method.name) {
                    "clearCallingIdentity" -> {
                        isIdentityCleared = true
                        return true
                    }
                    "restoreCallingIdentity" -> {
                        isIdentityCleared = false
                        return true
                    }
                }
            }

            if (isIdentityCleared) {
                return true
            }

            // Enforcement of a method annotated `@RequiresPermission` is done by
            // `RequiresPermissionVisitor`
            context.evaluator.getAnnotation(method, ANNOTATION_REQUIRES_PERMISSION)?.let {
                enforcedPermissions.addAll(parseAnnotation(context, it))
                return true
            }

            // Enforcement of a method annotated `@EnforcePermission` is done by
            // `EnforcePermissionDetector`
            context.evaluator.getAnnotation(method, ANNOTATION_ENFORCE_PERMISSION)?.let {
                enforcedPermissions.addAll(parseAnnotation(context, it))
                return true
            }

            checkEnforcement(node, method)

            return true
        }

        private fun checkEnforcement(node: UCallExpression, method: PsiMethod) {
            fun extractPermissionFromArgument(node: UCallExpression, index: Int) {
                node.valueArguments.getOrNull(index)?.let { arg ->
                    ConstantEvaluator.evaluate(context, arg)?.toString()?.let {
                        enforcedPermissions.allOf.add(it)
                    }
                }
            }
            if (
                context.evaluator.isMemberInSubClassOf(method, CLASS_CONTEXT, false) &&
                    method.name.matches(CONTEXT_ENFORCEMENT_METHOD_REGEX)
            ) {
                extractPermissionFromArgument(node, 0)
            } else if (
                context.evaluator.isMemberInSubClassOf(method, CLASS_PERMISSION_CHECKER, false) &&
                    method.name.matches(PERMISSION_CHECKER_ENFORCEMENT_METHOD_REGEX)
            ) {
                extractPermissionFromArgument(node, 1)
            } else if (
                context.evaluator.isMemberInSubClassOf(method, CLASS_PERMISSION_MANAGER, false) &&
                    method.name.matches(PERMISSION_MANAGER_ENFORCEMENT_METHOD_REGEX)
            ) {
                extractPermissionFromArgument(node, 0)
            } else if (isPermissionMethodCall(node)) {
                node.resolve()?.getUMethod()?.uastParameters?.forEachIndexed { index, parameter ->
                    if (hasPermissionNameAnnotation(parameter)) {
                        extractPermissionFromArgument(node, index)
                    }
                }
            }
        }
    }

    private fun getRequiredPermissionsFromSuper(
        context: JavaContext,
        method: UMethod,
    ): PermissionHolder {
        val permissionHolder = PermissionHolder()
        method.javaPsi.findSuperMethods().forEach { superMethod ->
            permissionHolder.addAll(getRequiredPermissionsFromMethod(context, superMethod))
        }
        return permissionHolder
    }

    private fun getRequiredPermissionsFromMethod(
        context: JavaContext,
        method: PsiMethod,
    ): PermissionHolder {
        return context.evaluator.getAnnotation(method, ANNOTATION_REQUIRES_PERMISSION)?.let {
            parseAnnotation(context, it)
        } ?: PermissionHolder()
    }

    private fun parseAnnotation(context: JavaContext, annotation: UAnnotation): PermissionHolder {
        val holder = PermissionHolder()

        fun getPermissions(value: UExpression?, context: JavaContext): Set<String> {
            if (value == null) return emptySet()

            fun extractStringFromPsi(psi: PsiElement?): String? {
                return when (psi) {
                    is PsiReferenceExpression -> {
                        val text = psi.text
                        if (text.contains(".permission.")) text else null
                    }
                    is PsiLiteralExpression -> {
                        psi.value as? String
                    }
                    else -> null
                }
            }

            if (value is UCallExpression && value.kind.name == "array_initializer") {
                return value.valueArguments
                    .mapNotNull { arg ->
                        val evaluated = ConstantEvaluator.evaluate(context, arg)
                        evaluated?.toString() ?: extractStringFromPsi(arg.sourcePsi)
                    }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }

            val evaluated = ConstantEvaluator.evaluate(context, value)
            val result = evaluated?.toString() ?: extractStringFromPsi(value.sourcePsi)
            return if (result != null && result.isNotEmpty()) {
                setOf(result)
            } else {
                emptySet()
            }
        }

        val valuePerms = getPermissions(annotation.findAttributeValue("value"), context)
        val allOfPerms = getPermissions(annotation.findAttributeValue("allOf"), context)
        val anyOfPerms = getPermissions(annotation.findAttributeValue("anyOf"), context)

        holder.allOf.addAll(valuePerms)
        holder.allOf.addAll(allOfPerms)
        holder.anyOf.addAll(anyOfPerms)

        return holder
    }

    private data class PermissionHolder(
        val allOf: MutableSet<String> = mutableSetOf(),
        val anyOf: MutableSet<String> = mutableSetOf(),
    ) {
        fun isEmpty() = allOf.isEmpty() && anyOf.isEmpty()

        fun addAll(other: PermissionHolder) {
            allOf.addAll(other.allOf)
            anyOf.addAll(other.anyOf)
        }

        fun covers(other: PermissionHolder): Boolean {
            val allMet = allOf.containsAll(other.allOf)

            val anyMet =
                if (other.anyOf.isEmpty()) {
                    true
                } else {
                    other.anyOf.any { it in allOf || it in anyOf }
                }
            return allMet && anyMet
        }

        override fun toString(): String {
            if (isEmpty()) return "[none]"
            val parts = mutableListOf<String>()
            if (allOf.isNotEmpty()) parts.add("allOf=$allOf")
            if (anyOf.isNotEmpty()) parts.add("anyOf=$anyOf")
            return parts.joinToString(separator = ", ", prefix = "{", postfix = "}")
        }
    }

    companion object {
        private val BINDER_INTERNALS_REGEX = "^(Stub|Default|Proxy)$".toRegex()
        private val CONTEXT_ENFORCEMENT_METHOD_REGEX =
            "^(enforce|check)(Calling)?(OrSelf)?Permission$".toRegex()
        private val PERMISSION_CHECKER_ENFORCEMENT_METHOD_REGEX = "^check.*Permission$".toRegex()
        private val PERMISSION_MANAGER_ENFORCEMENT_METHOD_REGEX = "^checkPermission.*".toRegex()

        @JvmField
        val ISSUE_MISSING_OR_MISMATCHED_REQUIRES_PERMISSION_ANNOTATION =
            Issue.create(
                id = "MissingOrMismatchedRequiresPermissionAnnotation",
                briefDescription = "Missing or mismatched @RequiresPermission on implementation.",
                explanation =
                    """
                An overriding method must be annotated with @RequiresPermission and it must be
                equivalent to the annotation on the super method.",
            """
                        .trimIndent(),
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.ERROR,
                implementation =
                    Implementation(RequiresPermissionDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )

        @JvmField
        val ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION =
            Issue.create(
                id = "IncorrectRequiresPermissionPropagation",
                briefDescription = "Incorrectly propagating @RequiresPermission",
                explanation =
                    """
                Methods that call other APIs requiring permissions must be annotated with their own
                @RequiresPermission annotation.
                This annotation must be specific enough to cover all permissions required by the
                APIs it calls (not "too narrow"), but should not declare permissions that are
                never used (not "too broad").
            """
                        .trimIndent(),
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.ERROR,
                implementation =
                    Implementation(RequiresPermissionDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
