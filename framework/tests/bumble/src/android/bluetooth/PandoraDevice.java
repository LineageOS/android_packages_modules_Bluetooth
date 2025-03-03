/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.google.protobuf.Empty;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;

import org.junit.rules.ExternalResource;

import pandora.BumbleConfigGrpc;
import pandora.DckGrpc;
import pandora.GATTGrpc;
import pandora.HIDGrpc;
import pandora.HostGrpc;
import pandora.HostProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;
import pandora.OOBGrpc;
import pandora.OppGrpc;
import pandora.RFCOMMGrpc;
import pandora.SecurityGrpc;
import pandora.l2cap.L2CAPGrpc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class PandoraDevice extends ExternalResource {
    private static final String TAG = PandoraDevice.class.getSimpleName();

    private final String mNetworkAddress;
    private String mPublicBluetoothAddress;
    private final int mPort;
    private ManagedChannel mChannel;

    public PandoraDevice(String networkAddress, int port) {
        mNetworkAddress = networkAddress;
        mPort = port;
    }

    public PandoraDevice() {
        this("localhost", 7999);
    }

    @Override
    protected void before() {
        Log.i(TAG, "factoryReset");
        // FactoryReset is killing the server and restarting all channels created before the server
        // restarted that cannot be reused
        ManagedChannel channel =
                OkHttpChannelBuilder.forAddress(mNetworkAddress, mPort).usePlaintext().build();
        HostGrpc.HostBlockingStub stub =
                HostGrpc.newBlockingStub(channel).withDeadlineAfter(10000, TimeUnit.MILLISECONDS);
        try {
            stub.factoryReset(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                // Server is shutting down, the call might be canceled with an UNAVAILABLE status
                // because the stream is closed.
            } else {
                throw e;
            }
        }
        try {
            // terminate the channel
            channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        mChannel = OkHttpChannelBuilder.forAddress(mNetworkAddress, mPort).usePlaintext().build();
        stub = HostGrpc.newBlockingStub(mChannel);
        HostProto.ReadLocalAddressResponse readLocalAddressResponse =
                stub.withWaitForReady().readLocalAddress(Empty.getDefaultInstance());
        mPublicBluetoothAddress =
                Utils.addressStringFromByteString(readLocalAddressResponse.getAddress());
    }

    @Override
    protected void after() {
        Log.i(TAG, "shutdown");
        try {
            // terminate the channel
            mChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            mChannel = null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return bumble as a remote device
     */
    public BluetoothDevice getRemoteDevice() {
        return ApplicationProvider.getApplicationContext()
                .getSystemService(BluetoothManager.class)
                .getAdapter()
                .getRemoteDevice(mPublicBluetoothAddress);
    }

    /**
     * Start advertising with Random address type
     *
     * @return Context.CancellableContext
     */
    public Context.CancellableContext advertise() {
        return advertise(OwnAddressType.RANDOM, null, true, true);
    }

    /**
     * Start advertising.
     *
     * @return a Context.CancellableContext to cancel the advertising
     */
    public Context.CancellableContext advertise(OwnAddressType ownAddressType) {
        return advertise(ownAddressType, null, true, true);
    }

    /**
     * Start advertising.
     *
     * @return a Context.CancellableContext to cancel the advertising
     */
    public Context.CancellableContext advertise(
            OwnAddressType ownAddressType, UUID serviceUuid, boolean legacy, boolean connectable) {
        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setLegacy(legacy)
                        .setConnectable(connectable)
                        .setOwnAddressType(ownAddressType);

        if (serviceUuid != null) {
            requestBuilder.setData(
                    HostProto.DataTypes.newBuilder()
                            .addCompleteServiceClassUuids128(serviceUuid.toString())
                            .build());
        }

        Context.CancellableContext cancellableContext = Context.current().withCancellation();
        cancellableContext.run(
                new Runnable() {
                    public void run() {
                        hostBlocking().advertise(requestBuilder.build());
                    }
                });

        return cancellableContext;
    }

    /** Get Pandora Host service */
    public HostGrpc.HostStub host() {
        return HostGrpc.newStub(mChannel);
    }

    /** Get Pandora Host service */
    public HostGrpc.HostBlockingStub hostBlocking() {
        return HostGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora BumbleConfig service */
    public BumbleConfigGrpc.BumbleConfigStub bumbleConfig() {
        return BumbleConfigGrpc.newStub(mChannel);
    }

    /** Get Pandora BumbleConfig service */
    public BumbleConfigGrpc.BumbleConfigBlockingStub bumbleConfigBlocking() {
        return BumbleConfigGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora HID service */
    public HIDGrpc.HIDStub hid() {
        return HIDGrpc.newStub(mChannel);
    }

    /** Get Pandora HID blocking service */
    public HIDGrpc.HIDBlockingStub hidBlocking() {
        return HIDGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora Dck service */
    public DckGrpc.DckStub dck() {
        return DckGrpc.newStub(mChannel);
    }

    /** Get Pandora Dck blocking service */
    public DckGrpc.DckBlockingStub dckBlocking() {
        return DckGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora Security service */
    public SecurityGrpc.SecurityStub security() {
        return SecurityGrpc.newStub(mChannel);
    }

    /** Get Pandora OOB blocking service */
    public OOBGrpc.OOBBlockingStub oobBlocking() {
        return OOBGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora GATT service */
    public GATTGrpc.GATTStub gatt() {
        return GATTGrpc.newStub(mChannel);
    }

    /** Get Pandora GATT blocking service */
    public GATTGrpc.GATTBlockingStub gattBlocking() {
        return GATTGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora RFCOMM service */
    public RFCOMMGrpc.RFCOMMStub rfcomm() {
        return RFCOMMGrpc.newStub(mChannel);
    }

    /** Get Pandora RFCOMM blocking service */
    public RFCOMMGrpc.RFCOMMBlockingStub rfcommBlocking() {
        return RFCOMMGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora L2CAP service */
    public L2CAPGrpc.L2CAPStub l2cap() {
        return L2CAPGrpc.newStub(mChannel);
    }

    /** Get Pandora L2CAP blocking service */
    public L2CAPGrpc.L2CAPBlockingStub l2capBlocking() {
        return L2CAPGrpc.newBlockingStub(mChannel);
    }

    /** Get Pandora OPP service */
    public OppGrpc.OppStub opp() {
        return OppGrpc.newStub(mChannel);
    }

    /** Get Pandora OPP blocking service */
    public OppGrpc.OppBlockingStub oppBlocking() {
        return OppGrpc.newBlockingStub(mChannel);
    }
}
