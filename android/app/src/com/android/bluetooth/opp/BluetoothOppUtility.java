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

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.icu.text.MessageFormat;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.util.EventLog;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.R;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** This class has some utilities for Opp application; */
public class BluetoothOppUtility {
    private static final String TAG = BluetoothOppUtility.class.getSimpleName();

    // TODO(b/398120192): use API instead of a hardcode string.
    private static final String NEARBY_SHARING_COMPONENT = "nearby_sharing_component";

    /** Whether the device has the "nosdcard" characteristic, or null if not-yet-known. */
    private static Boolean sNoSdCard = null;

    @VisibleForTesting
    static final ConcurrentHashMap<Uri, BluetoothOppSendFileInfo> sSendFileMap =
            new ConcurrentHashMap<Uri, BluetoothOppSendFileInfo>();

    private BluetoothOppUtility() {}

    public static boolean isBluetoothShareUri(Uri uri) {
        if (uri.toString().startsWith(BluetoothShare.CONTENT_URI.toString())
                && !uri.getAuthority().equals(BluetoothShare.CONTENT_URI.getAuthority())) {
            EventLog.writeEvent(0x534e4554, "225880741", -1, "");
        }
        return Objects.equals(uri.getAuthority(), BluetoothShare.CONTENT_URI.getAuthority());
    }

