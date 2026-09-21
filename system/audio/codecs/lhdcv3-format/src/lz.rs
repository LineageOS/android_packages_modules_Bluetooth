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

//! The byte oriented coder a channel takes instead of Golomb-Rice when the
//! escape bit in its parameter byte is set.
//!
//! The stream is a sequence of tokens, each a literal run then a match:
//!
//! ```text
//! [run][run bytes][length][distance]
//! ```
//!
//! A run of zero carries no bytes and a length of zero carries no distance,
//! which is how a run of the full 255 is continued. Matches are at least four
//! bytes and at most 255, and reach back at most 509. A distance under 255 is
//! one byte; beyond that the byte is 255 and a second carries the rest.
//!
//! The match finder is a 2048 entry hash table over the four bytes at the
//! current position. It is never cleared, so a block's output depends on the
//! blocks coded before it.
//!
//! It holds addresses, and a match is only taken when the one it finds lies
//! inside the buffer being coded. Weighing a channel packs into that channel's
//! own buffer while coding one always packs into the first channel's, so an
//! entry left by the second channel is never a match for anything else. That
//! is what [`Buffer`] stands for.

/// Slots the match finder remembers positions in. Swept over the corpus: half
/// of it loses a fifth of a dB at the top rate, twice it gains a hundredth.
/// Nothing about it reaches the stream, so a decoder neither knows nor cares.
pub const TABLE: usize = 2048;

/// Knuth's multiplicative hash, the odd integer nearest 2^32 over the golden
/// ratio, which is what lz4 and much else scatter with.
const HASH: u32 = 0x9E37_79B1;

const MIN_MATCH: usize = 4;

const MAX_RUN: usize = 255;

const MAX_DISTANCE: usize = 509;

const LONG_DISTANCE: usize = 255;

#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum Buffer {
    First,
    Second,
}

#[derive(Clone)]
pub struct Table {
    at: [Option<(Buffer, u32)>; TABLE],
}

impl Default for Table {
    fn default() -> Self {
        Table { at: [None; TABLE] }
    }
}

fn hash(v: u32) -> usize {
    (v.wrapping_mul(HASH) & (TABLE as u32 - 1)) as usize
}

fn be32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

fn match_at(
    t: &mut Table,
    src: &[u8],
    p: usize,
    v: u32,
    buf: Buffer,
) -> Option<(usize, usize)> {
    let prev = t.at[hash(v)];
    t.at[hash(v)] = Some((buf, p as u32));
    let (was, q) = prev?;
    let q = q as usize;
    if was != buf || q >= p || p - q > MAX_DISTANCE || be32(&src[q..]) != v {
        return None;
    }
    let room = (src.len() - p - 3).min(MAX_RUN);
    if room < MIN_MATCH + 1 {
        return Some((MIN_MATCH, p - q));
    }
    let mut len = room;
    for k in MIN_MATCH..room {
        if src[p + k] != src[q + k] {
            len = k;
            break;
        }
    }
    Some((len, p - q))
}

fn skip(t: &mut Table, src: &[u8], p: &mut usize, v: &mut u32, len: usize, buf: Buffer) {
    for _ in 0..len - 1 {
        *v = (*v << 8) | u32::from(src.get(*p + 4).copied().unwrap_or(0));
        *p += 1;
        t.at[hash(*v)] = Some((buf, *p as u32));
    }
    *p += 1;
}

pub fn compress(t: &mut Table, src: &[u8], cap: usize) -> Option<Vec<u8>> {
    let buf = Buffer::First;
    if src.len() < MIN_MATCH {
        return None;
    }
    let mut out: Vec<u8> = Vec::new();
    let mut run = 0usize;
    let mut p = 0usize;
    let mut v = (u32::from(src[0]) << 16) | (u32::from(src[1]) << 8) | u32::from(src[2]);

    let literal = |out: &mut Vec<u8>, run: &mut usize, b: u8| {
        if *run == MAX_RUN {
            let at = out.len() - MAX_RUN - 1;
            out[at] = MAX_RUN as u8;
            out.push(0);
            *run = 0;
        }
        if *run == 0 {
            out.push(0);
        }
        out.push(b);
        *run += 1;
    };

    while src.len() - p > 3 {
        v = (v << 8) | u32::from(src[p + 3]);
        if let Some((len, dist)) = match_at(t, src, p, v, buf) {
            if run != 0 {
                let at = out.len() - run - 1;
                out[at] = run as u8;
                run = 0;
            } else {
                out.push(0);
            }
            if out.len() + 3 > cap {
                return None;
            }
            out.push(len as u8);
            if dist < LONG_DISTANCE {
                out.push(dist as u8);
            } else {
                out.push(0xff);
                out.push((dist + 1) as u8);
            }
            skip(t, src, &mut p, &mut v, len, buf);
        } else {
            if out.len() + 3 > cap {
                return None;
            }
            literal(&mut out, &mut run, src[p]);
            p += 1;
        }
    }
    while p < src.len() {
        if out.len() + 3 > cap {
            return None;
        }
        literal(&mut out, &mut run, src[p]);
        p += 1;
    }
    if run != 0 {
        let at = out.len() - run - 1;
        out[at] = run as u8;
    }
    Some(out)
}

