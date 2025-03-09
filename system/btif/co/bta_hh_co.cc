/******************************************************************************
 *
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

#include "bta_hh_co.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <fcntl.h>
#include <linux/hid.h>
#include <linux/input.h>
#include <linux/uhid.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "bta_hh_api.h"
#include "btif_config.h"
#include "btif_hh.h"
#include "hardware/bt_hh.h"
#include "hci/controller_interface.h"
#include "main/shim/entry.h"
#include "osi/include/alarm.h"
#include "osi/include/allocator.h"
#include "osi/include/compat.h"
#include "osi/include/fixed_queue.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"
#include "storage/config_keys.h"
#include "types/raw_address.h"

#define BTA_HH_NV_LOAD_MAX 16
static tBTA_HH_RPT_CACHE_ENTRY sReportCache[BTA_HH_NV_LOAD_MAX];
#define BTA_HH_CACHE_REPORT_VERSION 1
#define THREAD_NORMAL_PRIORITY 0
#define BT_HH_THREAD_PREFIX "bt_hh_"
/* Max number of polling interrupt allowed */
#define BTA_HH_UHID_INTERRUPT_COUNT_MAX 100
/* Disconnect if UHID isn't ready after this many milliseconds. */
#define BTA_HH_UHID_READY_DISCONN_TIMEOUT_MS 10000
#define BTA_HH_UHID_READY_SHORT_DISCONN_TIMEOUT_MS 2000

using namespace bluetooth;

static constexpr char kDevPath[] = "/dev/uhid";
static constexpr char kPropertyWaitMsAfterUhidOpen[] = "bluetooth.hid.wait_ms_after_uhid_open";

static constexpr bthh_report_type_t map_rtype_uhid_hh[] = {BTHH_FEATURE_REPORT, BTHH_OUTPUT_REPORT,
                                                           BTHH_INPUT_REPORT};

static void* btif_hh_poll_event_thread(void* arg);
static bool to_uhid_thread(int fd, const tBTA_HH_TO_UHID_EVT* ev, size_t data_len);

static void uhid_set_non_blocking(int fd) {
  int opts = fcntl(fd, F_GETFL);
  if (opts < 0) {
    log::error("Getting flags failed ({})", strerror(errno));
  }

  opts |= O_NONBLOCK;

  if (fcntl(fd, F_SETFL, opts) < 0) {
    log::verbose("Setting non-blocking flag failed ({})", strerror(errno));
  }
}

static bool uhid_get_report_req_handler(btif_hh_uhid_t* p_uhid, struct uhid_get_report_req& req) {
  log::debug("Report type = {}, id = {}", req.rtype, req.rnum);

  if (req.rtype > UHID_INPUT_REPORT) {
    log::error("Invalid report type {}", req.rtype);
    return false;
  }

  if (p_uhid->get_rpt_id_queue == nullptr) {
    log::error("Queue is not initialized");
    return false;
  }

  uint32_t* context = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *context = req.id;

  if (!fixed_queue_try_enqueue(p_uhid->get_rpt_id_queue, (void*)context)) {
    osi_free(context);
    log::error("Queue is full, dropping event {}", req.id);
    return false;
  }

  btif_hh_getreport(p_uhid, map_rtype_uhid_hh[req.rtype], req.rnum, 0);
  return true;
}

#if ENABLE_UHID_SET_REPORT
static bool uhid_set_report_req_handler(btif_hh_uhid_t* p_uhid, struct uhid_set_report_req& req) {
  log::debug("Report type = {}, id = {}", req.rtype, req.rnum);

  if (req.rtype > UHID_INPUT_REPORT) {
    log::error("Invalid report type {}", req.rtype);
    return false;
  }

  if (p_uhid->set_rpt_id_queue == nullptr) {
    log::error("Queue is not initialized");
    return false;
  }

  uint32_t* context = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *context = req.id;

  if (!fixed_queue_try_enqueue(p_uhid->set_rpt_id_queue, (void*)context)) {
    osi_free(context);
    log::error("Queue is full, dropping event {}", req.id);
    return false;
  }

  btif_hh_setreport(p_uhid, map_rtype_uhid_hh[req.rtype], req.size, req.data);
  return true;
}
#endif  // ENABLE_UHID_SET_REPORT

