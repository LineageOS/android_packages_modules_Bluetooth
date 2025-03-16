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

package com.android.bluetooth.pbapclient;

import android.accounts.Account;
import android.bluetooth.BluetoothDevice;

import java.nio.ByteBuffer;
import java.util.List;

public class Utils {
    // VCard Version strings
    public static final String VERSION_21 = "2.1";
    public static final String VERSION_30 = "3.0";
    public static final String VERSION_UNSUPPORTED = "4.0";

    // Constants for creating VCard strings.
    private static final String N = "N";
    private static final String FN = "FN";
    private static final String ADDR = "ADR;TYPE=HOME";
    private static final String CELL = "TEL;TYPE=CELL";
    private static final String EMAIL = "EMAIL;INTERNET";
    private static final String TEL = "TEL;TYPE=0";

    // Constants for creating a call history entry
    public static final String MISSED_CALL = "MISSED";
    public static final String INCOMING_CALL = "RECEIVED";
    public static final String OUTGOING_CALL = "DIALED";
    private static final String CALL_HISTORY = "X-IRMC-CALL-DATETIME";

    public static final String ACCOUNT_TYPE = "com.android.bluetooth.pbapclient";

    public static Account getAccountForDevice(BluetoothDevice device) {
        return new Account(device.getAddress(), ACCOUNT_TYPE);
    }

    /**
     * Group a list of VCard entries or Call History entries into a full phonebook
     *
     * @param vcardStrings The list of VCard or call history strings to group into a phonebook
     * @return A string representation of the entire phonebook
     */
    public static String createPhonebook(List<String> vcardStrings) {
        StringBuilder sb = new StringBuilder();
        for (String vcard : vcardStrings) {
            sb.append(vcard).append("\n");
        }
        return sb.toString();
    }

    /**
     * Create a VCard string fit for parsing
     *
     * <p>A null value in any field outside of name fields will cause it to be dropped from the
     * entry.
     *
     * @param version the version of the VCard you want to create
     * @param first the first name of the VCard you want to create
     * @param last the last name of the VCard you want to create
     * @param phone the phone number of the VCard you want to create
     * @param addr the address of the VCard you want to create
     * @param email the email of the VCard you want to create
     * @return a VCard string, built with the information provided
     */
    public static String createVcard(
            String version, String first, String last, String phone, String addr, String email) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCARD\n");
        sb.append("VERSION:").append(version).append("\n");

        // Friendly name
        sb.append(FN).append(":").append(first).append(" ").append(last).append("\n");

        // Full name: “LastName;FirstName;MiddleName;Prefix;Suffix”
        sb.append(N).append(":").append(last).append(";").append(first).append("\n");

        if (phone != null) {
            sb.append(CELL).append(":").append(phone).append("\n");
        }

        if (addr != null) {
            sb.append(ADDR).append(":").append(addr).append("\n");
        }

        if (email != null) {
            sb.append(EMAIL).append(":").append(email).append("\n");
        }

        sb.append("END:VCARD");

        return sb.toString();
    }

    /**
     * Create a call history entry string fit for parsing
     *
     * <p>A call history entry is a VCard with special fields to carry the type of call and the time
     * the call occurred
     *
     * @param version the version of the call history entry you want to create
     * @param type the type of the call history entry you want to create
     * @param time the time of the call history entry, in the format "YYYYMMDDTHHMMSS"
     * @param first the first name of the person who was called
     * @param last the last name of the person who was called
     * @param phone the phone number of the person who was called
     */
    public static String createCallHistory(
            String version, String type, String time, String first, String last, String phone) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCARD\n");
        sb.append("VERSION:").append(version).append("\n");

        if (VERSION_30.equals(version)) {
            sb.append(FN).append(":").append(first).append(" ").append(last).append("\n");
        }

        sb.append(N).append(":").append(last).append(";").append(first).append("\n");

        sb.append(TEL).append(":").append(phone).append("\n");

        // Time format: YYYYMMDDTHHMMSS -> 20050320T100000
        if (VERSION_30.equals(version)) {
            sb.append(CALL_HISTORY)
                    .append(";TYPE=")
                    .append(type)
                    .append(":")
                    .append(time)
                    .append("\n");
        } else {
            sb.append(CALL_HISTORY).append(";").append(type).append(":").append(time).append("\n");
        }

        sb.append("END:VCARD");

        return sb.toString();
    }

    public static byte[] shortToByteArray(short s) {
        ByteBuffer ret = ByteBuffer.allocate(2);
        ret.putShort(s);
        return ret.array();
    }

    public static byte[] longToByteArray(long l) {
        ByteBuffer ret = ByteBuffer.allocate(16);
        ret.putLong(0); // Most significant bytes
        ret.putLong(l); // Least significant bytes
        return ret.array();
    }
}
