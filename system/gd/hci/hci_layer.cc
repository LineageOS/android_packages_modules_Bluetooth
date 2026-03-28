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

#include "hci/hci_layer.h"

#ifdef TARGET_FLOSS
#include <signal.h>
#endif
#include <bluetooth/log.h>
#include <bluetooth/metrics/os_metrics.h>

#include <map>
#include <utility>
#include <vector>

#include "com_android_bluetooth_flags.h"
#include "common/bind.h"
#include "common/stop_watch.h"
#include "hal/hci_hal.h"
#include "hci/class_of_device.h"
#include "hci/hci_data_router.h"
#include "hci/hci_metrics_logging.h"
#include "hci/inquiry_interface.h"
#include "os/alarm.h"
#include "os/queue.h"
#include "os/system_properties.h"
#include "packet/raw_builder.h"
#include "storage/storage_module.h"

namespace bluetooth {
namespace hci {
using bluetooth::common::BindOnce;
using bluetooth::common::ContextualCallback;
using bluetooth::common::ContextualOnceCallback;
using bluetooth::hci::CommandBuilder;
using bluetooth::hci::CommandCompleteView;
using bluetooth::hci::CommandStatusOrCompleteView;
using bluetooth::hci::CommandStatusView;
using bluetooth::hci::EventView;
using bluetooth::hci::LeMetaEventView;
using bluetooth::os::Handler;
using common::BidiQueue;
using common::BidiQueueEnd;
using hci::OpCode;
using hci::ResetCompleteView;
using os::Alarm;
using os::Handler;
using std::unique_ptr;

static std::recursive_mutex life_cycle_guard;
static bool life_cycle_stopped = true;

#ifdef TARGET_FLOSS
// Signal to indicate the controller needs to be reset.
const int SIG_RESET_CTRL = SIGUSR1;
#endif

static std::chrono::milliseconds getHciTimeoutMs() {
  static auto sHciTimeoutMs = std::chrono::milliseconds(bluetooth::os::GetSystemPropertyUint32Base(
          "bluetooth.hci.timeout_milliseconds", HciLayer::kHciTimeoutMs.count()));
  return sHciTimeoutMs;
}

static std::chrono::milliseconds getHciTimeoutRestartMs() {
  static auto sRestartHciTimeoutMs = std::chrono::milliseconds(
          bluetooth::os::GetSystemPropertyUint32Base("bluetooth.hci.restart_timeout_milliseconds",
                                                     HciLayer::kHciTimeoutRestartMs.count()));
  return sRestartHciTimeoutMs;
}

static void fail_if_reset_complete_not_success(CommandCompleteView complete) {
  auto reset_complete = ResetCompleteView::Create(complete);
  log::assert_that(reset_complete.IsValid(), "assert failed: reset_complete.IsValid()");
  log::assert_that(reset_complete.GetStatus() == ErrorCode::SUCCESS,
                   "assert failed: reset_complete.GetStatus() == ErrorCode::SUCCESS");

  log::info("HciLayer::reset is completed");
}

static void abort_after_time_out(OpCode op_code) {
  log::fatal("Done waiting for debug information after HCI timeout ({}) for {}ms",
             OpCodeText(op_code), getHciTimeoutRestartMs().count());
}

class CommandQueueEntry {
public:
  enum class WaitingFor { STATUS, COMPLETE, STATUS_OR_COMPLETE };

  CommandQueueEntry(unique_ptr<CommandBuilder> command_packet,
                    ContextualOnceCallback<void(CommandCompleteView)> on_complete_function)
      : command(std::move(command_packet)),
        waiting_for_(WaitingFor::COMPLETE),
        on_complete(std::move(on_complete_function)) {}

  CommandQueueEntry(unique_ptr<CommandBuilder> command_packet,
                    ContextualOnceCallback<void(CommandStatusView)> on_status_function)
      : command(std::move(command_packet)),
        waiting_for_(WaitingFor::STATUS),
        on_status(std::move(on_status_function)) {}

  CommandQueueEntry(
          unique_ptr<CommandBuilder> command_packet,
          ContextualOnceCallback<void(CommandStatusOrCompleteView)> on_status_or_complete_function)
      : command(std::move(command_packet)),
        waiting_for_(WaitingFor::STATUS_OR_COMPLETE),
        on_status_or_complete(std::move(on_status_or_complete_function)) {}

  unique_ptr<CommandBuilder> command;
  unique_ptr<CommandView> command_view;

  WaitingFor waiting_for_;
  ContextualOnceCallback<void(CommandStatusView)> on_status;
  ContextualOnceCallback<void(CommandCompleteView)> on_complete;
  ContextualOnceCallback<void(CommandStatusOrCompleteView)> on_status_or_complete;

  template <typename TView>
  ContextualOnceCallback<void(TView)>* GetCallback() {
    return nullptr;
  }

  template <>
  ContextualOnceCallback<void(CommandStatusView)>* GetCallback<CommandStatusView>() {
    return &on_status;
  }

  template <>
  ContextualOnceCallback<void(CommandCompleteView)>* GetCallback<CommandCompleteView>() {
    return &on_complete;
  }

  template <>
  ContextualOnceCallback<void(CommandStatusOrCompleteView)>*
  GetCallback<CommandStatusOrCompleteView>() {
    return &on_status_or_complete;
  }
};

struct HciLayer::impl {
  impl(os::Handler* handler, hal::HciHal* hal, storage::StorageModule* storage, HciLayer& module)
      : hal_(hal), storage_(storage), module_(module), router_(handler, acl_queue_.GetUpEnd()) {
    handler_ = handler;
    hci_timeout_alarm_ = new Alarm(&handler->thread());
  }