/* Calculate the minimum length required to send message to UHID */
static size_t uhid_calc_msg_len(const struct uhid_event* ev, size_t var_len) {
  switch (ev->type) {
    // these messages don't have data following them, so just 4 bytes of type.
    case UHID_DESTROY:
    case UHID_STOP:
    case UHID_OPEN:
    case UHID_CLOSE:
      return sizeof(ev->type);
    // these messages has static length of data.
    case UHID_START:
      return sizeof(ev->type) + sizeof(ev->u.start);
    case UHID_OUTPUT:
      return sizeof(ev->type) + sizeof(ev->u.output);
    case UHID_GET_REPORT:
      return sizeof(ev->type) + sizeof(ev->u.get_report);
    case UHID_SET_REPORT_REPLY:
      return sizeof(ev->type) + sizeof(ev->u.set_report_reply);
    // these messages has variable amount of data. We only need to write the
    // necessary length.
    case UHID_CREATE2:
      return sizeof(ev->type) + sizeof(ev->u.create2) - HID_MAX_DESCRIPTOR_SIZE + var_len;
    case UHID_INPUT2:
      return sizeof(ev->type) + sizeof(ev->u.input2) - UHID_DATA_MAX + var_len;
    case UHID_GET_REPORT_REPLY:
      return sizeof(ev->type) + sizeof(ev->u.get_report_reply) - UHID_DATA_MAX + var_len;
    case UHID_SET_REPORT:
      return sizeof(ev->type) + sizeof(ev->u.set_report) - UHID_DATA_MAX + var_len;
    default:
      log::error("unknown uhid event type {}", ev->type);
      return 0;
  }
}

/*Internal function to perform UHID write and error checking*/
static int uhid_write(int fd, const struct uhid_event* ev, size_t len) {
  ssize_t ret;
  OSI_NO_INTR(ret = write(fd, ev, len));

  if (ret < 0) {
    int rtn = -errno;
    log::error("Cannot write to uhid:{}", strerror(errno));
    return rtn;
  } else if (ret != (ssize_t)len) {
    log::error("Wrong size written to uhid: {} != {}", ret, len);
    return -EFAULT;
  }

  return 0;
}

static void uhid_flush_input_queue(btif_hh_uhid_t* p_uhid) {
  struct uhid_event* p_ev = nullptr;
  while (true) {
    p_ev = (struct uhid_event*)fixed_queue_try_dequeue(p_uhid->input_queue);
    if (p_ev == nullptr) {
      break;
    }
    uhid_write(p_uhid->fd, p_ev, uhid_calc_msg_len(p_ev, p_ev->u.input2.size));
    osi_free(p_ev);
  }
}

static void uhid_set_ready(btif_hh_uhid_t* p_uhid) {
  if (p_uhid->ready_for_data) {
    return;
  }
  p_uhid->ready_for_data = true;
  uhid_flush_input_queue(p_uhid);
}

// This runs on main thread.
static void uhid_delayed_ready_cback(void* data) {
  int send_fd = PTR_TO_INT(data);
  tBTA_HH_TO_UHID_EVT ev = {};

  // Notify the UHID thread that the timer has expired.
  log::verbose("UHID delayed ready evt");
  ev.type = BTA_HH_UHID_INBOUND_READY_EVT;
  to_uhid_thread(send_fd, &ev, 0);
}

// This runs on main thread.
static void uhid_ready_disconn_timeout(void* data) {
  int dev_handle = PTR_TO_INT(data);

  log::verbose("UHID ready disconn timeout evt");
  BTA_HhClose(dev_handle);
}

static void uhid_on_open(btif_hh_uhid_t* p_uhid) {
  if (p_uhid->ready_for_data || alarm_is_scheduled(p_uhid->delayed_ready_timer)) {
    return;
  }

  if (com::android::bluetooth::flags::close_hid_if_uhid_ready_too_slow()) {
    if (alarm_is_scheduled(p_uhid->ready_disconn_timer)) {
      alarm_cancel(p_uhid->ready_disconn_timer);
    }
  }

  // On some platforms delay is required, because even though UHID has indicated
  // ready, the input events might still not be processed, and therefore lost.
  // If it's not required, immediately set UHID as ready.
  int ready_delay_ms = osi_property_get_int32(kPropertyWaitMsAfterUhidOpen, 0);
  if (ready_delay_ms == 0) {
    uhid_set_ready(p_uhid);
    return;
  }

  alarm_set_on_mloop(p_uhid->delayed_ready_timer, ready_delay_ms, uhid_delayed_ready_cback,
                     INT_TO_PTR(p_uhid->internal_send_fd));
}

static void uhid_queue_input(btif_hh_uhid_t* p_uhid, struct uhid_event* ev, size_t len) {
  struct uhid_event* p_ev = (struct uhid_event*)osi_malloc(len);
  if (!p_ev) {
    log::error("allocate uhid_event failed");
    return;
  }
  memcpy(p_ev, ev, len);

  if (!fixed_queue_try_enqueue(p_uhid->input_queue, (void*)p_ev)) {
    osi_free(p_ev);
    log::error("uhid_event_queue is full, dropping event");
  }
}

