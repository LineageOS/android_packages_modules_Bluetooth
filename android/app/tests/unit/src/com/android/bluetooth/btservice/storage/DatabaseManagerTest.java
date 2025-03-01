/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.bluetooth.btservice.storage;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@MediumTest
@RunWith(AndroidJUnit4.class)
public final class DatabaseManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;

    private BluetoothDevice mDevice = getTestDevice(54);
    private BluetoothDevice mDevice2 = getTestDevice(55);
    private BluetoothDevice mDevice3 = getTestDevice(56);
    private BluetoothDevice mDevice4 = getTestDevice(57);
    private BluetoothDevice mDevice5 = getTestDevice(58);

    private MetadataDatabase mDatabase;
    private DatabaseManager mDatabaseManager;

    private static final String LOCAL_STORAGE = "LocalStorage";
    private static final String TEST_STRING = "Test String";
    private static final String DB_NAME = "test_db";
    private static final int A2DP_SUPPORT_OP_CODEC_TEST = 0;
    private static final int A2DP_ENABLED_OP_CODEC_TEST = 1;
    private static final int MAX_META_ID = 16;
    private static final byte[] TEST_BYTE_ARRAY = "TEST_VALUE".getBytes();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public MigrationTestHelper testHelper =
            new MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    MetadataDatabase.class.getCanonicalName(),
                    new FrameworkSQLiteOpenHelperFactory());

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);

        // Create a memory database for DatabaseManager instead of use a real database.
        mDatabase =
                Room.inMemoryDatabaseBuilder(
                                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                                MetadataDatabase.class)
                        .build();

        when(mAdapterService.getPackageManager())
                .thenReturn(
                        InstrumentationRegistry.getInstrumentation()
                                .getTargetContext()
                                .getPackageManager());

        mDatabaseManager = new DatabaseManager(mAdapterService);

        BluetoothDevice[] bondedDevices = {mDevice};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();
        doNothing().when(mAdapterService).onMetadataChanged(any(), anyInt(), any());

        restartDatabaseManagerHelper();
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.clearAdapterService(mAdapterService);
        mDatabase.deleteAll();
        mDatabaseManager.cleanup();
    }

    @Test
    public void testMetadataDefault() {
        Metadata data = new Metadata(mDevice.getAddress());
        mDatabase.insert(data);
        restartDatabaseManagerHelper();

        for (int id = 0; id < BluetoothProfile.MAX_PROFILE_ID; id++) {
            assertThat(mDatabaseManager.getProfileConnectionPolicy(mDevice, id))
                    .isEqualTo(CONNECTION_POLICY_UNKNOWN);
        }

        assertThat(mDatabaseManager.getA2dpSupportsOptionalCodecs(mDevice))
                .isEqualTo(BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);

        assertThat(mDatabaseManager.getA2dpOptionalCodecsEnabled(mDevice))
                .isEqualTo(BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);

        for (int id = 0; id < MAX_META_ID; id++) {
            assertThat(mDatabaseManager.getCustomMeta(mDevice, id)).isNull();
        }

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    @Test
    public void testSetGetProfileConnectionPolicy() {
        int badConnectionPolicy = -100;

        // Cases of device not in database
        testSetGetProfileConnectionPolicyCase(
                false, CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_UNKNOWN, true);
        testSetGetProfileConnectionPolicyCase(
                false, CONNECTION_POLICY_FORBIDDEN, CONNECTION_POLICY_FORBIDDEN, true);
        testSetGetProfileConnectionPolicyCase(
                false, CONNECTION_POLICY_ALLOWED, CONNECTION_POLICY_ALLOWED, true);
        testSetGetProfileConnectionPolicyCase(
                false, badConnectionPolicy, CONNECTION_POLICY_UNKNOWN, false);

        // Cases of device already in database
        testSetGetProfileConnectionPolicyCase(
                true, CONNECTION_POLICY_UNKNOWN, CONNECTION_POLICY_UNKNOWN, true);
        testSetGetProfileConnectionPolicyCase(
                true, CONNECTION_POLICY_FORBIDDEN, CONNECTION_POLICY_FORBIDDEN, true);
        testSetGetProfileConnectionPolicyCase(
                true, CONNECTION_POLICY_ALLOWED, CONNECTION_POLICY_ALLOWED, true);
        testSetGetProfileConnectionPolicyCase(
                true, badConnectionPolicy, CONNECTION_POLICY_UNKNOWN, false);
    }

    @Test
    public void testSetGetA2dpSupportsOptionalCodecs() {
        int badValue = -100;

        // Cases of device not in database
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                false,
                badValue,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);

        // Cases of device already in database
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_SUPPORT_OP_CODEC_TEST,
                true,
                badValue,
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
    }

    @Test
    public void testSetGetA2dpOptionalCodecsEnabled() {
        int badValue = -100;

        // Cases of device not in database
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                false,
                badValue,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);

        // Cases of device already in database
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
        testSetGetA2dpOptionalCodecsCase(
                A2DP_ENABLED_OP_CODEC_TEST,
                true,
                badValue,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
    }

    @Test
    public void testRemoveUnusedMetadata_WithSingleBondedDevice() {
        // Insert two devices to database and cache, only mDevice is
        // in the bonded list
        Metadata otherData = new Metadata(mDevice4.getAddress());
        // Add metadata for otherDevice
        otherData.setCustomizedMeta(0, TEST_BYTE_ARRAY);
        mDatabaseManager.mMetadataCache.put(mDevice4.getAddress(), otherData);
        mDatabase.insert(otherData);

        Metadata data = new Metadata(mDevice.getAddress());
        mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), data);
        mDatabase.insert(data);

        mDatabaseManager.removeUnusedMetadata();
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        // Check removed device report metadata changed to null
        verify(mAdapterService).onMetadataChanged(mDevice4, 0, null);

        List<Metadata> list = mDatabase.load();

        // Check number of metadata in the database
        assertThat(list).hasSize(1);

        // Check whether the device is in database
        Metadata checkData = list.get(0);
        assertThat(checkData.getAddress()).isEqualTo(mDevice.getAddress());

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    @Test
    public void testRemoveUnusedMetadata_WithMultiBondedDevices() {
        // Insert three devices to database and cache, otherDevice1 and otherDevice2
        // are in the bonded list

        // Add metadata for mDevice
        Metadata testData = new Metadata(mDevice.getAddress());
        testData.setCustomizedMeta(0, TEST_BYTE_ARRAY);
        mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), testData);
        mDatabase.insert(testData);

        // Add metadata for mDevice4
        Metadata otherData1 = new Metadata(mDevice4.getAddress());
        otherData1.setCustomizedMeta(0, TEST_BYTE_ARRAY);
        mDatabaseManager.mMetadataCache.put(mDevice4.getAddress(), otherData1);
        mDatabase.insert(otherData1);

        // Add metadata for mDevice5
        Metadata otherData2 = new Metadata(mDevice5.getAddress());
        otherData2.setCustomizedMeta(0, TEST_BYTE_ARRAY);
        mDatabaseManager.mMetadataCache.put(mDevice5.getAddress(), otherData2);
        mDatabase.insert(otherData2);

        // Add mDevice4 mDevice5 to bonded devices
        BluetoothDevice[] bondedDevices = {mDevice4, mDevice5};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();

        mDatabaseManager.removeUnusedMetadata();
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        // Check mDevice report metadata changed to null
        verify(mAdapterService).onMetadataChanged(mDevice, 0, null);

        // Check number of metadata in the database
        List<Metadata> list = mDatabase.load();
        // mDevice4 and mDevice5 should still in database
        assertThat(list).hasSize(2);

        // Check whether the devices are in the database
        Metadata checkData1 = list.get(0);
        assertThat(checkData1.getAddress()).isEqualTo(mDevice5.getAddress());
        Metadata checkData2 = list.get(1);
        assertThat(checkData2.getAddress()).isEqualTo(mDevice4.getAddress());

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    @Test
    public void testSetGetCustomMeta() {
        int badKey = 100;
        byte[] value = "input value".getBytes();

        // Device is not in database
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_MANUFACTURER_NAME, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_MODEL_NAME, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_SOFTWARE_VERSION, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_HARDWARE_VERSION, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_COMPANION_APP, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_MAIN_ICON, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_UNTETHERED_CASE_ICON, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_DEVICE_TYPE, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_MAIN_BATTERY, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_MAIN_CHARGING, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(
                false,
                BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                value,
                true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_SPATIAL_AUDIO, value, true);
        testSetGetCustomMetaCase(
                false, BluetoothDevice.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_LE_AUDIO, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_GMCS_CCCD, value, true);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_GTBS_CCCD, value, true);
        testSetGetCustomMetaCase(false, badKey, value, false);
        testSetGetCustomMetaCase(false, BluetoothDevice.METADATA_EXCLUSIVE_MANAGER, value, true);

        // Device is in database
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_MANUFACTURER_NAME, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_MODEL_NAME, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_SOFTWARE_VERSION, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_HARDWARE_VERSION, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_COMPANION_APP, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_MAIN_ICON, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_UNTETHERED_CASE_ICON, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_DEVICE_TYPE, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_MAIN_BATTERY, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_MAIN_CHARGING, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_SPATIAL_AUDIO, value, true);
        testSetGetCustomMetaCase(
                true, BluetoothDevice.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_LE_AUDIO, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_GMCS_CCCD, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_GTBS_CCCD, value, true);
        testSetGetCustomMetaCase(true, BluetoothDevice.METADATA_EXCLUSIVE_MANAGER, value, true);
    }

    @Test
    public void testSetGetAudioPolicyMetaData() {
        BluetoothSinkAudioPolicy value =
                new BluetoothSinkAudioPolicy.Builder()
                        .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .setActiveDevicePolicyAfterConnection(
                                BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED)
                        .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                        .build();

        // Device is not in database
        testSetGetAudioPolicyMetadataCase(false, value, true);
        // Device is in database
        testSetGetAudioPolicyMetadataCase(true, value, true);
    }

    @Test
    public void testSetConnectionHeadset() {
        mSetFlagsRule.disableFlags(Flags.FLAG_AUTO_CONNECT_ON_MULTIPLE_HFP_WHEN_NO_A2DP_DEVICE);
        // Verify pre-conditions to ensure a fresh test
        assertThat(mDatabaseManager.mMetadataCache).isEmpty();
        assertThat(mDevice).isNotNull();
        assertThat(mDevice2).isNotNull();
        assertThat(mDatabaseManager.getMostRecentlyActiveHfpDevice()).isNull();

        // Set the first device's connection
        mDatabaseManager.setConnection(mDevice, BluetoothProfile.HEADSET);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).isActiveHfpDevice)
                .isTrue();
        List<BluetoothDevice> mostRecentlyConnectedDevicesOrdered =
                mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mDatabaseManager.getMostRecentlyActiveHfpDevice()).isEqualTo(mDevice);
        assertThat(mostRecentlyConnectedDevicesOrdered).containsExactly(mDevice);

        // Setting the second device's connection
        mDatabaseManager.setConnection(mDevice2, BluetoothProfile.HEADSET);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).isActiveHfpDevice)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).isActiveHfpDevice)
                .isTrue();
        assertThat(mDatabaseManager.getMostRecentlyActiveHfpDevice()).isEqualTo(mDevice2);
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice2, mDevice)
                .inOrder();

        // Disconnect first test device's connection
        mDatabaseManager.setDisconnection(mDevice, BluetoothProfile.HEADSET);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).isActiveHfpDevice)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyActiveHfpDevice()).isNotNull();
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice2, mDevice)
                .inOrder();

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    @Test
    public void testSetConnection() {
        mSetFlagsRule.disableFlags(Flags.FLAG_AUTO_CONNECT_ON_MULTIPLE_HFP_WHEN_NO_A2DP_DEVICE);
        // Verify pre-conditions to ensure a fresh test
        assertThat(mDatabaseManager.mMetadataCache).isEmpty();
        assertThat(mDevice).isNotNull();
        assertThat(mDevice2).isNotNull();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isNull();

        // Set the first device's connection
        mDatabaseManager.setConnection(mDevice, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isTrue();
        List<BluetoothDevice> mostRecentlyConnectedDevicesOrdered =
                mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isEqualTo(mDevice);
        assertThat(mostRecentlyConnectedDevicesOrdered).containsExactly(mDevice);

        // Setting the second device's connection
        mDatabaseManager.setConnection(mDevice2, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isTrue();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isEqualTo(mDevice2);
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice2, mDevice)
                .inOrder();

        // Connect first test device again
        mDatabaseManager.setConnection(mDevice, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isTrue();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isEqualTo(mDevice);
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice, mDevice2)
                .inOrder();

        // Disconnect first test device's connection
        mDatabaseManager.setDisconnection(mDevice, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isNull();
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice, mDevice2)
                .inOrder();

        // Connect third test device (non-a2dp device)
        mDatabaseManager.setConnection(mDevice3, BluetoothProfile.HEADSET);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice3.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isNull();
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice3, mDevice, mDevice2)
                .inOrder();

        // Connect first test device again
        mDatabaseManager.setConnection(mDevice, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isTrue();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice3.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isEqualTo(mDevice);
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice, mDevice3, mDevice2)
                .inOrder();

        // Connect third test device again and ensure it doesn't reset active a2dp device
        mDatabaseManager.setConnection(mDevice3, BluetoothProfile.HEADSET);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isTrue();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice3.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isEqualTo(mDevice);
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice3, mDevice, mDevice2)
                .inOrder();

        // Disconnect second test device
        mDatabaseManager.setDisconnection(mDevice2, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isTrue();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice3.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isEqualTo(mDevice);
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice3, mDevice, mDevice2)
                .inOrder();

        // Disconnect first test device
        mDatabaseManager.setDisconnection(mDevice, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice3.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isNull();
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice3, mDevice, mDevice2)
                .inOrder();

        // Disconnect third test device
        mDatabaseManager.setDisconnection(mDevice3, BluetoothProfile.A2DP);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice2.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.mMetadataCache.get(mDevice3.getAddress()).is_active_a2dp_device)
                .isFalse();
        assertThat(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).isNull();
        mostRecentlyConnectedDevicesOrdered = mDatabaseManager.getMostRecentlyConnectedDevices();
        assertThat(mostRecentlyConnectedDevicesOrdered)
                .containsExactly(mDevice3, mDevice, mDevice2)
                .inOrder();

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    @Test
    public void testSetGetPreferredAudioProfiles() {
        Bundle preferences = new Bundle();
        preferences.putInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY, BluetoothProfile.LE_AUDIO);
        preferences.putInt(BluetoothAdapter.AUDIO_MODE_DUPLEX, BluetoothProfile.LE_AUDIO);

        // TEST 1: If input is invalid, throws the right Exception
        assertThrows(
                NullPointerException.class,
                () -> mDatabaseManager.setPreferredAudioProfiles(null, preferences));
        assertThrows(
                NullPointerException.class,
                () -> mDatabaseManager.setPreferredAudioProfiles(new ArrayList<>(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> mDatabaseManager.setPreferredAudioProfiles(new ArrayList<>(), preferences));
        assertThrows(
                IllegalArgumentException.class,
                () -> mDatabaseManager.getPreferredAudioProfiles(null));

        // TEST 2: If not stored, setter fails and getter returns an empty Bundle
        testSetGetPreferredAudioProfilesCase(
                false, preferences, Bundle.EMPTY, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);

        // TEST 3: If stored, setter succeeds and getter returns the stored preference
        testSetGetPreferredAudioProfilesCase(
                true, preferences, preferences, BluetoothStatusCodes.SUCCESS);
    }

    @Test
    public void testDatabaseMigration_100_101() throws IOException {
        // Create a database with version 100
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 100);
        Cursor cursor = db.query("SELECT * FROM metadata");

        // pbap_client_priority should not in version 100
        assertHasColumn(cursor, "pbap_client_priority", false);

        // Migrate database from 100 to 101
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 101, true, MetadataDatabase.MIGRATION_100_101);
        cursor = db.query("SELECT * FROM metadata");

        // Check whether pbap_client_priority exists in version 101
        assertHasColumn(cursor, "pbap_client_priority", true);
    }

    @Test
    public void testDatabaseMigration_101_102() throws IOException {
        // Create a database with version 101
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 101);
        Cursor cursor = db.query("SELECT * FROM metadata");

        // Insert a device to the database
        ContentValues device = contentValuesDevice_101();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Check the metadata names on version 101
        assertHasColumn(cursor, "is_unthethered_headset", true);
        assertHasColumn(cursor, "unthethered_left_icon", true);
        assertHasColumn(cursor, "unthethered_right_icon", true);
        assertHasColumn(cursor, "unthethered_case_icon", true);
        assertHasColumn(cursor, "unthethered_left_battery", true);
        assertHasColumn(cursor, "unthethered_right_battery", true);
        assertHasColumn(cursor, "unthethered_case_battery", true);
        assertHasColumn(cursor, "unthethered_left_charging", true);
        assertHasColumn(cursor, "unthethered_right_charging", true);
        assertHasColumn(cursor, "unthethered_case_charging", true);

        // Migrate database from 101 to 102
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 102, true, MetadataDatabase.MIGRATION_101_102);
        cursor = db.query("SELECT * FROM metadata");

        // metadata names should be changed on version 102
        assertHasColumn(cursor, "is_unthethered_headset", false);
        assertHasColumn(cursor, "unthethered_left_icon", false);
        assertHasColumn(cursor, "unthethered_right_icon", false);
        assertHasColumn(cursor, "unthethered_case_icon", false);
        assertHasColumn(cursor, "unthethered_left_battery", false);
        assertHasColumn(cursor, "unthethered_right_battery", false);
        assertHasColumn(cursor, "unthethered_case_battery", false);
        assertHasColumn(cursor, "unthethered_left_charging", false);
        assertHasColumn(cursor, "unthethered_right_charging", false);
        assertHasColumn(cursor, "unthethered_case_charging", false);

        assertHasColumn(cursor, "is_untethered_headset", true);
        assertHasColumn(cursor, "untethered_left_icon", true);
        assertHasColumn(cursor, "untethered_right_icon", true);
        assertHasColumn(cursor, "untethered_case_icon", true);
        assertHasColumn(cursor, "untethered_left_battery", true);
        assertHasColumn(cursor, "untethered_right_battery", true);
        assertHasColumn(cursor, "untethered_case_battery", true);
        assertHasColumn(cursor, "untethered_left_charging", true);
        assertHasColumn(cursor, "untethered_right_charging", true);
        assertHasColumn(cursor, "untethered_case_charging", true);

        while (cursor.moveToNext()) {
            // Check whether metadata data type are blob
            assertColumnBlob(cursor, "manufacturer_name");
            assertColumnBlob(cursor, "model_name");
            assertColumnBlob(cursor, "software_version");
            assertColumnBlob(cursor, "hardware_version");
            assertColumnBlob(cursor, "companion_app");
            assertColumnBlob(cursor, "main_icon");
            assertColumnBlob(cursor, "is_untethered_headset");
            assertColumnBlob(cursor, "untethered_left_icon");
            assertColumnBlob(cursor, "untethered_right_icon");
            assertColumnBlob(cursor, "untethered_case_icon");
            assertColumnBlob(cursor, "untethered_left_battery");
            assertColumnBlob(cursor, "untethered_right_battery");
            assertColumnBlob(cursor, "untethered_case_battery");
            assertColumnBlob(cursor, "untethered_left_charging");
            assertColumnBlob(cursor, "untethered_right_charging");
            assertColumnBlob(cursor, "untethered_case_charging");

            // Check whether metadata values are migrated to version 102 successfully
            assertColumnBlobData(cursor, "manufacturer_name", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "model_name", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "software_version", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "hardware_version", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "companion_app", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "main_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "is_untethered_headset", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_left_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_right_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_case_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_left_battery", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_right_battery", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_case_battery", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_left_charging", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_right_charging", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_case_charging", TEST_STRING.getBytes());
        }
    }

    @Test
    public void testDatabaseMigration_102_103() throws IOException {
        // Create a database with version 102
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 102);
        Cursor cursor = db.query("SELECT * FROM metadata");

        // Insert a device to the database
        ContentValues device = contentValuesDevice_102();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Check the metadata names on version 102
        assertHasColumn(cursor, "a2dp_priority", true);
        assertHasColumn(cursor, "a2dp_sink_priority", true);
        assertHasColumn(cursor, "hfp_priority", true);
        assertHasColumn(cursor, "hfp_client_priority", true);
        assertHasColumn(cursor, "hid_host_priority", true);
        assertHasColumn(cursor, "pan_priority", true);
        assertHasColumn(cursor, "pbap_priority", true);
        assertHasColumn(cursor, "pbap_client_priority", true);
        assertHasColumn(cursor, "map_priority", true);
        assertHasColumn(cursor, "sap_priority", true);
        assertHasColumn(cursor, "hearing_aid_priority", true);
        assertHasColumn(cursor, "map_client_priority", true);

        // Migrate database from 102 to 103
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 103, true, MetadataDatabase.MIGRATION_102_103);
        cursor = db.query("SELECT * FROM metadata");

        // metadata names should be changed on version 103
        assertHasColumn(cursor, "a2dp_priority", false);
        assertHasColumn(cursor, "a2dp_sink_priority", false);
        assertHasColumn(cursor, "hfp_priority", false);
        assertHasColumn(cursor, "hfp_client_priority", false);
        assertHasColumn(cursor, "hid_host_priority", false);
        assertHasColumn(cursor, "pan_priority", false);
        assertHasColumn(cursor, "pbap_priority", false);
        assertHasColumn(cursor, "pbap_client_priority", false);
        assertHasColumn(cursor, "map_priority", false);
        assertHasColumn(cursor, "sap_priority", false);
        assertHasColumn(cursor, "hearing_aid_priority", false);
        assertHasColumn(cursor, "map_client_priority", false);

        assertHasColumn(cursor, "a2dp_connection_policy", true);
        assertHasColumn(cursor, "a2dp_sink_connection_policy", true);
        assertHasColumn(cursor, "hfp_connection_policy", true);
        assertHasColumn(cursor, "hfp_client_connection_policy", true);
        assertHasColumn(cursor, "hid_host_connection_policy", true);
        assertHasColumn(cursor, "pan_connection_policy", true);
        assertHasColumn(cursor, "pbap_connection_policy", true);
        assertHasColumn(cursor, "pbap_client_connection_policy", true);
        assertHasColumn(cursor, "map_connection_policy", true);
        assertHasColumn(cursor, "sap_connection_policy", true);
        assertHasColumn(cursor, "hearing_aid_connection_policy", true);
        assertHasColumn(cursor, "map_client_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check PRIORITY_AUTO_CONNECT (1000) was replaced with CONNECTION_POLICY_ALLOWED (100)
            assertColumnIntData(cursor, "a2dp_connection_policy", 100);
            assertColumnIntData(cursor, "a2dp_sink_connection_policy", 100);
            assertColumnIntData(cursor, "hfp_connection_policy", 100);
            assertColumnIntData(cursor, "hfp_client_connection_policy", 100);
            assertColumnIntData(cursor, "hid_host_connection_policy", 100);
            assertColumnIntData(cursor, "pan_connection_policy", 100);
            assertColumnIntData(cursor, "pbap_connection_policy", 100);
            assertColumnIntData(cursor, "pbap_client_connection_policy", 100);
            assertColumnIntData(cursor, "map_connection_policy", 100);
            assertColumnIntData(cursor, "sap_connection_policy", 100);
            assertColumnIntData(cursor, "hearing_aid_connection_policy", 100);
            assertColumnIntData(cursor, "map_client_connection_policy", 100);

            // Check whether metadata data type are blob
            assertColumnBlob(cursor, "manufacturer_name");
            assertColumnBlob(cursor, "model_name");
            assertColumnBlob(cursor, "software_version");
            assertColumnBlob(cursor, "hardware_version");
            assertColumnBlob(cursor, "companion_app");
            assertColumnBlob(cursor, "main_icon");
            assertColumnBlob(cursor, "is_untethered_headset");
            assertColumnBlob(cursor, "untethered_left_icon");
            assertColumnBlob(cursor, "untethered_right_icon");
            assertColumnBlob(cursor, "untethered_case_icon");
            assertColumnBlob(cursor, "untethered_left_battery");
            assertColumnBlob(cursor, "untethered_right_battery");
            assertColumnBlob(cursor, "untethered_case_battery");
            assertColumnBlob(cursor, "untethered_left_charging");
            assertColumnBlob(cursor, "untethered_right_charging");
            assertColumnBlob(cursor, "untethered_case_charging");

            // Check whether metadata values are migrated to version 103 successfully
            assertColumnBlobData(cursor, "manufacturer_name", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "model_name", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "software_version", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "hardware_version", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "companion_app", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "main_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "is_untethered_headset", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_left_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_right_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_case_icon", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_left_battery", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_right_battery", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_case_battery", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_left_charging", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_right_charging", TEST_STRING.getBytes());
            assertColumnBlobData(cursor, "untethered_case_charging", TEST_STRING.getBytes());
        }
    }

    @Test
    public void testDatabaseMigration_103_104() throws IOException {
        // Create a database with version 103
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 103);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_103();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 103 to 104
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 104, true, MetadataDatabase.MIGRATION_103_104);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "last_active_time", true);
        assertHasColumn(cursor, "is_active_a2dp_device", true);

        while (cursor.moveToNext()) {
            // Check the two new columns were added with their default values
            assertColumnIntData(cursor, "last_active_time", -1);
            assertColumnIntData(cursor, "is_active_a2dp_device", 0);
        }
    }

    @Test
    public void testDatabaseMigration_104_105() throws IOException {
        // Create a database with version 104
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 104);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_104();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 104 to 105
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 105, true, MetadataDatabase.MIGRATION_104_105);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "device_type", true);
        assertHasColumn(cursor, "main_battery", true);
        assertHasColumn(cursor, "main_charging", true);
        assertHasColumn(cursor, "main_low_battery_threshold", true);
        assertHasColumn(cursor, "untethered_right_low_battery_threshold", true);
        assertHasColumn(cursor, "untethered_left_low_battery_threshold", true);
        assertHasColumn(cursor, "untethered_case_low_battery_threshold", true);

        while (cursor.moveToNext()) {
            // Check the old column have the original value
            assertColumnStringData(cursor, "address", mDevice.getAddress());

            // Check the new columns were added with their default values
            assertColumnBlobData(cursor, "device_type", null);
            assertColumnBlobData(cursor, "main_battery", null);
            assertColumnBlobData(cursor, "main_charging", null);
            assertColumnBlobData(cursor, "main_low_battery_threshold", null);
            assertColumnBlobData(cursor, "untethered_right_low_battery_threshold", null);
            assertColumnBlobData(cursor, "untethered_left_low_battery_threshold", null);
            assertColumnBlobData(cursor, "untethered_case_low_battery_threshold", null);
        }
    }

    @Test
    public void testDatabaseMigration_105_106() throws IOException {
        // Create a database with version 105
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 105);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_105();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 105 to 106
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 106, true, MetadataDatabase.MIGRATION_105_106);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "le_audio_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "le_audio_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_106_107() throws IOException {
        // Create a database with version 106
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 106);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_106();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 106 to 107
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 107, true, MetadataDatabase.MIGRATION_106_107);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "volume_control_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "volume_control_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_107_108() throws IOException {
        // Create a database with version 107
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 107);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_107();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 107 to 108
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 108, true, MetadataDatabase.MIGRATION_107_108);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "csip_set_coordinator_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "csip_set_coordinator_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_108_109() throws IOException {
        // Create a database with version 108
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 108);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_108();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 108 to 109
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 109, true, MetadataDatabase.MIGRATION_108_109);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "le_call_control_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "le_call_control_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_109_110() throws IOException {
        // Create a database with version 109
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 109);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_109();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 109 to 110
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 110, true, MetadataDatabase.MIGRATION_109_110);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "hap_client_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "hap_client_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_110_111() throws IOException {
        // Create a database with version 110
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 110);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_110();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 109 to 110
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 111, true, MetadataDatabase.MIGRATION_110_111);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "bass_client_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "bass_client_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_111_112() throws IOException {
        // Create a database with version 111
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 111);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_111();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 111 to 112
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 112, true, MetadataDatabase.MIGRATION_111_112);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "battery_connection_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "battery_connection_policy", 100);
        }
    }

    @Test
    public void testDatabaseMigration_112_113() throws IOException {
        // Create a database with version 112
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 112);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_112();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 112 to 113
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 113, true, MetadataDatabase.MIGRATION_112_113);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "spatial_audio", true);
        assertHasColumn(cursor, "fastpair_customized", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnBlobData(cursor, "spatial_audio", null);
            assertColumnBlobData(cursor, "fastpair_customized", null);
        }
    }

    @Test
    public void testDatabaseMigration_113_114() throws IOException {
        // Create a database with version 113
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 113);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_113();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 113 to 114
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 114, true, MetadataDatabase.MIGRATION_113_114);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "le_audio", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnBlobData(cursor, "le_audio", null);
        }
    }

    @Test
    public void testDatabaseMigration_114_115() throws IOException {
        // Create a database with version 114
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 114);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_114();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 114 to 115
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 115, true, MetadataDatabase.MIGRATION_114_115);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "call_establish_audio_policy", true);
        assertHasColumn(cursor, "connecting_time_audio_policy", true);
        assertHasColumn(cursor, "in_band_ringtone_audio_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "call_establish_audio_policy", 0);
            assertColumnIntData(cursor, "connecting_time_audio_policy", 0);
            assertColumnIntData(cursor, "in_band_ringtone_audio_policy", 0);
        }
    }

    @Test
    public void testDatabaseMigration_115_116() throws IOException {
        // Create a database with version 115
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 115);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_115();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 115 to 116
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 116, true, MetadataDatabase.MIGRATION_115_116);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "preferred_output_only_profile", true);
        assertHasColumn(cursor, "preferred_duplex_profile", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "preferred_output_only_profile", 0);
            assertColumnIntData(cursor, "preferred_duplex_profile", 0);
        }
    }

    @Test
    public void testDatabaseMigration_116_117() throws IOException {
        // Create a database with version 116
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 116);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_116();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 116 to 117
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 117, true, MetadataDatabase.MIGRATION_116_117);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "gmcs_cccd", true);
        assertHasColumn(cursor, "gtbs_cccd", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnBlobData(cursor, "gmcs_cccd", null);
            assertColumnBlobData(cursor, "gtbs_cccd", null);
        }
    }

    @Test
    public void testDatabaseMigration_117_118() throws IOException {
        // Create a database with version 117
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 117);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_117();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 117 to 118
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 118, true, MetadataDatabase.MIGRATION_117_118);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "isActiveHfpDevice", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "isActiveHfpDevice", 0);
        }
    }

    @Test
    public void testDatabaseMigration_118_119() throws IOException {
        // Create a database with version 118
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 118);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_118();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 118 to 119
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 119, true, MetadataDatabase.MIGRATION_118_119);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "exclusive_manager", true);

        while (cursor.moveToNext()) {
            // Check the new column was added with default value
            assertColumnBlobData(cursor, "exclusive_manager", null);
        }
    }

    @Test
    public void testDatabaseMigration_119_120() throws IOException {
        // Create a database with version 119
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 119);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_119();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 119 to 120
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 120, true, MetadataDatabase.MIGRATION_119_120);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "active_audio_device_policy", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "active_audio_device_policy", 0);
        }
    }

    @Test
    public void testDatabaseMigration_120_121() throws IOException {
        // Create a database with version 120
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 120);

        // Insert a device to the database
        ContentValues device = contentValuesDevice_120();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 120 to 121
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 121, true, MetadataDatabase.MIGRATION_120_121);
        Cursor cursor = db.query("SELECT * FROM metadata");

        assertHasColumn(cursor, "is_preferred_microphone_for_calls", true);

        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "is_preferred_microphone_for_calls", 1);
        }
    }

    @Test
    public void testDatabaseMigration_121_122() throws IOException {
        // Create a database with version 121
        SupportSQLiteDatabase db = testHelper.createDatabase(DB_NAME, 121);

        // insert a device to the database
        ContentValues device = contentValuesDevice_121();
        assertThat(db.insert("metadata", SQLiteDatabase.CONFLICT_IGNORE, device)).isNotEqualTo(-1);

        // Migrate database from 121 to 122
        db.close();
        db =
                testHelper.runMigrationsAndValidate(
                        DB_NAME, 122, true, MetadataDatabase.MIGRATION_121_122);
        Cursor cursor = db.query("SELECT * FROM metadata");
        assertHasColumn(cursor, "key_missing_count", true);
        while (cursor.moveToNext()) {
            // Check the new columns was added with default value
            assertColumnIntData(cursor, "key_missing_count", 0);
        }
    }

    private ContentValues createContentValuesDeviceCommon() {
        ContentValues device = new ContentValues();
        device.put("address", mDevice.getAddress());
        device.put("migrated", false);
        device.put("a2dpSupportsOptionalCodecs", -1);
        device.put("a2dpOptionalCodecsEnabled", -1);
        device.put("manufacturer_name", TEST_STRING);
        device.put("model_name", TEST_STRING);
        device.put("software_version", TEST_STRING);
        device.put("hardware_version", TEST_STRING);
        device.put("companion_app", TEST_STRING);
        device.put("main_icon", TEST_STRING);
        return device;
    }

    private ContentValues createContentValuesDeviceStarting102() {
        ContentValues device = createContentValuesDeviceCommon();
        device.put("is_untethered_headset", TEST_STRING);
        device.put("untethered_left_icon", TEST_STRING);
        device.put("untethered_right_icon", TEST_STRING);
        device.put("untethered_case_icon", TEST_STRING);
        device.put("untethered_left_battery", TEST_STRING);
        device.put("untethered_right_battery", TEST_STRING);
        device.put("untethered_case_battery", TEST_STRING);
        device.put("untethered_left_charging", TEST_STRING);
        device.put("untethered_right_charging", TEST_STRING);
        device.put("untethered_case_charging", TEST_STRING);
        return device;
    }

    private ContentValues contentValuesDevice_101() {
        ContentValues device = createContentValuesDeviceCommon();
        // The following are available ONLY in 101
        device.put("a2dp_priority", -1);
        device.put("a2dp_sink_priority", -1);
        device.put("hfp_priority", -1);
        device.put("hfp_client_priority", -1);
        device.put("hid_host_priority", -1);
        device.put("pan_priority", -1);
        device.put("pbap_priority", -1);
        device.put("pbap_client_priority", -1);
        device.put("map_priority", -1);
        device.put("sap_priority", -1);
        device.put("hearing_aid_priority", -1);
        device.put("map_client_priority", -1);
        device.put("is_unthethered_headset", TEST_STRING);
        device.put("unthethered_left_icon", TEST_STRING);
        device.put("unthethered_right_icon", TEST_STRING);
        device.put("unthethered_case_icon", TEST_STRING);
        device.put("unthethered_left_battery", TEST_STRING);
        device.put("unthethered_right_battery", TEST_STRING);
        device.put("unthethered_case_battery", TEST_STRING);
        device.put("unthethered_left_charging", TEST_STRING);
        device.put("unthethered_right_charging", TEST_STRING);
        device.put("unthethered_case_charging", TEST_STRING);
        return device;
    }

    private ContentValues contentValuesDevice_102() {
        ContentValues device = createContentValuesDeviceStarting102();
        // The following are available ONLY in 102
        device.put("a2dp_priority", 1000);
        device.put("a2dp_sink_priority", 1000);
        device.put("hfp_priority", 1000);
        device.put("hfp_client_priority", 1000);
        device.put("hid_host_priority", 1000);
        device.put("pan_priority", 1000);
        device.put("pbap_priority", 1000);
        device.put("pbap_client_priority", 1000);
        device.put("map_priority", 1000);
        device.put("sap_priority", 1000);
        device.put("hearing_aid_priority", 1000);
        device.put("map_client_priority", 1000);
        return device;
    }

    private ContentValues contentValuesDevice_103() {
        ContentValues device = createContentValuesDeviceStarting102();
        device.put("a2dp_connection_policy", 100);
        device.put("a2dp_sink_connection_policy", 100);
        device.put("hfp_connection_policy", 100);
        device.put("hfp_client_connection_policy", 100);
        device.put("hid_host_connection_policy", 100);
        device.put("pan_connection_policy", 100);
        device.put("pbap_connection_policy", 100);
        device.put("pbap_client_connection_policy", 100);
        device.put("map_connection_policy", 100);
        device.put("sap_connection_policy", 100);
        device.put("hearing_aid_connection_policy", 100);
        device.put("map_client_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_104() {
        ContentValues device = contentValuesDevice_103();
        device.put("last_active_time", -1);
        device.put("is_active_a2dp_device", 0);
        return device;
    }

    private ContentValues contentValuesDevice_105() {
        return contentValuesDevice_104();
    }

    private ContentValues contentValuesDevice_106() {
        ContentValues device = contentValuesDevice_105();
        device.put("le_audio_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_107() {
        ContentValues device = contentValuesDevice_106();
        device.put("volume_control_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_108() {
        ContentValues device = contentValuesDevice_107();
        device.put("csip_set_coordinator_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_109() {
        ContentValues device = contentValuesDevice_108();
        device.put("le_call_control_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_110() {
        return contentValuesDevice_109();
    }

    private ContentValues contentValuesDevice_111() {
        ContentValues device = contentValuesDevice_110();
        device.put("bass_client_connection_policy", 100);
        device.put("hap_client_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_112() {
        ContentValues device = contentValuesDevice_111();
        device.put("battery_connection_policy", 100);
        return device;
    }

    private ContentValues contentValuesDevice_113() {
        return contentValuesDevice_112();
    }

    private ContentValues contentValuesDevice_114() {
        return contentValuesDevice_113();
    }

    private ContentValues contentValuesDevice_115() {
        return contentValuesDevice_114();
    }

    private ContentValues contentValuesDevice_116() {
        ContentValues device = contentValuesDevice_115();
        device.put("preferred_output_only_profile", 0);
        device.put("preferred_duplex_profile", 0);
        return device;
    }

    private ContentValues contentValuesDevice_117() {
        return contentValuesDevice_116();
    }

    private ContentValues contentValuesDevice_118() {
        ContentValues device = contentValuesDevice_117();
        device.put("isActiveHfpDevice", 0);
        return device;
    }

    private ContentValues contentValuesDevice_119() {
        return contentValuesDevice_118();
    }

    private ContentValues contentValuesDevice_120() {
        ContentValues device = contentValuesDevice_119();
        device.put("active_audio_device_policy", 0);
        return device;
    }

    private ContentValues contentValuesDevice_121() {
        ContentValues device = contentValuesDevice_120();
        device.put("is_preferred_microphone_for_calls", 1);
        return device;
    }

    /** Helper function to check whether the database has the expected column */
    void assertHasColumn(Cursor cursor, String columnName, boolean hasColumn) {
        if (hasColumn) {
            assertThat(cursor.getColumnIndex(columnName)).isNotEqualTo(-1);
        } else {
            assertThat(cursor.getColumnIndex(columnName)).isEqualTo(-1);
        }
    }

    /** Helper function to check whether the database has the expected value */
    void assertColumnIntData(Cursor cursor, String columnName, int value) {
        assertThat(cursor.getInt(cursor.getColumnIndex(columnName))).isEqualTo(value);
    }

    /** Helper function to check whether the column data type is BLOB */
    void assertColumnBlob(Cursor cursor, String columnName) {
        assertThat(cursor.getType(cursor.getColumnIndex(columnName)))
                .isEqualTo(Cursor.FIELD_TYPE_BLOB);
    }

    /** Helper function to check the BLOB data in a column is expected */
    void assertColumnBlobData(Cursor cursor, String columnName, byte[] data) {
        assertThat(cursor.getBlob(cursor.getColumnIndex(columnName))).isEqualTo(data);
    }

    /** Helper function to check whether the database has the expected value */
    void assertColumnStringData(Cursor cursor, String columnName, String value) {
        assertThat(cursor.getString(cursor.getColumnIndex(columnName))).isEqualTo(value);
    }

    void restartDatabaseManagerHelper() {
        Metadata data = new Metadata(LOCAL_STORAGE);
        data.migrated = true;
        mDatabase.insert(data);

        mDatabaseManager.cleanup();
        mDatabaseManager.start(mDatabase);
        // Wait for handler thread finish its task.
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        // Remove local storage
        mDatabaseManager.mMetadataCache.remove(LOCAL_STORAGE);
        mDatabaseManager.deleteDatabase(data);
        // Wait for handler thread finish its task.
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    void testSetGetProfileConnectionPolicyCase(
            boolean stored,
            int connectionPolicy,
            int expectedConnectionPolicy,
            boolean expectedSetResult) {
        if (stored) {
            Metadata data = new Metadata(mDevice.getAddress());
            mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), data);
            mDatabase.insert(data);
        }
        assertThat(
                        mDatabaseManager.setProfileConnectionPolicy(
                                mDevice, BluetoothProfile.HEADSET, connectionPolicy))
                .isEqualTo(expectedSetResult);
        assertThat(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HEADSET))
                .isEqualTo(expectedConnectionPolicy);
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        List<Metadata> list = mDatabase.load();

        // Check number of metadata in the database
        if (!stored) {
            if (connectionPolicy != CONNECTION_POLICY_FORBIDDEN
                    && connectionPolicy != CONNECTION_POLICY_ALLOWED) {
                // Database won't be updated
                assertThat(list).isEmpty();
                return;
            }
        }
        assertThat(list).hasSize(1);

        // Check whether the device is in database
        restartDatabaseManagerHelper();
        assertThat(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.HEADSET))
                .isEqualTo(expectedConnectionPolicy);

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    void testSetGetA2dpOptionalCodecsCase(int test, boolean stored, int value, int expectedValue) {
        if (stored) {
            Metadata data = new Metadata(mDevice.getAddress());
            mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), data);
            mDatabase.insert(data);
        }
        if (test == A2DP_SUPPORT_OP_CODEC_TEST) {
            mDatabaseManager.setA2dpSupportsOptionalCodecs(mDevice, value);
            assertThat(mDatabaseManager.getA2dpSupportsOptionalCodecs(mDevice))
                    .isEqualTo(expectedValue);
        } else {
            mDatabaseManager.setA2dpOptionalCodecsEnabled(mDevice, value);
            assertThat(mDatabaseManager.getA2dpOptionalCodecsEnabled(mDevice))
                    .isEqualTo(expectedValue);
        }
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        List<Metadata> list = mDatabase.load();

        // Check number of metadata in the database
        if (!stored) {
            // Database won't be updated
            assertThat(list).isEmpty();
            return;
        }
        assertThat(list).hasSize(1);

        // Check whether the device is in database
        restartDatabaseManagerHelper();
        if (test == A2DP_SUPPORT_OP_CODEC_TEST) {
            assertThat(mDatabaseManager.getA2dpSupportsOptionalCodecs(mDevice))
                    .isEqualTo(expectedValue);
        } else {
            assertThat(mDatabaseManager.getA2dpOptionalCodecsEnabled(mDevice))
                    .isEqualTo(expectedValue);
        }

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    void testSetGetCustomMetaCase(boolean stored, int key, byte[] value, boolean expectedResult) {
        byte[] testValue = "test value".getBytes();
        int verifyTime = 1;
        if (stored) {
            Metadata data = new Metadata(mDevice.getAddress());
            mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), data);
            mDatabase.insert(data);
            assertThat(mDatabaseManager.setCustomMeta(mDevice, key, testValue))
                    .isEqualTo(expectedResult);
            verify(mAdapterService).onMetadataChanged(mDevice, key, testValue);
            verifyTime++;
        }
        assertThat(mDatabaseManager.setCustomMeta(mDevice, key, value)).isEqualTo(expectedResult);
        if (expectedResult) {
            // Check for callback and get value
            verify(mAdapterService, times(verifyTime)).onMetadataChanged(mDevice, key, value);
            assertThat(mDatabaseManager.getCustomMeta(mDevice, key)).isEqualTo(value);
        } else {
            assertThat(mDatabaseManager.getCustomMeta(mDevice, key)).isNull();
            return;
        }
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        // Check whether the value is saved in database
        restartDatabaseManagerHelper();
        assertThat(mDatabaseManager.getCustomMeta(mDevice, key)).isEqualTo(value);

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    void testSetGetAudioPolicyMetadataCase(
            boolean stored, BluetoothSinkAudioPolicy policy, boolean expectedResult) {
        BluetoothSinkAudioPolicy testPolicy = new BluetoothSinkAudioPolicy.Builder().build();
        if (stored) {
            Metadata data = new Metadata(mDevice.getAddress());
            mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), data);
            mDatabase.insert(data);
            assertThat(mDatabaseManager.setAudioPolicyMetadata(mDevice, testPolicy))
                    .isEqualTo(expectedResult);
        }
        assertThat(mDatabaseManager.setAudioPolicyMetadata(mDevice, policy))
                .isEqualTo(expectedResult);
        if (expectedResult) {
            // Check for callback and get value
            assertThat(mDatabaseManager.getAudioPolicyMetadata(mDevice)).isEqualTo(policy);
        } else {
            assertThat(mDatabaseManager.getAudioPolicyMetadata(mDevice)).isNull();
            return;
        }
        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        // Check whether the value is saved in database
        restartDatabaseManagerHelper();
        assertThat(mDatabaseManager.getAudioPolicyMetadata(mDevice)).isEqualTo(policy);

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    void testSetGetPreferredAudioProfilesCase(
            boolean stored,
            Bundle preferencesToSet,
            Bundle expectedPreferences,
            int expectedSetResult) {
        if (stored) {
            Metadata data = new Metadata(mDevice.getAddress());
            Metadata data2 = new Metadata(mDevice2.getAddress());
            mDatabaseManager.mMetadataCache.put(mDevice.getAddress(), data);
            mDatabaseManager.mMetadataCache.put(mDevice2.getAddress(), data2);
            mDatabase.insert(data);
            mDatabase.insert(data2);
        }
        List<BluetoothDevice> groupDevices = new ArrayList<>();
        groupDevices.add(mDevice);
        groupDevices.add(mDevice2);

        assertThat(mDatabaseManager.setPreferredAudioProfiles(groupDevices, preferencesToSet))
                .isEqualTo(expectedSetResult);
        Bundle testDevicePreferences = mDatabaseManager.getPreferredAudioProfiles(mDevice);
        Bundle testDevice2Preferences = mDatabaseManager.getPreferredAudioProfiles(mDevice2);
        assertThat(testDevicePreferences).isNotNull();
        assertThat(testDevice2Preferences).isNotNull();

        assertThat(testDevicePreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY))
                .isEqualTo(expectedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY));
        assertThat(testDevicePreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX))
                .isEqualTo(expectedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX));
        assertThat(testDevice2Preferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY))
                .isEqualTo(0);
        assertThat(testDevice2Preferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX)).isEqualTo(0);

        // Wait for database update
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());

        List<Metadata> list = mDatabase.load();

        // Check number of metadata in the database
        if (!stored) {
            assertThat(list).isEmpty();
            return;
        }
        assertThat(list).hasSize(2);

        // Check whether the device is in database
        restartDatabaseManagerHelper();
        testDevicePreferences = mDatabaseManager.getPreferredAudioProfiles(mDevice);
        testDevice2Preferences = mDatabaseManager.getPreferredAudioProfiles(mDevice2);
        assertThat(testDevicePreferences).isNotNull();
        assertThat(testDevice2Preferences).isNotNull();

        assertThat(testDevicePreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY))
                .isEqualTo(expectedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY));
        assertThat(testDevicePreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX))
                .isEqualTo(expectedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX));
        assertThat(testDevice2Preferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY))
                .isEqualTo(0);
        assertThat(testDevice2Preferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX)).isEqualTo(0);

        mDatabaseManager.factoryReset();
        mDatabaseManager.mMetadataCache.clear();
        // Wait for clear database
        TestUtils.waitForLooperToFinishScheduledTask(mDatabaseManager.getHandlerLooper());
    }

    @Test
    public void setCustomMetadata_reentrantCallback_noDeadLock() throws Exception {
        final int key = 3;
        final byte[] newValue = new byte[2];

        CompletableFuture<byte[]> future = new CompletableFuture();

        Answer answer =
                invocation -> {
                    // Concurrent database call during callback execution
                    byte[] value =
                            CompletableFuture.supplyAsync(
                                            () -> mDatabaseManager.getCustomMeta(mDevice, key))
                                    .completeOnTimeout(null, 1, TimeUnit.SECONDS)
                                    .get();

                    future.complete(value);
                    return null;
                };

        doAnswer(answer).when(mAdapterService).onMetadataChanged(any(), anyInt(), any());

        mDatabaseManager.setCustomMeta(mDevice, key, newValue);

        assertThat(future.get()).isEqualTo(newValue);
    }
}
