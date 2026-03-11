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

#include <base/location.h>
#include <bluetooth/log.h>
#include <fuzzer/FuzzedDataProvider.h>
#include <gmock/gmock.h>

#include <cstdint>

#include "hal/snoop_logger.h"
#include "osi/include/allocator.h"
#include "stack/include/port_api.h"
#include "stack/include/rfcdefs.h"
#include "stack/mock/mock_stack_acl.h"
#include "stack/mock/mock_stack_btm_dev.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack_rfcomm_test_utils.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_btif_config.h"
#include "test/mock/mock_main_shim_entry.h"

using ::testing::NiceMock;
using ::testing::Unused;

namespace bluetooth {
namespace hal {
class SnoopLogger;

void SnoopLogger::AcceptlistRfcommDlci(uint16_t, uint16_t, uint8_t) {}
void SnoopLogger::SetRfcommPortOpen(uint16_t, uint16_t, uint8_t, uint16_t, bool) {}
void SnoopLogger::SetRfcommPortClose(uint16_t, uint16_t, uint8_t, uint16_t) {}
}  // namespace hal
}  // namespace bluetooth

namespace {

tL2CAP_APPL_INFO appl_info;
bluetooth::rfcomm::MockRfcommCallback* rfcomm_callback = nullptr;

constexpr uint8_t kDummyId = 0x77;
constexpr RawAddress kDummyRemoteAddr("77:88:99:AA:BB:CC");
constexpr uint16_t kDummyCID = 0x1234;
constexpr RawAddress kDummyAddr("11:22:33:44:55:66");

void port_mgmt_cback(const tPORT_RESULT code, uint8_t port_handle) {
  rfcomm_callback->PortManagementCallback(code, port_handle, 0);
}
void port_event_cback(uint32_t code, uint8_t port_handle) {
  rfcomm_callback->PortEventCallback(code, port_handle, 0);
}

class FakeBtStack {
  NiceMock<bluetooth::testing::stack::l2cap::Mock> mock_l2cap_interface;

public:
  NiceMock<bluetooth::rfcomm::MockRfcommCallback> mock_rfcomm_callback;

  FakeBtStack() {
    ON_CALL(mock_l2cap_interface, L2CA_DataWrite).WillByDefault([](Unused, BT_HDR* hdr) {
      osi_free(hdr);
      return tL2CAP_DW_RESULT::SUCCESS;
    });
    ON_CALL(mock_l2cap_interface, L2CA_ConnectReq).WillByDefault([](Unused, Unused) {
      return kDummyCID;
    });
    ON_CALL(mock_l2cap_interface, L2CA_DisconnectReq).WillByDefault([](Unused) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_Register)
            .WillByDefault([](uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, Unused, Unused,
                              Unused, Unused, Unused) {
              appl_info = p_cb_info;
              return psm;
            });
    bluetooth::testing::stack::l2cap::set_interface(&mock_l2cap_interface);

    rfcomm_callback = &mock_rfcomm_callback;
  }

  ~FakeBtStack() {
    rfcomm_callback = nullptr;
    bluetooth::testing::stack::l2cap::reset_interface();
  }
};

class Fakes {
public:
  test::fake::FakeOsi fake_osi;
  FakeBtStack fake_stack;
};

}  // namespace

static int Cleanup(uint8_t* server_handle) { return RFCOMM_RemoveServer(*server_handle); }

static int ServerInit(FuzzedDataProvider* fdp, uint8_t* server_handle) {
  RFCOMM_Init();

  auto mtu = fdp->ConsumeIntegral<uint16_t>();
  auto scn = fdp->ConsumeIntegral<uint8_t>();
  auto uuid = fdp->ConsumeIntegral<uint16_t>();

  int status = RFCOMM_CreateConnectionWithSecurity(uuid, scn, true, mtu, kDummyAddr, server_handle,
                                                   port_mgmt_cback, 0, RfcommCfgInfo{});
  if (status != PORT_SUCCESS) {
    return status;
  }
  status = PORT_SetEventMaskAndCallback(*server_handle, PORT_EV_RXCHAR, port_event_cback);
  return status;
}

