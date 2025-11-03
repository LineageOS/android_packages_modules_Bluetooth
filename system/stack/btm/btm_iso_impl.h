/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#pragma once

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <com_android_bluetooth_flags.h>

#include <memory>
#include <mutex>
#include <unordered_map>

#include "btm_dev.h"
#include "btm_iso_api.h"
#include "btm_iso_api_types.h"
#include "common/time_util.h"
#include "hci/controller.h"
#include "hci/include/hci_layer.h"
#include "internal_include/stack_config.h"
#include "main/shim/entry.h"
#include "main/shim/hci_layer.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/hcidefs.h"
#include "stack/include/hcimsgs.h"

namespace bluetooth {
namespace hci {
namespace iso_manager {
static constexpr uint8_t kIsoHeaderWithTsLen = 12;
static constexpr uint8_t kIsoHeaderWithoutTsLen = 8;

static constexpr uint8_t kStateFlagsNone = 0x00;
static constexpr uint8_t kStateFlagIsConnecting = 0x01;
static constexpr uint8_t kStateFlagIsConnected = 0x02;
static constexpr uint8_t kStateFlagHasDataPathSet = 0x04;
static constexpr uint8_t kStateFlagIsBroadcast = 0x10;
static constexpr uint8_t kStateFlagIsCancelled = 0x20;
static constexpr uint8_t kStateFlagSettingDataPath = 0x40;

static constexpr IsoClientHandle kDefaultClientHandle = 1;

constexpr char kBtmLogTag[] = "ISO";

struct iso_sync_info {
  uint16_t tx_seq_nb;
  uint16_t rx_seq_nb;
};

struct iso_group {
  uint8_t id;  // cig id or big handle.
  IsoClientHandle client_handle;
  std::vector<uint16_t> stream_conn_handles;
};

struct iso_stream {
  uint16_t conn_handle;
  uint8_t group_id;  // cig id or big handle this stream belongs to.

  struct iso_sync_info sync_info;
  std::atomic_uint8_t state_flags;
  uint32_t sdu_itv;
  std::atomic_uint16_t used_credits;

  struct credits_stats {
    size_t credits_underflow_bytes = 0;
    size_t credits_underflow_count = 0;
    uint64_t credits_last_underflow_us = 0;
  };

  struct event_stats {
    size_t evt_lost_count = 0;
    size_t seq_nb_mismatch_count = 0;
    uint64_t evt_last_lost_us = 0;
  };

  credits_stats cr_stats;
  event_stats evt_stats;
};

struct iso_impl {
  iso_impl() {
    iso_credits_ = shim::GetController()->GetControllerIsoBufferSize().total_num_le_packets_;
    iso_buffer_size_ = shim::GetController()->GetControllerIsoBufferSize().le_data_packet_length_;
    log::info("{} created, iso credits: {}, buffer size: {}.", std::format_ptr(this),
              iso_credits_.load(), iso_buffer_size_);
  }

  ~iso_impl() { log::info("{} removed.", std::format_ptr(this)); }

  IsoManagerCallbacks* get_client_callbacks_from_cig(uint8_t cig_id) {
    const std::lock_guard<std::mutex> lock(iso_client_mutex_);
    auto group_it = cig_id_to_group_map_.find(cig_id);
    if (group_it == cig_id_to_group_map_.end()) {
      return nullptr;
    }
    auto client_it = iso_clients_.find(group_it->second->client_handle);
    if (client_it == iso_clients_.end()) {
      return nullptr;
    }
    return &client_it->second;
  }

  IsoManagerCallbacks* get_client_callbacks_from_big(uint8_t big_handle) {
    const std::lock_guard<std::mutex> lock(iso_client_mutex_);
    auto group_it = big_handle_to_group_map_.find(big_handle);
    if (group_it == big_handle_to_group_map_.end()) {
      return nullptr;
    }
    auto client_it = iso_clients_.find(group_it->second->client_handle);
    if (client_it == iso_clients_.end()) {
      return nullptr;
    }
    return &client_it->second;
  }

  IsoManagerCallbacks* get_client_callbacks_from_stream(iso_stream* stream) {
    if (stream == nullptr) {
      return nullptr;
    }
    return (stream->state_flags & kStateFlagIsBroadcast)
                   ? get_client_callbacks_from_big(stream->group_id)
                   : get_client_callbacks_from_cig(stream->group_id);
  }

  IsoClientHandle register_callbacks(IsoManagerCallbacks callbacks) {
    const std::lock_guard<std::mutex> lock(iso_client_mutex_);

    if (!com_android_bluetooth_flags_btm_multi_client_support()) {
      if (iso_clients_.find(kDefaultClientHandle) == iso_clients_.end()) {
        iso_clients_.emplace(kDefaultClientHandle, IsoManagerCallbacks{});
      }

      if (callbacks.cig_callbacks) {
        iso_clients_.at(kDefaultClientHandle).cig_callbacks = callbacks.cig_callbacks;
      }

      if (callbacks.big_callbacks) {
        iso_clients_.at(kDefaultClientHandle).big_callbacks = callbacks.big_callbacks;
      }

      if (callbacks.iso_traffic_active_callback) {
        iso_traffic_active_callbacks_list_.push_back(callbacks.iso_traffic_active_callback);
      }

      return kDefaultClientHandle;
    }

    IsoClientHandle client_handle;
    if (!freed_iso_client_handles_.empty()) {
      client_handle = freed_iso_client_handles_.back();
      freed_iso_client_handles_.pop_back();
    } else {
      // In case of wrap around, we need to find a free handle
      do {
        client_handle = next_iso_client_handle_++;
        if (next_iso_client_handle_ == 0) {
          next_iso_client_handle_ = 1;
        }
      } while (iso_clients_.count(client_handle));
    }
    iso_clients_.emplace(client_handle, callbacks);
    return client_handle;
  }

  void deregister_callbacks(IsoClientHandle client_handle) {
    const std::lock_guard<std::mutex> lock(iso_client_mutex_);
    if (!com_android_bluetooth_flags_btm_multi_client_support()) {
      return;
    }
    if (iso_clients_.erase(client_handle)) {
      freed_iso_client_handles_.push_back(client_handle);
    }
  }

  void notify_iso_traffic_active(bool is_active) {
    const std::lock_guard<std::mutex> lock(iso_client_mutex_);
    if (com_android_bluetooth_flags_btm_multi_client_support()) {
      for (const auto& [_, iso_client] : iso_clients_) {
        if (iso_client.iso_traffic_active_callback) {
          iso_client.iso_traffic_active_callback(is_active);
        }
      }
    } else {
      for (const auto& callback : iso_traffic_active_callbacks_list_) {
        callback(is_active);
      }
    }
  }

