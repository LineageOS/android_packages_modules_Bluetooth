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

package android.bluetooth;

import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import static java.util.Objects.requireNonNull;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the state of a Bluetooth LE Broadcast Sink for a specific broadcast source.
 *
 * <p>This object provides brief information about a broadcast's current state. It includes the
 * sink's current PA and BIG sync states, as well as BIS sync states to show which BIS are currently
 * active.
 */
@Hide
@FlaggedApi(Flags.FLAG_LEAUDIO_BROADCAST_SINK_FEATURE)
public final class BluetoothLeBroadcastSinkState {

    private final InnerParcel mParcel;

    /**
     * Constructor for a {@link BluetoothLeBroadcastSinkState}.
     *
     * @param broadcastId The broadcast ID of the source.
     * @param paSyncState The PA sync state for the broadcast source.
     * @param bigSyncState The BIG sync state for the broadcast source.
     * @param bisSyncStates A list of bitfields representing the synchronization state of each BIS.
     */
    @Hide
    public BluetoothLeBroadcastSinkState(
            int broadcastId,
            @PaSyncState int paSyncState,
            @BigSyncState int bigSyncState,
            @NonNull List<Long> bisSyncStates) {
        mParcel = new InnerParcel(broadcastId, paSyncState, bigSyncState, bisSyncStates);
    }

    /**
     * Private constructor to create a {@link BluetoothLeBroadcastSinkState} from an InnerParcel.
     *
     * @param parcel The InnerParcel containing the state data.
     */
    private BluetoothLeBroadcastSinkState(InnerParcel parcel) {
        mParcel = parcel;
    }

    /**
     * Get the underlying parcelable object.
     *
     * @return The InnerParcel object.
     */
    @Hide
    @RequiresNoPermission
    public InnerParcel getParcel() {
        return mParcel;
    }

    /**
     * Create a {@link BluetoothLeBroadcastSinkState} from an InnerParcel.
     *
     * @param parcel The InnerParcel to create the state from.
     * @return the {@link BluetoothLeBroadcastSinkState} associated with this parcel, or null if the
     *     parcel is null.
     */
    @RequiresNoPermission
    static @Nullable BluetoothLeBroadcastSinkState fromParcel(InnerParcel parcel) {
        if (parcel == null) {
            return null;
        }
        return new BluetoothLeBroadcastSinkState(parcel);
    }