static void FuzzAsServer(FuzzedDataProvider* fdp) {
  auto server_handle = fdp->ConsumeIntegralInRange<uint8_t>(1, MAX_RFC_PORTS);
  if (ServerInit(fdp, &server_handle) != PORT_SUCCESS) {
    return;
  }

  appl_info.pL2CA_ConnectInd_Cb(kDummyRemoteAddr, kDummyCID, 0, kDummyId);

  // Simulating configuration confirmation event
  tL2CAP_CFG_INFO cfg = {};
  appl_info.pL2CA_ConfigCfm_Cb(kDummyCID, 0, &cfg);

  // Feeding input packets
  constexpr uint16_t kMaxPacketSize = 1024;
  while (fdp->remaining_bytes() > 0) {
    auto size = fdp->ConsumeIntegralInRange<uint16_t>(0, kMaxPacketSize);
    auto bytes = fdp->ConsumeBytes<uint8_t>(size);
    BT_HDR* hdr = reinterpret_cast<BT_HDR*>(osi_calloc(sizeof(BT_HDR) + bytes.size()));
    hdr->len = bytes.size();
    std::copy(bytes.cbegin(), bytes.cend(), hdr->data);
    appl_info.pL2CA_DataInd_Cb(kDummyCID, hdr);
  }

  // Simulating disconnecting event
  appl_info.pL2CA_DisconnectInd_Cb(kDummyCID, false);

  Cleanup(&server_handle);
}

static int ClientInit(FuzzedDataProvider* fdp, uint8_t* client_handle) {
  RFCOMM_Init();

  auto mtu = fdp->ConsumeIntegral<uint16_t>();
  auto scn = fdp->ConsumeIntegral<uint8_t>();
  auto uuid = fdp->ConsumeIntegral<uint16_t>();

  int status = RFCOMM_CreateConnectionWithSecurity(uuid, scn, false, mtu, kDummyAddr, client_handle,
                                                   port_mgmt_cback, 0, RfcommCfgInfo{});
  if (status != PORT_SUCCESS) {
    return status;
  }
  status = PORT_SetEventMaskAndCallback(*client_handle, PORT_EV_RXCHAR, port_event_cback);
  return status;
}

static void FuzzAsClient(FuzzedDataProvider* fdp) {
  auto client_handle = fdp->ConsumeIntegralInRange<uint8_t>(1, MAX_RFC_PORTS);

  if (ClientInit(fdp, &client_handle) != PORT_SUCCESS) {
    return;
  }

  // Simulating outbound connection confirm event
  appl_info.pL2CA_ConnectCfm_Cb(kDummyCID, tL2CAP_CONN::L2CAP_CONN_OK);

  // Simulating configuration confirmation event
  tL2CAP_CFG_INFO cfg = {};
  appl_info.pL2CA_ConfigCfm_Cb(kDummyCID, 0, &cfg);

  // Feeding input packets
  constexpr uint16_t kMaxPacketSize = 1024;
  while (fdp->remaining_bytes() > 0) {
    auto size = fdp->ConsumeIntegralInRange<uint16_t>(0, kMaxPacketSize);
    auto bytes = fdp->ConsumeBytes<uint8_t>(size);
    BT_HDR* hdr = reinterpret_cast<BT_HDR*>(osi_calloc(sizeof(BT_HDR) + bytes.size()));
    hdr->len = bytes.size();
    std::copy(bytes.cbegin(), bytes.cend(), hdr->data);
    appl_info.pL2CA_DataInd_Cb(kDummyCID, hdr);
  }

  Cleanup(&client_handle);
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  auto fakes = std::make_unique<Fakes>();

  FuzzedDataProvider fdp(data, size);

  if (fdp.ConsumeBool()) {
    FuzzAsServer(&fdp);
  } else {
    FuzzAsClient(&fdp);
  }

  return 0;
}
