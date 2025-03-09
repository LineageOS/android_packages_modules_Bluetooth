/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.Utils;
import com.android.bluetooth.flags.Flags;

import java.util.ArrayList;

public class BluetoothOppHandoverReceiver extends BroadcastReceiver {
    private static final String TAG = BluetoothOppHandoverReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Action :" + action);
        if (action == null) return;
        if (action.equals(Constants.ACTION_HANDOVER_SEND)
                || action.equals(Constants.ACTION_HANDOVER_SEND_MULTIPLE)) {
            final BluetoothDevice device =
                    (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) {
                Log.d(TAG, "No device attached to handover intent.");
                return;
            }

            final String mimeType = intent.getType();
            ArrayList<Uri> uris = new ArrayList<Uri>();
            if (action.equals(Constants.ACTION_HANDOVER_SEND)) {
                Uri stream = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) {
                    uris.add(stream);
                }
            } else if (action.equals(Constants.ACTION_HANDOVER_SEND_MULTIPLE)) {
                uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }

            if (mimeType != null && uris != null && !uris.isEmpty()) {
                final Context finalContext = context;
                final ArrayList<Uri> finalUris = uris;
                new Thread(
                                () -> {
                                    BluetoothOppManager.getInstance(finalContext)
                                            .saveSendingFileInfo(
                                                    mimeType,
                                                    finalUris,
                                                    true /* isHandover */,
                                                    true /* fromExternal */);
                                    BluetoothOppManager.getInstance(finalContext)
                                            .startTransfer(device);
                                })
                        .start();
            } else {
                Log.d(TAG, "No mimeType or stream attached to handover request");
                return;
            }
        } else if (action.equals(Constants.ACTION_ACCEPTLIST_DEVICE)) {
            BluetoothDevice device =
                    (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            String brEdrAddress =
                    Flags.identityAddressNullIfNotKnown()
                            ? Utils.getBrEdrAddress(device)
                            : device.getIdentityAddress();
            Log.d(TAG, "Adding " + brEdrAddress + " to acceptlist");
            BluetoothOppManager.getInstance(context).addToAcceptlist(brEdrAddress);
        } else if (action.equals(Constants.ACTION_STOP_HANDOVER)) {
            int id = intent.getIntExtra(Constants.EXTRA_BT_OPP_TRANSFER_ID, -1);
            if (id != -1) {
                Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + id);

                Log.d(TAG, "Stopping handover transfer with Uri " + contentUri);
                BluetoothMethodProxy.getInstance()
                        .contentResolverDelete(
                                context.getContentResolver(), contentUri, null, null);
            }
        } else {
            Log.d(TAG, "Unknown action: " + action);
        }
    }
}
