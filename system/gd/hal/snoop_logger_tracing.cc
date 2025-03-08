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

#ifdef __ANDROID__
#include "hal/snoop_logger_tracing.h"

#include <bluetooth/log.h>
#include <perfetto/trace/android/bluetooth_trace.pbzero.h>
#include <perfetto/tracing.h>

#include "hal/snoop_logger.h"
#include "hci/hci_packets.h"

PERFETTO_DEFINE_DATA_SOURCE_STATIC_MEMBERS(bluetooth::hal::SnoopLoggerTracing);

using perfetto::protos::pbzero::BluetoothTracePacketType;

namespace bluetooth {
namespace hal {
namespace {

// The Perfetto trace flush interval in nanoseconds.
constexpr uint64_t TRACE_FLUSH_INTERVAL_NANOS = 100 * 1000 * 1000;

static bool SkipTracePoint(const HciPacket& packet, SnoopLogger::PacketType type) {
  if (type == SnoopLogger::PacketType::EVT) {
    uint8_t evt_code = packet[0];

    // Below set of commands does not provide further insight into bluetooth
    // behavior. Skip these to save bluetooth tracing from becoming too large.
    return evt_code == static_cast<uint8_t>(hci::EventCode::NUMBER_OF_COMPLETED_PACKETS) ||
           evt_code == static_cast<uint8_t>(hci::EventCode::COMMAND_COMPLETE) ||
           evt_code == static_cast<uint8_t>(hci::EventCode::COMMAND_STATUS);
  }

  return false;
}
}  // namespace

BundleKey::BundleKey(const HciPacket& packet, SnoopLogger::Direction direction,
                     SnoopLogger::PacketType type)
    : packet_type(type), direction(direction) {
  switch (type) {
    case SnoopLogger::PacketType::EVT: {
      event_code = packet[0];

      if (event_code == static_cast<uint8_t>(hci::EventCode::LE_META_EVENT) ||
          event_code == static_cast<uint8_t>(hci::EventCode::VENDOR_SPECIFIC)) {
        subevent_code = packet[2];
      }
    } break;
    case SnoopLogger::PacketType::CMD: {
      op_code = packet[0] | (packet[1] << 8);
    } break;
    case SnoopLogger::PacketType::ACL:
    case SnoopLogger::PacketType::ISO:
    case SnoopLogger::PacketType::SCO: {
      handle = (packet[0] | (packet[1] << 8)) & 0x0fff;
    } break;
  }
}

#define AGG_FIELDS(x) \
  (x).packet_type, (x).direction, (x).event_code, (x).subevent_code, (x).op_code, (x).handle

bool BundleKey::operator==(const BundleKey& b) const {
  return std::tie(AGG_FIELDS(*this)) == std::tie(AGG_FIELDS(b));
}

template <typename T, typename... Rest>
void HashCombine(std::size_t& seed, const T& val, const Rest&... rest) {
  seed ^= std::hash<T>()(val) + 0x9e3779b9 + (seed << 6) + (seed >> 2);
  (HashCombine(seed, rest), ...);
}

std::size_t BundleHash::operator()(const BundleKey& a) const {
  std::size_t seed = 0;
  HashCombine(seed, AGG_FIELDS(a));
  return seed;
}

#undef AGG_FIELDS

void SnoopLoggerTracing::InitializePerfetto() {
  perfetto::TracingInitArgs args;
  args.backends |= perfetto::kSystemBackend;

  perfetto::Tracing::Initialize(args);
  perfetto::DataSourceDescriptor dsd;
  dsd.set_name("android.bluetooth_tracing");
  SnoopLoggerTracing::Register(dsd);
}

BluetoothTracePacketType SnoopLoggerTracing::HciToTracePacketType(
        SnoopLogger::PacketType hci_packet_type, SnoopLogger::Direction direction) {
  BluetoothTracePacketType trace_packet_type;
  switch (hci_packet_type) {
    case SnoopLogger::PacketType::CMD: {
      trace_packet_type = BluetoothTracePacketType::HCI_CMD;
    } break;
    case SnoopLogger::PacketType::EVT: {
      trace_packet_type = BluetoothTracePacketType::HCI_EVT;
    } break;
    case SnoopLogger::PacketType::ACL: {
      if (direction == SnoopLogger::INCOMING) {
        trace_packet_type = BluetoothTracePacketType::HCI_ACL_RX;
      } else {
        trace_packet_type = BluetoothTracePacketType::HCI_ACL_TX;
      }
    } break;
    case SnoopLogger::PacketType::ISO: {
      if (direction == SnoopLogger::INCOMING) {
        trace_packet_type = BluetoothTracePacketType::HCI_ISO_RX;
      } else {
        trace_packet_type = BluetoothTracePacketType::HCI_ISO_TX;
      }
    } break;
    case SnoopLogger::PacketType::SCO: {
      if (direction == SnoopLogger::INCOMING) {
        trace_packet_type = BluetoothTracePacketType::HCI_SCO_RX;
      } else {
        trace_packet_type = BluetoothTracePacketType::HCI_SCO_TX;
      }
    } break;
  }
  return trace_packet_type;
}

void SnoopLoggerTracing::TracePacket(const HciPacket& packet, SnoopLogger::Direction direction,
                                     SnoopLogger::PacketType type) {
  if (SkipTracePoint(packet, type)) {
    return;
  }

  SnoopLoggerTracing::Trace([&](SnoopLoggerTracing::TraceContext ctx) {
    perfetto::LockedHandle<SnoopLoggerTracing> handle = ctx.GetDataSourceLocked();
    if (handle.valid()) {
      handle->Record(ctx, packet, direction, type);
    }
  });
}

void SnoopLoggerTracing::Record(TraceContext& ctx, const HciPacket& packet,
                                SnoopLogger::Direction direction, SnoopLogger::PacketType type) {
  // Write pending events before saving the new one to the bundle. Not doing this
  // includes the new event after a potentially long gap, leading to a bundle with
  // a very long duration.
  uint64_t timestamp_ns = perfetto::base::GetBootTimeNs().count();
  if (last_flush_ns_ + TRACE_FLUSH_INTERVAL_NANOS < timestamp_ns) {
    for (const auto& [key, details] : bttrace_bundles_) {
      Write(ctx, key, details);
    }

    bttrace_bundles_.clear();
    last_flush_ns_ = timestamp_ns;
  }

  BundleKey key(packet, direction, type);

  BundleDetails& bundle = bttrace_bundles_[key];
  bundle.count++;
  bundle.total_length += packet.size();
  bundle.start_ts = std::min(bundle.start_ts, timestamp_ns);
  bundle.end_ts = std::max(bundle.end_ts, timestamp_ns);
}

void SnoopLoggerTracing::Write(TraceContext& ctx, const BundleKey& key,
                               const BundleDetails& details) {
  auto trace_pkt = ctx.NewTracePacket();
  trace_pkt->set_timestamp(details.start_ts);
  auto* bt_event = trace_pkt->set_bluetooth_trace_event();
  bt_event->set_packet_type(HciToTracePacketType(key.packet_type, key.direction));
  bt_event->set_count(details.count);
  bt_event->set_length(details.total_length);
  bt_event->set_duration(details.end_ts - details.start_ts);
  if (key.op_code.has_value()) {
    bt_event->set_op_code(*key.op_code);
  }
  if (key.event_code.has_value()) {
    bt_event->set_event_code(*key.event_code);
  }
  if (key.subevent_code.has_value()) {
    bt_event->set_subevent_code(*key.subevent_code);
  }
  if (key.handle.has_value()) {
    bt_event->set_connection_handle(*key.handle);
  }
}

void SnoopLoggerTracing::OnSetup(const SetupArgs&) {}
void SnoopLoggerTracing::OnStart(const StartArgs&) {}
void SnoopLoggerTracing::OnStop(const StopArgs&) {}
void SnoopLoggerTracing::OnFlush(const FlushArgs&) {}

}  // namespace hal
}  // namespace bluetooth
#endif  // __ANDROID__