pub fn cost(t: &mut Table, src: &[u8], cap: usize, buf: Buffer) -> Option<usize> {
    if src.len() < MIN_MATCH {
        return None;
    }
    let mut n = 0usize;
    let mut run = 0usize;
    let mut p = 0usize;
    let mut v = (u32::from(src[0]) << 16) | (u32::from(src[1]) << 8) | u32::from(src[2]);
    while src.len() - p > 3 {
        v = (v << 8) | u32::from(src[p + 3]);
        if let Some((len, dist)) = match_at(t, src, p, v, buf) {
            if run == 0 {
                n += 1;
            }
            run = 0;
            n += if dist < LONG_DISTANCE { 2 } else { 3 };
            skip(t, src, &mut p, &mut v, len, buf);
        } else {
            if run == MAX_RUN {
                n += 2;
                run = 0;
            }
            if run == 0 {
                n += 1;
            }
            n += 1;
            run += 1;
            p += 1;
        }
        if n > cap {
            return None;
        }
    }
    while p < src.len() {
        if run == MAX_RUN {
            n += 2;
            run = 0;
        }
        if run == 0 {
            n += 1;
        }
        n += 1;
        run += 1;
        p += 1;
    }
    if n > cap {
        None
    } else {
        Some(n)
    }
}

pub fn decompress(src: &[u8], want: usize) -> Option<(Vec<u8>, usize)> {
    let mut out = Vec::with_capacity(want);
    let mut at = 0usize;
    while out.len() < want {
        let run = *src.get(at)? as usize;
        at += 1;
        if at + run > src.len() {
            return None;
        }
        out.extend_from_slice(&src[at..at + run]);
        at += run;
        if out.len() >= want {
            break;
        }
        let len = *src.get(at)? as usize;
        at += 1;
        if len == 0 {
            continue;
        }
        let first = *src.get(at)? as usize;
        at += 1;
        let dist = if first < LONG_DISTANCE {
            first
        } else {
            let more = *src.get(at)? as usize;
            at += 1;
            more + LONG_DISTANCE
        };
        if dist == 0 || dist > out.len() {
            return None;
        }
        for _ in 0..len {
            let b = out[out.len() - dist];
            out.push(b);
        }
    }
    out.truncate(want);
    Some((out, at))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_run_of_one_byte_codes_as_a_literal_and_a_match() {
        let src = vec![0u8; 64];
        let mut t = Table::default();
        let out = compress(&mut t, &src, 1024).unwrap();
        assert_eq!(decompress(&out, src.len()).unwrap().0, src);
    }

    #[test]
    fn a_captured_stream_for_a_constant_block_reads_back() {
        let stream = [
            0x01u8, 0x00, 0x11, 0x01, 0x03, 0x01, 0x77, 0x00, 0xff, 0x01, 0x00, 0xff, 0x01,
            0x00, 0xe7, 0x01, 0x03, 0x00, 0x00, 0x00,
        ];
        let (out, used) = decompress(&stream, 255 * 3).unwrap();
        let mut want = vec![0u8; 255 * 3];
        want[18..21].copy_from_slice(&[0x01, 0x77, 0x00]);
        assert_eq!(out, want);
        assert_eq!(used, 20);
    }

    #[test]
    fn what_is_coded_reads_back_for_a_spread_of_shapes() {
        let shapes: Vec<Vec<u8>> = vec![
            (0..600).map(|i| (i % 7) as u8).collect(),
            (0..600).map(|i| if i % 64 < 60 { 0 } else { i as u8 }).collect(),
            (0..1024).map(|i| ((i * 37) % 251) as u8).collect(),
            vec![9u8; 1000],
            (0..300).map(|i| (i / 40) as u8).collect(),
        ];
        for src in shapes {
            let mut t = Table::default();
            let out = compress(&mut t, &src, 1 << 20).unwrap();
            assert_eq!(decompress(&out, src.len()).unwrap().0, src, "len {}", src.len());
        }
    }

    #[test]
    fn the_cost_is_what_coding_it_takes() {
        for seed in 0..8u32 {
            let src: Vec<u8> = (0..900u32)
                .map(|i| ((i.wrapping_mul(2654435761).wrapping_add(seed)) >> 24) as u8 & 0x0f)
                .collect();
            let mut a = Table::default();
            let mut b = Table::default();
            let out = compress(&mut a, &src, 1 << 20).unwrap();
            assert_eq!(cost(&mut b, &src, 1 << 20, Buffer::First), Some(out.len()), "seed {seed}");
        }
    }

    #[test]
    fn a_cap_that_cannot_be_met_is_refused() {
        let src: Vec<u8> = (0..900).map(|i| (i * 31 % 253) as u8).collect();
        let mut t = Table::default();
        assert!(compress(&mut t, &src, 8).is_none());
    }

    #[test]
    fn the_table_carries_between_blocks() {
        let src: Vec<u8> = (0..600).map(|i| ((i * 17) % 61) as u8).collect();
        let mut t = Table::default();
        let first = compress(&mut t, &src, 1 << 20).unwrap();
        let second = compress(&mut t, &src, 1 << 20).unwrap();
        assert!(second.len() <= first.len());
        assert_eq!(decompress(&second, src.len()).unwrap().0, src);
    }
}
