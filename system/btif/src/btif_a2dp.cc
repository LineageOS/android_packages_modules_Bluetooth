/******************************************************************************
 *
 *  Copyright 2016 The Android Open Source Project
 *  Copyright 2009-2012 Broadcom Corporation
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

#define LOG_TAG "bluetooth-a2dp"

#include "btif_a2dp.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>

#include "audio_hal_interface/a2dp_encoding.h"
#include "bta_av_api.h"
#include "btif_a2dp_sink.h"
#include "btif_a2dp_source.h"
#include "btif_av.h"
#include "btif_av_co.h"
#include "btif_hf.h"
#include "stack/include/avdt_api.h"

using namespace bluetooth;
using bluetooth::audio::a2dp::Status;

void btif_a2dp_on_idle(const RawAddress& /*peer_addr*/, const A2dpType local_a2dp_type) {
  log::debug("Peer stream endpoint type:{}",
             peer_stream_endpoint_text(btif_av_get_peer_sep(local_a2dp_type)));
  if (btif_av_get_peer_sep(local_a2dp_type) == AVDT_TSEP_SNK) {
    btif_a2dp_source_on_idle();
  } else if (btif_av_get_peer_sep(local_a2dp_type) == AVDT_TSEP_SRC) {
    btif_a2dp_sink_on_idle();
  }
}

bool btif_a2dp_on_started(const RawAddress& peer_addr, tBTA_AV_START* p_av_start,
                          const A2dpType local_a2dp_type) {
  log::info("## ON A2DP STARTED ## peer {} p_av_start:{}", peer_addr, std::format_ptr(p_av_start));

  if (p_av_start == NULL) {
    auto status = Status::SUCCESS;
    if (!bluetooth::headset::IsCallIdle()) {
      log::error("peer {} call in progress, do not start A2DP stream", peer_addr);
      status = Status::FAILURE;
    }
    /* just ack back a local start request, do not start the media encoder since
     * this is not for BTA_AV_START_EVT. */
    bluetooth::audio::a2dp::ack_stream_started(status);
    return true;
  }

  log::info("peer {} status:{} suspending:{} initiator:{}", peer_addr, p_av_start->status,
            p_av_start->suspending, p_av_start->initiator);

  if (p_av_start->status == BTA_AV_SUCCESS) {
    if (p_av_start->suspending) {
      log::warn("peer {} A2DP is suspending and ignores the started event", peer_addr);
      return false;
    }
    if (btif_av_is_a2dp_offload_running()) {
      btif_av_stream_start_offload();
    } else {
      if (btif_av_get_peer_sep(local_a2dp_type) == AVDT_TSEP_SNK) {
        /* Start the media encoder to do the SW audio stream */
        btif_a2dp_source_start_audio_req();
      }
      if (p_av_start->initiator) {
        bluetooth::audio::a2dp::ack_stream_started(Status::SUCCESS);
        return true;
      }
    }
  } else if (p_av_start->initiator) {
    log::error("peer {} A2DP start request failed: status = {}", peer_addr, p_av_start->status);
    bluetooth::audio::a2dp::ack_stream_started(Status::FAILURE);
    return true;
  }
  return false;
}

void btif_a2dp_on_stopped(tBTA_AV_SUSPEND* p_av_suspend, const A2dpType local_a2dp_type) {
  log::info("## ON A2DP STOPPED ## p_av_suspend={}", std::format_ptr(p_av_suspend));

  const uint8_t peer_type_sep = btif_av_get_peer_sep(local_a2dp_type);
  if (peer_type_sep == AVDT_TSEP_SRC) {
    btif_a2dp_sink_on_stopped(p_av_suspend);
    return;
  }
  if (peer_type_sep == AVDT_TSEP_SNK) {
    if (bluetooth::audio::a2dp::is_hal_enabled() || !btif_av_is_a2dp_offload_running()) {
      btif_a2dp_source_on_stopped(p_av_suspend);
      return;
    }
  }
}

void btif_a2dp_on_suspended(tBTA_AV_SUSPEND* p_av_suspend, const A2dpType local_a2dp_type) {
  log::info("## ON A2DP SUSPENDED ## p_av_suspend={}", std::format_ptr(p_av_suspend));
  const uint8_t peer_type_sep = btif_av_get_peer_sep(local_a2dp_type);
  if (peer_type_sep == AVDT_TSEP_SRC) {
    btif_a2dp_sink_on_suspended(p_av_suspend);
    return;
  }
  if (peer_type_sep == AVDT_TSEP_SNK) {
    if (bluetooth::audio::a2dp::is_hal_enabled() || !btif_av_is_a2dp_offload_running()) {
      btif_a2dp_source_on_suspended(p_av_suspend);
      return;
    }
  }
}

void btif_a2dp_on_offload_started(const RawAddress& peer_addr, tBTA_AV_STATUS status) {
  Status ack;
  log::info("peer {} status {}", peer_addr, status);

  switch (status) {
    case BTA_AV_SUCCESS:
      // Call required to update the session state for metrics.
      btif_a2dp_source_start_audio_req();
      ack = Status::SUCCESS;
      break;
    case BTA_AV_FAIL_RESOURCES:
      log::error("peer {} FAILED UNSUPPORTED", peer_addr);
      ack = Status::UNSUPPORTED_CODEC_CONFIGURATION;
      break;
    default:
      log::error("peer {} FAILED: status = {}", peer_addr, status);
      ack = Status::FAILURE;
      break;
  }

  if (btif_av_is_a2dp_offload_running()) {
    if (ack != Status::SUCCESS && btif_av_stream_started_ready(A2dpType::kSource)) {
      log::error("peer {} offload start failed", peer_addr);
      btif_av_stream_stop(peer_addr);
    }
  }

  bluetooth::audio::a2dp::ack_stream_started(ack);
}

void btif_debug_a2dp_dump(int fd) {
  btif_a2dp_source_debug_dump(fd);
  btif_a2dp_sink_debug_dump(fd);
  btif_a2dp_codec_debug_dump(fd);
}
