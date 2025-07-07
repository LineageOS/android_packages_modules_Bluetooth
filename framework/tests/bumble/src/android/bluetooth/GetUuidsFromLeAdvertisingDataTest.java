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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;

import static pandora.HostProto.DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE;

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

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.DataTypes;
import pandora.HostProto.OwnAddressType;

import java.time.Duration;

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

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothAdapter mAdapter =
            mContext.getSystemService(BluetoothManager.class).getAdapter();
    private InOrder mInOrder;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mReceiver);
    }

    @RequiresFlagsEnabled(Flags.FLAG_GET_SVC_UUIDS_FROM_BLE_ADV_DATA)
    @Test
    public void getUuidsFromServiceUuid(@TestParameter boolean usePublicAddress) {
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
    public void getUuidsFromServiceData(@TestParameter boolean usePublicAddress) {
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
    public void getUuidsFromServiceData_128BitUuid(@TestParameter boolean usePublicAddress) {
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
    public void getUuidsFromBothServiceUuidAndData(@TestParameter boolean usePublicAddress) {
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
            @TestParameter boolean usePublicAddress) {
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
            @TestParameter boolean usePublicAddress) throws Exception {
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
        dataType =
                DataTypes.newBuilder()
                        .setLeDiscoverabilityModeValue(DISCOVERABLE_GENERAL_VALUE)
                        .build();
        expectedUuids = null;
        verifyDiscoveryBroadcastUuids(dataType, usePublicAddress, expectedUuids);
    }

    void verifyDiscoveryBroadcastUuids(
            DataTypes dataTypes, boolean usePublicAddress, ParcelUuid[] expectedUuids) {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setOwnAddressType(
                                usePublicAddress ? OwnAddressType.PUBLIC : OwnAddressType.RANDOM)
                        .setData(dataTypes)
                        .setLegacy(true) // Bumble only supports legacy advertising
                        .build();

        // Collect and ignore responses.
        mBumble.host().advertise(request, new StreamObserverSpliterator<>());

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        mContext.registerReceiver(mReceiver, filter);
        Utils.setupIntentLogger(TAG, mReceiver);

        assertThat(mAdapter.startDiscovery()).isTrue();
        try {
            verifyIntentReceived(hasAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
            verifyIntentReceived(
                    hasAction(BluetoothDevice.ACTION_FOUND),
                    hasExtra(
                            BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE,
                            hasBitSet(BluetoothDevice.DEVICE_TYPE_LE)),
                    hasExtra(BluetoothDevice.EXTRA_UUID_LE, expectedUuids));
        } finally {
            mContext.unregisterReceiver(mReceiver);
            assertThat(mAdapter.cancelDiscovery()).isTrue();
        }
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private static Matcher<Integer> hasBitSet(final int flag) {
        return new CustomTypeSafeMatcher<Integer>("BitSet Matcher") {
            @Override
            protected boolean matchesSafely(Integer item) {
                return (item & flag) != 0;
            }
        };
    }
}
