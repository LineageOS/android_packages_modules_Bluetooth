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

import java.lang.annotation.Native

/**
 * A generic base class for unifying JNI interface classes.
 *
 * @param T The subtype of NativeCallback for the specific interface.
 */
abstract class NativeInterface<T : NativeCallback>(
    /**
     * The native callback handler, which is a subtype of [NativeCallback]. This field is accessed
     * from the native JNI layer.
     */
    @Native @JvmField protected val nativeCallback: T
) {
    /**
     * A common cleanup method that all NativeInterface classes must implement. This must call the
     * native cleanup implementation usually called `cleanupNative`.
     */
    abstract fun cleanup()
}
