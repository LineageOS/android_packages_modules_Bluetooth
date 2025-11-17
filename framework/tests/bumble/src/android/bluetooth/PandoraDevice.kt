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

package android.bluetooth

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.Empty
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource
import pandora.BumbleConfigGrpc
import pandora.DckGrpc
import pandora.GATTGrpc
import pandora.HFPGrpc
import pandora.HIDGrpc
import pandora.HostGrpc
import pandora.HostProto
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.OwnAddressType
import pandora.OOBGrpc
import pandora.OppGrpc
import pandora.RFCOMMGrpc
import pandora.SecurityGrpc
import pandora.SecurityStorageGrpc
import pandora.l2cap.L2CAPGrpc

private const val TAG = "PandoraDevice"

class PandoraDevice(
    private val networkAddress: String = "localhost",
    private val port: Int = 7999,
) : ExternalResource() {

    private lateinit var publicBluetoothAddress: String
    private var channel: ManagedChannel? = null

    protected override fun before() {
        Log.i(TAG, "factoryReset")
        // FactoryReset is killing the server and restarting all channels created before the server
        // restarted that cannot be reused
        val ManagedChannel =
            OkHttpChannelBuilder.forAddress(networkAddress, port).usePlaintext().build()
        val stub =
            HostGrpc.newBlockingStub(ManagedChannel).withDeadlineAfter(10000, TimeUnit.MILLISECONDS)
        try {
            stub.factoryReset(Empty.getDefaultInstance())
        } catch (e: StatusRuntimeException) {
            if (
                e.status.code == Status.Code.CANCELLED || e.status.code == Status.Code.UNAVAILABLE
            ) {
                // Server is shutting down, the call might be canceled with a CANCELLED or
                // UNAVAILABLE status because the stream is closed.
            } else {
                throw e
            }
        }
        try {
            // terminate the channel
            ManagedChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
        channel = OkHttpChannelBuilder.forAddress(networkAddress, port).usePlaintext().build()
        val stub2 = HostGrpc.newBlockingStub(channel)
        val readLocalAddressResponse =
            stub2
                .withWaitForReady()
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .readLocalAddress(Empty.getDefaultInstance())
        publicBluetoothAddress = readLocalAddressResponse.address.toAddressString()
        Log.i(TAG, "factoryReset complete")
    }

    override fun after() {
        Log.i(TAG, "shutdown")
        try {
            // terminate the channel
            channel!!.shutdown().awaitTermination(1, TimeUnit.SECONDS)
            channel = null
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    /** @return bumble as a remote device */
    val remoteDevice
        get() =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(BluetoothManager::class.java)
                .adapter
                .getRemoteDevice(publicBluetoothAddress)

    /**
     * Start advertising.
     *
     * @return a Context.CancellableContext to cancel the advertising
     */
    fun advertise(
        ownAddressType: OwnAddressType = OwnAddressType.RANDOM,
        serviceUuid: UUID? = null,
        legacy: Boolean = true,
        connectable: Boolean = true,
    ): Context.CancellableContext {
        val requestBuilder =
            AdvertiseRequest.newBuilder()
                .setLegacy(legacy)
                .setConnectable(connectable)
                .setOwnAddressType(ownAddressType)

        if (serviceUuid != null) {
            requestBuilder.data =
                HostProto.DataTypes.newBuilder()
                    .addCompleteServiceClassUuids128(serviceUuid.toString())
                    .build()
        }

        val cancellableContext = Context.current().withCancellation()
        cancellableContext.run { hostBlocking().advertise(requestBuilder.build()) }
        return cancellableContext
    }

    /** Get Pandora Host service */
    fun host() = HostGrpc.newStub(channel)

    /** Get Pandora Host service */
    fun hostBlocking() = HostGrpc.newBlockingStub(channel)

    /** Get Pandora BumbleConfig service */
    fun bumbleConfig() = BumbleConfigGrpc.newStub(channel)

    /** Get Pandora BumbleConfig service */
    fun bumbleConfigBlocking() = BumbleConfigGrpc.newBlockingStub(channel)

    /** Get Pandora HID service */
    fun hid() = HIDGrpc.newStub(channel)

    /** Get Pandora HID blocking service */
    fun hidBlocking() = HIDGrpc.newBlockingStub(channel)

    /** Get Pandora Dck service */
    fun dck() = DckGrpc.newStub(channel)

    /** Get Pandora Dck blocking service */
    fun dckBlocking() = DckGrpc.newBlockingStub(channel)

    /** Get Pandora Security service */
    fun security() = SecurityGrpc.newStub(channel)

    /** Get Pandora Security Storage blocking service */
    fun securityStorageBlocking() = SecurityStorageGrpc.newBlockingStub(channel)

    /** Get Pandora OOB blocking service */
    fun oobBlocking() = OOBGrpc.newBlockingStub(channel)

    /** Get Pandora GATT service */
    fun gatt() = GATTGrpc.newStub(channel)

    /** Get Pandora GATT blocking service */
    fun gattBlocking() = GATTGrpc.newBlockingStub(channel)

    /** Get Pandora RFCOMM service */
    fun rfcomm() = RFCOMMGrpc.newStub(channel)

    /** Get Pandora RFCOMM blocking service */
    fun rfcommBlocking() = RFCOMMGrpc.newBlockingStub(channel)

    /** Get Pandora L2CAP service */
    fun l2cap() = L2CAPGrpc.newStub(channel)

    /** Get Pandora L2CAP blocking service */
    fun l2capBlocking() = L2CAPGrpc.newBlockingStub(channel)

    /** Get Pandora OPP service */
    fun opp() = OppGrpc.newStub(channel)

    /** Get Pandora OPP blocking service */
    fun oppBlocking() = OppGrpc.newBlockingStub(channel)

    /** Get Pandora HFP service */
    fun hf() = HFPGrpc.newStub(channel)

    /** Get Pandora HFP blocking service */
    fun hfBlocking() = HFPGrpc.newBlockingStub(channel)

    companion object {
        /**
         * static method to create second pandora device on port 7998
         *
         * @return PandoraDevice object
         */
        fun createSecondPandoraDevice() = PandoraDevice("localhost", 7998)
    }
}
