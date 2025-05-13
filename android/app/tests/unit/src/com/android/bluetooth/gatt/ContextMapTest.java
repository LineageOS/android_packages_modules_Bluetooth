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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothGattCallback;
import android.content.AttributionSource;
import android.content.pm.PackageManager;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.gatt.ContextMap.App;

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
        assertThat(contextMapById.packageName).isEqualTo(APP_NAME);
        assertThat(contextMapById.callback).isEqualTo(mMockCallback);
        assertThat(contextMapById.uuid).isEqualTo(RANDOM_UUID1);
        App contextMapByUuid = contextMap.getByUuid(RANDOM_UUID1);
        assertThat(contextMapByUuid.packageName).isEqualTo(APP_NAME);
        App contextMapByConn = contextMap.getByConnId(CONN_ID1);
        assertThat(contextMapByConn.packageName).isEqualTo(APP_NAME);

        List<Integer> ids = contextMap.getAllAppsIds();
        assertThat(ids).containsExactly(APP_ID1, APP_ID2);
    }

    @Test
    public void getConnMethods() {
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        assertThat(contextMap.getConnectedDevices()).containsExactly(mDevice1, mDevice2);

        assertThat(contextMap.connIdByDevice(APP_ID1, mDevice1)).isEqualTo(CONN_ID1);
        assertThat(contextMap.connIdByDevice(APP_ID2, mDevice2)).isEqualTo(CONN_ID2);
        assertThat(contextMap.connIdByDevice(123456, mDevice1)).isNull();

        assertThat(contextMap.deviceByConnId(CONN_ID1)).isEqualTo(mDevice1);
        assertThat(contextMap.deviceByConnId(CONN_ID2)).isEqualTo(mDevice2);
        assertThat(contextMap.deviceByConnId(123456)).isNull();

        List<ContextMap.Connection> connList = contextMap.getConnectionByApp(APP_ID1);
        assertThat(connList).hasSize(1);
        assertThat(connList.get(0).connId()).isEqualTo(CONN_ID1);
        assertThat(connList.get(0).device()).isEqualTo(mDevice1);
        assertThat(connList.get(0).appId()).isEqualTo(APP_ID1);
        assertThat(contextMap.getConnectionByApp(APP_ID2)).hasSize(1);
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
        assertThat(contextMap.getConnectedMap()).isEmpty();

        contextMap = getMapWithAppAndConnection();
        contextMap.removeConnectionsByAppId(APP_ID1);
        assertThat(contextMap.getConnectedMap()).isNotEmpty();
        contextMap.removeConnectionsByAppId(APP_ID2);
        assertThat(contextMap.getConnectedMap()).isEmpty();
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        StringBuilder sb = new StringBuilder();
        ContextMap<IBluetoothGattCallback> contextMap = getMapWithAppAndConnection();
        contextMap.dump(sb);
    }

    private ContextMap<IBluetoothGattCallback> getMapWithAppAndConnection() {
        ContextMap<IBluetoothGattCallback> contextMap = new ContextMap<>();
        App app = contextMap.add(RANDOM_UUID1, mMockCallback, mAdapterService, mAttributionSource);
        app.id = APP_ID1;
        app = contextMap.add(RANDOM_UUID2, mMockCallback, mAdapterService, mAttributionSource);
        app.id = APP_ID2;

        contextMap.addConnection(APP_ID1, CONN_ID1, BluetoothDevice.TRANSPORT_LE, mDevice1);
        contextMap.addConnection(APP_ID2, CONN_ID2, BluetoothDevice.TRANSPORT_BREDR, mDevice2);
        assertThat(contextMap.getConnectedMap()).isNotEmpty();
        assertThat(contextMap.getAllAppsIds()).isNotEmpty();
        return contextMap;
    }
}
