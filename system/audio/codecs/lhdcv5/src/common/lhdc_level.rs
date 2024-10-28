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

use crate::math;

fn gmax_calc(
    a: libc::c_float,
    b: libc::c_float,
    c: libc::c_float,
    d: libc::c_int,
    len: libc::c_int,
    e: libc::c_int,
) -> libc::c_float {
    a - (b - c) / d as libc::c_float * (len - e) as libc::c_float
}

const OFFSET_MAX: i32 = (1 << 9) - 8;

pub struct LevelTable {
    cache: [libc::c_float; 512],
    // TODO(b/454096420) make non-pub
    pub start: libc::c_int,
    jump: libc::c_float,
}

impl Default for LevelTable {
    fn default() -> Self {
        Self::new()
    }
}

impl LevelTable {
    /// Constructs a zeroed `LevelTable`
    ///
    /// Results are not meaningful until `init` is called.
    pub fn new() -> Self {
        Self { cache: [0.0; 512], start: 0, jump: 0.0 }
    }

    pub fn level(&mut self, offset_idx: i32) -> f32 {
        if self.cache[offset_idx as usize] != 0.0 {
            return self.cache[offset_idx as usize];
        }
        if offset_idx > self.start {
            self.cache[offset_idx as usize] = 1.0f32 / math::power_of_2(self.calc(offset_idx));
        } else {
            self.cache[offset_idx as usize] = 1.0f32 / (1_i32 << offset_idx) as f32;
        };
        self.cache[offset_idx as usize]
    }

    /// Computes a function of `offset_idx` with:
    /// * Slope 1 from 0 to `start`
    /// * Slope offset_jump from `start` to `OFFSET_MAX`
    /// * Slope 1 thereafter
    /// * A maximum of 30.0
    fn calc(&self, offset_idx: i32) -> f32 {
        let mut offset =
            (offset_idx.min(OFFSET_MAX) - self.start) as f32 * self.jump + self.start as f32;
        offset += (offset_idx.max(OFFSET_MAX) - OFFSET_MAX) as f32;
        offset = offset.min(30.0);
        offset
    }

    pub fn init(&mut self, size: i32, resolution: i32, hz: i32, ms: i32) {
        self.start = 1;
        let mut offset_max = 30.0f32;
        self.cache.fill(0.0);
        if ms % 25 as libc::c_int == 0 as libc::c_int && ms <= 100 as libc::c_int {
            match resolution {
                24 => {
                    match hz {
                        8000 | 16000 | 24000 | 32000 | 44100 | 48000 => {
                            offset_max = gmax_calc(
                                26.2f32,
                                26.2f32,
                                16.4f32,
                                575 as libc::c_int,
                                size,
                                50 as libc::c_int,
                            );
                        }
                        96000 => {
                            offset_max = gmax_calc(
                                25.7f32,
                                25.7f32,
                                20.75f32,
                                575 as libc::c_int,
                                size,
                                50 as libc::c_int,
                            );
                        }
                        192000 => {
                            offset_max = gmax_calc(
                                25.7f32,
                                25.7f32,
                                24.42f32,
                                575 as libc::c_int,
                                size,
                                50 as libc::c_int,
                            );
                        }
                        _ => {}
                    }
                    self.start = (offset_max / 4 as libc::c_int as libc::c_float) as libc::c_int;
                }
                16 => {
                    match hz {
                        8000 | 16000 | 24000 | 32000 | 44100 => {
                            offset_max = gmax_calc(
                                26.15f32,
                                26.15f32,
                                16.42f32,
                                575 as libc::c_int,
                                size,
                                50 as libc::c_int,
                            );
                        }
                        48000 => {
                            offset_max = gmax_calc(
                                26.18f32,
                                26.18f32,
                                16.32f32,
                                575 as libc::c_int,
                                size,
                                50 as libc::c_int,
                            );
                        }
                        _ => {}
                    }
                    self.start = (offset_max / 2.5f32) as libc::c_int;
                }
                _ => {}
            }
        }
        let deno = OFFSET_MAX - self.start - 1 as libc::c_int;
        self.jump = (offset_max - self.start as libc::c_float) / deno as libc::c_float;
    }
}
