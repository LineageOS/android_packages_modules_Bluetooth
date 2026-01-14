// Copyright (C) 2025, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#![allow(non_camel_case_types, non_snake_case, non_upper_case_globals, unused_assignments)]
use lhdcv5::lhdc_api::*;
use log::{error, info};

pub type __LHDC_ABR_TYPE__ = libc::c_uint;
pub const LHDC_AUTOBITRATE_ADJTABLE_COUNT: u32 = 6;

const ABR_MAX_STAGE_BITRATE: u32 = 500;
const ABR_UP_RATE_TIME_CNT: u32 = 5;
const ABR_UP_QUEUE_LENGTH_THRESHOLD: u32 = 1;
const ABR_DOWN_RATE_TIME_CNT: u32 = 5;
const ABR_DOWN_QUEUE_LENGTH_THRESHOLD: u32 = 0;
const ABR_DOWN_TARGET_STAGE: u32 = 0;

pub struct LHDC_ABR_Para_T {
    abr_table: Vec<u32>,
    gABR_table_index: u32,
    down_bitrate_count: u32,
    down_bitrate_sum: u32,
    up_bitrate_count: u32,
    up_bitrate_sum: u32,
}

pub struct AutoBitRate<'a> {
    pub handle: &'a mut Context,
    #[allow(dead_code)]
    pub table_index: usize,
}

pub struct LHDC_ABR {
    pub auto_bitrate_adjust_table_lhdc_44k: [u32; 6],
    pub auto_bitrate_adjust_table_lhdc_48k: [u32; 6],
    pub auto_bitrate_adjust_table_lhdc_96k: [u32; 6],
    pub auto_bitrate_adjust_table_lhdc_192k: [u32; 6],
    pub handle_abr: LHDC_ABR_Para_T,
}

impl LHDC_ABR {
    pub fn new() -> Self {
        LHDC_ABR {
            auto_bitrate_adjust_table_lhdc_44k: [160, 192, 240, 320, 400, 480],
            auto_bitrate_adjust_table_lhdc_48k: [160, 192, 256, 320, 400, ABR_MAX_STAGE_BITRATE],
            auto_bitrate_adjust_table_lhdc_96k: [256, 320, 400, 400, 400, ABR_MAX_STAGE_BITRATE],
            auto_bitrate_adjust_table_lhdc_192k: [256, 320, 400, 400, 400, ABR_MAX_STAGE_BITRATE],
            handle_abr: LHDC_ABR_Para_T {
                abr_table: vec![],
                gABR_table_index: 0,
                down_bitrate_count: 0,
                down_bitrate_sum: 0,
                up_bitrate_count: 0,
                up_bitrate_sum: 0,
            },
        }
    }

