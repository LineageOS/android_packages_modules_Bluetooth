/******************************************************************************
 *
 *  Copyright 2016 The Android Open Source Project
 *  Copyright 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth-a2dp"

#include "btif/include/btif_a2dp_sink.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <future>
#include <mutex>
#include <string>
#include <utility>

#include "a2dp_api.h"
#include "a2dp_codec_api.h"
#include "avdt_api.h"
#include "bta_av_api.h"
#include "btif/include/btif_av.h"
#include "btif/include/btif_av_co.h"
#include "btif/include/btif_avrcp_audio_track.h"
#include "btif/include/btif_util.h"  // CASE_RETURN_STR
#include "common/message_loop_thread.h"
#include "osi/include/alarm.h"
#include "osi/include/allocator.h"
#include "osi/include/fixed_queue.h"
#include "stack/include/bt_hdr.h"
#include "types/raw_address.h"

using bluetooth::common::MessageLoopThread;
using LockGuard = std::lock_guard<std::mutex>;
using namespace bluetooth;

/**
 * The receiving queue buffer size.
 */
#define MAX_INPUT_A2DP_FRAME_QUEUE_SZ (MAX_PCM_FRAME_NUM_PER_TICK * 2)

#define BTIF_SINK_MEDIA_TIME_TICK_MS 20

/* In case of A2DP Sink, we will delay start by 5 AVDTP Packets */
#define MAX_A2DP_DELAYED_START_FRAME_COUNT 5

enum {
  BTIF_A2DP_SINK_STATE_OFF,
  BTIF_A2DP_SINK_STATE_STARTING_UP,
  BTIF_A2DP_SINK_STATE_RUNNING,
  BTIF_A2DP_SINK_STATE_SHUTTING_DOWN
};

/* BTIF Media Sink command event definition */
enum {
  BTIF_MEDIA_SINK_DECODER_UPDATE = 1,
  BTIF_MEDIA_SINK_CLEAR_TRACK,
  BTIF_MEDIA_SINK_SET_FOCUS_STATE,
  BTIF_MEDIA_SINK_AUDIO_RX_FLUSH,
  BTIF_MEDIA_SINK_START,
  BTIF_MEDIA_SINK_SUSPEND
};

typedef struct {
  BT_HDR_RIGID hdr;
  uint8_t codec_info[AVDT_CODEC_SIZE];
  RawAddress peer_address;
} tBTIF_MEDIA_SINK_DECODER_UPDATE;

typedef struct {
  BT_HDR_RIGID hdr;
  btif_a2dp_sink_focus_state_t focus_state;
} tBTIF_MEDIA_SINK_FOCUS_UPDATE;

/* BTIF A2DP Sink control block */
class BtifA2dpSinkControlBlock {
public:
  explicit BtifA2dpSinkControlBlock(const std::string& thread_name)
      : worker_thread(thread_name),
        rx_audio_queue(nullptr),
        rx_flush(false),
        decode_alarm(nullptr),
        sample_rate(0),
        channel_count(0),
        rx_focus_state(BTIF_A2DP_SINK_FOCUS_NOT_GRANTED),
        audio_track(nullptr),
        decoder_interface(nullptr) {}

  void Reset() {
    if (audio_track != nullptr) {
      BtifAvrcpAudioTrackStop(audio_track);
      BtifAvrcpAudioTrackDelete(audio_track);
    }
    audio_track = nullptr;
    fixed_queue_free(rx_audio_queue, nullptr);
    rx_audio_queue = nullptr;
    alarm_free(decode_alarm);
    decode_alarm = nullptr;
    rx_flush = false;
    rx_focus_state = BTIF_A2DP_SINK_FOCUS_NOT_GRANTED;
    sample_rate = 0;
    channel_count = 0;
    decoder_interface = nullptr;
  }

  MessageLoopThread worker_thread;
  fixed_queue_t* rx_audio_queue;
  bool rx_flush; /* discards any incoming data when true */
  alarm_t* decode_alarm;
  int sample_rate;                             // 32000, 44100, 48000, 96000
  int bits_per_sample;                         // 16, 24, 32
  int channel_count;                           // 1, 2
  btif_a2dp_sink_focus_state_t rx_focus_state; /* audio focus state */
  void* audio_track;
  const tA2DP_DECODER_INTERFACE* decoder_interface;
};

