/******************************************************************************
 *
 *  Copyright 2017 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include <bluetooth/types/address.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using testing::ElementsAre;

static const char* test_addr = "12:34:56:78:9a:bc";
static const char* test_addr2 = "cb:a9:87:65:43:21";

// Validate the consteval constructor.
// Note: negative test cases cannot be implemented as they would generate compilation errors.
TEST(RawAddressUnittest, ConstructorString) {
  EXPECT_THAT(RawAddress("01:23:45:67:89:ab").address,
              ElementsAre(0x01, 0x23, 0x45, 0x67, 0x89, 0xab));
  EXPECT_THAT(RawAddress("cd:ef:AB:CD:EF:00").address,
              ElementsAre(0xcd, 0xef, 0xab, 0xcd, 0xef, 0x00));
}

TEST(RawAddressUnittest, ConstructorArray) {
  EXPECT_THAT(RawAddress(std::array<uint8_t, 6>{0x01, 0x23, 0x45, 0x67, 0x89, 0xab}).address,
              ElementsAre(0x01, 0x23, 0x45, 0x67, 0x89, 0xab));
}

TEST(RawAddressUnittest, test_is_empty) {
  RawAddress empty = RawAddress::FromString("00:00:00:00:00:00").value();
  ASSERT_TRUE(empty.IsEmpty());

  RawAddress not_empty = RawAddress::FromString("00:00:00:00:00:01").value();
  ASSERT_FALSE(not_empty.IsEmpty());
}

TEST(RawAddressUnittest, test_to_from_str) {
  RawAddress bdaddr = RawAddress::FromString(test_addr).value();

  ASSERT_EQ(0x12, bdaddr.address[0]);
  ASSERT_EQ(0x34, bdaddr.address[1]);
  ASSERT_EQ(0x56, bdaddr.address[2]);
  ASSERT_EQ(0x78, bdaddr.address[3]);
  ASSERT_EQ(0x9A, bdaddr.address[4]);
  ASSERT_EQ(0xBC, bdaddr.address[5]);

  std::string ret = bdaddr.ToString();

  ASSERT_STREQ(test_addr, ret.c_str());
}

TEST(RawAddressUnittest, test_from_octets) {
  static const uint8_t test_addr_array[] = {0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc};

  RawAddress bdaddr = RawAddress::FromOctets(test_addr_array);

  ASSERT_EQ(0x12, bdaddr.address[0]);
  ASSERT_EQ(0x34, bdaddr.address[1]);
  ASSERT_EQ(0x56, bdaddr.address[2]);
  ASSERT_EQ(0x78, bdaddr.address[3]);
  ASSERT_EQ(0x9A, bdaddr.address[4]);
  ASSERT_EQ(0xBC, bdaddr.address[5]);

  std::string ret = bdaddr.ToString();

  ASSERT_STREQ(test_addr, ret.c_str());
}

TEST(RawAddressTest, test_equals) {
  RawAddress bdaddr1 = RawAddress::FromString(test_addr).value();
  RawAddress bdaddr2 = RawAddress::FromString(test_addr).value();
  EXPECT_TRUE(bdaddr1 == bdaddr2);
  EXPECT_FALSE(bdaddr1 != bdaddr2);
  EXPECT_TRUE(bdaddr1 == bdaddr1);
  EXPECT_FALSE(bdaddr1 != bdaddr1);

  RawAddress bdaddr3 = RawAddress::FromString(test_addr2).value();
  EXPECT_FALSE(bdaddr2 == bdaddr3);
  EXPECT_TRUE(bdaddr2 != bdaddr3);
}

TEST(RawAddressTest, test_less_than) {
  RawAddress bdaddr1 = RawAddress::FromString(test_addr).value();
  RawAddress bdaddr2 = RawAddress::FromString(test_addr).value();
  EXPECT_FALSE(bdaddr1 < bdaddr2);
  EXPECT_FALSE(bdaddr1 < bdaddr1);

  RawAddress bdaddr3 = RawAddress::FromString(test_addr2).value();
  EXPECT_TRUE(bdaddr2 < bdaddr3);
  EXPECT_FALSE(bdaddr3 < bdaddr2);
}

TEST(RawAddressTest, test_more_than) {
  RawAddress bdaddr1 = RawAddress::FromString(test_addr).value();
  RawAddress bdaddr2 = RawAddress::FromString(test_addr).value();
  EXPECT_FALSE(bdaddr1 > bdaddr2);
  EXPECT_FALSE(bdaddr1 > bdaddr1);

  RawAddress bdaddr3 = RawAddress::FromString(test_addr2).value();
  EXPECT_FALSE(bdaddr2 > bdaddr3);
  EXPECT_TRUE(bdaddr3 > bdaddr2);
}

TEST(RawAddressTest, test_less_than_or_equal) {
  RawAddress bdaddr1 = RawAddress::FromString(test_addr).value();
  RawAddress bdaddr2 = RawAddress::FromString(test_addr).value();
  EXPECT_TRUE(bdaddr1 <= bdaddr2);
  EXPECT_TRUE(bdaddr1 <= bdaddr1);

  RawAddress bdaddr3 = RawAddress::FromString(test_addr2).value();
  EXPECT_TRUE(bdaddr2 <= bdaddr3);
  EXPECT_FALSE(bdaddr3 <= bdaddr2);
}

TEST(RawAddressTest, test_more_than_or_equal) {
  RawAddress bdaddr1 = RawAddress::FromString(test_addr).value();
  RawAddress bdaddr2 = RawAddress::FromString(test_addr).value();
  EXPECT_TRUE(bdaddr1 >= bdaddr2);
  EXPECT_TRUE(bdaddr1 >= bdaddr1);

  RawAddress bdaddr3 = RawAddress::FromString(test_addr2).value();
  EXPECT_FALSE(bdaddr2 >= bdaddr3);
  EXPECT_TRUE(bdaddr3 >= bdaddr2);
}

TEST(RawAddressTest, test_copy) {
  RawAddress bdaddr1 = RawAddress::FromString(test_addr).value();
  RawAddress bdaddr2 = bdaddr1;

  EXPECT_TRUE(bdaddr1 == bdaddr2);
}

TEST(RawAddressTest, IsValidAddress) {
  EXPECT_FALSE(RawAddress::IsValidAddress(""));
  EXPECT_FALSE(RawAddress::IsValidAddress("000000000000"));
  EXPECT_FALSE(RawAddress::IsValidAddress("00:00:00:00:0000"));
  EXPECT_FALSE(RawAddress::IsValidAddress("00:00:00:00:00:0"));
  EXPECT_FALSE(RawAddress::IsValidAddress("00:00:00:00:00:0;"));
  EXPECT_TRUE(RawAddress::IsValidAddress("00:00:00:00:00:00"));
  EXPECT_TRUE(RawAddress::IsValidAddress("AB:cd:00:00:00:00"));
  EXPECT_FALSE(RawAddress::IsValidAddress("aB:cD:eF:Gh:iJ:Kl"));
}

TEST(RawAddressTest, BdAddrFromString) {
  EXPECT_EQ(RawAddress::FromString("00:00:00:00:00:00"), RawAddress::kEmpty);
  EXPECT_EQ(RawAddress::FromString("ab:01:4C:d5:21:9f"), RawAddress("ab:01:4C:d5:21:9f"));
  EXPECT_EQ(RawAddress::FromString("ab:01:4C:d5:21"), std::nullopt);
  EXPECT_EQ(RawAddress::FromString("ab:01:4C:d5:21aaa"), std::nullopt);
  EXPECT_EQ(RawAddress::FromString("ab:01:4C:d5:21:xx"), std::nullopt);
}

TEST(RawAddress, ToStringTest) {
  RawAddress addr("11:22:33:44:55:ab");
  const std::string redacted_loggable_str = "xx:xx:xx:xx:55:ab";
  const std::string loggbable_str = "11:22:33:44:55:ab";
  std::string ret1 = addr.ToString();
  ASSERT_STREQ(ret1.c_str(), loggbable_str.c_str());
  std::string ret2 = addr.ToRedactedStringForLogging();
  ASSERT_STREQ(ret2.c_str(), redacted_loggable_str.c_str());
}

TEST(RawAddressUnittest, ToUint64) {
  RawAddress addr("12:34:56:78:9a:bc");
  // Big-endian: MSB is index 0 (0x12)
  EXPECT_EQ(addr.ToUint64(), 0x123456789abcULL);
}

TEST(RawAddressUnittest, FromUint64) {
  EXPECT_EQ(RawAddress::FromUint64(0x123456789abcULL), RawAddress("12:34:56:78:9a:bc"));
}
