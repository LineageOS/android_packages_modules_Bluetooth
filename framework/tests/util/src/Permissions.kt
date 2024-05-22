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

private const val TAG: String = "Permissions"

object Permissions {
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation()

    public interface PermissionContext : AutoCloseable {
        // Override AutoCloseable method to silent the requirement on Exception
        override fun close()
    }

    /**
     * Set permissions to be used as long as the resource is open. Restore initial permissions after
     * closing resource.
     *
     * @param newPermissions Permissions to hold when using resource. You need to specify at least 1
     */
    @JvmStatic
    fun withPermissions(vararg newPermissions: String): PermissionContext {
        val savedPermissions = replacePermissionsWith(*newPermissions)
        return object : PermissionContext {
            override fun close() {
                restorePermissions(savedPermissions)
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
            permissionsSet.remove(it)

            withPermissions(*arrayOf(*permissionsSet.toTypedArray())).use {
                assertThrows(SecurityException::class.java, { action() })
            }
        }
    }

    private fun restorePermissions(permissions: Set<String>) {
        if (UiAutomation.ALL_PERMISSIONS.equals(permissions)) {
            uiAutomation.adoptShellPermissionIdentity()
        } else {
            uiAutomation.adoptShellPermissionIdentity(*permissions.map { it }.toTypedArray())
        }
        Log.d(TAG, "Restored ${permissions}")
    }

    private fun replacePermissionsWith(vararg newPermissions: String): Set<String> {
        val currentPermissions = uiAutomation.getAdoptedShellPermissions()
        if (newPermissions.size == 0) {
            // Throw even if the code support it as we are not expecting this by design
            throw IllegalArgumentException("Invalid permissions replacement with no permissions.")
        }
        uiAutomation.adoptShellPermissionIdentity(*newPermissions)
        Log.d(TAG, "Replaced ${currentPermissions} with ${newPermissions.toSet()}")
        return currentPermissions
    }
}
