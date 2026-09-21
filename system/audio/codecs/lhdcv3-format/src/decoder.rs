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

//! Reading an LHDC V3 frame back to samples.
//!
//! A frame that decodes to the samples it was made from is correct whether or
//! not it is the only frame that could have carried them, which is a stronger
//! statement than comparing two encoders byte for byte.

use crate::frame::{signals, Signal, ESCAPE, SUBBLOCKS};
use crate::value::unzigzag;
use crate::frame::{self, Header, Level};
use crate::lz;
use crate::predictor::COEFFS;
use crate::BLOCK_SIZE;
use crate::value::{ESCAPE_BIAS, ESCAPE_MARKER, MID_SPAN, PREFIX_BITS, UNARY_LIMIT};

#[derive(Debug, PartialEq, Eq)]
pub enum DecodeError {
    Header(frame::ParseError),
    UnknownMode(u8),
    Truncated,
}

struct BitReader<'a> {
    data: &'a [u8],
    bit: usize,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        BitReader { data, bit: 0 }
    }

    fn read(&mut self, width: u32) -> Option<u32> {
        let mut v = 0u32;
        for _ in 0..width {
            let byte = *self.data.get(self.bit / 8)?;
            v = (v << 1) | ((byte >> (7 - self.bit % 8)) & 1) as u32;
            self.bit += 1;
        }
        Some(v)
    }

    fn align(&mut self) {
        self.bit = self.bit.div_ceil(8) * 8;
    }

    fn byte_pos(&self) -> usize {
        self.bit / 8
    }

    fn seek_byte(&mut self, at: usize) {
        self.bit = at * 8;
    }

    fn quotient(&mut self) -> Option<u32> {
        let mut total = 0u32;
        loop {
            let mut zeros = 0u32;
            loop {
                if zeros == UNARY_LIMIT {
                    break;
                }
                if self.read(1)? == 1 {
                    return Some(total + zeros);
                }
                zeros += 1;
            }
            let field = self.read(PREFIX_BITS - UNARY_LIMIT)?;
            if field == ESCAPE_MARKER {
                total += ESCAPE_BIAS;
                continue;
            }
            debug_assert!(field < MID_SPAN);
            return Some(total + UNARY_LIMIT + field);
        }
    }

    fn value(&mut self, shift: u32) -> Option<u32> {
        let q = self.quotient()?;
        let rem = if shift > 0 { self.read(shift)? } else { 0 };
        Some((q << shift) | rem)
    }
}

pub fn to_memory(f: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(f.len());
    for w in f.chunks(frame::LEN_GRANULE) {
        out.extend(w.iter().rev());
    }
    out
}

#[derive(Debug)]
pub struct Decoded {
    pub left: Vec<i32>,
    pub right: Vec<i32>,
    pub valid: usize,
    pub field: u8,
    pub divisor: [i32; 2],
    pub modes: [u8; 2],
}

fn read_be(m: &[u8], at: usize, n: usize) -> Option<i32> {
    if at + n > m.len() {
        return None;
    }
    let mut v: i32 = 0;
    for k in 0..n {
        v = (v << 8) | m[at + k] as i32;
    }
    let bits = 32 - 8 * n as u32;
    Some((v << bits) >> bits)
}

