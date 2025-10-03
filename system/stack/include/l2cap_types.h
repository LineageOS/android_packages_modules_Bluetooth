/*
 * Copyright 2024 The Android Open Source Project
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
#include <stdbool.h>

#include <cstdint>

#include "stack/include/hcidefs.h"
#include "stack/include/l2cdefs.h"

/* Validity check for PSM.  PSM values must be odd.  Also, all PSM values must
 * be assigned such that the least significant bit of the most sigificant
 * octet equals zero.
 */
#define L2C_INVALID_PSM(psm) (((psm) & 0x0101) != 0x0001)
#define L2C_IS_VALID_PSM(psm) (((psm) & 0x0101) == 0x0001)
#define L2C_IS_VALID_LE_PSM(psm) (((psm) > 0x0000) && ((psm) < 0x0100))

/* Define the minimum offset that L2CAP needs in a buffer. This is made up of
 * HCI type(1), len(2), handle(2), L2CAP len(2) and CID(2) => 9
 */
#define L2CAP_MIN_OFFSET 13 /* plus control(2), SDU length(2) */

#define L2CAP_LCC_SDU_LENGTH 2
#define L2CAP_LCC_OFFSET (L2CAP_MIN_OFFSET + L2CAP_LCC_SDU_LENGTH) /* plus SDU length(2) */

#define L2CAP_FCS_LENGTH 2

/*****************************************************************************
 *  Type Definitions
 ****************************************************************************/

/* Define the structure that applications use to create or accept
 * connections with enhanced retransmission mode.
 */
struct tL2CAP_ERTM_INFO {
  uint8_t preferred_mode;
};

/* Values for priority parameter to L2CA_SetAclPriority */
enum tL2CAP_PRIORITY : uint8_t {
  L2CAP_PRIORITY_NORMAL = 0,
  L2CAP_PRIORITY_HIGH = 1,
};

/* Values for priority parameter to L2CA_SetAclLatency */
enum tL2CAP_LATENCY : uint8_t {
  L2CAP_LATENCY_NORMAL = 0,
  L2CAP_LATENCY_LOW = 1,
};

#define L2CAP_NO_IDLE_TIMEOUT 0xFFFF

/* L2CA_FlushChannel num_to_flush definitions */
#define L2CAP_FLUSH_CHANS_ALL 0xffff
#define L2CAP_FLUSH_CHANS_GET 0x0000

/* Values for priority parameter to L2CA_SetTxPriority */
#define L2CAP_CHNL_PRIORITY_HIGH 0
#define L2CAP_CHNL_PRIORITY_LOW 2

typedef uint8_t tL2CAP_CHNL_PRIORITY;

typedef struct {
#define L2CAP_FCR_BASIC_MODE 0x00
#define L2CAP_FCR_ERTM_MODE 0x03
#define L2CAP_FCR_LE_COC_MODE 0x05

  uint8_t mode;

  uint8_t tx_win_sz;
  uint8_t max_transmit;
  uint16_t rtrans_tout;
  uint16_t mon_tout;
  uint16_t mps;
} tL2CAP_FCR_OPTS;

/* default options for ERTM mode */
constexpr tL2CAP_FCR_OPTS kDefaultErtmOptions = {
        L2CAP_FCR_ERTM_MODE,
        10,    /* Tx window size */
        20,    /* Maximum transmissions before disconnecting */
        2000,  /* Retransmission timeout (2 secs) */
        12000, /* Monitor timeout (12 secs) */
        1010   /* MPS segment size */
};

typedef struct {
  uint8_t qos_flags;          /* TBD */
  uint8_t service_type;       /* see below */
  uint32_t token_rate;        /* bytes/second */
  uint32_t token_bucket_size; /* bytes */
  uint32_t peak_bandwidth;    /* bytes/second */
  uint32_t latency;           /* microseconds */
  uint32_t delay_variation;   /* microseconds */
} FLOW_SPEC;

/* Define a structure to hold the configuration parameters. Since the
 * parameters are optional, for each parameter there is a boolean to
 * use to signify its presence or absence.
 */
typedef struct {
  tL2CAP_CFG_RESULT result; /* Only used in confirm messages */
  bool mtu_present;
  uint16_t mtu;
  bool qos_present;
  FLOW_SPEC qos;
  bool flush_to_present;
  uint16_t flush_to;
  bool fcr_present;
  tL2CAP_FCR_OPTS fcr;
  bool fcs_present; /* Optionally bypasses FCS checks */
  uint8_t fcs;      /* '0' if desire is to bypass FCS, otherwise '1' */
  bool ext_flow_spec_present;
  tHCI_EXT_FLOW_SPEC ext_flow_spec;
  bool init_credit_present;
  uint16_t init_credit;
  uint16_t flags; /* bit 0: 0-no continuation, 1-continuation */
  int lecoc_fixed_psm_slots;
  bool lecoc_assigned_psm;
} tL2CAP_CFG_INFO;

/* Define a structure to hold the configuration parameter for LE L2CAP
 * connection oriented channels.
 */
constexpr uint16_t kDefaultL2capMtu = 100;
constexpr uint16_t kDefaultL2capMps = 100;

// This is initial amount of credits we send, and amount to which we increase
// credits once they fall below threshold
uint16_t L2CA_LeCreditDefault();

// If credit count on remote fall below this value, we send back credits to
// reach default value.
uint16_t L2CA_LeCreditThreshold();

// Max number of CIDs in the L2CAP CREDIT BASED CONNECTION REQUEST
constexpr uint8_t L2CAP_CREDIT_BASED_MAX_CIDS = 5;

struct tL2CAP_LE_CFG_INFO {
  tL2CAP_CFG_RESULT result{tL2CAP_CFG_RESULT::L2CAP_CFG_OK}; /* Only used in confirm messages */
  uint16_t mtu{kDefaultL2capMtu};
  uint16_t mps{kDefaultL2capMps};
  uint16_t credits{L2CA_LeCreditDefault()};
  uint8_t number_of_channels{L2CAP_CREDIT_BASED_MAX_CIDS};
  uint8_t lecoc_fixed_psm_slots{0};
  bool lecoc_assigned_psm{false};
};

/* LE credit based L2CAP connection parameters */
constexpr uint16_t L2CAP_LE_MIN_MTU = 23;  // Minimum SDU size
constexpr uint16_t L2CAP_LE_MIN_MPS = 23;
constexpr uint16_t L2CAP_LE_MAX_MPS = 65533;
constexpr uint16_t L2CAP_LE_CREDIT_MAX = 65535;

namespace std {
template <>
struct formatter<tL2CAP_LATENCY> : enum_formatter<tL2CAP_LATENCY> {};
template <>
struct formatter<tL2CAP_PRIORITY> : enum_formatter<tL2CAP_PRIORITY> {};
}  // namespace std
