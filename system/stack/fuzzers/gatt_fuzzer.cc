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

#include <base/functional/callback.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <fuzzer/FuzzedDataProvider.h>

#include <cstdint>
#include <string>

#include "bt_status.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/gatt_api.h"
#include "stack/include/stack_app.h"
#include "stack/include/stack_le_connection.h"
#include "stack/mock/mock_stack_acl.h"
#include "stack/mock/mock_stack_btm_dev.h"
#include "stack/mock/mock_stack_l2cap_ble.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_btif_config.h"

using bluetooth::Uuid;
using ::testing::NiceMock;
using ::testing::Unused;

using namespace bluetooth;

BtStatus do_in_main_thread(base::OnceCallback<void()>) {
  // this is not properly mocked, so we use abort to catch if this is used in
  // any test cases
  abort();
}
BtStatus do_in_main_thread_delayed(base::OnceCallback<void()>, std::chrono::microseconds) {
  // this is not properly mocked, so we use abort to catch if this is used in
  // any test cases
  abort();
}

namespace bluetooth {
namespace os {
bool GetSystemPropertyBool(const std::string& property, bool default_value) {
  return default_value;
}
uint32_t GetSystemPropertyUint32(const std::string& property, uint32_t default_value) {
  return default_value;
}
}  // namespace os
}  // namespace bluetooth

constexpr RawAddress kDummyAddr("11:22:33:44:55:66");
constexpr uint16_t kMaxPacketSize = 1024;
namespace {

// Set up default callback structure
tL2CAP_FIXED_CHNL_REG fixed_chnl_reg = {
        .pL2CA_FixedConn_Cb = [](uint16_t, const RawAddress&, bool, uint16_t, tBT_TRANSPORT) {},
        .pL2CA_FixedData_Cb = [](uint16_t, const RawAddress&, BT_HDR*) {},
};

tL2CAP_APPL_INFO appl_info;
BtmDevice btm_device;

class FakeBtStack {
  NiceMock<bluetooth::testing::stack::l2cap::Mock> mock_l2cap_interface;

public:
  FakeBtStack() {
    test::mock::stack_btm_dev::btm_find_dev.body = [](const RawAddress&) { return &btm_device; };
    test::mock::stack_btm_dev::btm_get_dev.body = [](const RawAddress&) { return &btm_device; };

    test::mock::stack_l2cap_ble::L2CA_GetBleConnRole.body = [](const RawAddress&) {
      return HCI_ROLE_CENTRAL;
    };

    ON_CALL(mock_l2cap_interface, L2CA_SetIdleTimeoutByBdAddr)
            .WillByDefault([](Unused, Unused, Unused) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_RemoveFixedChnl).WillByDefault([](uint16_t lcid, Unused) {
      bluetooth::log::assert_that(lcid == L2CAP_ATT_CID, "assert failed: lcid == L2CAP_ATT_CID");
      return true;
    });
    ON_CALL(mock_l2cap_interface, L2CA_ConnectFixedChnl).WillByDefault([](Unused, Unused) {
      return true;
    });
    ON_CALL(mock_l2cap_interface, L2CA_DataWrite).WillByDefault([](Unused, BT_HDR* hdr) {
      osi_free(hdr);
      return tL2CAP_DW_RESULT::SUCCESS;
    });
    ON_CALL(mock_l2cap_interface, L2CA_DisconnectReq).WillByDefault([](Unused) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_SendFixedChnlData)
            .WillByDefault([](Unused, Unused, BT_HDR* hdr) {
              osi_free(hdr);
              return tL2CAP_DW_RESULT::SUCCESS;
            });
    ON_CALL(mock_l2cap_interface, L2CA_RegisterFixedChannel)
            .WillByDefault([](Unused, tL2CAP_FIXED_CHNL_REG* p_freg) {
              fixed_chnl_reg = *p_freg;
              return true;
            });
    ON_CALL(mock_l2cap_interface, L2CA_RegisterWithSecurity)
            .WillByDefault([](uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, Unused, Unused,
                              Unused, Unused, Unused) {
              appl_info = p_cb_info;
              return psm;
            });
    ON_CALL(mock_l2cap_interface, L2CA_RegisterLECoc)
            .WillByDefault([](uint16_t psm, Unused, Unused, Unused) { return psm; });

    ON_CALL(mock_l2cap_interface, L2CA_SetIdleTimeoutByBdAddr)
            .WillByDefault([](Unused, Unused, Unused) { return true; });
    ON_CALL(mock_l2cap_interface, L2CA_SetLeGattTimeout).WillByDefault([](Unused, Unused) {
      return true;
    });
    bluetooth::testing::stack::l2cap::set_interface(&mock_l2cap_interface);
  }

