/*
 * Copyright 2023 The Android Open Source Project
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
/*
 * Generated mock file from original source file
 *   Functions generated:10
 *
 *  mockcify.pl ver 0.6.2
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_btif_sock_rfc.h"

#include <cstdint>

#include "bta/include/bta_jv_co.h"
#include "btif/include/btif_sock_rfc.h"
#include "test/common/mock_functions.h"

// Original usings
using bluetooth::Uuid;

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace btif_sock_rfc {

// Function state capture and return values, if needed
struct bta_co_rfc_data_incoming bta_co_rfc_data_incoming;
struct bta_co_rfc_data_outgoing bta_co_rfc_data_outgoing;
struct bta_co_rfc_data_outgoing_size bta_co_rfc_data_outgoing_size;
struct btsock_rfc_cleanup btsock_rfc_cleanup;
struct btsock_rfc_connect btsock_rfc_connect;
struct btsock_rfc_control_req btsock_rfc_control_req;
struct btsock_rfc_disconnect btsock_rfc_disconnect;
struct btsock_rfc_init btsock_rfc_init;
struct btsock_rfc_listen btsock_rfc_listen;
struct btsock_rfc_signaled btsock_rfc_signaled;
struct on_btsocket_rfc_close on_btsocket_rfc_close;
struct on_btsocket_rfc_opened_complete on_btsocket_rfc_opened_complete;

}  // namespace btif_sock_rfc
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace btif_sock_rfc {

int bta_co_rfc_data_incoming::return_value = 0;
int bta_co_rfc_data_outgoing::return_value = 0;
int bta_co_rfc_data_outgoing_size::return_value = 0;
bt_status_t btsock_rfc_connect::return_value = BT_STATUS_SUCCESS;
bt_status_t btsock_rfc_control_req::return_value = BT_STATUS_SUCCESS;
bt_status_t btsock_rfc_disconnect::return_value = BT_STATUS_SUCCESS;
bt_status_t btsock_rfc_init::return_value = BT_STATUS_SUCCESS;
bt_status_t btsock_rfc_listen::return_value = BT_STATUS_SUCCESS;

}  // namespace btif_sock_rfc
}  // namespace mock
}  // namespace test

// Mocked functions, if any
int bta_co_rfc_data_incoming(uint32_t id, BT_HDR* p_buf) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::bta_co_rfc_data_incoming(id, p_buf);
}
int bta_co_rfc_data_outgoing(uint32_t id, uint8_t* buf, uint16_t size) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::bta_co_rfc_data_outgoing(id, buf, size);
}
int bta_co_rfc_data_outgoing_size(uint32_t id, int* size) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::bta_co_rfc_data_outgoing_size(id, size);
}
void btsock_rfc_cleanup(void) {
  inc_func_call_count(__func__);
  test::mock::btif_sock_rfc::btsock_rfc_cleanup();
}
bt_status_t btsock_rfc_connect(const RawAddress* bd_addr, const bluetooth::Uuid* uuid, int channel,
                               int* sock_fd, int flags, int app_uid, btsock_data_path_t data_path,
                               const char* socket_name, uint64_t hub_id, uint64_t endpoint_id,
                               int max_rx_packet_size) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::btsock_rfc_connect(bd_addr, uuid, channel, sock_fd, flags,
                                                       app_uid, data_path, socket_name, hub_id,
                                                       endpoint_id, max_rx_packet_size);
}
bt_status_t btsock_rfc_control_req(uint8_t dlci, const RawAddress& bd_addr, uint8_t modem_signal,
                                   uint8_t break_signal, uint8_t discard_buffers,
                                   uint8_t break_signal_seq, bool fc) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::btsock_rfc_control_req(
          dlci, bd_addr, modem_signal, break_signal, discard_buffers, break_signal_seq, fc);
}
bt_status_t btsock_rfc_disconnect(const RawAddress* bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::btsock_rfc_disconnect(bd_addr);
}
bt_status_t btsock_rfc_init(int poll_thread_handle, uid_set_t* set) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::btsock_rfc_init(poll_thread_handle, set);
}
bt_status_t btsock_rfc_listen(const char* service_name, const Uuid* service_uuid, int channel,
                              int* sock_fd, int flags, int app_uid, btsock_data_path_t data_path,
                              const char* socket_name, uint64_t hub_id, uint64_t endpoint_id,
                              int max_rx_packet_size) {
  inc_func_call_count(__func__);
  return test::mock::btif_sock_rfc::btsock_rfc_listen(service_name, service_uuid, channel, sock_fd,
                                                      flags, app_uid, data_path, socket_name,
                                                      hub_id, endpoint_id, max_rx_packet_size);
}
void btsock_rfc_signaled(int fd, int flags, uint32_t id) {
  inc_func_call_count(__func__);
  test::mock::btif_sock_rfc::btsock_rfc_signaled(fd, flags, id);
}
void on_btsocket_rfc_close(uint64_t socket_id) {
  inc_func_call_count(__func__);
  test::mock::btif_sock_rfc::on_btsocket_rfc_close(socket_id);
}
void on_btsocket_rfc_opened_complete(uint64_t socket_id, bool success) {
  inc_func_call_count(__func__);
  test::mock::btif_sock_rfc::on_btsocket_rfc_opened_complete(socket_id, success);
}
// Mocked functions complete
// END mockcify generation
