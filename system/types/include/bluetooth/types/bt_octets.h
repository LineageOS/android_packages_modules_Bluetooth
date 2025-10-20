/*
 * Copyright 2021 The Android Open Source Project
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

#include <array>
#include <cstdint>

constexpr unsigned int kOctet8Length = 8;
using Octet8 = std::array<uint8_t, kOctet8Length>;

constexpr unsigned int kOctet16Length = 16;
using Octet16 = std::array<uint8_t, kOctet16Length>;

constexpr unsigned int kOctet32Length = 32;
using Octet32 = std::array<uint8_t, kOctet32Length>;

constexpr Octet8 ZERO_OCTET8 = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

constexpr Octet16 ZERO_OCTET16 = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                                  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

constexpr Octet32 ZERO_OCTET32 = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                                  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                                  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

using PinCode = Octet16;
using LinkKey = Octet16;

/* Sample LTK from BT Spec 5.1 | Vol 6, Part C 1
 * 0x4C68384139F574D836BCF34E9DFB01BF */
constexpr LinkKey SAMPLE_LTK = {0xbf, 0x01, 0xfb, 0x9d, 0x4e, 0xf3, 0xbc, 0x36,
                                0xd8, 0x74, 0xf5, 0x39, 0x41, 0x38, 0x68, 0x4c};
