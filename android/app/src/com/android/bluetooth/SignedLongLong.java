/*
 * Copyright (C) 2015 Samsung System LSI
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

package com.android.bluetooth;

import com.android.bluetooth.map.BluetoothMapUtils;

/**
 * Class to represent a 128bit value using two long member variables. Has functionality to convert
 * to/from hex-strings. Mind that since signed variables are used to store the value internally is
 * used, the MSB/LSB long values can be negative.
 */
public record SignedLongLong(long leastSignificantBits, long mostSignificantBits)
        implements Comparable<SignedLongLong> {

    /**
     * Create a SignedLongLong from a Hex-String without "0x" prefix
     *
     * @param value the hex-string
     * @return the created object
     * @throws NullPointerException if the string {@code value} is null,
     * @throws NumberFormatException if the string {@code value} contains invalid characters.
     */
    public static SignedLongLong fromString(String value) {
        String lsbStr, msbStr;
        long msb = 0;

        lsbStr = msbStr = null;
        if (value == null) {
            throw new NullPointerException();
        }
        value = value.trim();
        int valueLength = value.length();
        if (valueLength == 0 || valueLength > 32) {
            throw new NumberFormatException("invalid string length: " + valueLength);
        }
        if (valueLength <= 16) {
            lsbStr = value;
        } else {
            lsbStr = value.substring(valueLength - 16, valueLength);
            msbStr = value.substring(0, valueLength - 16);
            msb = BluetoothMapUtils.getLongFromString(msbStr);
        }
        long lsb = BluetoothMapUtils.getLongFromString(lsbStr);
        return new SignedLongLong(lsb, msb);
    }

    @Override
    public int compareTo(SignedLongLong another) {
        if (mostSignificantBits == another.mostSignificantBits) {
            if (leastSignificantBits == another.leastSignificantBits) {
                return 0;
            }
            if (leastSignificantBits < another.leastSignificantBits) {
                return -1;
            }
            return 1;
        }
        if (mostSignificantBits < another.mostSignificantBits) {
            return -1;
        }
        return 1;
    }

    @Override
    public String toString() {
        return toHexString();
    }

    /**
     * @return a hex-string representation of the object values
     */
    public String toHexString() {
        return BluetoothMapUtils.getLongLongAsString(leastSignificantBits, mostSignificantBits);
    }
}
