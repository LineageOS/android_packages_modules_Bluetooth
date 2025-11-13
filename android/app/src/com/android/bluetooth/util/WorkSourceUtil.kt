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

package com.android.bluetooth.util

import android.os.WorkSource

/** Class for general helper methods for WorkSource operations. */
class WorkSourceUtil(workSource: WorkSource) {
    val uids: IntArray
    val tags: Array<String?>

    init {
        val uidList = mutableListOf<Int>()
        val tagList = mutableListOf<String?>()

        for (i in 0 until workSource.size()) {
            uidList.add(workSource.getUid(i))
            tagList.add(workSource.getPackageName(i))
        }

        workSource.workChains?.forEach { workChain ->
            uidList.add(workChain.attributionUid)
            tagList.add(workChain.attributionTag)
        }

        uids = uidList.toIntArray()
        tags = tagList.toTypedArray()
    }
}
