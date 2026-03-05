/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevicePicker;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.OpenableColumns;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.tests.bluetooth.MockitoRule;

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
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test cases for {@link BluetoothOppLauncherActivity}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothOppLauncherActivityTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private BluetoothMethodProxy mMethodProxy;
    @Mock private BluetoothOppManager mBluetoothOppManager;
    @Mock private ContentResolver mMockContentResolver;
    @Mock private Cursor mMockCursor;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private PackageManager mPackageManager;

    private static final String CONTENT_TYPE = "image/png";
    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();

    private Intent mIntent;

    @BeforeClass
    public static void setUpClass() throws Exception {
        BluetoothOppTestUtils.enableActivity(BluetoothOppLauncherActivity.class, true, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppReceiver.class, true, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnableActivity.class, true, sContext);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        BluetoothOppTestUtils.enableActivity(BluetoothOppLauncherActivity.class, false, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppReceiver.class, false, sContext);
        BluetoothOppTestUtils.enableActivity(BluetoothOppBtEnableActivity.class, false, sContext);
    }

    @Before
    public void setUp() {
        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);
        BluetoothOppManager.setInstanceForTesting(mBluetoothOppManager);
        doReturn(mPackageManager).when(mMethodProxy).getPackageManager(any());
        doReturn(mMockContentResolver).when(mMethodProxy).getContentResolver(any());
        MetricsLogger.setInstanceForTesting(mMetricsLogger);

        mIntent = new Intent();
        mIntent.setClass(sContext, BluetoothOppLauncherActivity.class);

        Intents.init();
    }

    @After
    public void tearDown() {
        Intents.release();
        BluetoothMethodProxy.setInstanceForTesting(null);
        BluetoothOppManager.setInstanceForTesting(null);
        Mockito.clearAllCaches();
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

    private static Cursor createMockCursor(long size) {
        Cursor mockCursor = Mockito.mock(Cursor.class);
        doReturn(true).when(mockCursor).moveToFirst();
        doReturn(0).when(mockCursor).getColumnIndexOrThrow(OpenableColumns.SIZE);
        doReturn(false).when(mockCursor).isNull(0);
        doReturn(size).when(mockCursor).getLong(0);
        return mockCursor;
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
    @EnableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @EnableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @DisableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @DisableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @EnableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @EnableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @EnableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @DisableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @DisableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    @DisableFlags(Flags.FLAG_OPP_CHECK_CONTENT_URI_PERMISSIONS)
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
    public void onCreate_withActionSend_grantUriPermissionToNearbyComponent() {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        doReturn("com.example/.BComponent")
                .when(mMethodProxy)
                .settingsSecureGetString(any(), eq("nearby_sharing_component"));
        String uriString = "content://test.provider/1";

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

    @Test
    public void onCreate_withActionSend_logMetrics() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());

        String uriString = "content://test.provider/1";
        Uri uri = Uri.parse(uriString);
        int testUid = 12345;
        long testFileSize = 2048L;
        int expectedSizeCategory = BluetoothOppUtility.categorizeFileSize(testFileSize);

        doReturn(mMockCursor).when(mMockContentResolver).query(eq(uri), any(), any(), any(), any());
        doReturn(true).when(mMockCursor).moveToFirst();
        doReturn(0).when(mMockCursor).getColumnIndexOrThrow(OpenableColumns.SIZE);
        doReturn(false).when(mMockCursor).isNull(0);
        doReturn(testFileSize).when(mMockCursor).getLong(0);

        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.applicationInfo = new ApplicationInfo();
        providerInfo.applicationInfo.uid = testUid;
        doReturn(providerInfo)
                .when(mPackageManager)
                .resolveContentProvider(eq("test.provider"), eq(0));

        ActivityScenario.launch(createSendIntent(uriString));

        verify(mMetricsLogger)
                .logBluetoothOppLauncherCreated(eq(testUid), eq(1), eq(expectedSizeCategory));
    }

    @Test
    public void onCreate_withActionSendMultiple_logMetrics() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());

        Uri uri1 = Uri.parse("content://test.provider/1");
        Uri uri2 = Uri.parse("content://test.provider/2");
        Uri uri3 = Uri.parse("content://test.provider/3");
        List<Uri> uriList = Arrays.asList(uri1, uri2, uri3);

        long fileSize1 = 1024L;
        long fileSize2 = 2048L;
        long fileSize3 = 4096L;
        long totalSize = fileSize1 + fileSize2 + fileSize3;
        int expectedSizeCategory = BluetoothOppUtility.categorizeFileSize(totalSize);

        doReturn(createMockCursor(fileSize1))
                .when(mMockContentResolver)
                .query(eq(uri1), any(), any(), any(), any());
        doReturn(createMockCursor(fileSize2))
                .when(mMockContentResolver)
                .query(eq(uri2), any(), any(), any(), any());
        doReturn(createMockCursor(fileSize3))
                .when(mMockContentResolver)
                .query(eq(uri3), any(), any(), any(), any());

        int testUid = 54321;
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.applicationInfo = new ApplicationInfo();
        providerInfo.applicationInfo.uid = testUid;
        doReturn(providerInfo)
                .when(mPackageManager)
                .resolveContentProvider(eq("test.provider"), eq(0));

        ActivityScenario.launch(createSendMultipleIntent(new ArrayList<>(uriList)));

        verify(mMetricsLogger)
                .logBluetoothOppLauncherCreated(
                        eq(testUid), eq(uriList.size()), eq(expectedSizeCategory));
    }

    @Test
    public void onCreate_withFileUri_logMetricsWithDefaultUid() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "file:///storage/emulated/0/image.png";

        ActivityScenario.launch(createSendIntent(uriString));

        verify(mPackageManager, never()).resolveContentProvider(anyString(), anyInt());
        verify(mMetricsLogger)
                .logBluetoothOppLauncherCreated(
                        eq(-1), eq(1), eq(BluetoothOppUtility.categorizeFileSize(0)));
    }

    @Test
    public void onCreate_resolveContentProviderFails_logMetricsWithDefaultUid() throws Exception {
        doReturn(true).when(mMethodProxy).bluetoothAdapterIsEnabled(any());
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mMethodProxy)
                .componentCallerCheckContentUriPermission(any(), any(), anyInt());
        String uriString = "content://test.provider/1";
        Uri uri = Uri.parse(uriString);
        long testFileSize = 512L;
        int expectedSizeCategory = BluetoothOppUtility.categorizeFileSize(testFileSize);

        doReturn(createMockCursor(testFileSize))
                .when(mMockContentResolver)
                .query(eq(uri), any(), any(), any(), any());
        doReturn(null).when(mPackageManager).resolveContentProvider(eq("test.provider"), eq(0));

        ActivityScenario.launch(createSendIntent(uriString));

        verify(mMetricsLogger)
                .logBluetoothOppLauncherCreated(eq(-1), eq(1), eq(expectedSizeCategory));
    }

    @Test
    public void onCreate_withActionSendMultiple_withNullList_doesNotLogMetrics() {
        Intent intent =
                new Intent(Intent.ACTION_SEND_MULTIPLE)
                        .setClass(sContext, BluetoothOppLauncherActivity.class)
                        .setType(CONTENT_TYPE)
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, null);

        ActivityScenario.launch(intent);

        verify(mMetricsLogger, never())
                .logBluetoothOppLauncherCreated(anyInt(), anyInt(), anyInt());
    }
}