/* Parse the events received from UHID driver*/
static int uhid_read_outbound_event(btif_hh_uhid_t* p_uhid) {
  log::assert_that(p_uhid != nullptr, "assert failed: p_uhid != nullptr");

  struct uhid_event ev = {};
  ssize_t ret;
  OSI_NO_INTR(ret = read(p_uhid->fd, &ev, sizeof(ev)));

  if (ret == 0) {
    log::error("Read HUP on uhid-cdev {}", strerror(errno));
    return -EFAULT;
  } else if (ret < 0) {
    log::error("Cannot read uhid-cdev: {}", strerror(errno));
    return -errno;
  }

  switch (ev.type) {
    case UHID_START:
      log::verbose("UHID_START from uhid-dev\n");
      break;
    case UHID_STOP:
      log::verbose("UHID_STOP from uhid-dev\n");
      break;
    case UHID_OPEN:
      log::verbose("UHID_OPEN from uhid-dev\n");
      uhid_on_open(p_uhid);
      break;
    case UHID_CLOSE:
      log::verbose("UHID_CLOSE from uhid-dev\n");
      p_uhid->ready_for_data = false;
      if (alarm_is_scheduled(p_uhid->delayed_ready_timer)) {
        alarm_cancel(p_uhid->delayed_ready_timer);
      }
      if (com::android::bluetooth::flags::close_hid_if_uhid_ready_too_slow()) {
        // It's possible to get OPEN->CLOSE->OPEN sequence from UHID. Therefore, instead of
        // immediately disconnecting when receiving CLOSE, here we wait a while and will
        // disconnect if we don't receive OPEN before it times out.
        if (!alarm_is_scheduled(p_uhid->ready_disconn_timer)) {
          alarm_set_on_mloop(p_uhid->ready_disconn_timer,
                             BTA_HH_UHID_READY_SHORT_DISCONN_TIMEOUT_MS, uhid_ready_disconn_timeout,
                             INT_TO_PTR(p_uhid->dev_handle));
        }
      }
      break;
    case UHID_OUTPUT:
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.output))) {
        log::error("Invalid size read from uhid-dev: {} < {}", ret,
                   sizeof(ev.type) + sizeof(ev.u.output));
        return -EFAULT;
      }

      log::verbose("UHID_OUTPUT: Report type = {}, report_size = {}", ev.u.output.rtype,
                   ev.u.output.size);
      // Send SET_REPORT with feature report if the report type in output event
      // is FEATURE
      if (ev.u.output.rtype == UHID_FEATURE_REPORT) {
        btif_hh_setreport(p_uhid, BTHH_FEATURE_REPORT, ev.u.output.size, ev.u.output.data);
      } else if (ev.u.output.rtype == UHID_OUTPUT_REPORT) {
        btif_hh_senddata(p_uhid, ev.u.output.size, ev.u.output.data);
      } else {
        log::error("UHID_OUTPUT: Invalid report type = {}", ev.u.output.rtype);
      }
      break;

    case UHID_GET_REPORT:
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.get_report))) {
        log::error("UHID_GET_REPORT: Invalid size read from uhid-dev: {} < {}", ret,
                   sizeof(ev.type) + sizeof(ev.u.get_report));
        return -EFAULT;
      }

      if (!uhid_get_report_req_handler(p_uhid, ev.u.get_report)) {
        return -EFAULT;
      }

      break;

#if ENABLE_UHID_SET_REPORT
    case UHID_SET_REPORT: {
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.set_report))) {
        log::error("UHID_SET_REPORT: Invalid size read from uhid-dev: {} < {}", ret,
                   sizeof(ev.type) + sizeof(ev.u.set_report));
        return -EFAULT;
      }

      if (!uhid_set_report_req_handler(p_uhid, ev.u.set_report)) {
        return -EFAULT;
      }
      break;
    }
#endif  // ENABLE_UHID_SET_REPORT

    default:
      log::error("Invalid event from uhid-dev: {}\n", ev.type);
  }

  return 0;
}

// Parse the internal events received from BTIF and translate to UHID
// returns -errno when error, 0 when successful, 1 when receiving close event.
static int uhid_read_inbound_event(btif_hh_uhid_t* p_uhid) {
  log::assert_that(p_uhid != nullptr, "assert failed: p_uhid != nullptr");

  tBTA_HH_TO_UHID_EVT ev = {};
  ssize_t ret;
  OSI_NO_INTR(ret = read(p_uhid->internal_recv_fd, &ev, sizeof(ev)));

  if (ret == 0) {
    log::error("Read HUP on internal uhid-cdev {}", strerror(errno));
    return -EFAULT;
  } else if (ret < 0) {
    log::error("Cannot read internal uhid-cdev: {}", strerror(errno));
    return -errno;
  }

  int res = 0;
  uint32_t* context;
  switch (ev.type) {
    case BTA_HH_UHID_INBOUND_INPUT_EVT:
      if (p_uhid->ready_for_data) {
        res = uhid_write(p_uhid->fd, &ev.uhid, ret - 1);
      } else {
        uhid_queue_input(p_uhid, &ev.uhid, ret - 1);
      }
      break;
    case BTA_HH_UHID_INBOUND_READY_EVT:
      uhid_set_ready(p_uhid);
      break;
    case BTA_HH_UHID_INBOUND_CLOSE_EVT:
      res = 1;  // any positive value indicates a normal close event
      break;
    case BTA_HH_UHID_INBOUND_DSCP_EVT:
      res = uhid_write(p_uhid->fd, &ev.uhid, ret - 1);
      break;
    case BTA_HH_UHID_INBOUND_GET_REPORT_EVT:
      context = (uint32_t*)fixed_queue_try_dequeue(p_uhid->get_rpt_id_queue);
      if (context == nullptr) {
        log::warn("No pending UHID_GET_REPORT");
        break;
      }
      ev.uhid.u.get_report_reply.id = *context;
      res = uhid_write(p_uhid->fd, &ev.uhid, ret - 1);
      osi_free(context);
      break;
#if ENABLE_UHID_SET_REPORT
    case BTA_HH_UHID_INBOUND_SET_REPORT_EVT:
      context = (uint32_t*)fixed_queue_try_dequeue(p_uhid->set_rpt_id_queue);
      if (context == nullptr) {
        log::warn("No pending UHID_SET_REPORT");
        break;
      }
      ev.uhid.u.set_report_reply.id = *context;
      res = uhid_write(p_uhid->fd, &ev.uhid, ret - 1);
      osi_free(context);
      break;
#endif  // ENABLE_UHID_SET_REPORT
    default:
      log::error("Invalid event from internal uhid-dev: {}", (uint8_t)ev.type);
  }

  return res;
}