  ~FakeBtStack() {
    test::mock::stack_btm_dev::btm_find_dev = {};
    test::mock::stack_btm_dev::btm_get_dev = {};

    test::mock::stack_l2cap_ble::L2CA_GetBleConnRole = {};

    bluetooth::testing::stack::l2cap::reset_interface();
  }
};

class Fakes {
public:
  test::fake::FakeOsi fake_osi;
  FakeBtStack fake_stack;
};

}  // namespace

static uint16_t s_ConnId;
static tGATT_IF s_AppIf;

static void GattInit() {
  s_ConnId = 0;
  s_AppIf = 0;

  gatt_init();

  /* Fill our internal UUID with a fixed pattern 0x82 */
  std::array<uint8_t, Uuid::kNumBytes128> tmp;
  tmp.fill(0x82);
  Uuid app_uuid = Uuid::From128BitBE(tmp);

  stack::tGATT_CBACK gap_cback = {
          .p_conn_cb = [](tGATT_IF, const RawAddress&, uint16_t conn_id, bool connected,
                          tGATT_DISCONN_REASON, tBT_TRANSPORT) { s_ConnId = conn_id; },
          .p_cmpl_cb = [](uint16_t, tGATTC_OPTYPE, tGATT_STATUS, tGATT_CL_COMPLETE*) {},
          .p_disc_res_cb = nullptr,
          .p_disc_cmpl_cb = nullptr,
          .p_req_cb = nullptr,
          .p_enc_cmpl_cb = nullptr,
          .p_congestion_cb = nullptr,
          .p_phy_update_cb = nullptr,
          .p_conn_update_cb = nullptr,
          .p_subrate_chg_cb = nullptr,
  };

  s_AppIf = stack::appRegister(app_uuid, "Gap", &gap_cback, false);
  stack::appStartIf(s_AppIf);
}

static void ServerInit() {
  GattInit();

  tGATT_APPL_INFO appl_info = {
          .p_nv_save_callback = [](bool, tGATTS_HNDL_RANGE*) {},
          .p_srv_chg_callback = [](tGATTS_SRV_CHG_CMD, tGATTS_SRV_CHG_REQ*,
                                   tGATTS_SRV_CHG_RSP*) { return true; },
  };
  (void)GATTS_NVRegister(&appl_info);

  Uuid svc_uuid = Uuid::From16Bit(UUID_SERVCLASS_GAP_SERVER);
  Uuid name_uuid = Uuid::From16Bit(GATT_UUID_GAP_DEVICE_NAME);
  Uuid icon_uuid = Uuid::From16Bit(GATT_UUID_GAP_ICON);
  Uuid addr_res_uuid = Uuid::From16Bit(GATT_UUID_GAP_CENTRAL_ADDR_RESOL);

  btgatt_db_element_t service[] = {{
                                           .uuid = svc_uuid,
                                           .type = BTGATT_DB_PRIMARY_SERVICE,
                                   },
                                   {.uuid = name_uuid,
                                    .type = BTGATT_DB_CHARACTERISTIC,
                                    .properties = GATT_CHAR_PROP_BIT_READ,
                                    .permissions = GATT_PERM_READ_IF_ENCRYPTED_OR_DISCOVERABLE},
                                   {.uuid = icon_uuid,
                                    .type = BTGATT_DB_CHARACTERISTIC,
                                    .properties = GATT_CHAR_PROP_BIT_READ,
                                    .permissions = GATT_PERM_READ},
                                   {.uuid = addr_res_uuid,
                                    .type = BTGATT_DB_CHARACTERISTIC,
                                    .properties = GATT_CHAR_PROP_BIT_READ,
                                    .permissions = GATT_PERM_READ}};

  /* Add a GAP service */
  (void)GATTS_AddService(s_AppIf, service, sizeof(service) / sizeof(btgatt_db_element_t));
}

static void ServerCleanup() {
  stack::appDeregister(s_AppIf);
  gatt_free();
}

