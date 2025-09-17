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

import com.android.tools.lint.detector.api.getUMethod
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter

const val BLUETOOTH_PLATFORM_PACKAGE = "com.android.bluetooth"

const val ANNOTATION_ENFORCE_PERMISSION = "android.annotation.EnforcePermission"
const val ANNOTATION_REQUIRES_PERMISSION = "android.annotation.RequiresPermission"
const val ANNOTATION_PERMISSION_METHOD = "android.annotation.PermissionMethod"
const val ANNOTATION_PERMISSION_NAME = "android.annotation.PermissionName"

const val CLASS_BINDER = "android.os.Binder"
const val CLASS_BROADCAST_RECEIVER = "android.content.BroadcastReceiver"
const val CLASS_CONTEXT = "android.content.Context"
const val CLASS_INTENT = "android.content.Intent"
const val CLASS_PERMISSION_CHECKER = "android.content.PermissionChecker"
const val CLASS_PERMISSION_MANAGER = "android.permission.PermissionManager"

/** Returns true */
fun isBluetoothClass(node: UClass?): Boolean {
    return node?.qualifiedName?.startsWith(BLUETOOTH_PLATFORM_PACKAGE) ?: false
}

/** Writes lines to debug output, visible in the isolated Java output */
fun debug(tag: String, message: String, indent: String = "") {
    println("$indent[$tag]: $message")
}

fun isPermissionMethodCall(callExpression: UCallExpression): Boolean {
    val method = callExpression.resolve()?.getUMethod() ?: return false
    return hasPermissionMethodAnnotation(method)
}

fun hasPermissionMethodAnnotation(method: UMethod): Boolean =
    getPermissionMethodAnnotation(method) != null

fun getPermissionMethodAnnotation(method: UMethod?): UAnnotation? =
    method?.uAnnotations?.firstOrNull { it.qualifiedName == ANNOTATION_PERMISSION_METHOD }

fun hasPermissionNameAnnotation(parameter: UParameter) =
    parameter.annotations.any { it.hasQualifiedName(ANNOTATION_PERMISSION_NAME) }

fun UField?.getRequiresPermissionAnnotation(): UAnnotation? =
    this?.uAnnotations?.firstOrNull { it.qualifiedName == ANNOTATION_REQUIRES_PERMISSION }
