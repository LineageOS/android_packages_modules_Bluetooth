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

package android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.DataTypes;
import pandora.HostProto.DiscoverabilityMode;
import pandora.HostProto.OwnAddressType;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Test cases for getting BLE UUIDs from {@link BluetoothDevice#ACTION_FOUND}. */
@RunWith(TestParameterInjector.class)
public class GetUuidsFromLeAdvertisingDataTest {

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    private static final String TEST_16_BIT_SERVICE_UUID = "1809";
    private static final String TEST_32_BIT_SERVICE_UUID = "12345678";
    private static final String TEST_128_BIT_SERVICE_UUID = "88400001-e95a-844e-c53f-fbec32ed5e54";

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();

    private final SettableFuture<String> mFutureDiscoveryStartedIntent = SettableFuture.create();
    private final SettableFuture<Intent> mDeviceFoundIntent = SettableFuture.create();

    private final BroadcastReceiver mConnectionStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(intent.getAction())) {
                        mFutureDiscoveryStartedIntent.set(
                                BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                    } else if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                        int discoveryResultType =
                                intent.getIntExtra(
                                        BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE,
                                        BluetoothDevice.DEVICE_TYPE_UNKNOWN);
                        if ((discoveryResultType & BluetoothDevice.DEVICE_TYPE_LE) != 0) {
                            mDeviceFoundIntent.set(intent);
                        }
                    }
                }
            };

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsInBleAdvertisingData_fromServiceUuid(
            @TestParameter boolean usePublicAddress) throws Exception {
        DataTypes.Builder dataTypeBuilder = DataTypes.newBuilder();
        dataTypeBuilder.addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID);
        dataTypeBuilder.addCompleteServiceClassUuids32(TEST_32_BIT_SERVICE_UUID);
        dataTypeBuilder.addCompleteServiceClassUuids128(TEST_128_BIT_SERVICE_UUID);
        dataTypeBuilder.setLeDiscoverabilityModeValue(
                DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE);

        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM);
        requestBuilder.setData(dataTypeBuilder.build());
        requestBuilder.setLegacy(true); // Bumble only supports legacy advertising

        advertiseWithBumble(requestBuilder);
        Intent foundIntent = startDiscovery();

        List<ParcelUuid> uuids =
                Arrays.asList(
                        foundIntent.getParcelableArrayExtra(
                                BluetoothDevice.EXTRA_UUID_LE, ParcelUuid.class));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_128_BIT_SERVICE_UUID)));
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsInBleAdvertisingData_fromServiceData(
            @TestParameter boolean usePublicAddress) throws Exception {
        DataTypes.Builder dataTypeBuilder = DataTypes.newBuilder();
        dataTypeBuilder.putServiceDataUuid16(
                TEST_16_BIT_SERVICE_UUID, ByteString.copyFromUtf8("a"));
        dataTypeBuilder.putServiceDataUuid32(
                TEST_32_BIT_SERVICE_UUID, ByteString.copyFromUtf8("b"));
        dataTypeBuilder.setLeDiscoverabilityModeValue(
                DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE);

        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM);
        requestBuilder.setData(dataTypeBuilder.build());
        requestBuilder.setLegacy(true); // Bumble only supports legacy advertising

        advertiseWithBumble(requestBuilder);
        Intent foundIntent = startDiscovery();

        List<ParcelUuid> uuids =
                Arrays.asList(
                        foundIntent.getParcelableArrayExtra(
                                BluetoothDevice.EXTRA_UUID_LE, ParcelUuid.class));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)));
    }

    // Due to packet size limit in legacy advertising, separate test for 128 bit UUID.
    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsInBleAdvertisingData_fromServiceData_128BitUuid(
            @TestParameter boolean usePublicAddress) throws Exception {
        DataTypes.Builder dataTypeBuilder = DataTypes.newBuilder();
        dataTypeBuilder.putServiceDataUuid128(
                TEST_128_BIT_SERVICE_UUID, ByteString.copyFromUtf8("c"));
        dataTypeBuilder.setLeDiscoverabilityModeValue(
                DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE);

        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM);
        requestBuilder.setData(dataTypeBuilder.build());
        requestBuilder.setLegacy(true); // Bumble only supports legacy advertising

        advertiseWithBumble(requestBuilder);
        Intent foundIntent = startDiscovery();

        List<ParcelUuid> uuids =
                Arrays.asList(
                        foundIntent.getParcelableArrayExtra(
                                BluetoothDevice.EXTRA_UUID_LE, ParcelUuid.class));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_128_BIT_SERVICE_UUID)));
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsInBleAdvertisingData_fromBothServiceUuidAndData(
            @TestParameter boolean usePublicAddress) throws Exception {
        DataTypes.Builder dataTypeBuilder = DataTypes.newBuilder();
        dataTypeBuilder.addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID);
        dataTypeBuilder.putServiceDataUuid32(
                TEST_32_BIT_SERVICE_UUID, ByteString.copyFromUtf8("b"));
        dataTypeBuilder.setLeDiscoverabilityModeValue(
                DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE);

        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM);
        requestBuilder.setData(dataTypeBuilder.build());
        requestBuilder.setLegacy(true); // Bumble only supports legacy advertising

        advertiseWithBumble(requestBuilder);
        Intent foundIntent = startDiscovery();

        List<ParcelUuid> uuids =
                Arrays.asList(
                        foundIntent.getParcelableArrayExtra(
                                BluetoothDevice.EXTRA_UUID_LE, ParcelUuid.class));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)));
        assertThat(uuids).contains(new ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)));
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsInBleAdvertisingData_doesNotContainUuidDataType_shouldReturnNullUuid(
            @TestParameter boolean usePublicAddress) throws Exception {
        DataTypes.Builder dataTypeBuilder = DataTypes.newBuilder();
        // No UUID data types are used.
        dataTypeBuilder.setLeDiscoverabilityModeValue(
                DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE);

        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM);
        requestBuilder.setData(dataTypeBuilder.build());
        requestBuilder.setLegacy(true); // Bumble only supports legacy advertising

        advertiseWithBumble(requestBuilder);
        Intent foundIntent = startDiscovery();

        // EXTRA_UUID_LE should give null as the advertisement does not contain
        // any Service UUID or Service DATA data type.
        assertThat(
                        foundIntent.getParcelableArrayExtra(
                                BluetoothDevice.EXTRA_UUID_LE, ParcelUuid.class))
                .isNull();
    }

    /* Starts discovery and return the ACTION_FOUND when LE adv data is received. */
    // TODO(b/408327820): Use Espresso and Hamcrest matcher to verify that intent is received.
    Intent startDiscovery() throws Exception {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        mContext.registerReceiver(mConnectionStateReceiver, filter);

        assertThat(mAdapter.startDiscovery()).isTrue();
        assertThat(mFutureDiscoveryStartedIntent.get())
                .isEqualTo(BluetoothAdapter.ACTION_DISCOVERY_STARTED);

        Intent intent = mDeviceFoundIntent.get(5, TimeUnit.SECONDS);

        assertThat(mAdapter.cancelDiscovery()).isTrue();
        mContext.unregisterReceiver(mConnectionStateReceiver);

        return intent;
    }

    private void advertiseWithBumble(AdvertiseRequest.Builder requestBuilder) {
        // Collect and ignore responses.
        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.host().advertise(requestBuilder.build(), responseObserver);
    }
}
