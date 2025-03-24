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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link VolumeControlOffsetDescriptor}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlOffsetDescriptorTest {
    @Test
    public void testVolumeControlOffsetDescriptorInvalidIdOperations() {
        VolumeControlOffsetDescriptor descriptor = new VolumeControlOffsetDescriptor();
        int invalidId = -1;
        int testValue = 100;
        String testDesc = "testDescription";
        int testLocation = 10000;

        // Verify API operations on invalid ID
        assertThat(descriptor.setValue(invalidId, testValue)).isFalse();
        assertThat(descriptor.getValue(invalidId)).isEqualTo(0);
        assertThat(descriptor.setDescription(invalidId, testDesc)).isFalse();

        assertThat(descriptor.getDescription(invalidId)).isNull();
        assertThat(descriptor.setLocation(invalidId, testLocation)).isFalse();

        assertThat(descriptor.getLocation(invalidId)).isEqualTo(0);
    }

    @Test
    public void testVolumeControlOffsetDescriptorMultipleInstanceAdded() {
        VolumeControlOffsetDescriptor descriptor = new VolumeControlOffsetDescriptor();

        int validId = 10;

        // Verify adding input
        assertThat(descriptor.size()).isEqualTo(0);
        descriptor.add(validId);
        assertThat(descriptor.size()).isEqualTo(1);

        // Verify adding same instance will not increase number of descriptors
        descriptor.add(validId);
        assertThat(descriptor.size()).isEqualTo(1);
    }

    @Test
    public void testVolumeControlOffsetDescriptorInstanceRemoveAndClear() {
        VolumeControlOffsetDescriptor descriptor = new VolumeControlOffsetDescriptor();
        int id_1 = 10;
        int id_2 = 20;
        int invalidId = 1;

        descriptor.add(id_1);
        descriptor.add(id_2);
        assertThat(descriptor.size()).isEqualTo(2);

        // Check remove api
        descriptor.remove(id_1);
        assertThat(descriptor.size()).isEqualTo(1);

        // Check remove api with invalid id
        descriptor.remove(invalidId);
        assertThat(descriptor.size()).isEqualTo(1);

        // Check clear API
        descriptor.clear();
        assertThat(descriptor.size()).isEqualTo(0);
    }

    @Test
    public void testVolumeControlOffsetDescriptorAllValidApiCalls() {
        VolumeControlOffsetDescriptor descriptor = new VolumeControlOffsetDescriptor();

        int validId = 10;
        int testValue = 100;
        String testDesc = "testDescription";
        int testLocation = 10000;

        descriptor.add(validId);

        // Verify operations on valid id
        assertThat(descriptor.setValue(validId, testValue)).isTrue();
        assertThat(descriptor.getValue(validId)).isEqualTo(testValue);
        assertThat(descriptor.setDescription(validId, testDesc)).isTrue();
        assertThat(descriptor.getDescription(validId)).isEqualTo(testDesc);
        assertThat(descriptor.setLocation(validId, testLocation)).isTrue();
        assertThat(descriptor.getLocation(validId)).isEqualTo(testLocation);

        // Verify debug dump
        StringBuilder sb = new StringBuilder();
        descriptor.dump(sb);
        assertThat(sb.toString().contains(testDesc)).isTrue();
    }
}