/*******************************************************************************
 *
 * Function create_thread
 *
 * Description creat a select loop
 *
 * Returns pthread_t
 *
 ******************************************************************************/
static inline pthread_t create_thread(void* (*start_routine)(void*), void* arg) {
  log::verbose("create_thread: entered");
  pthread_attr_t thread_attr;

  pthread_attr_init(&thread_attr);
  pthread_attr_setdetachstate(&thread_attr, PTHREAD_CREATE_JOINABLE);
  pthread_t thread_id = -1;
  if (pthread_create(&thread_id, &thread_attr, start_routine, arg) != 0) {
    log::error("pthread_create : {}", strerror(errno));
    return -1;
  }
  log::verbose("create_thread: thread created successfully");
  return thread_id;
}

/* Internal function to close the UHID driver*/
static void uhid_fd_close(btif_hh_uhid_t* p_uhid) {
  if (p_uhid->fd >= 0) {
    struct uhid_event ev = {};
    ev.type = UHID_DESTROY;
    uhid_write(p_uhid->fd, &ev, uhid_calc_msg_len(&ev, 0));
    log::debug("Closing fd={}, addr:{}", p_uhid->fd, p_uhid->link_spec);
    close(p_uhid->fd);
    p_uhid->fd = -1;
    close(p_uhid->internal_recv_fd);
    p_uhid->internal_recv_fd = -1;
    /* Clear the queues */
    fixed_queue_flush(p_uhid->get_rpt_id_queue, osi_free);
    fixed_queue_free(p_uhid->get_rpt_id_queue, NULL);
    p_uhid->get_rpt_id_queue = NULL;
#if ENABLE_UHID_SET_REPORT
    fixed_queue_flush(p_uhid->set_rpt_id_queue, osi_free);
    fixed_queue_free(p_uhid->set_rpt_id_queue, nullptr);
    p_uhid->set_rpt_id_queue = nullptr;
#endif  // ENABLE_UHID_SET_REPORT
    fixed_queue_flush(p_uhid->input_queue, osi_free);
    fixed_queue_free(p_uhid->input_queue, nullptr);
    p_uhid->input_queue = nullptr;

    alarm_free(p_uhid->delayed_ready_timer);
    alarm_free(p_uhid->ready_disconn_timer);
    osi_free(p_uhid);
  }
}

/* Internal function to open the UHID driver*/
static bool uhid_fd_open(btif_hh_device_t* p_dev) {
  if (p_dev->internal_send_fd < 0) {
    int sockets[2];
    if (socketpair(AF_LOCAL, SOCK_SEQPACKET | SOCK_NONBLOCK, 0, sockets) < 0) {
      return false;
    }

    btif_hh_uhid_t* uhid = (btif_hh_uhid_t*)osi_malloc(sizeof(btif_hh_uhid_t));
    uhid->link_spec = p_dev->link_spec;
    uhid->dev_handle = p_dev->dev_handle;
    uhid->internal_recv_fd = sockets[0];
    uhid->internal_send_fd = sockets[1];
    p_dev->internal_send_fd = sockets[1];

    // UHID thread owns the uhid struct and is responsible to free it.
    p_dev->hh_poll_thread_id = create_thread(btif_hh_poll_event_thread, uhid);
  }
  return true;
}

static int uhid_fd_poll(struct pollfd* pfds, int nfds) {
  int ret = 0;
  int counter = 0;

  do {
    if (counter++ > BTA_HH_UHID_INTERRUPT_COUNT_MAX) {
      log::error("Polling interrupted consecutively {} times", BTA_HH_UHID_INTERRUPT_COUNT_MAX);
      return -1;
    }
    ret = poll(pfds, nfds, -1);
  } while (ret == -1 && errno == EINTR);

  return ret;
}

