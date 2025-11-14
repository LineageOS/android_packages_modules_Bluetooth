/*
 * Copyright 2025 The Android Open Source Project
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

#include "fuzzer_validation_test.h"

#include <bluetooth/log.h>
#include <gtest/gtest.h>

// NOLINTNEXTLINE(build/c++17) Prefer to use the C++17 lib rather than g3 lib.
#include <filesystem>
#include <fstream>

namespace fuzzer_validation_test {

namespace fs = std::filesystem;
namespace log = bluetooth::log;

using std::ifstream;
using std::ios;
using testing::ExitedWithCode;

namespace {
const size_t kMaxCommandLength = 1000;

// Allow referencing of fuzzer entrance function as-is.
extern "C" int LLVMFuzzerTestOneInput(const uint8_t *Data, size_t Size);

// TODO: b/280300628 for fixing memory leaks. Until it's fixed leak
// detection needs to be turned off to unblock fuzzing.
// NOLINTNEXTLINE(bugprone-reserved-identifier)
extern "C" const char *__asan_default_options() { return "detect_leaks=0"; }

void runFuzzerOnCorpusAndExitProcess() {
  int i = 0;
  for (const auto &corpus_entry : fs::directory_iterator("./data/corpus")) {
    if (!corpus_entry.is_regular_file()) {
      continue;
    }
    const fs::path &path = corpus_entry.path();
    log::info("Running fuzzer with {}, i={}", path.string(), i);
    ifstream corpus(path, ios::in | ios::binary | ios::ate);
    std::streampos size = corpus.tellg();
    char *data = new char[size];
    corpus.seekg(0, ios::beg);
    corpus.read(data, size);
    corpus.close();
    LLVMFuzzerTestOneInput(reinterpret_cast<const uint8_t *>(data), size);
    delete[] data;
  }
  exit(0);
}
}  // namespace

void TestEnvironment::SetUp() {
  system("rm -rf ./data/corpus");
  char command[kMaxCommandLength];
  snprintf(command, kMaxCommandLength, "unzip -o ./data/%s.zip -d ./data/corpus", corpus_name);
  int result = system(command);
  if (result != 0) {
    FAIL() << "Failed to unzip the corpus.";
  }
}

void TestEnvironment::TearDown() { system("rm -rf ./data/corpus"); }

void runValidationTest() {
  EXPECT_EXIT(runFuzzerOnCorpusAndExitProcess(), ExitedWithCode(0), ".*")
          << "Check system/stack/fuzzers/test/README.md for debugging instructions.";
}

}  // namespace fuzzer_validation_test
