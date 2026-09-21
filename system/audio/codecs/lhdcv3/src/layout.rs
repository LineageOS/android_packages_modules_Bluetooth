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

//! Where a block sits in the stream it came from.
//!
//! A coded block does not line up with its own boundary: above 48 kHz it
//! reaches back a few samples, and the first block of a stream opens on
//! silence to cover the gap. A halved stream carries one sample in two and
//! comes out behind the input by the anti-alias filter's delay. Every reader
//! of a frame needs the same answer to "which input is this", so it is
//! answered here once.

use super::encoder::{frame_lead_in, halved};
use super::lpf;
use lhdcv3_format::BLOCK_SIZE;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Layout {
    /// Samples a coded block starts before its own boundary.
    pub lead: usize,
    /// Input samples between one coded sample and the next.
    pub step: usize,
    /// Input samples the coded stream sits behind the input by.
    pub delay: usize,
}

impl Layout {
    pub fn new(sample_rate: u32, bitrate_kbps: u32) -> Layout {
        Layout::of(sample_rate, halved(sample_rate, bitrate_kbps))
    }

    /// For a reader, which sees whether a frame is halved but not the bitrate
    /// that decided it.
    pub fn of(sample_rate: u32, halved: bool) -> Layout {
        if halved {
            Layout { lead: 0, step: 2, delay: lpf::GROUP_DELAY }
        } else {
            Layout { lead: frame_lead_in(sample_rate), step: 1, delay: 0 }
        }
    }

    /// Coded samples at the start of the first block that are silence rather
    /// than input.
    pub fn silent_head(&self, block: usize) -> usize {
        if block == 0 {
            self.lead.div_ceil(self.step)
        } else {
            0
        }
    }

    /// The input sample a coded sample stands for, or `None` where it stands
    /// for silence: the head of a block that opens on it, or the filter's own
    /// delay at the start of a halved stream.
    pub fn source(&self, block: usize, coded: usize) -> Option<usize> {
        let head = self.silent_head(block);
        if coded < head {
            return None;
        }
        let at = (block * BLOCK_SIZE).saturating_sub(self.lead);
        (at + (coded - head) * self.step).checked_sub(self.delay)
    }

    /// The input a block is coded from, which reaches back past its own
    /// boundary wherever there is a lead-in.
    pub fn feed(&self, block: usize, available: usize) -> std::ops::Range<usize> {
        let at = (block * BLOCK_SIZE).saturating_sub(self.lead);
        at..(at + BLOCK_SIZE + self.lead).min(available)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_rate_without_a_lead_in_maps_straight_through() {
        let l = Layout::new(48000, 900);
        assert_eq!(l, Layout { lead: 0, step: 1, delay: 0 });
        assert_eq!(l.source(0, 0), Some(0));
        assert_eq!(l.source(3, 7), Some(3 * BLOCK_SIZE + 7));
    }

    #[test]
    fn the_first_block_opens_on_silence_where_there_is_a_lead_in() {
        let l = Layout::new(96000, 900);
        assert_eq!(l.lead, 7);
        for coded in 0..7 {
            assert_eq!(l.source(0, coded), None, "coded {coded}");
        }
        assert_eq!(l.source(0, 7), Some(0));
        // Every block after it reaches back past its own boundary.
        assert_eq!(l.source(1, 0), Some(BLOCK_SIZE - 7));
        assert_eq!(l.source(2, 0), Some(2 * BLOCK_SIZE - 7));
    }

    #[test]
    fn a_block_is_fed_from_where_it_starts() {
        let l = Layout::new(96000, 900);
        assert_eq!(l.feed(0, 10000), 0..BLOCK_SIZE + 7);
        assert_eq!(l.feed(1, 10000), BLOCK_SIZE - 7..2 * BLOCK_SIZE);
        let flat = Layout::new(48000, 900);
        assert_eq!(flat.feed(2, 10000), 2 * BLOCK_SIZE..3 * BLOCK_SIZE);
    }

    #[test]
    fn a_halved_stream_takes_one_sample_in_two() {
        let l = Layout::new(96000, 128);
        assert_eq!(l, Layout { lead: 0, step: 2, delay: lpf::GROUP_DELAY });
        let head = lpf::GROUP_DELAY / 2;
        assert_eq!(l.source(0, head), Some(0));
        assert_eq!(l.source(0, head + 1), Some(2));
        assert_eq!(l.source(1, 0), Some(BLOCK_SIZE - lpf::GROUP_DELAY));
    }

    #[test]
    fn a_halved_stream_opens_on_the_filter_ramping_up() {
        let l = Layout::new(96000, 128);
        for coded in 0..lpf::GROUP_DELAY / 2 {
            assert_eq!(l.source(0, coded), None, "coded {coded}");
        }
        assert!(l.source(0, lpf::GROUP_DELAY / 2).is_some());
    }
}
