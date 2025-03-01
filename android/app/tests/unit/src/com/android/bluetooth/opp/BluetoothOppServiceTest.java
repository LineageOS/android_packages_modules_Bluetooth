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
package com.android.bluetooth.opp;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.opp.BluetoothOppService.WHERE_INVISIBLE_UNCONFIRMED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.MatrixCursor;
import android.os.Looper;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothOppServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BluetoothMethodProxy mBluetoothMethodProxy;
    @Mock private AdapterService mAdapterService;

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private BluetoothOppService mService;

    @Before
    public void setUp() throws Exception {
        mockGetSystemService(
                mAdapterService, Context.NOTIFICATION_SERVICE, NotificationManager.class);
        doReturn(mTargetContext.getPackageName()).when(mAdapterService).getPackageName();
        doReturn(mTargetContext.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(mTargetContext.getResources()).when(mAdapterService).getResources();
        doReturn(mTargetContext.getContentResolver()).when(mAdapterService).getContentResolver();

        BluetoothMethodProxy.setInstanceForTesting(mBluetoothMethodProxy);

        // BluetoothOppService can create a UpdateThread, which will call
        // BluetoothOppNotification#updateNotification(), which in turn create a new
        // NotificationUpdateThread. Both threads may cause the tests to fail because they try to
        // access to ContentProvider in multiple places (ContentProvider might be disabled & there
        // is no mocking). Since we have no intention to test those threads, avoid running them
        doNothing().when(mBluetoothMethodProxy).threadStart(any());

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mService = new BluetoothOppService(mAdapterService);
        mService.setAvailable(true);

        // Wait until the initial trimDatabase operation is done.
        verify(mBluetoothMethodProxy, timeout(3_000))
                .contentResolverQuery(
                        any(),
                        eq(BluetoothShare.CONTENT_URI),
                        eq(new String[] {BluetoothShare._ID}),
                        any(),
                        isNull(),
                        eq(BluetoothShare._ID));

        Mockito.clearInvocations(mBluetoothMethodProxy);
    }

    @After
    public void tearDown() throws Exception {
        // Since the update thread is not run (we mocked it), it will not clean itself on interrupt
        // (normally, the service will wait for the update thread to clean itself after
        // being interrupted). We clean it manually here
        mService.mUpdateThread = null;
        Thread updateNotificationThread = mService.mNotifier.mUpdateNotificationThread;
        if (updateNotificationThread != null) {
            updateNotificationThread.join();
        }

        BluetoothMethodProxy.setInstanceForTesting(null);
        mService.cleanup();
    }

    @Test
    public void testInitialize() {
        assertThat(BluetoothOppService.getBluetoothOppService()).isNotNull();
    }

    @Test
    public void deleteShare_deleteShareAndCorrespondingBatch() {
        int infoTimestamp = 123456789;
        int infoTimestamp2 = 123489;

        BluetoothOppShareInfo shareInfo =
                new BluetoothOppShareInfo(
                        1, // id
                        null, // Uri,
                        "hint",
                        "filename",
                        "mimetype",
                        0, // direction
                        "AA:BB:CC:DD:EE:FF", // destination
                        0, // visibility,
                        0, // confirm
                        0, // status
                        0, // totalBytes
                        0, // currentBytes
                        infoTimestamp,
                        false); // mediaScanned
        BluetoothOppShareInfo shareInfo2 =
                new BluetoothOppShareInfo(
                        1, // id
                        null, // Uri,
                        "hint",
                        "filename",
                        "mimetype",
                        0, // direction
                        "00:11:22:33:44:55", // destination
                        0, // visibility,
                        0, // confirm
                        0, // status
                        0, // totalBytes
                        0, // currentBytes
                        infoTimestamp2,
                        false); // mediaScanned

        mService.mShares.clear();
        mService.mShares.add(shareInfo);
        mService.mShares.add(shareInfo2);

        // batch1 will be removed
        BluetoothOppBatch batch1 = new BluetoothOppBatch(mService, shareInfo);
        BluetoothOppBatch batch2 = new BluetoothOppBatch(mService, shareInfo2);
        batch2.mStatus = Constants.BATCH_STATUS_FINISHED;
        mService.mBatches.clear();
        mService.mBatches.add(batch1);
        mService.mBatches.add(batch2);

        mService.deleteShare(0);

        assertThat(mService.mShares).containsExactly(shareInfo2);
        assertThat(mService.mBatches).containsExactly(batch2);
    }

    @Test
    public void dump_shouldNotThrow() {
        BluetoothOppShareInfo info = mock(BluetoothOppShareInfo.class);

        mService.mShares.add(info);

        // should not throw
        mService.dump(new StringBuilder());
    }

    @Test
    public void trimDatabase_trimsOldOrInvisibleRecords() {
        ContentResolver contentResolver = mTargetContext.getContentResolver();

        doReturn(1 /* any int is Ok */)
                .when(mBluetoothMethodProxy)
                .contentResolverDelete(
                        eq(contentResolver), eq(BluetoothShare.CONTENT_URI), anyString(), any());

        MatrixCursor cursor = new MatrixCursor(new String[] {BluetoothShare._ID}, 500);
        for (long i = 0; i < Constants.MAX_RECORDS_IN_DATABASE + 20; i++) {
            cursor.addRow(new Object[] {i});
        }

        doReturn(cursor)
                .when(mBluetoothMethodProxy)
                .contentResolverQuery(
                        eq(contentResolver),
                        eq(BluetoothShare.CONTENT_URI),
                        any(),
                        any(),
                        any(),
                        any());

        BluetoothOppService.trimDatabase(contentResolver);

        // check trimmed invisible records
        verify(mBluetoothMethodProxy)
                .contentResolverDelete(
                        eq(contentResolver),
                        eq(BluetoothShare.CONTENT_URI),
                        eq(WHERE_INVISIBLE_UNCONFIRMED),
                        any());

        // check trimmed old records
        verify(mBluetoothMethodProxy)
                .contentResolverDelete(
                        eq(contentResolver),
                        eq(BluetoothShare.CONTENT_URI),
                        eq(BluetoothShare._ID + " < " + 20),
                        any());
    }
}
