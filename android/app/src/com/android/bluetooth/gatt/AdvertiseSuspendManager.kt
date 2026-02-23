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
import android.util.Log
import com.android.bluetooth.btservice.AdapterSuspend

private const val TAG = GattUtil.TAG_PREFIX + "AdvertiseSuspendManager"

/**
 * Manages the queueing of advertisement commands during Bluetooth suspend state. This class is
 * responsible for holding commands when the adapter is suspending or suspended, and processing them
 * upon resume. All methods in this class are run inside the advertisement thread of
 * AdvertiseManager.
 */
class AdvertiseSuspendManager(
    private val advertiseManager: AdvertiseManager,
    private val adapterSuspend: AdapterSuspend,
) {

    enum class SuspendState {
        NORMAL, // Carry out requests as usual.
        // For below states, new requests are queued. It will be resolved when state becomes NORMAL.
        RESOLVING, // Wait until ongoing start/enable/disable requests are resolved.
        PAUSING, // Disable (pause) all advertisements.
        SUSPENDED, // Ready to suspend.
        RESUMING, // Enable all paused advertisements.
    }

    class AdvertiserSuspendInfo(
        val duration: Int,
        val maxExtAdvEvents: Int,
        val source: AttributionSource,
    ) {
        var currentlyEnabled = false
        var needEnableOnResume = false
        // The number of ongoing start/enable/disable operations for this advertiser.
        // Initially, this advertiser is waiting to be started.
        var numOfOngoingOperations = 1
    }

    private var suspendState = SuspendState.NORMAL
    private val pendingCommands = mutableListOf<PendingAdvertiseCommand>()

    private val suspendInfoMap = mutableMapOf<Int, AdvertiserSuspendInfo>()
    // The number of advertisers still in transition state. This indicates #adv with ongoing request
    // on RESOLVING, #adv not yet paused on PAUSING, and #adv not yet resumed on RESUMING.
    private var suspendAdvCounter = 0
    // To skip the shouldQueue check - used when en/disabling advertisements internally
    private var forceNoQueue = false

    sealed interface PendingAdvertiseCommand

    data class StartAdvertisingSetCommand(
        val parameters: AdvertisingSetParameters,
        val advertiseData: AdvertiseData?,
        val scanResponse: AdvertiseData?,
        val periodicParameters: PeriodicAdvertisingParameters?,
        val periodicData: AdvertiseData?,
        val duration: Int,
        val maxExtAdvEvents: Int,
        val gattServerCallback: IBluetoothGattServerCallback?,
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
        val source: AttributionSource,
    ) : PendingAdvertiseCommand

    data class SetAdvertisingDataCommand(val advertiserId: Int, val data: AdvertiseData?) :
        PendingAdvertiseCommand

    data class SetScanResponseDataCommand(val advertiserId: Int, val data: AdvertiseData?) :
        PendingAdvertiseCommand

    data class SetAdvertisingParametersCommand(
        val advertiserId: Int,
        val parameters: AdvertisingSetParameters,
    ) : PendingAdvertiseCommand

    data class SetPeriodicAdvertisingParametersCommand(
        val advertiserId: Int,
        val parameters: PeriodicAdvertisingParameters?,
    ) : PendingAdvertiseCommand

    data class SetPeriodicAdvertisingDataCommand(val advertiserId: Int, val data: AdvertiseData?) :
        PendingAdvertiseCommand

    data class SetPeriodicAdvertisingEnableCommand(val advertiserId: Int, val enable: Boolean) :
        PendingAdvertiseCommand

    private fun runPendingCommand(command: PendingAdvertiseCommand) =
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
                    command.source,
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

    /** Returns whether advertising commands should be queued, which is true during suspend. */
    fun shouldQueueCommand() = suspendState != SuspendState.NORMAL && !forceNoQueue

    /** Queue a Start Advertising Set command (during suspend). */
    fun queueStartAdvertisingSet(
        parameters: AdvertisingSetParameters,
        advertiseData: AdvertiseData?,
        scanResponse: AdvertiseData?,
        periodicParameters: PeriodicAdvertisingParameters?,
        periodicData: AdvertiseData?,
        duration: Int,
        maxExtAdvEvents: Int,
        gattServerCallback: IBluetoothGattServerCallback?,
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
        source: AttributionSource,
    ) {
        pendingCommands.add(
            EnableAdvertisingSetCommand(advertiserId, enable, duration, maxExtAdvEvents, source)
        )
    }

    /** Queue a Set Scan Advertising Data command (during suspend). */
    fun queueSetAdvertisingData(advertiserId: Int, data: AdvertiseData?) {
        pendingCommands.add(SetAdvertisingDataCommand(advertiserId, data))
    }

    /** Queue a Set Scan Response Data command (during suspend). */
    fun queueSetScanResponseData(advertiserId: Int, data: AdvertiseData?) {
        pendingCommands.add(SetScanResponseDataCommand(advertiserId, data))
    }

    /** Queue a Set Advertising Parameters command (during suspend). */
    fun queueSetAdvertisingParameters(advertiserId: Int, parameters: AdvertisingSetParameters) {
        pendingCommands.add(SetAdvertisingParametersCommand(advertiserId, parameters))
    }

    /** Queue a Set Periodic Advertising Parameters command (during suspend). */
    fun queueSetPeriodicAdvertisingParameters(
        advertiserId: Int,
        parameters: PeriodicAdvertisingParameters?,
    ) {
        pendingCommands.add(SetPeriodicAdvertisingParametersCommand(advertiserId, parameters))
    }

    /** Queue a Set Periodic Advertising Data command (during suspend). */
    fun queueSetPeriodicAdvertisingData(advertiserId: Int, data: AdvertiseData?) {
        pendingCommands.add(SetPeriodicAdvertisingDataCommand(advertiserId, data))
    }

    /** Queue a Set Periodic Advertising Enable command (during suspend). */
    fun queueSetPeriodicAdvertisingEnable(advertiserId: Int, enable: Boolean) {
        pendingCommands.add(SetPeriodicAdvertisingEnableCommand(advertiserId, enable))
    }

    private fun enableAdvertisingSet(
        advertiserId: Int,
        enable: Boolean,
        duration: Int,
        maxExtAdvEvents: Int,
        source: AttributionSource,
    ) {
        // Skip the state check when en/disabling advertisement internally.
        forceNoQueue = true
        advertiseManager.enableAdvertisingSet(
            advertiserId,
            enable,
            duration,
            maxExtAdvEvents,
            source,
        )
        forceNoQueue = false
    }

    /** Initiates suspend sequence. Resolve ongoing operations then pause all advertisements. */
    fun enterSuspend() {
        Log.i(TAG, "Enter suspend. Current state = $suspendState")

        if (suspendState == SuspendState.NORMAL || suspendState == SuspendState.RESUMING) {
            // Here (re)start the suspend flow from the beginning.
            waitAdvertisementsToResolve()
        } else if (suspendState == SuspendState.SUSPENDED) {
            // We're told to suspend but we're already suspended. Just report ready.
            finalizeSuspend()
        } // Otherwise just continue the ongoing suspend flow.
    }

    private fun waitAdvertisementsToResolve() {
        // Wait for all ongoing start/enable/disable operations to complete before proceeding to
        // pause the advertisements.
        suspendState = SuspendState.RESOLVING

        suspendAdvCounter = suspendInfoMap.values.count { it.numOfOngoingOperations > 0 }

        if (suspendAdvCounter == 0) {
            pauseAdvertisements()
        }
    }

    private fun pauseAdvertisements() {
        suspendState = SuspendState.PAUSING
        if (suspendAdvCounter != 0) {
            Log.w(TAG, "Suspend state is PAUSING but counter isn't zero")
            suspendAdvCounter = 0
        }

        for ((advertiserId, suspendInfo) in suspendInfoMap) {
            // In case of a quick suspend -> resume -> suspend, it's possible to have
            // needEnableOnResume flag still on and currentlyEnabled is off.
            suspendInfo.needEnableOnResume =
                suspendInfo.needEnableOnResume or suspendInfo.currentlyEnabled
            if (suspendInfo.needEnableOnResume) {
                suspendAdvCounter += 1
                enableAdvertisingSet(advertiserId, false, 0, 0, suspendInfo.source)
            }
        }

        if (suspendAdvCounter == 0) {
            finalizeSuspend()
        }
    }

    private fun finalizeSuspend() {
        suspendState = SuspendState.SUSPENDED
        adapterSuspend.advertiseSuspendReady()
    }

    /** Initiates resume sequence. Enable all paused advertisements. */
    fun exitSuspend() {
        Log.i(TAG, "Exit suspend. Current state = $suspendState")
        resumeAdvertisements()
    }

    private fun resumeAdvertisements() {
        suspendState = SuspendState.RESUMING
        suspendAdvCounter = 0

        for ((advertiserId, suspendInfo) in suspendInfoMap) {
            if (suspendInfo.needEnableOnResume) {
                suspendAdvCounter += 1
                enableAdvertisingSet(
                    advertiserId,
                    true,
                    suspendInfo.duration,
                    suspendInfo.maxExtAdvEvents,
                    suspendInfo.source,
                )
            }
        }

        if (suspendAdvCounter == 0) {
            finalizeResume()
        }
    }

    private fun finalizeResume() {
        suspendState = SuspendState.NORMAL

        pendingCommands.forEach(::runPendingCommand)
        pendingCommands.clear()
    }

    /** To be called from AdvertiseManager when starting an advertising set. */
    fun onStartAdvertisingSet(
        regId: Int,
        duration: Int,
        maxExtAdvEvents: Int,
        source: AttributionSource,
    ) {
        suspendInfoMap[regId] = AdvertiserSuspendInfo(duration, maxExtAdvEvents, source)
    }

    /** To be called from AdvertiseManager when stopping an advertising set. */
    fun onStopAdvertisingSet(advertiserId: Int) {
        suspendInfoMap.remove(advertiserId)
    }

    /** To be called from AdvertiseManager when enabling an advertising set. */
    fun onEnableAdvertisingSet(advertiserId: Int) {
        val suspendInfo = suspendInfoMap[advertiserId]
        if (suspendInfo == null) {
            Log.wtf(TAG, "onEnableAdvertisingSet: suspendInfo is null for id $advertiserId")
            return
        }
        suspendInfo.numOfOngoingOperations += 1
    }

    /** To be called from AdvertiseManager when an advertising set is started. */
    fun onAdvertisingSetStarted(regId: Int, advertiserId: Int, status: Int) {
        val suspendInfo = suspendInfoMap.remove(regId)
        if (suspendInfo == null) {
            Log.wtf(TAG, "onAdvertisingSetStarted: suspendInfo is null for id $regId")
            return
        }
        if (status == 0) {
            suspendInfo.numOfOngoingOperations -= 1
            suspendInfo.currentlyEnabled = true
            suspendInfoMap[advertiserId] = suspendInfo
        }
        if (suspendState == SuspendState.RESOLVING) {
            suspendAdvCounter -= 1
            if (suspendAdvCounter == 0) {
                Log.i(TAG, "All ongoing operations resolved, pausing advertisements.")
                pauseAdvertisements()
            }
        }
    }

    /**
     * To be called from AdvertiseManager when an advertising set is enabled.
     *
     * @return true if the callback of AdvertiseManager's onAdvertisingEnabled should be called
     */
    fun onAdvertisingEnabled(advertiserId: Int, enable: Boolean, status: Int): Boolean {
        val suspendInfo = suspendInfoMap[advertiserId]
        if (suspendInfo == null) {
            Log.wtf(TAG, "onAdvertisingEnabled: suspendInfo is null for id $advertiserId")
            return false
        }
        val wasEnabled = suspendInfo.currentlyEnabled
        var shouldCallCallback = true

        if (suspendState == SuspendState.PAUSING) {
            if (wasEnabled && !enable) {
                // Normal disablement - don't invoke callback
                suspendAdvCounter -= 1
                shouldCallCallback = false
                if (suspendAdvCounter == 0) {
                    finalizeSuspend()
                }
            } else {
                Log.w(TAG, "Unexpected event when pausing: was $wasEnabled now $enable")
            }
        } else if (suspendState == SuspendState.RESUMING) {
            val needEnable = suspendInfo.needEnableOnResume
            if (!wasEnabled && enable && needEnable) {
                // Normal re-enablement - don't invoke callback.
                suspendAdvCounter -= 1
                suspendInfo.needEnableOnResume = false
                shouldCallCallback = false
            } else if (!wasEnabled && !enable && status != 0 && needEnable) {
                // Re-enablement failed! Let's invoke callback to let the app know.
                suspendAdvCounter -= 1
                suspendInfo.needEnableOnResume = false
            } else {
                Log.w(
                    TAG,
                    "Unexpected event when resuming: need $needEnable was $wasEnabled now $enable",
                )
            }

            if (suspendAdvCounter == 0) {
                finalizeResume()
            }
        }

        suspendInfo.currentlyEnabled = enable
        suspendInfo.numOfOngoingOperations -= 1
        if (suspendState == SuspendState.RESOLVING && suspendInfo.numOfOngoingOperations == 0) {
            suspendAdvCounter -= 1
            if (suspendAdvCounter == 0) {
                pauseAdvertisements()
            }
        }

        return shouldCallCallback
    }

    /** Frees structures. */
    fun cleanup() {
        suspendInfoMap.clear()
        pendingCommands.clear()
    }
}