  ~impl() {
    handler_ = nullptr;
    incoming_acl_buffer_.Clear();
    incoming_sco_buffer_.Clear();
    incoming_iso_buffer_.Clear();
    if (hci_timeout_alarm_ != nullptr) {
      delete hci_timeout_alarm_;
    }
    if (hci_abort_alarm_ != nullptr) {
      delete hci_abort_alarm_;
    }
    command_queue_.clear();
  }

  void SetLeAclDataConsumer(LeAclDataConsumer* le_acl_data_consumer) {
    router_.SetLeAclDataConsumer(le_acl_data_consumer);
  }
  void SetClassicAclDataConsumer(ClassicAclDataConsumer* classic_acl_data_consumer) {
    router_.SetClassicAclDataConsumer(classic_acl_data_consumer);
  }

  void drop(EventView event) {
    log::info("Dropping event {}", EventCodeText(event.GetEventCode()));
  }

  void on_outbound_acl_ready() {
    auto packet = acl_queue_.GetDownEnd()->TryDequeue();
    std::vector<uint8_t> bytes;
    BitInserter bi(bytes);
    packet->Serialize(bi);
    hal_->sendAclData(bytes);
  }

  void on_outbound_sco_ready() {
    auto packet = sco_queue_.GetDownEnd()->TryDequeue();
    std::vector<uint8_t> bytes;
    BitInserter bi(bytes);
    packet->Serialize(bi);
    hal_->sendScoData(bytes);
  }

  void on_outbound_iso_ready() {
    auto packet = iso_queue_.GetDownEnd()->TryDequeue();
    std::vector<uint8_t> bytes;
    BitInserter bi(bytes);
    packet->Serialize(bi);
    hal_->sendIsoData(bytes);
  }

  template <typename TResponse>
  void enqueue_command(unique_ptr<CommandBuilder> command,
                       ContextualOnceCallback<void(TResponse)> on_response) {
    command_queue_.emplace_back(std::move(command), std::move(on_response));
    send_next_command();
  }

  void on_command_status(EventView event) {
    CommandStatusView response_view = CommandStatusView::Create(event);
    log::assert_that(response_view.IsValid(), "assert failed: response_view.IsValid()");
    OpCode op_code = response_view.GetCommandOpCode();
    ErrorCode status = response_view.GetStatus();
    if (status != ErrorCode::SUCCESS) {
      log::warn("Received UNEXPECTED command status:{} opcode:{}", ErrorCodeText(status),
                OpCodeText(op_code));
    }
    handle_command_response<CommandStatusView>(event, "status");
  }

  void on_command_complete(EventView event) {
    handle_command_response<CommandCompleteView>(event, "complete");
  }

  template <typename TResponse>
  void handle_command_response(EventView event, std::string logging_id) {
    TResponse response_view = TResponse::Create(event);
    log::assert_that(response_view.IsValid(), "assert failed: response_view.IsValid()");
    command_credits_ = response_view.GetNumHciCommandPackets();
    OpCode op_code = response_view.GetCommandOpCode();
    if (handler_ == nullptr) {
      log::warn("Ignoring command response {} for opcode {} because stack has been shutdown",
                EventCodeText(event.GetEventCode()), OpCodeText(op_code));
      return;
    }

    if (op_code == OpCode::NONE) {
      send_next_command();
      return;
    }
    bool is_status = logging_id == "status";

    log::assert_that(!command_queue_.empty(), "Unexpected {} event with OpCode {}", logging_id,
                     OpCodeText(op_code));
    if (waiting_command_ == OpCode::CONTROLLER_DEBUG_INFO &&
        op_code != OpCode::CONTROLLER_DEBUG_INFO) {
      log::error("Discarding event that came after timeout {}", OpCodeText(op_code));
      common::StopWatch::DumpStopWatchLog();
      return;
    }
    log::assert_that(waiting_command_ == op_code, "Waiting for {}, got {}",
                     OpCodeText(waiting_command_), OpCodeText(op_code));

    bool is_vendor_specific = (static_cast<int>(op_code) & (0x3f << 10)) == (0x3f << 10);
    using WaitingFor = CommandQueueEntry::WaitingFor;
    WaitingFor waiting_for = command_queue_.front().waiting_for_;
    CommandStatusView status_view = CommandStatusView::Create(event);
    if (is_vendor_specific && (is_status && waiting_for == WaitingFor::COMPLETE) &&
        (status_view.IsValid() && status_view.GetStatus() == ErrorCode::UNKNOWN_HCI_COMMAND)) {
      // If this is a command status of a vendor specific command, and command complete is expected,
      // we can't treat this as hard failure since we have no way of probing this lack of support at
      // earlier time. Instead we let the command complete handler handle a empty Command Complete
      // packet, which will be interpreted as invalid response.

      auto payload = std::make_unique<packet::RawBuilder>();
      payload->AddOctets1(static_cast<uint8_t>(status_view.GetStatus()));
      auto complete_event_builder =
              CommandCompleteBuilder::Create(status_view.GetNumHciCommandPackets(),
                                             status_view.GetCommandOpCode(), std::move(payload));
      auto complete = std::make_shared<std::vector<std::uint8_t>>(
              complete_event_builder->SerializeToBytes());
      CommandCompleteView command_complete_view =
              CommandCompleteView::Create(EventView::Create(PacketView<kLittleEndian>(complete)));
      log::assert_that(command_complete_view.IsValid(),
                       "assert failed: command_complete_view.IsValid()");
      (*command_queue_.front().GetCallback<CommandCompleteView>())(command_complete_view);
    } else if (waiting_for != WaitingFor::STATUS_OR_COMPLETE) {
      log::assert_that((waiting_for == WaitingFor::STATUS) == is_status,
                       "{} was not expecting {} event", OpCodeText(op_code), logging_id);

      (*command_queue_.front().GetCallback<TResponse>())(std::move(response_view));
    } else {
      (*command_queue_.front().GetCallback<CommandStatusOrCompleteView>())(
              std::move(response_view));
    }

    command_queue_.pop_front();
    waiting_command_ = OpCode::NONE;
    if (hci_timeout_alarm_ != nullptr) {
      hci_timeout_alarm_->Cancel();
      send_next_command();
    }
  }

