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
import android.bluetooth.lower_tester.PacketInstructions.Instruction;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LowerTester implements Runnable {
    private static final String TAG = LowerTester.class.getSimpleName();
    public static final int SUPPORTED_DEVICE_COUNT = 2;

    /**
     * The list of connected device addresses, which will be used to send and receive packets. This
     * will be populated by the test cases, usually Bumble and Cuttlefish devices. The addresses are
     * stored as byte arrays in little endian format.
     */
    private final ByteBuffer[] mConnectedDeviceAddressesAsBytes;

    /*
     * Cuttlefish configures the vsock port for BR/EDR and BLE sockets as 7400, and 7500.
     * For lower tester, these vsock ports are mapped to TCP ports 6411, and 6511 respectively.
     */
    public static final int BREDR_SOCKET_PORT = 6411;

    public static final int BLE_SOCKET_PORT = 6511;

    private final int mSocketPort;

    // The address of the lower tester device.
    private final String mAddress;
    private final ByteBuffer mAddressAsBytes;
    private static int sAddressCounter = 0;

    // Indicator to check if the socket connection thread should be running.
    private volatile boolean mIsThreadRunning = false;

    /** Defines the contract for handling an intercepted Bluetooth packet. */
    public interface PacketInterceptorHandler {
        /**
         * Processes the payload of an intercepted packet and returns instructions on how to
         * proceed.
         *
         * @param payload The payload of the intercepted packet.
         * @return {@link PacketInstructions} specifying what to do with the packet.
         */
        PacketInstructions handlePacket(ByteBuffer payload);
    }

    /**
     * The map of intercepted packet instructions, which will be used to intercept packets. This
     * will be populated by the test cases.
     *
     * <p>The lambda function as value will have the byte[] array which is the payload of the packet
     * as input (respective to the key opcode) received from the socket. The test can utilize the
     * byte[] to decide on the further instructions (if required), and will return the
     * PacketInstructions object, which will have the instructions to be followed for the packet.
     */
    private final Map<Integer, PacketInterceptorHandler>[] mInterceptedPacketInstructions;

    /**
     * Constructor for LowerTester.
     *
     * @param socketPort The socket port to use for the socket connection.
     * @param deviceAddress1 The address of the first device to connect to.
     * @param deviceAddress2 The address of the second device to connect to. These 2 device
     *     addresses are the ones which will be the actual endpoints for the communication. Lower
     *     tester will connect to these 2 devices, and send and receive packets to these 2 devices
     *     in a way that both the devices will actually communicate with each other through the
     *     lower tester in between.
     * @throws IllegalArgumentException If the socket port is not valid, or if the device addresses
     *     are not valid Bluetooth addresses.
     */
    public LowerTester(int socketPort, String deviceAddress1, String deviceAddress2) {
        if (!BluetoothAdapter.checkBluetoothAddress(deviceAddress1)
                || !BluetoothAdapter.checkBluetoothAddress(deviceAddress2)) {
            throw new IllegalArgumentException(
                    "One or both of the device addresses are not valid Bluetooth addresses.");
        }
        if (socketPort != BREDR_SOCKET_PORT && socketPort != BLE_SOCKET_PORT) {
            throw new IllegalArgumentException(
                    "Socket port must be either "
                            + BREDR_SOCKET_PORT
                            + " for BR/EDR or "
                            + BLE_SOCKET_PORT
                            + " for BLE.");
        }
        mConnectedDeviceAddressesAsBytes = new ByteBuffer[SUPPORTED_DEVICE_COUNT];
        mConnectedDeviceAddressesAsBytes[0] =
                Packet.getAddressAsBytes(deviceAddress1).asReadOnlyBuffer();
        mConnectedDeviceAddressesAsBytes[1] =
                Packet.getAddressAsBytes(deviceAddress2).asReadOnlyBuffer();

        // Use a static counter for unique addresses
        mAddress = "DA:4C:10:DE:18:" + String.format("%1$02X", sAddressCounter++);
        mAddressAsBytes = Packet.getAddressAsBytes(mAddress);

        mSocketPort = socketPort;
        mInterceptedPacketInstructions = new HashMap[SUPPORTED_DEVICE_COUNT];
        for (int i = 0; i < SUPPORTED_DEVICE_COUNT; i++) {
            mInterceptedPacketInstructions[i] = new HashMap<>();
        }

        Log.i(
                TAG,
                "LowerTester initialized for addresses: " + deviceAddress1 + ", " + deviceAddress2);
    }

    /**
     * Returns the address of the instantiated lower tester device, which will be used by the test
     * case to create the BluetoothDevice object for the remote device (lower tester)
     *
     * @return The address of the lower tester device.
     */
    public String getDeviceAddress() {
        return mAddress;
    }

    /**
     * Intercepts a packet with the given opcode and applies the provided function to the packet
     * data.
     *
     * @param opcode The opcode of the packet to be intercepted.
     * @param sourceAddress The address of the source of the packet.
     * @param handler The handler to be applied to the packet.
     *     {@snippet :
     *  LowerTester lowerTester = new LowerTester(BREDR_SOCKET_PORT, CF_ADDRESS, BUMBLE_ADDRESS);
     *  lowerTester.interceptPacket(opcode, sourceAddress, (payload) -> {
     *          PacketInstructions instructions = new PacketInstructions.Builder()
     *                                          .newPacket(opcode1, address1, data1)
     *                                          .newPacket(opcode2, address2, data2)
     *                                          .build();
     *          return instructions;
     *      });
     * }
     */
    public void interceptPacket(
            int opcode, String sourceAddress, PacketInterceptorHandler handler) {
        // TODO: Validate the opcode, instruction is already validated in the PacketInstructions
        // class.
        // If `handler` is null, it represents that this opcode needs to be ignored. Hence we need
        // to drop the packet.
        int index = checkAddressReturnDeviceIndex(Packet.getAddressAsBytes(sourceAddress));
        if (index == -1) {
            throw new IllegalArgumentException(
                    "Source address " + sourceAddress + " is not a connected device address.");
        }
        mInterceptedPacketInstructions[index].put(opcode, handler);
    }

    /**
     * Notifies the thread to stop, the thread will be stopped once the socket Tx/Rx completed
     * before beginning the next interaction. Note: This is not a synchronous method, it is only a
     * signal to the thread to stop. The thread will continue to run until the socket Tx/Rx is
     * completed.
     */
    public void initiateStop() {
        mIsThreadRunning = false;
    }

    /**
     * Thread.run() will be used to start the socket connection thread, which will listen for
     * incoming packets, modify them as per the intercepted instructions, and send them as per the
     * instructions or as per the normal flow.
     */
    @Override
    public void run() {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), mSocketPort), 15000);

            try (InputStream inputStream = socket.getInputStream();
                    OutputStream outputStream = socket.getOutputStream()) {
                mIsThreadRunning = true;
                while (mIsThreadRunning) {
                    Packet packet = receivePacket(inputStream);
                    if (packet == null) {
                        continue;
                    }
                    if (!handleInterceptedPackets(packet, outputStream)) {
                        // If the packet is not intercepted, send the packet as per the normal flow.
                        int destinationDeviceIndex =
                                1
                                        - checkAddressReturnDeviceIndex(
                                                packet.getSenderAddress()); // 0 -> 1, 1 -> 0.

                        sendPacket(
                                packet.getOpcode(),
                                mAddressAsBytes,
                                mConnectedDeviceAddressesAsBytes[destinationDeviceIndex],
                                packet.getPayload(),
                                outputStream);
                    }
                }
            }
        } catch (IOException e) {
            // Only log if the thread was not intentionally stopped
            Log.e(TAG, "Socket connection error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(
                    TAG,
                    "An unexpected error occurred in LowerTester run loop: " + e.getMessage(),
                    e);
        } finally {
            mIsThreadRunning = false;
            Log.i(TAG, "LowerTester thread has stopped.");
        }
    }

    /** Utility functions */

    /**
     * This method will process if there is any intercepted packet instruction for the given opcode.
     * If yes, it will handle the sending of data further to the destination device, and returns
     * true. Otherwise, it will return false and the normal packet flow will continue.
     *
     * @param incomingPacket Incoming packet.
     * @return True if the packet is intercepted, false otherwise.
     */
    private boolean handleInterceptedPackets(Packet incomingPacket, OutputStream outputStream)
            throws Exception {
        int opcode = incomingPacket.getOpcode();
        int sourceDeviceIndex =
                checkAddressReturnDeviceIndex(
                        incomingPacket.getSenderAddress()); // This will be a valid index
        if (!mInterceptedPacketInstructions[sourceDeviceIndex].containsKey(opcode)) {
            // This opcode is not intercepted for this source device.
            return false;
        }

        PacketInterceptorHandler handler =
                mInterceptedPacketInstructions[sourceDeviceIndex].get(opcode);
        if (handler == null) {
            // The packet is intercepted, but there is no handler for this opcode. Hence need to
            // drop the packet.
            return true;
        }

        List<Instruction> instructionsList =
                handler.handlePacket(incomingPacket.getPayload()).getInstructions();

        // Each instruction received from the test case will be handled here.
        for (Instruction instruction : instructionsList) {
            ByteBuffer destinationAddressAsBytes =
                    Packet.getAddressAsBytes(instruction.destinationAddress());
            if (checkAddressReturnDeviceIndex(destinationAddressAsBytes) == -1) {
                // The destination address is not a connected device address. Hence we need to drop
                // this instruction.
                Log.w(
                        TAG,
                        "Destination address "
                                + instruction.destinationAddress()
                                + " in the instruction list is not a connected device address.");
                continue;
            }

            sendPacket(
                    /* new packet opcode from the instruction */ instruction.opcode(),
                    /* lower tester as source address */ mAddressAsBytes,
                    /* destination address from the instruction */ destinationAddressAsBytes,
                    /* payload from the instruction */ ByteBuffer.wrap(
                                    instruction.data().toByteArray())
                            .asReadOnlyBuffer(),
                    outputStream);
        }

        // If there are no instructions, the packet will be handled as per the normal flow.
        // else, the packet will be handled as per the instructions.
        return (!instructionsList.isEmpty());
    }

    /**
     * Sends the packet over socket with the given opcode, sender and receiver addresses, and
     * payload.
     *
     * @param opcode The opcode of the packet
     * @param senderAddress The sender address of the packet
     * @param receiverAddress The receiver address of the packet
     * @param payload The payload of the packet
     */
    private void sendPacket(
            int opcode,
            ByteBuffer senderAddress,
            ByteBuffer receiverAddress,
            ByteBuffer payload,
            OutputStream outputStream)
            throws Exception {
        // First send the length of the packet.
        int payloadLen = (payload == null) ? 0 : payload.capacity();
        int sizeOfDataPacket = Packet.HEADER_LENGTH + payloadLen;

        outputStream.write(
                ByteBuffer.allocate(Packet.PACKET_LENGTH_FIELD_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(sizeOfDataPacket)
                        .array());
        outputStream.flush();

        // Use ByteBuffer for efficient packet construction
        ByteBuffer buffer = ByteBuffer.allocate(sizeOfDataPacket);
        buffer.put((byte) opcode);
        buffer.put(senderAddress);
        buffer.put(receiverAddress);
        if (payload != null) {
            buffer.put(payload);
        }

        // Send the complete packet.
        outputStream.write(buffer.array());
        outputStream.flush();
    }

    /**
     * Receives a packet over socket. This will be a blocking call, which will wait for a packet to
     * be received. This will make 2 read calls, one for the length of the packet, and another for
     * the packet data.
     *
     * @return The received packet data in Packet format, or null if an error occurs.
     */
    private Packet receivePacket(InputStream inputStream) throws Exception {
        // Read the length of the packet.
        ByteString lengthBuffer = ByteString.readFrom(inputStream, Packet.PACKET_LENGTH_FIELD_SIZE);
        if (lengthBuffer == null) {
            return null; // Indicates stream ended or error
        }
        int dataLen = Packet.littleEndianByteArrayToInt(lengthBuffer.toByteArray());
        if (dataLen < Packet.HEADER_LENGTH) {
            Log.e(
                    TAG,
                    "Received packet smaller than minimum header size. Expected at least "
                            + Packet.HEADER_LENGTH
                            + ", but got "
                            + dataLen);
            return null;
        }

        ByteString packetData = ByteString.readFrom(inputStream, dataLen);
        if (packetData == null) {
            return null;
        }

        Packet packet = new Packet(packetData.toByteArray());
        // Check if the receiver address of the packet matches the address of the lower tester
        // device.
        if (!mAddressAsBytes.equals(packet.getReceiverAddress())) {
            return null;
        }
        return packet;
    }

    /**
     * Checks if the address matches the address any of the connected device addresses. If yes, it
     * returns the index of the address in the connected device addresses to save the intercept
     * instructions.
     *
     * @param addressAsBytes The address to be checked.
     * @return The index of the address in the connected device addresses, or -1 if the address is
     *     not found or not a valid Bluetooth address.
     */
    private int checkAddressReturnDeviceIndex(ByteBuffer addressAsBytes) {
        for (int i = 0; i < SUPPORTED_DEVICE_COUNT; i++) {
            if (mConnectedDeviceAddressesAsBytes[i].equals(addressAsBytes)) {
                return i;
            }
        }

        return -1;
    }
}
