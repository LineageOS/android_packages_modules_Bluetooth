/*
 * Copyright 2018 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;

import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__BOND;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_A2DP;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_A2DP_SINK;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_BATTERY;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_CSIP_SET_COORDINATOR;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HAP_CLIENT;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HEADSET;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HEADSET_CLIENT;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HEARING_AID;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HID_HOST;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_LE_AUDIO;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_LE_AUDIO_BROADCAST_ASSISTANT;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_MAP_CLIENT;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_PAN;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_PBAP_CLIENT;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_VOLUME_CONTROL;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__STATE_BONDED;
import static com.android.bluetooth.BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__STATE_NONE;
import static com.android.bluetooth.BtRestrictedStatsLog.RESTRICTED_BLUETOOTH_DEVICE_NAME_REPORTED;

import android.app.AlarmManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothPbap;
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothSap;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.RequiresApi;

import com.android.bluetooth.BluetoothMetricsProto.BluetoothLog;
import com.android.bluetooth.BluetoothMetricsProto.BluetoothRemoteDeviceInformation;
import com.android.bluetooth.BluetoothMetricsProto.ProfileConnectionStats;
import com.android.bluetooth.BluetoothMetricsProto.ProfileId;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.BtRestrictedStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.bass_client.BassConstants;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Class of Bluetooth Metrics */
public class MetricsLogger {
    private static final String TAG =
            Utils.TAG_PREFIX_BLUETOOTH + MetricsLogger.class.getSimpleName();

    private static final String BLOOMFILTER_PATH = "/data/misc/bluetooth";
    private static final String BLOOMFILTER_FILE = "/devices_for_metrics_v3";
    private static final String MEDICAL_DEVICE_BLOOMFILTER_FILE = "/medical_devices_for_metrics_v1";
    public static final String BLOOMFILTER_FULL_PATH = BLOOMFILTER_PATH + BLOOMFILTER_FILE;
    public static final String MEDICAL_DEVICE_BLOOMFILTER_FULL_PATH =
            BLOOMFILTER_PATH + MEDICAL_DEVICE_BLOOMFILTER_FILE;

    // 6 hours timeout for counter metrics
    private static final long BLUETOOTH_COUNTER_METRICS_ACTION_DURATION_MILLIS = 6L * 3600L * 1000L;
    private static final int MAX_WORDS_ALLOWED_IN_DEVICE_NAME = 7;

    private static final HashMap<ProfileId, Integer> sProfileConnectionCounts = new HashMap<>();

    HashMap<Integer, Long> mCounters = new HashMap<>();
    private static volatile MetricsLogger sInstance = null;
    private AdapterService mAdapterService = null;
    private RemoteDevices mRemoteDevices = null;
    private AlarmManager mAlarmManager = null;
    private boolean mInitialized = false;
    private static final Object sLock = new Object();
    private BloomFilter<byte[]> mBloomFilter = null;
    protected boolean mBloomFilterInitialized = false;

    private BloomFilter<byte[]> mMedicalDeviceBloomFilter = null;

    protected boolean mMedicalDeviceBloomFilterInitialized = false;

