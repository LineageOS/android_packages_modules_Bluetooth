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

package android.bluetooth.test_utils

import android.app.UiAutomation
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows

private const val TAG = "Permissions"

object Permissions {
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation()

    interface PermissionContext : AutoCloseable {
        // Override AutoCloseable method to silent the requirement on Exception
        override fun close()
    }

    /**
     * Set permissions to be used as long as the resource is open. Restore initial permissions after
     * closing resource.
     *
     * @param newPermissions Permissions to hold when using resource.
     */
    @JvmStatic
    fun with(vararg newPermissions: String): PermissionContext = withPermissions(*newPermissions)

    @JvmStatic
    fun withPermissions(vararg newPermissions: String): PermissionContext {
        val savedPermissions = replacePermissionsWith(*newPermissions)
        return object : PermissionContext {
            override fun close() {
                restorePermissions(savedPermissions)
            }
        }
    }

    /** See {@link #enforce(List<String>, () -> Any} */
    @JvmStatic
    fun enforce(permissionToEnforce: String, action: () -> Any) =
        enforce(listOf(permissionToEnforce), action)

    /**
     * Grant all the permissions required then only remove one and perform the action. Then re-grant
     * all permissions and remove the next permissions
     *
     * @param permissionsToEnforce List of every single permissions to enforce one by one
     */
    @JvmStatic
    fun enforce(permissionsToEnforce: List<String>, action: () -> Any) {
        withPermissions().use {
            permissionsToEnforce.forEach { permEnforced ->
                val permissionsAllowed =
                    permissionsToEnforce.filter { it != permEnforced }.toTypedArray()

                uiAutomation.adoptShellPermissionIdentity(*permissionsAllowed)
                assertThrows(
                    "$permEnforced is not enforced",
                    SecurityException::class.java,
                    { action() },
                )
            }
        }
    }

    @JvmStatic
    fun enforceEachPermissions(action: () -> Any, newPermissions: List<String>) {
        if (newPermissions.size < 2) {
            throw IllegalArgumentException("Not supported for less than 2 permissions")
        }
        newPermissions.forEach {
            val permissionsSet = newPermissions.toMutableSet()
            val removedPermission = it
            permissionsSet.remove(removedPermission)

            withPermissions(*arrayOf(*permissionsSet.toTypedArray())).use {
                assertThrows(
                    "SecurityException was not thrown after removing $removedPermission",
                    SecurityException::class.java,
                    { action() },
                )
            }
        }
    }

    private fun restorePermissions(permissions: Set<String>) {
        if (UiAutomation.ALL_PERMISSIONS.equals(permissions)) {
            uiAutomation.adoptShellPermissionIdentity()
        } else if (permissions.size == 0) {
            uiAutomation.dropShellPermissionIdentity()
        } else {
            uiAutomation.adoptShellPermissionIdentity(*permissions.toTypedArray())
        }
        Log.d(TAG, "Restored [${permissions.joinToString()}]")
    }

    private fun replacePermissionsWith(vararg newPermissions: String): Set<String> {
        val currentPermissions = uiAutomation.getAdoptedShellPermissions()
        if (newPermissions.size != 0) {
            uiAutomation.adoptShellPermissionIdentity(*newPermissions)
        } else {
            uiAutomation.dropShellPermissionIdentity()
        }
        Log.d(
            TAG,
            "Replaced [${currentPermissions.joinToString()}] with [${newPermissions.joinToString()}]",
        )
        return currentPermissions
    }
}