// Mutex for below data structures.
static std::mutex g_mutex;

static BtifA2dpSinkControlBlock btif_a2dp_sink_cb("bt_a2dp_sink_worker_thread");

static std::atomic<int> btif_a2dp_sink_state{BTIF_A2DP_SINK_STATE_OFF};

static void btif_a2dp_sink_init_delayed();
static void btif_a2dp_sink_startup_delayed();
static void btif_a2dp_sink_start_session_delayed(const RawAddress& peer_address,
                                                 std::promise<void> peer_ready_promise);
static void btif_a2dp_sink_end_session_delayed();
static void btif_a2dp_sink_shutdown_delayed();
static void btif_a2dp_sink_cleanup_delayed();
static void btif_a2dp_sink_command_ready(BT_HDR_RIGID* p_msg);
static void btif_a2dp_sink_audio_handle_stop_decoding();
static void btif_decode_alarm_cb(void* context);
static void btif_a2dp_sink_audio_handle_start_decoding();
static void btif_a2dp_sink_avk_handle_timer();
static void btif_a2dp_sink_audio_rx_flush_req();
/* Handle incoming media packets A2DP SINK streaming */
static void btif_a2dp_sink_handle_inc_media(BT_HDR* p_msg);
static void btif_a2dp_sink_decoder_update_event(tBTIF_MEDIA_SINK_DECODER_UPDATE* p_buf);
static void btif_a2dp_sink_decoder_update_event_old(tBTIF_MEDIA_SINK_DECODER_UPDATE* p_buf);
static void btif_a2dp_sink_clear_track_event();
static void btif_a2dp_sink_set_focus_state_event(btif_a2dp_sink_focus_state_t state);
static void btif_a2dp_sink_audio_rx_flush_event();
static void btif_a2dp_sink_clear_track_event_req();
static void btif_a2dp_sink_on_start_event();
static void btif_a2dp_sink_on_suspend_event();

static const char* dump_media_event(uint16_t event) {
  switch (event) {
    CASE_RETURN_STR(BTIF_MEDIA_SINK_DECODER_UPDATE)
    CASE_RETURN_STR(BTIF_MEDIA_SINK_CLEAR_TRACK)
    CASE_RETURN_STR(BTIF_MEDIA_SINK_SET_FOCUS_STATE)
    CASE_RETURN_STR(BTIF_MEDIA_SINK_AUDIO_RX_FLUSH)
    CASE_RETURN_STR(BTIF_MEDIA_SINK_START)
    CASE_RETURN_STR(BTIF_MEDIA_SINK_SUSPEND)
    default:
      break;
  }
  return "UNKNOWN A2DP SINK EVENT";
}

bool btif_a2dp_sink_init() {
  log::info("");
  LockGuard lock(g_mutex);

  if (btif_a2dp_sink_state != BTIF_A2DP_SINK_STATE_OFF) {
    log::error("A2DP Sink media task already running");
    return false;
  }

  btif_a2dp_sink_cb.Reset();
  btif_a2dp_sink_state = BTIF_A2DP_SINK_STATE_STARTING_UP;

  /* Start A2DP Sink media task */
  btif_a2dp_sink_cb.worker_thread.StartUp();
  if (!btif_a2dp_sink_cb.worker_thread.IsRunning()) {
    log::error("unable to start up media thread");
    btif_a2dp_sink_state = BTIF_A2DP_SINK_STATE_OFF;
    return false;
  }

  btif_a2dp_sink_cb.rx_audio_queue = fixed_queue_new(SIZE_MAX);

  /* Schedule the rest of the operations */
  if (!btif_a2dp_sink_cb.worker_thread.EnableRealTimeScheduling()) {
#if defined(__ANDROID__)
    log::fatal("Failed to increase A2DP decoder thread priority");
#endif
  }
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_init_delayed));
  return true;
}

static void btif_a2dp_sink_init_delayed() {
  log::info("");
  btif_a2dp_sink_state = BTIF_A2DP_SINK_STATE_RUNNING;
}

bool btif_a2dp_sink_startup() {
  log::info("");
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_startup_delayed));
  return true;
}

static void btif_a2dp_sink_startup_delayed() {
  log::info("");
  LockGuard lock(g_mutex);
  // Nothing to do
}

