/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "enum_gen.h"

#include <format>
#include <iostream>

#include "util.h"

EnumGen::EnumGen(EnumDef e) : e_(std::move(e)) {}

void EnumGen::GenDefinition(std::ostream& stream) {
  stream << "enum class " << e_.name_ << " : " << util::GetTypeForSize(e_.size_) << " {";
  for (const auto& pair : e_.constants_) {
    stream << std::format("{} = {:#x},", pair.second, pair.first);
  }
  stream << "};\n";
}

void EnumGen::GenDefinitionPybind11(std::ostream& stream) {
  stream << "py::enum_<" << e_.name_ << ">(m, \"" << e_.name_ << "\")";
  for (const auto& pair : e_.constants_) {
    stream << ".value(\"" << pair.second << "\", " << e_.name_ << "::" << pair.second << ")";
  }
  stream << ";\n";
}

void EnumGen::GenLogging(std::ostream& stream) {
  // Print out the switch statement that converts all the constants to strings.
  stream << "inline std::string " << e_.name_ << "Text(const " << e_.name_ << "& param) {";
  stream << "switch (param) {";
  for (const auto& pair : e_.constants_) {
    stream << std::format("case {}::{}: return \"{}({:#0{}x})\";", e_.name_, pair.second,
                          pair.second, pair.first, 2 + (e_.size_ > 4 ? e_.size_ / 4 : 0));
  }
  stream << "default: return std::format(\"Unknown " << e_.name_ << "({:#0"
         << 2 + (e_.size_ > 4 ? e_.size_ / 4 : 0) << "x})\", static_cast<uint64_t>(param));";
  stream << "}";
  stream << "}\n\n";
}
