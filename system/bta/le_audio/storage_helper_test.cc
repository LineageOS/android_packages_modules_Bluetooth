/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "storage_helper.h"

#include <gtest/gtest.h>

using bluetooth::le_audio::LeAudioDevice;

namespace bluetooth::le_audio {

static RawAddress GetTestAddress(uint8_t index) {
  EXPECT_LT(index, UINT8_MAX);
  RawAddress result = {{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index}};
  return result;
}

class StorageHelperTest : public ::testing::Test {};

TEST(StorageHelperTest, DeserializeSinkPacs) {
  // clang-format off
        const std::vector<uint8_t> validSinkPack = {
                0x00,  // Magic
                0x01,  // Num of PACs
                0x02, 0x12,  // handle
                0x03, 0x12,  // cc handle
                0x02,  // Number of records in PAC
                0x1e,  // PAC entry size
                0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
                0x13,  // Codec specific cap. size
                0x03, 0x01, 0x04, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x1e, 0x00, 0x1e, 0x00, 0x02, 0x05, 0x01,  // Codec specific capa
                0x04,  // Metadata size
                0x03, 0x01, 0xff, 0x0f,  // Metadata
                0x1e,  //
                0x06, 0x00, 0x00, 0x00, 0x00,  // Codec ID
                0x13,  // Codec specific cap. size
                0x03, 0x01, 0x20, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x3c, 0x00, 0x3c, 0x00, 0x02, 0x05, 0x01,  // Codec specific capa
                0x04,  // Codec specific capa
                0x03, 0x01, 0xff, 0x0f,  // Metadata
        };

        const std::vector<uint8_t> invalidSinkPackNumOfPacs = {
                0x00,  // Magic
                0x05,  // Num of PACs
                0x02, 0x12,  // handle
                0x03, 0x12,  // cc handle
                0x01,  // Number of records in PAC
                0x1e,  // PAC entry size
                0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
                0x13,  // Codec specific cap. size
                0x03, 0x01, 0x04, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x1e, 0x00, 0x1e, 0x00, 0x02, 0x05, 0x01,  // Codec specific capa
                0x04,  // Metadata size
                0x03, 0x01, 0xff, 0x0f,  // Metadata
                0x1e,  //
                0x06, 0x00, 0x00, 0x00, 0x00,  // Codec ID
                0x13,  // Codec specific cap. size
                0x03, 0x01, 0x20, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x3c, 0x00, 0x3c, 0x00, 0x02, 0x05, 0x01,  // Codec specific capa
                0x04,  // Codec specific capa
                0x03, 0x01, 0xff, 0x0f,  // Metadata
        };

        const std::vector<uint8_t> invalidSinkPackMagic = {
                0x01,  // Magic
                0x01,  // Num of PACs
                0x02, 0x12,  // handle
                0x03, 0x12,  // cc handle
                0x02,  // Number of records in PAC
                0x1e,  // PAC entry size
                0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
                0x13,  // Codec specific cap. size
                0x03, 0x01, 0x04, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x1e, 0x00, 0x1e, 0x00, 0x02, 0x05, 0x01,  // Codec specific capa
                0x04,  // Metadata size
                0x03, 0x01, 0xff, 0x0f,  // Metadata
                0x1e,  //
                0x06, 0x00, 0x00, 0x00, 0x00,  // Codec ID
                0x13,  // Codec specific cap. size
                0x03, 0x01, 0x20, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x3c, 0x00, 0x3c, 0x00, 0x02, 0x05, 0x01,  // Codec specific capa
                0x04,  // Codec specific capa
                0x03, 0x01, 0xff, 0x0f,  // Metadata
        };
  // clang-format on

  RawAddress test_address0 = GetTestAddress(0);
  LeAudioDevice leAudioDevice(test_address0, DeviceConnectState::DISCONNECTED);
  ASSERT_TRUE(DeserializeSinkPacs(&leAudioDevice, validSinkPack));
  std::vector<uint8_t> serialize;
  ASSERT_TRUE(SerializeSinkPacs(&leAudioDevice, serialize));
  ASSERT_TRUE(serialize == validSinkPack);

  ASSERT_FALSE(DeserializeSinkPacs(&leAudioDevice, invalidSinkPackMagic));
  ASSERT_FALSE(DeserializeSinkPacs(&leAudioDevice, invalidSinkPackNumOfPacs));
}

TEST(StorageHelperTest, DeserializeSourcePacs) {
  // clang-format off
  const std::vector<uint8_t> validSourcePack = {
        0x00,  // Magic
        0x01,  // Num of PACs
        0x08, 0x12,  // handle
        0x09, 0x12,  // cc handle
        0x02,  // Number of records in PAC
        0x1e,  // PAC entry size
        0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
        0x13,  // Codec specific cap. size
        0x03, 0x01, 0x04, 0x00, 0x02, 0x02, 0x01, 0x02, 0x03, 0x01, 0x05, 0x04, 0x1e, 0x00, 0x1e, 0x00, 0x02, 0x05, 0x01,
        0x04,  // Metadata size
        0x03, 0x01, 0x03, 0x00,  // Metadata
        0x1e,  // PAC entry size
        0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
        0x13,  // Codec specific cap. size
        0x03, 0x01, 0x20, 0x00, 0x02, 0x02, 0x01, 0x02,  // Codec specific capa
        0x03, 0x01, 0x05, 0x04, 0x3c, 0x00, 0x3c, 0x00,  // Codec specific capa
        0x02, 0x05, 0x01,  // Codec specific capa
        0x04,  // Metadata size
        0x03, 0x01, 0x03, 0x00  // Metadata
  };

  const std::vector<uint8_t> invalidSourcePackNumOfPacs = {
        0x00,  // Magic
        0x04,  // Num of PACs
        0x08, 0x12,  // handle
        0x09, 0x12,  // cc handle
        0x01,  // Number of records in PAC
        0x1e,  // PAC entry size
        0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
        0x13,  // Codec specific cap. size
        0x03, 0x01, 0x04, 0x00, 0x02, 0x02, 0x01, 0x02,  // Codec specific capa
        0x03, 0x01, 0x05, 0x04, 0x1e, 0x00, 0x1e, 0x00,  // Codec specific capa
        0x02, 0x05, 0x01,  // Codec specific capa
        0x04,  // Metadata size
        0x03, 0x01, 0x03, 0x00,  // Metadata
        0x1e,  // PAC entry size
        0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
        0x13,  // Codec specific cap. size
        0x03, 0x01, 0x20, 0x00, 0x02, 0x02, 0x01, 0x02,  // Codec specific capa
        0x03, 0x01, 0x05, 0x04, 0x3c, 0x00, 0x3c, 0x00,  // Codec specific capa
        0x02, 0x05, 0x01,  // Codec specific capa
        0x04,  // Metadata size
        0x03, 0x01, 0x03, 0x00  // Metadata
 };

  const std::vector<uint8_t> invalidSourcePackMagic = {
        0x01,  // Magic
        0x01,  // Num of PACs
        0x08, 0x12,  // handle
        0x09, 0x12,  // cc handle
        0x02,  // Number of records in PAC
        0x1e,  // PAC entry size
        0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
        0x13,  // Codec specific cap. size
        0x03, 0x01, 0x04, 0x00, 0x02, 0x02, 0x01, 0x02,  // Codec specific capa
        0x03, 0x01, 0x05, 0x04, 0x1e, 0x00, 0x1e, 0x00,  // Codec specific capa
        0x02, 0x05, 0x01,  // Codec specific capa
        0x04,  // Metadata size
        0x03, 0x01, 0x03, 0x00,  // Metadata
        0x1e,  // PAC entry size
        0x06, 0x00, 0x00, 0x00, 0x00,  // Codec Id
        0x13,  // Codec specific cap. size
        0x03, 0x01, 0x20, 0x00, 0x02, 0x02, 0x01, 0x02,  // Codec specific capa
        0x03, 0x01, 0x05, 0x04, 0x3c, 0x00, 0x3c, 0x00,  // Codec specific capa
        0x02, 0x05, 0x01,  // Codec specific capa
        0x04,  // Metadata size
        0x03, 0x01, 0x03, 0x00  // Metadata
  };
  // clang-format on

  RawAddress test_address0 = GetTestAddress(0);
  LeAudioDevice leAudioDevice(test_address0, DeviceConnectState::DISCONNECTED);
  ASSERT_TRUE(DeserializeSourcePacs(&leAudioDevice, validSourcePack));
  std::vector<uint8_t> serialize;
  ASSERT_TRUE(SerializeSourcePacs(&leAudioDevice, serialize));
  ASSERT_TRUE(serialize == validSourcePack);

  ASSERT_FALSE(DeserializeSourcePacs(&leAudioDevice, invalidSourcePackMagic));
  ASSERT_FALSE(DeserializeSourcePacs(&leAudioDevice, invalidSourcePackNumOfPacs));
}

TEST(StorageHelperTest, DeserializeAses) {
  // clang-format off
  const std::vector<uint8_t> validAses {
        0x00,  // Magic
        0x03,  // Num of ASEs
        0x05, 0x11,  // handle
        0x06, 0x11,  // ccc handle
        0x01,  // ASE id
        0x01,  // direction
        0x08, 0x11,  // handle
        0x09, 0x11,  // ccc handle
        0x02,  // ASE id
        0x01,  // direction
        0x0b, 0x11,  // handle
        0x0c, 0x11,  // ccc handle
        0x03,  // ASE id
        0x02  // direction
  };
  const std::vector<uint8_t> invalidAsesNumOfAses {
        0x00,  // Magic
        0x05,  // Num of ASEs
        0x05, 0x11,  // handle
        0x06, 0x11,  // ccc handle
        0x01,  // ASE id
        0x01,  // direction
        0x08, 0x11,  // handle
        0x09, 0x11,  // ccc handle
        0x02,  // ASE id
        0x01,  // direction
        0x0b, 0x11,  // handle
        0x0c, 0x11,  // ccc handle
        0x03,  // ASE id
        0x02  // direction
  };
  const std::vector<uint8_t> invalidAsesMagic {
        0x01,  // Magic
        0x03,  // Num of ASEs
        0x05, 0x11,  // handle
        0x06, 0x11,  // ccc handle
        0x01,  // ASE id
        0x01,  // direction
        0x08, 0x11,  // handle
        0x09, 0x11,  // ccc handle
        0x02,  // ASE id
        0x01,  // direction
        0x0b, 0x11,  // handle
        0x0c, 0x11,  // ccc handle
        0x03,  // ASE id
        0x02  // direction
  };
  // clang-format on
  RawAddress test_address0 = GetTestAddress(0);
  LeAudioDevice leAudioDevice(test_address0, DeviceConnectState::DISCONNECTED);
  ASSERT_TRUE(DeserializeAses(&leAudioDevice, validAses));

  std::vector<uint8_t> serialize;
  ASSERT_TRUE(SerializeAses(&leAudioDevice, serialize));
  ASSERT_TRUE(serialize == validAses);

  ASSERT_FALSE(DeserializeAses(&leAudioDevice, invalidAsesNumOfAses));
  ASSERT_FALSE(DeserializeAses(&leAudioDevice, invalidAsesMagic));
}

TEST(StorageHelperTest, DeserializeHandles) {
  // clang-format off
  const std::vector<uint8_t> validHandles {
        0x00,  // Magic
        0x0e, 0x11,  // Control point handle
        0x0f, 0x11,  // Control point ccc handle
        0x05, 0x12,  // Sink audio location handle
        0x06, 0x12,  // Sink audio location ccc handle
        0x0b, 0x12,  // Source audio location handle
        0x0c, 0x12,  // Source audio location ccc handle
        0x11, 0x12,  // Supported context types handle
        0x12, 0x12,  // Supported context types ccc handle
        0x0e, 0x12,  // Available context types handle
        0x0f, 0x12,  // Available context types ccc handle
        0x03, 0xa3  // TMAP role handle
  };
  const std::vector<uint8_t> invalidHandlesMagic {
        0x01,  // Magic
        0x0e, 0x11,  // Control point handle
        0x0f, 0x11,  // Control point ccc handle
        0x05, 0x12,  // Sink audio location handle
        0x06, 0x12,  // Sink audio location ccc handle
        0x0b, 0x12,  // Source audio location handle
        0x0c, 0x12,  // Source audio location ccc handle
        0x11, 0x12,  // Supported context types handle
        0x12, 0x12,  // Supported context types ccc handle
        0x0e, 0x12,  // Available context types handle
        0x0f, 0x12,  // Available context types ccc handle
        0x03, 0xa3  // TMAP role handle
  };
    const std::vector<uint8_t> invalidHandles {
        0x00,  // Magic
        0x0e, 0x11,  // Control point handle
        0x0f, 0x11,  // Control point ccc handle
        0x05, 0x12,  // Sink audio location handle
        0x06, 0x12,  // Sink audio location ccc handle
        0x0b, 0x12,  // Source audio location handle
        0x0c, 0x12,  // Source audio location ccc handle
        0x11, 0x12,  // Supported context types handle
        0x12, 0x12,  // Supported context types ccc handle
        0x0e, 0x12,  // Available context types handle
        0x0f, 0x12,  // Available context types ccc handle
        0x03, 0xa3,  // TMAP role handle
        0x00, 0x00,  // corrupted
  };
  // clang-format on
  RawAddress test_address0 = GetTestAddress(0);
  LeAudioDevice leAudioDevice(test_address0, DeviceConnectState::DISCONNECTED);
  ASSERT_TRUE(DeserializeHandles(&leAudioDevice, validHandles));
  std::vector<uint8_t> serialize;
  ASSERT_TRUE(SerializeHandles(&leAudioDevice, serialize));
  ASSERT_TRUE(serialize == validHandles);

  ASSERT_FALSE(DeserializeHandles(&leAudioDevice, invalidHandlesMagic));
  ASSERT_FALSE(DeserializeHandles(&leAudioDevice, invalidHandles));
}

TEST(StorageHelperTest, DeserializeGmapV1) {
  // clang-format off
  const std::vector<uint8_t> validHandles {
        0x01,  // V1 Layout Magic
        0x0e, 0x11,  // Role Handle
        0x0f, 0x11,  // Feature Handle
        0x05,  // Role value
        0x06,  // Feature value
  };
  const std::vector<uint8_t> invalidHandlesMagic {
        0x00,  // Unknown Layout Magic
        0x0e, 0x11,  // Role Handle
        0x0f, 0x11,  // Feature Handle
        0x05,  // Role value
        0x06,  // Feature value
  };
  const std::vector<uint8_t> invalidHandles {
        0x01,  // V1 Layout Magic
        0x0e, 0x11,  // Role Handle
        0x0f, 0x11,  // Feature Handle
        0x05,  // Role value
        0x06,  // Feature value
        0x06,  // corrupted
  };

  // clang-format on
  RawAddress test_address0 = GetTestAddress(0);
  GmapClient gmap(test_address0);
  ASSERT_TRUE(DeserializeGmap(&gmap, validHandles));
  std::vector<uint8_t> serialize;
  ASSERT_TRUE(SerializeGmap(&gmap, serialize));
  ASSERT_TRUE(serialize == validHandles);

  ASSERT_FALSE(DeserializeGmap(&gmap, invalidHandlesMagic));
  ASSERT_FALSE(DeserializeGmap(&gmap, invalidHandles));
}
}  // namespace bluetooth::le_audio
