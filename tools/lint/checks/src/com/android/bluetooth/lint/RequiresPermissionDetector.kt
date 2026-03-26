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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiReferenceExpression
import java.util.regex.Pattern
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UastFacade
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * A lint detector that ensures correctness for `@RequiresPermission` annotations.
 *
 * This detector reports three distinct issues:
 * 1. **Missing or Mismatched Override Annotation
 *    (`ISSUE_MISSING_OR_MISMATCHED_REQUIRES_PERMISSION_ANNOTATION`):** Checks that a method
 *    overriding a super-method annotated with `@RequiresPermission` has an equivalent
 *    `@RequiresPermission` annotation.
 *     - An exception is made if the overriding method, while unannotated, performs the exact
 *       runtime permission enforcement (e.g., `context.enforce...`) required by the super-method.
 * 2. **Incorrect Permission Propagation (`ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION`):**
 *    Verifies that a method's declared annotation accurately reflects the permissions required by
 *    the APIs it calls or the runtime checks it performs.
 *     - **Too Narrow:** Reported if a method calls APIs that require permissions (e.g., other
 *       methods annotated with `@RequiresPermission` or `@EnforcePermission`, or runtime checks
 *       like `context.enforceCallingOrSelfPermission`) but is not annotated, or its annotation does
 *       not cover all those required permissions.
 *     - **Too Broad:** Reported if a method is annotated with `@RequiresPermission` but its body
 *       does not actually call any APIs or perform any runtime checks that require those
 *       permissions.
 *     - This check ignores any permission-requiring calls made within a
 *       `Binder.clearCallingIdentity()` block.
 * 3. **Mismatched Broadcast Permission
 *    (`ISSUE_MISSING_OR_MISMATCHED_SEND_BROADCAST_REQUIRES_PERMISSION`):** Ensures that calls to
 *    `context.sendBroadcast()` (and variants like `sendBroadcastAsUser`) enforce a permission that
 *    matches the permission declared on the broadcast Intent's action string (via
 *    `@RequiresPermission`).
 */
class RequiresPermissionDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        RequiresPermissionVisitor(context)

    private inner class RequiresPermissionVisitor(private val context: JavaContext) :
        UElementHandler() {
        override fun visitMethod(node: UMethod) {
            if (context.evaluator.isAbstract(node)) return

            // Ignore certain types of Binder generated code
            if (isBinderInternals(context, node)) return

            // Ignore known-local methods which don't need to propagate
            if (isLocalInternals(context, node)) return

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
            val nodeName = "${node.containingClass?.name}.${node.name}"
            if (!superPermissions.isEmpty() && declaredPermissions != superPermissions) {
                context.report(
                    ISSUE_MISSING_OR_MISMATCHED_REQUIRES_PERMISSION_ANNOTATION,
                    node,
                    context.getNameLocation(node),
                    "Method `$nodeName` must have an equivalent @RequiresPermission annotation " +
                        "to the one in the super method. Expected: $superPermissions but found: " +
                        "$declaredPermissions.",
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
                    "Method `$nodeName` is missing a @RequiresPermission annotation or it's too " +
                        "narrow. It calls APIs that require $enforcedPermissions but is only " +
                        "annotated with $declaredPermissions.",
                )
            } else if (tooBroad) {
                context.report(
                    ISSUE_INCORRECT_REQUIRES_PERMISSION_PROPAGATION,
                    node,
                    context.getNameLocation(node),
                    "Method `$nodeName` has a broader @RequiresPermission annotation than " +
                        "necessary. It is annotated with $declaredPermissions but only calls " +
                        "APIs requiring $enforcedPermissions.",
                )
            }
        }

        private fun isBinderInternals(context: JavaContext, method: UMethod): Boolean {
            if (context.evaluator.inheritsFrom(method.containingClass, CLASS_BINDER, true)) {
                val isBinderMethod = method.name == "onTransact" || method.name == "dump"
                val isGeneratedBinderClass =
                    method.containingClass?.name?.matches(BINDER_INTERNALS_REGEX) == true
                if (isBinderMethod || isGeneratedBinderClass) {
                    return true
                }
            }
            return false
        }

        private fun isLocalInternals(context: JavaContext, method: UMethod): Boolean {
            if (context.evaluator.isMemberInSubClassOf(method, CLASS_BROADCAST_RECEIVER, false)) {
                if (method.name == "onReceive") return true
            }
            return false
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

            if (context.evaluator.isMemberInSubClassOf(method, CLASS_CONTEXT, false)) {
                val isSendBroadcast = method.name.matches(SEND_BROADCAST_REGEX)
                val isSendBroadcastAsUser = method.name.matches(SEND_BROADCAST_AS_USER_REGEX)
                if (isSendBroadcast || isSendBroadcastAsUser) {
                    checkBroadcastPermission(node, isSendBroadcastAsUser)
                }
            }

            listOf(*method.findSuperMethods(), method).forEach { m ->
                // Enforcement of `@RequiresPermission` is done via `RequiresPermissionVisitor`
                context.evaluator.getAnnotation(m, ANNOTATION_REQUIRES_PERMISSION)?.let {
                    enforcedPermissions.addAll(parseAnnotation(context, it))
                }

                // Enforcement of `@EnforcePermission` is done via `EnforcePermissionDetector`
                context.evaluator.getAnnotation(m, ANNOTATION_ENFORCE_PERMISSION)?.let {
                    enforcedPermissions.addAll(parseAnnotation(context, it))
                }
            }

            node.valueArguments.forEach { argument -> argument.accept(this) }

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
                method.getUMethod()?.uastParameters?.forEachIndexed { index, parameter ->
                    if (hasPermissionNameAnnotation(parameter)) {
                        extractPermissionFromArgument(node, index)
                    }
                }
            }
        }

        private fun checkBroadcastPermission(node: UCallExpression, isAsUser: Boolean) {
            val sourcePerm = parseBroadcastSourcePermission(node)
            val targetPerm = parseBroadcastTargetPermission(node, isAsUser)

            if (sourcePerm != targetPerm) {
                context.report(
                    ISSUE_MISSING_OR_MISMATCHED_SEND_BROADCAST_REQUIRES_PERMISSION,
                    node,
                    context.getNameLocation(node),
                    "Broadcast action requires $sourcePerm but call is protected with $targetPerm.",
                )
            }
        }

        private fun parseBroadcastSourcePermission(
            broadcastCall: UCallExpression
        ): PermissionHolder {
            val enclosingMethod = broadcastCall.getContainingUMethod() ?: return PermissionHolder()

            class IntentActionScanner : AbstractUastVisitor() {
                var lastSeenActionField: PsiElement? = null
                var foundBroadcastCall = false

                override fun visitCallExpression(node: UCallExpression): Boolean {
                    if (foundBroadcastCall) return true

                    node.valueArguments.forEach { argument -> argument.accept(this) }

                    if (node.sourcePsi == broadcastCall.sourcePsi) {
                        foundBroadcastCall = true
                        return true
                    }

                    val call = node.resolve() ?: return true

                    if (
                        // Case 1: val intent = new Intent("ACTION_STRING")
                        (call.isConstructor &&
                            call.containingClass?.qualifiedName == CLASS_INTENT) ||
                            // Case 2: intent.setAction("ACTION_STRING")
                            (call.name == "setAction" &&
                                context.evaluator.isMemberInSubClassOf(call, CLASS_INTENT, false))
                    ) {
                        lastSeenActionField = node.valueArguments.getOrNull(0)?.tryResolve()
                    }

                    return true
                }
            }

            val scanner = IntentActionScanner().apply { enclosingMethod.accept(this) }

            val actionField = scanner.lastSeenActionField
            if (!scanner.foundBroadcastCall || actionField == null) {
                // Couldn't find broadcast call or track Intent action. This can happen if the
                // intent is passed as a parameter or if 'new Intent()' was called with no action.
                return PermissionHolder()
            }

            var ann: UAnnotation? =
                actionField.toUElementOfType<UField>()?.getRequiresPermissionAnnotation()
            if (ann == null) {
                val sourcePsi = UastFacade.convertElementWithParent(actionField, null)?.sourcePsi
                val uAnnotated = sourcePsi?.let {
                    UastFacade.convertElementWithParent(it, null) as? org.jetbrains.uast.UAnnotated
                }
                ann =
                    uAnnotated?.uAnnotations?.firstOrNull {
                        it.qualifiedName == ANNOTATION_REQUIRES_PERMISSION
                    }
            }

            if (ann == null) {
                val owner = actionField as? PsiModifierListOwner
                if (owner != null) {
                    ann = context.evaluator.getAnnotation(owner, ANNOTATION_REQUIRES_PERMISSION)
                }
            }

            if (ann != null) {
                return parseAnnotation(context, ann)
            }

            val holder = PermissionHolder()
            val sourcePsi = UastFacade.convertElementWithParent(actionField, null)?.sourcePsi
            if (sourcePsi is org.jetbrains.kotlin.psi.KtAnnotated) {
                val found =
                    sourcePsi.annotationEntries.find {
                        it.shortName?.asString() == "RequiresPermission"
                    }
                if (found != null) {
                    val text = found.text
                    val m =
                        Pattern.compile("android\\.Manifest\\.permission\\.([A-Z_]+)").matcher(text)
                    while (m.find()) {
                        holder.allOf.add("android.permission." + m.group(1))
                    }
                }
            }
            return holder
        }

        private fun parseBroadcastTargetPermission(
            node: UCallExpression,
            isAsUser: Boolean,
        ): PermissionHolder {
            val holder = PermissionHolder()
            // sendBroadcast(Intent, String OR String[]) -> index 1
            // sendBroadcastAsUser(Intent, UserHandle, String OR String[]) -> index 2
            val permissionIndex = if (isAsUser) 2 else 1
            holder.allOf.addAll(
                getPermissions(node.valueArguments.getOrNull(permissionIndex), context)
            )
            return holder
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
        return PermissionHolder().apply {
            allOf.addAll(getPermissions(annotation.findAttributeValue("value"), context))
            allOf.addAll(getPermissions(annotation.findAttributeValue("allOf"), context))
            anyOf.addAll(getPermissions(annotation.findAttributeValue("anyOf"), context))
        }
    }

    private fun getPermissions(value: UExpression?, context: JavaContext): Set<String> {
        if (value == null) return emptySet()

        var expr = value
        while (expr is UParenthesizedExpression) {
            expr = expr.expression
        }

        fun extractStringFromPsi(psi: PsiElement?): String? {
            return when (psi) {
                is PsiReferenceExpression -> {
                    val text = psi.text
                    if (text.contains(".permission.")) text else null
                }
                is PsiLiteralExpression -> psi.value as? String
                else -> null
            }
        }

        if (expr is UCallExpression) {
            return expr.valueArguments
                .mapNotNull { arg ->
                    val evaluated = ConstantEvaluator.evaluate(context, arg)
                    evaluated?.toString() ?: extractStringFromPsi(arg.sourcePsi)
                }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        val evaluated = ConstantEvaluator.evaluate(context, expr)
        val result = evaluated?.toString() ?: extractStringFromPsi(expr.sourcePsi)
        return if (result != null && result.isNotEmpty()) {
            setOf(result)
        } else {
            emptySet()
        }
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

        private val SEND_BROADCAST_REGEX =
            "^send(Ordered|Sticky)?Broadcast((With)?MultiplePermissions)?$".toRegex()
        private val SEND_BROADCAST_AS_USER_REGEX =
            "^send(Ordered|Sticky)?BroadcastAsUser(MultiplePermissions)?$".toRegex()

        @JvmField
        val ISSUE_MISSING_OR_MISMATCHED_SEND_BROADCAST_REQUIRES_PERMISSION =
            Issue.create(
                id = "MissingOrMismatchedSendBroadcastRequiresPermission",
                briefDescription = "Missing or mismatched @RequiresPermission on sendBroadcast",
                explanation =
                    """
                    The permission declared on the Intent action (via @RequiresPermission) must
                    match the permission enforced by the sendBroadcast() call. This check only
                    tracks variables initialized with 'new Intent(ACTION)' or assigned with
                    'intent.setAction(ACTION)' within the same method.
                    """
                        .trimIndent(),
                category = Category.SECURITY,
                priority = 6,
                severity = Severity.ERROR,
                implementation =
                    Implementation(RequiresPermissionDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )

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
                    Methods that call other APIs requiring permissions must be annotated with their
                    own @RequiresPermission annotation. This annotation must be specific enough to
                    cover all permissions required by the APIs it calls (not "too narrow"), but
                    should not declare permissions that are never used (not "too broad").
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