  void on_hci_timeout(OpCode op_code) {
#ifdef TARGET_FLOSS
    std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
    if (life_cycle_stopped) {
      return;
    }

    log::warn("Ignoring the timeouted HCI command {}.", OpCodeText(op_code));

    // Terminate the process to trigger controller reset, also stop sending and
    // processing any incoming packet immediately to prevent further error
    // while terminating.
    module_.LifeCycleStop();
    kill(getpid(), SIG_RESET_CTRL);
    return;
#endif

    common::StopWatch::DumpStopWatchLog();
    log::error("Timed out waiting for {} for {}ms, which was armed at: {}", OpCodeText(op_code),
               getHciTimeoutMs().count(), hci_timeout_alarm_->GetArmedTime());

    bluetooth::metrics::LogMetricHciTimeoutEvent(static_cast<uint32_t>(op_code));

    log::error("Flushing #{} waiting commands", command_queue_.size());
    for (auto& command : command_queue_) {
      auto* cmd_view = command.command_view.get();
      if (cmd_view && cmd_view->IsValid()) {
        log::debug("Flushing command: opcode:{}, waiting for: {}",
                   OpCodeText(cmd_view->GetOpCode()), static_cast<int>(command.waiting_for_));
      } else {
        log::debug("Flushing command: Invalid command_view packet");
      }
    }

    // Clear any waiting commands (there is an abort coming anyway)
    command_queue_.clear();
    command_credits_ = 1;
    waiting_command_ = OpCode::NONE;

    // Ignore the response, since we don't know what might come back.
    enqueue_command(ControllerDebugInfoBuilder::Create(),
                    handler_->BindOnce([](CommandCompleteView) {}));
    // Don't time out for this one;
    if (hci_timeout_alarm_ != nullptr) {
      hci_timeout_alarm_->Cancel();
      delete hci_timeout_alarm_;
      hci_timeout_alarm_ = nullptr;
    }
    if (hci_abort_alarm_ == nullptr) {
      hci_abort_alarm_ = new Alarm(&handler_->thread());
      hci_abort_alarm_->Schedule(BindOnce(&abort_after_time_out, op_code),
                                 getHciTimeoutRestartMs());
    } else {
      log::warn("Unable to schedule abort timer");
    }
  }

  void send_next_command() {
    if (command_credits_ == 0) {
      return;
    }
    if (waiting_command_ != OpCode::NONE) {
      return;
    }
    if (command_queue_.size() == 0) {
      return;
    }
    std::shared_ptr<std::vector<uint8_t>> bytes = std::make_shared<std::vector<uint8_t>>();
    BitInserter bi(*bytes);
    command_queue_.front().command->Serialize(bi);
    hal_->sendHciCommand(*bytes);

    auto cmd_view = CommandView::Create(PacketView<kLittleEndian>(bytes));
    log::assert_that(cmd_view.IsValid(), "assert failed: cmd_view.IsValid()");
    OpCode op_code = cmd_view.GetOpCode();
    command_queue_.front().command_view = std::make_unique<CommandView>(std::move(cmd_view));
    log_link_layer_connection_command(command_queue_.front().command_view);
    log_classic_pairing_command_status(command_queue_.front().command_view,
                                       ErrorCode::STATUS_UNKNOWN);
    waiting_command_ = op_code;
    command_credits_ = 0;  // Only allow one outstanding command
    if (hci_timeout_alarm_ != nullptr) {
      hci_timeout_alarm_->Schedule(
              BindOnce(&impl::on_hci_timeout, common::Unretained(this), op_code),
              getHciTimeoutMs());
    } else {
      log::warn("{} sent without an hci-timeout timer", OpCodeText(op_code));
    }
  }

  void register_event(EventCode event, ContextualCallback<void(EventView)> handler) {
    log::assert_that(event != EventCode::LE_META_EVENT, "Can not register handler for {}",
                     EventCodeText(EventCode::LE_META_EVENT));
    // Allow GD Cert tests to register for CONNECTION_REQUEST
    if (event == EventCode::CONNECTION_REQUEST && !module_.on_acl_connection_request_) {
      log::info("Registering test for CONNECTION_REQUEST, since there's no ACL");
      event_handlers_.erase(event);
    }
    log::assert_that(event_handlers_.count(event) == 0, "Can not register a second handler for {}",
                     EventCodeText(event));
    event_handlers_[event] = handler;
  }

  void unregister_event(EventCode event) { event_handlers_.erase(event); }

  void register_le_event(SubeventCode event, ContextualCallback<void(LeMetaEventView)> handler) {
    log::assert_that(le_event_handlers_.count(event) == 0,
                     "Can not register a second handler for {}", SubeventCodeText(event));
    le_event_handlers_[event] = handler;
  }

  void unregister_le_event(SubeventCode event) {
    auto it = le_event_handlers_.find(event);
    if (it == le_event_handlers_.end()) {
      log::warn("Can not unregister a non-existent handler for {}", SubeventCodeText(event));
      return;
    }
    le_event_handlers_.erase(it);
  }

  void register_development_event(DevelopmentSubeventCode event,
                                  ContextualCallback<void(DevelopmentEventView)> handler) {
    log::assert_that(development_event_handlers_.count(event) == 0,
                     "Can not register a second handler for {}",
                     DevelopmentSubeventCodeText(event));
    development_event_handlers_[event] = handler;
  }

  void unregister_development_event(DevelopmentSubeventCode event) {
    auto it = development_event_handlers_.find(event);
    if (it == development_event_handlers_.end()) {
      log::warn("Can not unregister a non-existent handler for {}",
                DevelopmentSubeventCodeText(event));
      return;
    }
    development_event_handlers_.erase(it);
  }

