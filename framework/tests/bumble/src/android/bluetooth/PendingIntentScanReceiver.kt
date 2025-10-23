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

package android.bluetooth

import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CompletableFuture

private const val TAG = "PendingIntentScanReceiver"

/**
 * PendingIntentScanReceiver is registered statically in the manifest file as a BroadcastReceiver
 * for the android.bluetooth.ACTION_SCAN_RESULT action. Tests can use nextScanResult() to get a
 * future that completes when scan results are next delivered.
 */
class PendingIntentScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive() intent: $intent")

        if (ACTION_SCAN_RESULT != intent.action) {
            throw RuntimeException()
        }

        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            Log.e(TAG, "onReceive() error: $errorCode")
            throw RuntimeException("onReceive() unexpected error: $errorCode")
        }

        val scanResults =
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            )

        nextScanResultFuture?.let {
            it.complete(scanResults)
            nextScanResultFuture = null
        } ?: throw IllegalStateException("scan result received but no future set")
    }

    companion object {
        const val ACTION_SCAN_RESULT: String = "android.bluetooth.test.ACTION_SCAN_RESULT"

        private var nextScanResultFuture: CompletableFuture<List<ScanResult>>? = null

        /**
         * Constructs a new PendingIntent associated with this class.
         *
         * @param context The context to associate the PendingIntent with.
         * @param requestCode The request code to uniquely identify this PendingIntent with.
         */
        fun newBroadcastPendingIntent(context: Context, requestCode: Int) =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent().apply {
                    action = ACTION_SCAN_RESULT
                    setClass(context, PendingIntentScanReceiver::class.java)
                },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT,
            )

        /**
         * Use this method for statically registered receivers.
         *
         * @return A future that will complete when the next scan result is received.
         */
        @Throws(IllegalStateException::class)
        fun nextScanResult(): CompletableFuture<List<ScanResult>> {
            if (nextScanResultFuture != null) {
                throw IllegalStateException("scan result future already set")
            }
            val future = CompletableFuture<List<ScanResult>>()
            nextScanResultFuture = future
            return future
        }

        /** Clears the future waiting for the next static receiver scan result, if any. */
        fun resetNextScanResultFuture() {
            nextScanResultFuture = null
        }
    }
}
