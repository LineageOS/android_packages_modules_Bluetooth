/*
 * Copyright 2023 The Android Open Source Project
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

#include <syslog.h>

#include "bluetooth/log.h"
#include "truncating_buffer.h"

namespace bluetooth::log_internal {

// Default value for $MaxMessageSize for rsyslog.
static constexpr size_t kBufferSize = 8192;

void vlog(Level level, char const* tag, char const* file_name, int line,
          char const* function_name, fmt::string_view fmt,
          fmt::format_args vargs) {
  // Convert the level to syslog severity.
  int severity = LOG_DEBUG;
  switch (level) {
    case Level::kVerbose:
    case Level::kDebug:
    default:
      severity = LOG_DEBUG;
      break;
    case Level::kInfo:
      severity = LOG_INFO;
      break;
    case Level::kWarn:
      severity = LOG_WARNING;
      break;
    case Level::kError:
      severity = LOG_ERR;
      break;
    case Level::kFatal:
      severity = LOG_CRIT;
      break;
  }

  // Prepare bounded stack buffer.
  truncating_buffer<kBufferSize> buffer;

  // Format file, line.
  fmt::format_to(std::back_insert_iterator(buffer), "{} {}:{} {}: ", tag,
                 file_name, line, function_name);

  // Format message.
  fmt::vformat_to(std::back_insert_iterator(buffer), fmt, vargs);

  // Print to vsyslog.
  syslog(LOG_USER | severity, "%s", buffer.c_str());
}

}  // namespace bluetooth::log_internal