static void uhid_start_polling(btif_hh_uhid_t* p_uhid) {
  std::array<struct pollfd, 2> pfds = {};
  pfds[0].fd = p_uhid->fd;
  pfds[0].events = POLLIN;
  pfds[1].fd = p_uhid->internal_recv_fd;
  pfds[1].events = POLLIN;

  while (true) {
    int ret = uhid_fd_poll(pfds.data(), 2);
    if (ret < 0) {
      log::error("Cannot poll for fds: {}\n", strerror(errno));
      break;
    }

    if (pfds[0].revents & POLLIN) {
      log::verbose("POLLIN");
      int result = uhid_read_outbound_event(p_uhid);
      if (result != 0) {
        log::error("Unhandled UHID outbound event, error: {}", result);
        break;
      }
    }

    if (pfds[1].revents & POLLIN) {
      int result = uhid_read_inbound_event(p_uhid);
      if (result != 0) {
        if (result < 0) {
          log::error("Unhandled UHID inbound event, error: {}", result);
        }
        break;
      }
    }

    if (pfds[1].revents & POLLHUP) {
      log::error("inbound fd hangup, disconnect UHID");
      break;
    }
  }
}

static bool uhid_configure_thread(btif_hh_uhid_t* p_uhid) {
  pid_t pid = gettid();
  // This thread is created by bt_main_thread with RT priority. Lower the thread
  // priority here since the tasks in this thread is not timing critical.
  struct sched_param sched_params;
  sched_params.sched_priority = THREAD_NORMAL_PRIORITY;
  if (sched_setscheduler(pid, SCHED_OTHER, &sched_params)) {
    log::error("Failed to set thread priority to normal: {}", strerror(errno));
    return false;
  }

  // Change the name of thread
  char thread_name[16] = {};
  sprintf(thread_name, BT_HH_THREAD_PREFIX "%02x:%02x", p_uhid->link_spec.addrt.bda.address[4],
          p_uhid->link_spec.addrt.bda.address[5]);
  pthread_setname_np(pthread_self(), thread_name);
  log::debug("Host hid polling thread created name:{} pid:{} fd:{}", thread_name, pid, p_uhid->fd);

  // Set the uhid fd as non-blocking to ensure we never block the BTU thread
  uhid_set_non_blocking(p_uhid->fd);

  return true;
}

/*******************************************************************************
 *
 * Function btif_hh_poll_event_thread
 *
 * Description the polling thread which polls for event from UHID driver
 *
 * Returns void
 *
 ******************************************************************************/
static void* btif_hh_poll_event_thread(void* arg) {
  btif_hh_uhid_t* p_uhid = (btif_hh_uhid_t*)arg;

  p_uhid->fd = open(kDevPath, O_RDWR | O_CLOEXEC);
  if (p_uhid->fd < 0) {
    log::error("Failed to open uhid, err:{}", strerror(errno));
    close(p_uhid->internal_recv_fd);
    p_uhid->internal_recv_fd = -1;
    return 0;
  }
  p_uhid->ready_for_data = false;
  p_uhid->delayed_ready_timer = alarm_new("uhid_delayed_ready_timer");
  p_uhid->ready_disconn_timer = alarm_new("uhid_ready_disconn_timer");
  if (com::android::bluetooth::flags::close_hid_if_uhid_ready_too_slow()) {
    alarm_set_on_mloop(p_uhid->ready_disconn_timer, BTA_HH_UHID_READY_DISCONN_TIMEOUT_MS,
                       uhid_ready_disconn_timeout, INT_TO_PTR(p_uhid->dev_handle));
  }

  p_uhid->get_rpt_id_queue = fixed_queue_new(SIZE_MAX);
  log::assert_that(p_uhid->get_rpt_id_queue, "assert failed: p_uhid->get_rpt_id_queue");
#if ENABLE_UHID_SET_REPORT
  p_uhid->set_rpt_id_queue = fixed_queue_new(SIZE_MAX);
  log::assert_that(p_uhid->set_rpt_id_queue, "assert failed: p_uhid->set_rpt_id_queue");
#endif  // ENABLE_UHID_SET_REPORT
  p_uhid->input_queue = fixed_queue_new(SIZE_MAX);
  log::assert_that(p_uhid->input_queue, "assert failed: p_uhid->input_queue");

  if (uhid_configure_thread(p_uhid)) {
    uhid_start_polling(p_uhid);
  }

  /* Todo: Disconnect if loop exited due to a failure */
  log::info("Polling thread stopped for device {}", p_uhid->link_spec);
  uhid_fd_close(p_uhid);
  return 0;
}

