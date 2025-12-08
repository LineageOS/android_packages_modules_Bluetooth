/*
 * Copyright 2024 The Android Open Source Project
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

package bluetooth.constants.aics;

/**
 * See Audio Input Control Service 1.0 - 2.2.1.3. Gain_Mode field
 * The Gain_Mode field shall be set to a value that reflects whether gain modes are manual
 * or automatic.
 * - Manual Only, the server allows only manual gain.
 * - Automatic Only, the server allows only automatic gain.
 *
 * For all other Gain_Mode field values, the server allows switchable automatic/manual gain.
 * @hide
 */
@JavaDerive(toString = true)
@Backing(type="byte")
enum GainMode {
    MANUAL_ONLY = 0x00,
    AUTOMATIC_ONLY = 0x01,
    MANUAL = 0x02,
    AUTOMATIC = 0x03,
}
