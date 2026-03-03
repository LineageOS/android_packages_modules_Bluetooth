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

#include <base/functional/callback.h>
#include <gmock/gmock.h>

#include <cstdint>
#include <vector>

#include "stack/include/btm_iso_api_types.h"
#include "stack/include/hcimsgs.h"

namespace hcic {

class MockHcicInterface : public ::bluetooth::legacy::hci::Interface {
public:
  // clang-format off
  MOCK_METHOD((void), SetCigParams,
              (uint8_t cig_id, struct bluetooth::hci::iso_manager::cig_create_params cig_params,
               base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), RemoveCig, (uint8_t cig_id, base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), CreateCis,
              (uint8_t num_cis, const EXT_CIS_CREATE_CFG* cis_create_cfg,
               base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), SetupIsoDataPath,
              (uint16_t iso_handle, uint8_t data_path_dir, uint8_t data_path_id,
               uint8_t codec_id_format, uint16_t codec_id_company, uint16_t codec_id_vendor,
               uint32_t controller_delay, std::vector<uint8_t> codec_conf,
               base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), RemoveIsoDataPath,
              (uint16_t iso_handle, uint8_t data_path_dir,
               base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), ReadIsoLinkQuality,
              (uint16_t iso_handle, base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), CreateBig,
              (uint8_t big_handle,
               struct bluetooth::hci::iso_manager::big_create_params big_params));

  MOCK_METHOD((void), TerminateBig, (uint8_t big_handle, uint8_t reason));

  MOCK_METHOD((void), SetBigChannelMapClassificationByConnHandles,
              (uint8_t action, uint8_t big_handle, const std::vector<uint16_t>& conn_handles));

  MOCK_METHOD((void), AcceptCis, (uint16_t cis_conn_handle));

  MOCK_METHOD((void), RejectCis,
              (uint16_t cis_conn_handle, uint8_t reason,
               base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  MOCK_METHOD((void), BigCreateSync,
              (uint8_t big_handle, uint16_t sync_handle, uint8_t encryption,
               (const std::array<uint8_t, 16>& bcast_code), uint8_t mse, uint16_t sync_timeout,
               const std::vector<uint8_t>& bis));

  MOCK_METHOD((void), BigTerminateSync,
              (uint8_t big_handle, base::OnceCallback<void(uint8_t*, uint16_t)> cb));

  // bluetooth::legacy::hci::Interface
  MOCK_METHOD(void, Disconnect, (uint16_t handle, uint8_t reason), (const override));
  MOCK_METHOD(void, ChangeConnectionPacketType, (uint16_t handle, uint16_t packet_types), (const override));
  MOCK_METHOD(void, StartRoleSwitch, (const RawAddress& bd_addr, uint8_t role), (const override));
  MOCK_METHOD(void, ConfigureDataPath,
              (hci_data_direction_t data_path_direction, uint8_t data_path_id,
               std::vector<uint8_t> vendor_config),
              (const override));
  // clang-format on
};

void SetMockHcicInterface(MockHcicInterface* mock_hcic_interface);

}  // namespace hcic
