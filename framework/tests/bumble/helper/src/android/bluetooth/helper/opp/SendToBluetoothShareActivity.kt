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

package android.bluetooth.helper.opp

import android.app.Activity
import android.bluetooth.helper.opp.SenderUtils.MIME_TYPE_IMAGE
import android.bluetooth.helper.opp.SenderUtils.resolveBluetoothShareActivityInfo
import android.content.ClipData
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.Intent.EXTRA_STREAM
import android.net.Uri
import android.os.Bundle

class SendToBluetoothShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent(ACTION_SEND)
            .putExtra(EXTRA_STREAM, intent.getParcelableExtra(EXTRA_STREAM, Uri::class.java))
            .apply { clipData = ClipData.newPlainText("Clip Data", "clip_data") }
            .setType(MIME_TYPE_IMAGE)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .resolveBluetoothShareActivityInfo(context = this)
            .run(this@SendToBluetoothShareActivity::startActivity)

        finish()
    }
}
