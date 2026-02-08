/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <bluetooth/log.h>

#include <algorithm>
#include <chrono>
#include <format>
#include <iomanip>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

#include "common/time_util.h"
#include "log/src/truncating_buffer.h"

namespace bluetooth {

static constexpr size_t kLogBufferSize = 256;
static constexpr size_t kLineBufferSize = 256;

struct LeAudioEventTracker {
private:
  auto get_events() {
    // Repack the ring buffer to restore the order of events
    auto const log_size = is_full_ ? buffer_.size() : next_evt_idx_;
    decltype(buffer_) ordered_buffer;
    ordered_buffer.reserve(log_size);

    if (is_full_) {
      std::copy(buffer_.begin() + next_evt_idx_, buffer_.end(), std::back_inserter(ordered_buffer));
    }
    std::copy(buffer_.begin(), buffer_.begin() + next_evt_idx_, std::back_inserter(ordered_buffer));

    return ordered_buffer;
  }

public:
  enum class EventType : char {
    START = '>',
    END = '<',
    POINT = '*',
    ERROR = '!',
    CALLBACK = '^',
    SUBEVENT = '-',
    TRANSITION = '~',
  };

  static inline std::string EventTypeToString(const EventType evt) {
    switch (evt) {
      case EventType::START:
        return " > ";
      case EventType::END:
        return " < ";
      case EventType::POINT:
        return " • ";
      case EventType::TRANSITION:
        return " ->";
      case EventType::ERROR:
        return " ! ";
      case EventType::CALLBACK:
        return " ✉→";
      case EventType::SUBEVENT:
        return " ◦ ";
      default:
        return " ? ";
    };
  }

  LeAudioEventTracker(size_t log_capacity = kLogBufferSize) { buffer_.resize(log_capacity); }

  template <typename... T>
  void OnEvent(const char* tag, EventType event_type, std::format_string<T...> fmt, T&&... args) {
    std::lock_guard<std::mutex> lock(event_mutex_);
    log_internal::truncating_buffer<kLineBufferSize> buffer;

    std::vformat_to(std::back_insert_iterator(buffer), fmt.get(),
                    std::make_format_args(log_internal::format_replace(args)...));

    auto ts = std::chrono::system_clock::now();
    buffer_[next_evt_idx_] = {ts, std::string(tag), event_type, buffer.c_str()};

    next_evt_idx_ = (next_evt_idx_ + 1) % buffer_.size();
    if (!is_full_ && next_evt_idx_ == 0) {
      is_full_ = true;
    }
  }

  // Defines the display order for components in the event tracker dumpsys
  static const std::vector<std::string_view> kOrderedCoreTags;

  void Dump(std::stringstream& stream) {
    std::lock_guard<std::mutex> lock(event_mutex_);

    // clang-format off
    stream << " -- [ LE Audio Event Log ] --------------------------------------------------------------------------\n";
    stream << " |    > - start,  < - end,  • - point,  -> - transition,  ◦ - subevent,  ✉→ - callback, ! - error   |\n";
    stream << " ----------------------------------------------------------------------------------------------------\n";
    // clang-format on

    auto ordered_events = get_events();

    // 1. Discover all unique tags from the events
    std::set<std::string_view> all_tags;
    for (const auto& event : ordered_events) {
      all_tags.insert(std::get<1>(event));
    }

    // 2. Build the final, ordered list of columns
    std::vector<std::string_view> final_columns = kOrderedCoreTags;
    std::vector<std::string_view> unordered_tags;

    for (const auto& tag : all_tags) {
      if (std::find(final_columns.begin(), final_columns.end(), tag) == final_columns.end()) {
        unordered_tags.push_back(tag);
      }
    }
    std::sort(unordered_tags.begin(), unordered_tags.end());
    final_columns.insert(final_columns.end(), unordered_tags.begin(), unordered_tags.end());

    // 3. Print headers and the bar structure
    std::string indent = " ";
    for (const auto& tag : final_columns) {
      stream << indent;
      if (std::find(unordered_tags.begin(), unordered_tags.end(), tag) != unordered_tags.end()) {
        stream << "*";
      } else {
        stream << " ";
      }
      stream << tag << "\n";
      indent += " | ";
    }
    stream << indent << "\n";

    // 4. Print events
    for (auto it = ordered_events.rbegin(); it != ordered_events.rend(); ++it) {
      auto const& [ts, tag, evt, msg] = *it;
      stream << " ";

      // Print the event marker in the correct column
      for (const auto& col_tag : final_columns) {
        if (tag == col_tag) {
          stream << EventTypeToString(evt);
        } else {
          stream << " ' ";
        }
      }

      // Print the timestamp and message
      auto now_time = std::chrono::system_clock::to_time_t(ts);
      auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(ts.time_since_epoch()) % 1000;
      struct tm time_info;
      stream << " " << std::put_time(localtime_r(&now_time, &time_info), "%m-%d %H:%M:%S") << '.'
             << std::dec << std::setfill('0') << std::setw(3) << ms.count() << " " << msg << "\n";
    }

    // clang-format off
    stream << " ---------------------------------------------------------------------------------------------------\n";
    stream << " |  > - start,  < - end,  • - point,  -> - transition,  ◦ - subevent,  ✉→ - callback, ! - error    |\n";
    stream << " ---------------------------------------------------------------------------------------------------\n";
    // clang-format on
  }

  static std::shared_ptr<LeAudioEventTracker>& GetLeAudioSinkInstance();

private:
  std::vector<
          std::tuple<std::chrono::system_clock::time_point, std::string, EventType, std::string>>
          buffer_;
  size_t next_evt_idx_ = 0;
  bool is_full_ = false;
  std::mutex event_mutex_;
};

}  // namespace bluetooth