static void btif_a2dp_sink_on_decode_complete([[maybe_unused]] uint8_t* data,
                                              [[maybe_unused]] uint32_t len) {
#ifdef __ANDROID__
  BtifAvrcpAudioTrackWriteData(btif_a2dp_sink_cb.audio_track, reinterpret_cast<void*>(data), len);
#endif
}

static bool btif_a2dp_sink_initialize_a2dp_control_block(const RawAddress& peer_address) {
  log::info("Initializing the control block for peer {}", peer_address);
  if (peer_address.IsEmpty()) {
    log::error("Peer address is empty. Control block cannot be initialized");
    return false;
  }
  uint8_t* codec_config = bta_av_co_get_codec_config(peer_address);
  log::verbose("p_codec_info[{:x}:{:x}:{:x}:{:x}:{:x}:{:x}]", codec_config[1], codec_config[2],
               codec_config[3], codec_config[4], codec_config[5], codec_config[6]);

  btif_a2dp_sink_cb.decoder_interface = A2DP_GetDecoderInterface(codec_config);

  if (btif_a2dp_sink_cb.decoder_interface == nullptr) {
    log::error("cannot stream audio: no source decoder interface");
    return false;
  }

  if (!btif_a2dp_sink_cb.decoder_interface->decoder_init(btif_a2dp_sink_on_decode_complete)) {
    log::error("failed to initialize decoder");
    return false;
  }

  if (btif_a2dp_sink_cb.decoder_interface->decoder_configure != nullptr) {
    btif_a2dp_sink_cb.decoder_interface->decoder_configure(codec_config);
  }

  log::info("codec = {}", A2DP_CodecInfoString(codec_config));
  int sample_rate = A2DP_GetTrackSampleRate(codec_config);
  if (sample_rate == -1) {
    log::error("cannot get the track frequency");
    return false;
  }
  int bits_per_sample = A2DP_GetTrackBitsPerSample(codec_config);
  if (bits_per_sample == -1) {
    log::error("%cannot get the bits per sample");
    return false;
  }
  int channel_count = A2DP_GetTrackChannelCount(codec_config);
  if (channel_count == -1) {
    log::error("cannot get the channel count");
    return false;
  }
  int channel_type = A2DP_GetSinkTrackChannelType(codec_config);
  if (channel_type == -1) {
    log::error("cannot get the Sink channel type");
    return false;
  }
  btif_a2dp_sink_cb.sample_rate = sample_rate;
  btif_a2dp_sink_cb.bits_per_sample = bits_per_sample;
  btif_a2dp_sink_cb.channel_count = channel_count;

  btif_a2dp_sink_cb.audio_track =
#ifdef __ANDROID__
          BtifAvrcpAudioTrackCreate(sample_rate, bits_per_sample, channel_count);
#else
          NULL;
#endif
  if (btif_a2dp_sink_cb.audio_track == nullptr) {
    log::error("track creation failed");
    return false;
  }
  log::info("A2DP sink control block initialized");
  return true;
}

bool btif_a2dp_sink_start_session(const RawAddress& peer_address,
                                  std::promise<void> peer_ready_promise) {
  log::info("peer_address={}", peer_address);
  if (btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(
              btif_a2dp_sink_start_session_delayed, peer_address, std::move(peer_ready_promise)))) {
    return true;
  } else {
    // cannot set promise but triggers crash
    log::fatal("peer_address={} fails to context switch", peer_address);
    return false;
  }
}

static void btif_a2dp_sink_start_session_delayed(const RawAddress& peer_address,
                                                 std::promise<void> peer_ready_promise) {
  log::info("");
  LockGuard lock(g_mutex);
  if (com::android::bluetooth::flags::bta_av_use_peer_codec()) {
    btif_a2dp_sink_initialize_a2dp_control_block(peer_address);
  }
  peer_ready_promise.set_value();
  // Nothing to do
}