  void register_vs_event(VseSubeventCode event,
                         ContextualCallback<void(VendorSpecificEventView)> handler) {
    log::assert_that(vs_event_handlers_.count(event) == 0,
                     "Can not register a second handler for {}", VseSubeventCodeText(event));
    vs_event_handlers_[event] = handler;
  }

  void unregister_vs_event(VseSubeventCode event) {
    auto it = vs_event_handlers_.find(event);
    if (it == vs_event_handlers_.end()) {
      log::warn("Can not unregister a non-existent handler for {}", VseSubeventCodeText(event));
      return;
    }
    vs_event_handlers_.erase(it);
  }

  void register_vs_event_default(ContextualCallback<void(VendorSpecificEventView)> handler) {
    vs_event_default_handler_ = std::move(handler);
  }

  void unregister_vs_event_default() { vs_event_default_handler_.reset(); }

  static void abort_after_root_inflammation(uint8_t vse_error) {
    log::fatal("Root inflammation with reason 0x{:02x}", vse_error);
  }

  void handle_root_inflammation(uint8_t vse_error_reason) {
    log::error("Received a Root Inflammation Event vendor reason 0x{:02x}, scheduling an abort",
               vse_error_reason);
    bluetooth::metrics::LogMetricBluetoothHalCrashReason(Address::kEmpty, 0, vse_error_reason);
    // Add Logging for crash reason
    if (hci_timeout_alarm_ != nullptr) {
      hci_timeout_alarm_->Cancel();
      delete hci_timeout_alarm_;
      hci_timeout_alarm_ = nullptr;
    }
    if (hci_abort_alarm_ == nullptr) {
      hci_abort_alarm_ = new Alarm(&handler_->thread());
      hci_abort_alarm_->Schedule(BindOnce(&abort_after_root_inflammation, vse_error_reason),
                                 getHciTimeoutRestartMs());
    } else {
      log::warn("Abort timer already scheduled");
    }
  }

  void on_hci_event(EventView event) {
    log::assert_that(event.IsValid(), "assert failed: event.IsValid()");
    if (command_queue_.empty()) {
      auto event_code = event.GetEventCode();
      // BT Core spec 5.2 (Volume 4, Part E section 4.4) allows anytime
      // COMMAND_COMPLETE and COMMAND_STATUS with opcode 0x0 for flow control
      if (event_code == EventCode::COMMAND_COMPLETE) {
        auto view = CommandCompleteView::Create(event);
        log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
        auto op_code = view.GetCommandOpCode();
        log::assert_that(op_code == OpCode::NONE,
                         "Received {} event with OpCode {} without a waiting command(is the HAL "
                         "sending commands, but not handling the events?)",
                         EventCodeText(event_code), OpCodeText(op_code));
      }
      if (event_code == EventCode::COMMAND_STATUS) {
        auto view = CommandStatusView::Create(event);
        log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
        auto op_code = view.GetCommandOpCode();
        log::assert_that(op_code == OpCode::NONE,
                         "Received {} event with OpCode {} without a waiting command(is the HAL "
                         "sending commands, but not handling the events?)",
                         EventCodeText(event_code), OpCodeText(op_code));
      }
      std::unique_ptr<CommandView> no_waiting_command{nullptr};
      log_hci_event(no_waiting_command, event, storage_);
    } else {
      log_hci_event(command_queue_.front().command_view, event, storage_);
    }
    EventCode event_code = event.GetEventCode();
    // Root Inflammation is a special case, since it aborts here
    if (event_code == EventCode::VENDOR_SPECIFIC) {
      auto view = VendorSpecificEventView::Create(event);
      log::assert_that(view.IsValid(), "assert failed: view.IsValid()");
      if (view.GetSubeventCode() == VseSubeventCode::BQR_EVENT) {
        auto bqr_event = BqrEventView::Create(view);
        auto inflammation = BqrRootInflammationEventView::Create(bqr_event);
        if (bqr_event.IsValid() && inflammation.IsValid()) {
          handle_root_inflammation(inflammation.GetVendorSpecificErrorCode());
          return;
        }
      }
    }
    switch (event_code) {
      case EventCode::COMMAND_COMPLETE:
        on_command_complete(event);
        break;
      case EventCode::COMMAND_STATUS:
        on_command_status(event);
        break;
      case EventCode::LE_META_EVENT:
        on_le_meta_event(event);
        break;
      case EventCode::HARDWARE_ERROR:
        on_hardware_error(event);
        break;
      case EventCode::DEVELOPMENT:
        on_development_event(event);
        break;
      case EventCode::VENDOR_SPECIFIC:
        on_vs_event(event);
        break;
      default:
        if (event_handlers_.find(event_code) == event_handlers_.end()) {
          log::warn("Unhandled event of type {}", EventCodeText(event_code));
        } else {
          event_handlers_[event_code](event);
        }
    }
  }

  void on_hardware_error(EventView event) {
    HardwareErrorView event_view = HardwareErrorView::Create(event);
    log::assert_that(event_view.IsValid(), "assert failed: event_view.IsValid()");
#ifdef TARGET_FLOSS
    log::warn("Hardware Error Event with code 0x{:02x}", event_view.GetHardwareCode());
    // Sending signal to indicate BT controller needs to reset.
    // The Floss daemon will be restarted. HCI reset during restart will clear the
    // error state of the BT controller.
    module_.LifeCycleStop();
    kill(getpid(), SIG_RESET_CTRL);
#else
    log::fatal("Hardware Error Event with code 0x{:02x}", event_view.GetHardwareCode());
#endif
  }

