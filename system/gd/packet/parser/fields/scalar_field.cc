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

#include "fields/scalar_field.h"

#include "fields/fixed_scalar_field.h"
#include "fields/size_field.h"
#include "util.h"

const std::string ScalarField::kFieldType = "ScalarField";

ScalarField::ScalarField(std::string name, int size, ParseLocation loc)
    : PacketField(name, loc), size_(size) {
  if (size_ > 64 || size_ < 0) {
    ERROR(this) << "Not implemented for size_ = " << size_;
  }
}

const std::string& ScalarField::GetFieldType() const { return ScalarField::kFieldType; }

Size ScalarField::GetSize() const { return size_; }

std::string ScalarField::GetDataType() const { return util::GetTypeForSize(size_); }

int GetShiftBits(int i) {
  int bits_past_byte_boundary = i % 8;
  if (bits_past_byte_boundary == 0) {
    return 0;
  } else {
    return 8 - bits_past_byte_boundary;
  }
}

int ScalarField::GenBounds(std::ostream& s, Size start_offset, Size end_offset, Size size) const {
  int num_leading_bits = 0;

  if (!start_offset.empty()) {
    // Default to start if available.
    num_leading_bits = start_offset.bits() % 8;
    if (!start_offset.has_dynamic()) {
      s << "auto " << GetName() << "_it = to_bound + " << (start_offset.bits() / 8) << ";";
    } else {
      s << "auto " << GetName() << "_it = to_bound + (" << start_offset << ") / 8;";
    }
  } else if (!end_offset.empty()) {
    num_leading_bits = GetShiftBits(end_offset.bits() + size.bits());
    Size byte_offset = Size(num_leading_bits + size.bits()) + end_offset;
    s << "auto " << GetName() << "_it = to_bound + (to_bound.NumBytesRemaining() - (" << byte_offset
      << ") / 8);";
  } else {
    ERROR(this) << "Ambiguous offset for field.";
  }
  return num_leading_bits;
}

void ScalarField::GenExtractor(std::ostream& s, int num_leading_bits, bool) const {
  if ((size_ % 8) == 0 && num_leading_bits == 0) {
    // Special case to address trailing scalar fields with an non standard number of bytes
    // (e.g. 24 bit value). If the endianess is little and the number of leading bits is
    // zero then the length is explicitly set.
    s << "auto extracted_value = " << GetName() << "_it.extract<" << GetDataType() << ">("
      << (size_ / 8) << ");";
    s << "*" << GetName() << "_ptr = static_cast<" << GetDataType() << ">(extracted_value);";
    return;
  }

  Size size = GetSize();
  // Extract the correct number of bytes. The return type could be different
  // from the extract type if an earlier field causes the beginning of the
  // current field to start in the middle of a byte.
  std::string extract_type = util::GetTypeForSize(size.bits() + num_leading_bits);
  s << "auto extracted_value = " << GetName() << "_it.extract<" << extract_type << ">();";

  // Right shift the result to remove leading bits.
  if (num_leading_bits != 0) {
    s << "extracted_value >>= " << num_leading_bits << ";";
  }
  // Mask the result if necessary.
  if (util::RoundSizeUp(size.bits()) != size.bits()) {
    uint64_t mask = 0;
    for (int i = 0; i < size.bits(); i++) {
      mask <<= 1;
      mask |= 1;
    }
    s << "extracted_value &= 0x" << std::hex << mask << std::dec << ";";
  }
  s << "*" << GetName() << "_ptr = static_cast<" << GetDataType() << ">(extracted_value);";
}

std::string ScalarField::GetGetterFunctionName() const {
  std::stringstream ss;
  ss << "Get" << util::UnderscoreToCamelCase(GetName());
  return ss.str();
}

void ScalarField::GenGetter(std::ostream& s, Size start_offset, Size end_offset) const {
  s << GetDataType() << " " << GetGetterFunctionName() << "() const {";
  s << "ASSERT(was_validated_);";

  if (!start_offset.empty() && start_offset.bits() % 8 == 0 && !start_offset.has_dynamic()) {
    if (util::RoundSizeUp(GetSize().bits()) == GetSize().bits()) {
      std::string extract_type = util::GetTypeForSize(GetSize().bits());
      bool do_static_cast = (extract_type != GetDataType());

      s << "return ";
      if (do_static_cast) {
        s << "static_cast<" << GetDataType() << ">(";
      }
      s << "(begin() + " << (start_offset.bits() / 8) << ").extract<" << extract_type << ">()";
      if (do_static_cast) {
        s << ")";
      }
      s << ";}";
      return;
    }

    if (util::RoundSizeUp(GetSize().bits()) == 16 && GetSize().bits() == 12) {
      /* special handling for connection handle mask, there are over 220 instances of it */
      std::string extract_type = util::GetTypeForSize(GetSize().bits());
      s << "return (begin() + " << (start_offset.bits() / 8) << ").extract<" << extract_type
        << ">() & 0xfff;";
      s << "}";
      return;
    }

    if ((size_ % 8) == 0) {
      // Special case to address trailing scalar fields with an non standard number of bytes
      // (e.g. 24 bit value). If the endianess is little and the number of leading bits is
      // zero then the length is explicitly set.
      s << "return (begin() + " << (start_offset.bits() / 8) << ").extract<" << GetDataType()
        << ">(" << (size_ / 8) << ");";
      s << "}";
      return;
    }
  }

  s << "auto to_bound = begin();";
  int num_leading_bits = GenBounds(s, start_offset, end_offset, GetSize());
  s << GetDataType() << " " << GetName() << "_value{};";
  s << GetDataType() << "* " << GetName() << "_ptr = &" << GetName() << "_value;";
  GenExtractor(s, num_leading_bits, false);
  s << "return " << GetName() << "_value;";
  s << "}";
}

std::string ScalarField::GetBuilderParameterType() const { return GetDataType(); }

bool ScalarField::HasParameterValidator() const {
  return util::RoundSizeUp(GetSize().bits()) != GetSize().bits();
}

void ScalarField::GenParameterValidator(std::ostream& s) const {
  s << "ASSERT(" << GetName() << " < (static_cast<uint64_t>(1) << " << GetSize().bits() << "));";
}

void ScalarField::GenInserter(std::ostream& s) const {
  if (GetSize().bits() == 8) {
    s << "i.insert_byte(" << GetName() << "_);";
  } else {
    s << "insert(" << GetName() << "_, i," << GetSize().bits() << ");";
  }
}

void ScalarField::GenValidator(std::ostream&) const {
  // Do nothing
}

void ScalarField::GenStringRepresentation(std::ostream& s, std::string accessor) const {
  if (GetSize().bits() <= 8) {
    // otherwise uint8_t fields will be rendered as escaped characters
    s << "+";
  }
  s << accessor;
}
