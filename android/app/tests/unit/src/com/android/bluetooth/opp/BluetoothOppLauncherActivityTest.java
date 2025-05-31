/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.bluetooth.opp;

import static android.platform.test.flag.junit.DeviceFlagsValueProvider.createCheckFlagsRule;

import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevicePicker;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.provider.Settings;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test cases for {@link BluetoothOppLauncherActivity}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothOppLauncherActivityTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final CheckFlagsRule mCheckFlagsRule = createCheckFlagsRule();

    // Activity tests can sometimes flaky because of external factors like system dialog, etc.
    // making the expected Espresso's root not focused or the activity doesn't show up.
    // Add retry rule to resolve this problem.
    @Rule public TestUtils.RetryTestRule mRetryTestRule = new TestUtils.RetryTestRule();

    @Mock private BluetoothMethodProxy mMethodProxy;
    @Mock private BluetoothOppManager mBluetoothOppManager;

    private static final String CONTENT_TYPE = "image/png";
    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();

    private Intent mIntent;

    @BeforeClass
    public static void setUpClass() {
        BluetoothOppTestUtils.enableActivity(BluetoothOppLauncherActivity.class, true, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppReceiver.class, true, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnableActivity.class, true, sContext);
    }

    @AfterClass
    public static void tearDownClass() {
        BluetoothOppTestUtils.enableActivity(BluetoothOppLauncherActivity.class, false, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppReceiver.class, false, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnableActivity.class, false, sContext);
    }

    @Before
    public void setUp() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);
        BluetoothOppManager.setInstanceForTesting(mBluetoothOppManager);

        mIntent = new Intent();
        mIntent.setClass(sContext, BluetoothOppLauncherActivity.class);

        Intents.init();
        TestUtils.setUpUiTest();
    }

    @After
    public void tearDown() throws Exception {
        Intents.release();
        TestUtils.tearDownUiTest();
        BluetoothMethodProxy.setInstanceForTesting(null);
        BluetoothOppManager.setInstanceForTesting(null);
    }

    private static Intent createSendIntent(String uriString) {
        return new Intent(Intent.ACTION_SEND)
                .setClass(sContext, BluetoothOppLauncherActivity.class)
                .setType(CONTENT_TYPE)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString));
    }

    private static Intent createSendMultipleIntent(List<Uri> uriList) {
        return new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setClass(sContext, BluetoothOppLauncherActivity.class)
                .setType(CONTENT_TYPE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uriList));
    }

    @Test
    public void onCreate_withNoAction_returnImmediately() {
        try (ActivityScenario<BluetoothOppLauncherActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void onCreate_withActionSend_withoutMetadata_finishImmediately() {
        mIntent.setAction(Intent.ACTION_SEND);
        try (ActivityScenario<BluetoothOppLauncherActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void onCreate_withActionSendMultiple_withoutMetadata_finishImmediately() {
        mIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        try (ActivityScenario<BluetoothOppLauncherActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSend_checkEnabled_noPermission_doesNotSaveFileInfo()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "content://test.provider/1";

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendIntent(uriString));

        verify(mBluetoothOppManager, never())
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriString),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSend_checkEnabled_hasPermission_savesFileInfo()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "content://test.provider/1";

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendIntent(uriString));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriString),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSend_checkNotEnabled_noPermission_savesFileInfo()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "content://test.provider/1";

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendIntent(uriString));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriString),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSend_checkNotEnabled_hasPermission_savesFileInfo()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "content://test.provider/1";

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendIntent(uriString));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriString),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSendMultiple_checkEnabled_noPermission_doesNotSaveFileInfos()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        List<Uri> uriList =
                Arrays.asList(
                        Uri.parse("content://test.provider/1"),
                        Uri.parse("content://test.provider/2"));

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendMultipleIntent(uriList));

        verify(mBluetoothOppManager, never())
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriList),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSendMultiple_checkEnabled_hasPermission_savesFileInfos()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        List<Uri> uriList =
                Arrays.asList(
                        Uri.parse("content://test.provider/1"),
                        Uri.parse("content://test.provider/2"));

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendMultipleIntent(uriList));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriList),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void
            onCreate_withActionSendMultiple_checkEnabled_partialPermission_savesPermittedFileInfo()
                    throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        List<Uri> uriList =
                Arrays.asList(
                        Uri.parse("content://test.provider/1"),
                        Uri.parse("content://test.provider/2"));

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendMultipleIntent(uriList));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(Arrays.asList(Uri.parse("content://test.provider/1"))),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSendMultiple_checkNotEnabled_noPermission_savesFileInfos()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        List<Uri> uriList =
                Arrays.asList(
                        Uri.parse("content://test.provider/1"),
                        Uri.parse("content://test.provider/2"));

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendMultipleIntent(uriList));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriList),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSendMultiple_checkNotEnabled_hasPermission_savesFileInfos()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        List<Uri> uriList =
                Arrays.asList(
                        Uri.parse("content://test.provider/1"),
                        Uri.parse("content://test.provider/2"));

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendMultipleIntent(uriList));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriList),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
    public void onCreate_withActionSendMultiple_checkNotEnabled_partialPermission_savesFileInfos()
            throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        List<Uri> uriList =
                Arrays.asList(
                        Uri.parse("content://test.provider/1"),
                        Uri.parse("content://test.provider/2"));

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendMultipleIntent(uriList));

        verify(mBluetoothOppManager)
                .saveSendingFileInfo(
                        eq(CONTENT_TYPE), eq(uriList),
                        anyBoolean() /* isHandover */, anyBoolean() /* fromExternal */);
    }

    @Test
    public void onCreate_withActionOpen_sendBroadcast() throws Exception {
        mIntent.setAction(Constants.ACTION_OPEN);
        mIntent.setData(Uri.EMPTY);
        ActivityScenario.launch(mIntent);
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);

        verify(mMethodProxy).contextSendBroadcast(any(), argument.capture());

        assertThat(argument.getValue().getAction()).isEqualTo(Constants.ACTION_OPEN);
        assertThat(argument.getValue().getComponent().getClassName())
                .isEqualTo(BluetoothOppReceiver.class.getName());
        assertThat(argument.getValue().getData()).isEqualTo(Uri.EMPTY);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SEND_OPP_DEVICE_PICKER_EXTRA_INTENT)
    public void onCreate_withActionSend_grantUriPermissionToNearbyComponent() {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "content://test.provider/1";
        Settings.Secure.putString(
                sContext.getContentResolver(),
                "nearby_sharing_component",
                "com.example/.BComponent");

        ActivityScenario<BluetoothOppLauncherActivity> unused =
                ActivityScenario.launch(createSendIntent(uriString));

        verify(mMethodProxy)
                .grantUriPermission(
                        any(),
                        eq("com.example"),
                        eq(Uri.parse(uriString)),
                        eq(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    @Ignore("b/263724420")
    @Test
    public void launchDevicePicker_bluetoothNotEnabled_launchEnableActivity() throws Exception {
        doReturn(false).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        // Unsupported action, the activity will stay without being finished right the way
        mIntent.setAction("unsupported-action");
        ActivityScenario<BluetoothOppLauncherActivity> scenario = ActivityScenario.launch(mIntent);

        scenario.onActivity(BluetoothOppLauncherActivity::launchDevicePicker);

        intended(hasComponent(BluetoothOppBtEnableActivity.class.getName()));
    }

    @Ignore("b/263724420")
    @Test
    public void launchDevicePicker_bluetoothEnabled_launchActivity() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        // Unsupported action, the activity will stay without being finished right the way
        mIntent.setAction("unsupported-action");
        ActivityScenario<BluetoothOppLauncherActivity> scenario = ActivityScenario.launch(mIntent);

        scenario.onActivity(BluetoothOppLauncherActivity::launchDevicePicker);

        intended(hasAction(BluetoothDevicePicker.ACTION_LAUNCH));
    }

    @Test
    public void createFileForSharedContent_returnFile() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        // Unsupported action, the activity will stay without being finished right the way
        mIntent.setAction("unsupported-action");
        ActivityScenario<BluetoothOppLauncherActivity> scenario = ActivityScenario.launch(mIntent);

        final Uri[] fileUri = new Uri[1];
        final String shareContent =
                "\na < b & c > a string to trigger pattern match with url: \r"
                        + "www.google.com, phone number: +821023456798, and email: abc@test.com";
        scenario.onActivity(
                activity -> {
                    fileUri[0] = activity.createFileForSharedContent(activity, shareContent);
                });
        assertThat(fileUri[0].toString().endsWith(".html")).isTrue();

        File file = new File(fileUri[0].getPath());
        // new file is in html format that include the shared content, so length should increase
        assertThat(file.length()).isGreaterThan(shareContent.length());
    }

    @Ignore("b/263754734")
    @Test
    public void sendFileInfo_finishImmediately() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        // Unsupported action, the activity will stay without being finished right the way
        mIntent.setAction("unsupported-action");
        doThrow(new IllegalArgumentException())
                .when(mBluetoothOppManager)
                .saveSendingFileInfo(any(), any(String.class), any(), any());
        try (ActivityScenario<BluetoothOppLauncherActivity> activityScenario =
                ActivityScenario.launch(mIntent)) {
            activityScenario.onActivity(
                    activity -> {
                        activity.sendFileInfo("text/plain", "content:///abc.txt", false, false);
                    });
            assertThat(activityScenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
        }
    }
}
