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

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.obex.HeaderSet;

import java.util.regex.Pattern;

/** Bluetooth OPP internal constant definitions */
public class Constants {
    /** Tag used for debugging/logging */
    public static final String BT_PREFIX_OPP = "BluetoothOpp";

    static final String TAG = BT_PREFIX_OPP + Constants.class.getSimpleName();

    /**
     * The intent that gets sent when the service must wake up for a retry Note: Only retry Outbound
     * transfers
     */
    static final String ACTION_RETRY = "android.btopp.intent.action.RETRY";

    /** the intent that gets sent when clicking a successful transfer */
    static final String ACTION_OPEN = "android.btopp.intent.action.OPEN";

    /** the intent that gets sent when clicking outbound transfer notification */
    static final String ACTION_OPEN_OUTBOUND_TRANSFER = "android.btopp.intent.action.OPEN_OUTBOUND";

    /** the intent that gets sent when clicking a inbound transfer notification */
    static final String ACTION_OPEN_INBOUND_TRANSFER = "android.btopp.intent.action.OPEN_INBOUND";

    /**
     * the intent extra to show the direction of a transfer. Value should be one of {@link
     * BluetoothShare#DIRECTION_INBOUND} or {@link BluetoothShare#DIRECTION_OUTBOUND}
     */
    static final String EXTRA_DIRECTION = "android.btopp.intent.extra.DIRECTION";

    /** the intent that gets sent when clicking an incomplete/failed transfer */
    static final String ACTION_LIST = "android.btopp.intent.action.LIST";

    /** the intent that gets sent when deleting the incoming file confirmation notification */
    static final String ACTION_HIDE = "android.btopp.intent.action.HIDE";

    /** the intent that gets sent when accepting the incoming file confirmation notification */
    static final String ACTION_ACCEPT = "android.btopp.intent.action.ACCEPT";

    /** the intent that gets sent when declining the incoming file confirmation notification */
    static final String ACTION_DECLINE = "android.btopp.intent.action.DECLINE";

    /** The intent that gets sent when deleting the notifications of completed inbound transfer. */
    static final String ACTION_HIDE_COMPLETED_INBOUND_TRANSFER =
            "android.btopp.intent.action.HIDE_COMPLETED_INBOUND_TRANSFER";

    /** The intent that gets sent when deleting the notifications of completed outbound transfer. */
    static final String ACTION_HIDE_COMPLETED_OUTBOUND_TRANSFER =
            "android.btopp.intent.action.HIDE_COMPLETED_OUTBOUND_TRANSFER";

    /** the intent that gets sent when clicking a incoming file confirm notification */
    static final String ACTION_INCOMING_FILE_CONFIRM = "android.btopp.intent.action.CONFIRM";

    /** The column that is used to remember whether the media scanner was invoked */
    static final String MEDIA_SCANNED = "scanned";

    static final int MEDIA_SCANNED_NOT_SCANNED = 0;

    static final int MEDIA_SCANNED_SCANNED_OK = 1;

    static final int MEDIA_SCANNED_SCANNED_FAILED = 2;

    /** The bytes size unit used in the file size categorization */
    static final long KB_TO_BYTES = 1024;

    static final long MB_TO_BYTES = KB_TO_BYTES * 1024;
    static final long GB_TO_BYTES = MB_TO_BYTES * 1024;

    /** The time unit used in the duration categorization */
    static final int SEC_TO_MS = 1000;

    static final int MIN_TO_MS = 60 * SEC_TO_MS;

    /**
     * The MIME type(s) of we could accept from other device. This is in essence a "acceptlist" of
     * acceptable types. Today, restricted to images, audio, video and certain text types.
     */
    static final String[] ACCEPTABLE_SHARE_INBOUND_TYPES =
            new String[] {
                "image/*",
                "video/*",
                "audio/*",
                "text/x-vcard",
                "text/x-vcalendar",
                "text/calendar",
                "text/plain",
                "text/html",
                "text/xml",
                "application/epub+zip",
                "application/zip",
                "application/vnd.ms-excel",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/x-hwp",
            };

    /** Where we store received files */
    static final String DEFAULT_STORE_SUBDIR = "/bluetooth";

    /**
     * To log debug/verbose in OPP, use the command "setprop log.tag.BluetoothOpp DEBUG" or "setprop
     * log.tag.BluetoothOpp VERBOSE" and then "adb root" + "adb shell "stop; start""
     */
    static final int MAX_RECORDS_IN_DATABASE = 50;

    static final int BATCH_STATUS_PENDING = 0;

    static final int BATCH_STATUS_RUNNING = 1;

    static final int BATCH_STATUS_FINISHED = 2;

    static final int BATCH_STATUS_FAILED = 3;

    static final String BLUETOOTHOPP_NAME_PREFERENCE = "btopp_names";

    static final String BLUETOOTHOPP_CHANNEL_PREFERENCE = "btopp_channels";

    static final String FILENAME_SEQUENCE_SEPARATOR = "-";

    private Constants() {}

    static void updateShareStatus(Context context, int id, int status) {
        BluetoothOppUtility.checkAndReportShareCompleted(context, id, status);
        Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + id);
        ContentValues updateValues = new ContentValues();
        updateValues.put(BluetoothShare.STATUS, status);
        BluetoothMethodProxy.getInstance()
                .contentResolverUpdate(
                        context.getContentResolver(), contentUri, updateValues, null, null);
        Constants.sendIntentIfCompleted(context, contentUri, status);
    }

    /** This function should be called whenever the transfer status changes to completed. */
    static void sendIntentIfCompleted(Context context, Uri contentUri, int status) {
        if (BluetoothShare.isStatusCompleted(status)) {
            Intent intent = new Intent(BluetoothShare.TRANSFER_COMPLETED_ACTION);
            intent.setClassName(context, BluetoothOppReceiver.class.getName());
            intent.setData(contentUri.normalizeScheme());
            context.sendBroadcast(intent);
        }
    }

    static boolean mimeTypeMatches(String mimeType, String[] matchAgainst) {
        for (String matchType : matchAgainst) {
            if (mimeTypeMatches(mimeType, matchType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mimeTypeMatches(String mimeType, String matchAgainst) {
        String matchRegex = matchAgainst.replaceAll("\\+", "\\\\+").replaceAll("\\*", ".*");
        Pattern p = Pattern.compile(matchRegex, Pattern.CASE_INSENSITIVE);
        return p.matcher(mimeType).matches();
    }

    static void logHeader(HeaderSet hs) {
        Log.v(TAG, "Dumping HeaderSet " + hs.toString());
        Log.v(TAG, "COUNT : " + hs.getHeader(HeaderSet.COUNT));
        Log.v(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
        Log.v(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
        Log.v(TAG, "LENGTH : " + hs.getHeader(HeaderSet.LENGTH));
        Log.v(TAG, "TIME_ISO_8601 : " + hs.getHeader(HeaderSet.TIME_ISO_8601));
        Log.v(TAG, "TIME_4_BYTE : " + hs.getHeader(HeaderSet.TIME_4_BYTE));
        Log.v(TAG, "DESCRIPTION : " + hs.getHeader(HeaderSet.DESCRIPTION));
        Log.v(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
        Log.v(TAG, "HTTP : " + hs.getHeader(HeaderSet.HTTP));
        Log.v(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
        Log.v(TAG, "OBJECT_CLASS : " + hs.getHeader(HeaderSet.OBJECT_CLASS));
        Log.v(TAG, "APPLICATION_PARAMETER : " + hs.getHeader(HeaderSet.APPLICATION_PARAMETER));
    }
}
