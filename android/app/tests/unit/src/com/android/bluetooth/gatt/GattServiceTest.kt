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

package com.android.bluetooth.gatt

import android.app.ActivityManager
import android.app.compat.CompatChanges
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.IBluetoothGattCallback
import android.companion.CompanionDeviceManager
import android.content.AttributionSource
import android.content.Context
import android.content.res.Resources
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import android.test.mock.MockContentProvider
import android.test.mock.MockContentResolver
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.ActionOnDeathRecipient
import com.android.bluetooth.ChangeIds.DONOT_STEAL_AUDIO_ON_GATT_CONN
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.btservice.CompanionManager
import com.android.bluetooth.flags.Flags
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mapclient.MapClientService
import com.android.bluetooth.mockBluetoothManager
import com.android.bluetooth.mockGetRemoteDevice
import com.android.bluetooth.mockGetSystemService
import com.android.modules.utils.build.SdkLevel
import com.android.tests.bluetooth.FakeTimeProvider
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.Optional
import java.util.UUID
import kotlin.time.ExperimentalTime
import libcore.junit.util.compat.CoreCompatChangeRule
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Test cases for [GattService]. */
@OptIn(ExperimentalTime::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class GattServiceTest(flags: FlagsWrapper) {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val compatChangeRule = CoreCompatChangeRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var gattCallback: IBluetoothGattCallback
    @Mock private lateinit var gattCallback2: IBluetoothGattCallback
    @Mock private lateinit var clientMap: ContextMap<IBluetoothGattCallback>
    @Mock private lateinit var reliableQueue: MutableSet<BluetoothDevice>
    @Mock private lateinit var nativeInterface: GattNativeInterface
    @Mock private lateinit var advertiseManagerNativeInterface: AdvertiseManagerNativeInterface
    @Mock
    private lateinit var distanceMeasurementNativeInterface: DistanceMeasurementNativeInterface
    @Mock private lateinit var resources: Resources
    @Mock private lateinit var adapterService: AdapterService

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val companionDeviceManager =
        context.getSystemService<CompanionDeviceManager>(CompanionDeviceManager::class.java)

    private val device = getTestDevice(109)

    private val CLIENT_CONN =
        ContextMap.Connection(CLIENT_CONN_ID, device, BluetoothDevice.TRANSPORT_LE, CLIENT_IF)
    private val CLIENT_CONN_LIST = listOf<ContextMap.Connection>(CLIENT_CONN)
    private val timeProvider = FakeTimeProvider()

    private lateinit var looper: TestLooper
    private lateinit var service: GattService

    @Before
    fun setUp() {
        val mockContentResolver = MockContentResolver(context)
        mockContentResolver.addProvider(
            Settings.AUTHORITY,
            object : MockContentProvider() {
                override fun call(method: String, request: String?, args: Bundle?): Bundle? {
                    return Bundle.EMPTY
                }
            },
        )

        doReturn(context.packageName).whenever(source).packageName
        doReturn(context.packageName).whenever(source).attributionTag
        doReturn(Binder.getCallingUid()).whenever(source).uid

        doReturn(CLIENT_CONN_LIST).whenever(clientMap).getConnectionsByDevice(CLIENT_IF, device)
        val clientApp = mock<ContextApp<IBluetoothGattCallback>>()
        doReturn(gattCallback).whenever(clientApp).callback
        doReturn(CLIENT_IF).whenever(clientApp).id
        doReturn(clientApp).whenever(clientMap).getByCallbackId(gattCallback)
        doReturn(clientApp).whenever(clientMap).getById(CLIENT_IF)
        doReturn(clientApp, null as Array<Any>?)
            .whenever(clientMap)
            .remove(any<Int>(), any<ContextMap.RemoveReason>())

        val clientApp2 = mock<ContextApp<IBluetoothGattCallback>>()
        doReturn(gattCallback2).whenever(clientApp2).callback
        doReturn(CLIENT_IF2).whenever(clientApp2).id
        doReturn(clientApp2).whenever(clientMap).getByCallbackId(gattCallback2)
        doReturn(clientApp2).whenever(clientMap).getById(CLIENT_IF2)

        doReturn(context.packageManager).whenever(adapterService).packageManager
        doReturn(context.getSharedPreferences("GattServiceTestPrefs", Context.MODE_PRIVATE))
            .whenever(adapterService)
            .getSharedPreferences(any<String>(), any<Int>())
        doReturn(resources).whenever(adapterService).resources
        doReturn(mockContentResolver).whenever(adapterService).contentResolver

        adapterService.mockBluetoothManager()
        adapterService.mockGetSystemService<LocationManager>()
        adapterService.mockGetSystemService<ActivityManager>()
        adapterService.mockGetRemoteDevice(device)
        doReturn(source).whenever(adapterService).attributionSource

        val btCompanionManager = CompanionManager(adapterService)
        doReturn(btCompanionManager).whenever(adapterService).companionManager

        looper = TestLooper()
        service =
            GattService(
                adapterService,
                nativeInterface,
                advertiseManagerNativeInterface,
                distanceMeasurementNativeInterface,
                clientMap,
                reliableQueue,
                companionDeviceManager,
                looper.looper,
                timeProvider,
            )
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun testServiceUpAndDown() {
        for (i in 0..2) {
            service.cleanup()
            service =
                GattService(
                    adapterService,
                    nativeInterface,
                    advertiseManagerNativeInterface,
                    distanceMeasurementNativeInterface,
                    clientMap,
                    reliableQueue,
                    companionDeviceManager,
                    looper.looper,
                    timeProvider,
                )
        }
    }

    @Test
    fun cleanUp_doesNotCrash() {
        service.cleanup()
    }

    @Test
    @DisableFlags(Flags.FLAG_LE_SUBRATE_MANAGER)
    fun subrateModeRequest_withLeSubrateManagerDisabled() {
        val inOrder = inOrder(nativeInterface)

        for (subrateMode in BluetoothGatt.SUBRATE_MODE_OFF..BluetoothGatt.SUBRATE_MODE_HIGH) {
            service.subrateModeRequest(gattCallback, device, subrateMode)

            // With no cached latency, latency for SUBRATE_MODE_OFF is 0.
            // For other modes, latency is hardcoded to 0.
            val expectedLatency = 0
            inOrder
                .verify(nativeInterface)
                .gattSubrateRequest(
                    eq(CLIENT_IF),
                    eq(device),
                    any<Int>(),
                    any<Int>(),
                    eq(expectedLatency),
                    any<Int>(),
                    any<Int>(),
                )
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SUBRATE_MANAGER)
    fun subrateModeRequest_withLeSubrateManagerEnabled() {
        val inOrder = inOrder(nativeInterface)

        for (subrateMode in BluetoothGatt.SUBRATE_MODE_OFF..BluetoothGatt.SUBRATE_MODE_HIGH) {
            service.subrateModeRequest(gattCallback, device, subrateMode)

            inOrder
                .verify(nativeInterface)
                .gattSubrateModeRequest(eq(CLIENT_IF), eq(device), eq(subrateMode))
        }
    }

    @Test
    fun subrateModeRequestDisablementLatencyParamRestore() {
        val inOrder = inOrder(nativeInterface)
        val implementInterval = 3
        val peripheralLatency = 5
        val supervisionTimeout = 6
        val status = 0

        val app = mock<ContextApp<IBluetoothGattCallback>>()
        doReturn(app).whenever(clientMap).getByConnId(CLIENT_CONN_ID)
        doReturn(gattCallback).whenever(app).callback
        doReturn(device).whenever(clientMap).deviceByConnId(CLIENT_CONN_ID)

        service.onClientConnUpdateFromNative(
            CLIENT_CONN_ID,
            implementInterval,
            peripheralLatency,
            supervisionTimeout,
            status,
        )

        service.subrateModeRequest(gattCallback, device, BluetoothGatt.SUBRATE_MODE_HIGH)
        if (Flags.leSubrateManager()) {
            inOrder
                .verify(nativeInterface)
                .gattSubrateModeRequest(
                    eq(CLIENT_IF),
                    eq(device),
                    eq(BluetoothGatt.SUBRATE_MODE_HIGH),
                )
        } else {
            inOrder
                .verify(nativeInterface)
                .gattSubrateRequest(
                    eq(CLIENT_IF),
                    eq(device),
                    any<Int>(),
                    any<Int>(),
                    eq(0),
                    any<Int>(),
                    any<Int>(),
                )
        }

        service.subrateModeRequest(gattCallback, device, BluetoothGatt.SUBRATE_MODE_OFF)
        if (Flags.leSubrateManager()) {
            inOrder
                .verify(nativeInterface)
                .gattSubrateModeRequest(
                    eq(CLIENT_IF),
                    eq(device),
                    eq(BluetoothGatt.SUBRATE_MODE_OFF),
                )
        } else {
            inOrder
                .verify(nativeInterface)
                .gattSubrateRequest(
                    eq(CLIENT_IF),
                    eq(device),
                    any<Int>(),
                    any<Int>(),
                    eq(peripheralLatency),
                    any<Int>(),
                    any<Int>(),
                )
        }
    }

    @Test
    fun testDumpDoesNotCrash() {
        service.dump(StringBuilder())
    }

    @Test
    fun registerClient() {
        val uuid = UUID.randomUUID()
        val callback = mock<IBluetoothGattCallback>()
        val eattSupport = true
        val transport = BluetoothDevice.TRANSPORT_LE

        service.registerClient(uuid, callback, eattSupport, transport, source)
        verify(nativeInterface).gattClientRegisterApp(uuid, context.packageName, eattSupport)
    }

    @Test
    fun registerClient_checkLimitPerApp() {
        doReturn(GattService.GATT_CLIENT_LIMIT_PER_APP)
            .whenever(clientMap)
            .countByAppUid(any<Int>())
        val uuid = UUID.randomUUID()
        val callback = mock<IBluetoothGattCallback>()
        val eattSupport = true
        val transport = BluetoothDevice.TRANSPORT_LE

        service.registerClient(uuid, callback, eattSupport, transport, source)
        verify(clientMap, never()).add(any<Int>(), any(), any(), any(), any<Int>(), any<String>())
        verify(nativeInterface, never()).gattClientRegisterApp(any<UUID>(), any(), any<Boolean>())
    }

    @Test
    fun unregisterClient() {
        service.unregisterClient(
            gattCallback,
            source,
            ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT,
        )
        verify(clientMap).remove(CLIENT_IF, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)
        verify(nativeInterface).gattClientUnregisterApp(CLIENT_IF)
    }

    @Test
    fun unregisterClientTwice() {
        // Simulate simultaneous unregistering from different threads by mocking mClientMap.
        service.unregisterClient(
            gattCallback,
            source,
            ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT,
        )
        service.unregisterClient(
            gattCallback,
            source,
            ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT,
        )
        verify(clientMap, atLeastOnce())
            .remove(CLIENT_IF, ContextMap.RemoveReason.REASON_UNREGISTER_CLIENT)

        // The second call is not propagated to the native stack.
        verify(nativeInterface, times(1)).gattClientUnregisterApp(CLIENT_IF)
    }

    @Test
    fun onClientRegisteredFromNative_success_unregistersOnBinderDied() {
        val uuid = UUID.randomUUID()
        val clientIf = 1
        val status = BluetoothGatt.GATT_SUCCESS
        val callback = mock<IBluetoothGattCallback>()
        val app = mock<ContextApp<IBluetoothGattCallback>>()

        doReturn(callback).whenever(app).callback
        doReturn(app).whenever(clientMap).getByUuid(uuid)
        doReturn(app).whenever(clientMap).getByCallbackId(callback)
        doReturn(clientIf).whenever(app).id
        // This mock is needed for unregisterClient to proceed
        doReturn(app)
            .whenever(clientMap)
            .remove(eq(clientIf), eq(ContextMap.RemoveReason.REASON_BINDER_DIED))

        // Call the method under test
        service.isAvailable = true
        service.onClientRegisteredFromNative(status, clientIf, uuid)

        // Verify that the app ID is set
        verify(app).id = clientIf

        // Verify that linkToDeath is called and capture the DeathRecipient
        val captor = argumentCaptor<IBinder.DeathRecipient>()
        verify(app).linkToDeath(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(ActionOnDeathRecipient::class.java)

        // Verify that the callback is invoked
        verify(callback).onClientRegistered(status)

        // Trigger binderDied on the captured recipient
        captor.firstValue.binderDied()
        looper.dispatchAll()

        // Verify that unregisterClient logic is executed
        verify(nativeInterface).gattClientUnregisterApp(clientIf)
    }

    @Test
    fun clientConnect() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = false
        val transport = 2
        val opportunistic = true
        val isAutomaticMtuEnabled = false

        service.clientConnect(
            gattCallback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            isAutomaticMtuEnabled,
            source,
        )

        verify(nativeInterface)
            .gattClientConnect(
                CLIENT_IF,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                0,
                false,
                isAutomaticMtuEnabled,
            )
    }

    @Test
    fun clientConnect_withCrossDeviceAccessServiceTag_setsPreferRelaxMode() {
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = false
        val transport = 2
        val opportunistic = true
        val isAutomaticMtuEnabled = false

        val tagSource =
            AttributionSource.Builder(Process.myUid())
                .setPackageName("com.test.package")
                .setAttributionTag("crossdeviceaccessservice")
                .build()

        service.clientConnect(
            gattCallback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            isAutomaticMtuEnabled,
            tagSource,
        )

        verify(nativeInterface)
            .gattClientConnect(
                CLIENT_IF,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                0,
                true, /* preferRelaxMode */
                isAutomaticMtuEnabled,
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_CONN_SETTINGS)
    @EnableCompatChanges(DONOT_STEAL_AUDIO_ON_GATT_CONN)
    fun clientConnectOverLeFailed() {
        assumeTrue(CompatChanges.isChangeEnabled(DONOT_STEAL_AUDIO_ON_GATT_CONN))
        assumeTrue(SdkLevel.isAtLeastC())
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = BluetoothDevice.TRANSPORT_LE
        val opportunistic = false
        val isAutomaticMtuEnabled = false

        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .adoptShellPermissionIdentity()

        service.clientConnect(
            gattCallback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            isAutomaticMtuEnabled,
            source,
        )

        verify(adapterService).notifyDirectLeGattClientConnect(any<Int>(), any<BluetoothDevice>())
        verify(nativeInterface)
            .gattClientConnect(
                CLIENT_IF,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                0,
                false,
                isAutomaticMtuEnabled,
            )

        service.onConnectedFromNative(
            CLIENT_IF,
            0,
            transport,
            BluetoothGatt.GATT_CONNECTION_TIMEOUT,
            device,
        )
        verify(adapterService).notifyGattClientConnectFailed(any<Int>(), any<BluetoothDevice>())
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_CONN_SETTINGS)
    @EnableCompatChanges(DONOT_STEAL_AUDIO_ON_GATT_CONN)
    fun clientConnectDisconnectOverLe() {
        assumeTrue(CompatChanges.isChangeEnabled(DONOT_STEAL_AUDIO_ON_GATT_CONN))
        assumeTrue(SdkLevel.isAtLeastC())
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = BluetoothDevice.TRANSPORT_LE
        val opportunistic = false
        val isAutomaticMtuEnabled = false

        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .adoptShellPermissionIdentity()

        service.clientConnect(
            gattCallback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            isAutomaticMtuEnabled,
            source,
        )

        verify(adapterService).notifyDirectLeGattClientConnect(any<Int>(), any<BluetoothDevice>())
        verify(nativeInterface)
            .gattClientConnect(
                CLIENT_IF,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                0,
                false,
                isAutomaticMtuEnabled,
            )

        service.onConnectedFromNative(CLIENT_IF, 15, transport, BluetoothGatt.GATT_SUCCESS, device)
        service.clientDisconnect(gattCallback, device, source)

        verify(adapterService).notifyGattClientDisconnect(any<Int>(), any<BluetoothDevice>())
    }

    @Test
    @EnableFlags(Flags.FLAG_GATT_CONN_SETTINGS)
    @EnableCompatChanges(DONOT_STEAL_AUDIO_ON_GATT_CONN)
    fun clientConnectOverLeDisconnectedByRemote() {
        assumeTrue(CompatChanges.isChangeEnabled(DONOT_STEAL_AUDIO_ON_GATT_CONN))
        assumeTrue(SdkLevel.isAtLeastC())
        val addressType = BluetoothDevice.ADDRESS_TYPE_RANDOM
        val isDirect = true
        val transport = BluetoothDevice.TRANSPORT_LE
        val opportunistic = false
        val isAutomaticMtuEnabled = false

        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .adoptShellPermissionIdentity()

        service.clientConnect(
            gattCallback,
            device,
            addressType,
            isDirect,
            transport,
            opportunistic,
            isAutomaticMtuEnabled,
            source,
        )

        verify(adapterService).notifyDirectLeGattClientConnect(any<Int>(), any<BluetoothDevice>())
        verify(nativeInterface)
            .gattClientConnect(
                CLIENT_IF,
                device,
                addressType,
                isDirect,
                transport,
                opportunistic,
                0,
                false,
                isAutomaticMtuEnabled,
            )

        service.onConnectedFromNative(CLIENT_IF, 15, transport, BluetoothGatt.GATT_SUCCESS, device)
        service.onDisconnectedFromNative(CLIENT_IF, 15, transport, 1, device)

        verify(adapterService).notifyGattClientDisconnect(any<Int>(), any<BluetoothDevice>())
    }

    @Test
    fun clientGetDevicesMatchingConnectionStates() {
        val states = intArrayOf(BluetoothProfile.STATE_CONNECTED)

        doReturn(setOf(getTestDevice(90))).whenever(adapterService).bondedDevices

        val connectedDevices = setOf(device)
        doReturn(connectedDevices).whenever(clientMap).getConnectedDevices()

        val deviceList = service.getDevicesMatchingConnectionStates(states)

        assertThat(deviceList).containsExactly(device)
    }

    @Test
    fun clientDisconnectAll() {
        val connMap = mapOf(CLIENT_IF to device)
        doReturn(connMap).whenever(clientMap).getConnectedMap()

        service.disconnectAll(source)
        verify(nativeInterface).gattClientDisconnect(CLIENT_IF, device, CLIENT_CONN_ID)
    }

    @Test
    fun clientConnectionParameterUpdate() {
        var connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH
        service.connectionParameterUpdate(gattCallback, device, connectionPriority)

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        service.connectionParameterUpdate(gattCallback, device, connectionPriority)

        connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        service.connectionParameterUpdate(gattCallback, device, connectionPriority)

        verify(nativeInterface, times(3))
            .gattConnectionParameterUpdate(
                eq(CLIENT_IF),
                eq(device),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                any<Int>(),
                eq(0),
                eq(0),
            )
    }

    @Test
    fun clientReadRemoteRssi_entryIsEmpty() {
        service.readRemoteRssi(gattCallback, device)

        verify(nativeInterface).gattClientReadRemoteRssi(CLIENT_IF, device)
    }

    @Test
    fun clientReadRemoteRssi_entryIsNotEmpty_elapsedTimeIsLessThanThrottleMs() {
        service.mRssiCache[device.address] =
            GattService.RssiCacheEntry(timeProvider.elapsedRealtime(), TEST_RSSI)

        // 25ms is less than the default throttle ms of 75ms
        timeProvider.advanceTime(Duration.ofMillis(25))
        service.readRemoteRssi(gattCallback, device)

        verify(gattCallback).onReadRemoteRssi(device, TEST_RSSI, BluetoothGatt.GATT_SUCCESS)
        verify(nativeInterface, never()).gattClientReadRemoteRssi(CLIENT_IF, device)
    }

    @Test
    fun clientReadRemoteRssi_entryIsNotEmpty_elapsedTimeIsMoreThanThrottleMs() {
        service.mRssiCache[device.address] =
            GattService.RssiCacheEntry(timeProvider.elapsedRealtime(), TEST_RSSI)

        // 100ms is more than the default throttle ms of 75ms
        timeProvider.advanceTime(Duration.ofMillis(100))
        service.readRemoteRssi(gattCallback, device)

        verify(nativeInterface).gattClientReadRemoteRssi(CLIENT_IF, device)
    }

    @Test
    fun clientOnReadRemoteRssiFromNative() {
        if (Flags.supportMultipleReadRssi()) {
            service.mClientsPendingRssi.add(CLIENT_IF)
        }

        service.onReadRemoteRssiFromNative(CLIENT_IF, device, TEST_RSSI, BluetoothGatt.GATT_SUCCESS)

        assertThat(service.mRssiCache[device.address]!!.rssi).isEqualTo(TEST_RSSI)
        verify(gattCallback).onReadRemoteRssi(device, TEST_RSSI, BluetoothGatt.GATT_SUCCESS)
    }

    @Test
    @EnableFlags(Flags.FLAG_SUPPORT_MULTIPLE_READ_RSSI)
    fun twoClientsReadRemoteRssi() {
        service.readRemoteRssi(gattCallback, device)
        service.readRemoteRssi(gattCallback2, device)

        verify(nativeInterface).gattClientReadRemoteRssi(CLIENT_IF, device)
        verify(nativeInterface, never()).gattClientReadRemoteRssi(CLIENT_IF2, device)
        service.onReadRemoteRssiFromNative(CLIENT_IF, device, TEST_RSSI, BluetoothGatt.GATT_SUCCESS)

        assertThat(service.mRssiCache[device.address]!!.rssi).isEqualTo(TEST_RSSI)
        verify(gattCallback).onReadRemoteRssi(device, TEST_RSSI, BluetoothGatt.GATT_SUCCESS)
        verify(gattCallback2).onReadRemoteRssi(device, TEST_RSSI, BluetoothGatt.GATT_SUCCESS)
    }

    @Test
    fun clientLeConnectionUpdate() {
        val minInterval = 3
        val maxInterval = 4
        val peripheralLatency = 5
        val supervisionTimeout = 6
        val minConnectionEventLen = 7
        val maxConnectionEventLen = 8

        service.leConnectionUpdate(
            gattCallback,
            device,
            minInterval,
            maxInterval,
            peripheralLatency,
            supervisionTimeout,
            minConnectionEventLen,
            maxConnectionEventLen,
        )

        verify(nativeInterface)
            .gattConnectionParameterUpdate(
                CLIENT_IF,
                device,
                minInterval,
                maxInterval,
                peripheralLatency,
                supervisionTimeout,
                minConnectionEventLen,
                maxConnectionEventLen,
            )
    }

    @Test
    fun clientReadPhy() {
        service.clientReadPhy(gattCallback, device)
        verify(nativeInterface).gattClientReadPhy(CLIENT_IF, device)
    }

    @Test
    fun clientSetPreferredPhy() {
        val txPhy = 2
        val rxPhy = 1
        val phyOptions = 3

        service.clientSetPreferredPhy(gattCallback, device, txPhy, rxPhy, phyOptions)
        verify(nativeInterface)
            .gattClientSetPreferredPhy(CLIENT_IF, device, txPhy, rxPhy, phyOptions)
    }

    @Test
    fun clientReadCharacteristic() {
        val handle = 2
        val authReq = 3

        service.readCharacteristic(gattCallback, device, handle, authReq)
        verify(nativeInterface).gattClientReadCharacteristic(CLIENT_CONN_ID, handle, authReq)
    }

    @Test
    fun clientReadUsingCharacteristicUuid() {
        val uuid = UUID.randomUUID()
        val startHandle = 2
        val endHandle = 3
        val authReq = 4

        service.readUsingCharacteristicUuid(
            gattCallback,
            device,
            uuid,
            startHandle,
            endHandle,
            authReq,
        )
        verify(nativeInterface)
            .gattClientReadUsingCharacteristicUuid(
                CLIENT_CONN_ID,
                uuid,
                startHandle,
                endHandle,
                authReq,
            )
    }

    @Test
    fun clientWriteCharacteristic() {
        val handle = 2
        val writeType = 3
        val authReq = 4
        val value = byteArrayOf(5, 6)

        val writeCharacteristicResult =
            service.writeCharacteristic(gattCallback, device, handle, writeType, authReq, value)
        assertThat(writeCharacteristicResult)
            .isEqualTo(BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED)
    }

    @Test
    fun clientReadDescriptor() {
        val handle = 2
        val authReq = 3

        service.readDescriptor(gattCallback, device, handle, authReq)
        verify(nativeInterface).gattClientReadDescriptor(CLIENT_CONN_ID, handle, authReq)
    }

    @Test
    fun clientBeginReliableWrite() {
        service.beginReliableWrite(device)
        verify(reliableQueue).add(device)
    }

    @Test
    fun clientEndReliableWrite() {
        val execute = true

        service.endReliableWrite(gattCallback, device, execute)
        verify(reliableQueue).remove(device)
        verify(nativeInterface).gattClientExecuteWrite(CLIENT_CONN_ID, execute)
    }

    @Test
    fun clientRegisterForNotification() {
        val handle = 2
        val enable = true

        service.registerForNotification(gattCallback, device, handle, enable)

        verify(nativeInterface)
            .gattClientRegisterForNotifications(CLIENT_IF, device, handle, enable)
    }

    @Test
    fun clientConfigureMTU() {
        val mtu = 2

        service.configureMTU(gattCallback, device, mtu)
        verify(nativeInterface).gattClientConfigureMTU(CLIENT_CONN_ID, mtu)
    }

    @Test
    fun clientRestrictedHandles() {
        val db = arrayListOf<GattDbElement>()

        val app = mock<ContextApp<IBluetoothGattCallback>>()
        val callback = mock<IBluetoothGattCallback>()

        doReturn(app).whenever(clientMap).getByConnId(CLIENT_CONN_ID)
        doReturn(callback).whenever(app).callback

        val hidService =
            GattDbElement.createPrimaryService(
                UUID.fromString("00001812-0000-1000-8000-00805F9B34FB")
            )
        hidService.id = 1

        val hidInfoChar =
            GattDbElement.createCharacteristic(
                UUID.fromString("00002A4A-0000-1000-8000-00805F9B34FB"),
                0,
                0,
            )
        hidInfoChar.id = 2

        val randomChar =
            GattDbElement.createCharacteristic(
                UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB"),
                0,
                0,
            )
        randomChar.id = 3

        db.add(hidService)
        db.add(hidInfoChar)
        db.add(randomChar)

        service.onGetGattDbFromNative(CLIENT_CONN_ID, db)
        // HID characteristics should be restricted
        assertThat(service.getRestrictedHandles()[CLIENT_CONN_ID]).contains(hidInfoChar.id)
        assertThat(service.getRestrictedHandles()[CLIENT_CONN_ID]).doesNotContain(randomChar.id)

        service.onDisconnectedFromNative(
            CLIENT_IF,
            CLIENT_CONN_ID,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothGatt.GATT_SUCCESS,
            device,
        )
        assertThat(service.getRestrictedHandles()).doesNotContainKey(CLIENT_CONN_ID)
    }

    @Test
    fun clientAncsAccessPermissionRejected() {
        if (Flags.checkMapclientConnectionPolicyForAncs()) {
            return
        }

        val db = arrayListOf<GattDbElement>()

        val app = mock<ContextApp<IBluetoothGattCallback>>()
        val callback = mock<IBluetoothGattCallback>()

        doReturn(app).whenever(clientMap).getByConnId(CLIENT_CONN_ID)
        doReturn(callback).whenever(app).callback

        val ancsService =
            GattDbElement.createPrimaryService(
                UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
            )
        ancsService.id = 1

        db.add(ancsService)

        doReturn(BluetoothDevice.ACCESS_REJECTED)
            .whenever(adapterService)
            .getMessageAccessPermission(any<BluetoothDevice>())

        service.onGetGattDbFromNative(CLIENT_CONN_ID, db)
        // ANCS should be restricted
        assertThat(service.getRestrictedHandles()[CLIENT_CONN_ID]).contains(ancsService.id)

        service.onDisconnectedFromNative(
            CLIENT_IF,
            CLIENT_CONN_ID,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothGatt.GATT_SUCCESS,
            device,
        )
        assertThat(service.getRestrictedHandles()).doesNotContainKey(CLIENT_CONN_ID)
    }

    @Test
    @EnableFlags(Flags.FLAG_CHECK_MAPCLIENT_CONNECTION_POLICY_FOR_ANCS)
    fun clientAncsAccessPermissionRejectedV2() {
        val db = arrayListOf<GattDbElement>()

        val app = mock<ContextApp<IBluetoothGattCallback>>()
        val callback = mock<IBluetoothGattCallback>()

        doReturn(app).whenever(clientMap).getByConnId(CLIENT_CONN_ID)
        doReturn(callback).whenever(app).callback

        val ancsService =
            GattDbElement.createPrimaryService(
                UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
            )
        ancsService.id = 1

        db.add(ancsService)

        val mapClientService = mock<MapClientService>()

        doReturn(Optional.of(mapClientService)).whenever(adapterService).getMapClientService()
        doReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED)
            .whenever(mapClientService)
            .getConnectionPolicy(any<BluetoothDevice>())

        service.onGetGattDbFromNative(CLIENT_CONN_ID, db)
        // ANCS should be restricted
        assertThat(service.getRestrictedHandles()[CLIENT_CONN_ID]).contains(ancsService.id)

        service.onDisconnectedFromNative(
            CLIENT_IF,
            CLIENT_CONN_ID,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothGatt.GATT_SUCCESS,
            device,
        )
        assertThat(service.getRestrictedHandles()).doesNotContainKey(CLIENT_CONN_ID)
    }

    companion object {
        private const val TEST_RSSI = 43
        private const val CLIENT_IF = 12
        private const val CLIENT_IF2 = 13
        private const val CLIENT_CONN_ID = 42

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() = FlagsWrapper.progressionOf(Flags.FLAG_GATT_THREAD)
    }
}
