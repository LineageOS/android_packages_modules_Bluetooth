/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothGattCallback;
import android.content.AttributionSource;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.gatt.ContextMap.App;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.List;
import java.util.UUID;

/** Test cases for {@link ContextMap}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ContextMapTest {
    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AttributionSource mAttributionSource;
    @Mock private AdapterService mAdapterService;
    @Mock private IBluetoothGattCallback mMockCallback;
    @Mock private PackageManager mMockPackageManager;

    private static final String APP_NAME = "com.android.what.a.name";
    private static final int APP_ID1 = 123;
    private static final int APP_ID2 = 987;
    private static final int CONN_ID1 = 321;
    private static final int CONN_ID2 = 654;
    private static final int CONN_ID3 = 987;
    private static final UUID RANDOM_UUID1 = UUID.randomUUID();
    private static final UUID RANDOM_UUID2 = UUID.randomUUID();

    private final BluetoothDevice mDevice1 = getTestDevice(34);
    private final BluetoothDevice mDevice2 = getTestDevice(58);

    @Before
    public void setUp() throws Exception {
        doReturn(mMockPackageManager).when(mAdapterService).getPackageManager();
        doReturn(APP_NAME).when(mMockPackageManager).getNameForUid(anyInt());
    }

    @Test
    public void getAppMethods() {
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        App contextMapById = contextMap.getById(APP_ID1);
        assertThat(contextMapById.getPackageName()).isEqualTo(APP_NAME);
        assertThat(contextMapById.getCallback()).isEqualTo(mMockCallback);
        assertThat(contextMapById.mUuid).isEqualTo(RANDOM_UUID1);
        App contextMapByUuid = contextMap.getByUuid(RANDOM_UUID1);
        assertThat(contextMapByUuid.getPackageName()).isEqualTo(APP_NAME);
        App contextMapByConn = contextMap.getByConnId(CONN_ID1);
        assertThat(contextMapByConn.getPackageName()).isEqualTo(APP_NAME);

        List<Integer> ids = contextMap.getAllAppsIds();
        assertThat(ids).containsExactly(APP_ID1, APP_ID2);
    }

    @Test
    public void getConnMethods() {
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        assertThat(contextMap.getConnectedDevices()).containsExactly(mDevice1, mDevice2);

        List<ContextMap.Connection> connections =
                contextMap.getConnectionsByDevice(APP_ID1, mDevice1);
        assertThat(connections).isNotNull();
        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).connId()).isEqualTo(CONN_ID1);
        assertThat(connections.get(0).device()).isEqualTo(mDevice1);
        assertThat(connections.get(0).transport()).isEqualTo(TRANSPORT_LE);
        assertThat(connections.get(0).appId()).isEqualTo(APP_ID1);

        connections = contextMap.getConnectionsByDevice(APP_ID2, mDevice2);
        assertThat(connections).isNotNull();
        assertThat(connections).hasSize(2);
        assertThat(connections.get(0).connId()).isEqualTo(CONN_ID2);
        assertThat(connections.get(0).device()).isEqualTo(mDevice2);
        assertThat(connections.get(0).transport()).isEqualTo(TRANSPORT_BREDR);
        assertThat(connections.get(0).appId()).isEqualTo(APP_ID2);

        assertThat(connections.get(1).connId()).isEqualTo(CONN_ID3);
        assertThat(connections.get(1).device()).isEqualTo(mDevice2);
        assertThat(connections.get(1).transport()).isEqualTo(TRANSPORT_LE);
        assertThat(connections.get(1).appId()).isEqualTo(APP_ID2);

        assertThat(contextMap.getConnectionsByDevice(APP_ID1, mDevice2)).isEmpty();
        assertThat(contextMap.getConnectionsByDevice(APP_ID2, mDevice1)).isEmpty();
        assertThat(contextMap.getConnectionsByDevice(123456, mDevice1)).isEmpty();
        assertThat(contextMap.getConnectionsByDevice(123456, mDevice2)).isEmpty();

        assertThat(contextMap.deviceByConnId(CONN_ID1)).isEqualTo(mDevice1);
        assertThat(contextMap.deviceByConnId(CONN_ID2)).isEqualTo(mDevice2);
        assertThat(contextMap.deviceByConnId(123456)).isNull();

        connections = contextMap.getConnectionByApp(APP_ID1);
        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).connId()).isEqualTo(CONN_ID1);
        assertThat(connections.get(0).device()).isEqualTo(mDevice1);
        assertThat(connections.get(0).transport()).isEqualTo(TRANSPORT_LE);
        assertThat(connections.get(0).appId()).isEqualTo(APP_ID1);
        assertThat(contextMap.getConnectionByApp(APP_ID2)).hasSize(2);
        assertThat(contextMap.getConnectionByApp(123456)).isEmpty();
        assertThat(contextMap.getConnectedMap())
                .containsExactly(APP_ID1, mDevice1, APP_ID2, mDevice2);
    }

    @Test
    public void clear() {
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        contextMap.clear();
        assertThat(contextMap.getConnectedMap()).isEmpty();
        assertThat(contextMap.getAllAppsIds()).isEmpty();
    }

    @Test
    public void removeMethods() {
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        contextMap.remove(APP_ID1, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        assertThat(contextMap.getAllAppsIds()).isNotEmpty();
        contextMap.remove(APP_ID2, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        assertThat(contextMap.getAllAppsIds()).isEmpty();

        contextMap = getMapWithAppAndConnection();
        contextMap.remove(RANDOM_UUID1, ContextMap.RemoveReason.REASON_REGISTER_FAILED);
        assertThat(contextMap.getAllAppsIds()).isNotEmpty();
        contextMap.remove(RANDOM_UUID2, ContextMap.RemoveReason.REASON_REGISTER_FAILED);
        assertThat(contextMap.getAllAppsIds()).isEmpty();

        contextMap = getMapWithAppAndConnection();
        contextMap.removeConnection(APP_ID1, CONN_ID1);
        assertThat(contextMap.getConnectedMap()).isNotEmpty();
        contextMap.removeConnection(APP_ID2, CONN_ID2);
        assertThat(contextMap.getConnectedMap()).isNotEmpty();
        contextMap.removeConnection(APP_ID2, CONN_ID3);
        assertThat(contextMap.getConnectedMap()).isEmpty();

        contextMap = getMapWithAppAndConnection();
        contextMap.removeConnectionsByAppId(APP_ID1);
        assertThat(contextMap.getConnectedMap()).isNotEmpty();
        contextMap.removeConnectionsByAppId(APP_ID2);
        assertThat(contextMap.getConnectedMap()).isEmpty();
    }

    @Test
    public void dump_withActiveApps_doesNotShowLastApps() {
        StringBuilder sb = new StringBuilder();
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        contextMap.dump(sb);

        String dumpOutput = sb.toString();
        assertThat(dumpOutput).contains("Entries: 2");
        assertThat(dumpOutput).contains("Last apps:");
        // Check that no AppRecord is printed
        assertThat(dumpOutput).doesNotContain("AppRecord<");
    }

    @Test
    public void dump_withRemovedApp_containsAppRecord() {
        ContextMap<IBluetoothGattCallback> contextMap = new ContextMap<>();
        App app =
                contextMap.add(
                        RANDOM_UUID1,
                        mMockCallback,
                        TRANSPORT_LE,
                        mAdapterService,
                        mAttributionSource);
        app.id = APP_ID1;

        // Remove the app to create an AppRecord in mLastRecords
        contextMap.remove(APP_ID1, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);

        StringBuilder sb = new StringBuilder();
        contextMap.dump(sb);

        String dumpOutput = sb.toString();
        assertThat(dumpOutput).contains("Last apps:");
        assertThat(dumpOutput).contains("app_if: " + APP_ID1);
        assertThat(dumpOutput).contains("appName: " + APP_NAME);
        assertThat(dumpOutput)
                .contains("reason: " + ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT);
        // Also check that the app is no longer in the main list
        assertThat(dumpOutput).contains("Entries: 0");
    }

    private ContextMap<IBluetoothGattCallback> getMapWithAppAndConnection() {
        ContextMap<IBluetoothGattCallback> contextMap = new ContextMap<>();
        App app =
                contextMap.add(
                        RANDOM_UUID1,
                        mMockCallback,
                        TRANSPORT_LE,
                        mAdapterService,
                        mAttributionSource);
        app.id = APP_ID1;
        app =
                contextMap.add(
                        RANDOM_UUID2,
                        mMockCallback,
                        TRANSPORT_LE,
                        mAdapterService,
                        mAttributionSource);
        app.id = APP_ID2;

        contextMap.addConnection(APP_ID1, CONN_ID1, TRANSPORT_LE, mDevice1);

        contextMap.addConnection(APP_ID2, CONN_ID2, TRANSPORT_BREDR, mDevice2);
        contextMap.addConnection(APP_ID2, CONN_ID3, TRANSPORT_LE, mDevice2);

        assertThat(contextMap.getConnectedMap()).isNotEmpty();
        assertThat(contextMap.getAllAppsIds()).isNotEmpty();
        return contextMap;
    }
}