bool btif_a2dp_sink_restart_session(const RawAddress& old_peer_address,
                                    const RawAddress& new_peer_address,
                                    std::promise<void> peer_ready_promise) {
  log::info("old_peer_address={} new_peer_address={}", old_peer_address, new_peer_address);

  log::assert_that(!new_peer_address.IsEmpty(), "assert failed: !new_peer_address.IsEmpty()");

  if (!old_peer_address.IsEmpty()) {
    btif_a2dp_sink_end_session(old_peer_address);
  }
  if (!bta_av_co_set_active_sink_peer(new_peer_address)) {
    log::error("Cannot stream audio: cannot set active peer to {}", new_peer_address);
    peer_ready_promise.set_value();
    return false;
  }

  if (old_peer_address.IsEmpty()) {
    btif_a2dp_sink_startup();
  }
  btif_a2dp_sink_start_session(new_peer_address, std::move(peer_ready_promise));

  return true;
}

bool btif_a2dp_sink_end_session(const RawAddress& peer_address) {
  log::info("peer_address={}", peer_address);
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_end_session_delayed));
  return true;
}

static void btif_a2dp_sink_end_session_delayed() {
  log::info("");
  LockGuard lock(g_mutex);
  // Nothing to do
}

void btif_a2dp_sink_shutdown() {
  log::info("");
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_shutdown_delayed));
}

static void btif_a2dp_sink_shutdown_delayed() {
  log::info("");
  LockGuard lock(g_mutex);
  // Nothing to do
}

void btif_a2dp_sink_cleanup() {
  log::info("");

  alarm_t* decode_alarm;

  // Make sure the sink is shutdown
  btif_a2dp_sink_shutdown();

  {
    LockGuard lock(g_mutex);
    if ((btif_a2dp_sink_state == BTIF_A2DP_SINK_STATE_OFF) ||
        (btif_a2dp_sink_state == BTIF_A2DP_SINK_STATE_SHUTTING_DOWN)) {
      return;
    }
    // Make sure no channels are restarted while shutting down
    btif_a2dp_sink_state = BTIF_A2DP_SINK_STATE_SHUTTING_DOWN;

    decode_alarm = btif_a2dp_sink_cb.decode_alarm;
    btif_a2dp_sink_cb.decode_alarm = nullptr;
  }

  // Stop the timer
  alarm_free(decode_alarm);

  // Exit the thread
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_cleanup_delayed));
  btif_a2dp_sink_cb.worker_thread.ShutDown();
}

static void btif_a2dp_sink_cleanup_delayed() {
  log::info("");
  LockGuard lock(g_mutex);

  fixed_queue_free(btif_a2dp_sink_cb.rx_audio_queue, nullptr);
  btif_a2dp_sink_cb.rx_audio_queue = nullptr;
  btif_a2dp_sink_state = BTIF_A2DP_SINK_STATE_OFF;
}

static void btif_a2dp_sink_command_ready(BT_HDR_RIGID* p_msg) {
  log::verbose("event {} {}", p_msg->event, dump_media_event(p_msg->event));

  switch (p_msg->event) {
    case BTIF_MEDIA_SINK_DECODER_UPDATE:
      btif_a2dp_sink_decoder_update_event((tBTIF_MEDIA_SINK_DECODER_UPDATE*)p_msg);
      break;
    case BTIF_MEDIA_SINK_CLEAR_TRACK:
      btif_a2dp_sink_clear_track_event();
      break;
    case BTIF_MEDIA_SINK_SET_FOCUS_STATE: {
      btif_a2dp_sink_focus_state_t state = ((tBTIF_MEDIA_SINK_FOCUS_UPDATE*)p_msg)->focus_state;
      btif_a2dp_sink_set_focus_state_event(state);
      break;
    }
    case BTIF_MEDIA_SINK_AUDIO_RX_FLUSH:
      btif_a2dp_sink_audio_rx_flush_event();
      break;
    case BTIF_MEDIA_SINK_START:
      btif_a2dp_sink_on_start_event();
      break;
    case BTIF_MEDIA_SINK_SUSPEND:
      btif_a2dp_sink_on_suspend_event();
      break;
    default:
      log::error("unknown event {}", p_msg->event);
      break;
  }

  log::verbose("{} DONE", dump_media_event(p_msg->event));
  osi_free(p_msg);
}

