/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.bluetooth.offload.a2dp;

/**
 * Configuration relative to a stream.
 * The configurations tracks parameters of the vendor HCI command Start A2DP Offload v2.
 */
parcelable StreamConfiguration {

    /**
     * Handle of the active HCI connection.
     */
    int connectionHandle;

    /**
     * Identifier of the L2CAP Channel opened for A2DP streaming.
     */
    int l2capChannelId;

    /**
     * 0x00 - Output (AVDTP Source/Merge).
     * 0x01 - Input (AVDTP Sink/Split).
     */
    byte dataPathDirection;

    /**
     * Maximum size of L2CAP packets, negotiated with the peer.
     */
    int peerMtu;

    /**
     * 0x00 - Disable SCMS-T Content Protection Header.
     * 0x01 - Enable SCMS-T Content Protection Header.
     */
    byte cpEnableScmsT;

    /**
     * When SCMS-T Content Protection Header is enabled (CP_SCMS_T_Enable set to 0x01),
     * defines the header value that precedes the audio content (refer to A2DP, section 3.2.1-2)
     * as defined by Bluetooth Assigned Numbers, section 6.3.2. Ignored when SCMS-T Content
     * protection isn't enabled.
     */
    byte cpHeaderScmsT;

    /**
     * Vendor Specific Parameters provided by the Bluetooth Audio HAL,
     * CodecParameters.vendorSpecificParameters[].
     */
    byte[] VendorSpecificParameters;
}
