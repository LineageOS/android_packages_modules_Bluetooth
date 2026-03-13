/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.bluetooth.vap

import android.bluetooth.BluetoothDevice
import com.android.bluetooth.Util
import com.android.bluetooth.profile.NativeInterface
import java.util.Objects

/** Voice Assistant Profile Server Native Interface to/from JNI. */
class VapServerNativeInterface(nativeCallback: VapServerNativeCallback) :
    NativeInterface<VapServerNativeCallback>(Objects.requireNonNull(nativeCallback)) {

    fun init() {
        initNative()
    }

    fun setCcid(ccid: Int) {
        setCcidNative(ccid)
    }

    override fun cleanup() {
        cleanupNative()
    }

    fun setVaName(vaName: String?) {
        setVaNameNative(vaName)
    }

    // Native methods that call into the JNI interface
    private external fun initNative()

    private external fun setCcidNative(ccid: Int)

    private external fun cleanupNative()

    private external fun setVaNameNative(vaName: String?)

    companion object {
        private val TAG = VapServerNativeInterface::class.java.simpleName

        private fun getByteAddress(device: BluetoothDevice?): ByteArray {
            return if (device == null) {
                Util.getBytesFromAddress("00:00:00:00:00:00")
            } else Util.getBytesFromAddress(device.address)
        }
    }
}
