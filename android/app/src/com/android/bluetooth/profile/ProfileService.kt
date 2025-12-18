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

package com.android.bluetooth.profile

import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.android.bluetooth.Util
import com.android.bluetooth.btservice.AdapterService
import java.util.Optional

/**
 * Base class for a Bluetooth profile.
 *
 * @param profileId The id of this Profile. see [BluetoothProfile]
 * @param adapterService The [AdapterService].
 */
abstract class ProfileService(val profileId: Int, val adapterService: AdapterService) :
    ContextWrapper(adapterService) {

    interface IProfileServiceBinder : IBinder {
        fun cleanup()
    }

    val name = javaClass.simpleName
    val binder: Optional<IProfileServiceBinder>
    var isAvailable = false

    init {
        Log.d(name, "Service created")
        binder = Optional.ofNullable(initBinder())
    }

    override fun toString() = name

    /**
     * Called in ProfileService constructor to init binder interface for this profile service.
     *
     * @return initialized binder interface for this profile service.
     */
    protected abstract fun initBinder(): IProfileServiceBinder?

    /** Called when this object is no longer needed and is being discarded. */
    abstract fun cleanup()

    protected fun <T> obtainSystemService(serviceClass: Class<T>): T {
        return adapterService.getSystemService(serviceClass)
    }

    /**
     * Set the availability of an owned/managed component (Service, Activity, Provider, etc.) using
     * a string class name assumed to be in the Bluetooth package.
     *
     * <p>It's expected that profiles can have a set of components that they may use to provide
     * features or interact with other services/the user. Profiles are expected to enable those
     * components when they start, and disable them when they stop.
     *
     * @param className The class name of the owned component residing in the Bluetooth package
     * @param enable True to enable the component, False to disable it
     */
    protected fun setComponentAvailable(className: String, enable: Boolean) {
        Log.d(name, "setComponentAvailable(className=$className, enable=$enable)")

        val component = ComponentName(packageName, className)
        // Test should not set components available for the device
        if (Util.isInstrumentationTestMode) {
            Log.w(name, "Skip call to setComponentAvailable($component, $enable)")
            return
        }

        Log.d(name, "setComponentAvailable($component, $enable)")

        packageManager.setComponentEnabledSetting(
            component,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS,
        )
    }

    /**
     * Support dumping profile-specific information for dumpsys
     *
     * @param sb StringBuilder from the profile.
     */
    open fun dump(sb: StringBuilder) {
        sb.appendLine("Profile: $name")
    }

    companion object {
        /**
         * Append an indented String for adding dumpsys support to subclasses.
         *
         * @param sb StringBuilder from the profile.
         * @param text String to indent and append.
         */
        @JvmStatic
        fun println(sb: StringBuilder, text: String) {
            sb.appendLine("  $text")
        }
    }
}
