/*
 * Copyright 2020 The Android Open Source Project
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

#include "dumpsys/filter.h"

#include <bluetooth/log.h>

#include <memory>

using namespace bluetooth;
using namespace dumpsys;

class Filter {
 public:
  Filter(const dumpsys::ReflectionSchema& reflection_schema) : reflection_schema_(reflection_schema) {}

  virtual ~Filter() = default;

  virtual void FilterInPlace(char* dumpsys_data) = 0;

  static std::unique_ptr<Filter> Factory(const dumpsys::ReflectionSchema& reflection_schema);

 protected:
  /**
   * Given both reflection field data and the populated flatbuffer table data, if any,
   * filter the contents of the field based upon the filtering privacy level.
   *
   * Primitives and composite strings may be successfully processed at this point.
   * Other composite types (e.g. structs or tables) must be expanded into the
   * respective grouping of subfields.
   *
   * @param field The reflection field information from the bundled schema
   * @param table The populated field data, if any
   *
   * @return true if field was filtered successfully, false otherwise.
   */
  virtual bool FilterField(const reflection::Field* /* field */, flatbuffers::Table* /* table */) {
    return false;
  }

  /**
   * Given both reflection object data and the populated flatbuffer table data, if any,
   * filter the object fields based upon the filtering privacy level.
   *
   * @param object The reflection object information from the bundled schema
   * @param table The populated field data, if any
   *
   */
  virtual void FilterObject(
      const reflection::Object* /* object */, flatbuffers::Table* /* table */){};

  /**
   * Given both reflection field data and the populated table data, if any,
   * filter the contents of the table based upon the filtering privacy level.
   *
   * @param schema The reflection schema information from the bundled schema
   * @param table The populated field data, if any
   *
   */
  virtual void FilterTable(
      const reflection::Schema* /* schema */, flatbuffers::Table* /* table */){};

  const dumpsys::ReflectionSchema& reflection_schema_;
};

class DeveloperPrivacyFilter : public Filter {
 public:
  DeveloperPrivacyFilter(const dumpsys::ReflectionSchema& reflection_schema) : Filter(reflection_schema) {}
  void FilterInPlace(char* /* dumpsys_data */) override { /* Nothing to do in this mode */
  }
};

std::unique_ptr<Filter> Filter::Factory(const dumpsys::ReflectionSchema& reflection_schema) {
  return std::make_unique<DeveloperPrivacyFilter>(reflection_schema);
}

void bluetooth::dumpsys::FilterSchema(
    const ReflectionSchema& reflection_schema, std::string* dumpsys_data) {
  auto filter = Filter::Factory(reflection_schema);
  filter->FilterInPlace(dumpsys_data->data());
}
