/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifdef __ANDROID__
#error "sysprops_module is not supposed to be used on Android"
#endif

#include <string>

namespace bluetooth {
namespace sysprops {

class SyspropsModule {
public:
  SyspropsModule();
  SyspropsModule(const SyspropsModule&) = delete;
  SyspropsModule& operator=(const SyspropsModule&) = delete;

  ~SyspropsModule() = default;

private:
  void parse_config(std::string file_path);
};

void InitSyspropsModule();

}  // namespace sysprops
}  // namespace bluetooth