void btif_a2dp_sink_update_decoder(const RawAddress& peer_address, const uint8_t* p_codec_info) {
  log::info("peer_address {}", peer_address);
  tBTIF_MEDIA_SINK_DECODER_UPDATE* p_buf = reinterpret_cast<tBTIF_MEDIA_SINK_DECODER_UPDATE*>(
          osi_malloc(sizeof(tBTIF_MEDIA_SINK_DECODER_UPDATE)));

  log::verbose("p_codec_info[{:x}:{:x}:{:x}:{:x}:{:x}:{:x}]", p_codec_info[1], p_codec_info[2],
               p_codec_info[3], p_codec_info[4], p_codec_info[5], p_codec_info[6]);
  memcpy(p_buf->codec_info, p_codec_info, AVDT_CODEC_SIZE);
  p_buf->peer_address = peer_address;
  p_buf->hdr.event = BTIF_MEDIA_SINK_DECODER_UPDATE;

  btif_a2dp_sink_cb.worker_thread.DoInThread(
          base::BindOnce(btif_a2dp_sink_command_ready, (BT_HDR_RIGID*)p_buf));
}

void btif_a2dp_sink_on_idle() {
  log::info("");
  BT_HDR_RIGID* p_buf = reinterpret_cast<BT_HDR_RIGID*>(osi_malloc(sizeof(BT_HDR_RIGID)));
  p_buf->event = BTIF_MEDIA_SINK_SUSPEND;
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_command_ready, p_buf));

  if (btif_a2dp_sink_state == BTIF_A2DP_SINK_STATE_OFF) {
    return;
  }
  btif_a2dp_sink_audio_handle_stop_decoding();
  btif_a2dp_sink_clear_track_event_req();
}

void btif_a2dp_sink_on_stopped(tBTA_AV_SUSPEND* /* p_av_suspend */) {
  log::info("");
  BT_HDR_RIGID* p_buf = reinterpret_cast<BT_HDR_RIGID*>(osi_malloc(sizeof(BT_HDR_RIGID)));
  p_buf->event = BTIF_MEDIA_SINK_SUSPEND;
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_command_ready, p_buf));

  if (btif_a2dp_sink_state == BTIF_A2DP_SINK_STATE_OFF) {
    return;
  }
  btif_a2dp_sink_audio_handle_stop_decoding();
}

void btif_a2dp_sink_on_suspended(tBTA_AV_SUSPEND* /* p_av_suspend */) {
  log::info("");
  BT_HDR_RIGID* p_buf = reinterpret_cast<BT_HDR_RIGID*>(osi_malloc(sizeof(BT_HDR_RIGID)));
  p_buf->event = BTIF_MEDIA_SINK_SUSPEND;
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_command_ready, p_buf));

  if (btif_a2dp_sink_state == BTIF_A2DP_SINK_STATE_OFF) {
    return;
  }
  btif_a2dp_sink_audio_handle_stop_decoding();
}

bool btif_a2dp_sink_on_start() {
  log::info("");

  BT_HDR_RIGID* p_buf = reinterpret_cast<BT_HDR_RIGID*>(osi_malloc(sizeof(BT_HDR_RIGID)));
  p_buf->event = BTIF_MEDIA_SINK_START;
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_command_ready, p_buf));

  return true;
}

static void btif_a2dp_sink_audio_handle_stop_decoding() {
  log::info("");
  alarm_t* old_alarm;
  {
    LockGuard lock(g_mutex);
    btif_a2dp_sink_cb.rx_flush = true;
    btif_a2dp_sink_audio_rx_flush_req();
    old_alarm = btif_a2dp_sink_cb.decode_alarm;
    btif_a2dp_sink_cb.decode_alarm = nullptr;
  }

  // Drop the lock here, btif_decode_alarm_cb may in the process of being called
  // while we alarm free leading to deadlock.
  //
  // alarm_free waits for btif_decode_alarm_cb which is waiting for g_mutex.
  alarm_free(old_alarm);

  {
    LockGuard lock(g_mutex);
#ifdef __ANDROID__
    BtifAvrcpAudioTrackPause(btif_a2dp_sink_cb.audio_track);
#endif
  }
}

static void btif_decode_alarm_cb(void* /* context */) {
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_avk_handle_timer));
}

static void btif_a2dp_sink_clear_track_event() {
  log::info("");
  LockGuard lock(g_mutex);

#ifdef __ANDROID__
  BtifAvrcpAudioTrackStop(btif_a2dp_sink_cb.audio_track);
  BtifAvrcpAudioTrackDelete(btif_a2dp_sink_cb.audio_track);
#endif
  btif_a2dp_sink_cb.audio_track = nullptr;
}