  void on_le_meta_event(EventView event) {
    LeMetaEventView meta_event_view = LeMetaEventView::Create(event);
    log::assert_that(meta_event_view.IsValid(), "assert failed: meta_event_view.IsValid()");
    SubeventCode subevent_code = meta_event_view.GetSubeventCode();
    if (le_event_handlers_.find(subevent_code) == le_event_handlers_.end()) {
      log::warn("Unhandled le subevent of type {}", SubeventCodeText(subevent_code));
      return;
    }
    le_event_handlers_[subevent_code](meta_event_view);
  }

  void on_development_event(EventView event) {
    DevelopmentEventView development_event_view = DevelopmentEventView::Create(event);
    log::assert_that(development_event_view.IsValid(),
                     "assert failed: development_event_view.IsValid()");
    DevelopmentSubeventCode subevent_code = development_event_view.GetSubeventCode();
    if (development_event_handlers_.find(subevent_code) == development_event_handlers_.end()) {
      log::warn("Unhandled development event of type {}",
                DevelopmentSubeventCodeText(subevent_code));
      return;
    }
    development_event_handlers_[subevent_code](development_event_view);
  }

  void on_vs_event(EventView event) {
    VendorSpecificEventView vs_event_view = VendorSpecificEventView::Create(event);
    log::assert_that(vs_event_view.IsValid(), "assert failed: vs_event_view.IsValid()");
    VseSubeventCode subevent_code = vs_event_view.GetSubeventCode();
    if (vs_event_handlers_.find(subevent_code) != vs_event_handlers_.end()) {
      vs_event_handlers_[subevent_code](vs_event_view);
    } else if (vs_event_default_handler_.has_value()) {
      (*vs_event_default_handler_)(vs_event_view);
    } else {
      log::warn("Unhandled vendor specific event of type {}", VseSubeventCodeText(subevent_code));
    }
  }

  os::Handler* handler_;
  hal::HciHal* hal_;
  storage::StorageModule* storage_;
  HciLayer& module_;

  // Command Handling
  std::list<CommandQueueEntry> command_queue_;

  std::map<EventCode, ContextualCallback<void(EventView)>> event_handlers_;
  std::map<SubeventCode, ContextualCallback<void(LeMetaEventView)>> le_event_handlers_;
  std::map<DevelopmentSubeventCode, ContextualCallback<void(DevelopmentEventView)>>
          development_event_handlers_;
  std::map<VseSubeventCode, ContextualCallback<void(VendorSpecificEventView)>> vs_event_handlers_;
  std::optional<ContextualCallback<void(VendorSpecificEventView)>> vs_event_default_handler_;

  OpCode waiting_command_{OpCode::NONE};
  uint8_t command_credits_{1};  // Send reset first
  Alarm* hci_timeout_alarm_{nullptr};
  Alarm* hci_abort_alarm_{nullptr};

  // Acl packets
  BidiQueue<AclView, AclBuilder> acl_queue_{3 /* TODO: Set queue depth */};
  os::EnqueueBuffer<AclView> incoming_acl_buffer_{acl_queue_.GetDownEnd()};

  // SCO packets
  BidiQueue<ScoView, ScoBuilder> sco_queue_{3 /* TODO: Set queue depth */};
  os::EnqueueBuffer<ScoView> incoming_sco_buffer_{sco_queue_.GetDownEnd()};

  // ISO packets
  BidiQueue<IsoView, IsoBuilder> iso_queue_{3 /* TODO: Set queue depth */};
  os::EnqueueBuffer<IsoView> incoming_iso_buffer_{iso_queue_.GetDownEnd()};

  HciDataRouter router_;
  uint16_t vendor_connection_handle_min_{0};
  uint16_t vendor_connection_handle_max_{0};
  ContextualCallback<void(uint16_t, std::vector<uint8_t>)> vendor_specific_acl_handler_{};
};

// All functions here are running on the HAL thread
struct HciLayer::hal_callbacks : public hal::HciHalCallbacks {
  explicit hal_callbacks(HciLayer& module) : module_(module) {}

  void hciEventReceived(hal::HciPacket event_bytes) override {
    std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
    if (life_cycle_stopped) {
      return;
    }
    auto packet = packet::PacketView<packet::kLittleEndian>(
            std::make_shared<std::vector<uint8_t>>(event_bytes));
    EventView event = EventView::Create(packet);
    module_.impl_->handler_->CallOn(module_.impl_, &impl::on_hci_event, std::move(event));
  }

  void aclDataReceived(hal::HciPacket data_bytes) override {
    std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
    if (life_cycle_stopped) {
      return;
    }
    auto packet = packet::PacketView<packet::kLittleEndian>(
            std::make_shared<std::vector<uint8_t>>(std::move(data_bytes)));
    auto acl_view = AclView::Create(packet);

    if (com_android_bluetooth_flags_report_vendor_events_from_acl() &&
        module_.impl_->vendor_connection_handle_min_ > 0) {
      log::assert_that(acl_view.IsValid(), "invalid acl packet");
      uint16_t handle = acl_view.GetHandle();
      if (handle >= module_.impl_->vendor_connection_handle_min_ &&
          handle <= module_.impl_->vendor_connection_handle_max_) {
        if (module_.impl_->vendor_specific_acl_handler_) {
          // The payload is copied into a vector because the callback expects a vector.
          auto payload = acl_view.GetPayload();
          std::vector<uint8_t> data(payload.begin(), payload.end());
          // Post the task to the handler thread.
          module_.impl_->handler_->Post(common::BindOnce(
                  [](ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler,
                     uint16_t handle,
                     std::vector<uint8_t> data) { handler(handle, std::move(data)); },
                  module_.impl_->vendor_specific_acl_handler_, handle, std::move(data)));
        } else {
          log::warn("Dropping vendor specific ACL packet since no handler is registered");
        }
        return;
      }
    }

    auto acl = std::make_unique<AclView>(std::move(acl_view));
    module_.impl_->incoming_acl_buffer_.Enqueue(std::move(acl), module_.impl_->handler_);
  }

