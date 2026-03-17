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

package com.android.bluetooth.btservice;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED;
import static android.bluetooth.BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.BundleMatchers.hasEntry;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.Utils.joinUninterruptibly;
import static com.android.bluetooth.btservice.RemoteDevices.ACL_CONNECTION_DELIVERY_GROUP_POLICY;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.Is.isA;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.BroadcastOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.EncryptionStatus;
import android.bluetooth.State;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.SystemProperties;
import android.os.TestLooperManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.bas.BatteryService;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfp.HeadsetHalConstants;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Test cases for {@link RemoteDevices}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class RemoteDevicesTest {
    @Rule
    public final StaticMockitoRule mMockitoRule =
            new StaticMockitoRule(Config.class, SystemProperties.class);

    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mPackageManager;
    @Mock private AdapterNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(43);

    private InOrder mInOrder;
    private RemoteDevices mRemoteDevices;
    private HandlerThread mHandlerThread;
    private TestLooperManager mTestLooperManager;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public RemoteDevicesTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() {
        mInOrder = inOrder(mAdapterService);
        mHandlerThread = new HandlerThread("RemoteDevicesTestHandlerThread");
        mHandlerThread.start();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        mTestLooperManager = instrumentation.acquireLooperManager(mHandlerThread.getLooper());

        mockGetBluetoothManager(mAdapterService);
        mockGetSystemService(mAdapterService, CompanionDeviceManager.class);
        doReturn(mPackageManager).when(mAdapterService).getPackageManager();
        doReturn(UNCATEGORIZED).when(mAdapterService).getRemoteClass(mDevice);

        doReturn(mNativeInterface).when(mAdapterService).getNative();
        mRemoteDevices = new RemoteDevices(mAdapterService, mHandlerThread.getLooper());
        verify(mAdapterService).getSystemService(BluetoothManager.class);
        verify(mAdapterService, times(2)).getPackageManager();
        verify(mAdapterService).getSystemService(CompanionDeviceManager.class);
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
    }

    @After
    public void tearDown() {
        mTestLooperManager.release();
        mHandlerThread.quit();
        joinUninterruptibly(mHandlerThread);
    }

    @Test
    public void testUpdateBatteryLevel_normalSequence() {
        int batteryLevel = 10;

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that update same battery level for the same device does not trigger intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that updating battery level to different value triggers the intent again
        batteryLevel = 15;
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_errorNegativeValue() {
        int batteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Verify that updating with invalid battery level does not trigger the intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that device property stays null after invalid update
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_errorTooLargeValue() {
        int batteryLevel = 101;

        // Verify that updating invalid battery level does not trigger the intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that device property stays null after invalid update
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testResetBeforeUpdate() {
        // Verify that resetting battery level keeps device property null
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ false);
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testResetAfterUpdate() {
        int batteryLevel = 10;

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ false);
        verifyBatteryLevelUpdate(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify no intent is sent after second reset
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ false);
        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevelOnHeadsetStateChange() {
        int batteryLevel = 10;

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.onHeadsetConnectionStateChanged(
                mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        verify(mAdapterService).getBatteryService();
        verifyBatteryLevelUpdate(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testOnHeadsetStateChangeWithBatteryService_NotResetBatteryLevel() {
        int batteryLevel = 10;

        makeBatteryServiceAvailable(mDevice);
        assertThat(mRemoteDevices.hasBatteryService(mDevice)).isTrue();
        verify(mAdapterService).getBatteryService();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that battery level is not reset
        mRemoteDevices.onHeadsetConnectionStateChanged(
                mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        verify(mAdapterService, times(2)).getBatteryService();

        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(batteryLevel);

        doReturn(Optional.empty()).when(mAdapterService).getBatteryService();
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testAclStateChangeCallback() {
        int batteryLevel = 10;
        int transport = 2; // LE transport
        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that when device is completely disconnected, RemoteDevices reset battery level to
        // BluetoothDevice.BATTERY_LEVEL_UNKNOWN
        doReturn(State.ON).when(mAdapterService).getState();
        mRemoteDevices.aclStateChangeCallback(
                0,
                Util.getByteAddress(mDevice),
                0, // Public address type
                transport,
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                19,
                BluetoothDevice.ERROR); // HCI code 19 remote terminated
        // Verify ACTION_ACL_DISCONNECTED and BATTERY_LEVEL_CHANGED intent are sent
        final Bundle expectedBundle =
                BroadcastOptions.makeBasic()
                        .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                        .setDeliveryGroupMatchingKey(
                                ACL_CONNECTION_DELIVERY_GROUP_POLICY,
                                transport + "/" + mDevice.getAddress())
                        .toBundle();
        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED), hasExtras(expectedBundle));

        int newBatteryLevel = 20;
        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice, newBatteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(newBatteryLevel);
    }

    @Test
    public void testHfIndicatorParser_testCorrectValue() {
        int batteryLevel = 10;

        // Verify that ACTION_HF_INDICATORS_VALUE_CHANGED intent updates battery level
        mRemoteDevices.onHfIndicatorValueChanged(
                mDevice, HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS, batteryLevel);
        verifyBatteryLevelUpdate(batteryLevel);
    }

    @Test
    public void testHfIndicatorParser_testWrongIndicatorId() {
        int batteryLevel = 10;

        mRemoteDevices.onHfIndicatorValueChanged(mDevice, batteryLevel, 3);
        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that device property is still null after invalid update
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
    }

    @Test
    public void testOnVendorSpecificHeadsetEvent_testCorrectPlantronicsXEvent() {
        // Prepare the base device property
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        // Verify that correct ACTION_VENDOR_SPECIFIC_HEADSET_EVENT updates battery level
        mRemoteDevices.onVendorSpecificHeadsetEvent(
                mDevice,
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT,
                BluetoothAssignedNumbers.PLANTRONICS,
                BluetoothHeadset.AT_CMD_TYPE_SET,
                getXEventArray(3, 8));
        verifyBatteryLevelUpdate(42);
    }

    @Test
    public void testOnVendorSpecificHeadsetEvent_testCorrectAppleBatteryVsc() {
        // Prepare the base device property
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        // Verify that correct ACTION_VENDOR_SPECIFIC_HEADSET_EVENT updates battery level
        mRemoteDevices.onVendorSpecificHeadsetEvent(
                mDevice,
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                BluetoothAssignedNumbers.APPLE,
                BluetoothHeadset.AT_CMD_TYPE_SET,
                new Object[] {
                    3,
                    BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                    5,
                    2,
                    1,
                    3,
                    10
                });
        verifyBatteryLevelUpdate(60);
    }

    @Test
    public void testGetBatteryLevelFromXEventVsc() {
        assertThat(RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(3, 8))).isEqualTo(42);
        assertThat(RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(10, 11)))
                .isEqualTo(100);
        assertThat(RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(1, 1)))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(3, 1)))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(-1, 1)))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(-1, -1)))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
    }

    @Test
    public void testGetBatteryLevelFromAppleBatteryVsc() {
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    1,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    0
                                }))
                .isEqualTo(10);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    1,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    9
                                }))
                .isEqualTo(100);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    3,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    5,
                                    2,
                                    1,
                                    3,
                                    10
                                }))
                .isEqualTo(60);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    3,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    5,
                                    2,
                                    1,
                                    3
                                }))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    1,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    10
                                }))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    1,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    -1
                                }))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {
                                    1,
                                    BluetoothHeadset
                                            .VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                                    "5"
                                }))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1, 35, 37}))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        assertThat(
                        RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                                new Object[] {1, "WRONG", "WRONG"}))
                .isEqualTo(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
    }

    @Test
    public void testResetBatteryLevelOnHeadsetClientStateChange() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.onHeadsetClientConnectionStateChanged(
                mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        verify(mAdapterService).getBatteryService();
        verifyBatteryLevelUpdate(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testHeadsetClientDisconnectedWithBatteryService_NotResetBatteryLevel() {
        int batteryLevel = 10;

        makeBatteryServiceAvailable(mDevice);
        assertThat(mRemoteDevices.hasBatteryService(mDevice)).isTrue();
        verify(mAdapterService).getBatteryService();

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that battery level is not reset.
        mRemoteDevices.onHeadsetClientConnectionStateChanged(
                mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        verify(mAdapterService, times(2)).getBatteryService();

        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(batteryLevel);

        doReturn(Optional.empty()).when(mAdapterService).getBatteryService();
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevelWithBas_overridesHfpBatteryLevel() {
        int batteryLevelHfp = 10;
        int batteryLevelBas = 15;

        makeBatteryServiceAvailable(mDevice);
        assertThat(mRemoteDevices.hasBatteryService(mDevice)).isTrue();
        verify(mAdapterService).getBatteryService();

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevelHfp, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevelHfp);

        // Verify that updating battery service overrides hfp battery level
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevelBas, /* fromBas= */ true);
        verifyBatteryLevelUpdate(batteryLevelBas);

        // Verify that the battery level persists
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ true);
        verifyNoMoreInteractions(mAdapterService);

        // We lost both connection and battery level is reset
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ false);
        verifyBatteryLevelUpdate(BATTERY_LEVEL_UNKNOWN);

        doReturn(Optional.empty()).when(mAdapterService).getBatteryService();
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevelWithHfp_overridesUnknownBasBatteryLevel() {
        int batteryLevelHfp = 10;
        int batteryLevelBas = 15;
        int batteryLevelHfp2 = 20;

        makeBatteryServiceAvailable(mDevice);
        assertThat(mRemoteDevices.hasBatteryService(mDevice)).isTrue();
        verify(mAdapterService).getBatteryService();

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevelHfp, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevelHfp);

        // Verify that updating battery service overrides hfp battery level
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevelBas, /* fromBas= */ true);
        verifyBatteryLevelUpdate(batteryLevelBas);

        // Verify that the battery level persists
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ true);
        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that the battery level
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevelHfp2, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevelHfp2);

        doReturn(Optional.empty()).when(mAdapterService).getBatteryService();
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBasBatteryLevel_withNoHfpLevel_resetsInstantly() {
        int batteryLevelBas = 50;

        // Set an initial battery level from BAS.
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevelBas, /* fromBas= */ true);
        verifyBatteryLevelUpdate(batteryLevelBas);
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(mDevice);
        assertThat(deviceProp).isNotNull();
        assertThat(deviceProp.getBatteryLevel()).isEqualTo(batteryLevelBas);

        // Reset the battery level from BAS (e.g., device disconnected).
        // Since HFP level is unknown, the overall level should reset to UNKNOWN.
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ true);
        verifyBatteryLevelUpdate(BATTERY_LEVEL_UNKNOWN);
        assertThat(deviceProp.getBatteryLevel()).isEqualTo(BATTERY_LEVEL_UNKNOWN);
    }

    @Test
    public void testUpdateBatteryLevelWithSameValue_notSendBroadcast() {
        int batteryLevel = 10;

        makeBatteryServiceAvailable(mDevice);
        assertThat(mRemoteDevices.hasBatteryService(mDevice)).isTrue();
        verify(mAdapterService).getBatteryService();

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);
        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that updating battery service doesn't send broadcast
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ true);
        verifyNoMoreInteractions(mAdapterService);

        // Verify that the battery level isn't reset
        mRemoteDevices.resetBatteryLevel(mDevice, /* fromBas= */ true);
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(batteryLevel);
        verifyNoMoreInteractions(mAdapterService);

        doReturn(Optional.empty()).when(mAdapterService).getBatteryService();
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testAgBatteryLevelIndicator_testCorrectValue() {
        int chargeIndicator = 3;

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that ACTION_AG_EVENT intent updates battery level
        mRemoteDevices.onAgBatteryLevelChanged(mDevice, chargeIndicator);
        verifyBatteryLevelUpdate(RemoteDevices.batteryChargeIndicatorToPercentage(chargeIndicator));
    }

    @Test
    public void testMultipleAddDeviceProperties() {
        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
        DeviceProperties prop1 =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        DeviceProperties prop2 =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        assertThat(prop1).isEqualTo(prop2);
    }

    @Test
    public void testSetGetHfAudioPolicyForRemoteAg() {
        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));

        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(mDevice);
        BluetoothSinkAudioPolicy policies =
                new BluetoothSinkAudioPolicy.Builder()
                        .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .setActiveDevicePolicyAfterConnection(
                                BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .build();
        deviceProp.setHfAudioPolicyForRemoteAg(policies);

        // Verify that the audio policy properties are set and get properly
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getHfAudioPolicyForRemoteAg())
                .isEqualTo(policies);
    }

    @Test
    public void testIsCoordinatedSetMemberAsLeAudioEnabled() {
        ExtendedMockito.doReturn(true)
                .when(() -> Config.isProfileSupported(BluetoothProfile.CSIP_SET_COORDINATOR));

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));

        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(mDevice);
        deviceProp.setIsCoordinatedSetMember(true);

        assertThat(deviceProp.isCoordinatedSetMember()).isTrue();
    }

    @Test
    public void testIsCoordinatedSetMemberAsLeAudioDisabled() {
        ExtendedMockito.doReturn(false)
                .when(() -> Config.isProfileSupported(BluetoothProfile.CSIP_SET_COORDINATOR));

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));

        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(mDevice);
        deviceProp.setIsCoordinatedSetMember(true);

        assertThat(deviceProp.isCoordinatedSetMember()).isFalse();
    }

    @Test
    public void testIsDeviceNull() {
        assertThat(mRemoteDevices.getDeviceProperties(null)).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_BATTERY_LEVEL_UPDATE_ONLY_THROUGH_HF_INDICATOR)
    public void testResetBatteryLevel_testHfpBatteryIndicatorEnabled() {
        int batteryLevel = 25;

        // Verify that device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, batteryLevel, /* fromBas= */ false);

        verifyBatteryLevelUpdate(batteryLevel);

        // Verify that user can get battery level after the update
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(batteryLevel);

        // Verify that the HFP indicator is disabled
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).isHfpBatteryIndicatorEnabled())
                .isFalse();

        // Set HF indicator
        mRemoteDevices.onHfIndicatorStatus(
                mDevice, HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS, true);

        // Verify that the HFP indicator is enabled
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).isHfpBatteryIndicatorEnabled())
                .isTrue();

        // Try to set battery level with vendor specific event
        mRemoteDevices.onVendorSpecificHeadsetEvent(
                mDevice,
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                BluetoothAssignedNumbers.APPLE,
                BluetoothHeadset.AT_CMD_TYPE_SET,
                new Object[] {
                    3,
                    BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                    5,
                    2,
                    1,
                    3,
                    10
                });

        // Vendor specific event xevent
        mRemoteDevices.onVendorSpecificHeadsetEvent(
                mDevice,
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT,
                BluetoothAssignedNumbers.PLANTRONICS,
                BluetoothHeadset.AT_CMD_TYPE_SET,
                getXEventArray(3, 8));

        verifyNoIntentSentForBatteryLevelUpdate();

        // Verify that the battery level is still same
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(batteryLevel);

        int newBatteryLevel = 60;

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice, newBatteryLevel, false);

        verifyBatteryLevelUpdate(newBatteryLevel);

        // Verify that user can get battery level after the update
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(newBatteryLevel);

        // Verify that the HFP indicator is enabled
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).isHfpBatteryIndicatorEnabled())
                .isTrue();

        // Set HF indicator to false
        mRemoteDevices.onHfIndicatorStatus(
                mDevice, HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS, false);

        // Verify that the HFP indicator is disabled
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).isHfpBatteryIndicatorEnabled())
                .isFalse();

        // Try to set battery level with vendor specific event
        mRemoteDevices.onVendorSpecificHeadsetEvent(
                mDevice,
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                BluetoothAssignedNumbers.APPLE,
                BluetoothHeadset.AT_CMD_TYPE_SET,
                new Object[] {
                    3,
                    BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                    4,
                    2,
                    1,
                    3,
                    10
                });

        newBatteryLevel = 50;
        verifyBatteryLevelUpdate(newBatteryLevel);

        // Verify that the battery level is still same
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(newBatteryLevel);
    }

    @Test
    public void testLinkState_bredr() {
        final int transport = TRANSPORT_BREDR;

        // Prepare the base device property
        if (mRemoteDevices.getDeviceProperties(mDevice) == null) {
            mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        }

        // Validate the connected state
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(mDevice);
        deviceProp.setConnected(transport, 1);
        assertThat(deviceProp.getConnectionHandle(transport)).isEqualTo(1);

        // Validate encryption
        deviceProp.setEncryptionStatus(transport, 10, BluetoothDevice.ENCRYPTION_ALGORITHM_AES);
        EncryptionStatus encryptionStatus = deviceProp.getEncryptionStatus(transport);
        assertThat(encryptionStatus.getAlgorithm())
                .isEqualTo(BluetoothDevice.ENCRYPTION_ALGORITHM_AES);
        assertThat(encryptionStatus.getKeySize()).isEqualTo(10);

        // Invalid encryption should be null
        deviceProp.setEncryptionStatus(transport, 0, BluetoothDevice.ENCRYPTION_ALGORITHM_E0);
        assertThat(deviceProp.getEncryptionStatus(transport)).isNull();

        // Set disconnected, and validate the state.
        deviceProp.setDisconnected(transport);
        assertThat(deviceProp.getConnectionHandle(transport)).isEqualTo(BluetoothDevice.ERROR);
    }

    @Test
    @EnableFlags(Flags.FLAG_FIX_INTENT_SELECTION_FOR_ACL)
    public void aclStateChangeCallback_bleOnWithFixIntentFlag_sendsAclIntent() {
        final int transport = TRANSPORT_BREDR;
        doReturn(State.BLE_ON).when(mAdapterService).getState();

        // Test ACL Connected
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                transport,
                AbstractionLayer.BT_ACL_STATE_CONNECTED,
                0, // hciReason
                1); // handle
        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport));

        // Test ACL Disconnected
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                transport,
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                0, // hciReason
                1); // handle
        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport));
    }

    @Test
    @DisableFlags(Flags.FLAG_FIX_INTENT_SELECTION_FOR_ACL)
    public void aclStateChangeCallback_bleOnWithoutFixIntentFlag_sendsBleAclIntent() {
        final int transport = TRANSPORT_BREDR;
        doReturn(State.BLE_ON).when(mAdapterService).getState();

        // Test ACL Connected
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                transport,
                AbstractionLayer.BT_ACL_STATE_CONNECTED,
                0, // hciReason
                1); // handle
        verifyIntentSent(hasAction(BluetoothAdapter.ACTION_BLE_ACL_CONNECTED));

        // Test ACL Disconnected
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                transport,
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                0, // hciReason
                1); // handle
        verifyIntentSent(hasAction(BluetoothAdapter.ACTION_BLE_ACL_DISCONNECTED));
    }

    @Test
    public void deviceFoundCallback_callsDiscoveryResultHandler() {
        // When discovery result restriction is disabled
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                SystemProperties.getBoolean(
                                        "bluetooth.restrict_discovered_device.enabled", false));

        // And device properties exist
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        assertThat(deviceProp).isNotNull();

        // Then discovery result handler should be called
        mRemoteDevices.deviceFoundCallback(Util.getByteAddress(mDevice));
        verify(mAdapterService).discoveryResultHandler(eq(deviceProp));
    }

    @Test
    public void deviceFoundCallback_noProperties_doesNothing() {
        // When device properties do not exist for a device
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();

        // Then discovery result handler should not be called
        mRemoteDevices.deviceFoundCallback(Util.getByteAddress(mDevice));
        verify(mAdapterService, never()).discoveryResultHandler(any());
    }

    @Test
    public void deviceFoundCallback_restrictedNoName_doesNothing() {
        // When discovery result restriction is enabled
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                SystemProperties.getBoolean(
                                        "bluetooth.restrict_discovered_device.enabled", false));

        // And device properties exist but device name is null
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        assertThat(deviceProp).isNotNull();
        assertThat(deviceProp.getName()).isNull();

        // Then discovery result handler should not be called
        mRemoteDevices.deviceFoundCallback(Util.getByteAddress(mDevice));
        verify(mAdapterService, never()).discoveryResultHandler(any());
    }

    @Test
    public void deviceFoundCallback_restrictedWithName_callsDiscoveryResultHandler() {
        // When discovery result restriction is enabled
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                SystemProperties.getBoolean(
                                        "bluetooth.restrict_discovered_device.enabled", false));

        // And device properties exist with a valid device name
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        deviceProp.setName("Test Device");

        // Then discovery result handler should be called
        mRemoteDevices.deviceFoundCallback(Util.getByteAddress(mDevice));
        verify(mAdapterService).discoveryResultHandler(eq(deviceProp));
    }

    @Test
    public void aclStateChangeCallback_unbondedWithoutBondingAttempt_keepsProperties() {
        // Add a device. By default, its bond state is BOND_NONE and no bonding has been
        // initiated.
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBondingInitiator())
                .isEqualTo(0); // BONDING_INITIATOR_NONE

        doReturn(State.ON).when(mAdapterService).getState();

        // Simulate ACL disconnection for this device.
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                TRANSPORT_BREDR,
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                0, // hciReason
                1); // handle

        // Verify that the device properties are NOT removed, because no bonding attempt was ever
        // made for this device.
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
    }

    @Test
    public void aclStateChangeCallback_unbondedWithBondingAttempt_removesProperties() {
        // Add a device and set its state to reflect a prior bonding attempt that failed,
        // resulting in BOND_NONE state.
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        deviceProp.setBondState(BluetoothDevice.BOND_NONE);
        deviceProp.setBondingInitiatedLocally(true); // Signifies a bonding attempt was made
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();

        doReturn(State.ON).when(mAdapterService).getState();

        // Simulate ACL disconnection for this device.
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                TRANSPORT_BREDR,
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                0, // hciReason
                1); // handle

        // Verify that the device properties ARE removed, because a bonding attempt was made.
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
    }

    @Test
    public void aclStateChangeCallback_bondingDevice_keepsPropertiesAndSendsCancel() {
        // Add a device and set its state to BOND_BONDING.
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        deviceProp.setBondState(BluetoothDevice.BOND_BONDING);
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();

        doReturn(State.ON).when(mAdapterService).getState();
        // Mock for sendPairingCancelIntent to get the pairing UI package
        ExtendedMockito.doReturn("some.package.name")
                .when(() -> SystemProperties.get(anyString(), anyString()));

        // Simulate ACL disconnection during bonding.
        mRemoteDevices.aclStateChangeCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Util.getByteAddress(mDevice),
                mDevice.getAddressType(),
                TRANSPORT_BREDR,
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                0, // hciReason
                1); // handle

        // Verify that the device properties are NOT removed.
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        // Verify that a PAIRING_CANCEL intent is sent to dismiss any UI dialogs.
        verifyIntentSent(hasAction(BluetoothDevice.ACTION_PAIRING_CANCEL));
    }

    @Test
    public void setBondState_bondedWithUnknownIdentityAddress_setsIdentityAddress() {
        // Add a device, its properties will be created with an unknown identity address.
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        assertThat(deviceProp.getIdentityAddress()).isEqualTo(DeviceProperties.UNKNOWN_ADDRESS);

        // Set the bond state to BONDED.
        deviceProp.setBondState(BluetoothDevice.BOND_BONDED);

        // Verify that the identity address is now set to the device's own address.
        assertThat(deviceProp.getIdentityAddress().getAddress()).isEqualTo(mDevice.getAddress());
        assertThat(deviceProp.getIdentityAddress().getAddressType())
                .isEqualTo(mDevice.getAddressType());
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void testTriggerUuidNotification() {
        // Make sure that the device is in the remote devices list
        mRemoteDevices.addDeviceProperties(Util.getByteAddress(mDevice), mDevice.getAddressType());

        // Trigger the UUID notification
        mRemoteDevices.triggerUuidNotification(mDevice);

        // Verify that a handler message is sent by the method call
        Message msg = mTestLooperManager.next();
        assertThat(msg).isNotNull();

        // Verify that executing that message results in a direct call to deviceUuidsUpdated
        mTestLooperManager.execute(msg);
        verify(mAdapterService)
                .deviceUuidsUpdated(
                        argThat(device -> device.equals(mDevice)),
                        argThat(uuids -> uuids == null),
                        eq(true));
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void devicePropertyChangedCallback_withUuids_doesNotCallUuidsUpdatedTwice() {
        final int serviceDiscoveryTimeoutMs = RemoteDevices.SERVICE_DISCOVERY_TIMEOUT_MS;

        // Call fetchUuids to queue a timeout message.
        mRemoteDevices.fetchUuids(mDevice, BluetoothDevice.TRANSPORT_AUTO);
        verify(mNativeInterface).getRemoteServices(any(), anyInt());

        // Simulate devicePropertyChangedCallback from native stack.
        byte[] address = Util.getByteAddress(mDevice);
        int[] types = new int[] {AbstractionLayer.BT_PROPERTY_UUIDS};
        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        byte[][] values = new byte[][] {Utils.uuidsToByteArray(sampleUuids)};
        mRemoteDevices.devicePropertyChangedCallback(
                address, mDevice.getAddressType(), types, values);

        // Process the UUID update message.
        Message msg = mTestLooperManager.next();
        assertThat(msg).isNotNull();
        mTestLooperManager.execute(msg);

        // Verify deviceUuidsUpdated is called only once.
        verify(mAdapterService, timeout(serviceDiscoveryTimeoutMs).times(1))
                .deviceUuidsUpdated(
                        argThat(device -> device.equals(mDevice)),
                        argThat(uuids -> Arrays.equals(uuids, sampleUuids)),
                        eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void devicePropertyChangedCallback_withoutUuids_callsUuidsUpdatedOnTimeout() {
        final int serviceDiscoveryTimeoutMs = RemoteDevices.SERVICE_DISCOVERY_TIMEOUT_MS;

        // Call fetchUuids to queue a timeout message.
        mRemoteDevices.fetchUuids(mDevice, BluetoothDevice.TRANSPORT_AUTO);
        verify(mNativeInterface).getRemoteServices(any(), anyInt());

        // Process the UUID update message.
        Message msg = mTestLooperManager.next();
        assertThat(msg).isNotNull();
        mTestLooperManager.execute(msg);

        // Verify deviceUuidsUpdated is called only once.
        verify(mAdapterService, timeout(serviceDiscoveryTimeoutMs).times(1))
                .deviceUuidsUpdated(
                        argThat(device -> device.equals(mDevice)),
                        argThat(uuids -> uuids == null),
                        eq(false));
    }

    @Test
    public void testFetchUuids_skipsDiscoveryIfTimeoutMessageExists() {
        // First call to fetchUuids should start discovery and queue a timeout message
        mRemoteDevices.fetchUuids(mDevice, BluetoothDevice.TRANSPORT_AUTO);

        // Verify that getRemoteServices is called
        verify(mNativeInterface)
                .getRemoteServices(
                        argThat(address -> Arrays.equals(address, Util.getByteAddress(mDevice))),
                        eq(BluetoothDevice.TRANSPORT_AUTO));

        // Second call should not start another discovery because a timeout message is pending
        mRemoteDevices.fetchUuids(mDevice, BluetoothDevice.TRANSPORT_AUTO);

        // Verify that getRemoteServices is not called a second time
        verify(mNativeInterface, times(1)).getRemoteServices(any(byte[].class), anyInt());
    }

    private static Object[] getXEventArray(int batteryLevel, int numLevels) {
        ArrayList<Object> list = new ArrayList<>();
        list.add(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT_BATTERY_LEVEL);
        list.add(batteryLevel);
        list.add(numLevels);
        list.add(0);
        list.add(0);
        return list.toArray();
    }

    private void makeBatteryServiceAvailable(BluetoothDevice device) {
        BatteryService batteryService = mock(BatteryService.class);
        doReturn(STATE_CONNECTED).when(batteryService).getConnectionState(device);
        doReturn(Optional.of(batteryService)).when(mAdapterService).getBatteryService();
    }

    private void verifyBatteryLevelUpdate(int batteryLevel) {
        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, batteryLevel));
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(mDevice).getBatteryLevel())
                .isEqualTo(batteryLevel);
    }

    private void verifyNoIntentSentForBatteryLevelUpdate() {
        mInOrder.verify(mAdapterService, never()).sendBroadcast(any(), any(), any());
        mInOrder.verify(mAdapterService, never()).sendBroadcastAsUser(any(), any(), any(), any());
        mInOrder.verify(mAdapterService, never())
                .sendBroadcastWithMultiplePermissions(any(), any());
    }

    private void verifyIntentSent(Matcher<Intent>... matchers) {
        verifyIntentSent(AllOf.allOf(matchers), anyOf(isA(Bundle.class), nullValue(Bundle.class)));
    }

    private void verifyIntentSent(Matcher<Intent> intentMatcher, Matcher<Bundle> bundleMatcher) {
        mInOrder.verify(mAdapterService)
                .sendBroadcast(
                        (Intent) MockitoHamcrest.argThat(intentMatcher),
                        eq(BLUETOOTH_CONNECT),
                        (Bundle) MockitoHamcrest.argThat(bundleMatcher));
    }

    /**
     * Creates a {@code Matcher<Bundle>} that verifies all key/value pairs in the {@code
     * expectedBundle} exist in the actual Bundle.
     */
    public static Matcher<Bundle> hasExtras(Bundle expectedBundle) {
        if (expectedBundle == null) {
            return nullValue(Bundle.class);
        }

        // Create a list of individual Matcher<Bundle> for each key-value pair
        final List<Matcher<Bundle>> matchers = new ArrayList<>();
        for (String key : expectedBundle.keySet()) {
            matchers.add(hasEntry(key, expectedBundle.get(key)));
        }

        return AllOf.allOf(matchers.toArray(new Matcher[0]));
    }

    @Test
    public void testAddDeviceProperties_addNewDevice() {
        // GIVEN a new device address
        String addressString = "00:11:22:33:44:55";
        byte[] address = Util.getBytesFromAddress(addressString);
        int addressType = BluetoothDevice.ADDRESS_TYPE_PUBLIC;

        // WHEN adding device properties
        DeviceProperties prop = mRemoteDevices.addDeviceProperties(address, addressType);

        // THEN properties are created and stored
        assertThat(prop).isNotNull();
        assertThat(prop.getDevice().getAddress()).isEqualTo(addressString);
        assertThat(mRemoteDevices.getDeviceProperties(prop.getDevice())).isEqualTo(prop);
    }

    @Test
    public void testAddDeviceProperties_lruEviction() {
        int maxDevices = RemoteDevices.MAX_DEVICE_QUEUE_SIZE;

        // Add maxDevices devices
        List<BluetoothDevice> devices = new ArrayList<>();
        for (int i = 0; i < maxDevices; i++) {
            String address = String.format("%02X:00:00:00:00:00", i);
            DeviceProperties prop =
                    mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(address));
            devices.add(prop.getDevice());
        }

        // Verify all are present
        for (BluetoothDevice device : devices) {
            assertThat(mRemoteDevices.getDeviceProperties(device)).isNotNull();
        }

        // WHEN adding another device
        String newAddress = "FF:FF:FF:FF:FF:FF";
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(newAddress));

        // THEN the first device (LRU) should be evicted
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(0))).isNull();
        // And the new one should be present
        assertThat(mRemoteDevices.getDevice(newAddress)).isNotNull();
        // And the second device should still be present
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(1))).isNotNull();
    }

    @Test
    public void testAddDeviceProperties_lruEviction_bondedAndConnectedAreSkipped() {
        int maxDevices = RemoteDevices.MAX_DEVICE_QUEUE_SIZE;

        // Add maxDevices devices
        List<BluetoothDevice> devices = new ArrayList<>();
        for (int i = 0; i < maxDevices; i++) {
            String address = String.format("%02X:00:00:00:00:00", i);
            DeviceProperties prop =
                    mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(address));
            devices.add(prop.getDevice());
            if (i == 0) {
                prop.setBondState(BluetoothDevice.BOND_BONDED);
            } else if (i == 1) {
                prop.setConnected(TRANSPORT_BREDR, 123);
            }
        }

        // WHEN adding another device
        String newAddress = "FF:FF:FF:FF:FF:FF";
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(newAddress));

        // THEN the first (bonded) and second (connected) devices should NOT be evicted
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(0))).isNotNull();
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(1))).isNotNull();

        // The third device should be evicted instead
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(2))).isNull();
    }

    @Test
    public void testAddDeviceProperties_lruEviction_preferDevicesWithoutPackages() {
        int maxDevices = RemoteDevices.MAX_DEVICE_QUEUE_SIZE;

        // Add maxDevices devices
        List<BluetoothDevice> devices =
                fillLruCacheWithDevices(
                        maxDevices,
                        (i, prop) -> {
                            if (i == 0) {
                                // First device has a package associated
                                prop.addPackage("com.test.package");
                            }
                        });

        // WHEN adding the another device
        String newAddress = "FF:FF:FF:FF:FF:FF";
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(newAddress));

        // THEN the first device (with package) should NOT be evicted
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(0))).isNotNull();

        // The second device (without package) should be evicted instead
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(1))).isNull();
    }

    @Test
    public void testAddDeviceProperties_lruEviction_noEligibleDeviceToEvict() {
        int maxDevices = RemoteDevices.MAX_DEVICE_QUEUE_SIZE;

        // GIVEN a full cache where all devices are ineligible for eviction (bonded or connected)
        List<BluetoothDevice> devices =
                fillLruCacheWithDevices(
                        maxDevices,
                        (i, prop) -> {
                            if (i % 2 == 0) {
                                prop.setBondState(BluetoothDevice.BOND_BONDED);
                            } else {
                                prop.setConnected(TRANSPORT_BREDR, 123);
                            }
                        });

        // WHEN adding another device
        String newAddress = "FF:FF:FF:FF:FF:FF";
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(newAddress));

        // THEN no device should be evicted
        for (BluetoothDevice device : devices) {
            assertThat(mRemoteDevices.getDeviceProperties(device)).isNotNull();
        }
        // And the new device should be present, exceeding the cache size temporarily
        assertThat(mRemoteDevices.getDevice(newAddress)).isNotNull();
    }

    @Test
    public void testAddDeviceProperties_lruEviction_evictsCandidateWithPackageWhenNoBetterOption() {
        int maxDevices = RemoteDevices.MAX_DEVICE_QUEUE_SIZE;

        // GIVEN a full cache where one device is bonded and the rest are "last resort" candidates
        List<BluetoothDevice> devices =
                fillLruCacheWithDevices(
                        maxDevices,
                        (i, prop) -> {
                            if (i == 0) {
                                prop.setBondState(BluetoothDevice.BOND_BONDED);
                            } else {
                                prop.addPackage("com.test.package." + i);
                            }
                        });

        // WHEN adding another device
        String newAddress = "FF:FF:FF:FF:FF:FF";
        mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(newAddress));

        // THEN the first device (bonded) should NOT be evicted
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(0))).isNotNull();
        // The second device (the first "last resort" candidate) should be evicted
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(1))).isNull();
        // The third device should still be present
        assertThat(mRemoteDevices.getDeviceProperties(devices.get(2))).isNotNull();
        // And the new device should be present
        assertThat(mRemoteDevices.getDevice(newAddress)).isNotNull();
    }

    @Test
    public void testGetAlias() {
        // Verify that getAlias returns null when device property is null initially
        assertThat(mRemoteDevices.getDeviceProperties(mDevice)).isNull();
        assertThat(mRemoteDevices.getAlias(mDevice)).isNull();

        // Prepare the base device property
        DeviceProperties deviceProp =
                mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(mDevice.getAddress()));
        assertThat(deviceProp).isNotNull();

        // Verify that getAlias returns null when no alias is set
        assertThat(mRemoteDevices.getAlias(mDevice)).isNull();

        // Set an alias
        String testAlias = "TestAlias";
        deviceProp.setAlias(mDevice, testAlias);

        // Verify that getAlias returns the correctly set alias
        assertThat(mRemoteDevices.getAlias(mDevice)).isEqualTo(testAlias);
    }

    private List<BluetoothDevice> fillLruCacheWithDevices(
            int count, BiConsumer<Integer, DeviceProperties> propertySetter) {
        List<BluetoothDevice> devices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String address = String.format("%02X:00:00:00:00:00", i);
            DeviceProperties prop =
                    mRemoteDevices.addDeviceProperties(Util.getBytesFromAddress(address));
            devices.add(prop.getDevice());
            if (propertySetter != null) {
                propertySetter.accept(i, prop);
            }
        }
        return devices;
    }
}