// Must be called while locked.
static void btif_a2dp_sink_audio_handle_start_decoding() {
  log::info("");
  if (btif_a2dp_sink_cb.decode_alarm != nullptr) {
    return;  // Already started decoding
  }

#ifdef __ANDROID__
  BtifAvrcpAudioTrackStart(btif_a2dp_sink_cb.audio_track);
#endif

  btif_a2dp_sink_cb.decode_alarm = alarm_new_periodic("btif.a2dp_sink_decode");
  if (btif_a2dp_sink_cb.decode_alarm == nullptr) {
    log::error("unable to allocate decode alarm");
    return;
  }
  alarm_set(btif_a2dp_sink_cb.decode_alarm, BTIF_SINK_MEDIA_TIME_TICK_MS, btif_decode_alarm_cb,
            nullptr);
}

// Must be called while locked.
static void btif_a2dp_sink_handle_inc_media(BT_HDR* p_msg) {
  if ((btif_av_get_peer_sep(A2dpType::kSink) == AVDT_TSEP_SNK) || (btif_a2dp_sink_cb.rx_flush)) {
    log::verbose("state changed happened in this tick");
    return;
  }

  log::assert_that(btif_a2dp_sink_cb.decoder_interface != nullptr,
                   "assert failed: btif_a2dp_sink_cb.decoder_interface != nullptr");
  if (!btif_a2dp_sink_cb.decoder_interface->decode_packet(p_msg)) {
    log::error("decoding failed");
  }
}

static void btif_a2dp_sink_avk_handle_timer() {
  LockGuard lock(g_mutex);

  BT_HDR* p_msg;
  if (fixed_queue_is_empty(btif_a2dp_sink_cb.rx_audio_queue)) {
    log::verbose("empty queue");
    return;
  }

  /* Don't do anything in case of focus not granted */
  if (btif_a2dp_sink_cb.rx_focus_state == BTIF_A2DP_SINK_FOCUS_NOT_GRANTED) {
    log::verbose("skipping frames since focus is not present");
    return;
  }
  /* Play only in BTIF_A2DP_SINK_FOCUS_GRANTED case */
  if (btif_a2dp_sink_cb.rx_flush) {
    fixed_queue_flush(btif_a2dp_sink_cb.rx_audio_queue, osi_free);
    return;
  }

  log::verbose("process frames begin");
  while (true) {
    p_msg = (BT_HDR*)fixed_queue_try_dequeue(btif_a2dp_sink_cb.rx_audio_queue);
    if (p_msg == NULL) {
      break;
    }
    log::verbose("number of packets in queue {}",
                 fixed_queue_length(btif_a2dp_sink_cb.rx_audio_queue));

    /* Queue packet has less frames */
    btif_a2dp_sink_handle_inc_media(p_msg);
    osi_free(p_msg);
  }
  log::verbose("process frames end");
}

/* when true media task discards any rx frames */
void btif_a2dp_sink_set_rx_flush(bool enable) {
  log::info("enable={}", enable);
  LockGuard lock(g_mutex);

  btif_a2dp_sink_cb.rx_flush = enable;
}

static void btif_a2dp_sink_audio_rx_flush_event() {
  log::info("");
  LockGuard lock(g_mutex);
  // Flush all received encoded audio buffers
  fixed_queue_flush(btif_a2dp_sink_cb.rx_audio_queue, osi_free);
}

static void btif_a2dp_sink_decoder_update_event(tBTIF_MEDIA_SINK_DECODER_UPDATE* p_buf) {
  log::info("");
  if (!com::android::bluetooth::flags::bta_av_use_peer_codec()) {
    btif_a2dp_sink_decoder_update_event_old(p_buf);
    return;
  }
  LockGuard lock(g_mutex);
  log::verbose("p_codec_info[{:x}:{:x}:{:x}:{:x}:{:x}:{:x}]", p_buf->codec_info[1],
               p_buf->codec_info[2], p_buf->codec_info[3], p_buf->codec_info[4],
               p_buf->codec_info[5], p_buf->codec_info[6]);

  btif_a2dp_sink_cb.rx_flush = false;
  log::verbose("reset to Sink role");

  bta_av_co_save_codec(p_buf->peer_address, p_buf->codec_info);
  log::info("codec = {}", A2DP_CodecInfoString(p_buf->codec_info));
}

