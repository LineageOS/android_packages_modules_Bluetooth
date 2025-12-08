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

package com.android.server.bluetooth

import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle

private const val TAG = "RolePermissionListener"

object RolePermissionListener {
    const val BLUETOOTH_ROLE = "android.app.role.SYSTEM_BLUETOOTH_STACK"

    private var session: UserSession? = null

    /**
     * Registers a listener for the Bluetooth stack role and triggers the onRoleGranted callback
     * when the role is granted to a package.
     */
    @JvmStatic
    fun registerForUser(
        looper: Looper,
        userContext: Context,
        user: UserHandle,
        onRoleGranted: () -> Unit,
    ) {
        session?.let { it.unregister() }

        val roleManager = userContext.getSystemService(RoleManager::class.java)!!
        val userSession = UserSession(looper, roleManager, user, onRoleGranted)
        if (hasBluetoothBeGivenStackRole(roleManager)) {
            Log.i(TAG, "Bluetooth already holds $BLUETOOTH_ROLE. Starting immediately")
            userSession.unregister()
            onRoleGranted()
        } else {
            Log.i(TAG, "Bluetooth does not hold $BLUETOOTH_ROLE. Start is delayed")
            session = userSession
        }
    }

    private class UserSession(
        looper: Looper,
        private val roleManager: RoleManager,
        private val user: UserHandle,
        private val onRoleGranted: () -> Unit,
    ) : OnRoleHoldersChangedListener {

        private val handler = Handler(looper)

        init {
            roleManager.addOnRoleHoldersChangedListenerAsUser(handler::post, this, user)
        }

        override fun onRoleHoldersChanged(roleName: String, unusedUser: UserHandle) {
            if (roleName != BLUETOOTH_ROLE) {
                Log.v(TAG, "onRoleHoldersChanged for unrelated role $roleName")
                return
            }
            Log.d(TAG, "onRoleHoldersChanged for $roleName")
            if (hasBluetoothBeGivenStackRole(roleManager)) {
                unregister()
                onRoleGranted()
            }
        }

        fun unregister() {
            session = null
            roleManager.removeOnRoleHoldersChangedListenerAsUser(this, user)
            handler.removeCallbacksAndMessages(null)
        }
    }

    private fun hasBluetoothBeGivenStackRole(roleManager: RoleManager): Boolean {
        val roleHolders = roleManager.getRoleHolders(BLUETOOTH_ROLE)
        Log.i(TAG, "Role holder: $roleHolders")
        return roleHolders.isNotEmpty() && roleHolders.first().isNotEmpty()
    }
}
