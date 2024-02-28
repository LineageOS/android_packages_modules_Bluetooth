/*
 * Copyright 2024 The Android Open Source Project
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
#include "btif/include/bta_av_co_peer.h"

#include <base/logging.h>

#include "bta/include/bta_av_api.h"
#include "include/check.h"

// Macro to convert BTA AV audio handle to index and vice versa
#define BTA_AV_CO_AUDIO_HANDLE_TO_INDEX(bta_av_handle) \
  (((bta_av_handle) & (~BTA_AV_CHNL_MSK)) - 1)
#define BTA_AV_CO_AUDIO_INDEX_TO_HANDLE(index) \
  (((index) + 1) | BTA_AV_CHNL_AUDIO)

BtaAvCoPeer::BtaAvCoPeer()
    : addr(RawAddress::kEmpty),
      num_sinks(0),
      num_sources(0),
      num_seps(0),
      num_rx_sinks(0),
      num_rx_sources(0),
      num_sup_sinks(0),
      num_sup_sources(0),
      p_sink(nullptr),
      p_source(nullptr),
      codec_config{},
      acceptor(false),
      reconfig_needed(false),
      opened(false),
      mtu(0),
      uuid_to_connect(0),
      bta_av_handle_(0),
      codecs_(nullptr),
      content_protect_active_(false) {
  Reset(0);
}

void BtaAvCoPeer::Init(
    const std::vector<btav_a2dp_codec_config_t>& codec_priorities) {
  Reset(bta_av_handle_);
  // Reset the current config
  codecs_ = new A2dpCodecs(codec_priorities);
  codecs_->init();
  A2DP_InitDefaultCodec(codec_config);
}

void BtaAvCoPeer::Reset(tBTA_AV_HNDL bta_av_handle) {
  addr = RawAddress::kEmpty;
  for (size_t i = 0; i < BTA_AV_CO_NUM_ELEMENTS(sinks); i++) {
    BtaAvCoSep& sink = sinks[i];
    sink.Reset();
  }
  for (size_t i = 0; i < BTA_AV_CO_NUM_ELEMENTS(sources); i++) {
    BtaAvCoSep& source = sources[i];
    source.Reset();
  }
  num_sinks = 0;
  num_sources = 0;
  num_seps = 0;
  num_rx_sinks = 0;
  num_rx_sources = 0;
  num_sup_sinks = 0;
  num_sup_sources = 0;
  p_sink = nullptr;
  p_source = nullptr;
  memset(codec_config, 0, sizeof(codec_config));
  acceptor = false;
  reconfig_needed = false;
  opened = false;
  mtu = 0;
  uuid_to_connect = 0;

  bta_av_handle_ = bta_av_handle;
  delete codecs_;
  codecs_ = nullptr;
  content_protect_active_ = false;
}
