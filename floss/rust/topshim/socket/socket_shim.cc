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
#include "topshim/socket/socket_shim.h"

#include "src/profiles/socket.rs.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {

namespace internal {

// Singleton instance of SocketIntf
static SocketIntf* g_sock_if;

}  // namespace internal

tBT_STATUS_LEGACY SocketIntf::listen(btsock_type_t type, ::rust::Vec<uint8_t> service_name,
                                     Uuid uuid, int channel, int& sock_fd, int flags, int app_uid,
                                     btsock_data_path_t data_path, ::rust::Vec<uint8_t> socket_name,
                                     uint64_t hub_id, uint64_t endpoint_id,
                                     int max_rx_packet_size) const {
  return toLegacyStatus(sock_intf_->listen(type, reinterpret_cast<char*>(service_name.data()),
                                           &uuid, channel, &sock_fd, flags, app_uid, data_path,
                                           reinterpret_cast<char*>(socket_name.data()), hub_id,
                                           endpoint_id, max_rx_packet_size));
}

tBT_STATUS_LEGACY SocketIntf::connect(RawAddress bd_addr, btsock_type_t type, Uuid uuid,
                                      int channel, int& sock_fd, int flags, int app_uid,
                                      btsock_data_path_t data_path,
                                      ::rust::Vec<uint8_t> socket_name, uint64_t hub_id,
                                      uint64_t endpoint_id, int max_rx_packet_size) const {
  return toLegacyStatus(sock_intf_->connect(bd_addr, type, &uuid, channel, &sock_fd, flags, app_uid,
                                            data_path, reinterpret_cast<char*>(socket_name.data()),
                                            hub_id, endpoint_id, max_rx_packet_size));
}

void SocketIntf::request_max_tx_data_length(RawAddress bd_addr) const {
  sock_intf_->request_max_tx_data_length(bd_addr);
}

tBT_STATUS_LEGACY SocketIntf::control_req(uint8_t dlci, RawAddress bd_addr, uint8_t modem_signal,
                                          uint8_t break_signal, uint8_t discard_buffers,
                                          uint8_t break_signal_seq, bool fc) const {
  return toLegacyStatus(sock_intf_->control_req(dlci, bd_addr, modem_signal, break_signal,
                                                discard_buffers, break_signal_seq, fc));
}

tBT_STATUS_LEGACY SocketIntf::disconnect_all(RawAddress bd_addr) const {
  return toLegacyStatus(sock_intf_->disconnect_all(bd_addr));
}

std::unique_ptr<SocketIntf> GetSocketProfile(const BtIntf& intf) {
  if (internal::g_sock_if) {
    std::abort();
  }

  auto sock_if = std::make_unique<SocketIntf>(reinterpret_cast<const btsock_interface_t*>(
          intf.get_profile_interface(BT_PROFILE_SOCKETS_ID)));
  internal::g_sock_if = sock_if.get();
  return sock_if;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