static void FuzzAsServer(FuzzedDataProvider& fdp) {
  ServerInit();
  fixed_chnl_reg.pL2CA_FixedConn_Cb(L2CAP_ATT_CID, kDummyAddr, true, 0, BT_TRANSPORT_LE);

  while (fdp.remaining_bytes() > 0) {
    auto size = fdp.ConsumeIntegralInRange<uint16_t>(0, kMaxPacketSize);
    auto bytes = fdp.ConsumeBytes<uint8_t>(size);
    BT_HDR* hdr = (BT_HDR*)osi_calloc(sizeof(BT_HDR) + bytes.size());
    hdr->len = bytes.size();
    std::copy(bytes.cbegin(), bytes.cend(), hdr->data);
    fixed_chnl_reg.pL2CA_FixedData_Cb(L2CAP_ATT_CID, kDummyAddr, hdr);
  }

  ServerCleanup();
}

static void ClientInit() {
  GattInit();
  (void)stack::leConnectionConnect(s_AppIf, kDummyAddr, BTM_BLE_DIRECT_CONNECTION);
}

static void ClientCleanup() {
  (void)stack::leConnectionCancelConnect(s_AppIf, kDummyAddr, true);
  stack::appDeregister(s_AppIf);
  gatt_free();
}

static void FuzzAsClient(FuzzedDataProvider& fdp) {
  ClientInit();
  fixed_chnl_reg.pL2CA_FixedConn_Cb(L2CAP_ATT_CID, kDummyAddr, true, 0, BT_TRANSPORT_LE);

  while (fdp.remaining_bytes() > 0) {
    auto op = fdp.ConsumeIntegral<uint8_t>();
    switch (op) {
      case GATTC_OPTYPE_CONFIG: {
        auto mtu = fdp.ConsumeIntegral<uint16_t>();
        (void)GATTC_ConfigureMTU(s_ConnId, mtu);
        break;
      }
      case GATTC_OPTYPE_DISCOVERY: {
        auto type = (tGATT_DISC_TYPE)fdp.ConsumeIntegralInRange<uint8_t>(0, GATT_DISC_MAX);
        uint16_t start = fdp.ConsumeIntegral<uint16_t>();
        uint16_t end = fdp.ConsumeIntegral<uint16_t>();
        (void)GATTC_Discover(s_ConnId, type, start, end);
        break;
      }
      case GATTC_OPTYPE_READ: {
        auto type = (tGATT_READ_TYPE)fdp.ConsumeIntegralInRange<uint8_t>(0, GATT_READ_MAX);
        tGATT_READ_PARAM param = {};
        fdp.ConsumeData(&param, sizeof(param));
        (void)GATTC_Read(s_ConnId, type, &param);
        break;
      }
      case GATTC_OPTYPE_WRITE: {
        auto type =
                (tGATT_WRITE_TYPE)fdp.ConsumeIntegralInRange<uint8_t>(0, GATT_WRITE_PREPARE + 1);
        tGATT_VALUE value = {};
        value.len = fdp.ConsumeIntegralInRange<uint16_t>(0, sizeof(value.value));
        value.len = fdp.ConsumeData(&value.value, value.len);
        (void)GATTC_Write(s_ConnId, type, &value);
        break;
      }
      case GATTC_OPTYPE_EXE_WRITE: {
        auto type = fdp.ConsumeBool();
        (void)GATTC_ExecuteWrite(s_ConnId, type);
        break;
      }
      default:
        break;
    }
    auto size = fdp.ConsumeIntegralInRange<uint16_t>(0, kMaxPacketSize);
    auto bytes = fdp.ConsumeBytes<uint8_t>(size);
    BT_HDR* hdr = (BT_HDR*)osi_calloc(sizeof(BT_HDR) + bytes.size());
    hdr->len = bytes.size();
    std::copy(bytes.cbegin(), bytes.cend(), hdr->data);
    fixed_chnl_reg.pL2CA_FixedData_Cb(L2CAP_ATT_CID, kDummyAddr, hdr);
  }

  fixed_chnl_reg.pL2CA_FixedConn_Cb(L2CAP_ATT_CID, kDummyAddr, false, 0, BT_TRANSPORT_LE);
  ClientCleanup();
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  auto fakes = std::make_unique<Fakes>();

  FuzzedDataProvider fdp(data, size);

  if (fdp.ConsumeBool()) {
    FuzzAsServer(fdp);
  } else {
    FuzzAsClient(fdp);
  }

  return 0;
}
