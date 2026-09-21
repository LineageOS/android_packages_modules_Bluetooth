// Copyright (C) 2026, The Android Open Source Project
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

//! Constants for the LHDC V3 bitstream.

pub const BITRATE_KBPS: [u32; 9] = [64, 128, 192, 256, 320, 400, 500, 900, 1000];

pub const BITRATE_INDEX_ABR: usize = 9;

pub const ABR_START_KBPS: u32 = 400;

pub const MIN_BITRATE_KBPS: u32 = 128;
pub const MIN_BITRATE_LIMITED_KBPS: u32 = 320;

pub use lhdcv3_format::BLOCK_SIZE;

pub const BLOCKS_PER_BATCH: usize = 4;

pub const NOISE_LEVELS: u32 = 24;

pub const MAX_ENCODED_LEVEL: u32 = 31;

pub fn noise_threshold(level: u32) -> f32 {
    let step = ((1u64 << (level + 1)) - 1) as f32;
    step * step / 12.0
}

pub fn level_for_noise(allowance: f32) -> u32 {
    (0..NOISE_LEVELS).find(|&l| noise_threshold(l) >= allowance).unwrap_or(NOISE_LEVELS - 1)
}

pub fn inline_noise_expression(level: u32) -> f32 {
    let step = (1u64 << level) as f32;
    let effective = (step - 1.0) + step / 8.0;
    effective * effective / 12.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bitrate_table_matches_the_library() {
        assert_eq!(BITRATE_KBPS, [64, 128, 192, 256, 320, 400, 500, 900, 1000]);
    }

    #[test]
    fn thresholds_match_the_table_in_the_encoder() {
        let want: [f32; 24] = [
            0.083333, 0.750000, 4.083333, 18.750000, 80.083336, 330.750000, 1344.083374,
            5418.750000, 21760.083984, 87210.750000, 349184.093750, 1397418.750000, 5591040.0,
            22366890.0, 89473024.0, 357903008.0, 1.43163e9, 5.72658e9, 2.29064e10, 9.16258e10,
            3.66504e11, 1.46601e12, 5.86406e12, 2.34562e13,
        ];
        for (l, w) in want.iter().enumerate() {
            let got = noise_threshold(l as u32);
            assert!((got - w).abs() <= w.abs() * 1e-5, "level {l}: {got} vs {w}");
        }
    }

    #[test]
    fn a_tighter_allowance_picks_a_finer_level() {
        assert_eq!(level_for_noise(noise_threshold(6)), 6);
        assert_eq!(level_for_noise(noise_threshold(2)), 2);
        assert!(level_for_noise(1.0) < level_for_noise(1000.0));
    }

    #[test]
    fn the_table_that_follows_is_a_different_sequence() {
        let after: [f32; 6] = [0.0, 0.0833333, 0.333333, 0.75, 1.33333, 2.08333];
        for (n, w) in after.iter().enumerate() {
            let got = (n * n) as f32 / 12.0;
            assert!((got - w).abs() <= 1e-4, "n={n}");
        }
        assert!(noise_threshold(NOISE_LEVELS - 1) > 1e13);
    }

    #[test]
    fn the_inline_expression_is_not_the_table() {
        assert!((inline_noise_expression(1) - noise_threshold(1)).abs() > 0.5);
    }
}

pub fn kbps_for_index(index: usize) -> u32 {
    if index == BITRATE_INDEX_ABR {
        return ABR_START_KBPS;
    }
    BITRATE_KBPS[index.min(BITRATE_KBPS.len() - 1)].max(MIN_BITRATE_KBPS)
}
