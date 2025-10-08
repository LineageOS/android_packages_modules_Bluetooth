/*
 * Copyright 2025 The Android Open Source Project
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

package bluetooth.constants;

/**
* Set of constants we can find in BT Core specification to be shared between native and Java.
* @hide
*/
@JavaDerive(toString = true)
@Backing(type="int")
enum Core {
    /** GATT max attribute length (Bluetooth Core Specification 6.1 Volume 3, Part F, section 3.2.9) */
    GATT_MAX_ATTR_LEN = 512,
}