  void scoDataReceived(hal::HciPacket data_bytes) override {
    std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
    if (life_cycle_stopped) {
      return;
    }
    auto packet = packet::PacketView<packet::kLittleEndian>(
            std::make_shared<std::vector<uint8_t>>(std::move(data_bytes)));
    auto sco = std::make_unique<ScoView>(ScoView::Create(packet));
    module_.impl_->incoming_sco_buffer_.Enqueue(std::move(sco), module_.impl_->handler_);
  }

  void isoDataReceived(hal::HciPacket data_bytes) override {
    std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
    if (life_cycle_stopped) {
      return;
    }
    auto packet = packet::PacketView<packet::kLittleEndian>(
            std::make_shared<std::vector<uint8_t>>(std::move(data_bytes)));
    auto iso = std::make_unique<IsoView>(IsoView::Create(packet));
    module_.impl_->incoming_iso_buffer_.Enqueue(std::move(iso), module_.impl_->handler_);
  }

#ifdef TARGET_FLOSS
  void controllerNeedsReset() override {
    log::info("Controller needs reset!");
    module_.LifeCycleStop();
    kill(getpid(), SIG_RESET_CTRL);
  }
#endif

  HciLayer& module_;
};

common::BidiQueueEnd<AclBuilder, AclView>* HciLayer::GetAclQueueEnd() {
  return impl_->acl_queue_.GetUpEnd();
}

common::BidiQueueEnd<ScoBuilder, ScoView>* HciLayer::GetScoQueueEnd() {
  return impl_->sco_queue_.GetUpEnd();
}

common::BidiQueueEnd<IsoBuilder, IsoView>* HciLayer::GetIsoQueueEnd() {
  return impl_->iso_queue_.GetUpEnd();
}

void HciLayer::EnqueueCommand(unique_ptr<CommandBuilder> command,
                              ContextualOnceCallback<void(CommandCompleteView)> on_complete) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::enqueue_command<CommandCompleteView>, std::move(command),
                          std::move(on_complete));
}

void HciLayer::EnqueueCommand(unique_ptr<CommandBuilder> command,
                              ContextualOnceCallback<void(CommandStatusView)> on_status) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::enqueue_command<CommandStatusView>, std::move(command),
                          std::move(on_status));
}

void HciLayer::EnqueueCommand(
        unique_ptr<CommandBuilder> command,
        ContextualOnceCallback<void(CommandStatusOrCompleteView)> on_status_or_complete) {
  impl_->handler_->CallOn(impl_, &impl::enqueue_command<CommandStatusOrCompleteView>,
                          std::move(command), std::move(on_status_or_complete));
}

void HciLayer::RegisterEventHandler(EventCode event, ContextualCallback<void(EventView)> handler) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::register_event, event, handler);
}

void HciLayer::UnregisterEventHandler(EventCode event) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::unregister_event, event);
}

void HciLayer::RegisterLeEventHandler(SubeventCode event,
                                      ContextualCallback<void(LeMetaEventView)> handler) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::register_le_event, event, handler);
}

void HciLayer::UnregisterLeEventHandler(SubeventCode event) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::unregister_le_event, event);
}

void HciLayer::RegisterDevelopmentEventHandler(
        DevelopmentSubeventCode event, ContextualCallback<void(DevelopmentEventView)> handler) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::register_development_event, event, handler);
}

void HciLayer::UnregisterDevelopmentEventHandler(DevelopmentSubeventCode event) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::unregister_development_event, event);
}

void HciLayer::RegisterVendorSpecificEventHandler(
        VseSubeventCode event, ContextualCallback<void(VendorSpecificEventView)> handler) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::register_vs_event, event, handler);
}

void HciLayer::UnregisterVendorSpecificEventHandler(VseSubeventCode event) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  if (life_cycle_stopped) {
    return;
  }
  impl_->handler_->CallOn(impl_, &impl::unregister_vs_event, event);
}

void HciLayer::RegisterDefaultVendorSpecificEventHandler(
        ContextualCallback<void(VendorSpecificEventView)> handler) {
  impl_->handler_->CallOn(impl_, &impl::register_vs_event_default, handler);
}

void HciLayer::UnregisterDefaultVendorSpecificEventHandler() {
  impl_->handler_->CallOn(impl_, &impl::unregister_vs_event_default);
}

void HciLayer::SetVendorAclHandleRange(uint16_t min, uint16_t max) {
  impl_->handler_->Post(common::BindOnce(
          [](impl* p_impl, uint16_t min, uint16_t max) {
            p_impl->vendor_connection_handle_min_ = min;
            p_impl->vendor_connection_handle_max_ = max;
          },
          impl_, min, max));
}

void HciLayer::RegisterVendorSpecificAclHandler(
        ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler) {
  impl_->handler_->Post(common::BindOnce(
          [](impl* p_impl, ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler) {
            p_impl->vendor_specific_acl_handler_ = handler;
          },
          impl_, handler));
}

void HciLayer::UnregisterVendorSpecificAclHandler() {
  impl_->handler_->Post(
          common::BindOnce([](impl* p_impl) { p_impl->vendor_specific_acl_handler_ = {}; }, impl_));
}

void HciLayer::on_disconnection_complete(EventView event_view) {
  auto disconnection_view = DisconnectionCompleteView::Create(event_view);
  if (!disconnection_view.IsValid()) {
    log::info("Dropping invalid disconnection packet");
    return;
  }

  uint16_t handle = disconnection_view.GetConnectionHandle();
  ErrorCode reason = disconnection_view.GetReason();
  Disconnect(handle, reason);
}

