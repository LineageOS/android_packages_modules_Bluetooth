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
#ifndef GD_RUST_TOPSHIM_SOCKET_SOCKET_SHIM_H
#define GD_RUST_TOPSHIM_SOCKET_SOCKET_SHIM_H

#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/uuid.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_sock.h>

#include <memory>

#include "rust/cxx.h"
#include "topshim/btif/btif_shim.h"
#include "topshim/common/bt_status_helper.h"

namespace bluetooth {
namespace topshim {
namespace rust {

// C++ Socket Interface that matches the Rust Socket FFI defined in /topshim/src/profiles/socket.rs
// Provides a translation for the btsock_interface_t defined in /system/btif/src/btif_sock.cc
class SocketIntf {
public:
  SocketIntf(const btsock_interface_t* sock_intf) : sock_intf_(sock_intf) {}
  ~SocketIntf() = default;

  tBT_STATUS_LEGACY listen(btsock_type_t type, ::rust::Vec<uint8_t> service_name, Uuid uuid,
                           int channel, int& sock_fd, int flags, int app_uid,
                           btsock_data_path_t data_path, ::rust::Vec<uint8_t> socket_name,
                           uint64_t hub_id, uint64_t endpoint_id, int max_rx_packet_size) const;
  tBT_STATUS_LEGACY connect(RawAddress bd_addr, btsock_type_t type, Uuid uuid, int channel,
                            int& sock_fd, int flags, int app_uid, btsock_data_path_t data_path,
                            ::rust::Vec<uint8_t> socket_name, uint64_t hub_id, uint64_t endpoint_id,
                            int max_rx_packet_size) const;
  void request_max_tx_data_length(RawAddress bd_addr) const;
  tBT_STATUS_LEGACY control_req(uint8_t dlci, RawAddress bd_addr, uint8_t modem_signal,
                                uint8_t break_signal, uint8_t discard_buffers,
                                uint8_t break_signal_seq, bool fc) const;
  tBT_STATUS_LEGACY disconnect_all(RawAddress bd_addr) const;

private:
  const btsock_interface_t* sock_intf_;
};

std::unique_ptr<SocketIntf> GetSocketProfile(const BtIntf& intf);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_SOCKET_SOCKET_SHIM_H
