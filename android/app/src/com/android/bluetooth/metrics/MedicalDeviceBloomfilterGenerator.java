/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.metrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Class to generate a medical device bloomfilter */
public class MedicalDeviceBloomfilterGenerator {

    public static final String BLOOM_FILTER_DEFAULT =
            "01070000003C01002106584044800850055"
                    + "002308488410020D9A00001138410510000"
                    + "000042004200000000000C2000000040064"
                    + "0120080020110412A500090520210040C40"
                    + "4002601040005004400148414006198A041"
                    + "00890000600400000800210041810600800"
                    + "0142208000721A030000028102448201110"
                    + "0002007120020101448C211490A2B000084"
                    + "C010004004C00C080808200026210608110"
                    + "200011200000015000000212C4400040802"
                    + "00111114840000001002080040186000404"
                    + "81C064400052381109017039900000200C9"
                    + "C0002E6480000101C40000601064001A018"
                    + "40440280A810001000040455A0404617034"
                    + "50000140040D020020C6204100804041600"
                    + "80840002000800804280028000440000122"
                    + "00808409905022000590000110448080400"
                    + "561004210020430092602000040C0090C00"
                    + "C18480020000519C1482100111011120390"
                    + "02C0000228208104800C050440000004040"
                    + "00871400882400140080000005308124900"
                    + "104000040002410508CA349000200000202"
                    + "90200920181890100800110220A20874820"
                    + "0428080054A0005101C0820060090080040"
                    + "6820C480F40081014010201800000018101"
                    + "208914100321008006520002030010800C4"
                    + "1022C000048206002010041220000008021"
                    + "002080314040000100030002008";

    /** Provide byte[] version of a given string */
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                    (byte)
                            ((Character.digit(s.charAt(i), 16) << 4)
                                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /** Generate bloom filter file given filePath */
    public static void generateDefaultBloomfilter(String filePath) throws IOException {
        File outputFile = new File(filePath);
        outputFile.createNewFile(); // if file already exists will do nothing
        FileOutputStream fos = new FileOutputStream(filePath);
        fos.write(hexStringToByteArray(BLOOM_FILTER_DEFAULT));
        fos.close();
    }
}
