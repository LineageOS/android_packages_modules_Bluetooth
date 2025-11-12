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

package com.android.server.bluetooth;

import android.bluetooth.IBluetoothManagerCallback;
import android.content.AttributionSource;

/** Binder object definition to use in the Bluetotoh System Server messenger */
@JavaPassthrough(annotation="@android.annotation.Hide")
interface SystemServiceMessage {
    parcelable RegisterAdapter {
        IBluetoothManagerCallback binder;
        parcelable Reply {
            @nullable IBinder value;
        }
    }

    parcelable UnregisterAdapter {
        IBluetoothManagerCallback binder;
        parcelable Reply {}
    }

    parcelable Enable {
        AttributionSource attributionSource;
        @nullable IBinder bleToken;
        boolean isQuiet;
        parcelable Reply {
            boolean value;
        }
    }

    parcelable Disable {
        AttributionSource attributionSource;
        @nullable IBinder bleToken;
        boolean persist;
        parcelable Reply {
            boolean value;
        }
    }

    parcelable FactoryReset {
        AttributionSource attributionSource;
        parcelable Reply {
            boolean value;
        }
    }

    parcelable GetAddress {
        AttributionSource attributionSource;
        parcelable Reply {
            String value;
        }
    }

    parcelable SetName {
        AttributionSource attributionSource;
        String name;
        parcelable Reply {}
    }

    parcelable GetName {
        AttributionSource attributionSource;
        parcelable Reply {
            String value;
        }
    }

    parcelable IsBleScanAvailable {
        parcelable Reply {
            boolean value;
        }
    }

    parcelable IsHearingAidSupported {
        parcelable Reply {
            boolean value;
        }
    }

    parcelable SetSnoopLog {
        int mode;
        parcelable Reply {}
    }

    parcelable GetSnoopLog {
        parcelable Reply {
            int value;
        }
    }

    parcelable IsAutoSupported {
        parcelable Reply {
            boolean value;
        }
    }

    parcelable IsAutoEnabled {
        parcelable Reply {
            boolean value;
        }
    }

    parcelable SetAutoOnEnabled {
        boolean enabledStatus;
        parcelable Reply {}
    }
}
