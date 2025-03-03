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

#include "bta/le_audio/gmap_client.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <hardware/bluetooth.h>

#include "bta/le_audio/le_audio_types.h"
#include "fake_osi.h"
#include "osi/include/properties.h"
#include "test/mock/mock_osi_properties.h"

using bluetooth::le_audio::GmapClient;
using ::testing::_;

static constexpr char kGmapEnabledSysProp[] = "bluetooth.profile.gmap.enabled";

class GmapClientTest : public ::testing::Test {
public:
  RawAddress addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  GmapClient gmapClient = GmapClient(addr);
};

TEST_F(GmapClientTest, test_parse_role) {
  const uint8_t role = 0b0001;
  gmapClient.parseAndSaveGmapRole(1, &role);

  ASSERT_EQ(gmapClient.getRole(), role);
}

TEST_F(GmapClientTest, test_parse_invalid_role) {
  const uint8_t role = 0b0001;
  ASSERT_FALSE(gmapClient.parseAndSaveGmapRole(2, &role));
}

TEST_F(GmapClientTest, test_parse_ugt_feature) {
  const uint8_t value = 0b0001;
  gmapClient.parseAndSaveUGTFeature(1, &value);

  ASSERT_EQ(gmapClient.getUGTFeature(), value);
}

TEST_F(GmapClientTest, test_parse_invalid_ugt_feature) {
  const uint8_t value = 0b0001;
  ASSERT_FALSE(gmapClient.parseAndSaveUGTFeature(2, &value));
}

TEST_F(GmapClientTest, test_add_from_storage) {
  const uint8_t role = 0b0001;
  const uint16_t role_handle = 2;
  const uint8_t UGT_feature = 0b0011;
  const uint16_t UGT_feature_handle = 4;
  gmapClient.AddFromStorage(role, role_handle, UGT_feature, UGT_feature_handle);
  ASSERT_EQ(gmapClient.getRole(), role);
  ASSERT_EQ(gmapClient.getRoleHandle(), role_handle);
  ASSERT_EQ(gmapClient.getUGTFeature(), UGT_feature);
  ASSERT_EQ(gmapClient.getUGTFeatureHandle(), UGT_feature_handle);
}

TEST_F(GmapClientTest, test_role_handle) {
  const uint16_t handle = 5;
  gmapClient.setRoleHandle(handle);
  ASSERT_EQ(gmapClient.getRoleHandle(), handle);
}

TEST_F(GmapClientTest, test_ugt_feature_handle) {
  const uint16_t handle = 6;
  gmapClient.setUGTFeatureHandle(handle);
  ASSERT_EQ(gmapClient.getUGTFeatureHandle(), handle);
}

TEST_F(GmapClientTest, test_is_gmap_client_enabled) {
  GmapClient::UpdateGmapOffloaderSupport(false);
  ASSERT_EQ(GmapClient::IsGmapClientEnabled(), false);

  com::android::bluetooth::flags::provider_->leaudio_gmap_client(true);
  osi_property_set_bool(kGmapEnabledSysProp, true);

  GmapClient::UpdateGmapOffloaderSupport(true);

  ASSERT_EQ(GmapClient::IsGmapClientEnabled(), true);
  osi_property_set_bool(kGmapEnabledSysProp, false);
}
