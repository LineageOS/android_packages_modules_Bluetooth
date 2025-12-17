/******************************************************************************
 *
 *  Copyright (C) 2017 Google, Inc.
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

#include <bluetooth/types/uuid.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <array>
#include <cstdint>

namespace bluetooth {

using testing::ElementsAre;

// Validate the consteval 16-bit constructor.
// Note: negative test cases cannot be implemented as they would generate compilation errors.
TEST(UuidTest, ConstructorUuid16) {
  EXPECT_THAT(Uuid("0123").uu, ElementsAre(0x00, 0x00, 0x01, 0x23, 0x00, 0x00, 0x10, 0x00, 0x80,
                                           0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("4567").uu, ElementsAre(0x00, 0x00, 0x45, 0x67, 0x00, 0x00, 0x10, 0x00, 0x80,
                                           0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("89ab").uu, ElementsAre(0x00, 0x00, 0x89, 0xab, 0x00, 0x00, 0x10, 0x00, 0x80,
                                           0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("cdef").uu, ElementsAre(0x00, 0x00, 0xcd, 0xef, 0x00, 0x00, 0x10, 0x00, 0x80,
                                           0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("ABCD").uu, ElementsAre(0x00, 0x00, 0xab, 0xcd, 0x00, 0x00, 0x10, 0x00, 0x80,
                                           0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("EF00").uu, ElementsAre(0x00, 0x00, 0xef, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80,
                                           0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
}

// Validate the consteval 32-bit constructor.
// Note: negative test cases cannot be implemented as they would generate compilation errors.
TEST(UuidTest, ConstructorUuid32) {
  EXPECT_THAT(Uuid("01234567").uu, ElementsAre(0x01, 0x23, 0x45, 0x67, 0x00, 0x00, 0x10, 0x00, 0x80,
                                               0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("89abcdef").uu, ElementsAre(0x89, 0xab, 0xcd, 0xef, 0x00, 0x00, 0x10, 0x00, 0x80,
                                               0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
  EXPECT_THAT(Uuid("ABCDEF00").uu, ElementsAre(0xab, 0xcd, 0xef, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80,
                                               0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb));
}

// Validate the consteval 128-bit constructor.
// Note: negative test cases cannot be implemented as they would generate compilation errors.
TEST(UuidTest, ConstructorUuid128) {
  EXPECT_THAT(Uuid("01234567-89ab-cdef-ABCD-EFaAaAaAaAaA").uu,
              ElementsAre(0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0xab, 0xcd, 0xef, 0xaa,
                          0xaa, 0xaa, 0xaa, 0xaa));
}

TEST(UuidTest, IsEmpty) {
  EXPECT_TRUE(Uuid::kEmpty.IsEmpty());
  EXPECT_FALSE(Uuid("1234").IsEmpty());
}

TEST(UuidTest, GetShortestRepresentationSize) {
  EXPECT_EQ(Uuid("1111").GetShortestRepresentationSize(), Uuid::kNumBytes16);
  EXPECT_EQ(Uuid("11112222").GetShortestRepresentationSize(), Uuid::kNumBytes32);
  EXPECT_EQ(Uuid("11112222-3333-4444-5555-666677778888").GetShortestRepresentationSize(),
            Uuid::kNumBytes128);
}

TEST(UuidTest, As16Bit) {
  EXPECT_EQ(Uuid("1111").As16Bit(), 0x1111u);
  EXPECT_EQ(Uuid("11112222").As16Bit(), 0x2222u);
  EXPECT_EQ(Uuid("11112222-3333-4444-5555-666677778888").As16Bit(), 0x2222u);
}

TEST(UuidTest, As32Bit) {
  EXPECT_EQ(Uuid("1111").As32Bit(), 0x00001111u);
  EXPECT_EQ(Uuid("11112222").As32Bit(), 0x11112222u);
  EXPECT_EQ(Uuid("11112222-3333-4444-5555-666677778888").As32Bit(), 0x11112222u);
}

TEST(UuidTest, Is16Bit) {
  EXPECT_TRUE(Uuid("1111").Is16Bit());
  EXPECT_TRUE(Uuid("00001111").Is16Bit());
  EXPECT_TRUE(Uuid("00001111-0000-1000-8000-00805f9b34fb").Is16Bit());
  EXPECT_FALSE(Uuid("11112222").Is16Bit());
  EXPECT_FALSE(Uuid("11112222-0000-1000-8000-00805f9b34fb").Is16Bit());
  EXPECT_FALSE(Uuid("11112222-3333-4444-5555-666677778888").Is16Bit());
}

TEST(UuidTest, From16Bit) {
  EXPECT_EQ(Uuid::From16Bit(0x0000), Uuid("0000"));
  EXPECT_EQ(Uuid::From16Bit(0x0123), Uuid("0123"));
}

TEST(UuidTest, From32Bit) {
  EXPECT_EQ(Uuid::From32Bit(0x00000000), Uuid("00000000"));
  EXPECT_EQ(Uuid::From32Bit(0x0123abcd), Uuid("0123abcd"));
}

TEST(UuidTest, ToString) {
  constexpr char UUID16_STR[] = "00001111-0000-1000-8000-00805f9b34fb";
  constexpr char UUID32_STR[] = "11112222-0000-1000-8000-00805f9b34fb";
  constexpr char UUID128_STR[] = "11111111-1111-1111-1111-111111111111";

  EXPECT_EQ(Uuid(UUID16_STR).ToString(), UUID16_STR);
  EXPECT_EQ(Uuid(UUID32_STR).ToString(), UUID32_STR);
  EXPECT_EQ(Uuid(UUID128_STR).ToString(), UUID128_STR);
}

TEST(UuidTest, FromString) {
  // Valid inputs.
  constexpr char UUID16[6][5] = {"0123", "4567", "89ab", "cdef", "ABCD", "EF00"};
  constexpr char UUID32[3][9] = {"01234567", "89abcdef", "ABCDEF00"};
  constexpr char UUID128[] = "01234567-89ab-cdef-ABCD-EFaAaAaAaAaA";

  EXPECT_EQ(Uuid::FromString(UUID16[0]), Uuid(UUID16[0]));
  EXPECT_EQ(Uuid::FromString(UUID16[1]), Uuid(UUID16[1]));
  EXPECT_EQ(Uuid::FromString(UUID16[2]), Uuid(UUID16[2]));
  EXPECT_EQ(Uuid::FromString(UUID16[3]), Uuid(UUID16[3]));
  EXPECT_EQ(Uuid::FromString(UUID16[4]), Uuid(UUID16[4]));
  EXPECT_EQ(Uuid::FromString(UUID16[5]), Uuid(UUID16[5]));
  EXPECT_EQ(Uuid::FromString(UUID32[0]), Uuid(UUID32[0]));
  EXPECT_EQ(Uuid::FromString(UUID32[1]), Uuid(UUID32[1]));
  EXPECT_EQ(Uuid::FromString(UUID32[2]), Uuid(UUID32[2]));
  EXPECT_EQ(Uuid::FromString(UUID128), Uuid(UUID128));

  // Invalid lengths.
  EXPECT_EQ(Uuid::FromString(""), std::nullopt);
  EXPECT_EQ(Uuid::FromString("012"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("01234"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("0123456"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("012345678"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("01234567-89ab-cdef-ABCD-EFaAaAaAaAa"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("01234567-89ab-cdef-ABCD-EFaAaAaAaAaAA"), std::nullopt);

  // Invalid hyphen placement.
  EXPECT_EQ(Uuid::FromString("0123456789abcdefABCDEFaAaAaAaAaA"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("01234567-89ab-cdef-ABCDE-FaAaAaAaAaA"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("0123456789a-b-cdef-ABCDE-FaAaAaAaAaA"), std::nullopt);

  // Invalid characters.
  EXPECT_EQ(Uuid::FromString("012_"), std::nullopt);
  EXPECT_EQ(Uuid::FromString("012g"), std::nullopt);
}

}  // namespace bluetooth