/* Pass messages to be handled by uhid_read_inbound_event in the UHID thread */
static bool to_uhid_thread(int fd, const tBTA_HH_TO_UHID_EVT* ev, size_t data_len) {
  if (fd < 0) {
    log::error("Cannot write to uhid thread: invalid fd");
    return false;
  }

  size_t len = data_len + sizeof(ev->type);
  ssize_t ret;
  OSI_NO_INTR(ret = write(fd, ev, len));

  if (ret < 0) {
    log::error("Cannot write to uhid thread: {}", strerror(errno));
    return false;
  } else if (ret != (ssize_t)len) {
    log::error("Wrong size written to uhid thread: {} != {}", ret, len);
    return false;
  }

  return true;
}

int bta_hh_co_write(int fd, uint8_t* rpt, uint16_t len) {
  log::verbose("UHID write {}", len);

  tBTA_HH_TO_UHID_EVT to_uhid = {};
  struct uhid_event& ev = to_uhid.uhid;
  ev.type = UHID_INPUT2;
  ev.u.input2.size = len;
  if (len > sizeof(ev.u.input2.data)) {
    log::warn("Report size greater than allowed size");
    return -1;
  }
  memcpy(ev.u.input2.data, rpt, len);

  size_t mlen = uhid_calc_msg_len(&ev, len);
  to_uhid.type = BTA_HH_UHID_INBOUND_INPUT_EVT;
  return to_uhid_thread(fd, &to_uhid, mlen) ? 0 : -1;
}

/*******************************************************************************
 *
 * Function      bta_hh_co_open
 *
 * Description   When connection is opened, this call-out function is executed
 *               by HH to do platform specific initialization.
 *
 * Returns       True if platform specific initialization is successful
 ******************************************************************************/
bool bta_hh_co_open(uint8_t dev_handle, uint8_t sub_class, tBTA_HH_ATTR_MASK attr_mask,
                    uint8_t app_id, tAclLinkSpec& link_spec) {
  bool new_device = false;

  if (dev_handle == BTA_HH_INVALID_HANDLE) {
    log::warn("dev_handle ({}) is invalid", dev_handle);
    return false;
  }

  // Reuse existing instance if possible
  btif_hh_device_t* p_dev = btif_hh_find_dev_by_handle(dev_handle);
  if (p_dev != nullptr) {
    log::info(
            "Found an existing device with the same handle dev_status={}, "
            "device={}, attr_mask=0x{:04x}, sub_class=0x{:02x}, app_id={}, "
            "dev_handle={}",
            p_dev->dev_status, p_dev->link_spec, p_dev->attr_mask, p_dev->sub_class, p_dev->app_id,
            dev_handle);
  } else {  // Use an empty slot
    p_dev = btif_hh_find_empty_dev();
    if (p_dev == nullptr) {
      log::error("Too many HID devices are connected");
      return false;
    }

    new_device = true;
    log::verbose("New HID device added for handle {}", dev_handle);

    p_dev->internal_send_fd = -1;
    p_dev->attr_mask = attr_mask;
    p_dev->sub_class = sub_class;
    p_dev->app_id = app_id;
    p_dev->local_vup = false;
  }

  p_dev->link_spec = link_spec;
  p_dev->dev_handle = dev_handle;

  if (!uhid_fd_open(p_dev)) {
    return false;
  }

  if (new_device) {
    btif_hh_cb.device_num++;
  }

  p_dev->dev_status = BTHH_CONN_STATE_CONNECTED;
  log::debug("Return device status {}", p_dev->dev_status);
  return true;
}

/*******************************************************************************
 *
 * Function      bta_hh_co_close
 *
 * Description   When connection is closed, this call-out function is executed
 *               by HH to do platform specific finalization.
 *
 * Parameters    p_dev  - device
 *
 * Returns       void.
 ******************************************************************************/
