/*
 * Copyright (c) 2022 The Android Open Source Project
 * Copyright (c) 2020 The Linux Foundation
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

package com.android.bluetooth.btservice

/**
 * APIs of interoperability workaround utilities. These APIs will call stack layer's interop APIs of
 * interop.cc to do matching or entry adding/removing.
 */
object InteropUtil {

    /**
     * Add interop feature from device/include/interop.h to below InteropFeature if this feature
     * needs to be matched at java layer. Feature's name will be passed to stack layer to do
     * matching, so make sure that the added feature's name is exactly same as that in
     * device/include/interop.h.
     */
    enum class InteropFeature {
        INTEROP_NOT_UPDATE_AVRCP_PAUSED_TO_REMOTE,
        INTEROP_PHONE_POLICY_INCREASED_DELAY_CONNECT_OTHER_PROFILES,
        INTEROP_PHONE_POLICY_REDUCED_DELAY_CONNECT_OTHER_PROFILES,
        INTEROP_HFP_FAKE_INCOMING_CALL_INDICATOR,
        INTEROP_HFP_SEND_CALL_INDICATORS_BACK_TO_BACK,
        INTEROP_SETUP_SCO_WITH_NO_DELAY_AFTER_SLC_DURING_CALL,
        INTEROP_RETRY_SCO_AFTER_REMOTE_REJECT_SCO,
        INTEROP_ADV_PBAP_VER_1_2,
        INTEROP_HFP_SEND_OK_FOR_CLCC_AFTER_VOIP_CALL_END,
        INTEROP_A2DP_DELAY_DISCONNECT,
        INTEROP_DISABLE_PROFILE_FALLBACK,
    }
}
