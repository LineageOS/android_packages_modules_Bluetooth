/*
 * Copyright 2025 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <array>

#include "bta/dm/bta_dm_int.h"
#include "bta/hd/bta_hd_int.h"
#include "bta/include/bta_hd_api.h"
#include "osi/include/allocator.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_osi_allocator.h"

namespace {
std::array<uint8_t, 32> data32 = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b,
        0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
        0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
};
const RawAddress kRawAddress("11:22:33:44:55:66");
}  // namespace

class BtaHdTest : public ::testing::Test {
protected:
  void SetUp() override {
    reset_mock_function_count_map();
    test::mock::osi_allocator::osi_malloc.body = [](size_t size) { return malloc(size); };
    test::mock::osi_allocator::osi_calloc.body = [](size_t size) { return calloc(1UL, size); };
    test::mock::osi_allocator::osi_free.body = [](void* ptr) { free(ptr); };
    test::mock::osi_allocator::osi_free_and_reset.body = [](void** ptr) {
      free(*ptr);
      *ptr = nullptr;
    };
  }

  void TearDown() override {
    bta_hd_cb.p_cback = nullptr;

    test::mock::osi_allocator::osi_malloc = {};
    test::mock::osi_allocator::osi_calloc = {};
    test::mock::osi_allocator::osi_free = {};
    test::mock::osi_allocator::osi_free_and_reset = {};
  }
};

TEST_F(BtaHdTest, simple) {}

TEST_F(BtaHdTest, bta_hd_open_act) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = kRawAddress,
          .data = 0,
          .p_data = nullptr,
  };

  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_OPEN_EVT, event);
    ASSERT_EQ(kRawAddress, p_data->conn.bda);
  };

  bta_hd_open_act((tBTA_HD_DATA*)(&data));
  ASSERT_EQ(kRawAddress, bta_hd_cb.bd_addr);
}

TEST_F(BtaHdTest, bta_hd_close_act__vc_unplug_false) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = kRawAddress,
          .data = 0,
          .p_data = nullptr,
  };

  bta_hd_cb.vc_unplug = FALSE;
  bta_hd_cb.bd_addr = kRawAddress;

  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_CLOSE_EVT, event);
    ASSERT_EQ(kRawAddress, p_data->conn.bda);
  };

  bta_hd_close_act((tBTA_HD_DATA*)(&data));
  ASSERT_EQ(RawAddress::kEmpty, bta_hd_cb.bd_addr);
}

TEST_F(BtaHdTest, bta_hd_close_act__vc_unplug_true) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = kRawAddress,
          .data = 0,
          .p_data = nullptr,
  };

  bta_hd_cb.vc_unplug = TRUE;
  bta_hd_cb.bd_addr = kRawAddress;

  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_VC_UNPLUG_EVT, event);
    ASSERT_EQ(kRawAddress, p_data->conn.bda);
  };

  bta_hd_close_act((tBTA_HD_DATA*)(&data));
  ASSERT_EQ(RawAddress::kEmpty, bta_hd_cb.bd_addr);
  ASSERT_EQ(FALSE, bta_hd_cb.vc_unplug);
}

TEST_F(BtaHdTest, bta_hd_intr_data_act__use_report_id_true) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = RawAddress::kEmpty,
          .data = 32,
          .p_data = static_cast<BT_HDR*>(osi_calloc(32 + sizeof(BT_HDR))),
  };

  bta_hd_cb.use_report_id = true;
  bta_hd_cb.boot_mode = false;

  data.p_data->len = static_cast<uint16_t>(data32.size());
  data.p_data->offset = 0;
  uint8_t* p_data = (uint8_t*)(data.p_data + 1);
  std::copy(data32.begin(), data32.end(), p_data);

  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_INTR_DATA_EVT, event);
    uint8_t* data = (uint8_t*)(p_data->intr_data.p_data);
    ASSERT_EQ(data32[0], p_data->intr_data.report_id);
    ASSERT_TRUE(std::equal(data32.begin() + 1, data32.end(), data));
  };

  bta_hd_intr_data_act((tBTA_HD_DATA*)(&data));
}

TEST_F(BtaHdTest, bta_hd_get_report_act__use_report_id_true) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = RawAddress::kEmpty,
          .data = 32,
          .p_data = static_cast<BT_HDR*>(osi_calloc(32 + sizeof(BT_HDR))),
  };

  bta_hd_cb.use_report_id = true;

  data.p_data->len = static_cast<uint16_t>(data32.size());
  data.p_data->offset = 0;
  uint8_t* p_data = (uint8_t*)(data.p_data + 1);
  std::copy(data32.begin(), data32.end(), p_data);

  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_GET_REPORT_EVT, event);
    ASSERT_EQ(0x01, p_data->get_report.report_type);
    ASSERT_EQ(0x02, p_data->get_report.report_id);
    ASSERT_EQ(0x0403, p_data->get_report.buffer_size);
  };

  bta_hd_get_report_act((tBTA_HD_DATA*)(&data));
}

TEST_F(BtaHdTest, bta_hd_set_report_act) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = RawAddress::kEmpty,
          .data = 32,
          .p_data = static_cast<BT_HDR*>(osi_calloc(32 + sizeof(BT_HDR))),
  };

  bta_hd_cb.use_report_id = true;

  data.p_data->len = static_cast<uint16_t>(data32.size());
  data.p_data->offset = 0;
  uint8_t* p_data = (uint8_t*)(data.p_data + 1);
  std::copy(data32.begin(), data32.end(), p_data);

  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_SET_REPORT_EVT, event);
    ASSERT_EQ(0x01, p_data->set_report.report_type);
    ASSERT_EQ(0x02, p_data->set_report.report_id);
    ASSERT_EQ(30, p_data->set_report.len);
    ASSERT_TRUE(std::equal(data32.begin() + 2, data32.end(), p_data->set_report.p_data));
  };

  bta_hd_set_report_act((tBTA_HD_DATA*)(&data));
}

TEST_F(BtaHdTest, bta_hd_vc_unplug_act) {
  bta_hd_vc_unplug_act();
  ASSERT_EQ(1, get_func_call_count("HID_DevVirtualCableUnplug"));
  ASSERT_EQ(true, bta_hd_cb.vc_unplug);
}

TEST_F(BtaHdTest, bta_hd_vc_unplug_done_act) {
  tBTA_HD_CBACK_DATA data = {
          .hdr =
                  {
                          .event = 0,
                          .len = 0,
                          .offset = 0,
                          .layer_specific = 0,
                  },
          .addr = RawAddress::kEmpty,
          .data = 32,
          .p_data = static_cast<BT_HDR*>(osi_calloc(sizeof(BT_HDR))),
  };

  data.addr = kRawAddress;
  bta_hd_cb.p_cback = [](tBTA_HD_EVT event, tBTA_HD* p_data) {
    ASSERT_EQ(BTA_HD_VC_UNPLUG_EVT, event);
    ASSERT_EQ(kRawAddress, p_data->conn.bda);
  };

  bta_hd_vc_unplug_done_act((tBTA_HD_DATA*)(&data));
  ASSERT_EQ(1, get_func_call_count("HID_DevUnplugDevice"));
  ASSERT_EQ(kRawAddress, bta_hd_cb.bd_addr);
}