void bta_hh_co_close(btif_hh_device_t* p_dev) {
  log::info("Closing device handle={}, status={}, address={}", p_dev->dev_handle, p_dev->dev_status,
            p_dev->link_spec);

  if (p_dev->internal_send_fd >= 0) {
    tBTA_HH_TO_UHID_EVT to_uhid = {};
    to_uhid.type = BTA_HH_UHID_INBOUND_CLOSE_EVT;
    to_uhid_thread(p_dev->internal_send_fd, &to_uhid, 0);
    pthread_join(p_dev->hh_poll_thread_id, NULL);
    p_dev->hh_poll_thread_id = -1;

    close(p_dev->internal_send_fd);
    p_dev->internal_send_fd = -1;
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_co_data
 *
 * Description      This function is executed by BTA when HID host receive a
 *                  data report.
 *
 * Parameters       dev_handle  - device handle
 *                  *p_rpt      - pointer to the report data
 *                  len         - length of report data
 *
 * Returns          void
 ******************************************************************************/
void bta_hh_co_data(uint8_t dev_handle, uint8_t* p_rpt, uint16_t len) {
  btif_hh_device_t* p_dev;

  log::verbose("dev_handle = {}", dev_handle);

  p_dev = btif_hh_find_connected_dev_by_handle(dev_handle);
  if (p_dev == NULL) {
    log::warn("Error: unknown HID device handle {}", dev_handle);
    return;
  }

  bta_hh_co_write(p_dev->internal_send_fd, p_rpt, len);
}

/*******************************************************************************
 *
 * Function         bta_hh_co_send_hid_info
 *
 * Description      This function is called in btif_hh.c to process DSCP
 *                  received.
 *
 * Parameters       dev_handle  - device handle
 *                  dscp_len    - report descriptor length
 *                  *p_dscp     - report descriptor
 *
 * Returns          void
 ******************************************************************************/
void bta_hh_co_send_hid_info(btif_hh_device_t* p_dev, const char* dev_name, uint16_t vendor_id,
                             uint16_t product_id, uint16_t version, uint8_t ctry_code,
                             uint16_t dscp_len, uint8_t* p_dscp) {
  int result;
  tBTA_HH_TO_UHID_EVT to_uhid = {};
  struct uhid_event& ev = to_uhid.uhid;

  if (dscp_len > sizeof(ev.u.create2.rd_data)) {
    log::error("HID descriptor is too long: {}", dscp_len);
    return;
  }

  log::info(
          "vendor_id = 0x{:04x}, product_id = 0x{:04x}, version= "
          "0x{:04x},ctry_code=0x{:02x}",
          vendor_id, product_id, version, ctry_code);

  // Create and send hid descriptor to kernel
  ev.type = UHID_CREATE2;
  osi_strlcpy((char*)ev.u.create2.name, dev_name, sizeof(ev.u.create2.name));
  // TODO (b/258090765) fix: ToString -> ToColonSepHexString
  snprintf((char*)ev.u.create2.uniq, sizeof(ev.u.create2.uniq), "%s",
           p_dev->link_spec.addrt.bda.ToString().c_str());

  // Write controller address to phys field to correlate the hid device with a
  // specific bluetooth controller.
  auto controller = bluetooth::shim::GetController();
  // TODO (b/258090765) fix: ToString -> ToColonSepHexString
  snprintf((char*)ev.u.create2.phys, sizeof(ev.u.create2.phys), "%s",
           controller->GetMacAddress().ToString().c_str());

  ev.u.create2.rd_size = dscp_len;
  memcpy(ev.u.create2.rd_data, p_dscp, dscp_len);
  ev.u.create2.bus = BUS_BLUETOOTH;
  ev.u.create2.vendor = vendor_id;
  ev.u.create2.product = product_id;
  ev.u.create2.version = version;
  ev.u.create2.country = ctry_code;

  size_t mlen = uhid_calc_msg_len(&ev, dscp_len);
  to_uhid.type = BTA_HH_UHID_INBOUND_DSCP_EVT;
  if (!to_uhid_thread(p_dev->internal_send_fd, &to_uhid, mlen)) {
    log::warn("Error: failed to send DSCP");
    if (p_dev->internal_send_fd >= 0) {
      // Detach the uhid thread. It will exit by itself upon receiving hangup.
      pthread_detach(p_dev->hh_poll_thread_id);
      p_dev->hh_poll_thread_id = -1;
      close(p_dev->internal_send_fd);
      p_dev->internal_send_fd = -1;
    }
  }

  return;
}

/*******************************************************************************
 *
 * Function         bta_hh_co_set_rpt_rsp
 *
 * Description      This callout function is executed by HH when Set Report
 *                  Response is received on Control Channel.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_set_rpt_rsp([[maybe_unused]] uint8_t dev_handle, [[maybe_unused]] uint8_t status) {
#if ENABLE_UHID_SET_REPORT
  log::verbose("dev_handle = {}", dev_handle);

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dev_handle);
  if (p_dev == nullptr) {
    log::warn("Unknown HID device handle {}", dev_handle);
    return;
  }

  tBTA_HH_TO_UHID_EVT to_uhid = {};
  to_uhid.type = BTA_HH_UHID_INBOUND_SET_REPORT_EVT;
  to_uhid.uhid.type = UHID_SET_REPORT_REPLY;
  to_uhid.uhid.u.set_report_reply.err = status;

  to_uhid_thread(p_dev->internal_send_fd, &to_uhid, uhid_calc_msg_len(&to_uhid.uhid, 0));
  return;
#else
  log::error("UHID_SET_REPORT_REPLY not supported");
#endif  // ENABLE_UHID_SET_REPORT
}

/*******************************************************************************
 *
 * Function         bta_hh_co_get_rpt_rsp
 *
 * Description      This callout function is executed by HH when Get Report
 *                  Response is received on Control Channel.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_get_rpt_rsp(uint8_t dev_handle, uint8_t status, const uint8_t* p_rpt, uint16_t len) {
  btif_hh_device_t* p_dev;

  log::verbose("dev_handle = {}, status = {}", dev_handle, status);

  p_dev = btif_hh_find_connected_dev_by_handle(dev_handle);
  if (p_dev == nullptr) {
    log::warn("Unknown HID device handle {}", dev_handle);
    return;
  }

  // len of zero is allowed, it's possible on failure case.
  if (len > UHID_DATA_MAX) {
    log::warn("Invalid report size = {}", len);
    return;
  }

  tBTA_HH_TO_UHID_EVT to_uhid = {};
  to_uhid.type = BTA_HH_UHID_INBOUND_GET_REPORT_EVT;
  to_uhid.uhid.type = UHID_GET_REPORT_REPLY;
  to_uhid.uhid.u.get_report_reply.err = status;
  to_uhid.uhid.u.get_report_reply.size = len;
  if (len > 0) {
    memcpy(to_uhid.uhid.u.get_report_reply.data, p_rpt, len);
  }

  to_uhid_thread(p_dev->internal_send_fd, &to_uhid, uhid_calc_msg_len(&to_uhid.uhid, len));
}

/*******************************************************************************
 *
 * Function         bta_hh_le_co_rpt_info
 *
 * Description      This callout function is to convey the report information on
 *                  a HOGP device to the application. Application can save this
 *                  information in NV if device is bonded and load it back when
 *                  stack reboot.
 *
 * Parameters       link_spec   - ACL link specification
 *                  p_entry     - report entry pointer
 *                  app_id      - application id
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_le_co_rpt_info(const tAclLinkSpec& link_spec, tBTA_HH_RPT_CACHE_ENTRY* p_entry,
                           uint8_t /* app_id */) {
  unsigned idx = 0;

  std::string addrstr = link_spec.addrt.bda.ToString();
  const char* bdstr = addrstr.c_str();

  size_t len = btif_config_get_bin_length(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT);
  if (len >= sizeof(tBTA_HH_RPT_CACHE_ENTRY) && len <= sizeof(sReportCache)) {
    btif_config_get_bin(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT, (uint8_t*)sReportCache, &len);
    idx = len / sizeof(tBTA_HH_RPT_CACHE_ENTRY);
  }

  if (idx < BTA_HH_NV_LOAD_MAX) {
    memcpy(&sReportCache[idx++], p_entry, sizeof(tBTA_HH_RPT_CACHE_ENTRY));
    btif_config_set_bin(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT, (const uint8_t*)sReportCache,
                        idx * sizeof(tBTA_HH_RPT_CACHE_ENTRY));
    btif_config_set_int(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT_VERSION, BTA_HH_CACHE_REPORT_VERSION);
    log::verbose("Saving report; dev={}, idx={}", link_spec, idx);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_le_co_cache_load
 *
 * Description      This callout function is to request the application to load
 *                  the cached HOGP report if there is any. When cache reading
 *                  is completed, bta_hh_le_co_cache_load() is called by the
 *                  application.
 *
 * Parameters       link_spec   - ACL link specification
 *                  p_num_rpt   - number of cached report
 *                  app_id      - application id
 *
 * Returns          the cached report array
 *
 ******************************************************************************/
tBTA_HH_RPT_CACHE_ENTRY* bta_hh_le_co_cache_load(const tAclLinkSpec& link_spec, uint8_t* p_num_rpt,
                                                 uint8_t app_id) {
  std::string addrstr = link_spec.addrt.bda.ToString();
  const char* bdstr = addrstr.c_str();

  size_t len = btif_config_get_bin_length(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT);
  if (!p_num_rpt || len < sizeof(tBTA_HH_RPT_CACHE_ENTRY)) {
    return NULL;
  }

  if (len > sizeof(sReportCache)) {
    len = sizeof(sReportCache);
  }
  btif_config_get_bin(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT, (uint8_t*)sReportCache, &len);

  int cache_version = -1;
  btif_config_get_int(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT_VERSION, &cache_version);

  if (cache_version != BTA_HH_CACHE_REPORT_VERSION) {
    bta_hh_le_co_reset_rpt_cache(link_spec, app_id);
    return NULL;
  }

  *p_num_rpt = len / sizeof(tBTA_HH_RPT_CACHE_ENTRY);

  log::verbose("Loaded {} reports; dev={}", *p_num_rpt, link_spec);

  return sReportCache;
}

/*******************************************************************************
 *
 * Function         bta_hh_le_co_reset_rpt_cache
 *
 * Description      This callout function is to reset the HOGP device cache.
 *
 * Parameters       link_spec  - ACL link specification
 *
 * Returns          none
 *
 ******************************************************************************/
void bta_hh_le_co_reset_rpt_cache(const tAclLinkSpec& link_spec, uint8_t /* app_id */) {
  std::string addrstr = link_spec.addrt.bda.ToString();
  const char* bdstr = addrstr.c_str();

  btif_config_remove(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT);
  btif_config_remove(bdstr, BTIF_STORAGE_KEY_HOGP_REPORT_VERSION);
  log::verbose("Reset cache for bda {}", link_spec);
}
