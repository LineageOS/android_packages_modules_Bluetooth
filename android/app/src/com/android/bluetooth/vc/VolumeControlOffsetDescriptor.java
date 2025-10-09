/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.vc;

import com.android.bluetooth.profile.ProfileService;

import java.util.HashMap;
import java.util.Map;

/*
 * Class representing Volume Control Offset on the remote device.
 * This is internal class for the VolumeControlService
 */
class VolumeControlOffsetDescriptor {
    final Map<Integer, Descriptor> mVolumeOffsets = new HashMap<>();

    private static class Descriptor {
        int mValue = 0;
        int mLocation = 0;
        String mDescription = null;
    }

    int size() {
        return mVolumeOffsets.size();
    }

    void add(int id) {
        if (!mVolumeOffsets.containsKey(id)) {
            mVolumeOffsets.put(id, new Descriptor());
        }
    }

    boolean setValue(int id, int value) {
        Descriptor desc = mVolumeOffsets.get(id);
        if (desc == null) {
            return false;
        }
        desc.mValue = value;
        return true;
    }

    int getValue(int id) {
        Descriptor desc = mVolumeOffsets.get(id);
        if (desc == null) {
            return 0;
        }
        return desc.mValue;
    }

    boolean setDescription(int id, String description) {
        Descriptor desc = mVolumeOffsets.get(id);
        if (desc == null) {
            return false;
        }
        desc.mDescription = description;
        return true;
    }

    String getDescription(int id) {
        Descriptor desc = mVolumeOffsets.get(id);
        if (desc == null) {
            return null;
        }
        return desc.mDescription;
    }

    boolean setLocation(int id, int location) {
        Descriptor desc = mVolumeOffsets.get(id);
        if (desc == null) {
            return false;
        }
        desc.mLocation = location;
        return true;
    }

    int getLocation(int id) {
        Descriptor desc = mVolumeOffsets.get(id);
        if (desc == null) {
            return 0;
        }
        return desc.mLocation;
    }

    void remove(int id) {
        mVolumeOffsets.remove(id);
    }

    void clear() {
        mVolumeOffsets.clear();
    }

    void dump(StringBuilder sb) {
        for (Map.Entry<Integer, Descriptor> entry : mVolumeOffsets.entrySet()) {
            Descriptor descriptor = entry.getValue();
            Integer id = entry.getKey();
            ProfileService.println(sb, "        Id: " + id);
            ProfileService.println(sb, "        value: " + descriptor.mValue);
            ProfileService.println(sb, "        location: " + descriptor.mLocation);
            ProfileService.println(sb, "        description: " + descriptor.mDescription);
        }
    }
}
