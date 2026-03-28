/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <rust/cxx.h>

#include <cstdint>
#include <memory>

#include "stack/include/btm_iso_api.h"

namespace bluetooth::hci::iso_manager {

namespace ffi {
struct IsoCigCallbacks;
struct IsoBigCallbacks;
}  // namespace ffi

class CigCallbacksShim : public ::bluetooth::hci::iso_manager::CigCallbacks {
public:
  explicit CigCallbacksShim(rust::Box<::bluetooth::hci::iso_manager::ffi::IsoCigCallbacks> cb);
  ~CigCallbacksShim() override = default;

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override;
  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override;
  void OnIsoLinkQualityRead(uint16_t conn_handle, uint8_t cig_id, uint32_t tx_unacked_packets,
                            uint32_t tx_flushed_packets, uint32_t tx_last_subevent_packets,
                            uint32_t retransmitted_packets, uint32_t crc_error_packets,
                            uint32_t rx_unreceived_packets, uint32_t duplicate_packets) override;

  void OnCisEvent(uint8_t event, void* data) override;
  void OnCigEvent(uint8_t event, void* data) override;

private:
  rust::Box<::bluetooth::hci::iso_manager::ffi::IsoCigCallbacks> callbacks_;
};

class BigCallbacksShim : public ::bluetooth::hci::iso_manager::BigCallbacks {
public:
  explicit BigCallbacksShim(rust::Box<::bluetooth::hci::iso_manager::ffi::IsoBigCallbacks> cb);
  ~BigCallbacksShim() override = default;

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t big_handle) override;
  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t big_handle) override;

  void OnBisEvent(uint8_t event, void* data) override;
  void OnBigSourceEvent(BigSourceEvent event, void* data) override;
  void OnBigSinkEvent(BigSinkEvent event, void* data) override;

private:
  rust::Box<::bluetooth::hci::iso_manager::ffi::IsoBigCallbacks> callbacks_;
};

class IsoManagerShim {
public:
  IsoManagerShim();
  ~IsoManagerShim();

  void RegisterCallbacksNative(
          rust::Box<::bluetooth::hci::iso_manager::ffi::IsoCigCallbacks> cig_callbacks,
          rust::Box<::bluetooth::hci::iso_manager::ffi::IsoBigCallbacks> big_callbacks);

  void CreateCig(uint8_t cig_id, uint32_t sdu_interval_c_to_p, uint32_t sdu_interval_p_to_c,
                 uint8_t worse_cast_sca, bool packing, bool framing,
                 uint16_t max_transport_latency_c_to_p, uint16_t max_transport_latency_p_to_c,
                 rust::Vec<uint8_t> cis_ids, rust::Vec<uint16_t> max_sdu_c_to_p,
                 rust::Vec<uint16_t> max_sdu_p_to_c, rust::Vec<uint8_t> phy_c_to_p,
                 rust::Vec<uint8_t> phy_p_to_c, rust::Vec<uint8_t> rtn_c_to_p,
                 rust::Vec<uint8_t> rtn_p_to_c);

  void ReconfigureCig(uint8_t cig_id, uint32_t sdu_interval_c_to_p, uint32_t sdu_interval_p_to_c,
                      uint8_t worse_cast_sca, bool packing, bool framing,
                      uint16_t max_transport_latency_c_to_p, uint16_t max_transport_latency_p_to_c,
                      rust::Vec<uint8_t> cis_ids, rust::Vec<uint16_t> max_sdu_c_to_p,
                      rust::Vec<uint16_t> max_sdu_p_to_c, rust::Vec<uint8_t> phy_c_to_p,
                      rust::Vec<uint8_t> phy_p_to_c, rust::Vec<uint8_t> rtn_c_to_p,
                      rust::Vec<uint8_t> rtn_p_to_c);

  void RemoveCig(uint8_t cig_id, bool force);

  void CreateCis(rust::Vec<uint16_t> cis_conn_handles, rust::Vec<uint16_t> acl_conn_handles);

  void DisconnectCis(uint16_t conn_handle, uint8_t reason);

  void CreateBig(uint8_t big_handle, uint8_t advertising_handle, uint8_t num_bis, uint32_t sdu_itv,
                 uint16_t max_sdu_size, uint16_t max_transport_latency, uint8_t rtn, uint8_t phy,
                 bool packing, bool framing, bool encryption,
                 std::array<uint8_t, 16> broadcast_code);

  void TerminateBig(uint8_t big_handle, uint8_t reason);

  void BigCreateSync(uint8_t big_handle, uint16_t sync_handle, bool encryption,
                     std::array<uint8_t, 16> broadcast_code, uint8_t mse, uint16_t big_sync_timeout,
                     rust::Vec<uint8_t> bis_indices);

  void BigTerminateSync(uint8_t big_handle);

  void SetupIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir, uint8_t data_path_id,
                        uint8_t coding_format, uint16_t company_id,
                        uint16_t vendor_specific_codec_id, uint32_t controller_delay,
                        rust::Vec<uint8_t> codec_configuration);

  void RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir);

  void SendIsoData(uint16_t conn_handle, rust::Slice<const uint8_t> data);

  void ReadIsoLinkQuality(uint16_t conn_handle);

private:
  ::bluetooth::hci::IsoManager* iso_manager_;
  std::unique_ptr<CigCallbacksShim> cig_callbacks_shim_;
  std::unique_ptr<BigCallbacksShim> big_callbacks_shim_;
  ::bluetooth::hci::iso_manager::IsoClientHandle client_handle_;
};

std::unique_ptr<IsoManagerShim> GetIsoManagerShim();

}  // namespace bluetooth::hci::iso_manager