pub fn decode(f: &[u8], sample_bytes: usize) -> Result<Decoded, DecodeError> {
    let h = Header::parse(f).map_err(DecodeError::Header)?;
    let m = to_memory(&f[..h.len]);
    let field = h.channel_field();
    let chosen = signals(field);
    let modes = [m[3] >> 4, m[3] & 0x0f];
    let divisor = [Level::decode(m[4]).divisor(), Level::decode(m[5]).divisor()];

    let mut at = 6usize;
    let mut shift = [0u32; 2];
    let mut constant = [None; 2];
    let mut order = [0usize; 2];
    let mut raw_history = [false; 2];
    let mut packed: [Option<usize>; 2] = [None, None];
    let mut lpc: [Option<(u32, Vec<i32>)>; 2] = [None, None];

    for ch in 0..2 {
        match modes[ch] {
            0 => {
                constant[ch] = read_be(&m, at, sample_bytes);
                at += sample_bytes;
            }
            n @ 2..=14 => {
                order[ch] = n as usize - 2;
                let byte = *m.get(at).ok_or(DecodeError::Truncated)?;
                shift[ch] = (byte & 0x3f) as u32;
                raw_history[ch] = byte & 0x80 != 0;
                if byte & ESCAPE != 0 {
                    packed[ch] = Some(*m.get(at + 1).ok_or(DecodeError::Truncated)? as usize);
                }
                at += 1 + usize::from(byte & ESCAPE != 0);
            }
            15 => {
                let first = *m.get(at).ok_or(DecodeError::Truncated)?;
                shift[ch] = (first & 0x3f) as u32;
                if first & ESCAPE != 0 {
                    packed[ch] = Some(*m.get(at + 1).ok_or(DecodeError::Truncated)? as usize);
                }
                at += 1 + usize::from(first & ESCAPE != 0);
                let byte = *m.get(at).ok_or(DecodeError::Truncated)?;
                at += 1;
                let ord = ((byte >> 5) & 0x07) as usize + 1;
                let cshift = (byte & 0x1f) as u32;
                let mut c = Vec::with_capacity(ord);
                for _ in 0..ord {
                    c.push(read_be(&m, at, 2).ok_or(DecodeError::Truncated)?);
                    at += 2;
                }
                order[ch] = ord;
                lpc[ch] = Some((cshift, c));
            }
            other => return Err(DecodeError::UnknownMode(other)),
        }
    }

    let span = if m[1] & 0x80 != 0 { BLOCK_SIZE } else { BLOCK_SIZE / 2 };
    let sub_len = span / SUBBLOCKS;
    let mut sig = [vec![0i32; span], vec![0i32; span]];
    let mut high = [vec![0u32; span], vec![0u32; span]];
    let valid = [span; 2];
    let mut r = BitReader::new(&m);
    r.seek_byte(at);

    for sub in 0..SUBBLOCKS {
        for ch in 0..2 {
            if let Some(v) = constant[ch] {
                sig[ch].iter_mut().for_each(|s| *s = v);
                continue;
            }
            let warm = if lpc[ch].is_some() || raw_history[ch] {
                order[ch]
            } else {
                usize::from(order[ch] > 0)
            };
            if sub == 0 {
                let n = match chosen[ch] {
                    Signal::Difference => sample_bytes + 1,
                    _ => sample_bytes,
                };
                for i in 0..warm {
                    let v = read_be(&m, r.byte_pos(), n).ok_or(DecodeError::Truncated)?;
                    sig[ch][i] = v;
                    r.seek_byte(r.byte_pos() + n);
                }
            }
            let first = warm;
            let start = (sub * sub_len).max(first);
            match packed[ch] {
                Some(width) => {
                    if sub == 0 {
                        let n = (span - warm) * width;
                        let rest = &m[r.byte_pos()..];
                        let (out, used) =
                            lz::decompress(rest, n).ok_or(DecodeError::Truncated)?;
                        for (k, chunk) in out.chunks_exact(width).enumerate() {
                            let mut v = 0u32;
                            for &b in chunk {
                                v = (v << 8) | u32::from(b);
                            }
                            high[ch][warm + k] = v;
                        }
                        r.seek_byte(r.byte_pos() + used);
                    }
                    for i in start..(sub + 1) * sub_len {
                        let low = if shift[ch] == 0 {
                            0
                        } else {
                            r.read(shift[ch]).ok_or(DecodeError::Truncated)?
                        };
                        sig[ch][i] = unzigzag((high[ch][i] << shift[ch]) | low);
                    }
                }
                None => {
                    for i in start..(sub + 1) * sub_len {
                        let v = r.value(shift[ch]).ok_or(DecodeError::Truncated)?;
                        sig[ch][i] = unzigzag(v);
                    }
                }
            }
            r.align();
        }
    }

    for ch in 0..2 {
        if constant[ch].is_some() {
            continue;
        }
        if let Some((cshift, c)) = &lpc[ch] {
            let order = c.len();
            for i in order..span {
                let mut acc = 0i64;
                for (j, &q) in c.iter().enumerate() {
                    acc += q as i64 * sig[ch][i - 1 - j] as i64;
                }
                sig[ch][i] = (sig[ch][i] as i64 + (acc >> cshift)) as i32;
            }
        } else if order[ch] > 0 {
            let o = order[ch];
            if raw_history[ch] {
                for i in o..span {
                    let mut acc = sig[ch][i] as i64;
                    for (j, &c) in COEFFS[o].iter().enumerate().skip(1) {
                        acc -= c * sig[ch][i - j] as i64;
                    }
                    sig[ch][i] = acc as i32;
                }
            } else {
                let warm = sig[ch][0];
                sig[ch][0] = 0;
                for _ in 1..o {
                    let mut acc = 0i32;
                    for v in sig[ch].iter_mut() {
                        acc = acc.wrapping_add(*v);
                        *v = acc;
                    }
                }
                let mut acc = warm;
                sig[ch][0] = warm;
                for i in 1..span {
                    acc = acc.wrapping_add(sig[ch][i]);
                    sig[ch][i] = acc;
                }
            }
        }
    }

    let scale = [divisor[0].max(1), divisor[1].max(1)];
    let (mut left, mut right) = (vec![0i32; span], vec![0i32; span]);
    for i in 0..span {
        let (a, b) = (sig[0][i], sig[1][i]);
        let (l, rr) = match (chosen[0], chosen[1]) {
            (Signal::Left, Signal::Right) => (a, b),
            (Signal::Left, Signal::Difference) => (a, a + b),
            (Signal::Difference, Signal::Right) => (b - a, b),
            _ => (a, b),
        };
        left[i] = l * scale[0];
        right[i] = rr * scale[1];
    }

    Ok(Decoded { left, right, field, divisor, modes, valid: valid[0].min(valid[1]) })
}
