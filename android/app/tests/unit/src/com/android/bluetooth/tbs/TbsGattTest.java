/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.tbs;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.AdditionalMatchers.*;
import static org.mockito.Mockito.*;

import android.bluetooth.*;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Looper;
import android.util.Pair;

import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;

import com.google.common.primitives.Bytes;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class TbsGattTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();

    @Mock private AdapterService mAdapterService;
    @Mock private BluetoothGattServerProxy mGattServer;
    @Mock private TbsGatt.Callback mCallback;
    @Mock private TbsService mService;
    @Captor private ArgumentCaptor<BluetoothGattService> mGattServiceCaptor;

    private final BluetoothDevice mFirstDevice = getTestDevice(0);
    private final BluetoothDevice mSecondDevice = getTestDevice(1);

    private Integer mCurrentCcid;
    private String mCurrentUci;
    private List<String> mCurrentUriSchemes;
    private String mCurrentProviderName;
    private int mCurrentTechnology;

    private TbsGatt mTbsGatt;

    @Before
    public void setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        doReturn(true).when(mGattServer).addService(any(BluetoothGattService.class));
        doReturn(true).when(mGattServer).open(any(BluetoothGattServerCallback.class));
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mTbsGatt = new TbsGatt(mAdapterService, mService, mGattServer);
    }

    private void prepareDefaultService() {
        mCurrentCcid = 122;
        mCurrentUci = "un" + mCurrentCcid.toString();
        mCurrentUriSchemes = new ArrayList<String>(Arrays.asList("tel"));
        mCurrentProviderName = "unknown";
        mCurrentTechnology = 0x00;

        assertThat(
                        mTbsGatt.init(
                                mCurrentCcid,
                                mCurrentUci,
                                mCurrentUriSchemes,
                                true,
                                true,
                                mCurrentProviderName,
                                mCurrentTechnology,
                                mCallback))
                .isTrue();

        verify(mAdapterService).registerBluetoothStateCallback(any(), any());
        verify(mGattServer).addService(mGattServiceCaptor.capture());
        doReturn(mGattServiceCaptor.getValue()).when(mGattServer).getService(any(UUID.class));
    }

    private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        BluetoothGattService service = mGattServiceCaptor.getValue();
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
        assertThat(characteristic).isNotNull();

        return characteristic;
    }

    private void configureNotifications(
            BluetoothDevice device, BluetoothGattCharacteristic characteristic, boolean enable) {
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);
        assertThat(descriptor).isNotNull();

        mTbsGatt.mGattServerCallback.onDescriptorWriteRequest(
                device,
                1,
                descriptor,
                false,
                true,
                0,
                enable
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        verify(mGattServer)
                .sendResponse(eq(device), eq(1), eq(BluetoothGatt.GATT_SUCCESS), eq(0), any());
        reset(mGattServer);
    }

    private void verifySetValue(
            BluetoothGattCharacteristic characteristic,
            Object value,
            boolean shouldNotify,
            BluetoothDevice device,
            boolean clearGattMock) {
        boolean notifyWithValue = false;

        if (characteristic.getUuid().equals(TbsGatt.UUID_BEARER_PROVIDER_NAME)) {
            boolean valueChanged = !characteristic.getStringValue(0).equals((String) value);
            if (valueChanged) {
                assertThat(mTbsGatt.setBearerProviderName((String) value)).isTrue();
            } else {
                assertThat(mTbsGatt.setBearerProviderName((String) value)).isFalse();
            }
            assertThat(characteristic.getStringValue(0)).isEqualTo((String) value);

        } else if (characteristic.getUuid().equals(TbsGatt.UUID_BEARER_TECHNOLOGY)) {
            assertThat(mTbsGatt.setBearerTechnology((Integer) value)).isTrue();
            assertThat(characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0))
                    .isEqualTo((Integer) value);

        } else if (characteristic
                .getUuid()
                .equals(TbsGatt.UUID_BEARER_URI_SCHEMES_SUPPORTED_LIST)) {
            String valueString = String.join(",", (List<String>) value);
            boolean valueChanged = !characteristic.getStringValue(0).equals(valueString);
            if (valueChanged) {
                assertThat(mTbsGatt.setBearerUriSchemesSupportedList((List<String>) value))
                        .isTrue();
            } else {
                assertThat(mTbsGatt.setBearerUriSchemesSupportedList((List<String>) value))
                        .isFalse();
            }
            assertThat(characteristic.getStringValue(0)).isEqualTo(valueString);

        } else if (characteristic.getUuid().equals(TbsGatt.UUID_STATUS_FLAGS)) {
            Pair<Integer, Boolean> flagStatePair = (Pair<Integer, Boolean>) value;
            notifyWithValue = true;
            switch (flagStatePair.first) {
                case TbsGatt.STATUS_FLAG_INBAND_RINGTONE_ENABLED:
                    if (flagStatePair.second) {
                        assertThat(mTbsGatt.setInbandRingtoneFlag(device)).isTrue();
                    } else {
                        assertThat(mTbsGatt.clearInbandRingtoneFlag(device)).isTrue();
                    }
                    break;

                case TbsGatt.STATUS_FLAG_SILENT_MODE_ENABLED:
                    if (flagStatePair.second) {
                        assertThat(mTbsGatt.setSilentModeFlag()).isTrue();
                    } else {
                        assertThat(mTbsGatt.clearSilentModeFlag()).isTrue();
                    }
                    break;

                default:
                    assertWithMessage("Unexpected flag: " + flagStatePair.first).fail();
            }

        } else if (characteristic.getUuid().equals(TbsGatt.UUID_CALL_STATE)) {
            Pair<Map<Integer, TbsCall>, byte[]> callsExpectedPacketPair =
                    (Pair<Map<Integer, TbsCall>, byte[]>) value;
            assertThat(mTbsGatt.setCallState(callsExpectedPacketPair.first)).isTrue();
            assertThat(characteristic.getValue()).isEqualTo(callsExpectedPacketPair.second);

        } else if (characteristic.getUuid().equals(TbsGatt.UUID_BEARER_LIST_CURRENT_CALLS)) {
            Pair<Map<Integer, TbsCall>, byte[]> callsExpectedPacketPair =
                    (Pair<Map<Integer, TbsCall>, byte[]>) value;
            assertThat(mTbsGatt.setBearerListCurrentCalls(callsExpectedPacketPair.first)).isTrue();
            assertThat(characteristic.getValue()).isEqualTo(callsExpectedPacketPair.second);

        } else if (characteristic.getUuid().equals(TbsGatt.UUID_TERMINATION_REASON)) {
            Pair<Integer, Integer> indexReasonPair = (Pair<Integer, Integer>) value;
            assertThat(mTbsGatt.setTerminationReason(indexReasonPair.first, indexReasonPair.second))
                    .isTrue();
            assertThat(characteristic.getValue())
                    .isEqualTo(
                            new byte[] {
                                indexReasonPair.first.byteValue(),
                                indexReasonPair.second.byteValue()
                            });
        } else if (characteristic.getUuid().equals(TbsGatt.UUID_INCOMING_CALL)) {
            if (value == null) {
                assertThat(mTbsGatt.clearIncomingCall()).isTrue();
                assertThat(characteristic.getValue()).isEmpty();
            } else {
                Pair<Integer, String> indexStrPair = (Pair<Integer, String>) value;
                assertThat(mTbsGatt.setIncomingCall(indexStrPair.first, indexStrPair.second))
                        .isTrue();
                assertThat(characteristic.getValue())
                        .isEqualTo(
                                Bytes.concat(
                                        new byte[] {(byte) indexStrPair.first.byteValue()},
                                        indexStrPair.second.getBytes(StandardCharsets.UTF_8)));
            }

        } else if (characteristic.getUuid().equals(TbsGatt.UUID_CALL_FRIENDLY_NAME)) {
            if (value == null) {
                assertThat(mTbsGatt.clearFriendlyName()).isTrue();
                assertThat(characteristic.getValue()).isEmpty();
            } else {
                Pair<Integer, String> indexNamePair = (Pair<Integer, String>) value;
                assertThat(mTbsGatt.setCallFriendlyName(indexNamePair.first, indexNamePair.second))
                        .isTrue();
                assertThat(characteristic.getValue())
                        .isEqualTo(
                                Bytes.concat(
                                        new byte[] {(byte) indexNamePair.first.byteValue()},
                                        indexNamePair.second.getBytes(StandardCharsets.UTF_8)));
            }
        }

        if (shouldNotify) {
            if (notifyWithValue) {
                verify(mGattServer)
                        .notifyCharacteristicChanged(
                                eq(device), eq(characteristic), eq(false), any());
            } else {
                verify(mGattServer)
                        .notifyCharacteristicChanged(eq(device), eq(characteristic), eq(false));
            }
        } else {
            if (notifyWithValue) {
                verify(mGattServer, never())
                        .notifyCharacteristicChanged(
                                eq(device), eq(characteristic), anyBoolean(), any());
            } else {
                verify(mGattServer, never())
                        .notifyCharacteristicChanged(eq(device), eq(characteristic), anyBoolean());
            }
        }

        if (clearGattMock) {
            reset(mGattServer);
        }
    }

    @Test
    public void testSetBearerProviderName() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_PROVIDER_NAME);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(characteristic, "providerName2", true, mFirstDevice, true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(characteristic, "providerName3", false, mFirstDevice, true);
    }

    @Test
    public void testSetBearerTechnology() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(characteristic, 0x04, true, mFirstDevice, true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(characteristic, 0x05, false, mFirstDevice, true);
    }

    @Test
    public void testSetUriSchemes() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_URI_SCHEMES_SUPPORTED_LIST);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(
                characteristic,
                new ArrayList<>(Arrays.asList("uri2", "uri3")),
                true,
                mFirstDevice,
                true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(
                characteristic,
                new ArrayList<>(Arrays.asList("uri4", "uri5")),
                false,
                mFirstDevice,
                true);
    }

    @Test
    public void testSetCurrentCallList() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_LIST_CURRENT_CALLS);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        Map<Integer, TbsCall> callsMap = new TreeMap<>();
        callsMap.put(
                0x0A,
                TbsCall.create(
                        new BluetoothLeCall(
                                UUID.randomUUID(), "tel:123456789", "John Doe", 0x03, 0x00)));
        byte[] packetExpected =
                new byte[] {
                    // First call
                    (byte) 0x10, // Length of this entry (incl. URI length, excl. this length field
                    // byte)
                    0x0A, // Call index
                    0x03, // Active call state
                    0x00, // Bit0:0-incoming,1-outgoing | Bit1:0-not-withheld,1-withheld |
                    // Bit2:0-provided-by-netw.,1-withheld-by-netw.
                    0x74,
                    0x65,
                    0x6c,
                    0x3a,
                    0x31,
                    0x32,
                    0x33,
                    0x34,
                    0x35,
                    0x36,
                    0x37,
                    0x38,
                    0x39,
                    // URI: tel:123456789
                };
        verifySetValue(
                characteristic,
                new Pair<Map<Integer, TbsCall>, byte[]>(callsMap, packetExpected),
                true,
                mFirstDevice,
                true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        callsMap.put(
                0x0B,
                TbsCall.create(
                        new BluetoothLeCall(
                                UUID.randomUUID(),
                                "tel:987654321",
                                "Kate",
                                0x01,
                                BluetoothLeCall.FLAG_OUTGOING_CALL)));
        packetExpected =
                new byte[] {
                    // First call
                    (byte) 0x10, // Length of this entry (incl. URI length, excl. this length field
                    // byte)
                    0x0A, // Call index
                    0x03, // Active call state
                    0x00, // Bit0:0-incoming,1-outgoing | Bit1:0-not-withheld,1-withheld |
                    // Bit2:0-provided-by-netw.,1-withheld-by-netw.
                    0x74,
                    0x65,
                    0x6c,
                    0x3a,
                    0x31,
                    0x32,
                    0x33,
                    0x34,
                    0x35,
                    0x36,
                    0x37,
                    0x38,
                    0x39,
                    // URI: tel:123456789
                    // Second call
                    (byte) 0x10, // Length of this entry (incl. URI length, excl. this length field
                    // byte)
                    0x0B, // Call index
                    0x01, // Dialing call state
                    0x01, // Bit0:0-incoming,1-outgoing | Bit1:0-not-withheld,1-withheld |
                    // Bit2:0-provided-by-netw.,1-withheld-by-netw.
                    0x74,
                    0x65,
                    0x6c,
                    0x3a,
                    0x39,
                    0x38,
                    0x37,
                    0x36,
                    0x35,
                    0x34,
                    0x33,
                    0x32,
                    0x31,
                    // URI: tel:987654321
                };
        verifySetValue(
                characteristic,
                new Pair<Map<Integer, TbsCall>, byte[]>(callsMap, packetExpected),
                false,
                mFirstDevice,
                true);
    }

    @Test
    public void testSetStatusFlags() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_STATUS_FLAGS);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(
                characteristic,
                new Pair<Integer, Boolean>(TbsGatt.STATUS_FLAG_INBAND_RINGTONE_ENABLED, true),
                true,
                mFirstDevice,
                true);
        verifySetValue(
                characteristic,
                new Pair<Integer, Boolean>(TbsGatt.STATUS_FLAG_SILENT_MODE_ENABLED, true),
                true,
                mFirstDevice,
                true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(
                characteristic,
                new Pair<Integer, Boolean>(TbsGatt.STATUS_FLAG_SILENT_MODE_ENABLED, false),
                false,
                mFirstDevice,
                true);
    }

    @Test
    public void testSetCallState() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_CALL_STATE);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        byte[] packetExpected =
                new byte[] {
                    (byte) 0x0A, // Call index
                    0x03, // Active call state
                    0x00, // Bit0:0-incoming,1-outgoing | Bit1:0-not-withheld,1-withheld |
                    // Bit2:0-provided-by-netw.,1-withheld-by-netw.
                };
        Map<Integer, TbsCall> callsMap = new TreeMap<>();
        callsMap.put(
                0x0A,
                TbsCall.create(
                        new BluetoothLeCall(
                                UUID.randomUUID(), "tel:123456789", "John Doe", 0x03, 0x00)));
        verifySetValue(
                characteristic,
                new Pair<Map<Integer, TbsCall>, byte[]>(callsMap, packetExpected),
                true,
                mFirstDevice,
                true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        packetExpected =
                new byte[] {
                    (byte) 0x0A, // Call index
                    0x03, // Active call state
                    0x00, // Bit0:0-incoming,1-outgoing | Bit1:0-not-withheld,1-withheld |
                    // Bit2:0-provided-by-netw.,1-withheld-by-netw.
                    (byte) 0x0B, // Call index
                    0x04, // Locally Held call state
                    0x00, // Bit0:0-incoming,1-outgoing | Bit1:0-not-withheld,1-withheld |
                    // Bit2:0-provided-by-netw.,1-withheld-by-netw.
                };
        callsMap.put(
                0x0B,
                TbsCall.create(
                        new BluetoothLeCall(
                                UUID.randomUUID(), "tel:987654321", "Kate", 0x04, 0x00)));
        verifySetValue(
                characteristic,
                new Pair<Map<Integer, TbsCall>, byte[]>(callsMap, packetExpected),
                false,
                mFirstDevice,
                true);
    }

    @Test
    public void testSetCallControlPointResult() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT);

        byte requestedOpcode = TbsGatt.CALL_CONTROL_POINT_OPCODE_ACCEPT;
        byte callIndex = 0x01;
        byte result = TbsGatt.CALL_CONTROL_POINT_RESULT_SUCCESS;

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        mTbsGatt.setCallControlPointResult(mFirstDevice, requestedOpcode, callIndex, result);
        assertThat(characteristic.getValue())
                .asList()
                .containsExactly(requestedOpcode, callIndex, result)
                .inOrder();
        verify(mGattServer, after(2000))
                .notifyCharacteristicChanged(eq(mFirstDevice), eq(characteristic), eq(false));
        reset(mGattServer);

        callIndex = 0x02;

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        mTbsGatt.setCallControlPointResult(mFirstDevice, requestedOpcode, callIndex, result);
        assertThat(characteristic.getValue())
                .asList()
                .containsExactly(requestedOpcode, callIndex, result)
                .inOrder();
        verify(mGattServer, after(2000).never())
                .notifyCharacteristicChanged(any(), any(), anyBoolean());
    }

    @Test
    public void testSetTerminationReason() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_TERMINATION_REASON);

        // Check with no CCC configured
        verifySetValue(
                characteristic, new Pair<Integer, Integer>(0x0A, 0x01), false, mFirstDevice, true);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(
                characteristic, new Pair<Integer, Integer>(0x0B, 0x02), true, mFirstDevice, true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(
                characteristic, new Pair<Integer, Integer>(0x0C, 0x02), false, mFirstDevice, true);
    }

    @Test
    public void testSetIncomingCall() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_INCOMING_CALL);

        // Check with no CCC configured
        verifySetValue(
                characteristic,
                new Pair<Integer, String>(0x0A, "tel:123456789"),
                false,
                mFirstDevice,
                true);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(
                characteristic,
                new Pair<Integer, String>(0x0A, "tel:987654321"),
                true,
                mFirstDevice,
                true);

        // No incoming call (should not send any notification)
        verifySetValue(characteristic, null, false, mFirstDevice, true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(
                characteristic,
                new Pair<Integer, String>(0x0A, "tel:123456789"),
                false,
                mFirstDevice,
                true);
    }

    @Test
    public void testSetFriendlyName() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_FRIENDLY_NAME);

        // Check with no CCC configured
        verifySetValue(
                characteristic,
                new Pair<Integer, String>(0x0A, "PersonA"),
                false,
                mFirstDevice,
                true);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(
                characteristic,
                new Pair<Integer, String>(0x0B, "PersonB"),
                true,
                mFirstDevice,
                true);

        // Clear friendly name (should not send any notification)
        verifySetValue(characteristic, null, false, mFirstDevice, true);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(
                characteristic,
                new Pair<Integer, String>(0x0C, "PersonC"),
                false,
                mFirstDevice,
                true);
    }

    @Test
    public void testHandleControlPointRequest() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT);

        // Call the internal GATT callback as if peer device accepts the call
        byte[] value = new byte[] {0x00 /* opcode */, 0x0A /* argument */};
        mTbsGatt.mGattServerCallback.onCharacteristicWriteRequest(
                mFirstDevice, 1, characteristic, false, true, 0, value);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        aryEq(new byte[] {0x00, 0x0A}));

        // Verify the higher layer callback call
        verify(mCallback)
                .onCallControlPointRequest(eq(mFirstDevice), eq(0x00), aryEq(new byte[] {0x0A}));
    }

    @Test
    public void testHandleControlPointInvalidLengthRequest() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT);

        // Call the internal GATT callback as if peer device accepts the call
        byte[] value = new byte[] {0x00 /* opcode */};
        mTbsGatt.mGattServerCallback.onCharacteristicWriteRequest(
                mFirstDevice, 1, characteristic, false, true, 0, value);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH),
                        eq(0),
                        aryEq(new byte[] {0x00}));
    }

    @Test
    public void testSetInbandRingtoneTwice() {
        prepareDefaultService();

        // Make sure notification is sent once
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_STATUS_FLAGS);
        configureNotifications(mFirstDevice, characteristic, true);
        configureNotifications(mSecondDevice, characteristic, true);

        int statusFlagValue = TbsGatt.STATUS_FLAG_INBAND_RINGTONE_ENABLED;

        byte[] valueBytes = new byte[2];
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);

        mTbsGatt.setInbandRingtoneFlag(mFirstDevice);
        mTbsGatt.setInbandRingtoneFlag(mFirstDevice);

        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mFirstDevice), eq(characteristic), eq(false), eq(valueBytes));

        reset(mGattServer);
        mTbsGatt.setInbandRingtoneFlag(mSecondDevice);
        mTbsGatt.setInbandRingtoneFlag(mSecondDevice);

        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mSecondDevice), eq(characteristic), eq(false), eq(valueBytes));
    }

    @Test
    public void testClearInbandRingtoneTwice() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_STATUS_FLAGS);
        configureNotifications(mFirstDevice, characteristic, true);
        configureNotifications(mSecondDevice, characteristic, true);

        int statusFlagValue = TbsGatt.STATUS_FLAG_INBAND_RINGTONE_ENABLED;

        byte[] valueBytes = new byte[2];
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);

        mTbsGatt.setInbandRingtoneFlag(mFirstDevice);
        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mFirstDevice), eq(characteristic), eq(false), eq(valueBytes));

        reset(mGattServer);

        mTbsGatt.setInbandRingtoneFlag(mSecondDevice);
        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mSecondDevice), eq(characteristic), eq(false), eq(valueBytes));

        reset(mGattServer);

        // clear flag
        statusFlagValue = 0;
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);

        mTbsGatt.clearInbandRingtoneFlag(mFirstDevice);
        mTbsGatt.clearInbandRingtoneFlag(mFirstDevice);
        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mFirstDevice), eq(characteristic), eq(false), eq(valueBytes));

        reset(mGattServer);
        mTbsGatt.clearInbandRingtoneFlag(mSecondDevice);
        mTbsGatt.clearInbandRingtoneFlag(mSecondDevice);
        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mSecondDevice), eq(characteristic), eq(false), eq(valueBytes));
    }

    @Test
    public void testSilentModeAndInbandRingtoneFlagsChanges() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_STATUS_FLAGS);
        configureNotifications(mFirstDevice, characteristic, true);
        configureNotifications(mSecondDevice, characteristic, true);

        int statusFlagValue = TbsGatt.STATUS_FLAG_SILENT_MODE_ENABLED;

        byte[] valueBytes = new byte[2];
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);

        // Call it 3 times and see only once notification go to both devices.
        mTbsGatt.setSilentModeFlag();
        mTbsGatt.setSilentModeFlag();
        mTbsGatt.setSilentModeFlag();
        verify(mGattServer, times(2))
                .notifyCharacteristicChanged(any(), eq(characteristic), eq(false), eq(valueBytes));

        reset(mGattServer);

        statusFlagValue =
                TbsGatt.STATUS_FLAG_INBAND_RINGTONE_ENABLED
                        | TbsGatt.STATUS_FLAG_SILENT_MODE_ENABLED;
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);

        mTbsGatt.setInbandRingtoneFlag(mFirstDevice);

        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mFirstDevice), eq(characteristic), eq(false), eq(valueBytes));

        reset(mGattServer);
        mTbsGatt.setInbandRingtoneFlag(mSecondDevice);

        verify(mGattServer)
                .notifyCharacteristicChanged(
                        eq(mSecondDevice), eq(characteristic), eq(false), eq(valueBytes));
        reset(mGattServer);

        statusFlagValue = TbsGatt.STATUS_FLAG_INBAND_RINGTONE_ENABLED;
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);

        // Call it 3 times and see only once notification go to both devices.
        mTbsGatt.clearSilentModeFlag();
        mTbsGatt.clearSilentModeFlag();
        mTbsGatt.clearSilentModeFlag();
        verify(mGattServer, times(2))
                .notifyCharacteristicChanged(any(), eq(characteristic), eq(false), eq(valueBytes));
    }

    @Test
    public void testHandleIsInbandRingtoneEnabled() {
        prepareDefaultService();
        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_STATUS_FLAGS);

        mTbsGatt.mGattServerCallback.onCharacteristicReadRequest(
                mFirstDevice, 1, 0, characteristic);
        // Verify the higher layer callback call
        verify(mCallback).isInbandRingtoneEnabled(eq(mFirstDevice));
    }

    @Test
    public void testCharacteristic_longReadAuthorized() {
        prepareDefaultService();

        /* Twenty three octets long friendly name */
        String title = "01234567890123456789012";
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_FRIENDLY_NAME);
        characteristic.setValue(title);

        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        int offset = 0;
        mTbsGatt.mGattServerCallback.onCharacteristicReadRequest(
                mFirstDevice, 1, offset, characteristic);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(offset),
                        eq(title.getBytes()));

        offset = characteristic.getValue().length;
        mTbsGatt.mGattServerCallback.onCharacteristicReadRequest(
                mFirstDevice, 2, offset, characteristic);

        byte[] empty = new byte[] {};
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(2),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(offset),
                        eq(empty));
    }

    @Test
    public void testCharacteristic_longReadOutsideLenAuthorized() {
        prepareDefaultService();

        /* Twenty three octets long friendly name */
        String title = "01234567890123456789012";
        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_FRIENDLY_NAME);
        characteristic.setValue(title);

        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        int offset = characteristic.getValue().length + 1;
        mTbsGatt.mGattServerCallback.onCharacteristicReadRequest(
                mFirstDevice, 2, offset, characteristic);

        byte[] empty = new byte[] {};
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(2),
                        eq(BluetoothGatt.GATT_INVALID_OFFSET),
                        eq(offset),
                        eq(empty));
    }

    @Test
    public void testClientCharacteristicConfiguration() {
        prepareDefaultService();

        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY);
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);

        // Check with no configuration
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Check with notifications enabled
        configureNotifications(mFirstDevice, characteristic, true);
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Check with notifications disabled
        configureNotifications(mFirstDevice, characteristic, false);
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
    }

    @Test
    public void testMultipleClientCharacteristicConfiguration() {
        prepareDefaultService();

        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY);
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);

        // Check with no configuration
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));

        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mSecondDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mSecondDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Check with notifications enabled for first device
        configureNotifications(mFirstDevice, characteristic, true);
        verifySetValue(characteristic, 4, true, mFirstDevice, true);
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Check if second device is still not subscribed for notifications and will not get it
        verifySetValue(characteristic, 5, false, mSecondDevice, false);
        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mFirstDevice), eq(characteristic), eq(false));
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mSecondDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mSecondDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Check with notifications enabled for first and second device
        configureNotifications(mSecondDevice, characteristic, true);
        verifySetValue(characteristic, 6, true, mSecondDevice, false);
        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mFirstDevice), eq(characteristic), eq(false));
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mSecondDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mSecondDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Disable notification for first device, check if second will get notification
        configureNotifications(mFirstDevice, characteristic, false);
        verifySetValue(characteristic, 7, false, mFirstDevice, false);
        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mSecondDevice), eq(characteristic), eq(false));
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
        reset(mGattServer);

        // Check with notifications disabled of both device
        configureNotifications(mSecondDevice, characteristic, false);
        verifySetValue(characteristic, 4, false, mFirstDevice, false);
        verify(mGattServer, never())
                .notifyCharacteristicChanged(eq(mSecondDevice), eq(characteristic), eq(false));
        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mSecondDevice, 1, 0, descriptor);
        verify(mGattServer)
                .sendResponse(
                        eq(mSecondDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
    }

    @Test
    public void testCharacteristicReadAccessRejectedUnauthorized() {
        prepareDefaultService();

        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY);

        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mTbsGatt.mGattServerCallback.onCharacteristicReadRequest(
                mFirstDevice, 1, 0, characteristic);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testCharacteristicNotifyOnAuthorization() {
        prepareDefaultService();
        assertThat(mGattServiceCaptor.getValue()).isNotNull();

        BluetoothGattCharacteristic characteristic = getCharacteristic(TbsGatt.UUID_STATUS_FLAGS);
        configureNotifications(mFirstDevice, characteristic, true);
        configureNotifications(mSecondDevice, characteristic, true);

        doReturn(mGattServiceCaptor.getValue()).when(mGattServer).getService(any(UUID.class));
        assertThat(mGattServiceCaptor.getValue()).isNotNull();

        // Leave it as unauthorized yet
        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        int statusFlagValue = TbsGatt.STATUS_FLAG_SILENT_MODE_ENABLED;

        // Assume no update on one of the characteristics
        BluetoothGattCharacteristic characteristic2 = getCharacteristic(TbsGatt.UUID_CALL_STATE);
        characteristic2.setValue((byte[]) null);

        assertThat(mGattServiceCaptor.getValue()).isNotNull();

        // Call it once but expect no notification for the unauthorized device
        byte[] valueBytes = new byte[2];
        valueBytes[0] = (byte) (statusFlagValue & 0xFF);
        valueBytes[1] = (byte) ((statusFlagValue >> 8) & 0xFF);
        mTbsGatt.setSilentModeFlag();
        verify(mGattServer, never())
                .notifyCharacteristicChanged(any(), eq(characteristic), eq(false), eq(valueBytes));

        // Expect a single notification for the just authorized device
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));
        assertThat(mGattServiceCaptor.getValue()).isNotNull();
        mTbsGatt.onDeviceAuthorizationSet(mFirstDevice);
        verify(mGattServer, never())
                .notifyCharacteristicChanged(any(), eq(characteristic2), eq(false));
        verify(mGattServer)
                .notifyCharacteristicChanged(any(), eq(characteristic), eq(false), eq(valueBytes));
    }

    @Test
    public void testCharacteristicReadAccessUnknownUnauthorized() {
        prepareDefaultService();

        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY);

        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mTbsGatt.mGattServerCallback.onCharacteristicReadRequest(
                mFirstDevice, 1, 0, characteristic);

        verify(mService, never()).onDeviceUnauthorized(eq(mFirstDevice));
    }

    @Test
    public void testCharacteristicWriteAccessRejectedUnauthorized() {
        prepareDefaultService();

        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT);

        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        byte[] value =
                new byte[] {
                    0x00, /* opcode */ 0x0A, /* argument */
                };

        mTbsGatt.mGattServerCallback.onCharacteristicWriteRequest(
                mFirstDevice, 1, characteristic, false, true, 0, value);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testCharacteristicWriteAccessUnknownUnauthorized() {
        prepareDefaultService();

        BluetoothGattCharacteristic characteristic =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT);

        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        byte[] value =
                new byte[] {
                    0x00, /* opcode */ 0x0A, /* argument */
                };

        mTbsGatt.mGattServerCallback.onCharacteristicWriteRequest(
                mFirstDevice, 1, characteristic, false, true, 0, value);

        verify(mService).onDeviceUnauthorized(eq(mFirstDevice));
    }

    @Test
    public void testDescriptorReadAccessRejectedUnauthorized() {
        prepareDefaultService();

        BluetoothGattDescriptor descriptor =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY)
                        .getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);
        assertThat(descriptor).isNotNull();

        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testDescriptorReadAccessUnknownUnauthorized() {
        prepareDefaultService();

        BluetoothGattDescriptor descriptor =
                getCharacteristic(TbsGatt.UUID_BEARER_TECHNOLOGY)
                        .getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);
        assertThat(descriptor).isNotNull();

        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mTbsGatt.mGattServerCallback.onDescriptorReadRequest(mFirstDevice, 1, 0, descriptor);

        verify(mService, never()).onDeviceUnauthorized(eq(mFirstDevice));
    }

    @Test
    public void testDescriptorWriteAccessRejectedUnauthorized() {
        prepareDefaultService();

        BluetoothGattDescriptor descriptor =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT)
                        .getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);
        assertThat(descriptor).isNotNull();

        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        byte[] value =
                new byte[] {
                    0x00, /* opcode */ 0x0A, /* argument */
                };

        mTbsGatt.mGattServerCallback.onDescriptorWriteRequest(
                mFirstDevice, 1, descriptor, false, true, 0, value);

        verify(mGattServer)
                .sendResponse(
                        eq(mFirstDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testDescriptorWriteAccessUnknownUnauthorized() {
        prepareDefaultService();

        BluetoothGattDescriptor descriptor =
                getCharacteristic(TbsGatt.UUID_CALL_CONTROL_POINT)
                        .getDescriptor(TbsGatt.UUID_CLIENT_CHARACTERISTIC_CONFIGURATION);
        assertThat(descriptor).isNotNull();

        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        byte[] value =
                new byte[] {
                    0x00, /* opcode */ 0x0A, /* argument */
                };

        mTbsGatt.mGattServerCallback.onDescriptorWriteRequest(
                mFirstDevice, 1, descriptor, false, true, 0, value);

        verify(mService, never()).onDeviceUnauthorized(eq(mFirstDevice));
    }
}