    private final AlarmManager.OnAlarmListener mOnAlarmListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    drainBufferedCounters();
                    scheduleDrains();
                }
            };

    public static MetricsLogger getInstance() {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new MetricsLogger();
                }
            }
        }
        return sInstance;
    }

    /**
     * Allow unit tests to substitute MetricsLogger with a test instance
     *
     * @param instance a test instance of the MetricsLogger
     */
    @VisibleForTesting
    public static void setInstanceForTesting(MetricsLogger instance) {
        Utils.enforceInstrumentationTestMode();
        synchronized (sLock) {
            Log.d(TAG, "setInstanceForTesting(), set to " + instance);
            sInstance = instance;
        }
    }

    @VisibleForTesting
    boolean isInitialized() {
        return mInitialized;
    }

    public boolean initBloomFilter(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                Log.w(TAG, "MetricsLogger is creating a new Bloomfilter file");
                DeviceBloomfilterGenerator.generateDefaultBloomfilter(path);
            }

            FileInputStream in = new FileInputStream(new File(path));
            mBloomFilter = BloomFilter.readFrom(in, Funnels.byteArrayFunnel());
            mBloomFilterInitialized = true;
        } catch (IOException e1) {
            Log.w(TAG, "MetricsLogger can't read the BloomFilter file.");
            byte[] bloomfilterData =
                    DeviceBloomfilterGenerator.hexStringToByteArray(
                            DeviceBloomfilterGenerator.BLOOM_FILTER_DEFAULT);
            try {
                mBloomFilter =
                        BloomFilter.readFrom(
                                new ByteArrayInputStream(bloomfilterData),
                                Funnels.byteArrayFunnel());
                mBloomFilterInitialized = true;
                Log.i(TAG, "The default bloomfilter is used");
                return true;
            } catch (IOException e2) {
                Log.w(TAG, "The default bloomfilter can't be used.");
            }
            return false;
        }
        return true;
    }

    /** Initialize medical device bloom filter */
    public boolean initMedicalDeviceBloomFilter(String path) {
        try {
            File medicalDeviceFile = new File(path);
            if (!medicalDeviceFile.exists()) {
                Log.w(TAG, "MetricsLogger is creating a new medical device Bloomfilter file");
                MedicalDeviceBloomfilterGenerator.generateDefaultBloomfilter(path);
            }

            FileInputStream inputStream = new FileInputStream(new File(path));
            mMedicalDeviceBloomFilter =
                    BloomFilter.readFrom(inputStream, Funnels.byteArrayFunnel());
            mMedicalDeviceBloomFilterInitialized = true;
        } catch (IOException e1) {
            Log.w(TAG, "MetricsLogger can't read the medical device BloomFilter file.");
            byte[] bloomfilterData =
                    MedicalDeviceBloomfilterGenerator.hexStringToByteArray(
                            MedicalDeviceBloomfilterGenerator.BLOOM_FILTER_DEFAULT);
            try {
                mMedicalDeviceBloomFilter =
                        BloomFilter.readFrom(
                                new ByteArrayInputStream(bloomfilterData),
                                Funnels.byteArrayFunnel());
                mMedicalDeviceBloomFilterInitialized = true;
                Log.i(TAG, "The medical device bloomfilter is used");
                return true;
            } catch (IOException e2) {
                Log.w(TAG, "The medical device bloomfilter can't be used.");
            }
            return false;
        }
        return true;
    }

    protected void setBloomfilter(BloomFilter bloomfilter) {
        mBloomFilter = bloomfilter;
    }

    protected void setMedicalDeviceBloomfilter(BloomFilter bloomfilter) {
        mMedicalDeviceBloomFilter = bloomfilter;
    }

    void init(AdapterService adapterService, RemoteDevices remoteDevices) {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mAdapterService = adapterService;
        mRemoteDevices = remoteDevices;
        scheduleDrains();
        if (!initBloomFilter(BLOOMFILTER_FULL_PATH)) {
            Log.w(TAG, "MetricsLogger can't initialize the bloomfilter");
            // The class is for multiple metrics tasks.
            // We still want to use this class even if the bloomfilter isn't initialized
            // so still return true here.
        }
        if (!initMedicalDeviceBloomFilter(MEDICAL_DEVICE_BLOOMFILTER_FULL_PATH)) {
            Log.w(TAG, "MetricsLogger can't initialize the medical device bloomfilter");
            // The class is for multiple metrics tasks.
            // We still want to use this class even if the bloomfilter isn't initialized
            // so still return true here.
        }
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothPbap.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothSap.ACTION_CONNECTION_STATE_CHANGED);
        mAdapterService.registerReceiver(mReceiver, filter);
    }

    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action == null) {
                        Log.w(TAG, "Received intent with null action");
                        return;
                    }
                    switch (action) {
                        case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.A2DP, intent);
                            break;
                        case BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.A2DP_SINK, intent);
                            break;
                        case BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.AVRCP_CONTROLLER, intent);
                            break;
                        case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.HEADSET, intent);
                            break;
                        case BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.HEADSET_CLIENT, intent);
                            break;
                        case BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.HEARING_AID, intent);
                            break;
                        case BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.HID_DEVICE, intent);
                            break;
                        case BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.HID_HOST, intent);
                            break;
                        case BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.LE_AUDIO, intent);
                            break;
                        case BluetoothMap.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.MAP, intent);
                            break;
                        case BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.MAP_CLIENT, intent);
                            break;
                        case BluetoothPan.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.PAN, intent);
                            break;
                        case BluetoothPbap.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.PBAP, intent);
                            break;
                        case BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.PBAP_CLIENT, intent);
                            break;
                        case BluetoothSap.ACTION_CONNECTION_STATE_CHANGED:
                            logConnectionStateChanges(BluetoothProfile.SAP, intent);
                            break;
                        default:
                            Log.w(TAG, "Received unknown intent " + intent);
                            break;
                    }
                }
            };

    private void logConnectionStateChanges(int profile, Intent connIntent) {
        BluetoothDevice device = connIntent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        int state = connIntent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        int metricId = mAdapterService.getMetricId(device);
        if (state == STATE_CONNECTING) {
            String deviceName = mRemoteDevices.getName(device);
            BluetoothStatsLog.write(
                    BluetoothStatsLog.BLUETOOTH_DEVICE_NAME_REPORTED, metricId, deviceName);
            logAllowlistedDeviceNameHash(metricId, deviceName);
        }
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_CONNECTION_STATE_CHANGED,
                state,
                0 /* deprecated */,
                profile,
                mAdapterService.obfuscateAddress(device),
                metricId,
                0,
                -1);
    }

    public boolean cacheCount(int key, long count) {
        if (!mInitialized) {
            Log.w(TAG, "MetricsLogger isn't initialized");
            return false;
        }
        if (count <= 0) {
            Log.w(TAG, "count is not larger than 0. count: " + count + " key: " + key);
            return false;
        }
        long total = 0;

        synchronized (sLock) {
            if (mCounters.containsKey(key)) {
                total = mCounters.get(key);
            }
            if (Long.MAX_VALUE - total < count) {
                Log.w(TAG, "count overflows. count: " + count + " current total: " + total);
                mCounters.put(key, Long.MAX_VALUE);
                return false;
            }
            mCounters.put(key, total + count);
        }
        return true;
    }

    /**
     * Log profile connection event by incrementing an internal counter for that profile. This log
     * persists over adapter enable/disable and only get cleared when metrics are dumped or when
     * Bluetooth process is killed.
     *
     * @param profileId Bluetooth profile that is connected at this event
     */
    public static void logProfileConnectionEvent(ProfileId profileId) {
        synchronized (sProfileConnectionCounts) {
            sProfileConnectionCounts.merge(profileId, 1, Integer::sum);
        }
    }

    /**
     * Dump collected metrics into proto using a builder. Clean up internal data after the dump.
     *
     * @param metricsBuilder proto builder for {@link BluetoothLog}
     */
    public static void dumpProto(BluetoothLog.Builder metricsBuilder) {
        synchronized (sProfileConnectionCounts) {
            sProfileConnectionCounts.forEach(
                    (key, value) ->
                            metricsBuilder.addProfileConnectionStats(
                                    ProfileConnectionStats.newBuilder()
                                            .setProfileId(key)
                                            .setNumTimesConnected(value)
                                            .build()));
            sProfileConnectionCounts.clear();
        }
    }

    protected void scheduleDrains() {
        Log.i(TAG, "setCounterMetricsAlarm()");
        if (mAlarmManager == null) {
            mAlarmManager = ((Context) mAdapterService).getSystemService(AlarmManager.class);
        }
        mAlarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + BLUETOOTH_COUNTER_METRICS_ACTION_DURATION_MILLIS,
                TAG,
                mOnAlarmListener,
                null);
    }

    public boolean count(int key, long count) {
        if (!mInitialized) {
            Log.w(TAG, "MetricsLogger isn't initialized");
            return false;
        }
        if (count <= 0) {
            Log.w(TAG, "count is not larger than 0. count: " + count + " key: " + key);
            return false;
        }
        BluetoothStatsLog.write(BluetoothStatsLog.BLUETOOTH_CODE_PATH_COUNTER, key, count);
        return true;
    }

    protected void drainBufferedCounters() {
        Log.i(TAG, "drainBufferedCounters().");
        synchronized (sLock) {
            // send mCounters to statsd
            for (int key : mCounters.keySet()) {
                count(key, mCounters.get(key));
            }
            mCounters.clear();
        }
    }

    void close() {
        if (!mInitialized) {
            return;
        }
        Log.d(TAG, "close()");
        mAdapterService.unregisterReceiver(mReceiver);
        cancelPendingDrain();
        drainBufferedCounters();
        mAlarmManager = null;
        mInitialized = false;
        mBloomFilterInitialized = false;
        mMedicalDeviceBloomFilterInitialized = false;
    }

    protected void cancelPendingDrain() {
        mAlarmManager.cancel(mOnAlarmListener);
    }

    private static void writeFieldIfNotNull(
            ProtoOutputStream proto,
            long fieldType,
            long fieldCount,
            long fieldNumber,
            Object value) {
        if (value != null) {
            try {
                if (fieldType == ProtoOutputStream.FIELD_TYPE_STRING) {
                    proto.write(fieldType | fieldCount | fieldNumber, value.toString());
                }

                if (fieldType == ProtoOutputStream.FIELD_TYPE_INT32) {
                    proto.write(fieldType | fieldCount | fieldNumber, (Integer) value);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error writing field " + fieldNumber + ": " + e.getMessage());
            }
        }
    }

    /**
     * Retrieves a byte array containing serialized remote device information for the specified
     * BluetoothDevice. This data can be used for remote device identification and logging. Does not
     * include medical remote devices.
     *
     * @param device The BluetoothDevice for which to retrieve device information.
     * @return A byte array containing the serialized remote device information.
     */
    public byte[] getRemoteDeviceInfoProto(BluetoothDevice device) {
        return mInitialized ? buildRemoteDeviceInfoProto(device, false) : null;
    }

    /**
     * Retrieves a byte array containing serialized remote device information for the specified
     * BluetoothDevice. This data can be used for remote device identification and logging.
     *
     * @param device The BluetoothDevice for which to retrieve device information.
     * @param includeMedicalDevices Should be true only if logging as de-identified metric,
     *     otherwise false.
     * @return A byte array containing the serialized remote device information.
     */
    public byte[] getRemoteDeviceInfoProto(BluetoothDevice device, boolean includeMedicalDevices) {
        return mInitialized ? buildRemoteDeviceInfoProto(device, includeMedicalDevices) : null;
    }

    private byte[] buildRemoteDeviceInfoProto(
            BluetoothDevice device, boolean includeMedicalDevices) {
        ProtoOutputStream proto = new ProtoOutputStream();

        // write Allowlisted Device Name Hash
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_STRING,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.ALLOWLISTED_DEVICE_NAME_HASH_FIELD_NUMBER,
                getAllowlistedDeviceNameHash(
                        mAdapterService.getRemoteName(device), includeMedicalDevices));

        // write COD
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_INT32,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.CLASS_OF_DEVICE_FIELD_NUMBER,
                mAdapterService.getRemoteClass(device));

        // write OUI
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_INT32,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.OUI_FIELD_NUMBER,
                getOui(device));

        // write deviceTypeMetaData
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_INT32,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.DEVICE_TYPE_METADATA_FIELD_NUMBER,
                getDeviceTypeMetaData(device));

        return proto.getBytes();
    }

    private int getDeviceTypeMetaData(BluetoothDevice device) {
        byte[] deviceTypeMetaDataBytes =
                mAdapterService.getMetadata(device, BluetoothDevice.METADATA_DEVICE_TYPE);

        if (deviceTypeMetaDataBytes == null) {
            return BluetoothProtoEnums.NOT_AVAILABLE;
        }
        String deviceTypeMetaData = new String(deviceTypeMetaDataBytes, StandardCharsets.UTF_8);

        return switch (deviceTypeMetaData) {
            case "Watch" -> BluetoothProtoEnums.WATCH;
            case "Untethered Headset" -> BluetoothProtoEnums.UNTETHERED_HEADSET;
            case "Stylus" -> BluetoothProtoEnums.STYLUS;
            case "Speaker" -> BluetoothProtoEnums.SPEAKER;
            case "Headset" -> BluetoothProtoEnums.HEADSET;
            case "Carkit" -> BluetoothProtoEnums.CARKIT;
            case "Default" -> BluetoothProtoEnums.DEFAULT;
            default -> BluetoothProtoEnums.NOT_AVAILABLE;
        };
    }

    private static int getOui(BluetoothDevice device) {
        return Integer.parseInt(device.getAddress().replace(":", "").substring(0, 6), 16);
    }

    protected List<String> getWordBreakdownList(String deviceName) {
        if (deviceName == null) {
            return Collections.emptyList();
        }
        // remove more than one spaces in a row
        deviceName = deviceName.trim().replaceAll(" +", " ");
        // remove non alphanumeric characters and spaces, and transform to lower cases.
        String[] words =
                deviceName
                        .replaceAll("[^a-zA-Z0-9 ]", "")
                        .toLowerCase(Locale.ROOT)
                        .split(" ", MAX_WORDS_ALLOWED_IN_DEVICE_NAME + 1);

        if (words.length > MAX_WORDS_ALLOWED_IN_DEVICE_NAME) {
            // Validity checking here to avoid excessively long sequences
            return Collections.emptyList();
        }
        // collect the word breakdown in an arraylist
        ArrayList<String> wordBreakdownList = new ArrayList<String>();
        for (int start = 0; start < words.length; start++) {

            StringBuilder deviceNameCombination = new StringBuilder();
            for (int end = start; end < words.length; end++) {
                deviceNameCombination.append(words[end]);
                wordBreakdownList.add(deviceNameCombination.toString());
            }
        }

        // Prevent returning a mutable list
        return Collections.unmodifiableList(wordBreakdownList);
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    protected void uploadRestrictedBluetoothDeviceName(List<String> wordBreakdownList) {
        for (String word : wordBreakdownList) {
            BtRestrictedStatsLog.write(RESTRICTED_BLUETOOTH_DEVICE_NAME_REPORTED, word);
        }
    }

    private String getMatchedString(List<String> wordBreakdownList, boolean includeMedicalDevices) {
        if (!mBloomFilterInitialized || wordBreakdownList.isEmpty()) {
            return "";
        }

        String matchedString = "";
        for (String word : wordBreakdownList) {
            byte[] sha256 = getSha256(word);
            if (mBloomFilter.mightContain(sha256) && word.length() > matchedString.length()) {
                matchedString = word;
            }
        }

        return (matchedString.equals("") && includeMedicalDevices)
                ? getMatchedStringForMedicalDevice(wordBreakdownList)
                : matchedString;
    }

    private String getMatchedStringForMedicalDevice(List<String> wordBreakdownList) {
        String matchedString = "";
        for (String word : wordBreakdownList) {
            byte[] sha256 = getSha256(word);
            if (mMedicalDeviceBloomFilter.mightContain(sha256)
                    && word.length() > matchedString.length()) {
                matchedString = word;
            }
        }
        return matchedString;
    }

    private static int convertAppImportance(int importance) {
        if (importance < IMPORTANCE_FOREGROUND_SERVICE) {
            return BluetoothStatsLog
                    .LE_APP_SCAN_STATE_CHANGED__APP_IMPORTANCE__IMPORTANCE_HIGHER_THAN_FGS;
        }
        if (importance > IMPORTANCE_FOREGROUND_SERVICE) {
            return BluetoothStatsLog
                    .LE_APP_SCAN_STATE_CHANGED__APP_IMPORTANCE__IMPORTANCE_LOWER_THAN_FGS;
        }
        return BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__APP_IMPORTANCE__IMPORTANCE_EQUAL_TO_FGS;
    }

    /** Logs the app scan stats with app attribution when the app scan state changed. */
    public void logAppScanStateChanged(
            int[] uids,
            String[] tags,
            boolean enabled,
            boolean isFilterScan,
            boolean isCallbackScan,
            int scanCallBackType,
            int scanType,
            int scanMode,
            long reportDelayMillis,
            long scanDurationMillis,
            int numOngoingScan,
            boolean isScreenOn,
            boolean isAppDead,
            int appImportance) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED,
                uids,
                tags,
                enabled,
                isFilterScan,
                isCallbackScan,
                scanCallBackType,
                scanType,
                scanMode,
                reportDelayMillis,
                scanDurationMillis,
                numOngoingScan,
                isScreenOn,
                isAppDead,
                convertAppImportance(appImportance));
    }

    /** Logs the radio scan stats with app attribution when the radio scan stopped. */
    public void logRadioScanStopped(
            int[] uids,
            String[] tags,
            int scanType,
            int scanMode,
            long scanIntervalMillis,
            long scanWindowMillis,
            boolean isScreenOn,
            long scanDurationMillis,
            int appImportance) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_RADIO_SCAN_STOPPED,
                uids,
                tags,
                scanType,
                scanMode,
                scanIntervalMillis,
                scanWindowMillis,
                isScreenOn,
                scanDurationMillis,
                convertAppImportance(appImportance));
    }

    /** Logs the advertise stats with app attribution when the advertise state changed. */
    public void logAdvStateChanged(
            int[] uids,
            String[] tags,
            boolean enabled,
            int interval,
            int txPowerLevel,
            boolean isConnectable,
            boolean isPeriodicAdvertisingEnabled,
            boolean hasScanResponse,
            boolean isExtendedAdv,
            int instanceCount,
            long advDurationMs,
            int appImportance) {
        BluetoothStatsLog.write(
                BluetoothStatsLog.LE_ADV_STATE_CHANGED,
                uids,
                tags,
                enabled,
                interval,
                txPowerLevel,
                isConnectable,
                isPeriodicAdvertisingEnabled,
                hasScanResponse,
                isExtendedAdv,
                instanceCount,
                advDurationMs,
                convertAppImportance(appImportance));
    }

    protected String getAllowlistedDeviceNameHash(
            String deviceName, boolean includeMedicalDevices) {
        List<String> wordBreakdownList = getWordBreakdownList(deviceName);
        String matchedString = getMatchedString(wordBreakdownList, includeMedicalDevices);
        return getSha256String(matchedString);
    }

    protected String logAllowlistedDeviceNameHash(int metricId, String deviceName) {
        List<String> wordBreakdownList = getWordBreakdownList(deviceName);
        boolean includeMedicalDevices = false;
        String matchedString = getMatchedString(wordBreakdownList, includeMedicalDevices);
        if (!matchedString.isEmpty()) {
            statslogBluetoothDeviceNames(metricId, matchedString);
        }
        return getSha256String(matchedString);
    }

    protected void statslogBluetoothDeviceNames(int metricId, String matchedString) {
        String sha256 = getSha256String(matchedString);
        Log.d(TAG, "Uploading sha256 hash of matched bluetooth device name: " + sha256);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_HASHED_DEVICE_NAME_REPORTED, metricId, sha256);
    }

    public void logBluetoothEvent(BluetoothDevice device, int eventType, int state, int uid) {

        if (!mInitialized || mAdapterService.getMetricId(device) == 0) {
            return;
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED,
                eventType,
                state,
                uid,
                mAdapterService.getMetricId(device),
                getRemoteDeviceInfoProto(device, false));
    }

    protected static String getSha256String(String name) {
        if (name.isEmpty()) {
            return "";
        }
        StringBuilder hexString = new StringBuilder();
        byte[] hashBytes = getSha256(name);
        for (byte b : hashBytes) {
            hexString.append(Utils.formatSimple("%02x", b));
        }
        return hexString.toString();
    }

    protected static byte[] getSha256(String name) {
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "No SHA-256 in MessageDigest");
            return null;
        }
        return digest.digest(name.getBytes(StandardCharsets.UTF_8));
    }

    private static int getProfileEnumFromProfileId(int profile) {
        return switch (profile) {
            case BluetoothProfile.A2DP ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_A2DP;
            case BluetoothProfile.A2DP_SINK ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_A2DP_SINK;
            case BluetoothProfile.HEADSET ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HEADSET;
            case BluetoothProfile.HEADSET_CLIENT ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HEADSET_CLIENT;
            case BluetoothProfile.MAP_CLIENT ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_MAP_CLIENT;
            case BluetoothProfile.HID_HOST ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HID_HOST;
            case BluetoothProfile.PAN ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_PAN;
            case BluetoothProfile.PBAP_CLIENT ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_PBAP_CLIENT;
            case BluetoothProfile.HEARING_AID ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HEARING_AID;
            case BluetoothProfile.HAP_CLIENT ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_HAP_CLIENT;
            case BluetoothProfile.VOLUME_CONTROL ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_VOLUME_CONTROL;
            case BluetoothProfile.CSIP_SET_COORDINATOR ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_CSIP_SET_COORDINATOR;
            case BluetoothProfile.LE_AUDIO ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_LE_AUDIO;
            case BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_LE_AUDIO_BROADCAST_ASSISTANT;
            case BluetoothProfile.BATTERY ->
                    BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION_BATTERY;
            default -> BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__PROFILE_CONNECTION;
        };
    }

    public void logProfileConnectionStateChange(
            BluetoothDevice device, int profileId, int state, int prevState) {

        switch (state) {
            case BluetoothAdapter.STATE_CONNECTED:
                logBluetoothEvent(
                        device,
                        getProfileEnumFromProfileId(profileId),
                        BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__SUCCESS,
                        0);
                break;
            case BluetoothAdapter.STATE_DISCONNECTED:
                if (prevState == BluetoothAdapter.STATE_CONNECTING) {
                    logBluetoothEvent(
                            device,
                            getProfileEnumFromProfileId(profileId),
                            BluetoothStatsLog.BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__FAIL,
                            0);
                }
                break;
        }
    }

    /** Logs LE Audio Broadcast audio session. */
    public void logLeAudioBroadcastAudioSession(
            int broadcastId,
            int[] audioQuality,
            int groupSize,
            long sessionDurationMs,
            long latencySessionConfiguredMs,
            long latencySessionStreamingMs,
            int sessionStatus) {
        if (!mInitialized) {
            return;
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BROADCAST_AUDIO_SESSION_REPORTED,
                broadcastId,
                audioQuality.length,
                audioQuality,
                groupSize,
                sessionDurationMs,
                latencySessionConfiguredMs,
                latencySessionStreamingMs,
                sessionStatus);
    }

    /** Logs Bond State Machine event */
    public void logBondStateMachineEvent(BluetoothDevice device, int bondState) {
        switch (bondState) {
            case BluetoothDevice.BOND_NONE:
                logBluetoothEvent(
                        device,
                        BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__BOND,
                        BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__STATE_NONE,
                        0);
                break;
            case BluetoothDevice.BOND_BONDED:
                logBluetoothEvent(
                        device,
                        BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__EVENT_TYPE__BOND,
                        BLUETOOTH_CROSS_LAYER_EVENT_REPORTED__STATE__STATE_BONDED,
                        0);
                break;
            default:
        }
    }

    /** Logs LE Audio Broadcast audio sync. */
    public void logLeAudioBroadcastAudioSync(
            BluetoothDevice device,
            int broadcastId,
            boolean isLocalBroadcast,
            long syncDurationMs,
            long latencyPaSyncMs,
            long latencyBisSyncMs,
            int syncStatus) {
        if (!mInitialized) {
            return;
        }

        BluetoothStatsLog.write(
                BluetoothStatsLog.BROADCAST_AUDIO_SYNC_REPORTED,
                isLocalBroadcast ? broadcastId : BassConstants.INVALID_BROADCAST_ID,
                isLocalBroadcast,
                syncDurationMs,
                latencyPaSyncMs,
                latencyBisSyncMs,
                syncStatus,
                getRemoteDeviceInfoProto(device, false));
    }
}