static void btif_a2dp_sink_decoder_update_event_old(tBTIF_MEDIA_SINK_DECODER_UPDATE* p_buf) {
  log::info("");
  LockGuard lock(g_mutex);
  log::verbose("p_codec_info[{:x}:{:x}:{:x}:{:x}:{:x}:{:x}]", p_buf->codec_info[1],
               p_buf->codec_info[2], p_buf->codec_info[3], p_buf->codec_info[4],
               p_buf->codec_info[5], p_buf->codec_info[6]);

  int sample_rate = A2DP_GetTrackSampleRate(p_buf->codec_info);
  if (sample_rate == -1) {
    log::error("cannot get the track frequency");
    return;
  }
  int bits_per_sample = A2DP_GetTrackBitsPerSample(p_buf->codec_info);
  if (bits_per_sample == -1) {
    log::error("cannot get the bits per sample");
    return;
  }
  int channel_count = A2DP_GetTrackChannelCount(p_buf->codec_info);
  if (channel_count == -1) {
    log::error("cannot get the channel count");
    return;
  }
  int channel_type = A2DP_GetSinkTrackChannelType(p_buf->codec_info);
  if (channel_type == -1) {
    log::error("cannot get the Sink channel type");
    return;
  }
  btif_a2dp_sink_cb.sample_rate = sample_rate;
  btif_a2dp_sink_cb.bits_per_sample = bits_per_sample;
  btif_a2dp_sink_cb.channel_count = channel_count;

  btif_a2dp_sink_cb.rx_flush = false;
  log::verbose("reset to Sink role");

  bta_av_co_save_codec(p_buf->peer_address, p_buf->codec_info);
  log::info("codec = {}", A2DP_CodecInfoString(p_buf->codec_info));

  btif_a2dp_sink_cb.decoder_interface = A2DP_GetDecoderInterface(p_buf->codec_info);

  if (btif_a2dp_sink_cb.decoder_interface == nullptr) {
    log::error("cannot stream audio: no source decoder interface");
    return;
  }

  if (!btif_a2dp_sink_cb.decoder_interface->decoder_init(btif_a2dp_sink_on_decode_complete)) {
    log::error("failed to initialize decoder");
    return;
  }

  if (btif_a2dp_sink_cb.decoder_interface->decoder_configure != nullptr) {
    btif_a2dp_sink_cb.decoder_interface->decoder_configure(p_buf->codec_info);
  }

  log::verbose("create audio track");
  btif_a2dp_sink_cb.audio_track =
#ifdef __ANDROID__
          BtifAvrcpAudioTrackCreate(sample_rate, bits_per_sample, channel_count);
#else
          NULL;
#endif
  if (btif_a2dp_sink_cb.audio_track == nullptr) {
    log::error("track creation failed");
    return;
  }
}

uint8_t btif_a2dp_sink_enqueue_buf(BT_HDR* p_pkt) {
  LockGuard lock(g_mutex);
  if (btif_a2dp_sink_cb.rx_flush) { /* Flush enabled, do not enqueue */
    return fixed_queue_length(btif_a2dp_sink_cb.rx_audio_queue);
  }

  log::verbose("+");

  /* Allocate and queue this buffer */
  BT_HDR* p_msg = reinterpret_cast<BT_HDR*>(osi_malloc(sizeof(*p_msg) + p_pkt->len));
  memcpy(p_msg, p_pkt, sizeof(*p_msg));
  p_msg->offset = 0;
  memcpy(p_msg->data, p_pkt->data + p_pkt->offset, p_pkt->len);
  fixed_queue_enqueue(btif_a2dp_sink_cb.rx_audio_queue, p_msg);

  /* If the queue is full, pop the front off to make room for the new data */
  if (fixed_queue_length(btif_a2dp_sink_cb.rx_audio_queue) == MAX_INPUT_A2DP_FRAME_QUEUE_SZ) {
    log::verbose("Audio data buffer has reached max size. Dropping front packet");
    osi_free(fixed_queue_try_dequeue(btif_a2dp_sink_cb.rx_audio_queue));
  }

  /* Check to see if we need to start decoding */
  if (btif_a2dp_sink_cb.decode_alarm == nullptr &&
      fixed_queue_length(btif_a2dp_sink_cb.rx_audio_queue) >= MAX_A2DP_DELAYED_START_FRAME_COUNT) {
    log::verbose("Can initiate decoding, focus_state={}", btif_a2dp_sink_cb.rx_focus_state);
    if (btif_a2dp_sink_cb.rx_focus_state == BTIF_A2DP_SINK_FOCUS_GRANTED) {
      log::info("Request to begin decoding");
      btif_a2dp_sink_audio_handle_start_decoding();
    }
  }

  return fixed_queue_length(btif_a2dp_sink_cb.rx_audio_queue);
}