    fn lhdc_enc_abr_adjust_bitrate(&mut self, abr: &mut AutoBitRate, queueLen: u32) -> i32 {
        let mut last_bitrate: u32 = 0;
        let mut last_bitrate_inx: u32 = 0;
        let mut new_abr_bitrate_inx: u32 = 0;
        let mut new_bitrate: u32 = 0;
        let mut new_bitrate_inx: u32 = 0;
        let upd_qual_status: bool = false;
        let mut queueLength: u32 = 0;
        let mut queueSumTmp: u32 = 0;

        if self.handle_abr.down_bitrate_count >= ABR_DOWN_RATE_TIME_CNT {
            queueLength = self.handle_abr.down_bitrate_sum / self.handle_abr.down_bitrate_count;

            // clean ABR down statistics parameters
            self.handle_abr.down_bitrate_count = 0;
            self.handle_abr.down_bitrate_sum = 0;

            if queueLength > ABR_DOWN_QUEUE_LENGTH_THRESHOLD {
                last_bitrate = abr.handle.last_bitrate();
                last_bitrate_inx = match abr.handle.get_target_bitrate_inx(last_bitrate) {
                    Ok(inx) => inx,
                    Err(err) => {
                        error!(
                            "[AUTO_BITRATE][ABR_ADJ](DN) lhdc_get_bitrate_index error {:?}",
                            err
                        );
                        return LHDC_FRET_ERROR;
                    }
                };

                // configure new target bitrate
                new_abr_bitrate_inx = ABR_DOWN_TARGET_STAGE;
                if new_abr_bitrate_inx >= self.handle_abr.gABR_table_index {
                    new_abr_bitrate_inx = self.handle_abr.gABR_table_index
                }
                new_bitrate = self.handle_abr.abr_table[new_abr_bitrate_inx as usize];

                new_bitrate_inx = match abr.handle.get_target_bitrate_inx(new_bitrate) {
                    Ok(inx) => inx,
                    Err(err) => {
                        error!(
                            "[AUTO_BITRATE][ABR_ADJ](DN) lhdc_get_bitrate_index error {:?}",
                            err
                        );
                        return LHDC_FRET_ERROR;
                    }
                };

                info!(
                    "[AUTO_BITRATE][ABR_ADJ](DN) last_bitrate:{} new_bitrate:{}",
                    last_bitrate, new_bitrate
                );
                info!(
                    "[AUTO_BITRATE][ABR_ADJ](DN) last_bitrate_inx:{} new_bitrate_inx:{}",
                    last_bitrate_inx, new_bitrate_inx
                );
                info!(
                    "[AUTO_BITRATE][ABR_ADJ](DN) gABR_table_index:{} new_abr_bitrate_inx:{}",
                    self.handle_abr.gABR_table_index, new_abr_bitrate_inx
                );

                // check if need to downtier bitrate
                if (new_bitrate_inx <= last_bitrate_inx)
                    && (new_abr_bitrate_inx < self.handle_abr.gABR_table_index)
                {
                    let func_ret =
                        abr.handle.set_target_bitrate_inx(new_bitrate_inx, upd_qual_status);
                    if func_ret.is_err() {
                        error!(
                            "[AUTO_BITRATE][ABR_ADJ](DN) lhdc_set_bitrate_index error {:?}",
                            func_ret
                        );
                        return LHDC_FRET_ERROR;
                    }

                    info!(
                        "[AUTO_BITRATE][ABR_ADJ](DN) br_table[{}]({}) to br_table[{}][{}]",
                        self.handle_abr.gABR_table_index,
                        self.handle_abr.abr_table[self.handle_abr.gABR_table_index as usize],
                        new_abr_bitrate_inx,
                        self.handle_abr.abr_table[new_abr_bitrate_inx as usize]
                    );

                    // clean ABR up statistics parameters
                    self.handle_abr.up_bitrate_count = 0;
                    self.handle_abr.up_bitrate_sum = 0;
                    self.handle_abr.gABR_table_index = new_abr_bitrate_inx;
                } else {
                    info!(
                        "[AUTO_BITRATE][ABR_ADJ](DN) bitrate not changed ({})[{}]",
                        last_bitrate, self.handle_abr.gABR_table_index
                    );
                    self.handle_abr.gABR_table_index = new_abr_bitrate_inx
                }
            } else {
                // Do nothing (not meet the down-bitrate condition)
            }
        }
        if self.handle_abr.up_bitrate_count >= ABR_UP_RATE_TIME_CNT {
            queueSumTmp = self.handle_abr.up_bitrate_sum;

            // clean ABR up statistics parameters
            self.handle_abr.up_bitrate_count = 0;
            self.handle_abr.up_bitrate_sum = 0;

            if queueSumTmp < ABR_UP_QUEUE_LENGTH_THRESHOLD {
                // get last bitrate and index
                last_bitrate = abr.handle.last_bitrate();
                last_bitrate_inx = match abr.handle.get_target_bitrate_inx(last_bitrate) {
                    Ok(inx) => inx,
                    Err(err) => {
                        error!(
                            "[AUTO_BITRATE][ABR_ADJ](UP) lhdc_get_bitrate_index error {:?}",
                            err
                        );
                        return LHDC_FRET_ERROR;
                    }
                };

                // configure new target bitrate and index
                if self.handle_abr.gABR_table_index < (LHDC_AUTOBITRATE_ADJTABLE_COUNT - 1) {
                    new_abr_bitrate_inx = self.handle_abr.gABR_table_index + 1;
                } else {
                    new_abr_bitrate_inx = self.handle_abr.gABR_table_index
                }

                new_bitrate = self.handle_abr.abr_table[new_abr_bitrate_inx as usize];
                new_bitrate_inx = match abr.handle.get_target_bitrate_inx(new_bitrate) {
                    Ok(inx) => inx,
                    Err(err) => {
                        error!(
                            "[AUTO_BITRATE][ABR_ADJ](UP) lhdc_get_bitrate_index error {:?}",
                            err
                        );
                        return LHDC_FRET_ERROR;
                    }
                };

                info!(
                    "[AUTO_BITRATE][ABR_ADJ](UP) last_bitrate:{} new_bitrate:{}",
                    last_bitrate, new_bitrate
                );
                info!(
                    "[AUTO_BITRATE][ABR_ADJ](UP) last_bitrate_inx:{} new_bitrate_inx:{}",
                    last_bitrate_inx, new_bitrate_inx
                );
                info!(
                    "[AUTO_BITRATE][ABR_ADJ](UP) gABR_table_index:{} new_abr_bitrate_inx:{}",
                    self.handle_abr.gABR_table_index, new_abr_bitrate_inx
                );

                // check if need to uptier bitrate
                if (new_bitrate_inx > last_bitrate_inx)
                    && (new_abr_bitrate_inx > self.handle_abr.gABR_table_index)
                {
                    let func_ret =
                        abr.handle.set_target_bitrate_inx(new_bitrate_inx, upd_qual_status);
                    if func_ret.is_err() {
                        error!(
                            "[AUTO_BITRATE][ABR_ADJ](UP) lhdc_set_bitrate_index error {:?}",
                            func_ret
                        );
                        return LHDC_FRET_ERROR;
                    }
                    info!(
                        "[AUTO_BITRATE][ABR_ADJ](UP) br_table[{}]({}) to br_table[{}][{}]",
                        self.handle_abr.gABR_table_index,
                        self.handle_abr.abr_table[self.handle_abr.gABR_table_index as usize],
                        new_abr_bitrate_inx,
                        self.handle_abr.abr_table[new_abr_bitrate_inx as usize]
                    );

                    // clean ABR down statistics parameters
                    self.handle_abr.down_bitrate_count = 0;
                    self.handle_abr.down_bitrate_sum = 0;
                    self.handle_abr.gABR_table_index = new_abr_bitrate_inx;
                } else {
                    info!(
                        "[AUTO_BITRATE][ABR_ADJ](UP) bitrate not changed ({})[{}]",
                        last_bitrate, self.handle_abr.gABR_table_index
                    );
                    self.handle_abr.gABR_table_index = new_abr_bitrate_inx
                }
            } else {
                // Do nothing (not meet the up-bitrate condition)
            }
        }
        if queueLen > 0 {
            self.handle_abr.up_bitrate_sum += queueLen;
            self.handle_abr.down_bitrate_sum += queueLen;
        }

        self.handle_abr.up_bitrate_count += 1;
        self.handle_abr.down_bitrate_count += 1;

        LHDC_FRET_SUCCESS
    }