  void on_set_cig_params(uint8_t cig_id, uint32_t sdu_itv_mtos, uint8_t* stream, uint16_t len) {
    uint8_t cis_cnt;
    uint16_t conn_handle;
    cig_create_cmpl_evt evt;

    log::assert_that(len >= 3, "Invalid packet length: {}", len);

    STREAM_TO_UINT8(evt.status, stream);
    STREAM_TO_UINT8(evt.cig_id, stream);
    STREAM_TO_UINT8(cis_cnt, stream);

    uint8_t evt_code =
            IsCigKnown(cig_id) ? kIsoEventCigOnReconfigureCmpl : kIsoEventCigOnCreateCmpl;

    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "CIG Create complete",
                   std::format("cig_id:0x{:02x}, status: {}", evt.cig_id,
                               hci_status_code_text((tHCI_STATUS)(evt.status))));

    size_t stream_sz_before_cig_create = conn_hdl_to_iso_stream_map_.size();

    if (evt.status == HCI_SUCCESS) {
      log::assert_that(len >= (3) + (cis_cnt * sizeof(uint16_t)), "Invalid CIS count: {}", cis_cnt);

      auto group_it = cig_id_to_group_map_.find(evt.cig_id);
      log::assert_that(group_it != cig_id_to_group_map_.end(), "Cannot find group for cig_id: {}",
                       evt.cig_id);

      /* Remove entries for the reconfigured CIG */
      if (evt_code == kIsoEventCigOnReconfigureCmpl) {
        for (auto handle : group_it->second->stream_conn_handles) {
          conn_hdl_to_iso_stream_map_.erase(handle);
        }
        group_it->second->stream_conn_handles.clear();
      }

      evt.conn_handles.reserve(cis_cnt);
      for (int i = 0; i < cis_cnt; i++) {
        STREAM_TO_UINT16(conn_handle, stream);

        evt.conn_handles.push_back(conn_handle);
        group_it->second->stream_conn_handles.push_back(conn_handle);

        auto stream_ptr = std::make_unique<iso_stream>();
        stream_ptr->conn_handle = conn_handle;
        stream_ptr->group_id = cig_id;
        stream_ptr->sdu_itv = sdu_itv_mtos;
        stream_ptr->sync_info = {.tx_seq_nb = 0, .rx_seq_nb = 0};
        stream_ptr->used_credits = 0;
        stream_ptr->state_flags = kStateFlagsNone;
        conn_hdl_to_iso_stream_map_[conn_handle] = std::move(stream_ptr);
      }
    }

    auto* iso_client = get_client_callbacks_from_cig(cig_id);
    log::assert_that(iso_client != nullptr, "Invalid iso_client for cig {}", cig_id);
    log::assert_that(iso_client->cig_callbacks != nullptr, "Invalid CIG callbacks");
    iso_client->cig_callbacks->OnCigEvent(evt_code, &evt);

    if (evt_code == kIsoEventCigOnCreateCmpl && !stream_sz_before_cig_create) {
      // Only set active for the first stream setup.
      notify_iso_traffic_active(true);
    }
  }

  void create_cig(IsoClientHandle client_handle, uint8_t cig_id,
                  struct iso_manager::cig_create_params cig_params) {
    log::assert_that(!IsCigKnown(cig_id), "Invalid cig - already exists: {}", cig_id);

    {
      const std::lock_guard<std::mutex> lock(iso_client_mutex_);
      auto group = std::make_unique<iso_group>();
      group->id = cig_id;
      group->client_handle = client_handle;
      cig_id_to_group_map_[cig_id] = std::move(group);
    }

    btsnd_hcic_ble_set_cig_params(
            cig_id, cig_params.sdu_itv_mtos, cig_params.sdu_itv_stom, cig_params.sca,
            cig_params.packing, cig_params.framing, cig_params.max_trans_lat_stom,
            cig_params.max_trans_lat_mtos, cig_params.cis_cfgs.size(), cig_params.cis_cfgs.data(),
            base::BindOnce(&iso_impl::on_set_cig_params, weak_factory_.GetWeakPtr(), cig_id,
                           cig_params.sdu_itv_mtos));

    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "CIG Create",
                   std::format("cig_id:0x{:02x}, size: {}", cig_id, cig_params.cis_cfgs.size()));
  }

  void reconfigure_cig(uint8_t cig_id, struct iso_manager::cig_create_params cig_params) {
    log::assert_that(IsCigKnown(cig_id), "No such cig: {}", cig_id);

    btsnd_hcic_ble_set_cig_params(
            cig_id, cig_params.sdu_itv_mtos, cig_params.sdu_itv_stom, cig_params.sca,
            cig_params.packing, cig_params.framing, cig_params.max_trans_lat_stom,
            cig_params.max_trans_lat_mtos, cig_params.cis_cfgs.size(), cig_params.cis_cfgs.data(),
            base::BindOnce(&iso_impl::on_set_cig_params, weak_factory_.GetWeakPtr(), cig_id,
                           cig_params.sdu_itv_mtos));
  }

  void on_remove_cig(uint8_t cig_id, uint8_t* stream, uint16_t len) {
    cig_remove_cmpl_evt evt;

    log::assert_that(len == 2, "Invalid packet length: {}", len);

    STREAM_TO_UINT8(evt.status, stream);
    STREAM_TO_UINT8(evt.cig_id, stream);

    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "CIG Remove complete",
                   std::format("cig_id:0x{:02x}, status: {}", evt.cig_id,
                               hci_status_code_text((tHCI_STATUS)(evt.status))));

    auto* iso_client = get_client_callbacks_from_cig(cig_id);
    log::assert_that(iso_client != nullptr, "Invalid iso_client for cig {}", cig_id);
    log::assert_that(iso_client->cig_callbacks != nullptr, "Invalid CIG callbacks");
    iso_client->cig_callbacks->OnCigEvent(kIsoEventCigOnRemoveCmpl, &evt);

    if (evt.status == HCI_SUCCESS) {
      auto group_it = cig_id_to_group_map_.find(evt.cig_id);
      if (group_it != cig_id_to_group_map_.end()) {
        for (auto handle : group_it->second->stream_conn_handles) {
          conn_hdl_to_iso_stream_map_.erase(handle);
        }
        cig_id_to_group_map_.erase(group_it);
      }
    }

    if (!conn_hdl_to_iso_stream_map_.empty()) {
      return;
    }

    // Only set inactive after last stream removal.
    notify_iso_traffic_active(false);
  }

