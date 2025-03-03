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

import static android.Manifest.permission.MEDIA_CONTENT_CONTROL;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dpsink.A2dpSinkNativeInterface;
import com.android.bluetooth.avrcp.AvrcpNativeInterface;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerNativeInterface;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hearingaid.HearingAidNativeInterface;
import com.android.bluetooth.hfp.HeadsetNativeInterface;
import com.android.bluetooth.hfpclient.NativeInterface;
import com.android.bluetooth.hid.HidHostNativeInterface;
import com.android.bluetooth.le_audio.LeAudioNativeInterface;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ProfileServiceTest {
    private static final int NUM_REPEATS = 5;

    @Spy
    private AdapterService mAdapterService =
            new AdapterService(InstrumentationRegistry.getInstrumentation().getTargetContext());

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private DatabaseManager mDatabaseManager;
    @Mock private TelephonyManager mMockTelephonyManager;

    private int[] mProfiles;

    @Mock private A2dpSinkNativeInterface mA2dpSinkNativeInterface;
    @Mock private AvrcpNativeInterface mAvrcpNativeInterface;
    @Mock private AvrcpControllerNativeInterface mAvrcpControllerNativeInterface;
    @Mock private HeadsetNativeInterface mHeadsetNativeInterface;
    @Mock private NativeInterface mHeadsetClientNativeInterface;
    @Mock private HearingAidNativeInterface mHearingAidNativeInterface;
    @Mock private SdpManagerNativeInterface mSdpManagerNativeInterface;
    @Mock private HidHostNativeInterface mHidHostNativeInterface;
    @Mock private LeAudioNativeInterface mLeAudioInterface;

    private void setProfileState(int profile, int state) {
        FutureTask task =
                new FutureTask(() -> mAdapterService.setProfileServiceState(profile, state), null);
        new Handler(Looper.getMainLooper()).post(task);
        try {
            task.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setAllProfilesState(int state, int invocationNumber) {
        int profileCount = mProfiles.length;
        for (int profile : mProfiles) {
            setProfileState(profile, state);
        }
        if (invocationNumber == 0) {
            verify(mAdapterService, never()).onProfileServiceStateChanged(any(), anyInt());
            return;
        }
        ArgumentCaptor<ProfileService> argument = ArgumentCaptor.forClass(ProfileService.class);
        verify(mAdapterService, times(profileCount * invocationNumber))
                .onProfileServiceStateChanged(argument.capture(), eq(state));

        Map<Class, Long> counts =
                argument.getAllValues().stream()
                        .collect(Collectors.groupingBy(Object::getClass, Collectors.counting()));

        counts.forEach(
                (clazz, count) -> assertThat((long) invocationNumber).isEqualTo(count.longValue()));
    }

    @Before
    public void setUp()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(MEDIA_CONTENT_CONTROL);
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doNothing().when(mAdapterService).addProfile(any());
        doNothing().when(mAdapterService).removeProfile(any());
        doNothing().when(mAdapterService).onProfileServiceStateChanged(any(), anyInt());
        doReturn(42).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(false).when(mAdapterService).isA2dpOffloadEnabled();
        doReturn(false).when(mAdapterService).pbapPseDynamicVersionUpgradeIsEnabled();

        TestUtils.mockGetSystemService(
                mAdapterService,
                "Context.TELEPHONY_SERVICE",
                TelephonyManager.class,
                mMockTelephonyManager);

        mProfiles =
                Arrays.stream(Config.getSupportedProfiles())
                        .filter(
                                profile ->
                                        profile != BluetoothProfile.HAP_CLIENT
                                                && profile != BluetoothProfile.VOLUME_CONTROL
                                                && profile != BluetoothProfile.CSIP_SET_COORDINATOR
                                                && profile != BluetoothProfile.GATT
                                                && profile != BluetoothProfile.HID_DEVICE
                                                && profile != BluetoothProfile.PAN
                                                && profile != BluetoothProfile.A2DP)
                        .toArray();
        TestUtils.setAdapterService(mAdapterService);

        assertThat(AdapterService.getAdapterService()).isNotNull();

        A2dpSinkNativeInterface.setInstance(mA2dpSinkNativeInterface);
        AvrcpNativeInterface.setInstance(mAvrcpNativeInterface);
        AvrcpControllerNativeInterface.setInstance(mAvrcpControllerNativeInterface);
        HeadsetNativeInterface.setInstance(mHeadsetNativeInterface);
        /* HeadsetClient */ NativeInterface.setInstance(mHeadsetClientNativeInterface);
        HearingAidNativeInterface.setInstance(mHearingAidNativeInterface);
        SdpManagerNativeInterface.setInstance(mSdpManagerNativeInterface);
        HidHostNativeInterface.setInstance(mHidHostNativeInterface);
        LeAudioNativeInterface.setInstance(mLeAudioInterface);
    }

    @After
    public void tearDown()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        TestUtils.clearAdapterService(mAdapterService);
        mAdapterService = null;
        mProfiles = null;
        A2dpSinkNativeInterface.setInstance(null);
        AvrcpNativeInterface.setInstance(null);
        AvrcpControllerNativeInterface.setInstance(null);
        HeadsetNativeInterface.setInstance(null);
        /* HeadsetClient */ NativeInterface.setInstance(null);
        HearingAidNativeInterface.setInstance(null);
        SdpManagerNativeInterface.setInstance(null);
        HidHostNativeInterface.setInstance(null);
        LeAudioNativeInterface.setInstance(null);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    /**
     * Test: Start the Bluetooth services that are configured. Verify that the same services start.
     */
    @Test
    public void testEnableDisable() {
        setAllProfilesState(BluetoothAdapter.STATE_ON, 1);
        setAllProfilesState(BluetoothAdapter.STATE_OFF, 1);
    }

    /**
     * Test: Start the Bluetooth services that are configured twice. Verify that the services start.
     */
    @Test
    public void testEnableDisableTwice() {
        setAllProfilesState(BluetoothAdapter.STATE_ON, 1);
        setAllProfilesState(BluetoothAdapter.STATE_OFF, 1);
        setAllProfilesState(BluetoothAdapter.STATE_ON, 2);
        setAllProfilesState(BluetoothAdapter.STATE_OFF, 2);
    }

    /**
     * Test: Start the Bluetooth services that are configured. Verify that each profile starts and
     * stops.
     */
    @Test
    public void testEnableDisableInterleaved() {
        int invocationNumber = mProfiles.length;
        for (int profile : mProfiles) {
            if (profile == BluetoothProfile.GATT) {
                // GattService is no longer a service to be start independently
                invocationNumber--;
                continue;
            }
            setProfileState(profile, BluetoothAdapter.STATE_ON);
            setProfileState(profile, BluetoothAdapter.STATE_OFF);
        }
        ArgumentCaptor<ProfileService> starts = ArgumentCaptor.forClass(ProfileService.class);
        ArgumentCaptor<ProfileService> stops = ArgumentCaptor.forClass(ProfileService.class);
        verify(mAdapterService, times(invocationNumber))
                .onProfileServiceStateChanged(starts.capture(), eq(BluetoothAdapter.STATE_ON));
        verify(mAdapterService, times(invocationNumber))
                .onProfileServiceStateChanged(stops.capture(), eq(BluetoothAdapter.STATE_OFF));

        List<ProfileService> startedArguments = starts.getAllValues();
        List<ProfileService> stoppedArguments = stops.getAllValues();
        assertThat(startedArguments).hasSize(stoppedArguments.size());
        for (ProfileService service : startedArguments) {
            assertThat(stoppedArguments).contains(service);
            stoppedArguments.remove(service);
            assertThat(stoppedArguments).doesNotContain(service);
        }
    }

    /**
     * Test: Start and stop a single profile repeatedly. Verify that the profiles start and stop.
     */
    @Test
    public void testRepeatedEnableDisableSingly() {
        int profileNumber = 0;
        for (int profile : mProfiles) {
            for (int i = 0; i < NUM_REPEATS; i++) {
                setProfileState(profile, BluetoothAdapter.STATE_ON);
                ArgumentCaptor<ProfileService> start =
                        ArgumentCaptor.forClass(ProfileService.class);
                verify(mAdapterService, times(NUM_REPEATS * profileNumber + i + 1))
                        .onProfileServiceStateChanged(
                                start.capture(), eq(BluetoothAdapter.STATE_ON));
                setProfileState(profile, BluetoothAdapter.STATE_OFF);
                ArgumentCaptor<ProfileService> stop = ArgumentCaptor.forClass(ProfileService.class);
                verify(mAdapterService, times(NUM_REPEATS * profileNumber + i + 1))
                        .onProfileServiceStateChanged(
                                stop.capture(), eq(BluetoothAdapter.STATE_OFF));
                assertThat(start.getValue()).isEqualTo(stop.getValue());
            }
            profileNumber += 1;
        }
    }

    /**
     * Test: Start and stop a single profile repeatedly and verify that the profile services are
     * registered and unregistered accordingly.
     */
    @Test
    public void testProfileServiceRegisterUnregister() {
        int profileNumber = 0;
        for (int profile : mProfiles) {
            for (int i = 0; i < NUM_REPEATS; i++) {
                setProfileState(profile, BluetoothAdapter.STATE_ON);
                ArgumentCaptor<ProfileService> start =
                        ArgumentCaptor.forClass(ProfileService.class);
                verify(mAdapterService, times(NUM_REPEATS * profileNumber + i + 1))
                        .addProfile(start.capture());
                setProfileState(profile, BluetoothAdapter.STATE_OFF);
                ArgumentCaptor<ProfileService> stop = ArgumentCaptor.forClass(ProfileService.class);
                verify(mAdapterService, times(NUM_REPEATS * profileNumber + i + 1))
                        .removeProfile(stop.capture());
                assertThat(start.getValue()).isEqualTo(stop.getValue());
            }
            profileNumber += 1;
        }
    }

    /**
     * Test: Stop the Bluetooth profile services that are not started. Verify that the profile
     * service state is not changed.
     */
    @Test
    public void testDisable() {
        setAllProfilesState(BluetoothAdapter.STATE_OFF, 0);
    }
}
