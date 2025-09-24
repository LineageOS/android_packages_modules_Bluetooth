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

#include "storage/mutation.h"

namespace bluetooth {
namespace storage {

Mutation::Mutation(ConfigCache* config) : config_(config) {
  log::assert_that(config_ != nullptr, "assert failed: config_ != nullptr");
}

void Mutation::Add(MutationEntry entry) { normal_config_entries_.emplace(std::move(entry)); }

void Mutation::Commit() { config_->Commit(normal_config_entries_); }

}  // namespace storage
}  // namespace bluetooth