  void remove_cig(uint8_t cig_id, bool force) {
    if (!force) {
      log::assert_that(IsCigKnown(cig_id), "No such cig: {}", cig_id);
    } else {
      log::warn("Forcing to remove CIG {}", cig_id);
    }

    btsnd_hcic_ble_remove_cig(
            cig_id, base::BindOnce(&iso_impl::on_remove_cig, weak_factory_.GetWeakPtr(), cig_id));
    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "CIG Remove",
                   std::format("cig_id:0x{:02x} (f:{})", cig_id, force));
  }

  void on_status_establish_cis(struct iso_manager::cis_establish_params conn_params,
                               uint8_t* stream, uint16_t len) {
    uint8_t status;

    log::assert_that(len == 2, "Invalid packet length: {}", len);

    STREAM_TO_UINT16(status, stream);

    for (auto cis_param : conn_params.conn_pairs) {
      cis_establish_cmpl_evt evt;

      auto stream_ptr = GetStream(cis_param.cis_conn_handle);
      log::assert_that(stream_ptr != nullptr, "No such cis: {}", cis_param.cis_conn_handle);

      auto device_address = cis_hdl_to_addr[cis_param.cis_conn_handle];

      if (status != HCI_SUCCESS) {
        evt.status = status;
        evt.cis_conn_hdl = cis_param.cis_conn_handle;
        evt.cig_id = stream_ptr->group_id;
        stream_ptr->state_flags &= ~kStateFlagIsConnecting;

        auto* client_cbs = get_client_callbacks_from_stream(stream_ptr);
        log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                         stream_ptr->conn_handle);
        log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");
        client_cbs->cig_callbacks->OnCisEvent(kIsoEventCisEstablishCmpl, &evt);

        BTM_LogHistory(kBtmLogTag, device_address, "Establish CIS failed ",
                       std::format("handle:0x{:04x}, status:{}", evt.cis_conn_hdl,
                                   hci_status_code_text((tHCI_STATUS)(status))));
        cis_hdl_to_addr.erase(evt.cis_conn_hdl);
      }

      log::verbose("{}, cis_handle: {:#x}, flags: {:#x}, status {}", device_address,
                   cis_param.cis_conn_handle, stream_ptr->state_flags,
                   hci_status_code_text((tHCI_STATUS)(status)));
    }
  }

  void establish_cis(struct iso_manager::cis_establish_params conn_params) {
    for (auto& el : conn_params.conn_pairs) {
      auto stream_ptr = GetStream(el.cis_conn_handle);
      log::assert_that(stream_ptr, "No such cis: {}", el.cis_conn_handle);
      log::assert_that(!(stream_ptr->state_flags &
                         (kStateFlagIsConnected | kStateFlagIsConnecting | kStateFlagIsCancelled)),
                       "cis: {} is already connected/connecting/cancelled flags: {}, "
                       "num of cis params: {}",
                       el.cis_conn_handle, stream_ptr->state_flags, conn_params.conn_pairs.size());

      stream_ptr->state_flags |= kStateFlagIsConnecting;

      const BtmDevice* p_device = btm_find_dev_by_handle(el.acl_conn_handle);
      if (p_device) {
        cis_hdl_to_addr[el.cis_conn_handle] = p_device->ble.pseudo_addr;
        BTM_LogHistory(kBtmLogTag, p_device->ble.pseudo_addr, "Establish CIS",
                       std::format("handle:0x{:04x}", el.acl_conn_handle));
      }
      log::verbose("{}, cis_handle: {:#x}, flags: {:#x}", cis_hdl_to_addr[el.cis_conn_handle],
                   el.cis_conn_handle, stream_ptr->state_flags);
    }
    btsnd_hcic_ble_create_cis(conn_params.conn_pairs.size(), conn_params.conn_pairs.data(),
                              base::BindOnce(&iso_impl::on_status_establish_cis,
                                             weak_factory_.GetWeakPtr(), conn_params));
  }

  void disconnect_cis(uint16_t cis_handle, uint8_t reason) {
    auto stream_ptr = GetStream(cis_handle);
    log::assert_that(stream_ptr, "No such cis: {}", cis_handle);
    log::assert_that(stream_ptr->state_flags & kStateFlagIsConnected ||
                             stream_ptr->state_flags & kStateFlagIsConnecting,
                     "Not connected");

    if (stream_ptr->state_flags & kStateFlagIsConnecting) {
      if (!com_android_bluetooth_flags_btm_iso_improve_canceling_iso()) {
        stream_ptr->state_flags &= ~kStateFlagIsConnecting;
      }
      stream_ptr->state_flags |= kStateFlagIsCancelled;
    }

    bluetooth::legacy::hci::GetInterface().Disconnect(cis_handle, static_cast<tHCI_STATUS>(reason));

    BTM_LogHistory(kBtmLogTag, cis_hdl_to_addr[cis_handle], "Disconnect CIS ",
                   std::format("handle:0x{:04x}, reason:{}", cis_handle,
                               hci_reason_code_text((tHCI_REASON)(reason))));
    log::verbose("{}, cis_handle: {:#x}, flags: {:#x}", cis_hdl_to_addr[cis_handle], cis_handle,
                 stream_ptr->state_flags);
  }

  int get_number_of_active_iso() {
    int num_iso = conn_hdl_to_iso_stream_map_.size();
    log::info("Current number of active_iso is {}", num_iso);
    return num_iso;
  }

  void on_setup_iso_data_path(uint8_t* stream, uint16_t /* len */) {
    uint8_t status;
    uint16_t conn_handle;

    STREAM_TO_UINT8(status, stream);
    STREAM_TO_UINT16(conn_handle, stream);

    iso_stream* iso = GetStream(conn_handle);
    if (iso == nullptr) {
      /* That can happen when ACL has been disconnected while ISO patch was
       * creating */
      log::warn("Invalid connection handle: {}", conn_handle);
      return;
    }

    auto* client_cbs = get_client_callbacks_from_stream(iso);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     conn_handle);

    BTM_LogHistory(kBtmLogTag, cis_hdl_to_addr[conn_handle], "Setup data path complete",
                   std::format("handle:0x{:04x}, status:{}", conn_handle,
                               hci_status_code_text((tHCI_STATUS)(status))));

    log::verbose("{}, conn_handle: {:#x}, flags: {:#x} status {}", cis_hdl_to_addr[conn_handle],
                 conn_handle, iso->state_flags, hci_status_code_text((tHCI_STATUS)(status)));

    iso->state_flags &= ~kStateFlagSettingDataPath;

    if (status == HCI_SUCCESS) {
      iso->state_flags |= kStateFlagHasDataPathSet;
    }

    if (iso->state_flags & kStateFlagIsBroadcast) {
      log::assert_that(client_cbs->big_callbacks != nullptr, "Invalid BIG callbacks");
      client_cbs->big_callbacks->OnSetupIsoDataPath(status, conn_handle, iso->group_id);
    } else {
      log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");
      client_cbs->cig_callbacks->OnSetupIsoDataPath(status, conn_handle, iso->group_id);
    }
  }

  void setup_iso_data_path(uint16_t conn_handle,
                           struct iso_manager::iso_data_path_params path_params) {
    iso_stream* iso = GetStream(conn_handle);
    log::assert_that(iso != nullptr, "No such iso connection: {}", conn_handle);

    if (!(iso->state_flags & kStateFlagIsBroadcast)) {
      log::assert_that(iso->state_flags & kStateFlagIsConnected, "CIS not established");
    }

    iso->state_flags |= kStateFlagSettingDataPath;

    btsnd_hcic_ble_setup_iso_data_path(
            conn_handle, path_params.data_path_dir, path_params.data_path_id,
            path_params.codec_id_format, path_params.codec_id_company, path_params.codec_id_vendor,
            path_params.controller_delay, std::move(path_params.codec_conf),
            base::BindOnce(&iso_impl::on_setup_iso_data_path, weak_factory_.GetWeakPtr()));
    BTM_LogHistory(kBtmLogTag, cis_hdl_to_addr[conn_handle], "Setup data path",
                   std::format("handle:0x{:04x}, dir:0x{:02x}, path_id:0x{:02x}, codec_id:0x{:02x}",
                               conn_handle, path_params.data_path_dir, path_params.data_path_id,
                               path_params.codec_id_format));
  }

  void on_remove_iso_data_path(uint8_t* stream, uint16_t len) {
    uint8_t status;
    uint16_t conn_handle;

    if (len < 3) {
      log::warn("Malformatted packet received");
      return;
    }
    STREAM_TO_UINT8(status, stream);
    STREAM_TO_UINT16(conn_handle, stream);

    iso_stream* iso = GetStream(conn_handle);
    if (iso == nullptr) {
      /* That could happen when ACL has been disconnected while removing data
       * path */
      log::warn("Invalid connection handle: {}", conn_handle);
      return;
    }

    auto* client_cbs = get_client_callbacks_from_stream(iso);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     conn_handle);

    BTM_LogHistory(kBtmLogTag, cis_hdl_to_addr[conn_handle], "Remove data path complete",
                   std::format("handle:0x{:04x}, status:{}", conn_handle,
                               hci_status_code_text((tHCI_STATUS)(status))));
    log::verbose("{}, conn_handle: {:#x}, flags: {:#x} status {}", cis_hdl_to_addr[conn_handle],
                 conn_handle, iso->state_flags, hci_status_code_text((tHCI_STATUS)(status)));

    if (status == HCI_SUCCESS) {
      iso->state_flags &= ~kStateFlagHasDataPathSet;
    }

    if (iso->state_flags & kStateFlagIsBroadcast) {
      log::assert_that(client_cbs->big_callbacks != nullptr, "Invalid BIG callbacks");
      client_cbs->big_callbacks->OnRemoveIsoDataPath(status, conn_handle, iso->group_id);
    } else {
      log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");
      client_cbs->cig_callbacks->OnRemoveIsoDataPath(status, conn_handle, iso->group_id);
    }
  }

  void remove_iso_data_path(uint16_t conn_handle, uint8_t data_path_dir) {
    iso_stream* iso = GetStream(conn_handle);
    log::assert_that(iso != nullptr, "No such iso connection: 0x{:x}", conn_handle);
    log::assert_that(
            (iso->state_flags & (kStateFlagHasDataPathSet | kStateFlagSettingDataPath)) != 0,
            "Data path not set");

    btsnd_hcic_ble_remove_iso_data_path(
            conn_handle, data_path_dir,
            base::BindOnce(&iso_impl::on_remove_iso_data_path, weak_factory_.GetWeakPtr()));

    BTM_LogHistory(kBtmLogTag, cis_hdl_to_addr[conn_handle], "Remove data path",
                   std::format("handle:0x{:04x}, dir:0x{:02x}", conn_handle, data_path_dir));
    log::verbose("{}, conn_handle: {:#x}, flags: {:#x} dir {:#x}", cis_hdl_to_addr[conn_handle],
                 conn_handle, iso->state_flags, data_path_dir);
  }

  void on_iso_link_quality_read(uint8_t* stream, uint16_t len) {
    uint8_t status;
    uint16_t conn_handle;
    uint32_t txUnackedPackets;
    uint32_t txFlushedPackets;
    uint32_t txLastSubeventPackets;
    uint32_t retransmittedPackets;
    uint32_t crcErrorPackets;
    uint32_t rxUnreceivedPackets;
    uint32_t duplicatePackets;

    // 1 + 2 + 4 * 7
#define ISO_LINK_QUALITY_SIZE 31
    if (len < ISO_LINK_QUALITY_SIZE) {
      log::error("Malformated link quality format, len={}", len);
      return;
    }

    STREAM_TO_UINT8(status, stream);
    if (status != HCI_SUCCESS) {
      log::error("Failed to Read ISO Link Quality, status: 0x{:x}", status);
      return;
    }

    STREAM_TO_UINT16(conn_handle, stream);

    iso_stream* iso = GetStream(conn_handle);
    if (iso == nullptr) {
      /* That could happen when ACL has been disconnected while waiting on the
       * read respose */
      log::warn("Invalid connection handle: {}", conn_handle);
      return;
    }

    auto* client_cbs = get_client_callbacks_from_stream(iso);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     conn_handle);
    log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");

    STREAM_TO_UINT32(txUnackedPackets, stream);
    STREAM_TO_UINT32(txFlushedPackets, stream);
    STREAM_TO_UINT32(txLastSubeventPackets, stream);
    STREAM_TO_UINT32(retransmittedPackets, stream);
    STREAM_TO_UINT32(crcErrorPackets, stream);
    STREAM_TO_UINT32(rxUnreceivedPackets, stream);
    STREAM_TO_UINT32(duplicatePackets, stream);

    client_cbs->cig_callbacks->OnIsoLinkQualityRead(
            conn_handle, iso->group_id, txUnackedPackets, txFlushedPackets, txLastSubeventPackets,
            retransmittedPackets, crcErrorPackets, rxUnreceivedPackets, duplicatePackets);
  }

  void read_iso_link_quality(uint16_t conn_handle) {
    iso_stream* iso = GetStream(conn_handle);
    if (iso == nullptr) {
      log::error("No such iso connection: 0x{:x}", conn_handle);
      return;
    }

    btsnd_hcic_ble_read_iso_link_quality(
            conn_handle,
            base::BindOnce(&iso_impl::on_iso_link_quality_read, weak_factory_.GetWeakPtr()));
  }

  BT_HDR* prepare_hci_packet(uint16_t conn_handle, uint16_t seq_nb, uint16_t data_len) {
    /* Add 2 for packet seq., 2 for length */
    uint16_t iso_data_load_len = data_len + 4;

    /* Add 2 for handle, 2 for length */
    uint16_t iso_full_len = iso_data_load_len + 4;
    BT_HDR* packet = (BT_HDR*)osi_malloc(iso_full_len + sizeof(BT_HDR));
    packet->len = iso_full_len;
    packet->offset = 0;
    packet->event = MSG_STACK_TO_HC_HCI_ISO;
    packet->layer_specific = 0;

    uint8_t* packet_data = packet->data;
    UINT16_TO_STREAM(packet_data, conn_handle);
    UINT16_TO_STREAM(packet_data, iso_data_load_len);

    UINT16_TO_STREAM(packet_data, seq_nb);
    UINT16_TO_STREAM(packet_data, data_len);

    return packet;
  }

  void send_iso_data(uint16_t conn_handle, const uint8_t* data, uint16_t data_len) {
    iso_stream* iso = GetStream(conn_handle);
    log::assert_that(iso != nullptr, "No such iso connection handle: 0x{:x}", conn_handle);

    if (!(iso->state_flags & kStateFlagIsBroadcast)) {
      if (!(iso->state_flags & kStateFlagIsConnected)) {
        log::warn("Cis handle: 0x{:x} not established", conn_handle);
        return;
      }
    }

    if (!(iso->state_flags & kStateFlagHasDataPathSet)) {
      log::warn("Data path not set for handle: 0x{:04x}", conn_handle);
      return;
    }

    /* Calculate sequence number for the ISO data packet.
     * It should be incremented by 1 every SDU Interval.
     */
    uint16_t seq_nb = iso->sync_info.tx_seq_nb;
    iso->sync_info.tx_seq_nb = (seq_nb + 1) & 0xffff;

    if (iso_credits_ == 0 || data_len > iso_buffer_size_) {
      iso->cr_stats.credits_underflow_bytes += data_len;
      iso->cr_stats.credits_underflow_count++;
      iso->cr_stats.credits_last_underflow_us = bluetooth::common::time_get_os_boottime_us();

      log::warn(", dropping ISO packet, len: {}, iso credits: {}, iso handle: 0x{:x}",
                static_cast<int>(data_len), static_cast<int>(iso_credits_), conn_handle);
      return;
    }

    iso_credits_--;
    iso->used_credits++;

    BT_HDR* packet = prepare_hci_packet(conn_handle, seq_nb, data_len);
    memcpy(packet->data + kIsoHeaderWithoutTsLen, data, data_len);
    auto hci = bluetooth::shim::hci_layer_get_interface();
    packet->event = MSG_STACK_TO_HC_HCI_ISO | 0x0001;
    hci->transmit_downward(packet, iso_buffer_size_);
  }

  void send_disconnect_complete_event(IsoManagerCallbacks* client_cbs, uint8_t cig_id,
                                      uint16_t handle, uint8_t reason) {
    cis_disconnected_evt evt = {
            .reason = reason,
            .cig_id = cig_id,
            .cis_conn_hdl = handle,
    };

    client_cbs->cig_callbacks->OnCisEvent(kIsoEventCisDisconnected, &evt);
  }

  void handle_race_on_canceling_cis(iso_stream* stream_ptr, uint16_t handle) {
    /* In case of race when Host being sending HCI Disconnect just in time when Controller already
     * scheduled CIS Established event, btm_iso should also handled it. We have two cases here:
     * either CIS managed to be connected or failed to connect*/
    stream_ptr->state_flags &= ~kStateFlagIsCancelled;
    if (stream_ptr->state_flags & kStateFlagIsConnected) {
      /* If CIS managed to connect while user requested to cancel, btm_iso should not notify upper
       * layer about created CIS but silently wait for this disconnect completed event which will
       * come soon.
       */
      log::warn(
              "cis: {:#x} got connected just before it was canceled - do not send Established "
              "event but instead wait for disconnection complete event. Flags: {:#x} ",
              handle, stream_ptr->state_flags);

      return;
    }
    /* In case CIS failed to be established just before host sent HCI DIsconnect. In such a case,
     * just send Disconnect Complete event*/
    log::warn(
            "cis: {:#x} failed to connect just before it was canceled - send disconnect complete "
            "event. Flags: {:#x}",
            handle, stream_ptr->state_flags);
    auto* client_cbs = get_client_callbacks_from_stream(stream_ptr);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     stream_ptr->conn_handle);
    log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");

    send_disconnect_complete_event(client_cbs, stream_ptr->group_id, handle,
                                   HCI_ERR_CONN_CAUSE_LOCAL_HOST);
  }

  void process_cis_est_pkt(uint8_t len, uint8_t* data) {
    cis_establish_cmpl_evt evt;

    log::assert_that(len == 28, "Invalid packet length: {}", len);

    STREAM_TO_UINT8(evt.status, data);
    STREAM_TO_UINT16(evt.cis_conn_hdl, data);

    auto stream_ptr = GetStream(evt.cis_conn_hdl);
    log::assert_that(stream_ptr != nullptr, "No such cis: {}", evt.cis_conn_hdl);

    auto* client_cbs = get_client_callbacks_from_stream(stream_ptr);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     stream_ptr->conn_handle);
    log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");

    BTM_LogHistory(
            kBtmLogTag, cis_hdl_to_addr[evt.cis_conn_hdl], "CIS established event",
            std::format("cis_handle:0x{:04x} status:{} flags:{:#x}", evt.cis_conn_hdl,
                        hci_error_code_text((tHCI_STATUS)(evt.status)), stream_ptr->state_flags));

    STREAM_TO_UINT24(evt.cig_sync_delay, data);
    STREAM_TO_UINT24(evt.cis_sync_delay, data);
    STREAM_TO_UINT24(evt.trans_lat_mtos, data);
    STREAM_TO_UINT24(evt.trans_lat_stom, data);
    STREAM_TO_UINT8(evt.phy_mtos, data);
    STREAM_TO_UINT8(evt.phy_stom, data);
    STREAM_TO_UINT8(evt.nse, data);
    STREAM_TO_UINT8(evt.bn_mtos, data);
    STREAM_TO_UINT8(evt.bn_stom, data);
    STREAM_TO_UINT8(evt.ft_mtos, data);
    STREAM_TO_UINT8(evt.ft_stom, data);
    STREAM_TO_UINT16(evt.max_pdu_mtos, data);
    STREAM_TO_UINT16(evt.max_pdu_stom, data);
    STREAM_TO_UINT16(evt.iso_itv, data);

    stream_ptr->state_flags &= ~kStateFlagIsConnecting;

    if (evt.status == HCI_SUCCESS) {
      stream_ptr->state_flags |= kStateFlagIsConnected;
    } else {
      if (evt.status == HCI_ERR_CANCELLED_BY_LOCAL_HOST) {
        /* kStateFlagIsCancelled is cleared in disconnection complete event
         * which shall also arrive during CIS cancel procedure. If flag is
         * cleared it means that Disconnect Complete Event arrived before this
         * CIS established event. This is also fine. In such case clear address
         * to handle mapping (which is used only for logs) and send
         * Disconnect Complete event. Otherwise, wait with clearing it
         * until Disconnect Complete event arrives
         */

        if (!(stream_ptr->state_flags & kStateFlagIsCancelled)) {
          log::info(
                  "Flag kStateFlagIsCancelled already cleared, means Disconnect Complete arrived "
                  "before this event.");
          cis_hdl_to_addr.erase(evt.cis_conn_hdl);
          if (com_android_bluetooth_flags_btm_iso_improve_canceling_iso()) {
            log::info("cis: {:#x} cancelation completed, send disconnect complete event",
                      evt.cis_conn_hdl);
            send_disconnect_complete_event(client_cbs, stream_ptr->group_id, evt.cis_conn_hdl,
                                           HCI_ERR_CONN_CAUSE_LOCAL_HOST);
            return;
          }
        } else if (com_android_bluetooth_flags_btm_iso_improve_canceling_iso()) {
          log::info(
                  "Skip sending Established event for canceled cis: {:#x} flags: {:#x}, wait for "
                  "disconnect complete event",
                  evt.cis_conn_hdl, stream_ptr->state_flags);
          return;
        }
      } else {
        cis_hdl_to_addr.erase(evt.cis_conn_hdl);
      }
    }

    if (com_android_bluetooth_flags_btm_iso_improve_canceling_iso() &&
        (stream_ptr->state_flags & kStateFlagIsCancelled)) {
      handle_race_on_canceling_cis(stream_ptr, evt.cis_conn_hdl);
      return;
    }
    evt.cig_id = stream_ptr->group_id;

    client_cbs->cig_callbacks->OnCisEvent(kIsoEventCisEstablishCmpl, &evt);
  }

  void disconnection_complete(uint16_t handle, uint8_t reason) {
    /* Check if this is an ISO handle */
    auto stream_ptr = GetStream(handle);
    if (stream_ptr == nullptr) {
      return;
    }

    log::info("{}, cis_handle {:#x} flags: {}", cis_hdl_to_addr[handle], handle,
              stream_ptr->state_flags);

    BTM_LogHistory(kBtmLogTag, cis_hdl_to_addr[handle], "CIS disconnected",
                   std::format("cis_handle:0x{:04x}, reason:{}", handle,
                               hci_error_code_text((tHCI_REASON)(reason))));

    if (stream_ptr->state_flags & kStateFlagIsConnecting) {
      log::info("{}, cis_handle: {:#x} waiting for cis established event with cancel status",
                cis_hdl_to_addr[handle], handle);
    } else {
      cis_hdl_to_addr.erase(handle);
    }

    if (!(stream_ptr->state_flags & kStateFlagIsConnected) &&
        !(stream_ptr->state_flags & kStateFlagIsCancelled)) {
      log::warn("Unexpected {} cis: {:#x} disconnected, flags: {:#x}", cis_hdl_to_addr[handle],
                handle, stream_ptr->state_flags);
      return;
    }

    stream_ptr->state_flags &= ~kStateFlagIsConnected;

    /* return used credits */
    iso_credits_ += stream_ptr->used_credits;
    stream_ptr->used_credits = 0;

    if (com_android_bluetooth_flags_btm_iso_improve_canceling_iso() &&
        (stream_ptr->state_flags & kStateFlagIsCancelled)) {
      if (stream_ptr->state_flags & kStateFlagIsConnecting) {
        stream_ptr->state_flags &= ~kStateFlagIsCancelled;
        log::info(
                "Waiting for the CIS Established Event for cancel indication for {}, cis: {:#x}, "
                "flags: {:#x}",
                cis_hdl_to_addr[handle], handle, stream_ptr->state_flags);
        return;
      }
      log::info("cis: {:#x} cancelation completed, send disconnect complete event", handle);
    }

    stream_ptr->state_flags &= ~kStateFlagIsCancelled;

    auto client_cbs = get_client_callbacks_from_stream(stream_ptr);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     stream_ptr->conn_handle);
    log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");

    send_disconnect_complete_event(client_cbs, stream_ptr->group_id, handle, reason);

    /* Note:  Data path is considered still valid, but can be reconfigured only once
     * CIS is reestablished.
     */
  }

  void handle_gd_num_completed_pkts(uint16_t handle, uint16_t credits) {
    auto iter = conn_hdl_to_iso_stream_map_.find(handle);
    if (iter != conn_hdl_to_iso_stream_map_.end()) {
      iter->second->used_credits -= credits;
      iso_credits_ += credits;
    }
  }

  void process_create_big_cmpl_pkt(uint8_t len, uint8_t* data) {
    struct big_create_cmpl_evt evt;

    log::assert_that(len >= 18, "Invalid packet length: {}", len);

    STREAM_TO_UINT8(evt.status, data);
    STREAM_TO_UINT8(evt.big_handle, data);
    STREAM_TO_UINT24(evt.big_sync_delay, data);
    STREAM_TO_UINT24(evt.transport_latency_big, data);
    STREAM_TO_UINT8(evt.phy, data);
    STREAM_TO_UINT8(evt.nse, data);
    STREAM_TO_UINT8(evt.bn, data);
    STREAM_TO_UINT8(evt.pto, data);
    STREAM_TO_UINT8(evt.irc, data);
    STREAM_TO_UINT16(evt.max_pdu, data);
    STREAM_TO_UINT16(evt.iso_interval, data);

    uint8_t num_bis;
    STREAM_TO_UINT8(num_bis, data);

    log::assert_that(num_bis != 0, "Bis count is 0");
    log::assert_that(len == (18 + num_bis * sizeof(uint16_t)),
                     "Invalid packet length: {}. Number of bis: {}", len, num_bis);

    auto group_it = big_handle_to_group_map_.find(evt.big_handle);
    log::assert_that(group_it != big_handle_to_group_map_.end(),
                     "Cannot find group for big_handle: {}", evt.big_handle);

    size_t stream_sz_before_big_create = conn_hdl_to_iso_stream_map_.size();

    for (auto i = 0; i < num_bis; ++i) {
      uint16_t conn_handle;
      STREAM_TO_UINT16(conn_handle, data);
      evt.conn_handles.push_back(conn_handle);
      group_it->second->stream_conn_handles.push_back(conn_handle);
      log::info("received BIS conn_hdl {}", conn_handle);

      if (evt.status == HCI_SUCCESS) {
        auto stream_ptr = std::make_unique<iso_stream>();
        stream_ptr->conn_handle = conn_handle;
        stream_ptr->group_id = evt.big_handle;
        stream_ptr->sdu_itv = last_big_create_req_sdu_itv_;
        stream_ptr->sync_info = {.tx_seq_nb = 0, .rx_seq_nb = 0};
        stream_ptr->used_credits = 0;
        stream_ptr->state_flags = kStateFlagIsBroadcast;

        log::verbose("BIG_HANDLE {}, bis_handle: {:#x}, flags: {:#x}, status {}", evt.big_handle,
                     conn_handle, stream_ptr->state_flags,
                     hci_status_code_text((tHCI_STATUS)(evt.status)));

        conn_hdl_to_iso_stream_map_[conn_handle] = std::move(stream_ptr);
      }
    }

    auto* client_cbs = get_client_callbacks_from_big(evt.big_handle);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for big {}",
                     evt.big_handle);
    log::assert_that(client_cbs->big_callbacks != nullptr, "Invalid BIG callbacks");
    client_cbs->big_callbacks->OnBigEvent(kIsoEventBigOnCreateCmpl, &evt);

    if (stream_sz_before_big_create) {
      return;
    }

    // Only set active for the first stream setup.
    notify_iso_traffic_active(true);
  }

  void process_terminate_big_cmpl_pkt(uint8_t len, uint8_t* data) {
    struct big_terminate_cmpl_evt evt;

    log::assert_that(len == 2, "Invalid packet length: {}", len);

    STREAM_TO_UINT8(evt.big_handle, data);
    STREAM_TO_UINT8(evt.reason, data);

    auto group_it = big_handle_to_group_map_.find(evt.big_handle);
    log::assert_that(group_it != big_handle_to_group_map_.end(), "No such big: {}", evt.big_handle);

    auto* client_cbs = get_client_callbacks_from_big(evt.big_handle);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for big {}",
                     evt.big_handle);
    log::assert_that(client_cbs->big_callbacks != nullptr, "Invalid BIG callbacks");
    client_cbs->big_callbacks->OnBigEvent(kIsoEventBigOnTerminateCmpl, &evt);

    for (auto handle : group_it->second->stream_conn_handles) {
      conn_hdl_to_iso_stream_map_.erase(handle);
    }
    big_handle_to_group_map_.erase(group_it);

    if (!conn_hdl_to_iso_stream_map_.empty()) {
      return;
    }

    // Only set inactive after last stream removal.
    notify_iso_traffic_active(false);
  }

  void create_big(IsoClientHandle client_handle, uint8_t big_handle,
                  struct big_create_params big_params) {
    log::assert_that(!IsBigKnown(big_handle), "Invalid big - already exists: {}", big_handle);

    {
      const std::lock_guard<std::mutex> lock(iso_client_mutex_);
      auto group = std::make_unique<iso_group>();
      group->id = big_handle;
      group->client_handle = client_handle;
      big_handle_to_group_map_[big_handle] = std::move(group);
    }

    if (stack_config_get_interface()->get_pts_unencrypt_broadcast()) {
      log::info("Force create broadcst without encryption for PTS test");
      big_params.enc = 0;
      big_params.enc_code = {0};
    }

    last_big_create_req_sdu_itv_ = big_params.sdu_itv;
    btsnd_hcic_ble_create_big(big_handle, big_params.adv_handle, big_params.num_bis,
                              big_params.sdu_itv, big_params.max_sdu_size,
                              big_params.max_transport_latency, big_params.rtn, big_params.phy,
                              big_params.packing, big_params.framing, big_params.enc,
                              big_params.enc_code);
  }

  void terminate_big(uint8_t big_handle, uint8_t reason) {
    log::assert_that(IsBigKnown(big_handle), "No such big: {}", big_handle);

    btsnd_hcic_ble_term_big(big_handle, reason);
  }

  void on_iso_event(uint8_t code, uint8_t* packet, uint16_t packet_len) {
    switch (code) {
      case HCI_BLE_CIS_EST_EVT:
        process_cis_est_pkt(packet_len, packet);
        break;
      case HCI_BLE_CREATE_BIG_CPL_EVT:
        process_create_big_cmpl_pkt(packet_len, packet);
        break;
      case HCI_BLE_TERM_BIG_CPL_EVT:
        process_terminate_big_cmpl_pkt(packet_len, packet);
        break;
      case HCI_BLE_CIS_REQ_EVT:
        /* Not supported */
        break;
      case HCI_BLE_BIG_SYNC_EST_EVT:
        /* Not supported */
        break;
      case HCI_BLE_BIG_SYNC_LOST_EVT:
        /* Not supported */
        break;
      default:
        log::error("Unhandled event code {}", code);
    }
  }

  void handle_iso_data(BT_HDR* p_msg) {
    const uint8_t* stream = p_msg->data;
    cis_data_evt evt;
    uint16_t handle, seq_nb;

    if (p_msg->len <= ((p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) ? kIsoHeaderWithTsLen
                                                                        : kIsoHeaderWithoutTsLen)) {
      return;
    }

    STREAM_TO_UINT16(handle, stream);
    evt.cis_conn_hdl = HCID_GET_HANDLE(handle);

    iso_stream* iso = GetStream(evt.cis_conn_hdl);
    if (iso == nullptr) {
      log::error(", received data for the non-registered CIS!");
      return;
    }

    auto* client_cbs = get_client_callbacks_from_stream(iso);
    log::assert_that(client_cbs != nullptr, "Cannot find client callbacks for stream {}",
                     iso->conn_handle);
    log::assert_that(client_cbs->cig_callbacks != nullptr, "Invalid CIG callbacks");

    STREAM_SKIP_UINT16(stream);
    if (p_msg->layer_specific & BT_ISO_HDR_CONTAINS_TS) {
      STREAM_TO_UINT32(evt.ts, stream);
    } else {
      evt.ts = 0;
    }

    STREAM_TO_UINT16(seq_nb, stream);

    uint16_t expected_seq_nb = iso->sync_info.rx_seq_nb;
    iso->sync_info.rx_seq_nb = (seq_nb + 1) & 0xffff;

    evt.evt_lost = ((1 << 16) + seq_nb - expected_seq_nb) & 0xffff;
    if (evt.evt_lost > 0) {
      iso->evt_stats.evt_lost_count += evt.evt_lost;
      iso->evt_stats.evt_last_lost_us = bluetooth::common::time_get_os_boottime_us();

      log::warn("{} packets lost.", evt.evt_lost);
      iso->evt_stats.seq_nb_mismatch_count++;
    }

    evt.p_msg = p_msg;
    evt.cig_id = iso->group_id;
    evt.seq_nb = seq_nb;

    client_cbs->cig_callbacks->OnCisEvent(kIsoEventCisDataAvailable, &evt);
  }

  iso_stream* GetStream(uint16_t conn_handle) {
    auto it = conn_hdl_to_iso_stream_map_.find(conn_handle);
    return (it != conn_hdl_to_iso_stream_map_.end()) ? it->second.get() : nullptr;
  }

  bool IsCigKnown(uint8_t cig_id) const {
    const auto stream_it =
            std::find_if(conn_hdl_to_iso_stream_map_.cbegin(), conn_hdl_to_iso_stream_map_.cend(),
                         [cig_id](const auto& pair) {
                           return pair.second->group_id == cig_id &&
                                  !(pair.second->state_flags & kStateFlagIsBroadcast);
                         });
    return stream_it != conn_hdl_to_iso_stream_map_.cend();
  }

  bool IsBigKnown(uint8_t big_handle) const {
    const auto stream_it =
            std::find_if(conn_hdl_to_iso_stream_map_.cbegin(), conn_hdl_to_iso_stream_map_.cend(),
                         [big_handle](const auto& pair) {
                           return pair.second->group_id == big_handle &&
                                  (pair.second->state_flags & kStateFlagIsBroadcast);
                         });
    return stream_it != conn_hdl_to_iso_stream_map_.cend();
  }

  static void dump_credits_stats(int fd, const iso_stream::credits_stats& stats) {
    uint64_t now_us = bluetooth::common::time_get_os_boottime_us();

    dprintf(fd, "        Credits Stats:\n");
    dprintf(fd, "          Credits underflow (count): %zu\n", stats.credits_underflow_count);
    dprintf(fd, "          Credits underflow (bytes): %zu\n", stats.credits_underflow_bytes);
    dprintf(fd, "          Last underflow time ago (ms): %llu\n",
            stats.credits_last_underflow_us > 0
                    ? (unsigned long long)(now_us - stats.credits_last_underflow_us) / 1000
                    : 0llu);
  }

  static void dump_event_stats(int fd, const iso_stream::event_stats& stats) {
    uint64_t now_us = bluetooth::common::time_get_os_boottime_us();

    dprintf(fd, "        Event Stats:\n");
    dprintf(fd, "          Sequence number mismatch (count): %zu\n", stats.seq_nb_mismatch_count);
    dprintf(fd, "          Event lost (count): %zu\n", stats.evt_lost_count);
    dprintf(fd, "          Last event lost time ago (ms): %llu\n",
            stats.evt_last_lost_us > 0
                    ? (unsigned long long)(now_us - stats.evt_last_lost_us) / 1000
                    : 0llu);
  }

  void dump(int fd) const {
    dprintf(fd, "  ----------------\n ");
    dprintf(fd, "  ISO Manager:\n");
    dprintf(fd, "    Available credits: %d\n", iso_credits_.load());
    dprintf(fd, "    Controller buffer size: %d\n", iso_buffer_size_);
    dprintf(fd, "    CIGs:");
    for (auto const& group_pair : cig_id_to_group_map_) {
      dprintf(fd, "      CIG ID: %d", group_pair.first);
      for (auto const& handle : group_pair.second->stream_conn_handles) {
        auto stream_it = conn_hdl_to_iso_stream_map_.find(handle);
        if (stream_it == conn_hdl_to_iso_stream_map_.end()) {
          continue;
        }
        auto& stream = stream_it->second;
        dprintf(fd, "      CIS Connection handle: %d", stream->conn_handle);
        dprintf(fd, "        Used Credits: %d", stream->used_credits.load());
        dprintf(fd, "        SDU Interval: %d", stream->sdu_itv);
        dprintf(fd, "        State Flags: 0x%02hx", stream->state_flags.load());
        dump_credits_stats(fd, stream->cr_stats);
        dump_event_stats(fd, stream->evt_stats);
      }
    }
    dprintf(fd, "    BIGs:");
    for (auto const& group_pair : big_handle_to_group_map_) {
      dprintf(fd, "      BIG handle: %d", group_pair.first);
      for (auto const& handle : group_pair.second->stream_conn_handles) {
        auto stream_it = conn_hdl_to_iso_stream_map_.find(handle);
        if (stream_it == conn_hdl_to_iso_stream_map_.end()) {
          continue;
        }
        auto& stream = stream_it->second;
        dprintf(fd, "      BIS Connection handle: %d", stream->conn_handle);
        dprintf(fd, "        Used Credits: %d", stream->used_credits.load());
        dprintf(fd, "        SDU Interval: %d", stream->sdu_itv);
        dprintf(fd, "        State Flags: 0x%02hx", stream->state_flags.load());
        dump_credits_stats(fd, stream->cr_stats);
        dump_event_stats(fd, stream->evt_stats);
      }
    }
    dprintf(fd, "  ----------------");
  }

  void set_big_channel_map_classification(uint8_t action, uint8_t big_handle,
                                          const std::vector<uint16_t>& handles) {
    btsnd_hcic_ble_set_big_channel_map_classification_vsc(action, big_handle, handles);
  }

  std::unordered_map<uint16_t, RawAddress> cis_hdl_to_addr;

  std::atomic_uint16_t iso_credits_;
  uint16_t iso_buffer_size_;
  uint32_t last_big_create_req_sdu_itv_;

  std::list<std::function<void(bool)>> iso_traffic_active_callbacks_list_;

  // For generating unique client handles
  std::atomic<IsoClientHandle> next_iso_client_handle_ = kDefaultClientHandle;
  std::vector<IsoClientHandle> freed_iso_client_handles_;

  // Mutex to protect access to all shared states
  std::mutex iso_client_mutex_;

  // Client Callback Maps
  std::unordered_map<IsoClientHandle, IsoManagerCallbacks> iso_clients_;

  // Active Stream Tracking
  std::unordered_map<uint16_t /* conn_handle */, std::unique_ptr<iso_stream>>
          conn_hdl_to_iso_stream_map_;

  // CIG/BIG Ownership Tracking
  std::unordered_map<uint8_t /* cig_id */, std::unique_ptr<iso_group>> cig_id_to_group_map_;
  std::unordered_map<uint8_t /* big_handle */, std::unique_ptr<iso_group>> big_handle_to_group_map_;

  // Member variables should appear before the WeakPtrFactory, to ensure
  // that any WeakPtrs are invalidated before its members
  // variable's destructors are executed, rendering them invalid.
  base::WeakPtrFactory<iso_impl> weak_factory_{this};
};

}  // namespace iso_manager
}  // namespace hci
}  // namespace bluetooth
