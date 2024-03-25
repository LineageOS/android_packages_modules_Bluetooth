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

import static com.android.bluetooth.BtRestrictedStatsLog.RESTRICTED_BLUETOOTH_DEVICE_NAME_REPORTED;

import android.app.AlarmManager;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
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
import com.android.modules.utils.build.SdkLevel;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
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

/** Class of Bluetooth Metrics */
public class MetricsLogger {
    private static final String TAG = "BluetoothMetricsLogger";
    private static final String BLOOMFILTER_PATH = "/data/misc/bluetooth";
    private static final String BLOOMFILTER_FILE = "/devices_for_metrics_v2";
    public static final String BLOOMFILTER_FULL_PATH = BLOOMFILTER_PATH + BLOOMFILTER_FILE;

    // 6 hours timeout for counter metrics
    private static final long BLUETOOTH_COUNTER_METRICS_ACTION_DURATION_MILLIS = 6L * 3600L * 1000L;
    private static final int MAX_WORDS_ALLOWED_IN_DEVICE_NAME = 7;

    private static final HashMap<ProfileId, Integer> sProfileConnectionCounts = new HashMap<>();

    HashMap<Integer, Long> mCounters = new HashMap<>();
    private static volatile MetricsLogger sInstance = null;
    private Context mContext = null;
    private AlarmManager mAlarmManager = null;
    private boolean mInitialized = false;
    private static final Object sLock = new Object();
    private BloomFilter<byte[]> mBloomFilter = null;
    protected boolean mBloomFilterInitialized = false;

    private AlarmManager.OnAlarmListener mOnAlarmListener =
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

    public boolean isInitialized() {
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

    protected void setBloomfilter(BloomFilter bloomfilter) {
        mBloomFilter = bloomfilter;
    }

    public boolean init(Context context) {
        if (mInitialized) {
            return false;
        }
        mInitialized = true;
        mContext = context;
        scheduleDrains();
        if (!initBloomFilter(BLOOMFILTER_FULL_PATH)) {
            Log.w(TAG, "MetricsLogger can't initialize the bloomfilter");
            // The class is for multiple metrics tasks.
            // We still want to use this class even if the bloomfilter isn't initialized
            // so still return true here.
        }
        return true;
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
            mAlarmManager = mContext.getSystemService(AlarmManager.class);
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

    public boolean close() {
        if (!mInitialized) {
            return false;
        }
        Log.d(TAG, "close()");
        cancelPendingDrain();
        drainBufferedCounters();
        mAlarmManager = null;
        mContext = null;
        mInitialized = false;
        mBloomFilterInitialized = false;
        return true;
    }

    protected void cancelPendingDrain() {
        mAlarmManager.cancel(mOnAlarmListener);
    }

    private void writeFieldIfNotNull(
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
     * BluetoothDevice. This data can be used for remote device identification and logging.
     *
     * @param device The BluetoothDevice for which to retrieve device information.
     * @return A byte array containing the serialized remote device information.
     */
    public byte[] getRemoteDeviceInfoProto(BluetoothDevice device) {
        ProtoOutputStream proto = new ProtoOutputStream();

        // write Allowlisted Device Name Hash
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_STRING,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.ALLOWLISTED_DEVICE_NAME_HASH_FIELD_NUMBER,
                getAllowlistedDeviceNameHash(device.getName()));

        // write COD
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_INT32,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.CLASS_OF_DEVICE_FIELD_NUMBER,
                device.getBluetoothClass() != null
                        ? device.getBluetoothClass().getClassOfDevice()
                        : null);

        // write OUI
        writeFieldIfNotNull(
                proto,
                ProtoOutputStream.FIELD_TYPE_INT32,
                ProtoOutputStream.FIELD_COUNT_SINGLE,
                BluetoothRemoteDeviceInformation.OUI_FIELD_NUMBER,
                getOui(device));

        return proto.getBytes();
    }

    private int getOui(BluetoothDevice device) {
        return Integer.parseInt(device.getAddress().replace(":", "").substring(0, 6), 16);
    }

    private List<String> getWordBreakdownList(String deviceName) {
        if (deviceName == null) {
            return Collections.emptyList();
        }
        // remove more than one spaces in a row
        deviceName = deviceName.trim().replaceAll(" +", " ");
        // remove non alphanumeric characters and spaces, and transform to lower cases.
        String[] words = Ascii.toLowerCase(deviceName.replaceAll("[^a-zA-Z0-9 ]", "")).split(" ");

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

        return wordBreakdownList;
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void uploadRestrictedBluetothDeviceName(List<String> wordBreakdownList) {
        for (String word : wordBreakdownList) {
            BtRestrictedStatsLog.write(RESTRICTED_BLUETOOTH_DEVICE_NAME_REPORTED, word);
        }
    }

    private String getMatchedString(List<String> wordBreakdownList) {
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
        return matchedString;
    }

    protected String getAllowlistedDeviceNameHash(String deviceName) {
        List<String> wordBreakdownList = getWordBreakdownList(deviceName);
        String matchedString = getMatchedString(wordBreakdownList);
        return getSha256String(matchedString);
    }

    protected String logAllowlistedDeviceNameHash(
            int metricId, String deviceName, boolean logRestrictedNames) {
        List<String> wordBreakdownList = getWordBreakdownList(deviceName);
        String matchedString = getMatchedString(wordBreakdownList);
        if (logRestrictedNames) {
            // Log the restricted bluetooth device name
            if (SdkLevel.isAtLeastU()) {
                uploadRestrictedBluetothDeviceName(wordBreakdownList);
            }
        }
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

    protected static String getSha256String(String name) {
        if (name.isEmpty()) {
            return "";
        }
        StringBuilder hexString = new StringBuilder();
        byte[] hashBytes = getSha256(name);
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
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
}
