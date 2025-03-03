/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client;

import android.util.Log;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

/** Helper class to parse the Broadcast Announcement BASE data */
class BaseData {
    private static final String TAG = BassClientService.TAG + "." + BaseData.class.getSimpleName();

    private static final int METADATA_LEVEL1 = 1;
    private static final int METADATA_LEVEL2 = 2;
    private static final int METADATA_LEVEL3 = 3;
    private static final int METADATA_PRESENTATIONDELAY_LENGTH = 3;
    private static final int METADATA_CODEC_LENGTH = 5;
    private static final int CODEC_CONFIGURATION_SAMPLE_RATE_TYPE = 0x01;
    private static final int CODEC_CONFIGURATION_FRAME_DURATION_TYPE = 0x02;
    private static final int CODEC_CONFIGURATION_CHANNEL_ALLOCATION_TYPE = 0x03;
    private static final int CODEC_CONFIGURATION_OCTETS_PER_FRAME_TYPE = 0x04;
    private static final int METADATA_LANGUAGE_TYPE = 0x04;
    private static final int CODEC_AUDIO_LOCATION_FRONT_LEFT = 0x01000000;
    private static final int CODEC_AUDIO_LOCATION_FRONT_RIGHT = 0x02000000;
    private static final int CODEC_AUDIO_SAMPLE_RATE_8K = 0x01;
    private static final int CODEC_AUDIO_SAMPLE_RATE_16K = 0x03;
    private static final int CODEC_AUDIO_SAMPLE_RATE_24K = 0x05;
    private static final int CODEC_AUDIO_SAMPLE_RATE_32K = 0x06;
    private static final int CODEC_AUDIO_SAMPLE_RATE_44P1K = 0x07;
    private static final int CODEC_AUDIO_SAMPLE_RATE_48K = 0x08;
    private static final int CODEC_AUDIO_FRAME_DURATION_7P5MS = 0x00;
    private static final int CODEC_AUDIO_FRAME_DURATION_10MS = 0x01;

    private final BaseInformation mLevelOne;
    private final List<BaseInformation> mLevelTwo;
    private final List<BaseInformation> mLevelThree;

    private int mNumBISIndices = 0;

    public static class BaseInformation {
        public byte[] presentationDelay = new byte[3];
        public byte[] codecId = new byte[5];
        public int codecConfigLength;
        public byte[] codecConfigInfo;
        public int metaDataLength;
        public byte[] metaData;
        public byte numSubGroups;
        public byte index;
        public int subGroupId;
        public int level;

        BaseInformation() {
            presentationDelay = new byte[3];
            codecId = new byte[5];
            codecConfigLength = 0;
            codecConfigInfo = new byte[0];
            metaDataLength = 0;
            metaData = new byte[0];
            numSubGroups = 0;
            index = (byte) 0xFF;
            level = 0;
            log("BaseInformation is Initialized");
        }

        void print() {
            log("**BEGIN: Base Information**");
            log("**Level: " + level + "***");
            if (level == 1) {
                log("presentationDelay: " + Arrays.toString(presentationDelay));
            }
            if (level == 2) {
                log("codecId: " + Arrays.toString(codecId));
            }
            if (level == 2 || level == 3) {
                log("codecConfigLength: " + codecConfigLength);
                log("subGroupId: " + subGroupId);
            }
            if (codecConfigLength != 0) {
                log("codecConfigInfo: " + Arrays.toString(codecConfigInfo));
            }
            if (level == 2) {
                log("metaDataLength: " + metaDataLength);
                if (metaDataLength != 0) {
                    log("metaData: " + Arrays.toString(metaData));
                }
                if (level == 1 || level == 2) {
                    log("numSubGroups: " + numSubGroups);
                }
            }
            log("**END: Base Information****");
        }
    }

    BaseData(
            BaseInformation levelOne,
            List<BaseInformation> levelTwo,
            List<BaseInformation> levelThree,
            int numOfBISIndices) {
        mLevelOne = levelOne;
        mLevelTwo = levelTwo;
        mLevelThree = levelThree;
        mNumBISIndices = numOfBISIndices;
    }