void btif_a2dp_sink_audio_rx_flush_req() {
  log::info("");
  if (fixed_queue_is_empty(btif_a2dp_sink_cb.rx_audio_queue)) {
    /* Queue is already empty */
    return;
  }

  BT_HDR_RIGID* p_buf = reinterpret_cast<BT_HDR_RIGID*>(osi_malloc(sizeof(BT_HDR_RIGID)));
  p_buf->event = BTIF_MEDIA_SINK_AUDIO_RX_FLUSH;
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_command_ready, p_buf));
}

void btif_a2dp_sink_debug_dump(int /* fd */) {
  // Nothing to do
}

void btif_a2dp_sink_set_focus_state_req(btif_a2dp_sink_focus_state_t state) {
  log::info("");
  tBTIF_MEDIA_SINK_FOCUS_UPDATE* p_buf = reinterpret_cast<tBTIF_MEDIA_SINK_FOCUS_UPDATE*>(
          osi_malloc(sizeof(tBTIF_MEDIA_SINK_FOCUS_UPDATE)));
  p_buf->focus_state = state;
  p_buf->hdr.event = BTIF_MEDIA_SINK_SET_FOCUS_STATE;
  btif_a2dp_sink_cb.worker_thread.DoInThread(
          base::BindOnce(btif_a2dp_sink_command_ready, (BT_HDR_RIGID*)p_buf));
}

static void btif_a2dp_sink_set_focus_state_event(btif_a2dp_sink_focus_state_t state) {
  log::info("state={}", state);
  LockGuard lock(g_mutex);

  log::verbose("setting focus state to {}", state);
  btif_a2dp_sink_cb.rx_focus_state = state;
  if (btif_a2dp_sink_cb.rx_focus_state == BTIF_A2DP_SINK_FOCUS_NOT_GRANTED) {
    fixed_queue_flush(btif_a2dp_sink_cb.rx_audio_queue, osi_free);
    btif_a2dp_sink_cb.rx_flush = true;
  } else if (btif_a2dp_sink_cb.rx_focus_state == BTIF_A2DP_SINK_FOCUS_GRANTED) {
    btif_a2dp_sink_cb.rx_flush = false;
  }
}

void btif_a2dp_sink_set_audio_track_gain(float gain) {
  log::debug("set gain to {:f}", gain);
  LockGuard lock(g_mutex);

#ifdef __ANDROID__
  BtifAvrcpSetAudioTrackGain(btif_a2dp_sink_cb.audio_track, gain);
#endif
}

void* btif_a2dp_sink_get_audio_track(void) { return btif_a2dp_sink_cb.audio_track; }

static void btif_a2dp_sink_clear_track_event_req() {
  log::info("");
  BT_HDR_RIGID* p_buf = reinterpret_cast<BT_HDR_RIGID*>(osi_malloc(sizeof(BT_HDR_RIGID)));

  p_buf->event = BTIF_MEDIA_SINK_CLEAR_TRACK;
  btif_a2dp_sink_cb.worker_thread.DoInThread(base::BindOnce(btif_a2dp_sink_command_ready, p_buf));
}

static void btif_a2dp_sink_on_start_event() {
  log::info("");

  if ((btif_a2dp_sink_cb.decoder_interface != nullptr) &&
      (btif_a2dp_sink_cb.decoder_interface->decoder_start != nullptr)) {
    btif_a2dp_sink_cb.decoder_interface->decoder_start();
  }

  return;
}

static void btif_a2dp_sink_on_suspend_event() {
  log::info("");

  if ((btif_a2dp_sink_cb.decoder_interface != nullptr) &&
      (btif_a2dp_sink_cb.decoder_interface->decoder_suspend != nullptr)) {
    btif_a2dp_sink_cb.decoder_interface->decoder_suspend();
  }

  return;
}
