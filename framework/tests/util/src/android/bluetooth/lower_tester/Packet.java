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

import android.bluetooth.BluetoothAdapter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Packet class will be used to represent a packet in the Lower Tester. It will be used to store the
 * opcode, sender address, receiver address, and payload of a packet.
 */
public class Packet {
    private static final String TAG = Packet.class.getSimpleName();

    private final int mOpcode;
    private final ByteBuffer mSenderAddress; // Stored in little endian format
    private final ByteBuffer mReceiverAddress; // Stored in little endian format
    private final ByteBuffer mPayload;

    // Constants for packet structure
    public static final int ADDRESS_LENGTH = 6;
    public static final int OPCODE_LENGTH = 1;
    public static final int HEADER_LENGTH = OPCODE_LENGTH + (2 * ADDRESS_LENGTH); // 13 bytes
    public static final int PACKET_LENGTH_FIELD_SIZE = 4;

    /**
     * Helper method to create a read-only ByteBuffer slice for a specific segment.
     *
     * @param sourceBuffer The original ByteBuffer to slice from.
     * @param offset The starting offset of the segment.
     * @param length The length of the segment.
     * @return A read-only ByteBuffer representing the specified segment.
     */
    private static ByteBuffer createReadOnlySlice(ByteBuffer sourceBuffer, int offset, int length) {
        ByteBuffer tempBuffer = sourceBuffer.duplicate();
        tempBuffer.order(sourceBuffer.order()); // Maintain the same byte order
        tempBuffer.position(offset);
        tempBuffer.limit(offset + length);
        return tempBuffer.slice().asReadOnlyBuffer();
    }

    /**
     * @param packetData The packet data in byte array format.
     * @throws IllegalArgumentException If the packet data is not valid.
     */
    public Packet(byte[] packetData) {
        Objects.requireNonNull(packetData, "Packet data must not be null.");

        ByteBuffer readOnlyPacketData = ByteBuffer.wrap(packetData).asReadOnlyBuffer();
        if (readOnlyPacketData.remaining() < HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "Packet data must contain at least "
                            + HEADER_LENGTH
                            + " bytes for the header.");
        }

        // Use the helper method to create slices for each component
        mOpcode = readOnlyPacketData.get() & 0xFF; // Read opcode as an unsigned byte
        mSenderAddress = createReadOnlySlice(readOnlyPacketData, OPCODE_LENGTH, ADDRESS_LENGTH);
        mReceiverAddress =
                createReadOnlySlice(
                        readOnlyPacketData, OPCODE_LENGTH + ADDRESS_LENGTH, ADDRESS_LENGTH);

        int payloadOffset = HEADER_LENGTH;
        if (readOnlyPacketData.remaining() > payloadOffset) {
            mPayload =
                    createReadOnlySlice(
                            readOnlyPacketData,
                            payloadOffset,
                            readOnlyPacketData.remaining() - payloadOffset);
        } else {
            mPayload = null;
        }
    }

    /**
     * @return The opcode of the packet.
     */
    public int getOpcode() {
        return mOpcode;
    }

    /**
     * @return A copy of the sender address bytes to maintain immutability.
     */
    public ByteBuffer getSenderAddress() {
        return mSenderAddress;
    }

    /**
     * @return A copy of the receiver address bytes to maintain immutability.
     */
    public ByteBuffer getReceiverAddress() {
        return mReceiverAddress;
    }

    /**
     * @return A copy of the payload bytes, or null if there is no payload.
     */
    public ByteBuffer getPayload() {
        return mPayload;
    }

    /**
     * Converts a 4-byte little-endian array into an integer.
     *
     * @param buffer The 4-byte array representing the length in little-endian order.
     * @return The integer value.
     * @throws IllegalArgumentException if lenData is null or not 4 bytes long.
     */
    public static int littleEndianByteArrayToInt(byte[] buffer) {
        Objects.requireNonNull(buffer, "ByteBuffer must not be null.");
        if (buffer.length != PACKET_LENGTH_FIELD_SIZE) {
            throw new IllegalArgumentException(
                    "ByteBuffer must be " + PACKET_LENGTH_FIELD_SIZE + " bytes long.");
        }

        // Set the byte order to LITTLE_ENDIAN and read the integer.
        // The getInt() call reads 4 bytes and advances the buffer's position.
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * Converts a Bluetooth address string to a 6-byte array in little-endian format using
     * ByteBuffer.
     *
     * @param address The Bluetooth address string (e.g., "AA:BB:CC:DD:EE:FF").
     * @return The 6-byte array representation of the address in little-endian order.
     * @throws IllegalArgumentException If the address string is null or has an invalid format.
     */
    public static ByteBuffer getAddressAsBytes(String address) {
        Objects.requireNonNull(address, "Address string must not be null.");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Invalid Bluetooth address format: " + address);
        }

        // Create a ByteBuffer with the desired capacity and set its byte order to LITTLE_ENDIAN
        ByteBuffer buffer = ByteBuffer.allocate(ADDRESS_LENGTH);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        String[] hexParts = address.split(":");

        // The address string is typically represented in big-endian (most significant byte first)
        // when read left-to-right. For little-endian byte array, we need to iterate
        // from the end of the hexParts array.
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            buffer.put((byte) Integer.parseInt(hexParts[ADDRESS_LENGTH - 1 - i], 16));
        }

        return buffer;
    }
}
