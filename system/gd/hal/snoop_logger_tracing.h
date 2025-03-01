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

#pragma once
#ifdef __ANDROID__

#include <perfetto/trace/android/bluetooth_trace.pbzero.h>
#include <perfetto/tracing.h>

#include "hal/snoop_logger.h"

namespace bluetooth {
namespace hal {

// BundleKey is used to group packets into bundles organized by direction, type,
// and eventual op_code or event_code
struct BundleKey {
  explicit BundleKey(const HciPacket& packet, SnoopLogger::Direction direction,
                     SnoopLogger::PacketType type);

  SnoopLogger::PacketType packet_type;
  SnoopLogger::Direction direction;
  std::optional<uint16_t> op_code;
  std::optional<uint8_t> event_code;
  std::optional<uint8_t> subevent_code;
  std::optional<uint16_t> handle;

  bool operator==(const BundleKey& b) const;
};

// BundleHash is used to hash BundleKeys.
struct BundleHash {
  std::size_t operator()(const BundleKey& a) const;
};

// BundleDetails contains information about a bundle.
struct BundleDetails {
  uint32_t count = 0;
  uint32_t total_length = 0;
  uint64_t start_ts = std::numeric_limits<uint64_t>::max();
  uint64_t end_ts = std::numeric_limits<uint64_t>::min();
};

class SnoopLoggerTracing : public perfetto::DataSource<SnoopLoggerTracing> {
public:
  static void InitializePerfetto();
  static void TracePacket(uint64_t timestamp_us, const HciPacket& packet,
                          SnoopLogger::Direction direction, SnoopLogger::PacketType type);

  void OnSetup(const SetupArgs&) override;
  void OnStart(const StartArgs&) override;
  void OnStop(const StopArgs&) override;
  void OnFlush(const FlushArgs&) override;

private:
  static perfetto::protos::pbzero::BluetoothTracePacketType HciToTracePacketType(
          SnoopLogger::PacketType hci_packet_type, SnoopLogger::Direction direction);

  // Records the packet into the internal buffers.
  void Record(TraceContext& ctx, uint64_t timestamp_us, const HciPacket& packet,
              SnoopLogger::Direction direction, SnoopLogger::PacketType type);

  // Writes all pending data from the internal buffer as a new trace packet.
  void Write(TraceContext& ctx, const BundleKey& key, const BundleDetails& details);

  uint64_t last_flush_us_ = 0;
  std::unordered_map<BundleKey, BundleDetails, BundleHash> bttrace_bundles_;
};
}  // namespace hal
}  // namespace bluetooth

PERFETTO_DECLARE_DATA_SOURCE_STATIC_MEMBERS(bluetooth::hal::SnoopLoggerTracing);

#endif  // __ANDROID__
