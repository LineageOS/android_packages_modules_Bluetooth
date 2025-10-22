/*
 * Copyright (C) 2013 Samsung System LSI
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

package com.android.bluetooth.map;

import android.util.Log;

import com.android.bluetooth.map.BluetoothMapSmsPdu.SmsPdu;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BluetoothMapbMessageSms extends BluetoothMapbMessage {

    private final BluetoothMapService mMapService;
    private List<SmsPdu> mSmsBodyPdus = null;
    private String mSmsBody = null;

    BluetoothMapbMessageSms(BluetoothMapService mapService) {
        mMapService = mapService;
    }

    public void setSmsBodyPdus(List<SmsPdu> smsBodyPdus) {
        this.mSmsBodyPdus = smsBodyPdus;
        this.mCharset = null;
        if (smsBodyPdus.size() > 0) {
            this.mEncoding = smsBodyPdus.get(0).getEncodingString();
        }
    }

    public String getSmsBody() {
        return mSmsBody;
    }

    public void setSmsBody(String smsBody) {
        this.mSmsBody = smsBody;
        this.mCharset = "UTF-8";
        this.mEncoding = null;
    }

    @Override
    public void parseMsgPart(String msgPart) {
        if (mAppParamCharset == BluetoothMapAppParams.CHARSET_NATIVE) {
            Log.d(TAG, "Decoding \"" + msgPart + "\" as native PDU");
            byte[] msgBytes = decodeBinary(msgPart);
            if (msgBytes.length > 0
                    && msgBytes[0] < msgBytes.length - 1
                    && (msgBytes[msgBytes[0] + 1] & 0x03) != 0x01) {
                Log.d(TAG, "Only submit PDUs are supported");
                throw new IllegalArgumentException("Only submit PDUs are supported");
            }

            mSmsBody =
                    mSmsBody
                            + BluetoothMapSmsPdu.decodePdu(
                                    msgBytes,
                                    mType == TYPE.SMS_CDMA
                                            ? BluetoothMapSmsPdu.SMS_TYPE_CDMA
                                            : BluetoothMapSmsPdu.SMS_TYPE_GSM);
        } else {
            mSmsBody = mSmsBody + msgPart;
        }
    }

    @Override
    public void parseMsgInit() {
        mSmsBody = "";
    }

    @Override
    public byte[] encode() {
        List<byte[]> bodyFragments = new ArrayList<>();

        /* Store the messages in an ArrayList to be able to handle the different message types in
        a generic way.
        * We use byte[] since we need to extract the length in bytes.
        */
        if (mSmsBody != null) {
            String tmpBody =
                    mSmsBody.replaceAll(
                            "END:MSG",
                            "/END\\:MSG"); // Replace any occurrences of END:MSG with \END:MSG
            String remoteAddress = mMapService.getRemoteDevice().getAddress();
            /* Fix IOT issue with PCM carkit where carkit is unable to parse
            message if carriage return is present in it */
            if (DeviceWorkArounds.addressStartsWith(remoteAddress, DeviceWorkArounds.PCM_CARKIT)) {
                tmpBody = tmpBody.replaceAll("\r", "");
                /* Fix Message Display issue with FORD SYNC carkit -
                 * Remove line feed and include only carriage return */
            } else if (DeviceWorkArounds.addressStartsWith(
                    remoteAddress, DeviceWorkArounds.FORD_SYNC_CARKIT)) {
                tmpBody = tmpBody.replaceAll("\n", "");
                /* Fix IOT issue with SYNC carkit to remove trailing line feeds in the message body
                 */
            } else if (DeviceWorkArounds.addressStartsWith(
                            remoteAddress, DeviceWorkArounds.SYNC_CARKIT)
                    && tmpBody.length() > 0) {
                int trailingLF = 0;
                while ((tmpBody.charAt(tmpBody.length() - trailingLF - 1)) == '\n') trailingLF++;
                tmpBody = tmpBody.substring(0, (tmpBody.length() - trailingLF));
            }
            bodyFragments.add(tmpBody.getBytes(StandardCharsets.UTF_8));
        } else if (mSmsBodyPdus != null && mSmsBodyPdus.size() > 0) {
            for (SmsPdu pdu : mSmsBodyPdus) {
                // This cannot(must not) contain END:MSG
                bodyFragments.add(
                        encodeBinary(pdu.getData(), pdu.getScAddress())
                                .getBytes(StandardCharsets.UTF_8));
            }
        } else {
            bodyFragments.add(new byte[0]); // An empty message - no text
        }

        return encodeGeneric(bodyFragments);
    }
}