void HciLayer::on_connection_request(EventView event_view) {
  auto view = ConnectionRequestView::Create(event_view);
  if (!view.IsValid()) {
    log::info("Dropping invalid connection request packet");
    return;
  }

  Address address = view.GetBdAddr();
  ClassOfDevice cod = view.GetClassOfDevice();
  ConnectionRequestLinkType link_type = view.GetLinkType();
  switch (link_type) {
    case ConnectionRequestLinkType::ACL:
      if (!on_acl_connection_request_) {
        log::warn("No callback registered for ACL connection requests.");
      } else {
        on_acl_connection_request_(address, cod);
      }
      break;
    case ConnectionRequestLinkType::SCO:
    case ConnectionRequestLinkType::ESCO:
      if (!on_sco_connection_request_) {
        log::warn("No callback registered for SCO connection requests.");
      } else {
        on_sco_connection_request_(address, cod, link_type);
      }
      break;
  }
}

void HciLayer::Disconnect(uint16_t handle, ErrorCode reason) {
  std::unique_lock<std::mutex> lock(callback_handlers_guard_);
  for (auto callback : disconnect_handlers_) {
    callback(handle, reason);
  }
}

void HciLayer::RegisterForDisconnects(ContextualCallback<void(uint16_t, ErrorCode)> on_disconnect) {
  std::unique_lock<std::mutex> lock(callback_handlers_guard_);
  disconnect_handlers_.push_back(on_disconnect);
}

void HciLayer::on_read_remote_version_complete(EventView event_view) {
  auto view = ReadRemoteVersionInformationCompleteView::Create(event_view);
  log::assert_that(view.IsValid(), "Read remote version information packet invalid");
  ReadRemoteVersion(view.GetStatus(), view.GetConnectionHandle(), view.GetVersion(),
                    view.GetManufacturerName(), view.GetSubVersion());
}

void HciLayer::ReadRemoteVersion(hci::ErrorCode hci_status, uint16_t handle, uint8_t version,
                                 uint16_t manufacturer_name, uint16_t sub_version) {
  std::unique_lock<std::mutex> lock(callback_handlers_guard_);
  for (auto callback : read_remote_version_handlers_) {
    callback(hci_status, handle, version, manufacturer_name, sub_version);
  }
}

AclConnectionInterface* HciLayer::GetAclConnectionInterface(
        ContextualCallback<void(EventView)> event_handler,
        ContextualCallback<void(uint16_t, ErrorCode)> on_disconnect,
        ContextualCallback<void(Address, ClassOfDevice)> on_connection_request,
        ContextualCallback<void(hci::ErrorCode hci_status, uint16_t, uint8_t version,
                                uint16_t manufacturer_name, uint16_t sub_version)>
                on_read_remote_version) {
  {
    std::unique_lock<std::mutex> lock(callback_handlers_guard_);
    disconnect_handlers_.push_back(on_disconnect);
    read_remote_version_handlers_.push_back(on_read_remote_version);
    on_acl_connection_request_ = on_connection_request;
  }
  for (const auto event : AclConnectionEvents) {
    RegisterEventHandler(event, event_handler);
  }
  return &acl_connection_manager_interface_;
}

void HciLayer::PutAclConnectionInterface() {
  for (const auto event : AclConnectionEvents) {
    UnregisterEventHandler(event);
  }
  {
    std::unique_lock<std::mutex> lock(callback_handlers_guard_);
    disconnect_handlers_.clear();
    read_remote_version_handlers_.clear();
  }
}

LeAclConnectionInterface* HciLayer::GetLeAclConnectionInterface(
        ContextualCallback<void(LeMetaEventView)> event_handler,
        ContextualCallback<void(uint16_t, ErrorCode)> on_disconnect,
        ContextualCallback<void(hci::ErrorCode hci_status, uint16_t, uint8_t version,
                                uint16_t manufacturer_name, uint16_t sub_version)>
                on_read_remote_version) {
  {
    std::unique_lock<std::mutex> lock(callback_handlers_guard_);
    disconnect_handlers_.push_back(on_disconnect);
    read_remote_version_handlers_.push_back(on_read_remote_version);
  }
  for (const auto event : LeConnectionManagementEvents) {
    RegisterLeEventHandler(event, event_handler);
  }
  return &le_acl_connection_manager_interface_;
}

void HciLayer::PutLeAclConnectionInterface() {
  for (const auto event : LeConnectionManagementEvents) {
    UnregisterLeEventHandler(event);
  }
  {
    std::unique_lock<std::mutex> lock(callback_handlers_guard_);
    disconnect_handlers_.clear();
    read_remote_version_handlers_.clear();
  }
}

void HciLayer::RegisterForScoConnectionRequests(
        common::ContextualCallback<void(Address, ClassOfDevice, ConnectionRequestLinkType)>
                on_sco_connection_request) {
  std::unique_lock<std::mutex> lock(callback_handlers_guard_);
  on_sco_connection_request_ = on_sco_connection_request;
}

SecurityInterface* HciLayer::GetSecurityInterface(
        ContextualCallback<void(EventView)> event_handler) {
  for (const auto event : SecurityEvents) {
    RegisterEventHandler(event, event_handler);
  }
  return &security_interface;
}

LeSecurityInterface* HciLayer::GetLeSecurityInterface(
        ContextualCallback<void(LeMetaEventView)> event_handler) {
  for (const auto subevent : LeSecurityEvents) {
    RegisterLeEventHandler(subevent, event_handler);
  }
  return &le_security_interface;
}

LeAdvertisingInterface* HciLayer::GetLeAdvertisingInterface(
        ContextualCallback<void(LeMetaEventView)> event_handler) {
  for (const auto subevent : LeAdvertisingEvents) {
    RegisterLeEventHandler(subevent, event_handler);
  }
  return &le_advertising_interface;
}

