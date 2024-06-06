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

package android.bluetooth.cts;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.test_utils.BlockingBluetoothAdapter;
import android.content.Context;

/**
 * @deprecated see {@link android.bluetooth.test_utils.BlockingBluetoothAdapter}
 */
@Deprecated
public class BTAdapterUtils {
    private BTAdapterUtils() {}

    /**
     * @deprecated see {@link android.bluetooth.test_utils.BlockingBluetoothAdapter#enable}
     */
    @Deprecated
    public static final boolean enableAdapter(BluetoothAdapter adapter, Context ctx) {
        return BlockingBluetoothAdapter.enable();
    }

    /**
     * @deprecated see {@link android.bluetooth.test_utils.BlockingBluetoothAdapter#disable}
     */
    @Deprecated
    public static final boolean disableAdapter(BluetoothAdapter adapter, Context ctx) {
        return BlockingBluetoothAdapter.disable(true);
    }

    /**
     * @deprecated see {@link android.bluetooth.test_utils.BlockingBluetoothAdapter#disable}
     */
    @Deprecated
    public static final boolean disableAdapter(
            BluetoothAdapter adapter, boolean persist, Context ctx) {
        return BlockingBluetoothAdapter.disable(persist);
    }
}
