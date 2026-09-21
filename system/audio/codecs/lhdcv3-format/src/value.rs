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

//! The variable length code the residual is written with.
//!
//! Each value is first quantized by a per band right shift, then coded in one
//! of three tiers by the quotient `q`:
//!
//!   - `q < 10` is unary, `q` zeroes and a one.
//!   - `q < 73` is a sixteen bit field carrying `q - 10`.
//!   - otherwise a sixteen bit escape marker is written and `q` drops by 73,
//!     repeating until one of the tiers above takes it.

use crate::bitpack::BitWriter;

pub const UNARY_LIMIT: u32 = 10;

pub const MID_SPAN: u32 = 63;

pub const ESCAPE_BIAS: u32 = UNARY_LIMIT + MID_SPAN;

pub fn quantize(value: u32, shift: u32) -> u32 {
    if shift >= 32 {
        0
    } else {
        value >> shift
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Tier {
    Unary,
    Mid,
    Escape,
}

pub fn tier(q: u32) -> Tier {
    if q < UNARY_LIMIT {
        Tier::Unary
    } else if q - UNARY_LIMIT < MID_SPAN {
        Tier::Mid
    } else {
        Tier::Escape
    }
}

pub fn write_unary(w: &mut BitWriter, q: u32) {
    debug_assert!(q < UNARY_LIMIT);
    w.write(1, q + 1);
}

pub const PREFIX_BITS: u32 = 16;
pub const MID_VALUE_BITS: u32 = PREFIX_BITS - UNARY_LIMIT;

pub const ESCAPE_MARKER: u32 = 0x3f;

pub fn write_mid(w: &mut BitWriter, q: u32) {
    debug_assert!((UNARY_LIMIT..ESCAPE_BIAS).contains(&q));
    w.write(q - UNARY_LIMIT, PREFIX_BITS);
}

pub fn write_escape(w: &mut BitWriter) {
    w.write(ESCAPE_MARKER, PREFIX_BITS);
}

pub fn write_quotient(w: &mut BitWriter, q: u32) {
    for _ in 0..q / ESCAPE_BIAS {
        write_escape(w);
    }
    let q = q % ESCAPE_BIAS;
    if q < UNARY_LIMIT {
        write_unary(w, q);
    } else {
        write_mid(w, q);
    }
}

pub fn code_len(value: u32, shift: u32) -> u32 {
    let q = quantize(value, shift);
    let rest = q % ESCAPE_BIAS;
    let tail = if rest < UNARY_LIMIT { rest + 1 } else { PREFIX_BITS };
    shift + (q / ESCAPE_BIAS) * PREFIX_BITS + tail
}

pub fn write_value(w: &mut BitWriter, value: u32, shift: u32) {
    let q = quantize(value, shift);
    // The common case is a unary quotient, which is `q` zeroes and a one, so
    // it joins the kept bits below it into a single write.
    // The accumulator holds 32 bits including whatever is still pending, so
    // the joined code only fits when the two together do.
    if q < UNARY_LIMIT && w.pending_bits() + q + 1 + shift <= 32 {
        let tail = value & BitWriter::mask(shift);
        w.write((1 << shift) | tail, q + 1 + shift);
        return;
    }
    write_quotient(w, q);
    w.write(value, shift);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tier_boundaries_match_the_encoder() {
        assert_eq!(tier(0), Tier::Unary);
        assert_eq!(tier(9), Tier::Unary);
        assert_eq!(tier(10), Tier::Mid);
        assert_eq!(tier(72), Tier::Mid);
        assert_eq!(tier(73), Tier::Escape);
        assert_eq!(ESCAPE_BIAS, 73);
    }

    #[test]
    fn a_zero_run_becomes_set_bits() {
        let mut w = BitWriter::new();
        for _ in 0..32 {
            write_unary(&mut w, 0);
        }
        assert_eq!(w.finish(), vec![0xff; 4]);
    }

    #[test]
    fn unary_writes_zeros_then_a_one() {
        let mut w = BitWriter::new();
        write_unary(&mut w, 3);
        write_unary(&mut w, 0);
        write_unary(&mut w, 2);
        assert_eq!(w.finish(), vec![0b0001_1001]);
    }

    #[test]
    fn a_value_is_a_quotient_then_a_remainder() {
        let mut w = BitWriter::new();
        write_value(&mut w, 0b10110, 2);
        assert_eq!(w.finish(), vec![0b00000_1_10]);
    }

    #[test]
    fn a_shift_of_zero_writes_no_remainder() {
        let mut w = BitWriter::new();
        write_value(&mut w, 3, 0);
        assert_eq!(w.finish(), vec![0b0001_0000]);
    }

    #[test]
    fn the_mid_tier_is_ten_zeros_then_six_bits() {
        let mut w = BitWriter::new();
        write_mid(&mut w, 10);
        assert_eq!(w.finish(), vec![0x00, 0x00]);

        let mut w = BitWriter::new();
        write_mid(&mut w, 10 + 5);
        assert_eq!(w.finish(), vec![0x00, 0b00_000101]);
    }

    #[test]
    fn the_escape_marker_fills_the_value_bits() {
        let mut w = BitWriter::new();
        write_escape(&mut w);
        assert_eq!(w.finish(), vec![0x00, 0x3f]);
    }

    #[test]
    fn the_mid_tier_never_collides_with_the_escape() {
        assert_eq!(MID_SPAN, 63);
        assert_eq!(ESCAPE_BIAS - UNARY_LIMIT, MID_SPAN);
        assert!(MID_SPAN - 1 < ESCAPE_MARKER);
    }

    #[test]
    fn a_large_quotient_escapes_repeatedly() {
        let mut q = 500u32;
        let mut escapes = 0;
        while q >= ESCAPE_BIAS {
            q -= ESCAPE_BIAS;
            escapes += 1;
        }
        assert_eq!(escapes, 6);
        assert_eq!(q, 62);
        assert_eq!(tier(q), Tier::Mid);
        assert_eq!(q - UNARY_LIMIT, 52);

        let mut w = BitWriter::new();
        write_quotient(&mut w, 500);
        let out = w.finish();
        assert_eq!(out.len(), 7 * 2);
        for chunk in out.chunks(2).take(6) {
            assert_eq!(chunk, [0x00, 0x3f]);
        }
        assert_eq!(&out[12..], [0x00, 52]);
    }

    #[test]
    fn code_len_agrees_with_what_is_written() {
        for value in [0u32, 1, 2, 9, 10, 72, 73, 100, 500, 65535] {
            for shift in [0u32, 1, 3, 8] {
                let mut w = BitWriter::new();
                write_value(&mut w, value, shift);
                let want = code_len(value, shift);
                let got = w.pending_bits() + 8 * w.bytes().len() as u32;
                assert_eq!(got, want, "value {value} shift {shift}");
            }
        }
    }

    #[test]
    fn quantize_shifts_right() {
        assert_eq!(quantize(256, 0), 256);
        assert_eq!(quantize(256, 8), 1);
        assert_eq!(quantize(255, 8), 0);
        assert_eq!(quantize(1, 40), 0);
    }
}

pub fn zigzag(v: i32) -> u32 {
    ((v << 1) ^ (v >> 31)) as u32
}

pub fn unzigzag(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}
