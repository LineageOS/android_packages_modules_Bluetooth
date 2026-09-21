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

//! Streaming V3 encode.
//!
//! One call takes one block of stereo samples. Frames are held until the
//! packet they are going into is as full as the transport allows, and are then
//! handed over together; the media header the transport adds carries how many.

use super::encoder::{frame_lead_in, halved, Config, Encoder};
use lhdcv3_format::BLOCK_SIZE;

/// Frames the transport will not exceed in one packet however small they are,
/// which is the audio one interval covers.
pub fn frames_per_interval(rate: u32, interval_ms: u32) -> usize {
    let samples = rate as usize * interval_ms as usize / 1000;
    ((samples + BLOCK_SIZE / 2) / BLOCK_SIZE).max(1)
}

pub struct Session {
    encoder: Encoder,
    config: Config,
    /// Samples the coded block reaches back past its own boundary, kept so the
    /// next call starts where this one left off.
    carry: [Vec<i32>; 2],
    held: Vec<u8>,
    held_frames: usize,
    room: usize,
    per_packet: usize,
    blocks: usize,
}

impl Session {
    pub fn new(config: Config, mtu: usize, interval_ms: u32) -> Session {
        Session {
            encoder: Encoder::new(config),
            config,
            carry: [Vec::new(), Vec::new()],
            held: Vec::new(),
            held_frames: 0,
            room: mtu,
            per_packet: frames_per_interval(config.sample_rate, interval_ms),
            blocks: 0,
        }
    }

    /// Stereo samples per channel one call consumes.
    pub fn samples_per_call(&self) -> usize {
        BLOCK_SIZE
    }

    fn lead(&self) -> usize {
        if halved(self.config.sample_rate, self.config.bitrate_kbps) {
            0
        } else {
            frame_lead_in(self.config.sample_rate)
        }
    }

    /// Codes one block. When the packet it is going into is full, the frames
    /// held so far are appended to `out` and their count returned; otherwise
    /// nothing is written and the count is zero.
    pub fn encode(&mut self, left: &[i32], right: &[i32], out: &mut Vec<u8>) -> usize {
        assert!(left.len() >= BLOCK_SIZE && right.len() >= BLOCK_SIZE);
        let lead = self.lead();

        let mut buf = [
            std::mem::take(&mut self.carry[0]),
            std::mem::take(&mut self.carry[1]),
        ];
        let origin = self.blocks * BLOCK_SIZE - buf[0].len();
        buf[0].extend_from_slice(&left[..BLOCK_SIZE]);
        buf[1].extend_from_slice(&right[..BLOCK_SIZE]);

        let at = (self.blocks * BLOCK_SIZE).saturating_sub(lead) - origin;
        let end = (at + BLOCK_SIZE + lead).min(buf[0].len());
        let frame = self.encoder.encode_block(&buf[0][at..end], &buf[1][at..end]);
        self.blocks += 1;

        for (slot, side) in self.carry.iter_mut().zip(&buf) {
            slot.clear();
            slot.extend_from_slice(&side[side.len() - lead..]);
        }

        // One call hands over at most one packet: the count returned names the
        // frames in what was appended, and two packets appended together would
        // reach the sink as one.
        let mut sent = 0;
        if !self.held.is_empty() && self.held.len() + frame.bytes.len() > self.room {
            sent = self.flush(out);
        }
        self.held.extend_from_slice(&frame.bytes);
        self.held_frames += 1;
        if sent == 0 && self.held_frames >= self.per_packet {
            sent = self.flush(out);
        }
        sent
    }

    fn flush(&mut self, out: &mut Vec<u8>) -> usize {
        out.append(&mut self.held);
        std::mem::replace(&mut self.held_frames, 0)
    }

    /// Drops whatever is held without coding it, for a stream that is being
    /// flushed: what is held is audio from before the flush, and the stack
    /// asks for a flush precisely when that audio is no longer wanted.
    pub fn discard(&mut self) {
        self.held.clear();
        self.held_frames = 0;
    }

    /// Sets the rate the frames are coded toward. A rate that would change
    /// whether the stream is halved is refused: halving puts the anti-alias
    /// filter's delay into the stream, and taking it in or out part way
    /// through steps the audio by the filter's delay.
    pub fn set_bitrate(&mut self, kbps: u32) -> bool {
        if halved(self.config.sample_rate, kbps) != halved(self.config.sample_rate, self.config.bitrate_kbps)
        {
            return false;
        }
        self.config.bitrate_kbps = kbps;
        self.encoder.set_bitrate(kbps);
        true
    }

    /// Hands over whatever is still held, for the end of a stream.
    pub fn drain(&mut self, out: &mut Vec<u8>) -> usize {
        self.flush(out)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn a_rate_moves_only_where_halving_stays_as_it_is() {
        let config = Config { sample_rate: 96000, bits_per_sample: 24, bitrate_kbps: 900 };
        let mut s = Session::new(config, 600, 20);
        assert!(!halved(96000, 900));
        assert!(s.set_bitrate(1000), "a rung on the same side is taken");
        assert!(!s.set_bitrate(400), "a rung that would halve the stream is refused");
        assert!(s.set_bitrate(900));
    }

    #[test]
    fn a_flush_drops_what_is_held() {
        let config = Config { sample_rate: 48000, bits_per_sample: 16, bitrate_kbps: 400 };
        let mut s = Session::new(config, 4096, 20);
        let block = vec![0i32; BLOCK_SIZE];
        let mut out = Vec::new();
        s.encode(&block, &block, &mut out);
        s.discard();
        let mut after = Vec::new();
        assert_eq!(s.drain(&mut after), 0);
        assert!(after.is_empty());
    }

    use super::*;

    fn config(rate: u32) -> Config {
        Config { sample_rate: rate, bits_per_sample: 16, bitrate_kbps: 900 }
    }

    #[test]
    fn frames_are_held_until_the_packet_is_full() {
        let mut s = Session::new(config(48000), 679, 20);
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 % 900) - 450).collect();
        let mut out = Vec::new();
        let mut total = 0;
        for _ in 0..8 {
            total += s.encode(&x, &x, &mut out);
        }
        total += s.drain(&mut out);
        assert_eq!(total, 8);
        assert!(!out.is_empty());
    }

    #[test]
    fn a_packet_never_outgrows_the_transport() {
        let mut s = Session::new(config(48000), 300, 20);
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 % 30000) - 15000).collect();
        let mut out = Vec::new();
        for _ in 0..16 {
            let before = out.len();
            if s.encode(&x, &x, &mut out) > 0 {
                assert!(out.len() - before <= 300);
            }
        }
    }

    #[test]
    fn a_rate_with_a_lead_in_carries_it_between_calls() {
        let mut s = Session::new(config(96000), 679, 20);
        let x: Vec<i32> = (0..BLOCK_SIZE * 2).map(|i| (i as i32 % 700) - 350).collect();
        let mut out = Vec::new();
        s.encode(&x[..BLOCK_SIZE], &x[..BLOCK_SIZE], &mut out);
        assert_eq!(s.carry[0].len(), frame_lead_in(96000));
        s.encode(&x[BLOCK_SIZE..], &x[BLOCK_SIZE..], &mut out);
    }

    #[test]
    fn the_interval_bounds_how_many_frames_share_a_packet() {
        assert_eq!(frames_per_interval(44100, 20), 3);
        assert_eq!(frames_per_interval(48000, 20), 4);
        assert_eq!(frames_per_interval(96000, 20), 8);
    }
}
