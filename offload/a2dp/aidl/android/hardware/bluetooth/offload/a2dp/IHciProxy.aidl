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

import android.hardware.bluetooth.offload.a2dp.IHciProxyCallbacks;

/**
 * Interface of the HCI-Proxy module
 */
interface IHciProxy {

    /**
     * Register callbacks for events in the other direction
     */
    void registerCallbacks(in IHciProxyCallbacks callbacks);

    /**
     * Send A2DP packet on a `handle`
     */
    oneway void sendPacket(in int handle, in byte[] data);
}
