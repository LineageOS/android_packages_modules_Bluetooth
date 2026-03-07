/*
 * Copyright 2022 The Android Open Source Project
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

#include <fuzzer/FuzzedDataProvider.h>

#include <cstdint>

#include "common/message_loop_thread.h"
#include "hci/controller_mock.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_status.h"
#include "stack/include/smp_api.h"
#include "stack/mock/mock_stack_acl.h"
#include "stack/mock/mock_stack_btm_dev.h"
#include "stack/mock/mock_stack_l2cap_ble.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/smp/smp_int.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_btif_config.h"
#include "test/mock/mock_main_shim_entry.h"

using ::testing::NiceMock;
using ::testing::Unused;

bluetooth::common::MessageLoopThread* main_thread_ptr = nullptr;

bluetooth::common::MessageLoopThread* get_main_thread() { return main_thread_ptr; }

namespace {

#define SDP_DB_SIZE 0x10000

constexpr RawAddress kDummyAddr("11:22:33:44:55:66");
constexpr RawAddress kDummyRemoteAddr("77:88:99:AA:BB:CC");

// Set up default callback structure
tL2CAP_FIXED_CHNL_REG fixed_chnl_reg = {
        .pL2CA_FixedConn_Cb = [](uint16_t, const RawAddress&, bool, uint16_t, tBT_TRANSPORT) {},
        .pL2CA_FixedData_Cb = [](uint16_t, const RawAddress&, BT_HDR*) {},
};

tL2CAP_FIXED_CHNL_REG fixed_chnl_br_reg = {
        .pL2CA_FixedConn_Cb = [](uint16_t, const RawAddress&, bool, uint16_t, tBT_TRANSPORT) {},
        .pL2CA_FixedData_Cb = [](uint16_t, const RawAddress&, BT_HDR*) {},
};

BtmDevice btm_device;
bool is_peripheral;

class FakeBtStack {
  NiceMock<bluetooth::testing::stack::l2cap::Mock> mock_l2cap_interface;

public:
  FakeBtStack() {
    test::mock::stack_acl::BTM_ReadConnectionAddr.body =
            [](const RawAddress& remote_bda, RawAddress& local_conn_addr,
               tBLE_ADDR_TYPE* p_addr_type, bool ota_address) {
              local_conn_addr = kDummyAddr;
              *p_addr_type = BLE_ADDR_PUBLIC;
            };
    test::mock::stack_acl::BTM_ReadRemoteConnectionAddr.body =
            [](const RawAddress& pseudo_addr, RawAddress& conn_addr, tBLE_ADDR_TYPE* p_addr_type,
               bool ota_address) {
              conn_addr = kDummyRemoteAddr;
              *p_addr_type = BLE_ADDR_PUBLIC;
              return true;
            };
    test::mock::stack_btm_dev::btm_find_dev.body = [](const RawAddress&) { return &btm_device; };
    test::mock::stack_btm_dev::btm_get_dev.body = [](const RawAddress&) { return &btm_device; };

    test::mock::stack_l2cap_ble::L2CA_GetBleConnRole.body = [](const RawAddress&) {
      return is_peripheral ? HCI_ROLE_PERIPHERAL : HCI_ROLE_CENTRAL;
    };

    ON_CALL(mock_l2cap_interface, L2CA_SetIdleTimeoutByBdAddr)
            .WillByDefault([](const RawAddress&, uint16_t, uint8_t) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_RemoveFixedChnl)
            .WillByDefault([](uint16_t, const RawAddress&) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_ConnectFixedChnl)
            .WillByDefault([](uint16_t, const RawAddress&) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_SendFixedChnlData)
            .WillByDefault([](uint16_t cid, const RawAddress& addr, BT_HDR* hdr) {
              osi_free(hdr);
              return tL2CAP_DW_RESULT::SUCCESS;
            });
    ON_CALL(mock_l2cap_interface, L2CA_RegisterFixedChannel)
            .WillByDefault([](uint16_t fixed_cid, tL2CAP_FIXED_CHNL_REG* p_freg) {
              if (fixed_cid == L2CAP_SMP_CID) {
                fixed_chnl_reg = *p_freg;
              } else if (fixed_cid == L2CAP_SMP_BR_CID) {
                fixed_chnl_br_reg = *p_freg;
              } else {
                abort();
              }
              return true;
            });
    bluetooth::testing::stack::l2cap::set_interface(&mock_l2cap_interface);

    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockController>();
    ON_CALL(*bluetooth::hci::testing::mock_controller_, LeRand)
            .WillByDefault([](bluetooth::hci::LeRandCallback cb) { cb(0x1234); });

    main_thread_ptr = new bluetooth::common::MessageLoopThread("smp_fuzz_main_thread");
    main_thread_ptr->StartUp();
  }

  ~FakeBtStack() {
    // Shut down before deallocations to clear out any work to avoid use after frees.
    main_thread_ptr->ShutDown();

    delete main_thread_ptr;
    main_thread_ptr = nullptr;

    test::mock::stack_acl::BTM_ReadConnectionAddr = {};
    test::mock::stack_acl::BTM_ReadRemoteConnectionAddr = {};

    test::mock::stack_btm_dev::btm_find_dev = {};
    test::mock::stack_btm_dev::btm_get_dev = {};

    test::mock::stack_l2cap_ble::L2CA_GetBleConnRole = {};

    bluetooth::testing::stack::l2cap::reset_interface();

    bluetooth::hci::testing::mock_controller_.reset();
  }
};

class Fakes {
public:
  test::fake::FakeOsi fake_osi;
  FakeBtStack fake_stack;
};

}  // namespace

uint8_t oob_data[] = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
                      0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00};
tSMP_IO_REQ io_req = {};

tBTM_STATUS smp_callback(tSMP_EVT event, const RawAddress& bd_addr, tSMP_EVT_DATA* p_data) {
  switch (event) {
    case SMP_IO_CAP_REQ_EVT:
    case SMP_BR_KEYS_REQ_EVT: {
      tSMP_IO_REQ* p_req = (tSMP_IO_REQ*)p_data;
      memcpy(p_req, &io_req, sizeof(io_req));
    } break;

    case SMP_PASSKEY_REQ_EVT: {
      SMP_PasskeyReply(kDummyAddr, SMP_SUCCESS, 1234);
    } break;

    case SMP_NC_REQ_EVT: {
      SMP_ConfirmReply(kDummyAddr, SMP_SUCCESS);
    } break;

    case SMP_OOB_REQ_EVT: {
      SMP_OobDataReply(kDummyAddr, SMP_SUCCESS, sizeof(oob_data), oob_data);
    } break;

    case SMP_SC_OOB_REQ_EVT: {
      tSMP_SC_OOB_DATA oob_data = {};
      SMP_SecureConnectionOobDataReply((uint8_t*)&oob_data);
    } break;
    case SMP_CONSENT_REQ_EVT: {
      SMP_SecurityGrant(kDummyAddr, SMP_SUCCESS);
    } break;
    default:
      break;
  }
  return tBTM_STATUS::BTM_SUCCESS;
}

void Fuzz(const uint8_t* data, size_t size) {
  FuzzedDataProvider fdp(data, size);
  uint16_t cid;
  tBT_TRANSPORT transport;
  tL2CAP_FIXED_CHNL_REG* chnl_reg;

  SMP_Init(BTM_SEC_MODE_SP);
  SMP_Register(smp_callback);
  SMP_ClearLocScOobData();

  auto is_br = fdp.ConsumeBool();
  auto is_initiator = fdp.ConsumeBool();
  is_peripheral = fdp.ConsumeBool();
  fdp.ConsumeData(&io_req, sizeof(io_req));

  if (is_br) {
    cid = L2CAP_SMP_BR_CID;
    chnl_reg = &fixed_chnl_br_reg;
    transport = BT_TRANSPORT_BR_EDR;
    if (is_initiator) {
      SMP_BR_PairWith(kDummyAddr);
    }
  } else {
    cid = L2CAP_SMP_CID;
    chnl_reg = &fixed_chnl_reg;
    transport = BT_TRANSPORT_LE;
    if (is_initiator) {
      SMP_Pair(kDummyAddr);
    }
  }

  // Simulating connection establaishing event
  chnl_reg->pL2CA_FixedConn_Cb(cid, kDummyAddr, true, 0, transport);

  constexpr uint16_t kMaxPacketSize = 1024;
  while (fdp.remaining_bytes() > 0) {
    auto size = fdp.ConsumeIntegralInRange<uint16_t>(0, kMaxPacketSize);
    auto bytes = fdp.ConsumeBytes<uint8_t>(size);
    BT_HDR* hdr = (BT_HDR*)osi_calloc(sizeof(BT_HDR) + bytes.size());
    hdr->len = bytes.size();
    std::copy(bytes.cbegin(), bytes.cend(), hdr->data);

    // Simulating incoming data packet event
    chnl_reg->pL2CA_FixedData_Cb(cid, kDummyAddr, hdr);
  }

  // Simulating disconnecting event
  chnl_reg->pL2CA_FixedConn_Cb(cid, kDummyAddr, false, 0, transport);

  // Final cleanups to avoid memory leak
  alarm_free(smp_cb.smp_rsp_timer_ent);
  alarm_free(smp_cb.delayed_auth_timer_ent);
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* Data, size_t Size) {
  auto fakes = std::make_unique<Fakes>();
  Fuzz(Data, Size);
  return 0;
}
