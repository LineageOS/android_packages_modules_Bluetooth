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

package com.android.bluetooth.btservice

import android.bluetooth.OobData
import android.bluetooth.UidTraffic
import com.android.bluetooth.profile.NativeCallback

class AdapterNativeCallback(
    adapterService: AdapterService,
    private val adapterProperties: AdapterProperties,
) : NativeCallback(adapterService) {

    private var remoteDevices: RemoteDevices? = null
    private var bondStateMachine: BondStateMachine? = null

    fun init(bondStateMachine: BondStateMachine, remoteDevices: RemoteDevices) {
        this.remoteDevices = remoteDevices
        this.bondStateMachine = bondStateMachine
    }

    fun cleanup() {
        remoteDevices = null
        bondStateMachine = null
    }

    fun sspRequestCallback(
        address: ByteArray,
        transport: Int,
        pairingVariant: Int,
        passkey: Int,
        pairingAlgorithm: Int,
    ) {
        bondStateMachine?.sspRequestCallback(
            address,
            transport,
            pairingVariant,
            passkey,
            pairingAlgorithm,
        )
    }

    fun devicePropertyChangedCallback(
        address: ByteArray,
        addressType: Int,
        types: IntArray,
        value: Array<ByteArray>,
    ) {
        remoteDevices?.devicePropertyChangedCallback(address, addressType, types, value)
    }

    fun deviceFoundCallback(address: ByteArray) {
        remoteDevices?.deviceFoundCallback(address)
    }

    fun pinRequestCallback(
        address: ByteArray,
        name: ByteArray,
        cod: Int,
        min16Digits: Boolean,
        pairingAlgorithm: Int,
    ) {
        bondStateMachine?.pinRequestCallback(address, name, cod, min16Digits, pairingAlgorithm)
    }

    fun bondStateChangeCallback(
        status: Int,
        address: ByteArray,
        transport: Int,
        newState: Int,
        pairingAlgorithm: Int,
        pairingVariant: Int,
        pairingInitiator: Int,
        hciReason: Int,
    ) {
        bondStateMachine?.bondStateChangeCallback(
            status,
            address,
            transport,
            newState,
            pairingAlgorithm,
            pairingVariant,
            pairingInitiator,
            hciReason,
        )
    }

    fun addressConsolidateCallback(mainAddress: ByteArray, secondaryAddress: ByteArray) {
        remoteDevices?.addressConsolidateCallback(mainAddress, secondaryAddress)
    }

    fun leAddressAssociateCallback(
        mainAddress: ByteArray,
        secondaryAddress: ByteArray,
        identityAddressTypeFromNative: Int,
    ) {
        remoteDevices?.leAddressAssociateCallback(
            mainAddress,
            secondaryAddress,
            identityAddressTypeFromNative,
        )
    }

    fun aclStateChangeCallback(
        status: Int,
        address: ByteArray,
        addressType: Int,
        transport: Int,
        newState: Int,
        hciReason: Int,
        handle: Int,
    ) {
        remoteDevices?.aclStateChangeCallback(
            status,
            address,
            addressType,
            transport,
            newState,
            hciReason,
            handle,
        )
    }

    fun keyMissingCallback(address: ByteArray, reason: Int) {
        remoteDevices?.keyMissingCallback(address, reason)
    }

    fun encryptionChangeCallback(
        address: ByteArray,
        status: Int,
        encryptionEnable: Boolean,
        transport: Int,
        encryptionAlgo: Int,
        keySize: Int,
    ) {
        remoteDevices?.encryptionChangeCallback(
            address,
            status,
            encryptionEnable,
            transport,
            encryptionAlgo,
            keySize,
        )
    }

    fun stateChangeCallback(status: Int) = adapterService.stateChangeCallback(status)

    fun discoveryStateChangeCallback(state: Int) =
        adapterProperties.discoveryStateChangeCallback(state)

    fun adapterPropertyChangedCallback(types: IntArray, value: Array<ByteArray>) =
        adapterProperties.adapterPropertyChangedCallback(types, value)

    fun oobDataReceivedCallback(transport: Int, oobData: OobData) =
        adapterService.notifyOobDataCallback(transport, oobData)

    fun linkQualityReportCallback(
        timestamp: Long,
        reportId: Int,
        rssi: Int,
        snr: Int,
        retransmissionCount: Int,
        packetsNotReceiveCount: Int,
        negativeAcknowledgementCount: Int,
    ) =
        adapterService.linkQualityReportCallback(
            timestamp,
            reportId,
            rssi,
            snr,
            retransmissionCount,
            packetsNotReceiveCount,
            negativeAcknowledgementCount,
        )

    fun switchBufferSizeCallback(isLowLatencyBufferSize: Boolean) =
        adapterService.switchBufferSizeCallback(isLowLatencyBufferSize)

    fun switchCodecCallback(isLowLatencyBufferSize: Boolean) =
        adapterService.switchCodecCallback(isLowLatencyBufferSize)

    fun acquireWakeLock(lockName: String) = adapterService.acquireWakeLock(lockName)

    fun releaseWakeLock(lockName: String) = adapterService.releaseWakeLock(lockName)

    fun energyInfoCallback(
        status: Int,
        ctrlState: Int,
        txTime: Long,
        rxTime: Long,
        idleTime: Long,
        energyUsed: Long,
        data: Array<UidTraffic>,
    ) =
        adapterService.energyInfoCallback(
            status,
            ctrlState,
            txTime,
            rxTime,
            idleTime,
            energyUsed,
            data,
        )
}
