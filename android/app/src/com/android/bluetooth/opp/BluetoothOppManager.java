/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.R;
import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides a simplified interface on top of other Bluetooth service layer components;
 * Also it handles some Opp application level variables. It's a singleton got from
 * BluetoothOppManager.getInstance(context);
 */
public class BluetoothOppManager {
    private static final String TAG = BluetoothOppManager.class.getSimpleName();

    @VisibleForTesting static final String OPP_PREFERENCE_FILE = "OPPMGR";

    private static final String SENDING_FLAG = "SENDINGFLAG";
    private static final String MIME_TYPE = "MIMETYPE";
    private static final String FILE_URI = "FILE_URI";
    private static final String MIME_TYPE_MULTIPLE = "MIMETYPE_MULTIPLE";
    private static final String FILE_URIS = "FILE_URIS";
    private static final String MULTIPLE_FLAG = "MULTIPLE_FLAG";
    private static final String ARRAYLIST_ITEM_SEPARATOR = ";";

    @VisibleForTesting static final int ALLOWED_INSERT_SHARE_THREAD_NUMBER = 3;

    private static final Object INSTANCE_LOCK = new Object();

    @GuardedBy("INSTANCE_LOCK")
    private static BluetoothOppManager sInstance;

    private final Context mContext;
    private final BluetoothAdapter mAdapter;

    public boolean mSendingFlag; // used to continue sending process after received a ENABLED_ACTION
    public boolean mMultipleFlag;

    @VisibleForTesting String mMimeTypeOfSendingFile;
    @VisibleForTesting String mUriOfSendingFile;
    @VisibleForTesting String mMimeTypeOfSendingFiles;
    @VisibleForTesting List<Uri> mUrisOfSendingFiles;

    private int mInsertShareThreadNum = 0;
    private boolean mIsHandoverInitiated;
    private int mFileNumInBatch;

