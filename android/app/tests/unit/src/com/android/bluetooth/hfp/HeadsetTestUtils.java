/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Helper functions for HFP related tests */
public class HeadsetTestUtils {
    /**
     * Helper function to check if {@link HeadsetPhoneState} is set to correct values indicated in
     * {@code headsetCallState}
     *
     * @param headsetPhoneState a mocked {@link HeadsetPhoneState}
     * @param headsetCallState intended headset call state
     * @param timeoutMs timeout for this check in asynchronous test conditions
     */
    public static void verifyPhoneStateChangeSetters(
            HeadsetPhoneState headsetPhoneState, HeadsetCallState headsetCallState, int timeoutMs) {
        verify(headsetPhoneState, timeout(timeoutMs).times(1))
                .setNumActiveCall(headsetCallState.mNumActive);
        verify(headsetPhoneState, timeout(timeoutMs).times(1))
                .setNumHeldCall(headsetCallState.mNumHeld);
        verify(headsetPhoneState, timeout(timeoutMs).times(1))
                .setCallState(headsetCallState.mCallState);
    }
}