    static BaseData parseBaseData(byte[] serviceData) {
        if (serviceData == null) {
            Log.e(TAG, "Invalid service data for BaseData construction");
            throw new IllegalArgumentException("Basedata: serviceData is null");
        }
        BaseInformation levelOne = new BaseInformation();
        List<BaseInformation> levelTwo = new ArrayList<>();
        List<BaseInformation> levelThree = new ArrayList<>();
        int numOfBISIndices = 0;
        log("BASE input" + Arrays.toString(serviceData));

        // Parse Level 1 base
        levelOne.level = METADATA_LEVEL1;
        int offset = 0;
        System.arraycopy(serviceData, offset, levelOne.presentationDelay, 0, 3);
        offset += METADATA_PRESENTATIONDELAY_LENGTH;
        levelOne.numSubGroups = serviceData[offset++];
        levelOne.print();
        log("levelOne subgroups" + levelOne.numSubGroups);
        for (int i = 0; i < (int) levelOne.numSubGroups; i++) {
            if (offset >= serviceData.length) {
                Log.e(TAG, "Error: parsing Level 2");
                return null;
            }

            Pair<BaseInformation, Integer> pair1 = parseLevelTwo(serviceData, i, offset);
            if (pair1 == null) {
                Log.e(TAG, "Error: parsing Level 2");
                return null;
            }
            BaseInformation node2 = pair1.first;
            numOfBISIndices += node2.numSubGroups;
            levelTwo.add(node2);
            node2.print();
            offset = pair1.second;
            for (int k = 0; k < node2.numSubGroups; k++) {
                if (offset >= serviceData.length) {
                    Log.e(TAG, "Error: parsing Level 3");
                    return null;
                }

                Pair<BaseInformation, Integer> pair2 = parseLevelThree(serviceData, offset);
                if (pair2 == null) {
                    Log.e(TAG, "Error: parsing Level 3");
                    return null;
                }
                BaseInformation node3 = pair2.first;
                levelThree.add(node3);
                node3.print();
                offset = pair2.second;
            }
        }
        consolidateBaseofLevelTwo(levelTwo, levelThree);
        return new BaseData(levelOne, levelTwo, levelThree, numOfBISIndices);
    }

    private static Pair<BaseInformation, Integer> parseLevelTwo(
            byte[] serviceData, int groupIndex, int offset) {
        log("Parsing Level 2");
        BaseInformation node = new BaseInformation();
        node.level = METADATA_LEVEL2;
        node.subGroupId = groupIndex;
        int bufferLengthLeft = (serviceData.length - offset);

        // Min. length expected is: codecID (5) + numBis (1) + codecSpecCfgLen (1) + metadataLen (1)
        final int minNodeBufferLen = METADATA_CODEC_LENGTH + 3;
        if (bufferLengthLeft < minNodeBufferLen) {
            Log.e(TAG, "Error: Invalid Lvl2 buffer length.");
            return null;
        }

        node.numSubGroups = serviceData[offset++]; // NumBis
        System.arraycopy(serviceData, offset, node.codecId, 0, METADATA_CODEC_LENGTH);
        offset += METADATA_CODEC_LENGTH;

        // Declared codec specific data length
        int declaredLength = serviceData[offset++] & 0xff;

        bufferLengthLeft = (serviceData.length - offset);
        if (declaredLength < 0 || declaredLength > bufferLengthLeft) {
            Log.e(TAG, "Error: Invalid codec config length or codec config truncated.");
            return null;
        }

        if (declaredLength != 0) {
            node.codecConfigLength = declaredLength;
            node.codecConfigInfo = new byte[node.codecConfigLength];
            System.arraycopy(serviceData, offset, node.codecConfigInfo, 0, node.codecConfigLength);
            offset += node.codecConfigLength;
        }

        // Verify the buffer size left
        bufferLengthLeft = (serviceData.length - offset);
        if (bufferLengthLeft < 1) {
            Log.e(TAG, "Error: Invalid Lvl2 buffer length.");
            return null;
        }

        // Declared metadata length
        declaredLength = serviceData[offset++] & 0xff;
        --bufferLengthLeft;
        if (declaredLength < 0 || declaredLength > bufferLengthLeft) {
            Log.e(TAG, "Error: Invalid metadata length or metadata truncated.");
            return null;
        }

        if (declaredLength != 0) {
            node.metaDataLength = declaredLength;
            node.metaData = new byte[node.metaDataLength];
            System.arraycopy(serviceData, offset, node.metaData, 0, node.metaDataLength);
            offset += node.metaDataLength;
        }
        return new Pair<BaseInformation, Integer>(node, offset);
    }

    private static Pair<BaseInformation, Integer> parseLevelThree(byte[] serviceData, int offset) {
        log("Parsing Level 3");
        BaseInformation node = new BaseInformation();
        node.level = METADATA_LEVEL3;
        int bufferLengthLeft = (serviceData.length - offset);

        // Min. length expected is: bisIdx (1) + codecSpecCfgLen (1)
        final int minNodeBufferLen = 2;
        if (bufferLengthLeft < minNodeBufferLen) {
            Log.e(TAG, "Error: Invalid Lvl2 buffer length.");
            return null;
        }
        node.index = serviceData[offset++];

        // Verify the buffer size left
        int declaredLength = serviceData[offset++] & 0xff;

        bufferLengthLeft = (serviceData.length - offset);
        if (declaredLength < 0 || declaredLength > bufferLengthLeft) {
            Log.e(TAG, "Error: Invalid metadata length or metadata truncated.");
            return null;
        }

        if (declaredLength != 0) {
            node.codecConfigLength = declaredLength;
            node.codecConfigInfo = new byte[node.codecConfigLength];
            System.arraycopy(serviceData, offset, node.codecConfigInfo, 0, node.codecConfigLength);
            offset += node.codecConfigLength;
        }
        return new Pair<BaseInformation, Integer>(node, offset);
    }