    /** Get singleton instance. */
    public static BluetoothOppManager getInstance(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new BluetoothOppManager(context);
            }
            return sInstance;
        }
    }

    /** Set Singleton instance. Intended for testing purpose */
    @VisibleForTesting
    static void setInstanceForTesting(BluetoothOppManager instance) {
        synchronized (INSTANCE_LOCK) {
            sInstance = instance;
        }
    }

    BluetoothOppManager(Context context) {
        mContext = context;
        mAdapter = mContext.getSystemService(BluetoothManager.class).getAdapter();

        restoreApplicationData(); // Restore data from preference
    }

    /** Restore data from preference */
    private void restoreApplicationData() {
        SharedPreferences settings = mContext.getSharedPreferences(OPP_PREFERENCE_FILE, 0);

        // All member vars are not initialized till now
        mSendingFlag = settings.getBoolean(SENDING_FLAG, false);
        mMimeTypeOfSendingFile = settings.getString(MIME_TYPE, null);
        mUriOfSendingFile = settings.getString(FILE_URI, null);
        mMimeTypeOfSendingFiles = settings.getString(MIME_TYPE_MULTIPLE, null);
        mMultipleFlag = settings.getBoolean(MULTIPLE_FLAG, false);

        Log.v(
                TAG,
                "restoreApplicationData! "
                        + mSendingFlag
                        + mMultipleFlag
                        + mMimeTypeOfSendingFile
                        + mUriOfSendingFile);

        String strUris = settings.getString(FILE_URIS, null);
        mUrisOfSendingFiles = new ArrayList<Uri>();
        if (strUris != null) {
            String[] splitUri = strUris.split(ARRAYLIST_ITEM_SEPARATOR);
            for (int i = 0; i < splitUri.length; i++) {
                mUrisOfSendingFiles.add(Uri.parse(splitUri[i]));
                Log.v(TAG, "Uri in batch:  " + Uri.parse(splitUri[i]));
            }
        }

        mContext.getSharedPreferences(OPP_PREFERENCE_FILE, 0).edit().clear().apply();
    }

    /** Save application data to preference, need restore these data when service restart */
    private void storeApplicationData() {
        SharedPreferences.Editor editor =
                mContext.getSharedPreferences(OPP_PREFERENCE_FILE, 0).edit();
        editor.putBoolean(SENDING_FLAG, mSendingFlag);
        editor.putBoolean(MULTIPLE_FLAG, mMultipleFlag);
        if (mMultipleFlag) {
            editor.putString(MIME_TYPE_MULTIPLE, mMimeTypeOfSendingFiles);
            StringBuilder sb = new StringBuilder();
            for (int i = 0, count = mUrisOfSendingFiles.size(); i < count; i++) {
                Uri uriContent = mUrisOfSendingFiles.get(i);
                sb.append(uriContent);
                sb.append(ARRAYLIST_ITEM_SEPARATOR);
            }
            String strUris = sb.toString();
            editor.putString(FILE_URIS, strUris);

            editor.remove(MIME_TYPE);
            editor.remove(FILE_URI);
        } else {
            editor.putString(MIME_TYPE, mMimeTypeOfSendingFile);
            editor.putString(FILE_URI, mUriOfSendingFile);

            editor.remove(MIME_TYPE_MULTIPLE);
            editor.remove(FILE_URIS);
        }
        editor.apply();
        Log.v(TAG, "Application data stored to SharedPreference! ");
    }

    public void saveSendingFileInfo(
            String mimeType, String uriString, boolean isHandover, boolean fromExternal)
            throws IllegalArgumentException {
        synchronized (BluetoothOppManager.this) {
            mMultipleFlag = false;
            mMimeTypeOfSendingFile = mimeType;
            mIsHandoverInitiated = isHandover;
            Uri uri = Uri.parse(uriString);
            BluetoothOppSendFileInfo sendFileInfo =
                    BluetoothOppSendFileInfo.generateFileInfo(
                            mContext, uri, mimeType, fromExternal);
            uri = BluetoothOppUtility.generateUri(uri, sendFileInfo);
            BluetoothOppUtility.putSendFileInfo(uri, sendFileInfo);
            mUriOfSendingFile = uri.toString();
            storeApplicationData();
        }
    }

    public void saveSendingFileInfo(
            String mimeType, List<Uri> uris, boolean isHandover, boolean fromExternal)
            throws IllegalArgumentException {
        synchronized (BluetoothOppManager.this) {
            mMultipleFlag = true;
            mMimeTypeOfSendingFiles = mimeType;
            mUrisOfSendingFiles = new ArrayList<Uri>();
            mIsHandoverInitiated = isHandover;
            for (Uri uri : uris) {
                BluetoothOppSendFileInfo sendFileInfo =
                        BluetoothOppSendFileInfo.generateFileInfo(
                                mContext, uri, mimeType, fromExternal);
                uri = BluetoothOppUtility.generateUri(uri, sendFileInfo);
                mUrisOfSendingFiles.add(uri);
                BluetoothOppUtility.putSendFileInfo(uri, sendFileInfo);
            }
            storeApplicationData();
        }
    }

    /**
     * Get the current status of Bluetooth hardware.
     *
     * @return true if Bluetooth enabled, false otherwise.
     */
    public boolean isEnabled() {
        if (mAdapter != null) {
            return BluetoothMethodProxy.getInstance().bluetoothAdapterIsEnabled(mAdapter);
        } else {
            Log.v(TAG, "BLUETOOTH_SERVICE is not available! ");
            return false;
        }
    }

    /** Enable Bluetooth hardware. */
    public void enableBluetooth() {
        if (mAdapter != null) {
            mAdapter.enable();
        }
    }

    /** Disable Bluetooth hardware. */
    public void disableBluetooth() {
        if (mAdapter != null) {
            mAdapter.disable();
        }
    }

    /** Get device name per bluetooth address. */
    public String getDeviceName(BluetoothDevice device) {
        String deviceName = null;

        if (device != null) {
            deviceName = device.getAlias();
            if (deviceName == null) {
                deviceName = device.getName();
            }
        }

        if (deviceName == null) {
            deviceName = mContext.getString(R.string.unknown_device);
        }

        return deviceName;
    }

    public int getBatchSize() {
        synchronized (BluetoothOppManager.this) {
            return mFileNumInBatch;
        }
    }

    /** Fork a thread to insert share info to db. */
    public void startTransfer(BluetoothDevice device) {
        Log.v(TAG, "Active InsertShareThread number is : " + mInsertShareThreadNum);
        InsertShareInfoThread insertThread;
        synchronized (BluetoothOppManager.this) {
            if (mInsertShareThreadNum > ALLOWED_INSERT_SHARE_THREAD_NUMBER) {
                Log.e(TAG, "Too many shares user triggered concurrently!");

                // Notice user
                Intent in = new Intent(mContext, BluetoothOppBtErrorActivity.class);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                in.putExtra("title", mContext.getString(R.string.enabling_progress_title));
                in.putExtra("content", mContext.getString(R.string.ErrorTooManyRequests));
                mContext.startActivity(in);

                return;
            }
            insertThread =
                    new InsertShareInfoThread(
                            device,
                            mMultipleFlag,
                            mMimeTypeOfSendingFile,
                            mUriOfSendingFile,
                            mMimeTypeOfSendingFiles,
                            mUrisOfSendingFiles,
                            mIsHandoverInitiated);
            if (mMultipleFlag) {
                mFileNumInBatch = mUrisOfSendingFiles.size();
            }
        }

        insertThread.start();
    }

    /**
     * Thread to insert share info to db. In multiple files (say 100 files) share case, the
     * inserting share info to db operation would be a time consuming operation, so need a thread to
     * handle it. This thread allows multiple instances to support below case: User select multiple
     * files to share to one device (say device 1), and then right away share to second device
     * (device 2), we need insert all these share info to db.
     */
    private class InsertShareInfoThread extends Thread {
        private final BluetoothDevice mRemoteDevice;

        private final String mTypeOfSingleFile;

        private final String mUri;

        private final String mTypeOfMultipleFiles;

        private final List<Uri> mUris;

        private final boolean mIsMultiple;

        private final boolean mIsHandoverInitiated;

        InsertShareInfoThread(
                BluetoothDevice device,
                boolean multiple,
                String typeOfSingleFile,
                String uri,
                String typeOfMultipleFiles,
                List<Uri> uris,
                boolean handoverInitiated) {
            super("Insert ShareInfo Thread");
            this.mRemoteDevice = device;
            this.mIsMultiple = multiple;
            this.mTypeOfSingleFile = typeOfSingleFile;
            this.mUri = uri;
            this.mTypeOfMultipleFiles = typeOfMultipleFiles;
            this.mUris = uris;
            this.mIsHandoverInitiated = handoverInitiated;

            synchronized (BluetoothOppManager.this) {
                mInsertShareThreadNum++;
            }

            Log.v(TAG, "Thread id is: " + this.getId());
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (mRemoteDevice == null) {
                Log.e(TAG, "Target bt device is null!");
                return;
            }
            final var adapterService = AdapterService.deprecatedGetAdapterService();
            if (mIsMultiple) {
                insertMultipleShare(adapterService);
            } else {
                insertSingleShare(adapterService);
            }
            synchronized (BluetoothOppManager.this) {
                mInsertShareThreadNum--;
            }
        }

        /** Insert multiple sending sessions to db, only used by Opp application. */
        private void insertMultipleShare(AdapterService adapterService) {
            int count = mUris.size();
            Long ts = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                Uri fileUri = mUris.get(i);
                ContentValues values = new ContentValues();
                values.put(BluetoothShare.URI, fileUri.toString());
                ContentResolver contentResolver = mContext.getContentResolver();
                fileUri = BluetoothOppUtility.originalUri(fileUri);
                String contentType = contentResolver.getType(fileUri);
                Log.v(TAG, "Got mimetype: " + contentType + "  Got uri: " + fileUri);
                if (TextUtils.isEmpty(contentType)) {
                    contentType = mTypeOfMultipleFiles;
                }

                values.put(BluetoothShare.MIMETYPE, contentType);
                values.put(
                        BluetoothShare.DESTINATION, adapterService.getBrEdrAddress(mRemoteDevice));
                values.put(BluetoothShare.TIMESTAMP, ts);
                if (mIsHandoverInitiated) {
                    values.put(
                            BluetoothShare.USER_CONFIRMATION,
                            BluetoothShare.USER_CONFIRMATION_HANDOVER_CONFIRMED);
                }
                final Uri contentUri =
                        BluetoothMethodProxy.getInstance()
                                .contentResolverInsert(
                                        mContext.getContentResolver(),
                                        BluetoothShare.CONTENT_URI,
                                        values);
                Log.v(
                        TAG,
                        "Insert contentUri: "
                                + contentUri
                                + "  to device: "
                                + getDeviceName(mRemoteDevice));
            }
        }

        /** Insert single sending session to db, only used by Opp application. */
        private void insertSingleShare(AdapterService adapterService) {
            ContentValues values = new ContentValues();
            values.put(BluetoothShare.URI, mUri);
            values.put(BluetoothShare.MIMETYPE, mTypeOfSingleFile);
            values.put(BluetoothShare.DESTINATION, adapterService.getBrEdrAddress(mRemoteDevice));
            if (mIsHandoverInitiated) {
                values.put(
                        BluetoothShare.USER_CONFIRMATION,
                        BluetoothShare.USER_CONFIRMATION_HANDOVER_CONFIRMED);
            }
            final Uri contentUri =
                    BluetoothMethodProxy.getInstance()
                            .contentResolverInsert(
                                    mContext.getContentResolver(),
                                    BluetoothShare.CONTENT_URI,
                                    values);
            Log.v(
                    TAG,
                    "Insert contentUri: "
                            + contentUri
                            + "  to device: "
                            + getDeviceName(mRemoteDevice));
        }
    }

    void cleanUpSendingFileInfo() {
        synchronized (BluetoothOppManager.this) {
            Log.v(TAG, "cleanUpSendingFileInfo: mMultipleFlag = " + mMultipleFlag);
            if (!mMultipleFlag && (mUriOfSendingFile != null)) {
                Uri uri = Uri.parse(mUriOfSendingFile);
                BluetoothOppUtility.closeSendFileInfo(uri);
            } else if (mUrisOfSendingFiles != null) {
                for (Uri uri : mUrisOfSendingFiles) {
                    BluetoothOppUtility.closeSendFileInfo(uri);
                }
            }
        }
    }
}