void HciLayer::ReleaseLeAdvertisingInterface() {
  for (const auto subevent : LeAdvertisingEvents) {
    UnregisterLeEventHandler(subevent);
  }
}

LeScanningInterface* HciLayer::GetLeScanningInterface(
        ContextualCallback<void(LeMetaEventView)> event_handler) {
  for (const auto subevent : LeScanningEvents) {
    RegisterLeEventHandler(subevent, event_handler);
  }
  return &le_scanning_interface;
}

void HciLayer::ReleaseLeScanningInterface() {
  for (const auto subevent : LeScanningEvents) {
    UnregisterLeEventHandler(subevent);
  }
}

LeIsoInterface* HciLayer::GetLeIsoInterface(
        ContextualCallback<void(LeMetaEventView)> event_handler) {
  for (const auto subevent : LeIsoEvents) {
    RegisterLeEventHandler(subevent, event_handler);
  }
  return &le_iso_interface;
}

DistanceMeasurementInterface* HciLayer::GetDistanceMeasurementInterface(
        ContextualCallback<void(LeMetaEventView)> event_handler) {
  for (const auto subevent : DistanceMeasurementEvents) {
    RegisterLeEventHandler(subevent, event_handler);
  }
  return &distance_measurement_interface;
}

void HciLayer::ReleaseDistanceMeasurementInterface() {
  for (const auto subevent : DistanceMeasurementEvents) {
    UnregisterLeEventHandler(subevent);
  }
}

std::unique_ptr<InquiryInterface> HciLayer::GetInquiryInterface(
        ContextualCallback<void(EventView)> event_handler) {
  for (const auto event : InquiryEvents) {
    RegisterEventHandler(event, event_handler);
  }
  auto cleanup = common::BindOnce(
          [](HciLayer* hci) {
            for (const auto event : InquiryEvents) {
              hci->UnregisterEventHandler(event);
            }
          },
          common::Unretained(this));
  return std::make_unique<CommandInterfaceImpl<DiscoveryCommandBuilder>>(this, std::move(cleanup));
}

void HciLayer::SetLeAclDataConsumer(LeAclDataConsumer* le_acl_data_consumer) {
  impl_->SetLeAclDataConsumer(le_acl_data_consumer);
}
void HciLayer::SetClassicAclDataConsumer(ClassicAclDataConsumer* classic_acl_data_consumer) {
  impl_->SetClassicAclDataConsumer(classic_acl_data_consumer);
}

HciLayer::HciLayer(Handler* handler, hal::HciHal* hal, storage::StorageModule* storage) {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  impl_ = new impl(handler, hal, storage, *this);
  hal_callbacks_ = new hal_callbacks(*this);
  life_cycle_stopped = false;

  impl_->acl_queue_.GetDownEnd()->RegisterDequeue(
          handler, base::Bind(&impl::on_outbound_acl_ready, base::Unretained(impl_)));
  impl_->sco_queue_.GetDownEnd()->RegisterDequeue(
          handler, base::Bind(&impl::on_outbound_sco_ready, base::Unretained(impl_)));
  impl_->iso_queue_.GetDownEnd()->RegisterDequeue(
          handler, base::Bind(&impl::on_outbound_iso_ready, base::Unretained(impl_)));
  StartWithNoHalDependencies(handler);
  hal->registerIncomingPacketCallback(hal_callbacks_);
  EnqueueCommand(ResetBuilder::Create(), handler->BindOnce(&fail_if_reset_complete_not_success));

  log::verbose("module started !!");
}

HciLayer::HciLayer(Handler*) { impl_ = nullptr; }

// Initialize event handlers that don't depend on the HAL
void HciLayer::StartWithNoHalDependencies(Handler* handler) {
  RegisterEventHandler(EventCode::DISCONNECTION_COMPLETE,
                       handler->BindOn(this, &HciLayer::on_disconnection_complete));
  RegisterEventHandler(EventCode::READ_REMOTE_VERSION_INFORMATION_COMPLETE,
                       handler->BindOn(this, &HciLayer::on_read_remote_version_complete));
  auto drop_packet = handler->BindOn(impl_, &impl::drop);
  RegisterEventHandler(EventCode::PAGE_SCAN_REPETITION_MODE_CHANGE, drop_packet);
  RegisterEventHandler(EventCode::MAX_SLOTS_CHANGE, drop_packet);
  RegisterEventHandler(EventCode::CONNECTION_REQUEST,
                       handler->BindOn(this, &HciLayer::on_connection_request));
}

// Unregister event handlers that don't depend on the HAL
void HciLayer::StopWithNoHalDependencies() {
  UnregisterEventHandler(EventCode::DISCONNECTION_COMPLETE);
  UnregisterEventHandler(EventCode::READ_REMOTE_VERSION_INFORMATION_COMPLETE);
  UnregisterEventHandler(EventCode::PAGE_SCAN_REPETITION_MODE_CHANGE);
  UnregisterEventHandler(EventCode::MAX_SLOTS_CHANGE);
  UnregisterEventHandler(EventCode::CONNECTION_REQUEST);
}

HciLayer::~HciLayer() {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  life_cycle_stopped = true;
  if (!impl_) {
    return;
  }

  impl_->hal_->unregisterIncomingPacketCallback();
  delete hal_callbacks_;

  StopWithNoHalDependencies();

  impl_->acl_queue_.GetDownEnd()->UnregisterDequeue();
  impl_->sco_queue_.GetDownEnd()->UnregisterDequeue();
  impl_->iso_queue_.GetDownEnd()->UnregisterDequeue();
  delete impl_;

  log::verbose("module stopped !!");
}

// Function to stop sending and handling incoming packets
void HciLayer::LifeCycleStop() {
  std::unique_lock<std::recursive_mutex> lock(life_cycle_guard);
  life_cycle_stopped = true;
}

}  // namespace hci
}  // namespace bluetooth
