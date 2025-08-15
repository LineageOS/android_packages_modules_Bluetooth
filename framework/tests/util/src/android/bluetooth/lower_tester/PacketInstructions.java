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

package android.bluetooth.lower_tester;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable container for a list of instructions on how to handle an intercepted packet. This
 * class should be constructed using the provided {@link Builder}.
 */
public final class PacketInstructions {
    private static final String TAG = PacketInstructions.class.getSimpleName();

    /**
     * Represents a single, immutable instruction for a new packet.
     *
     * @param opcode Opcode of the new packet.
     * @param destinationAddress Address of the destination device. This will be used to set the
     *     receiver address of the new packet.
     * @param data Data payload for the new packet (can be null).
     */
    public static record Instruction(int opcode, String destinationAddress, ByteString data) {}

    private final List<Instruction> mInstructions;

    private PacketInstructions(List<Instruction> instructions) {
        mInstructions = List.copyOf(instructions);
    }

    public List<Instruction> getInstructions() {
        return mInstructions;
    }

    /** A builder for creating immutable {@link PacketInstructions} instances. */
    public static class Builder {
        private final List<Instruction> mInstructions;

        public Builder() {
            mInstructions = new ArrayList<>();
        }

        /**
         * Adds an instruction for a packet with the given opcode, destination address, and data
         * payload.
         *
         * @param opcode Opcode of the new packet.
         * @param destinationAddress Address of the destination device. This will be used to set the
         *     receiver address of the new packet.
         * @param data Data payload for the new packet (can be `null` for no payload).
         */
        public Builder newPacket(int opcode, String destinationAddress, byte[] data) {
            // TODO: Add validation if the packet data is valid for the given opcode.
            mInstructions.add(
                    new Instruction(opcode, destinationAddress, ByteString.copyFrom(data)));
            return this;
        }

        /**
         * @return A new, immutable {@link PacketInstructions} instance.
         */
        public PacketInstructions build() {
            return new PacketInstructions(mInstructions);
        }
    }
}