    public static BluetoothOppTransferInfo queryRecord(Context context, Uri uri) {
        BluetoothOppTransferInfo info = new BluetoothOppTransferInfo();
        Cursor cursor =
                BluetoothMethodProxy.getInstance()
                        .contentResolverQuery(
                                context.getContentResolver(), uri, null, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                fillRecord(context, cursor, info);
            }
            cursor.close();
        } else {
            info = null;
            Log.v(TAG, "BluetoothOppManager Error: not got data from db for uri:" + uri);
        }
        return info;
    }

    public static void fillRecord(Context context, Cursor cursor, BluetoothOppTransferInfo info) {
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        info.mID = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare._ID));
        info.mStatus = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.STATUS));
        info.mDirection = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.DIRECTION));
        info.mTotalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.TOTAL_BYTES));
        info.mCurrentBytes =
                cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.CURRENT_BYTES));
        info.mTimeStamp = cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP));
        info.mDestAddr = cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.DESTINATION));

        info.mFileName = cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare._DATA));
        if (info.mFileName == null) {
            info.mFileName =
                    cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.FILENAME_HINT));
        }
        if (info.mFileName == null) {
            info.mFileName = context.getString(R.string.unknown_file);
        }

        info.mFileUri = cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.URI));

        if (info.mFileUri != null) {
            Uri u = Uri.parse(info.mFileUri);
            info.mFileType = context.getContentResolver().getType(u);
        } else {
            Uri u = Uri.parse(info.mFileName);
            info.mFileType = context.getContentResolver().getType(u);
        }
        if (info.mFileType == null) {
            info.mFileType =
                    cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.MIMETYPE));
        }

        // Ideally BluetoothDevice object should be retrieved from the AdapterService but address
        // type is not necessary for reading the device name, so getting it from the
        // BluetoothAdapter is fine.
        BluetoothDevice remoteDevice = adapter.getRemoteDevice(info.mDestAddr);
        info.mDeviceName = BluetoothOppManager.getInstance(context).getDeviceName(remoteDevice);

        int confirmationType =
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.USER_CONFIRMATION));
        info.mHandoverInitiated =
                confirmationType == BluetoothShare.USER_CONFIRMATION_HANDOVER_CONFIRMED;

        Log.v(TAG, "Get data from db:" + info.mFileName + info.mFileType + info.mDestAddr);
    }

    /**
     * Open the received file with appropriate application, if can not find application to handle,
     * display error dialog.
     */
    public static void openReceivedFile(
            Context context, String fileName, String mimetype, Long timeStamp, Uri uri) {
        if (fileName == null || mimetype == null) {
            Log.e(TAG, "ERROR: Para fileName ==null, or mimetype == null");
            return;
        }

        if (!isBluetoothShareUri(uri)) {
            Log.e(TAG, "Trying to open a file that wasn't transferred over Bluetooth");
            return;
        }

        Uri path = null;
        Cursor metadataCursor =
                BluetoothMethodProxy.getInstance()
                        .contentResolverQuery(
                                context.getContentResolver(),
                                uri,
                                new String[] {BluetoothShare.URI},
                                null,
                                null,
                                null);
        if (metadataCursor != null) {
            try {
                if (metadataCursor.moveToFirst()) {
                    path = Uri.parse(metadataCursor.getString(0));
                }
            } finally {
                metadataCursor.close();
            }
        }

        if (path == null) {
            Log.e(TAG, "file uri not exist");
            return;
        }

        if (!fileExists(context, path)) {
            Intent in = new Intent(context, BluetoothOppBtErrorActivity.class);
            in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            in.putExtra("title", context.getString(R.string.not_exist_file));
            in.putExtra("content", context.getString(R.string.not_exist_file_desc));
            context.startActivity(in);

            // Due to the file is not existing, delete related info in btopp db
            // to prevent this file from appearing in live folder
            Log.v(TAG, "This uri will be deleted: " + uri);
            BluetoothMethodProxy.getInstance()
                    .contentResolverDelete(context.getContentResolver(), uri, null, null);
            return;
        }

        if (isRecognizedFileType(context, path, mimetype)) {
            Intent activityIntent = new Intent(Intent.ACTION_VIEW);
            activityIntent.setDataAndType(
                    path.normalizeScheme(), Intent.normalizeMimeType(mimetype));
            activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            try {
                Log.v(TAG, "ACTION_VIEW intent sent out: " + path + " / " + mimetype);
                context.startActivity(activityIntent);
            } catch (ActivityNotFoundException ex) {
                Log.v(TAG, "no activity for handling ACTION_VIEW intent:  " + mimetype, ex);
            } catch (SecurityException se) {
                Log.e(TAG, "ACTION_VIEW intent failed due to a SecurityException:  " + se);
                return;
            }
        } else {
            Intent in = new Intent(context, BluetoothOppBtErrorActivity.class);
            in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            in.putExtra("title", context.getString(R.string.unknown_file));
            in.putExtra("content", context.getString(R.string.unknown_file_desc));
            context.startActivity(in);
        }
    }

    static boolean fileExists(Context context, Uri uri) {
        // Open a specific media item using ParcelFileDescriptor.
        ContentResolver resolver = context.getContentResolver();
        String readOnlyMode = "r";
        try (ParcelFileDescriptor unusedPfd =
                BluetoothMethodProxy.getInstance()
                        .contentResolverOpenFileDescriptor(resolver, uri, readOnlyMode)) {
            return true;
        } catch (IOException e) {
            Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
        }
        return false;
    }

    /** To judge if the file type supported (can be handled by some app) by phone system. */
    public static boolean isRecognizedFileType(Context context, Uri fileUri, String mimetype) {
        boolean ret = true;

        Log.d(TAG, "RecognizedFileType() fileUri: " + fileUri + " mimetype: " + mimetype);

        Intent mimetypeIntent = new Intent(Intent.ACTION_VIEW);
        mimetypeIntent.setDataAndType(
                fileUri.normalizeScheme(), Intent.normalizeMimeType(mimetype));
        List<ResolveInfo> list =
                context.getPackageManager()
                        .queryIntentActivities(mimetypeIntent, PackageManager.MATCH_DEFAULT_ONLY);

        if (list.size() == 0) {
            Log.d(TAG, "NO application to handle MIME type " + mimetype);
            ret = false;
        }
        return ret;
    }

    /** update visibility to Hidden */
    public static void updateVisibilityToHidden(Context context, Uri uri) {
        ContentValues updateValues = new ContentValues();
        updateValues.put(BluetoothShare.VISIBILITY, BluetoothShare.VISIBILITY_HIDDEN);
        BluetoothMethodProxy.getInstance()
                .contentResolverUpdate(context.getContentResolver(), uri, updateValues, null, null);
    }

    /** Helper function to build the progress text. */
    public static String formatProgressText(long totalBytes, long currentBytes) {
        DecimalFormat df = new DecimalFormat("0%");
        df.setRoundingMode(RoundingMode.DOWN);
        double percent = 0.0;
        if (totalBytes > 0) {
            percent = currentBytes / (double) totalBytes;
        }
        return df.format(percent);
    }

    /** Helper function to build the result notification text content. */
    static String formatResultText(int countSuccess, int countUnsuccessful, Context context) {
        if (context == null) {
            return null;
        }
        Map<String, Object> mapUnsuccessful = new HashMap<>();
        mapUnsuccessful.put("count", countUnsuccessful);

        Map<String, Object> mapSuccess = new HashMap<>();
        mapSuccess.put("count", countSuccess);

        return new MessageFormat(
                        context.getResources()
                                .getString(
                                        R.string.noti_caption_success,
                                        new MessageFormat(
                                                        context.getResources()
                                                                .getString(
                                                                        R.string
                                                                                .noti_caption_unsuccessful),
                                                        Locale.getDefault())
                                                .format(mapUnsuccessful)),
                        Locale.getDefault())
                .format(mapSuccess);
    }

    /** Whether the device has the "nosdcard" characteristic or not. */
    public static boolean deviceHasNoSdCard() {
        if (sNoSdCard == null) {
            String characteristics = SystemProperties.get("ro.build.characteristics", "");
            sNoSdCard = Arrays.asList(characteristics).contains("nosdcard");
        }
        return sNoSdCard;
    }

    /** Get status description according to status code. */
    public static String getStatusDescription(Context context, int statusCode, String deviceName) {
        String ret;
        if (statusCode == BluetoothShare.STATUS_PENDING) {
            ret = context.getString(R.string.status_pending);
        } else if (statusCode == BluetoothShare.STATUS_RUNNING) {
            ret = context.getString(R.string.status_running);
        } else if (statusCode == BluetoothShare.STATUS_SUCCESS) {
            ret = context.getString(R.string.status_success);
        } else if (statusCode == BluetoothShare.STATUS_NOT_ACCEPTABLE) {
            ret = context.getString(R.string.status_not_accept);
        } else if (statusCode == BluetoothShare.STATUS_FORBIDDEN) {
            ret = context.getString(R.string.status_forbidden);
        } else if (statusCode == BluetoothShare.STATUS_CANCELED) {
            ret = context.getString(R.string.status_canceled);
        } else if (statusCode == BluetoothShare.STATUS_FILE_ERROR) {
            ret = context.getString(R.string.status_file_error);
        } else if (statusCode == BluetoothShare.STATUS_ERROR_NO_SDCARD) {
            int id =
                    deviceHasNoSdCard()
                            ? R.string.status_no_sd_card_nosdcard
                            : R.string.status_no_sd_card_default;
            ret = context.getString(id);
        } else if (statusCode == BluetoothShare.STATUS_CONNECTION_ERROR) {
            ret = context.getString(R.string.status_connection_error);
        } else if (statusCode == BluetoothShare.STATUS_ERROR_SDCARD_FULL) {
            int id = deviceHasNoSdCard() ? R.string.bt_sm_2_1_nosdcard : R.string.bt_sm_2_1_default;
            ret = context.getString(id);
        } else if ((statusCode == BluetoothShare.STATUS_BAD_REQUEST)
                || (statusCode == BluetoothShare.STATUS_LENGTH_REQUIRED)
                || (statusCode == BluetoothShare.STATUS_PRECONDITION_FAILED)
                || (statusCode == BluetoothShare.STATUS_UNHANDLED_OBEX_CODE)
                || (statusCode == BluetoothShare.STATUS_OBEX_DATA_ERROR)) {
            ret = context.getString(R.string.status_protocol_error);
        } else {
            ret = context.getString(R.string.status_unknown_error);
        }
        return ret;
    }

    /** Retry the failed transfer: Will insert a new transfer session to db */
    public static void retryTransfer(Context context, BluetoothOppTransferInfo transInfo) {
        ContentValues values = new ContentValues();
        values.put(BluetoothShare.URI, transInfo.mFileUri);
        values.put(BluetoothShare.MIMETYPE, transInfo.mFileType);
        values.put(BluetoothShare.DESTINATION, transInfo.mDestAddr);

        final Uri contentUri =
                context.getContentResolver().insert(BluetoothShare.CONTENT_URI, values);
        Log.v(TAG, "Insert contentUri: " + contentUri + "  to device: " + transInfo.mDeviceName);
    }

    static Uri originalUri(Uri uri) {
        String mUri = uri.toString();
        int atIndex = mUri.lastIndexOf("@");
        if (atIndex != -1) {
            mUri = mUri.substring(0, atIndex);
            uri = Uri.parse(mUri);
        }
        Log.v(TAG, "originalUri: " + uri);
        return uri;
    }

    static Uri generateUri(Uri uri, BluetoothOppSendFileInfo sendFileInfo) {
        String fileInfo = sendFileInfo.toString();
        int atIndex = fileInfo.lastIndexOf("@");
        fileInfo = fileInfo.substring(atIndex);
        uri = Uri.parse(uri + fileInfo);
        Log.v(TAG, "generateUri: " + uri);
        return uri;
    }

    static void putSendFileInfo(Uri uri, BluetoothOppSendFileInfo sendFileInfo) {
        Log.d(TAG, "putSendFileInfo: uri=" + uri + " sendFileInfo=" + sendFileInfo);
        if (sendFileInfo == BluetoothOppSendFileInfo.SEND_FILE_INFO_ERROR) {
            Log.e(TAG, "putSendFileInfo: bad sendFileInfo, URI: " + uri);
        }
        sSendFileMap.put(uri, sendFileInfo);
    }

    static BluetoothOppSendFileInfo getSendFileInfo(Uri uri) {
        Log.d(TAG, "getSendFileInfo: uri=" + uri);
        BluetoothOppSendFileInfo info = sSendFileMap.get(uri);
        return (info != null) ? info : BluetoothOppSendFileInfo.SEND_FILE_INFO_ERROR;
    }

    static void closeSendFileInfo(Uri uri) {
        Log.d(TAG, "closeSendFileInfo: uri=" + uri);
        BluetoothOppSendFileInfo info = sSendFileMap.remove(uri);
        if (info != null && info.mInputStream != null) {
            try {
                info.mInputStream.close();
            } catch (IOException ignored) {
                // Ignore
            }
        }
    }

    /**
     * Checks if the URI is in Environment.getExternalStorageDirectory() as it is the only directory
     * that is possibly readable by both the sender and the Bluetooth process.
     */
    static boolean isInExternalStorageDir(Uri uri) {
        if (!ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            Log.e(TAG, "Not a file URI: " + uri);
            return false;
        }

        if ("file".equals(uri.getScheme())) {
            String canonicalPath;
            try {
                canonicalPath = new File(uri.getPath()).getCanonicalPath();
            } catch (IOException e) {
                canonicalPath = uri.getPath();
            }
            File file = new File(canonicalPath);
            // if emulated
            if (Environment.isExternalStorageEmulated()) {
                // Gets legacy external storage path
                final String legacyPath = new File(System.getenv("EXTERNAL_STORAGE")).toString();
                // Splice in user-specific path when legacy path is found
                if (canonicalPath.startsWith(legacyPath)) {
                    file =
                            new File(
                                    Environment.getExternalStorageDirectory().toString(),
                                    canonicalPath.substring(legacyPath.length() + 1));
                }
            }
            return isSameOrSubDirectory(Environment.getExternalStorageDirectory(), file);
        }
        return isSameOrSubDirectory(
                Environment.getExternalStorageDirectory(), new File(uri.getPath()));
    }

    static boolean isForbiddenContent(Uri uri) {
        if ("com.android.bluetooth.map.MmsFileProvider".equals(uri.getHost())) {
            return true;
        }
        return false;
    }

    /**
     * Checks, whether the child directory is the same as, or a sub-directory of the base directory.
     * Neither base nor child should be null.
     */
    static boolean isSameOrSubDirectory(File base, File child) {
        try {
            base = base.getCanonicalFile();
            child = child.getCanonicalFile();
            File parentFile = child;
            while (parentFile != null) {
                if (base.equals(parentFile)) {
                    return true;
                }
                parentFile = parentFile.getParentFile();
            }
            return false;
        } catch (IOException ex) {
            Log.e(TAG, "Error while accessing file", ex);
            return false;
        }
    }

    protected static void cancelNotification(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.cancel(BluetoothOppNotification.NOTIFICATION_ID_PROGRESS);
    }

    /** Grants uri read permission to nearby sharing component. */
    static void grantPermissionToNearbyComponent(Context context, List<Uri> uris) {
        String packageName = getNearbyComponentPackageName(context);
        if (packageName == null) {
            return;
        }
        for (Uri uri : uris) {
            BluetoothMethodProxy.getInstance()
                    .grantUriPermission(
                            context, packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    private static String getNearbyComponentPackageName(Context context) {
        String componentString =
                BluetoothMethodProxy.getInstance()
                        .settingsSecureGetString(
                                context.getContentResolver(), NEARBY_SHARING_COMPONENT);
        if (componentString == null) {
            return null;
        }
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        if (componentName == null) {
            return null;
        }
        return componentName.getPackageName();
    }

    static int mapStatusToMetricsStatus(int status) {
        return switch (status) {
            case BluetoothShare.STATUS_PENDING ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_PENDING;
            case BluetoothShare.STATUS_RUNNING ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_RUNNING;
            case BluetoothShare.STATUS_SUCCESS ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_SUCCESS;
            case BluetoothShare.STATUS_BAD_REQUEST ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_BAD_REQUEST;
            case BluetoothShare.STATUS_FORBIDDEN ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_USER_STATUS_FORBIDDEN;
            case BluetoothShare.STATUS_NOT_ACCEPTABLE ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_NOT_ACCEPTABLE;
            case BluetoothShare.STATUS_LENGTH_REQUIRED ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_LENGTH_REQUIRED;
            case BluetoothShare.STATUS_PRECONDITION_FAILED ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_PRECONDITION_FAILED;
            case BluetoothShare.STATUS_CANCELED ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_CANCELED;
            case BluetoothShare.STATUS_UNKNOWN_ERROR ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_UNKNOWN_ERROR;
            case BluetoothShare.STATUS_FILE_ERROR ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_FILE_ERROR;
            case BluetoothShare.STATUS_ERROR_NO_SDCARD ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_ERROR_NO_SDCARD;
            case BluetoothShare.STATUS_ERROR_SDCARD_FULL ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_ERROR_SDCARD_FULL;
            case BluetoothShare.STATUS_UNHANDLED_OBEX_CODE ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_UNHANDLED_OBEX_CODE;
            case BluetoothShare.STATUS_OBEX_DATA_ERROR ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_OBEX_DATA_ERROR;
            case BluetoothShare.STATUS_CONNECTION_ERROR ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_CONNECTION_ERROR;
            default ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__SESSION_STATUS__OPP_SESSION_STATUS_UNSPECIFIED;
        };
    }

    static int mapDirectionToMetricsDirection(int direction) {
        return switch (direction) {
            case BluetoothShare.DIRECTION_INBOUND ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__DIRECTION__OPP_TRANSFER_DIRECTION_RECEIVE;
            case BluetoothShare.DIRECTION_OUTBOUND ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__DIRECTION__OPP_TRANSFER_DIRECTION_SEND;
            default ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__DIRECTION__OPP_TRANSFER_DIRECTION_UNSPECIFIED;
        };
    }

    static int categorizeMimeType(String mimeType) {
        if (mimeType == null) {
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_UNSPECIFIED;
        }

        if (mimeType.startsWith("image/")) {
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_IMAGE;
        }
        if (mimeType.startsWith("video/")) {
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_VIDEO;
        }
        if (mimeType.startsWith("audio/")) {
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_AUDIO;
        }

        return switch (mimeType) {
            case "application/pdf",
                    "application/msword",
                    "application/vnd.ms-excel",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain" ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_DOCUMENT;
            case "application/zip", "application/x-7z-compressed", "application/vnd.rar" ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_COMPRESSED;
            default ->
                    BluetoothStatsLog
                            .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__MIME_TYPE_CATEGORY__OPP_MIME_TYPE_CATEGORY_OTHER;
        };
    }

    static int categorizeFileSize(long bytes) {
        if (bytes <= 0)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_UNSPECIFIED;
        if (bytes <= Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_UNDER_1_KB;
        if (bytes <= 2 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_1KB_TO_2KB;
        if (bytes <= 4 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_2KB_TO_4KB;
        if (bytes <= 8 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_4KB_TO_8KB;
        if (bytes <= 16 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_8KB_TO_16KB;
        if (bytes <= 32 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_16KB_TO_32KB;
        if (bytes <= 64 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_32KB_TO_64KB;
        if (bytes <= 128 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_64KB_TO_128KB;
        if (bytes <= 256 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_128KB_TO_256KB;
        if (bytes <= 512 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_256KB_TO_512KB;
        if (bytes <= Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_512KB_TO_1MB;
        if (bytes <= 2 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_1MB_TO_2MB;
        if (bytes <= 4 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_2MB_TO_4MB;
        if (bytes <= 8 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_4MB_TO_8MB;
        if (bytes <= 16 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_8MB_TO_16MB;
        if (bytes <= 32 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_16MB_TO_32MB;
        if (bytes <= 64 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_32MB_TO_64MB;
        if (bytes <= 128 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_64MB_TO_128MB;
        if (bytes <= 256 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_128MB_TO_256MB;
        if (bytes <= 512 * Constants.MB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_256MB_TO_512MB;
        if (bytes <= 1 * Constants.GB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_512MB_TO_1GB;
        if (bytes <= 2 * Constants.GB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_1GB_TO_2GB;
        if (bytes <= 4 * Constants.GB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_2GB_TO_4GB;
        if (bytes <= 8 * Constants.GB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_4GB_TO_8GB;
        return BluetoothStatsLog
                .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__FILE_SIZE__OPP_FILE_SIZE_ABOVE_8GB;
    }

    static int categorizeDuration(long millis) {
        if (millis <= 0)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_UNSPECIFIED;
        if (millis < 1 * Constants.SEC_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_UNDER_1_SEC;
        if (millis < 5 * Constants.SEC_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_1_TO_5_SEC;
        if (millis < 30 * Constants.SEC_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_5_TO_30_SEC;
        if (millis < 60 * Constants.SEC_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_30_TO_60_SEC;
        if (millis < 5 * Constants.MIN_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_1_TO_5_MIN;
        if (millis < 15 * Constants.MIN_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_5_TO_15_MIN;
        if (millis < 30 * Constants.MIN_TO_MS)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_15_TO_30_MIN;
        return BluetoothStatsLog
                .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_DURATION__OPP_TRANSFER_DURATION_ABOVE_30_MIN;
    }

    static int categorizeSpeed(double bytesPerSecond) {
        if (bytesPerSecond <= 0)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_UNSPECIFIED;
        if (bytesPerSecond < 10 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_UNDER_10_KBPS;
        if (bytesPerSecond < 50 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_10_TO_50_KBPS;
        if (bytesPerSecond < 100 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_50_TO_100_KBPS;
        if (bytesPerSecond < 175 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_100_TO_175_KBPS;
        if (bytesPerSecond < 250 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_175_TO_250_KBPS;
        if (bytesPerSecond < 500 * Constants.KB_TO_BYTES)
            return BluetoothStatsLog
                    .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_250_TO_500_KBPS;
        return BluetoothStatsLog
                .BLUETOOTH_OPP_SHARE_STATUS_COMPLETE_REPORTED__TRANSFER_SPEED__OPP_TRANSFER_SPEED_ABOVE_500_KBPS;
    }

    static void checkAndReportShareCompleted(Context context, int id, int status) {
        final String[] FULL_PROJECTION =
                new String[] {
                    BluetoothShare._ID,
                    BluetoothShare.URI,
                    BluetoothShare.FILENAME_HINT,
                    BluetoothShare._DATA,
                    BluetoothShare.MIMETYPE,
                    BluetoothShare.DIRECTION,
                    BluetoothShare.DESTINATION,
                    BluetoothShare.STATUS,
                    BluetoothShare.TOTAL_BYTES,
                    BluetoothShare.CURRENT_BYTES,
                    BluetoothShare.TIMESTAMP
                };

        int oldStatus = -1;
        Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + id);
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor =
                BluetoothMethodProxy.getInstance()
                        .contentResolverQuery(
                                resolver, contentUri, FULL_PROJECTION, null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                oldStatus = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.STATUS));

                if (oldStatus == -1
                        || BluetoothShare.isStatusCompleted(oldStatus)
                        || !BluetoothShare.isStatusCompleted(status)) {
                    Log.w(
                            TAG,
                            "checkAndReportShareCompleted not to report. Old status: "
                                    + oldStatus
                                    + " new status: "
                                    + status);
                    return;
                }

                String fileName =
                        cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare._DATA));
                if (fileName == null) {
                    fileName =
                            cursor.getString(
                                    cursor.getColumnIndexOrThrow(BluetoothShare.FILENAME_HINT));
                }
                int direction =
                        cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.DIRECTION));
                String destination =
                        cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.DESTINATION));
                long totalBytes =
                        cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.TOTAL_BYTES));
                String mimeType =
                        cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.MIMETYPE));
                long timestamp =
                        cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP));

                long durationMillis = System.currentTimeMillis() - timestamp;
                double transferSpeed =
                        (double) (totalBytes * Constants.SEC_TO_MS / ((double) durationMillis));

                BluetoothAdapter adapter =
                        context.getSystemService(BluetoothManager.class).getAdapter();
                BluetoothDevice device = adapter.getRemoteDevice(destination);
                MetricsLogger.getInstance()
                        .logBluetoothOppShareStatusCompleteReported(
                                mapStatusToMetricsStatus(status),
                                mapDirectionToMetricsDirection(direction),
                                categorizeDuration(durationMillis),
                                categorizeFileSize(totalBytes),
                                categorizeSpeed(transferSpeed),
                                categorizeMimeType(mimeType),
                                device);
            }
        }
    }
}
