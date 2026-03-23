/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <bluetooth/log.h>
#include <bta_gatt_api.h>
#include <hardware/bluetooth.h>

#include <bitset>
#include <unordered_map>

namespace bluetooth::le_audio {

class GmapServerTest;

struct GmapCharacteristic {
  bluetooth::Uuid uuid_;
  uint16_t attribute_handle_;
};

/**
 * As there is only 1 Gaming Audio Server, so here uses static function
 */
class GmapServer {
public:
  friend class GmapServerTest;
  static void DebugDump(int fd);

  /**
   * UGG, UGT, and BGR devices supporting GMAP shall implement the GATT Server role
   * and instantiate only one GMA Server.
   * BGS-only devices are not required by GMAP to implement the GATT Server role.
   * @return true if GMAP Server is enabled
   */
  static bool IsGmapServerEnabled();

  static void UpdateGmapOffloaderSupport(bool value);

  static void Initialize(std::bitset<8> role, std::bitset<8> UGG_feature);

  static void Initialize(std::bitset<8> UGG_feature);

  static std::bitset<8> GetRole();

  static uint16_t GetRoleHandle();

  static std::bitset<8> GetUGGFeature();

  static uint16_t GetUGGFeatureHandle();

  /**
   * This function is used only for testing
   * @return an unordered_map whose key is attribute_handle_ and value is GmapCharacteristic
   */
  static std::unordered_map<uint16_t, GmapCharacteristic> &GetCharacteristics();

  constexpr static uint16_t kGmapRoleLen = 1;
  constexpr static uint16_t kGmapUGGFeatureLen = 1;

  static void OnReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                   const RawAddress& remote_bda, uint16_t handle, uint16_t offset,
                                   bool is_long);

private:
  static bool is_offloader_support_gmap_;
  static uint16_t server_if_;
  static std::unordered_map<uint16_t, GmapCharacteristic> characteristics_;
  static std::bitset<8> role_;
  static std::bitset<8> UGG_feature_;
};
}  // namespace bluetooth::le_audio