    /** The overall PA state of the Broadcast Sink for a given source. */
    @Hide
    @IntDef(
            prefix = "SINK_PA_STATE_",
            value = {
                SINK_PA_STATE_IDLE,
                SINK_PA_STATE_SYNCING,
                SINK_PA_STATE_SYNCED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PaSyncState {}

    /** The sink is not tracking or synchronizing with the PA of this source. */
    public static final int SINK_PA_STATE_IDLE = 0;

    /** The sink is attempting to synchronize with the PA of this source. */
    public static final int SINK_PA_STATE_SYNCING = 1;

    /** The sink is synchronized with the PA of this source. */
    public static final int SINK_PA_STATE_SYNCED = 2;

    /** The overall BIG state of the Broadcast Sink for a given source. */
    @IntDef(
            prefix = "SINK_BIG_STATE_",
            value = {
                SINK_BIG_STATE_IDLE,
                SINK_BIG_STATE_SYNCING,
                SINK_BIG_STATE_SYNCED,
                SINK_BIG_STATE_LEAVING,
                SINK_BIG_STATE_UPDATING,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BigSyncState {}

    /**
     * The sink is not attempting to join or is not joined to the BIG. This is the default state.
     */
    public static final int SINK_BIG_STATE_IDLE = 0;

    /** The sink is attempting to synchronize with the BIG to receive audio. */
    public static final int SINK_BIG_STATE_SYNCING = 1;

    /** The sink is synchronized with the BIG and receiving audio. */
    public static final int SINK_BIG_STATE_SYNCED = 2;

    /** The sink was terminating BIG sync to stop receiving audio. */
    public static final int SINK_BIG_STATE_LEAVING = 3;

    /** The sink was updating BIG sync to change subgroup. */
    public static final int SINK_BIG_STATE_UPDATING = 4;

    /**
     * Get the broadcast ID of the source.
     *
     * @return the broadcast ID of the source
     */
    @Hide
    @RequiresNoPermission
    public int getBroadcastId() {
        return mParcel.mBroadcastId;
    }

    /**
     * Get the PA sync state for the broadcast source.
     *
     * @return the PA sync state
     */
    @Hide
    @RequiresNoPermission
    public @PaSyncState int getPaSyncState() {
        return mParcel.mPaSyncState;
    }

    /**
     * Get the BIG sync state for the broadcast source.
     *
     * @return the BIG sync state
     */
    @Hide
    @RequiresNoPermission
    public @BigSyncState int getBigSyncState() {
        return mParcel.mBigSyncState;
    }

    /**
     * Get a list of bitfields representing the synchronization state of each BIS. value 0 means the
     * BIS is not synchronized. value 1 means the BIS is synchronized.
     *
     * @return a list of BIS synchronization states
     */
    @Hide
    @RequiresNoPermission
    public @NonNull List<Long> getBisSyncStates() {
        return mParcel.mBisSyncStates;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof BluetoothLeBroadcastSinkState)) return false;
        BluetoothLeBroadcastSinkState that = (BluetoothLeBroadcastSinkState) o;
        return Objects.equals(mParcel, that.mParcel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mParcel);
    }

    /**
     * Returns a string representation of this BluetoothLeBroadcastSinkState object.
     *
     * @return a string representation of this object
     */
    @Override
    public String toString() {
        return "BluetoothLeBroadcastSinkState{"
                + "mBroadcastId="
                + getBroadcastId()
                + ", mPaSyncState="
                + getPaSyncState()
                + ", mBigSyncState="
                + getBigSyncState()
                + ", mBisSyncStates="
                + getBisSyncStates()
                + '}';
    }

    public static final class InnerParcel implements Parcelable {
        private final int mBroadcastId;
        private final @PaSyncState int mPaSyncState;
        private final @BigSyncState int mBigSyncState;
        private final List<Long> mBisSyncStates;

        InnerParcel(
                int broadcastId,
                @PaSyncState int paSyncState,
                @BigSyncState int bigSyncState,
                @NonNull List<Long> bisSyncStates) {
            mBroadcastId = broadcastId;
            mPaSyncState = paSyncState;
            mBigSyncState = bigSyncState;
            mBisSyncStates = bisSyncStates;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (!(o instanceof InnerParcel)) return false;
            InnerParcel that = (InnerParcel) o;
            return mBroadcastId == that.mBroadcastId
                    && mPaSyncState == that.mPaSyncState
                    && mBigSyncState == that.mBigSyncState
                    && Objects.equals(mBisSyncStates, that.mBisSyncStates);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mBroadcastId, mPaSyncState, mBigSyncState, mBisSyncStates);
        }

        private InnerParcel(Parcel in) {
            mBroadcastId = in.readInt();
            mPaSyncState = in.readInt();
            mBigSyncState = in.readInt();
            mBisSyncStates =
                    requireNonNull(in.readArrayList(Long.class.getClassLoader(), Long.class));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        @SuppressWarnings("AndroidFrameworkEfficientParcelable") // No Creator match List<Long>
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mBroadcastId);
            out.writeInt(mPaSyncState);
            out.writeInt(mBigSyncState);
            out.writeList(mBisSyncStates);
        }

        /** {@link Parcelable.Creator} interface implementation. */
        public static final @NonNull Parcelable.Creator<InnerParcel> CREATOR =
                new Parcelable.Creator<InnerParcel>() {
                    @Override
                    public @NonNull InnerParcel createFromParcel(Parcel in) {
                        return new InnerParcel(in);
                    }

                    @Override
                    public @NonNull InnerParcel[] newArray(int size) {
                        return new InnerParcel[size];
                    }
                };
    }
}
