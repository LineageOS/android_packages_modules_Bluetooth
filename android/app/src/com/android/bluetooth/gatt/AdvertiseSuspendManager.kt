/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.gatt

import android.bluetooth.IBluetoothGattServerCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.IAdvertisingSetCallback
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import com.android.bluetooth.btservice.AdapterService

/**
 * Manages the queueing of advertisement commands during Bluetooth suspend state. This class is
 * responsible for holding commands when the adapter is suspending or suspended, and processing them
 * upon resume.
 */
class AdvertiseSuspendManager(
    private val advertiseManager: AdvertiseManager,
    private val adapterService: AdapterService,
) {
    private val TAG = "AdvertiseSuspendManager"

    enum class SuspendState {
        NORMAL, // Carry out requests as usual.
        // For below states, new requests are queued. It will be resolved when state becomes NORMAL.
        RESOLVING, // Wait until ongoing start/enable/disable requests are resolved.
        PAUSING, // Disable (pause) all advertisements.
        SUSPENDED, // Ready to suspend.
        RESUMING, // Enable all paused advertisements.
    }

    private var suspendState = SuspendState.NORMAL
    private val pendingCommands = mutableListOf<PendingAdvertiseCommand>()

    sealed interface PendingAdvertiseCommand

    data class StartAdvertisingSetCommand(
        val parameters: AdvertisingSetParameters,
        val advertiseData: AdvertiseData,
        val scanResponse: AdvertiseData,
        val periodicParameters: PeriodicAdvertisingParameters,
        val periodicData: AdvertiseData,
        val duration: Int,
        val maxExtAdvEvents: Int,
        val gattServerCallback: IBluetoothGattServerCallback,
        val callback: IAdvertisingSetCallback,
        val source: AttributionSource,
    ) : PendingAdvertiseCommand

    data class GetOwnAddressCommand(val advertiserId: Int) : PendingAdvertiseCommand

    data class StopAdvertisingSetCommand(val callback: IAdvertisingSetCallback) :
        PendingAdvertiseCommand

    data class EnableAdvertisingSetCommand(
        val advertiserId: Int,
        val enable: Boolean,
        val duration: Int,
        val maxExtAdvEvents: Int,
    ) : PendingAdvertiseCommand

    data class SetAdvertisingDataCommand(val advertiserId: Int, val data: AdvertiseData) :
        PendingAdvertiseCommand

    data class SetScanResponseDataCommand(val advertiserId: Int, val data: AdvertiseData) :
        PendingAdvertiseCommand

    data class SetAdvertisingParametersCommand(
        val advertiserId: Int,
        val parameters: AdvertisingSetParameters,
    ) : PendingAdvertiseCommand

    data class SetPeriodicAdvertisingParametersCommand(
        val advertiserId: Int,
        val parameters: PeriodicAdvertisingParameters,
    ) : PendingAdvertiseCommand

    data class SetPeriodicAdvertisingDataCommand(val advertiserId: Int, val data: AdvertiseData) :
        PendingAdvertiseCommand

    data class SetPeriodicAdvertisingEnableCommand(val advertiserId: Int, val enable: Boolean) :
        PendingAdvertiseCommand

    private fun runPendingCommand(command: PendingAdvertiseCommand) {
        when (command) {
            is StartAdvertisingSetCommand ->
                advertiseManager.startAdvertisingSet(
                    command.parameters,
                    command.advertiseData,
                    command.scanResponse,
                    command.periodicParameters,
                    command.periodicData,
                    command.duration,
                    command.maxExtAdvEvents,
                    command.gattServerCallback,
                    command.callback,
                    command.source,
                )
            is GetOwnAddressCommand -> advertiseManager.getOwnAddress(command.advertiserId)
            is StopAdvertisingSetCommand -> advertiseManager.stopAdvertisingSet(command.callback)
            is EnableAdvertisingSetCommand ->
                advertiseManager.enableAdvertisingSet(
                    command.advertiserId,
                    command.enable,
                    command.duration,
                    command.maxExtAdvEvents,
                )
            is SetAdvertisingDataCommand ->
                advertiseManager.setAdvertisingData(command.advertiserId, command.data)
            is SetScanResponseDataCommand ->
                advertiseManager.setScanResponseData(command.advertiserId, command.data)
            is SetAdvertisingParametersCommand ->
                advertiseManager.setAdvertisingParameters(command.advertiserId, command.parameters)
            is SetPeriodicAdvertisingParametersCommand ->
                advertiseManager.setPeriodicAdvertisingParameters(
                    command.advertiserId,
                    command.parameters,
                )
            is SetPeriodicAdvertisingDataCommand ->
                advertiseManager.setPeriodicAdvertisingData(command.advertiserId, command.data)
            is SetPeriodicAdvertisingEnableCommand ->
                advertiseManager.setPeriodicAdvertisingEnable(command.advertiserId, command.enable)
        }
    }

    /** Returns whether advertising commands should be queued, which is true during suspend. */
    fun shouldQueueCommand(): Boolean {
        return suspendState != SuspendState.NORMAL
    }

    /** Queue a Start Advertising Set command (during suspend). */
    fun queueStartAdvertisingSet(
        parameters: AdvertisingSetParameters,
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData,
        periodicParameters: PeriodicAdvertisingParameters,
        periodicData: AdvertiseData,
        duration: Int,
        maxExtAdvEvents: Int,
        gattServerCallback: IBluetoothGattServerCallback,
        callback: IAdvertisingSetCallback,
        source: AttributionSource,
    ) {
        pendingCommands.add(
            StartAdvertisingSetCommand(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                gattServerCallback,
                callback,
                source,
            )
        )
    }

    /** Queue a Get Own Address command (during suspend). */
    fun queueGetOwnAddress(advertiserId: Int) {
        pendingCommands.add(GetOwnAddressCommand(advertiserId))
    }

    /** Queue a Stop Advertising Set command (during suspend). */
    fun queueStopAdvertisingSet(callback: IAdvertisingSetCallback) {
        pendingCommands.add(StopAdvertisingSetCommand(callback))
    }

    /** Queue a Enable Advertising Set command (during suspend). */
    fun queueEnableAdvertisingSet(
        advertiserId: Int,
        enable: Boolean,
        duration: Int,
        maxExtAdvEvents: Int,
    ) {
        pendingCommands.add(
            EnableAdvertisingSetCommand(advertiserId, enable, duration, maxExtAdvEvents)
        )
    }

    /** Queue a Set Scan Advertising Data command (during suspend). */
    fun queueSetAdvertisingData(advertiserId: Int, data: AdvertiseData) {
        pendingCommands.add(SetAdvertisingDataCommand(advertiserId, data))
    }

    /** Queue a Set Scan Response Data command (during suspend). */
    fun queueSetScanResponseData(advertiserId: Int, data: AdvertiseData) {
        pendingCommands.add(SetScanResponseDataCommand(advertiserId, data))
    }

    /** Queue a Set Advertising Parameters command (during suspend). */
    fun queueSetAdvertisingParameters(advertiserId: Int, parameters: AdvertisingSetParameters) {
        pendingCommands.add(SetAdvertisingParametersCommand(advertiserId, parameters))
    }

    /** Queue a Set Periodic Advertising Parameters command (during suspend). */
    fun queueSetPeriodicAdvertisingParameters(
        advertiserId: Int,
        parameters: PeriodicAdvertisingParameters,
    ) {
        pendingCommands.add(SetPeriodicAdvertisingParametersCommand(advertiserId, parameters))
    }

    /** Queue a Set Periodic Advertising Data command (during suspend). */
    fun queueSetPeriodicAdvertisingData(advertiserId: Int, data: AdvertiseData) {
        pendingCommands.add(SetPeriodicAdvertisingDataCommand(advertiserId, data))
    }

    /** Queue a Set Periodic Advertising Enable command (during suspend). */
    fun queueSetPeriodicAdvertisingEnable(advertiserId: Int, enable: Boolean) {
        pendingCommands.add(SetPeriodicAdvertisingEnableCommand(advertiserId, enable))
    }

    /** Initiates suspend sequence. Resolve ongoing operations then pause all advertisements. */
    fun enterSuspend() {
        suspendState = SuspendState.PAUSING
        // later we pause the advertisements here then call finalizeSuspend
        finalizeSuspend()
    }

    private fun finalizeSuspend() {
        suspendState = SuspendState.SUSPENDED
        adapterService.adapterSuspend.advertiseSuspendReady()
    }

    /** Initiates resume sequence. Enable all paused advertisements. */
    fun exitSuspend() {
        suspendState = SuspendState.RESUMING
        // later we reenable the advertisements here then call finalizeResume
        finalizeResume()
    }

    private fun finalizeResume() {
        suspendState = SuspendState.NORMAL
        for (command in pendingCommands) {
            runPendingCommand(command)
        }
        pendingCommands.clear()
    }
}
