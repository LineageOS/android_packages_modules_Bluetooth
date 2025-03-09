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

package com.android.bluetooth.mcp;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.os.Looper;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.le_audio.LeAudioService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class MediaControlGattServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private MediaControlGattService.BluetoothGattServerProxy mGattServer;
    @Mock private McpService mMcpService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private MediaControlServiceCallbacks mCallback;

    @Captor private ArgumentCaptor<BluetoothGattService> mGattServiceCaptor;

    private BluetoothDevice mCurrentDevice;

    private static final UUID UUID_GMCS = UUID.fromString("00001849-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int TEST_CCID = 1;

    private MediaControlGattService mMediaControlGattService;

    @Before
    public void setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        TestUtils.setAdapterService(mAdapterService);

        doReturn(true).when(mGattServer).addService(any(BluetoothGattService.class));
        doReturn(new BluetoothDevice[0]).when(mAdapterService).getBondedDevices();
        doReturn(BluetoothDevice.ACCESS_ALLOWED).when(mMcpService).getDeviceAuthorization(any());

        mMediaControlGattService = new MediaControlGattService(mMcpService, mCallback, TEST_CCID);
        mMediaControlGattService.setBluetoothGattServerForTesting(mGattServer);
        mMediaControlGattService.setServiceManagerForTesting(mMcpService);
        mMediaControlGattService.setLeAudioServiceForTesting(mLeAudioService);
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.clearAdapterService(mAdapterService);
    }

    private void prepareConnectedDevice() {
        if (mCurrentDevice == null) {
            mCurrentDevice = getTestDevice(0);
            List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
            devices.add(mCurrentDevice);
            doReturn(devices).when(mGattServer).getConnectedDevices();
            doReturn(true).when(mGattServer).isDeviceConnected(eq(mCurrentDevice));
        }
    }

    private void prepareConnectedDevicesCccVal(
            BluetoothGattCharacteristic characteristic, byte[] value) {
        prepareConnectedDevice();
        mMediaControlGattService.setCcc(mCurrentDevice, characteristic.getUuid(), 0, value, true);
    }

    @Test
    public void testInit() {
        long mMandatoryFeatures = ServiceFeature.ALL_MANDATORY_SERVICE_FEATURES;

        doReturn(mMandatoryFeatures).when(mCallback).onGetFeatureFlags();
        assertThat(mMediaControlGattService.init(UUID_GMCS)).isTrue();
        assertThat(mMediaControlGattService.getServiceUuid()).isEqualTo(UUID_GMCS);
        assertThat(mMediaControlGattService.getContentControlId()).isEqualTo(TEST_CCID);

        doReturn(true).when(mGattServer).removeService(any(BluetoothGattService.class));
        mMediaControlGattService.destroy();
        verify(mCallback).onServiceInstanceUnregistered(eq(ServiceStatus.OK));
    }

    @Test
    public void testFailingInit() {
        long mMandatoryFeatures = 0;

        doReturn(mMandatoryFeatures).when(mCallback).onGetFeatureFlags();
        assertThat(mMediaControlGattService.init(UUID_GMCS)).isFalse();
    }

    private BluetoothGattService initAllFeaturesGattService() {
        long features =
                ServiceFeature.ALL_MANDATORY_SERVICE_FEATURES
                        | ServiceFeature.PLAYER_ICON_OBJ_ID
                        | ServiceFeature.PLAYER_ICON_URL
                        | ServiceFeature.PLAYBACK_SPEED
                        | ServiceFeature.SEEKING_SPEED
                        | ServiceFeature.CURRENT_TRACK_SEGMENT_OBJ_ID
                        | ServiceFeature.CURRENT_TRACK_OBJ_ID
                        | ServiceFeature.NEXT_TRACK_OBJ_ID
                        | ServiceFeature.CURRENT_GROUP_OBJ_ID
                        | ServiceFeature.PARENT_GROUP_OBJ_ID
                        | ServiceFeature.PLAYING_ORDER
                        | ServiceFeature.PLAYING_ORDER_SUPPORTED
                        | ServiceFeature.MEDIA_CONTROL_POINT
                        | ServiceFeature.MEDIA_CONTROL_POINT_OPCODES_SUPPORTED
                        | ServiceFeature.SEARCH_RESULT_OBJ_ID
                        | ServiceFeature.SEARCH_CONTROL_POINT
                        // Notifications
                        | ServiceFeature.PLAYER_NAME_NOTIFY
                        | ServiceFeature.TRACK_TITLE_NOTIFY
                        | ServiceFeature.TRACK_DURATION_NOTIFY
                        | ServiceFeature.TRACK_POSITION_NOTIFY
                        | ServiceFeature.PLAYBACK_SPEED_NOTIFY
                        | ServiceFeature.SEEKING_SPEED_NOTIFY
                        | ServiceFeature.CURRENT_TRACK_OBJ_ID_NOTIFY
                        | ServiceFeature.NEXT_TRACK_OBJ_ID_NOTIFY
                        | ServiceFeature.CURRENT_GROUP_OBJ_ID_NOTIFY
                        | ServiceFeature.PARENT_GROUP_OBJ_ID_NOTIFY
                        | ServiceFeature.PLAYING_ORDER_NOTIFY
                        | ServiceFeature.MEDIA_CONTROL_POINT_OPCODES_SUPPORTED_NOTIFY;

        doReturn(features).when(mCallback).onGetFeatureFlags();
        assertThat(mMediaControlGattService.init(UUID_GMCS)).isTrue();

        verify(mGattServer).addService(mGattServiceCaptor.capture());

        // Capture GATT Service definition for verification
        BluetoothGattService service = mGattServiceCaptor.getValue();
        assertThat(service).isNotNull();

        // Call back the low level GATT callback and expect proper higher level callback to be
        // called
        mMediaControlGattService.mServerCallback.onServiceAdded(
                BluetoothGatt.GATT_SUCCESS, service);
        verify(mCallback)
                .onServiceInstanceRegistered(
                        any(ServiceStatus.class), any(MediaControlGattServiceInterface.class));

        return service;
    }

    @Test
    public void testGattServerFullInitialState() {
        BluetoothGattService service = initAllFeaturesGattService();

        // Check initial state of all mandatory characteristics
        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYER_NAME);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getStringValue(0)).isEmpty();

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_TITLE);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getStringValue(0)).isEmpty();

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_DURATION);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0)
                                .intValue())
                .isEqualTo(0xFFFFFFFF);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0)
                                .intValue())
                .isEqualTo(0xFFFFFFFF);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_MEDIA_STATE);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                                .intValue())
                .isEqualTo(MediaState.INACTIVE.getValue());

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_CONTENT_CONTROL_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(BluetoothGattCharacteristic.PROPERTY_READ);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                                .intValue())
                .isEqualTo(TEST_CCID);

        // Check initial state of all optional characteristics
        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYER_ICON_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(BluetoothGattCharacteristic.PROPERTY_READ);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYER_ICON_URL);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(BluetoothGattCharacteristic.PROPERTY_READ);
        assertThat(characteristic.getStringValue(0)).isEmpty();

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_CHANGED);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(BluetoothGattCharacteristic.PROPERTY_NOTIFY);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYBACK_SPEED);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8, 0)
                                .intValue())
                .isEqualTo(0);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_SEEKING_SPEED);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8, 0)
                                .intValue())
                .isEqualTo(0);

        characteristic =
                service.getCharacteristic(
                        MediaControlGattService.UUID_CURRENT_TRACK_SEGMENT_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(BluetoothGattCharacteristic.PROPERTY_READ);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_TRACK_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_NEXT_TRACK_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_GROUP_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PARENT_GROUP_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                                .intValue())
                .isEqualTo(PlayingOrder.SINGLE_ONCE.getValue());

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER_SUPPORTED);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(BluetoothGattCharacteristic.PROPERTY_READ);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)
                                .intValue())
                .isEqualTo(SupportedPlayingOrder.SINGLE_ONCE);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_MEDIA_CONTROL_POINT);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);

        characteristic =
                service.getCharacteristic(
                        MediaControlGattService.UUID_MEDIA_CONTROL_POINT_OPCODES_SUPPORTED);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0)
                                .intValue())
                .isEqualTo(MediaControlGattService.INITIAL_SUPPORTED_OPCODES);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_SEARCH_RESULT_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        assertThat(characteristic.getValue().length).isEqualTo(0);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_SEARCH_CONTROL_POINT);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getProperties())
                .isEqualTo(
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                | BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
    }

    @Test
    public void testUpdatePlayerState() {
        BluetoothGattService service = initAllFeaturesGattService();
        Map<PlayerStateField, Object> state_map = new HashMap<>();
        float playback_speed = 0.5f;
        PlayingOrder playing_order = PlayingOrder.IN_ORDER_REPEAT;
        long track_position = 100;
        String player_name = "TestPlayerName";
        String icon_url = "www.testiconurl.com";
        Long icon_obj_id = 7L;
        Integer playing_order_supported =
                SupportedPlayingOrder.IN_ORDER_REPEAT
                        | SupportedPlayingOrder.SINGLE_ONCE
                        | SupportedPlayingOrder.SINGLE_REPEAT
                        | SupportedPlayingOrder.IN_ORDER_ONCE
                        | SupportedPlayingOrder.IN_ORDER_REPEAT
                        | SupportedPlayingOrder.OLDEST_ONCE
                        | SupportedPlayingOrder.OLDEST_REPEAT
                        | SupportedPlayingOrder.NEWEST_ONCE
                        | SupportedPlayingOrder.NEWEST_REPEAT
                        | SupportedPlayingOrder.SHUFFLE_ONCE
                        | SupportedPlayingOrder.SHUFFLE_REPEAT;
        Integer opcodes_supported =
                Request.SupportedOpcodes.NONE
                        | Request.SupportedOpcodes.PLAY
                        | Request.SupportedOpcodes.PAUSE
                        | Request.SupportedOpcodes.FAST_REWIND
                        | Request.SupportedOpcodes.FAST_FORWARD
                        | Request.SupportedOpcodes.STOP
                        | Request.SupportedOpcodes.MOVE_RELATIVE
                        | Request.SupportedOpcodes.PREVIOUS_SEGMENT
                        | Request.SupportedOpcodes.NEXT_SEGMENT
                        | Request.SupportedOpcodes.FIRST_SEGMENT
                        | Request.SupportedOpcodes.LAST_SEGMENT
                        | Request.SupportedOpcodes.GOTO_SEGMENT
                        | Request.SupportedOpcodes.PREVIOUS_TRACK
                        | Request.SupportedOpcodes.NEXT_TRACK
                        | Request.SupportedOpcodes.FIRST_TRACK
                        | Request.SupportedOpcodes.LAST_TRACK
                        | Request.SupportedOpcodes.GOTO_TRACK
                        | Request.SupportedOpcodes.PREVIOUS_GROUP
                        | Request.SupportedOpcodes.NEXT_GROUP
                        | Request.SupportedOpcodes.FIRST_GROUP
                        | Request.SupportedOpcodes.LAST_GROUP;
        String track_title = "Test Song";
        long track_duration = 1000;
        MediaState playback_state = MediaState.SEEKING;
        float seeking_speed = 2.0f;

        state_map.put(PlayerStateField.PLAYBACK_SPEED, playback_speed);
        state_map.put(PlayerStateField.PLAYING_ORDER, playing_order);
        state_map.put(PlayerStateField.TRACK_POSITION, track_position);
        state_map.put(PlayerStateField.PLAYER_NAME, player_name);
        state_map.put(PlayerStateField.ICON_URL, icon_url);
        state_map.put(PlayerStateField.ICON_OBJ_ID, icon_obj_id);
        state_map.put(PlayerStateField.PLAYING_ORDER_SUPPORTED, playing_order_supported);
        state_map.put(PlayerStateField.OPCODES_SUPPORTED, opcodes_supported);
        state_map.put(PlayerStateField.TRACK_TITLE, track_title);
        state_map.put(PlayerStateField.TRACK_DURATION, track_duration);
        state_map.put(PlayerStateField.PLAYBACK_STATE, playback_state);
        state_map.put(PlayerStateField.SEEKING_SPEED, seeking_speed);
        mMediaControlGattService.updatePlayerState(state_map);

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYBACK_SPEED);
        assertThat(characteristic).isNotNull();
        assertThat(mMediaControlGattService.getPlaybackSpeedChar().floatValue())
                .isEqualTo(playback_speed);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER);
        assertThat(characteristic).isNotNull();
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                                .intValue())
                .isEqualTo(playing_order.getValue());

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);
        assertThat(characteristic).isNotNull();
        // Set value as ms, kept in characteristic as 0.01s
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0)
                                .intValue())
                .isEqualTo(track_position / 10);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYER_NAME);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getStringValue(0)).isEqualTo(player_name);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYER_ICON_URL);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getStringValue(0)).isEqualTo(icon_url);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYER_ICON_OBJ_ID);
        assertThat(characteristic).isNotNull();
        assertThat(mMediaControlGattService.byteArray2ObjId(characteristic.getValue()))
                .isEqualTo(icon_obj_id.longValue());

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER_SUPPORTED);
        assertThat(characteristic).isNotNull();
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)
                                .intValue())
                .isEqualTo(playing_order_supported.intValue());

        characteristic =
                service.getCharacteristic(
                        MediaControlGattService.UUID_MEDIA_CONTROL_POINT_OPCODES_SUPPORTED);
        assertThat(characteristic).isNotNull();
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0)
                                .intValue())
                .isEqualTo(opcodes_supported.intValue());

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_TITLE);
        assertThat(characteristic).isNotNull();
        assertThat(characteristic.getStringValue(0)).isEqualTo(track_title);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_DURATION);
        assertThat(characteristic).isNotNull();
        // Set value as ms, kept in characteristic as 0.01s
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0)
                                .intValue())
                .isEqualTo(track_duration / 10);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_MEDIA_STATE);
        assertThat(characteristic).isNotNull();
        assertThat(
                        characteristic
                                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                                .intValue())
                .isEqualTo(playback_state.getValue());

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_SEEKING_SPEED);
        assertThat(characteristic).isNotNull();
        assertThat(mMediaControlGattService.getSeekingSpeedChar().floatValue())
                .isEqualTo(seeking_speed);
    }

    private void verifyWriteObjIdsValid(
            BluetoothGattCharacteristic characteristic, long value, int id) {
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice,
                1,
                characteristic,
                false,
                true,
                0,
                mMediaControlGattService.objId2ByteArray(value));

        verify(mCallback).onSetObjectIdRequest(eq(id), eq(value));

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(mMediaControlGattService.objId2ByteArray(value)));
    }

    @Test
    public void testWriteCallbacksValid() {
        BluetoothGattService service = initAllFeaturesGattService();
        int track_position = 100;
        byte playback_speed = 64;
        long current_track_obj_id = 7;
        long next_track_obj_id = 77;
        long current_group_obj_id = 777;
        PlayingOrder playing_order = PlayingOrder.IN_ORDER_REPEAT;
        Integer playing_order_supported = SupportedPlayingOrder.IN_ORDER_REPEAT;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) track_position);

        prepareConnectedDevice();
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mCallback).onTrackPositionSetRequest(eq(track_position * 10L));

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(bb.array()));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYBACK_SPEED);

        bb = ByteBuffer.allocate(Byte.BYTES);
        bb.put(playback_speed);

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mCallback).onPlaybackSpeedSetRequest(eq((float) Math.pow(2, playback_speed / 64.0)));

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(bb.array()));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_TRACK_OBJ_ID);
        verifyWriteObjIdsValid(
                characteristic, current_track_obj_id, ObjectIds.CURRENT_TRACK_OBJ_ID);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_NEXT_TRACK_OBJ_ID);
        verifyWriteObjIdsValid(characteristic, next_track_obj_id, ObjectIds.NEXT_TRACK_OBJ_ID);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_GROUP_OBJ_ID);
        verifyWriteObjIdsValid(
                characteristic, current_group_obj_id, ObjectIds.CURRENT_GROUP_OBJ_ID);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER_SUPPORTED);
        characteristic.setValue(
                playing_order_supported, BluetoothGattCharacteristic.FORMAT_UINT16, 0);
        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER);
        bb = ByteBuffer.allocate(Byte.BYTES);
        bb.put((byte) playing_order.getValue());

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mCallback).onPlayingOrderSetRequest(eq(playing_order.getValue()));

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(0),
                        eq(bb.array()));
    }

    private void verifyWriteObjIdsInvalid(
            BluetoothGattCharacteristic characteristic, byte diffByte) {
        byte[] value = new byte[] {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, diffByte};
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, value);

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH),
                        eq(0),
                        eq(value));
    }

    @Test
    public void testWriteCallbacksInvalid() {
        BluetoothGattService service = initAllFeaturesGattService();
        int track_position = 100;
        byte playback_speed = 64;
        PlayingOrder playing_order = PlayingOrder.IN_ORDER_REPEAT;
        byte diff_byte = 0x00;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES + 1).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) track_position);
        bb.put((byte) 0);

        prepareConnectedDevice();
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH),
                        eq(0),
                        eq(bb.array()));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYBACK_SPEED);

        bb = ByteBuffer.allocate(Byte.BYTES + 1);
        bb.put(playback_speed);
        bb.put((byte) 0);

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH),
                        eq(0),
                        eq(bb.array()));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_TRACK_OBJ_ID);
        verifyWriteObjIdsInvalid(characteristic, diff_byte++);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_NEXT_TRACK_OBJ_ID);
        verifyWriteObjIdsInvalid(characteristic, diff_byte++);

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_GROUP_OBJ_ID);
        verifyWriteObjIdsInvalid(characteristic, diff_byte++);

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER);
        bb = ByteBuffer.allocate(Byte.BYTES + 1);
        bb.put((byte) playing_order.getValue());
        bb.put((byte) 0);

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH),
                        eq(0),
                        eq(bb.array()));
    }

    private void testNotify(boolean registerForNotification) {
        BluetoothGattService service = initAllFeaturesGattService();
        String player_name = "TestPlayerName";
        String track_title = "Test Song";
        long track_duration = 1000;
        long track_position = 100;
        float playback_speed = 0.5f;
        float seeking_speed = 2.0f;
        Long obj_id = 7L;
        PlayingOrder playing_order = PlayingOrder.IN_ORDER_REPEAT;
        int playing_order_supported = SupportedPlayingOrder.IN_ORDER_REPEAT;
        int playback_state = MediaState.SEEKING.getValue();
        Integer opcodes_supported =
                Request.SupportedOpcodes.NONE
                        | Request.SupportedOpcodes.PLAY
                        | Request.SupportedOpcodes.PAUSE
                        | Request.SupportedOpcodes.FAST_REWIND
                        | Request.SupportedOpcodes.FAST_FORWARD
                        | Request.SupportedOpcodes.STOP
                        | Request.SupportedOpcodes.MOVE_RELATIVE
                        | Request.SupportedOpcodes.PREVIOUS_SEGMENT
                        | Request.SupportedOpcodes.NEXT_SEGMENT
                        | Request.SupportedOpcodes.FIRST_SEGMENT
                        | Request.SupportedOpcodes.LAST_SEGMENT
                        | Request.SupportedOpcodes.GOTO_SEGMENT
                        | Request.SupportedOpcodes.PREVIOUS_TRACK
                        | Request.SupportedOpcodes.NEXT_TRACK
                        | Request.SupportedOpcodes.FIRST_TRACK
                        | Request.SupportedOpcodes.LAST_TRACK
                        | Request.SupportedOpcodes.GOTO_TRACK
                        | Request.SupportedOpcodes.PREVIOUS_GROUP
                        | Request.SupportedOpcodes.NEXT_GROUP
                        | Request.SupportedOpcodes.FIRST_GROUP
                        | Request.SupportedOpcodes.LAST_GROUP;
        byte[] ccc_val =
                registerForNotification
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone()
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.clone();
        int times_cnt = registerForNotification ? 1 : 0;
        int media_control_request_opcode = Request.Opcodes.MOVE_RELATIVE;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYER_NAME);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updatePlayerNameChar(player_name, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_TITLE);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateTrackTitleChar(track_title, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_DURATION);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateTrackDurationChar(track_duration, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_MEDIA_STATE);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateMediaStateChar(playback_state);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateTrackPositionChar(track_position, false);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYBACK_SPEED);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updatePlaybackSpeedChar(playback_speed, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_SEEKING_SPEED);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateSeekingSpeedChar(seeking_speed, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_TRACK_OBJ_ID);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateObjectID(ObjectIds.CURRENT_TRACK_OBJ_ID, obj_id);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_NEXT_TRACK_OBJ_ID);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateObjectID(ObjectIds.NEXT_TRACK_OBJ_ID, obj_id);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_CURRENT_GROUP_OBJ_ID);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateObjectID(ObjectIds.CURRENT_GROUP_OBJ_ID, obj_id);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PARENT_GROUP_OBJ_ID);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateObjectID(ObjectIds.PARENT_GROUP_OBJ_ID, obj_id);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic = service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updatePlayingOrderSupportedChar(playing_order_supported);
        mMediaControlGattService.updatePlayingOrderChar(playing_order, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_MEDIA_CONTROL_POINT);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.setMediaControlRequestResult(
                new Request(media_control_request_opcode, 0), Request.Results.SUCCESS);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(
                        MediaControlGattService.UUID_MEDIA_CONTROL_POINT_OPCODES_SUPPORTED);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateSupportedOpcodesChar(opcodes_supported, true);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_SEARCH_RESULT_OBJ_ID);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.updateObjectID(ObjectIds.SEARCH_RESULT_OBJ_ID, obj_id);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_SEARCH_CONTROL_POINT);
        prepareConnectedDevicesCccVal(characteristic, ccc_val);
        mMediaControlGattService.setSearchRequestResult(
                null, SearchRequest.Results.SUCCESS, obj_id);
        verify(mGattServer, times(times_cnt))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));
    }

    @Test
    public void testNotifyRegistered() {
        testNotify(true);
    }

    @Test
    public void testNotifyNotRegistered() {
        testNotify(false);
    }

    private void verifyMediaControlPointRequest(
            int opcode, Integer value, int expectedGattResult, int invocation_count) {
        ByteBuffer bb;

        if (expectedGattResult == BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH) {
            bb = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        } else {
            bb =
                    ByteBuffer.allocate(value != null ? (Integer.BYTES + Byte.BYTES) : Byte.BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN);
        }
        bb.put((byte) opcode);
        if (value != null) {
            bb.putInt(value);
        }

        assertThat(
                        mMediaControlGattService.handleMediaControlPointRequest(
                                mCurrentDevice, bb.array()))
                .isEqualTo(expectedGattResult);

        if (expectedGattResult == BluetoothGatt.GATT_SUCCESS) {
            // Verify if callback comes to profile
            verify(mCallback, times(invocation_count++)).onMediaControlRequest(any(Request.class));
        }
    }

    private void verifyMediaControlPointRequests(int expectedGattResult) {
        BluetoothGattService service = initAllFeaturesGattService();
        int invocation_count = 1;
        Integer opcodes_supported =
                Request.SupportedOpcodes.PLAY
                        | Request.SupportedOpcodes.PAUSE
                        | Request.SupportedOpcodes.FAST_REWIND
                        | Request.SupportedOpcodes.FAST_FORWARD
                        | Request.SupportedOpcodes.STOP
                        | Request.SupportedOpcodes.MOVE_RELATIVE
                        | Request.SupportedOpcodes.PREVIOUS_SEGMENT
                        | Request.SupportedOpcodes.NEXT_SEGMENT
                        | Request.SupportedOpcodes.FIRST_SEGMENT
                        | Request.SupportedOpcodes.LAST_SEGMENT
                        | Request.SupportedOpcodes.GOTO_SEGMENT
                        | Request.SupportedOpcodes.PREVIOUS_TRACK
                        | Request.SupportedOpcodes.NEXT_TRACK
                        | Request.SupportedOpcodes.FIRST_TRACK
                        | Request.SupportedOpcodes.LAST_TRACK
                        | Request.SupportedOpcodes.GOTO_TRACK
                        | Request.SupportedOpcodes.PREVIOUS_GROUP
                        | Request.SupportedOpcodes.NEXT_GROUP
                        | Request.SupportedOpcodes.FIRST_GROUP
                        | Request.SupportedOpcodes.LAST_GROUP
                        | Request.SupportedOpcodes.GOTO_GROUP;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(
                        MediaControlGattService.UUID_MEDIA_CONTROL_POINT_OPCODES_SUPPORTED);
        prepareConnectedDevicesCccVal(
                characteristic, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.clone());
        mMediaControlGattService.updateSupportedOpcodesChar(opcodes_supported, true);
        verify(mGattServer, times(0))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        verifyMediaControlPointRequest(
                Request.Opcodes.PLAY, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.PAUSE, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.FAST_REWIND, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.FAST_FORWARD, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.STOP, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.MOVE_RELATIVE, 100, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.PREVIOUS_SEGMENT, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.NEXT_SEGMENT, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.FIRST_SEGMENT, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.LAST_SEGMENT, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.GOTO_SEGMENT, 10, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.PREVIOUS_TRACK, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.NEXT_TRACK, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.FIRST_TRACK, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.LAST_TRACK, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.GOTO_TRACK, 7, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.PREVIOUS_GROUP, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.NEXT_GROUP, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.FIRST_GROUP, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.LAST_GROUP, null, expectedGattResult, invocation_count++);
        verifyMediaControlPointRequest(
                Request.Opcodes.GOTO_GROUP, 10, expectedGattResult, invocation_count++);
    }

    @Test
    public void testMediaControlPointRequestValid() {
        verifyMediaControlPointRequests(BluetoothGatt.GATT_SUCCESS);
    }

    @Test
    public void testMediaControlPointRequestInvalidLength() {
        verifyMediaControlPointRequests(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH);
    }

    @Test
    public void testMediaControlPointRequestInvalid() {
        assertThat(mMediaControlGattService.isOpcodeSupported(Request.Opcodes.PLAY)).isFalse();
    }

    @Test
    public void testMediaControlPointeRequest_OpcodePlayCallLeAudioServiceSetActiveDevice() {
        initAllFeaturesGattService();
        prepareConnectedDevice();
        mMediaControlGattService.updateSupportedOpcodesChar(Request.SupportedOpcodes.PLAY, true);
        verifyMediaControlPointRequest(Request.Opcodes.PLAY, null, BluetoothGatt.GATT_SUCCESS, 1);

        final List<BluetoothLeBroadcastMetadata> metadataList = mock(List.class);
        when(mLeAudioService.getAllBroadcastMetadata()).thenReturn(metadataList);
        verify(mCallback).onMediaControlRequest(any(Request.class));
    }

    @Test
    public void testPlaybackSpeedWrite() {
        BluetoothGattService service = initAllFeaturesGattService();
        byte playback_speed = -64;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYBACK_SPEED);
        prepareConnectedDevicesCccVal(
                characteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone());

        ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES);
        bb.put(playback_speed);

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mCallback).onPlaybackSpeedSetRequest(eq((float) Math.pow(2, playback_speed / 64.0)));

        // Fake characteristic write - this is done by player status update
        characteristic.setValue(playback_speed, BluetoothGattCharacteristic.FORMAT_SINT8, 0);

        // Second set of the same value - does not bother player only sends notification
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));
    }

    @Test
    public void testUpdateSupportedOpcodesChar() {
        BluetoothGattService service = initAllFeaturesGattService();
        Integer opcodes_supported =
                Request.SupportedOpcodes.PLAY
                        | Request.SupportedOpcodes.PAUSE
                        | Request.SupportedOpcodes.FAST_REWIND
                        | Request.SupportedOpcodes.FAST_FORWARD
                        | Request.SupportedOpcodes.STOP
                        | Request.SupportedOpcodes.MOVE_RELATIVE
                        | Request.SupportedOpcodes.PREVIOUS_SEGMENT
                        | Request.SupportedOpcodes.NEXT_SEGMENT
                        | Request.SupportedOpcodes.FIRST_SEGMENT
                        | Request.SupportedOpcodes.LAST_SEGMENT
                        | Request.SupportedOpcodes.GOTO_SEGMENT
                        | Request.SupportedOpcodes.PREVIOUS_TRACK
                        | Request.SupportedOpcodes.NEXT_TRACK
                        | Request.SupportedOpcodes.FIRST_TRACK
                        | Request.SupportedOpcodes.LAST_TRACK
                        | Request.SupportedOpcodes.GOTO_TRACK
                        | Request.SupportedOpcodes.PREVIOUS_GROUP
                        | Request.SupportedOpcodes.NEXT_GROUP
                        | Request.SupportedOpcodes.FIRST_GROUP
                        | Request.SupportedOpcodes.LAST_GROUP
                        | Request.SupportedOpcodes.GOTO_GROUP;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(
                        MediaControlGattService.UUID_MEDIA_CONTROL_POINT_OPCODES_SUPPORTED);
        prepareConnectedDevicesCccVal(
                characteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone());

        mMediaControlGattService.updateSupportedOpcodesChar(opcodes_supported, true);
        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        // Verify if there will be no new notification triggered when nothing changes
        mMediaControlGattService.updateSupportedOpcodesChar(opcodes_supported, true);
        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));

        opcodes_supported = 0;
        mMediaControlGattService.updateSupportedOpcodesChar(opcodes_supported, true);
        verify(mGattServer, times(2))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));
    }

    @Test
    public void testPlayingOrderSupportedChar() {
        BluetoothGattService service = initAllFeaturesGattService();
        int playing_order_supported =
                SupportedPlayingOrder.IN_ORDER_REPEAT | SupportedPlayingOrder.NEWEST_ONCE;
        PlayingOrder playing_order = PlayingOrder.IN_ORDER_REPEAT;
        ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES);

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER);
        prepareConnectedDevicesCccVal(
                characteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone());

        mMediaControlGattService.updatePlayingOrderSupportedChar(playing_order_supported);

        bb.put((byte) playing_order.getValue());
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());
        verify(mCallback).onPlayingOrderSetRequest(anyInt());

        // Not supported playing order should be ignored
        playing_order = PlayingOrder.SHUFFLE_ONCE;
        bb.put(0, (byte) playing_order.getValue());
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());
        verify(mCallback).onPlayingOrderSetRequest(anyInt());

        playing_order = PlayingOrder.NEWEST_ONCE;
        bb.put(0, (byte) playing_order.getValue());
        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());
        verify(mCallback, times(2)).onPlayingOrderSetRequest(anyInt());
    }

    @Test
    public void testCharacteristicReadRejectedUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mMediaControlGattService.mServerCallback.onCharacteristicReadRequest(
                mCurrentDevice, 1, 0, characteristic);

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testCharacteristic_longReadAuthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        /* Twenty three octets long title */
        String title = "01234567890123456789012";
        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_TITLE);
        characteristic.setValue(title);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        int offset = 0;
        mMediaControlGattService.mServerCallback.onCharacteristicReadRequest(
                mCurrentDevice, 1, offset, characteristic);

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(offset),
                        eq(title.getBytes()));

        offset = characteristic.getValue().length;
        mMediaControlGattService.mServerCallback.onCharacteristicReadRequest(
                mCurrentDevice, 2, offset, characteristic);

        byte[] empty = new byte[] {};
        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(2),
                        eq(BluetoothGatt.GATT_SUCCESS),
                        eq(offset),
                        eq(empty));
    }

    @Test
    public void testCharacteristic_longReadOutsideLenAuthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        /* Twenty three octets long title */
        String title = "01234567890123456789012";
        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_TITLE);
        characteristic.setValue(title);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        int offset = characteristic.getValue().length + 1;
        mMediaControlGattService.mServerCallback.onCharacteristicReadRequest(
                mCurrentDevice, 1, offset, characteristic);

        byte[] empty = new byte[] {};
        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INVALID_OFFSET),
                        eq(offset),
                        eq(empty));
    }

    @Test
    public void testCharacteristicNotifyOnAuthorization() {
        BluetoothGattService service = initAllFeaturesGattService();
        prepareConnectedDevice();

        // Leave it as unauthorized yet
        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYING_ORDER_SUPPORTED);
        prepareConnectedDevicesCccVal(
                characteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone());

        // Assume no update on some of the characteristics
        BluetoothGattCharacteristic characteristic2 =
                service.getCharacteristic(MediaControlGattService.UUID_MEDIA_STATE);
        prepareConnectedDevicesCccVal(
                characteristic2, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone());
        characteristic2.setValue((byte[]) null);

        BluetoothGattCharacteristic characteristic3 =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_CHANGED);
        prepareConnectedDevicesCccVal(
                characteristic3, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone());
        characteristic3.setValue((byte[]) null);

        // Call it once but expect no notification for the unauthorized device
        int playing_order_supported =
                SupportedPlayingOrder.IN_ORDER_REPEAT | SupportedPlayingOrder.NEWEST_ONCE;
        mMediaControlGattService.updatePlayingOrderSupportedChar(playing_order_supported);
        verify(mGattServer, times(0))
                .notifyCharacteristicChanged(eq(mCurrentDevice), any(), eq(false));

        // Expect a single notification for the just authorized device
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));
        mMediaControlGattService.onDeviceAuthorizationSet(mCurrentDevice);
        verify(mGattServer, times(0))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic2), eq(false));
        verify(mGattServer, times(0))
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic3), eq(false));
        verify(mGattServer)
                .notifyCharacteristicChanged(eq(mCurrentDevice), eq(characteristic), eq(false));
    }

    @Test
    public void testCharacteristicReadUnknownUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mMediaControlGattService.mServerCallback.onCharacteristicReadRequest(
                mCurrentDevice, 1, 0, characteristic);
        verify(mMcpService, times(0)).onDeviceUnauthorized(eq(mCurrentDevice));
        verify(mGattServer, times(0))
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testCharacteristicWriteRejectedUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();
        int track_position = 100;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES + 1).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) track_position);
        bb.put((byte) 0);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testCharacteristicWriteUnknownUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();
        int track_position = 100;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION);

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES + 1).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) track_position);
        bb.put((byte) 0);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mMediaControlGattService.mServerCallback.onCharacteristicWriteRequest(
                mCurrentDevice, 1, characteristic, false, true, 0, bb.array());
        verify(mMcpService).onDeviceUnauthorized(eq(mCurrentDevice));
    }

    @Test
    public void testDescriptorReadRejectedUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattDescriptor descriptor =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION)
                        .getDescriptor(UUID_CCCD);
        assertThat(descriptor).isNotNull();

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mMediaControlGattService.mServerCallback.onDescriptorReadRequest(
                mCurrentDevice, 1, 0, descriptor);

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testDescriptorReadUnknownUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattDescriptor descriptor =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION)
                        .getDescriptor(UUID_CCCD);
        assertThat(descriptor).isNotNull();

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        mMediaControlGattService.mServerCallback.onDescriptorReadRequest(
                mCurrentDevice, 1, 0, descriptor);
        verify(mMcpService, times(0)).onDeviceUnauthorized(eq(mCurrentDevice));
        verify(mGattServer, times(0))
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testDescriptorWriteRejectedUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattDescriptor descriptor =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION)
                        .getDescriptor(UUID_CCCD);
        assertThat(descriptor).isNotNull();

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_REJECTED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);
        bb.put((byte) 1);

        mMediaControlGattService.mServerCallback.onDescriptorWriteRequest(
                mCurrentDevice, 1, descriptor, false, true, 0, bb.array());

        verify(mGattServer)
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testDescriptorWriteUnknownUnauthorized() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattDescriptor descriptor =
                service.getCharacteristic(MediaControlGattService.UUID_TRACK_POSITION)
                        .getDescriptor(UUID_CCCD);
        assertThat(descriptor).isNotNull();

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_UNKNOWN)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);
        bb.put((byte) 1);

        mMediaControlGattService.mServerCallback.onDescriptorWriteRequest(
                mCurrentDevice, 1, descriptor, false, true, 0, bb.array());
        verify(mMcpService, times(0)).onDeviceUnauthorized(eq(mCurrentDevice));
        verify(mGattServer, times(0))
                .sendResponse(
                        eq(mCurrentDevice),
                        eq(1),
                        eq(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
                        eq(0),
                        any());
    }

    @Test
    public void testUpdatePlayerNameFromNull() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_PLAYER_NAME);
        assertThat(characteristic).isNotNull();
        byte[] nullname = null;
        characteristic.setValue(nullname);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        Map<PlayerStateField, Object> state_map = new HashMap<>();
        String player_name = "TestPlayerName";
        state_map.put(PlayerStateField.PLAYER_NAME, player_name);
        mMediaControlGattService.updatePlayerState(state_map);
    }

    @Test
    public void testUpdatePlayerStateFromNullStateChar() {
        BluetoothGattService service = initAllFeaturesGattService();

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(MediaControlGattService.UUID_MEDIA_STATE);
        assertThat(characteristic).isNotNull();
        byte[] nullBytes = null;
        characteristic.setValue(nullBytes);

        prepareConnectedDevice();
        doReturn(BluetoothDevice.ACCESS_ALLOWED)
                .when(mMcpService)
                .getDeviceAuthorization(any(BluetoothDevice.class));

        Map<PlayerStateField, Object> state_map = new HashMap<>();
        MediaState playback_state = MediaState.SEEKING;
        state_map.put(PlayerStateField.PLAYBACK_STATE, playback_state);
        mMediaControlGattService.updatePlayerState(state_map);
    }

    @Test
    public void testDumpDoesNotCrash() {
        mMediaControlGattService.dump(new StringBuilder());
        initAllFeaturesGattService();
        mMediaControlGattService.dump(new StringBuilder());
    }
}