    static void consolidateBaseofLevelTwo(
            List<BaseInformation> levelTwo, List<BaseInformation> levelThree) {
        int startIdx = 0;
        int children = 0;
        for (int i = 0; i < levelTwo.size(); i++) {
            startIdx = startIdx + children;
            children = children + levelTwo.get(i).numSubGroups;
            consolidateBaseofLevelThree(
                    levelTwo, levelThree, i, startIdx, levelTwo.get(i).numSubGroups);
        }
    }

    static void consolidateBaseofLevelThree(
            List<BaseInformation> levelTwo,
            List<BaseInformation> levelThree,
            int parentSubgroup,
            int startIdx,
            int numNodes) {
        for (int i = startIdx; i < startIdx + numNodes || i < levelThree.size(); i++) {
            levelThree.get(i).subGroupId = levelTwo.get(parentSubgroup).subGroupId;
        }
    }

    public int getNumberOfIndices() {
        return mNumBISIndices;
    }

    public BaseInformation getLevelOne() {
        return mLevelOne;
    }

    public List<BaseInformation> getLevelTwo() {
        return mLevelTwo;
    }

    public List<BaseInformation> getLevelThree() {
        return mLevelThree;
    }

    public byte getNumberOfSubgroupsofBIG() {
        byte ret = 0;
        if (mLevelOne != null) {
            ret = mLevelOne.numSubGroups;
        }
        return ret;
    }

    public List<BaseInformation> getBISIndexInfos() {
        return mLevelThree;
    }

    byte[] getMetadata(int subGroup) {
        if (mLevelTwo != null) {
            return mLevelTwo.get(subGroup).metaData;
        }
        return null;
    }

    String getMetadataString(byte[] metadataBytes) {
        String ret = "";
        switch (metadataBytes[1]) {
            case METADATA_LANGUAGE_TYPE:
                char[] lang = new char[3];
                System.arraycopy(metadataBytes, 1, lang, 0, 3);
                Locale locale = new Locale(String.valueOf(lang));
                try {
                    ret = locale.getISO3Language();
                } catch (MissingResourceException e) {
                    ret = "UNKNOWN LANGUAGE";
                }
                break;
            default:
                ret = "UNKNOWN METADATA TYPE";
        }
        log("getMetadataString: " + ret);
        return ret;
    }

    String getCodecParamString(byte[] csiBytes) {
        String ret = "";
        switch (csiBytes[1]) {
            case CODEC_CONFIGURATION_CHANNEL_ALLOCATION_TYPE:
                byte[] location = new byte[4];
                System.arraycopy(csiBytes, 2, location, 0, 4);
                ByteBuffer wrapped = ByteBuffer.wrap(location);
                int audioLocation = wrapped.getInt();
                log("audioLocation: " + audioLocation);
                switch (audioLocation) {
                    case CODEC_AUDIO_LOCATION_FRONT_LEFT:
                        ret = "LEFT";
                        break;
                    case CODEC_AUDIO_LOCATION_FRONT_RIGHT:
                        ret = "RIGHT";
                        break;
                    case CODEC_AUDIO_LOCATION_FRONT_LEFT | CODEC_AUDIO_LOCATION_FRONT_RIGHT:
                        ret = "LR";
                        break;
                }
                break;
            case CODEC_CONFIGURATION_SAMPLE_RATE_TYPE:
                switch (csiBytes[2]) {
                    case CODEC_AUDIO_SAMPLE_RATE_8K:
                        ret = "8K";
                        break;
                    case CODEC_AUDIO_SAMPLE_RATE_16K:
                        ret = "16K";
                        break;
                    case CODEC_AUDIO_SAMPLE_RATE_24K:
                        ret = "24K";
                        break;
                    case CODEC_AUDIO_SAMPLE_RATE_32K:
                        ret = "32K";
                        break;
                    case CODEC_AUDIO_SAMPLE_RATE_44P1K:
                        ret = "44.1K";
                        break;
                    case CODEC_AUDIO_SAMPLE_RATE_48K:
                        ret = "48K";
                        break;
                }
                break;
            case CODEC_CONFIGURATION_FRAME_DURATION_TYPE:
                switch (csiBytes[2]) {
                    case CODEC_AUDIO_FRAME_DURATION_7P5MS:
                        ret = "7.5ms";
                        break;
                    case CODEC_AUDIO_FRAME_DURATION_10MS:
                        ret = "10ms";
                        break;
                }
                break;
            case CODEC_CONFIGURATION_OCTETS_PER_FRAME_TYPE:
                ret = "OPF_" + String.valueOf((int) csiBytes[2]);
                break;
            default:
                ret = "UNKNOWN PARAMETER";
        }
        log("getCodecParamString: " + ret);
        return ret;
    }

    void print() {
        mLevelOne.print();
        log("----- Level TWO BASE ----");
        for (int i = 0; i < mLevelTwo.size(); i++) {
            mLevelTwo.get(i).print();
        }
        log("----- Level THREE BASE ----");
        for (int i = 0; i < mLevelThree.size(); i++) {
            mLevelThree.get(i).print();
        }
    }

    static void log(String msg) {
        Log.d(TAG, msg);
    }
}
