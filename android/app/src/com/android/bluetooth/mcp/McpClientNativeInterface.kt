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

package com.android.bluetooth.mcp

import android.bluetooth.BluetoothDevice
import com.android.bluetooth.Util
import com.android.bluetooth.profile.NativeInterface

/** Native interface for the MCP Client. */
class McpClientNativeInterface(nativeCallback: McpClientNativeCallback) :
    NativeInterface<McpClientNativeCallback>(nativeCallback) {

    fun init() {
        initNative()
    }

    override fun cleanup() {
        cleanupNative()
    }

    fun connect(device: BluetoothDevice) {
        connectNative(getByteAddress(device))
    }

    fun disconnect(device: BluetoothDevice) {
        disconnectNative(getByteAddress(device))
    }

    fun play(device: BluetoothDevice, mediaControllerId: Int) {
        playNative(getByteAddress(device), mediaControllerId)
    }

    fun pause(device: BluetoothDevice, mediaControllerId: Int) {
        pauseNative(getByteAddress(device), mediaControllerId)
    }

    fun stop(device: BluetoothDevice, mediaControllerId: Int) {
        stopNative(getByteAddress(device), mediaControllerId)
    }

    fun nextTrack(device: BluetoothDevice, mediaControllerId: Int) {
        nextTrackNative(getByteAddress(device), mediaControllerId)
    }

    fun previousTrack(device: BluetoothDevice, mediaControllerId: Int) {
        previousTrackNative(getByteAddress(device), mediaControllerId)
    }

    fun fastRewind(device: BluetoothDevice, mediaControllerId: Int) {
        fastRewindNative(getByteAddress(device), mediaControllerId)
    }

    fun fastForward(device: BluetoothDevice, mediaControllerId: Int) {
        fastForwardNative(getByteAddress(device), mediaControllerId)
    }

    fun moveRelative(device: BluetoothDevice, mediaControllerId: Int, offset: Int) {
        moveRelativeNative(getByteAddress(device), mediaControllerId, offset)
    }

    fun setTrackPosition(device: BluetoothDevice, mediaControllerId: Int, position: Int) {
        setTrackPositionNative(getByteAddress(device), mediaControllerId, position)
    }

    fun setPlaybackSpeed(device: BluetoothDevice, mediaControllerId: Int, speed: Byte) {
        setPlaybackSpeedNative(getByteAddress(device), mediaControllerId, speed)
    }

    fun setPlayingOrder(
        device: BluetoothDevice,
        mediaControllerId: Int,
        playingOrder: PlayingOrder,
    ) {
        setPlayingOrderNative(getByteAddress(device), mediaControllerId, playingOrder.value)
    }

    private fun getByteAddress(device: BluetoothDevice): ByteArray {
        return Util.getBytesFromAddress(device.address)
    }

    private external fun initNative()

    private external fun cleanupNative()

    private external fun connectNative(address: ByteArray)

    private external fun disconnectNative(address: ByteArray)

    private external fun playNative(address: ByteArray, mediaControllerId: Int)

    private external fun pauseNative(address: ByteArray, mediaControllerId: Int)

    private external fun stopNative(address: ByteArray, mediaControllerId: Int)

    private external fun nextTrackNative(address: ByteArray, mediaControllerId: Int)

    private external fun previousTrackNative(address: ByteArray, mediaControllerId: Int)

    private external fun fastRewindNative(address: ByteArray, mediaControllerId: Int)

    private external fun fastForwardNative(address: ByteArray, mediaControllerId: Int)

    private external fun moveRelativeNative(address: ByteArray, mediaControllerId: Int, offset: Int)

    private external fun setTrackPositionNative(
        address: ByteArray,
        mediaControllerId: Int,
        position: Int,
    )

    private external fun setPlaybackSpeedNative(
        address: ByteArray,
        mediaControllerId: Int,
        speed: Byte,
    )

    private external fun setPlayingOrderNative(
        address: ByteArray,
        mediaControllerId: Int,
        playingOrder: Int,
    )
}