    pub fn lhdcBT_autoBR_reset_abr_index(&mut self) -> i32 {
        self.handle_abr.gABR_table_index = LHDC_AUTOBITRATE_ADJTABLE_COUNT - 1;
        LHDC_FRET_SUCCESS
    }

    pub fn lhdcBT_autoBR_adjust_bitrate_process(
        &mut self,
        abr: &mut AutoBitRate,
        queue_len: u32,
    ) -> i32 {
        let mut func_ret: i32 = LHDC_FRET_ERROR;
        // get current quality status (lhdc bitrate operation mode)
        let quality_status = abr.handle.quality_status();

        if quality_status != LHDC_QUALITY_AUTO {
            error!("quality_status is not auto bitrate mode");
            return LHDC_FRET_ERROR;
        }

        // lossy-only mode: ABR
        func_ret = self.lhdc_enc_abr_adjust_bitrate(abr, queue_len);

        if func_ret != LHDC_FRET_SUCCESS {
            error!("Failed to adjust auto bit rate ({func_ret})!");
            return LHDC_FRET_ERROR;
        }

        LHDC_FRET_SUCCESS
    }

    pub fn lhdcBT_autoBR_adjust_bitrate_init(&mut self, sample_rate: u32) -> i32 {
        match sample_rate {
            44100 => self.handle_abr.abr_table = self.auto_bitrate_adjust_table_lhdc_44k.to_vec(),
            48000 => self.handle_abr.abr_table = self.auto_bitrate_adjust_table_lhdc_48k.to_vec(),
            96000 => self.handle_abr.abr_table = self.auto_bitrate_adjust_table_lhdc_96k.to_vec(),
            192000 => self.handle_abr.abr_table = self.auto_bitrate_adjust_table_lhdc_192k.to_vec(),
            _ => {
                error!("Sample rate is invalid {}!", sample_rate);
                return LHDC_FRET_INVALID_HANDLE_PARA;
            }
        }
        self.handle_abr.gABR_table_index = LHDC_AUTOBITRATE_ADJTABLE_COUNT - 1;
        self.handle_abr.down_bitrate_count = 0;
        self.handle_abr.down_bitrate_sum = 0;
        self.handle_abr.up_bitrate_count = 0;
        self.handle_abr.up_bitrate_sum = 0;
        LHDC_FRET_SUCCESS
    }
}
