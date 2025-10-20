/******************************************************************************
 *
 *  Copyright 2025 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <string>

#include "bt_status.h"
#include "bt_status_origin.h"
#include "macros.h"

/** Bluetooth Error Status */
#define BTIF_STATUS_CODES(X)              \
  X(SUCCESS, 0)                           \
  X(FAIL)                                 \
  X(NOT_READY)                            \
  X(NOMEM)                                \
  X(BUSY) /* retryable error */           \
  X(DONE) /* request already completed */ \
  X(UNSUPPORTED)                          \
  X(PARM_INVALID)                         \
  X(UNHANDLED)                            \
  X(AUTH_FAILURE)                         \
  X(RMT_DEV_DOWN)                         \
  X(AUTH_REJECTED)                        \
  X(JNI_ENVIRONMENT_ERROR)                \
  X(JNI_THREAD_ATTACH_ERROR)              \
  X(WAKELOCK_ERROR)                       \
  X(TIMEOUT)                              \
  X(DEVICE_NOT_FOUND)                     \
  X(UNEXPECTED_STATE)                     \
  X(SOCKET_ERROR)

typedef enum { BTIF_STATUS_CODES(AS_ENUM) } BtifStatusCode;

static const std::string btif_status_text(BtStatusCode raw_status) {
  BtifStatusCode status = static_cast<BtifStatusCode>(raw_status);
  switch (status) {
    BTIF_STATUS_CODES(AS_STRING)
    default:
      return std::string("UNKNOWN");
  }
}

class BtifStatus : public BtStatus {
public:
  BtifStatus()
      : BtStatus(static_cast<BtStatusCode>(SUCCESS), BtStatusOrigin::BTIF, btif_status_text) {}
  BtifStatus(BtStatusCode code)
      : BtStatus(static_cast<BtStatusCode>(code), BtStatusOrigin::BTIF, btif_status_text) {}
};
