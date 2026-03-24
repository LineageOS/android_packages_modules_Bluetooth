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

package com.android.bluetooth.broadcastsink

import android.bluetooth.BluetoothLeBroadcastSinkState
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothLeBroadcastSinkStateTest {

    @Test
    fun testCreateAndGetters() {
        val state =
            BluetoothLeBroadcastSinkState(
                BROADCAST_ID,
                PA_STATE_SYNCED,
                BIG_STATE_SYNCED,
                BIS_SYNC_STATES,
            )

        assertThat(state.broadcastId).isEqualTo(BROADCAST_ID)
        assertThat(state.paSyncState).isEqualTo(PA_STATE_SYNCED)
        assertThat(state.bigSyncState).isEqualTo(BIG_STATE_SYNCED)
        assertThat(state.bisSyncStates).isEqualTo(BIS_SYNC_STATES)
        assertThat(state.parcel).isNotNull()
    }

    @Test
    fun testParceling() {
        val state =
            BluetoothLeBroadcastSinkState(
                BROADCAST_ID,
                PA_STATE_SYNCED,
                BIG_STATE_SYNCED,
                BIS_SYNC_STATES,
            )

        val innerParcel = state.parcel
        assertThat(innerParcel.describeContents()).isEqualTo(0)

        val parcel = Parcel.obtain()
        try {
            innerParcel.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val createdFromParcel =
                BluetoothLeBroadcastSinkState.InnerParcel.CREATOR.createFromParcel(parcel)
            assertThat(createdFromParcel).isNotNull()

            // fromParcel is package-private, so we use reflection to test it
            val fromParcelMethod =
                BluetoothLeBroadcastSinkState::class
                    .java
                    .getDeclaredMethod(
                        "fromParcel",
                        BluetoothLeBroadcastSinkState.InnerParcel::class.java,
                    )
            fromParcelMethod.isAccessible = true
            val unparceledState =
                fromParcelMethod.invoke(null, createdFromParcel) as BluetoothLeBroadcastSinkState

            assertThat(unparceledState.broadcastId).isEqualTo(BROADCAST_ID)
            assertThat(unparceledState.paSyncState).isEqualTo(PA_STATE_SYNCED)
            assertThat(unparceledState.bigSyncState).isEqualTo(BIG_STATE_SYNCED)
            assertThat(unparceledState.bisSyncStates).isEqualTo(BIS_SYNC_STATES)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun testFromParcel_withNull() {
        val fromParcelMethod =
            BluetoothLeBroadcastSinkState::class
                .java
                .getDeclaredMethod(
                    "fromParcel",
                    BluetoothLeBroadcastSinkState.InnerParcel::class.java,
                )
        fromParcelMethod.isAccessible = true
        val unparceledState = fromParcelMethod.invoke(null, null)

        assertThat(unparceledState).isNull()
    }

    @Test
    fun testEqualsAndHashCode() {
        val newBroadcastId = 99999

        val state1 =
            BluetoothLeBroadcastSinkState(
                BROADCAST_ID,
                PA_STATE_SYNCED,
                BIG_STATE_SYNCED,
                BIS_SYNC_STATES,
            )
        val state2 =
            BluetoothLeBroadcastSinkState(
                BROADCAST_ID,
                PA_STATE_SYNCED,
                BIG_STATE_SYNCED,
                BIS_SYNC_STATES,
            )
        val stateDifferent =
            BluetoothLeBroadcastSinkState(
                newBroadcastId,
                PA_STATE_SYNCED,
                BIG_STATE_SYNCED,
                BIS_SYNC_STATES,
            )

        // Test equals
        assertThat(state1).isEqualTo(state2)
        assertThat(state1).isNotEqualTo(stateDifferent)

        // Test hashCode
        assertThat(state1.hashCode()).isEqualTo(state2.hashCode())
        assertThat(state1.hashCode()).isNotEqualTo(stateDifferent.hashCode())
    }

    @Test
    fun testToString() {
        val state =
            BluetoothLeBroadcastSinkState(
                BROADCAST_ID,
                PA_STATE_SYNCED,
                BIG_STATE_SYNCED,
                BIS_SYNC_STATES,
            )
        val stateString = state.toString()

        assertThat(stateString).contains(BROADCAST_ID.toString())
        assertThat(stateString).contains(PA_STATE_SYNCED.toString())
        assertThat(stateString).contains(BIG_STATE_SYNCED.toString())
        assertThat(stateString).contains(BIS_SYNC_STATES.toString())
    }

    companion object {
        private const val BROADCAST_ID = 12345
        private const val PA_STATE_SYNCED = BluetoothLeBroadcastSinkState.SINK_PA_STATE_SYNCED
        private const val BIG_STATE_SYNCED = BluetoothLeBroadcastSinkState.SINK_BIG_STATE_SYNCED
        private val BIS_SYNC_STATES = listOf(1L, 0L, 1L)
    }
}
