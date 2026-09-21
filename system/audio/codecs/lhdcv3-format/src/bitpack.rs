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

//! Most significant bit first bit packing.
//!
//! A 32 bit accumulator holds what has not been written yet. Each value is
//! masked to its field width and shifted into the top of the accumulator,
//! and whole bytes are taken off the top while eight or more bits are
//! pending. A width of zero writes nothing.

#[derive(Debug, Default)]
pub struct BitWriter {
    out: Vec<u8>,
    acc: u32,
    bits: u32,
}

impl BitWriter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_capacity(bytes: usize) -> Self {
        BitWriter { out: Vec::with_capacity(bytes), ..Self::default() }
    }


    pub fn mask(width: u32) -> u32 {
        if width == 0 {
            0
        } else {
            u32::MAX >> (32 - width)
        }
    }

    pub fn write(&mut self, value: u32, width: u32) {
        // The accumulator carries what is pending as well, so a caller that
        // asks for more than fits would shift past the end of it.
        assert!(width <= 32 && self.bits + width <= 32, "{} pending + {width}", self.bits);
        if width == 0 {
            return;
        }
        self.bits += width;
        self.acc |= (value & Self::mask(width)) << (32 - self.bits);
        while self.bits >= 8 {
            self.out.push((self.acc >> 24) as u8);
            self.acc <<= 8;
            self.bits -= 8;
        }
    }

    pub fn pending_bits(&self) -> u32 {
        self.bits
    }

    pub fn align(&mut self) {
        if self.bits > 0 {
            self.out.push((self.acc >> 24) as u8);
            self.acc = 0;
            self.bits = 0;
        }
    }

    pub fn bytes(&self) -> &[u8] {
        &self.out
    }

    pub fn finish(mut self) -> Vec<u8> {
        if self.bits > 0 {
            self.out.push((self.acc >> 24) as u8);
        }
        self.out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_matches_the_library_construction() {
        assert_eq!(BitWriter::mask(0), 0);
        assert_eq!(BitWriter::mask(1), 0x1);
        assert_eq!(BitWriter::mask(3), 0x7);
        assert_eq!(BitWriter::mask(8), 0xff);
        assert_eq!(BitWriter::mask(32), u32::MAX);
    }

    #[test]
    fn packs_most_significant_bit_first() {
        let mut w = BitWriter::new();
        w.write(0b1, 1);
        w.write(0b011, 3);
        w.write(0b1100, 4);
        assert_eq!(w.finish(), vec![0b1_011_1100]);
    }

    #[test]
    fn spans_byte_boundaries() {
        let mut w = BitWriter::new();
        for _ in 0..8 {
            w.write(0b101, 3);
        }
        assert_eq!(w.finish(), vec![0b10110110, 0b11011011, 0b01101101]);
    }

    #[test]
    fn discards_bits_above_the_width() {
        let mut w = BitWriter::new();
        w.write(0xff, 3);
        assert_eq!(w.finish(), vec![0b111_00000]);
    }

    #[test]
    fn a_zero_width_writes_nothing() {
        let mut w = BitWriter::new();
        w.write(0xffff_ffff, 0);
        assert_eq!(w.pending_bits(), 0);
        assert!(w.finish().is_empty());
    }

    #[test]
    fn align_pads_with_zeros() {
        let mut w = BitWriter::new();
        w.write(0b101, 3);
        w.align();
        assert_eq!(w.pending_bits(), 0);
        w.write(0xff, 8);
        assert_eq!(w.finish(), vec![0b101_00000, 0xff]);
    }

    #[test]
    fn align_on_a_boundary_adds_nothing() {
        let mut w = BitWriter::new();
        w.write(0xab, 8);
        w.align();
        assert_eq!(w.finish(), vec![0xab]);
    }

    #[test]
    fn a_full_width_field_round_trips() {
        let mut w = BitWriter::new();
        w.write(0xdead_beef, 32);
        assert_eq!(w.finish(), vec![0xde, 0xad, 0xbe, 0xef]);
    }
}

