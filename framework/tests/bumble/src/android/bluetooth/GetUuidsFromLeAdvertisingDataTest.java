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

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;

import static pandora.HostProto.DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE;

import android.bluetooth.test_utils.BlockingBluetoothAdapter;
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

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import io.grpc.stub.StreamObserver;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.DataTypes;
import pandora.HostProto.OwnAddressType;
import pandora.SecurityProto.PairingEvent;
import pandora.SecurityProto.PairingEventAnswer;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Test cases for getting BLE UUIDs from {@link BluetoothDevice#ACTION_FOUND}. */
@RunWith(TestParameterInjector.class)
public class GetUuidsFromLeAdvertisingDataTest {
    private static final String TAG = GetUuidsFromLeAdvertisingDataTest.class.getSimpleName();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Mock private BroadcastReceiver mReceiver;

    private static final String TEST_16_BIT_SERVICE_UUID = "1809";
    private static final String TEST_32_BIT_SERVICE_UUID = "12345678";
    private static final String TEST_128_BIT_SERVICE_UUID = "88400001-e95a-844e-c53f-fbec32ed5e54";
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CANCEL_DISCOVERY_WAIT_TIME = Duration.ofMillis(500);

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();
    private InOrder mInOrder;
    private BluetoothDevice mRandomAddressBumbleDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mReceiver);

        mRandomAddressBumbleDevice =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        mContext.registerReceiver(mReceiver, filter);
        Utils.setupIntentLogger(TAG, mReceiver);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        if (bondedDevices.contains(mRandomAddressBumbleDevice)) {
            mRandomAddressBumbleDevice.removeBond();
        }
        if (bondedDevices.contains(mBumble.getRemoteDevice())) {
            mBumble.getRemoteDevice().removeBond();
        }
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsFromServiceUuid(
            @TestParameter boolean usePublicAddress, @TestParameter boolean createLeBond) {
        if (createLeBond) {
            assumeTrue(Flags.getSvcUuidsBugfix());
            createLeBondAndVerify(usePublicAddress);
            restartBluetooth();
        }

        DataTypes dataType =
                DataTypes.newBuilder()
                        .addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID)
                        .addCompleteServiceClassUuids32(TEST_32_BIT_SERVICE_UUID)
                        .addCompleteServiceClassUuids128(TEST_128_BIT_SERVICE_UUID)
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        ParcelUuid[] expectedUuids = {
            new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
            new ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID)),
            new ParcelUuid(Utils.uuidFromString(TEST_128_BIT_SERVICE_UUID))
        };

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsFromServiceData(
            @TestParameter boolean usePublicAddress, @TestParameter boolean createLeBond) {
        if (createLeBond) {
            assumeTrue(Flags.getSvcUuidsBugfix());
            createLeBondAndVerify(usePublicAddress);
            restartBluetooth();
        }

        DataTypes dataType =
                DataTypes.newBuilder()
                        .putServiceDataUuid16(
                                TEST_16_BIT_SERVICE_UUID, ByteString.copyFromUtf8("a"))
                        .putServiceDataUuid32(
                                TEST_32_BIT_SERVICE_UUID, ByteString.copyFromUtf8("b"))
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        ParcelUuid[] expectedUuids = {
            new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
            new ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID))
        };

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    // Due to packet size limit in legacy advertising, separate test for 128 bit UUID.
    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsFromServiceData_128BitUuid(
            @TestParameter boolean usePublicAddress, @TestParameter boolean createLeBond) {
        if (createLeBond) {
            assumeTrue(Flags.getSvcUuidsBugfix());
            createLeBondAndVerify(usePublicAddress);
            restartBluetooth();
        }

        DataTypes dataType =
                DataTypes.newBuilder()
                        .putServiceDataUuid128(
                                TEST_128_BIT_SERVICE_UUID, ByteString.copyFromUtf8("c"))
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        ParcelUuid[] expectedUuids = {
            new ParcelUuid(Utils.uuidFromString(TEST_128_BIT_SERVICE_UUID))
        };

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsFromBothServiceUuidAndData(
            @TestParameter boolean usePublicAddress, @TestParameter boolean createLeBond) {
        if (createLeBond) {
            assumeTrue(Flags.getSvcUuidsBugfix());
            createLeBondAndVerify(usePublicAddress);
            restartBluetooth();
        }

        DataTypes dataType =
                DataTypes.newBuilder()
                        .addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID)
                        .putServiceDataUuid32(
                                TEST_32_BIT_SERVICE_UUID, ByteString.copyFromUtf8("b"))
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        ParcelUuid[] expectedUuids = {
            new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
            new ParcelUuid(Utils.uuidFromString(TEST_32_BIT_SERVICE_UUID))
        };

        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    @RequiresFlagsEnabled({
        Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA,
        Flags.FLAG_GET_SVC_UUIDS_BUGFIX
    })
    @Test
    public void doesNotContainAnyUuidDataType_shouldReturnNullUuid(
            @TestParameter boolean usePublicAddress, @TestParameter boolean createLeBond) {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress);
            restartBluetooth();
        }

        DataTypes dataType =
                DataTypes.newBuilder()
                        // No UUID data types are used.
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();

        // EXTRA_UUID_LE should give null as the advertisement does not contain any Service UUID or
        // Service DATA data type.
        ParcelUuid[] expectedUuids = null;
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    @RequiresFlagsEnabled({
        Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA,
        Flags.FLAG_GET_SVC_UUIDS_BUGFIX
    })
    @Test
    public void uuidTypesAreRemovedFromAdvertisement_shouldReturnNullUuid(
            @TestParameter boolean usePublicAddress, @TestParameter boolean createLeBond)
            throws Exception {
        if (createLeBond) {
            createLeBondAndVerify(usePublicAddress);
            restartBluetooth();
        }

        DataTypes dataType =
                DataTypes.newBuilder()
                        .addCompleteServiceClassUuids16(TEST_16_BIT_SERVICE_UUID)
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        ParcelUuid[] expectedUuids = {
            new ParcelUuid(Utils.uuidFromString(TEST_16_BIT_SERVICE_UUID)),
        };
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);

        // Now, start a new advertisement with no UUIDs. ACTION_FOUND should have null UUIDs.
        mBumble.hostBlocking().factoryReset(Empty.getDefaultInstance());

        try {
            // Need to wait for the canceled discovery truly ends before starting a new discovery.
            // We cannot rely on ACTION_DISCOVERY_FINISHED, because it comes multiple times.
            // If we don't, then sometimes ACTION_FOUND intent is not sent because AdapterService
            // clears the 'discovering package' list with sending ACTION_DISCOVERY_FINISHED.
            Thread.sleep(CANCEL_DISCOVERY_WAIT_TIME.toMillis());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        dataType =
                DataTypes.newBuilder()
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        expectedUuids = null;
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    void createLeBondAndVerify(boolean usePublicAddress) {
        BluetoothDevice device =
                usePublicAddress ? mBumble.getRemoteDevice() : mRandomAddressBumbleDevice;

        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM)
                        .build();
        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.host().advertise(request, responseObserver);

        // Create bond over LE transport
        StreamObserverSpliterator<Void, PairingEvent> pairingEventStreamObserver =
                new StreamObserverSpliterator<>();
        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(pairingEventStreamObserver);
        assertThat(device.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        device.setPairingConfirmation(true);

        PairingEvent pairingEvent = pairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        responseObserver.cancel("Canceling Advertising.");
    }

    void verifyDiscoveryBroadcastUuids(
            DataTypes dataTypes, boolean usePublicAddress, ParcelUuid[] expectedUuids) {
        assertThat(mAdapter.startDiscovery()).isTrue();

        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM)
                        .setData(dataTypes)
                        .setLegacy(true) // Bumble only supports legacy advertising
                        .build();

        // Collect and ignore responses.
        mBumble.host().advertise(request, new StreamObserverSpliterator<>());

        try {
            verifyIntentReceived(hasAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
            verifyIntentReceived(
                    hasAction(BluetoothDevice.ACTION_FOUND),
                    hasExtra(
                            BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE,
                            hasBitSet(BluetoothDevice.DEVICE_TYPE_LE)),
                    hasExtra(BluetoothDevice.EXTRA_UUID_LE, expectedUuids));
        } finally {
            assertThat(mAdapter.cancelDiscovery()).isTrue();
        }
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private static Matcher<Integer> hasBitSet(final int flag) {
        return new CustomTypeSafeMatcher<>("BitSet Matcher") {
            @Override
            protected boolean matchesSafely(Integer item) {
                return (item & flag) != 0;
            }
        };
    }

    private static void restartBluetooth() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
        assertThat(BlockingBluetoothAdapter.enable()).isTrue();
    }
}
