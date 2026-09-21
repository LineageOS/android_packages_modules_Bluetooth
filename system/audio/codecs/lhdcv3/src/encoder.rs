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

//! LHDC V3 encoder.
//!
//! A block of [`BLOCK_SIZE`] samples becomes one frame. The encoder derives
//! three signals from the stereo pair, left, right and right minus left, and
//! codes two of them; which two is the frame's channel field. Each coded signal
//! is predicted with a fixed polynomial of order 0 to [`MAX_ORDER`], the
//! residual is mapped to unsigned by zigzag and written Golomb-Rice.
//!
//! The frame is built in memory and emitted with every four byte word reversed,
//! which is why the sync byte sits at memory offset 0 but at frame offset 3.
//!
//! Loss comes from one place: the whole signal is right shifted by a pass index
//! before anything else, and the encoder raises that index until the frame fits
//! the byte budget the bitrate sets.

use lhdcv3_format::bitpack::BitWriter;
use lhdcv3_format::frame::{self, signals, Level, Signal, ESCAPE, SUBBLOCKS, SYNC};
use crate::lpc::{self, Quantized};
use crate::lpf;
use lhdcv3_format::lz;
use lhdcv3_format::predictor::{Rows, MAX_ROWS};
use lhdcv3_format::BLOCK_SIZE;
use lhdcv3_format::value::{code_len, write_value, zigzag};

pub fn sub_len(span: usize) -> usize {
    span / SUBBLOCKS
}

/// Steps a block spans before its samples are divided to the nearest level
/// rather than toward zero.
///
/// The nearest level halves the error the decoder rebuilds with, but it also
/// lifts samples to the next level, which costs bits and can settle the search
/// on a coarser step than it would otherwise take. Under this many steps the
/// block is collapsing and those bits buy nothing back.
pub const ROUND_ABOVE_STEPS: u32 = 8;

fn rounding_bias(x: &[i32], divisor: i32) -> u32 {
    let peak = x.iter().map(|v| v.unsigned_abs()).max().unwrap_or(0);
    let step = divisor.unsigned_abs();
    if peak / step >= ROUND_ABOVE_STEPS {
        step / 2
    } else {
        0
    }
}

/// Divides a whole signal by one level.
///
/// The magnitude is divided and the sign put back, so a negative sample lands
/// the same distance from zero as its positive twin; an arithmetic shift would
/// carry it one step further out and change the residual the coder sees.
///
/// A power of two divisor is the common case on the resize ladder, where that
/// division is a shift.
pub fn quantize_all(x: &[i32], divisor: i32) -> Vec<i32> {
    if divisor <= 1 {
        return x.to_vec();
    }
    let bias = rounding_bias(x, divisor);
    let signed = |v: i32, q: u32| if v < 0 { -(q as i32) } else { q as i32 };
    if divisor & (divisor - 1) == 0 {
        let k = divisor.trailing_zeros();
        return x.iter().map(|&v| signed(v, (v.unsigned_abs() + bias) >> k)).collect();
    }
    // Every step of the grid is one of eight small units shifted up, and
    // dividing by the unit and then by the shift is dividing by the step.
    // The unit is known here, so its division is a multiply rather than the
    // divide instruction a step read out of a frame would need.
    let coarse = i32::BITS - 1 - divisor.leading_zeros();
    let shift = coarse.saturating_sub(3);
    let unit = divisor >> shift;
    macro_rules! by {
        ($u:literal) => {
            return x
                .iter()
                .map(|&v| signed(v, ((v.unsigned_abs() + bias) / $u) >> shift))
                .collect()
        };
    }
    if unit << shift == divisor {
        match unit {
            3 => by!(3),
            5 => by!(5),
            6 => by!(6),
            7 => by!(7),
            9 => by!(9),
            10 => by!(10),
            11 => by!(11),
            12 => by!(12),
            13 => by!(13),
            14 => by!(14),
            15 => by!(15),
            _ => {}
        }
    }
    x.iter().map(|&v| signed(v, (v.unsigned_abs() + bias) / divisor.unsigned_abs())).collect()
}

/// The finest step on the grid whose cell reaches the allowance.
///
/// A step of `d` spreads its error over a span of `d`, a power of `d^2/12`.
pub fn divisor_grid() -> impl Iterator<Item = (i32, i32)> {
    (1..8).map(|d| (d, 1)).chain(
        (3..=frame::MAX_LEVEL_COARSE).flat_map(|c| {
            let step = 1i32 << (c - 3);
            (0..8).map(move |f| ((1i32 << c) + f * step, step))
        }),
    )
}

/// The next step up the grid, which is the smallest move the format can name
/// from here.
pub fn next_divisor(d: i32) -> i32 {
    if d < 8 {
        return d + 1;
    }
    let coarse = (i32::BITS - 1 - d.leading_zeros()) as i32;
    d + (1i32 << (coarse - 3))
}

/// The step below this one on the grid.
pub fn prev_divisor(d: i32) -> i32 {
    if d <= 8 {
        return (d - 1).max(1);
    }
    let coarse = (i32::BITS - 1 - d.leading_zeros()) as i32;
    if d == 1i32 << coarse {
        d - (1i32 << (coarse - 4))
    } else {
        d - (1i32 << (coarse - 3))
    }
}

/// The coarsest step that divides every sample of a block, which is the
/// finest one worth coding it at: everything below it carries the block's
/// trailing zeros and nothing else. Material carried at less than the depth
/// it is stored in - sixteen bit content in twenty four bit words, say -
/// leaves that step well above one, and taking it is free.
pub fn common_step(left: &[i32], right: &[i32], ceiling: i32) -> i32 {
    let bits = left.iter().chain(right).fold(0i32, |a, &v| a | v);
    if bits == 0 {
        return 1;
    }
    (1i32 << bits.trailing_zeros().min(30)).clamp(1, ceiling)
}

/// Steps of the grid one doubling of the step covers, above the seven steps
/// at the head of it that go up by one.
pub const STEPS_PER_OCTAVE: i32 = 8;

/// Bytes a frame loses for each doubling of the coding step, as the slope of
/// frame length against the log of the step. The search
/// only takes it as a hint and weighs what it lands on, so it has to be near
/// and not exact.
pub const BYTES_PER_OCTAVE: f64 = 31.3;

/// Doublings one attempt may move up. The estimate is read from a frame that
/// is over budget, where it is least reliable, so the move it asks for is
/// capped and the bracket halves its way back from whatever it lands on.
/// Moving down is left uncapped, since a frame far under budget says nothing
/// about where the step belongs.
pub const COARSEN_REACH: f64 = 5.0;

/// Frames that fit the search may weigh before it settles for the finest of
/// them. Climbing to the first that fits is not counted: a frame has to fit
/// before it can be written at all.
pub const REFINEMENTS: u32 = 6;

pub fn grid_index(d: i32) -> i32 {
    if d < 8 {
        return d.max(1) - 1;
    }
    let coarse = (i32::BITS - 1 - d.leading_zeros()) as i32;
    7 + (coarse - 3) * STEPS_PER_OCTAVE + ((d - (1 << coarse)) >> (coarse - 3))
}

pub fn grid_divisor(index: i32) -> i32 {
    if index < 7 {
        return index.max(0) + 1;
    }
    let coarse = 3 + (index - 7) / STEPS_PER_OCTAVE;
    let f = (index - 7) % STEPS_PER_OCTAVE;
    (1i32 << coarse) + f * (1i32 << (coarse - 3))
}

#[cfg(test)]
mod grid_tests {
    use super::*;

    #[test]
    fn the_next_step_is_the_one_the_grid_holds() {
        let mut seen: Vec<i32> = divisor_grid().map(|(d, _)| d).collect();
        seen.dedup();
        for pair in seen.windows(2) {
            assert_eq!(next_divisor(pair[0]), pair[1], "after {}", pair[0]);
        }
    }

}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Mode {
    Constant,
    Coded { order: usize },
    Lpc { order: usize },
}

impl Mode {
    pub fn nibble(self) -> u8 {
        match self {
            Mode::Constant => 0,
            Mode::Coded { order } => order as u8 + 2,
            Mode::Lpc { .. } => LPC_NIBBLE,
        }
    }

    pub fn warmup(self) -> usize {
        match self {
            Mode::Constant => 0,
            Mode::Coded { order } => usize::from(order > 0),
            Mode::Lpc { order } => order,
        }
    }

    pub fn from_nibble(n: u8) -> Option<Mode> {
        match n {
            0 => Some(Mode::Constant),
            2..=14 => Some(Mode::Coded { order: n as usize - 2 }),
            _ => None,
        }
    }
}

fn warmup_bytes(signal: Signal, sample_bytes: usize) -> usize {
    match signal {
        Signal::Difference => sample_bytes + 1,
        _ => sample_bytes,
    }
}

#[derive(Clone, Debug)]
struct Plan {
    mode: Mode,
    warm: usize,
    shift: u32,
    values: Vec<u32>,
    lpc: Option<Quantized>,
    width: u32,
    escaped: Option<Escaped>,
}

/// How a signal would be coded, before the channel field has picked which two
/// signals the frame carries. Only those two are worth coding out.
struct Choice {
    mode: Mode,
    warm: usize,
    lpc: Option<Quantized>,
    residual: Vec<i32>,
    cost: u64,
}

impl Choice {
    fn constant() -> Choice {
        Choice { mode: Mode::Constant, warm: 0, lpc: None, residual: Vec::new(), cost: 0 }
    }

    fn into_plan(self) -> Plan {
        if self.mode == Mode::Constant {
            return Plan::constant();
        }
        Plan::coded(self.mode, zigzag_owned(self.residual), self.warm, self.lpc)
    }
}

impl Plan {
    fn constant() -> Plan {
        Plan {
            mode: Mode::Constant,
            warm: 0,
            width: 0,
            shift: 0,
            values: Vec::new(),
            lpc: None,
            escaped: None,
        }
    }

    fn coded(mode: Mode, values: Vec<u32>, warm: usize, lpc: Option<Quantized>) -> Plan {
        let coded = &values[warm..];
        Plan {
            mode,
            warm,
            width: coded.iter().copied().max().unwrap_or(0).max(1).ilog2() + 1,
            shift: select_shift(coded),
            values,
            lpc,
            escaped: None,
        }
    }
}

pub const LPC_NIBBLE: u8 = 15;

pub fn warmup_count(order: usize) -> usize {
    usize::from(order > 0)
}

fn fold(v: i32) -> u64 {
    (v ^ (v >> 31)) as u32 as u64
}

pub struct Fixed {
    pub order: usize,
    pub cost: u64,
    pub raw_history: bool,
}

/// Bytes a residual takes under a Rice code of the parameter its mean asks
/// for, plus whatever is sent raw ahead of it.
///
/// `folded` is the sum of the tail's magnitudes; a zigzag code carries twice
/// that, to within one per negative sample.
fn weigh(tail: usize, folded: u64, warm: usize, warm_bytes: usize) -> usize {
    if tail == 0 {
        return warm * warm_bytes;
    }
    let sum = folded * 2;
    let k = rice_parameter(tail, sum);
    let bits = tail * (k as usize + 1) + (sum >> k) as usize;
    warm * warm_bytes + bits.div_ceil(8)
}

pub fn select_order(x: &[i32], cap: usize) -> Option<Fixed> {
    select_order_over(x, &mut Rows::new(x), cap, 2)
}

fn select_order_over(x: &[i32], rows: &mut Rows, cap: usize, warm_bytes: usize) -> Option<Fixed> {
    let n = x.len();
    if n < 2 {
        return None;
    }
    assert!(cap <= MAX_ROWS);
    let mut held = ([0u64; MAX_ROWS], [0u64; MAX_ROWS]);
    let (a, b) = (&mut held.0[..cap], &mut held.1[..cap]);
    for k in 0..2.min(cap) {
        a[k] = rows.sum(k);
    }

    // Once the coded part of the residual stops shrinking it does not start
    // again, so the search stops there.
    let mut limit = cap;
    for k in 2..cap {
        a[k] = rows.sum(k);
        b[k] = rows.row(k)[1..k.min(n)].iter().map(|&v| fold(v)).sum();
        if a[k] - b[k] > a[k - 1] - b[k - 1] {
            limit = k;
            break;
        }
    }

    if a[1] == 0 && rows.row(1).iter().all(|&v| v == 0) {
        return None;
    }

    b[0] = a[0];
    // An order can send its history raw or code it. Weigh every order both
    // ways and take whichever writes out smallest; the sums are already here.
    let mut best: Option<(usize, Fixed)> = None;
    for k in 0..limit {
        if k >= 1 {
            b[k] = a[k] - b[k];
        }
        let coded = weigh(n - 1, a[k], warmup_count(k), warm_bytes);
        let mut take = |bytes: usize, f: Fixed| {
            if best.as_ref().is_none_or(|(b, _)| bytes < *b) {
                best = Some((bytes, f));
            }
        };
        take(coded, Fixed { order: k, cost: a[k], raw_history: false });
        if k >= 1 && k < n {
            let raw = weigh(n - k, b[k], k, warm_bytes);
            take(raw, Fixed { order: k, cost: b[k], raw_history: true });
        }
    }
    best.map(|(_, f)| f)
}

/// Packs each value's high bits into `width` bytes, big end first, reusing the
/// buffer it is given. The width is one of four, so say which rather than
/// copying a slice of it a value at a time.
fn pack_into(out: &mut Vec<u8>, values: &[u32], shift: u32, width: usize) {
    out.clear();
    out.reserve(values.len() * width);
    let q = |v: u32| if shift >= 32 { 0 } else { v >> shift };
    match width {
        1 => out.extend(values.iter().map(|&v| q(v) as u8)),
        2 => {
            for &v in values {
                out.extend_from_slice(&(q(v) as u16).to_be_bytes());
            }
        }
        3 => {
            for &v in values {
                let b = q(v).to_be_bytes();
                out.extend_from_slice(&b[1..]);
            }
        }
        _ => {
            for &v in values {
                out.extend_from_slice(&q(v).to_be_bytes());
            }
        }
    }
}

fn pack(values: &[u32], shift: u32, width: usize) -> Vec<u8> {
    let mut out = Vec::new();
    pack_into(&mut out, values, shift, width);
    out
}

fn pack_width(bits: u32, shift: u32) -> usize {
    match bits.saturating_sub(shift) {
        0..=8 => 1,
        9..=16 => 2,
        17..=24 => 3,
        _ => 4,
    }
}

fn rice_bytes(values: &[u32], shift: u32) -> usize {
    let bits: u32 = values.iter().map(|&v| code_len(v, shift)).sum();
    (bits as usize).div_ceil(8)
}

pub fn signal_cost(x: &[i32], sample_bytes: usize) -> u64 {
    plan(x, sample_bytes, sample_bytes, true).cost
}

/// Roughly what a choice takes to write out: its parameter byte, the
/// coefficients an LPC subframe carries, its warm-up samples at full width,
/// and the residual under a Rice code of the parameter its mean asks for.
///
/// The residual is weighed rather than coded. Picking the shift walks several
/// candidates over every value, which is worth doing once for the choice that
/// wins and not at all for the one that loses.
fn estimated_bytes(c: &Choice, warm_bytes: usize) -> usize {
    if c.mode == Mode::Constant {
        return warm_bytes;
    }
    let warm = c.warm.min(c.residual.len());
    let tail = &c.residual[warm..];
    let header = match c.mode {
        Mode::Lpc { order } => 2 + 2 * order,
        _ => 1,
    };
    if tail.is_empty() {
        return header + warm * warm_bytes;
    }
    let sum: u64 = tail.iter().map(|&v| zigzag(v) as u64).sum();
    let k = rice_parameter(tail.len(), sum);
    let bits = tail.len() * (k as usize + 1) + (sum >> k) as usize;
    header + warm * warm_bytes + bits.div_ceil(8)
}

/// Blocks a signal goes without the linear predictor being weighed, after one
/// where the fixed orders beat it. It helps on material a fixed order cannot
/// follow and next to nothing on the rest, so it is asked for again only
/// after it has lost.
pub const LPC_BACKOFF: u32 = 1;

/// Blocks the choice between the left channel and the difference is taken
/// from the block before rather than weighed again. The choice rarely changes
/// from one block to the next, and weighing the one the frame will not carry
/// is work the frame throws away.
pub const FIELD_BACKOFF: u32 = 2;

/// Blocks a slot goes without the escape coder being weighed at all, after
/// one where Golomb-Rice beat it everywhere. This is coarser than the backoff
/// inside a frame, which decides one slot at a time; a whole block skipped
/// costs nothing to decide.
pub const LZ_REST: u32 = 4;

/// Which way the signal would be coded, short of working out the codes: only
/// two of the three a frame weighs are carried, so the third is not worth
/// zigzagging and picking a parameter for.
fn plan(x: &[i32], sample_bytes: usize, warm_bytes: usize, lpc: bool) -> Choice {
    let mut rows = Rows::new(x);
    let Some(fixed) = select_order_over(x, &mut rows, MAX_ROWS, warm_bytes) else {
        return Choice::constant();
    };
    let fixed = fixed_plan(&mut rows, &fixed);
    if !lpc {
        return fixed;
    }
    match lpc_plan(x, sample_bytes) {
        Some(lpc) if estimated_bytes(&lpc, warm_bytes) < estimated_bytes(&fixed, warm_bytes) => lpc,
        _ => fixed,
    }
}

fn fixed_plan(rows: &mut Rows, fixed: &Fixed) -> Choice {
    let warm = if fixed.raw_history { fixed.order } else { warmup_count(fixed.order) };
    Choice {
        mode: Mode::Coded { order: fixed.order },
        warm,
        lpc: None,
        residual: rows.row(fixed.order).to_vec(),
        cost: fixed.cost,
    }
}

fn lpc_plan(x: &[i32], sample_bytes: usize) -> Option<Choice> {
    let padded;
    let fitted: &[i32] = if x.len() >= BLOCK_SIZE {
        x
    } else {
        padded = fitting_window(x);
        &padded
    };
    let q = lpc::analyse(fitted, x.len(), sample_bytes * 8)?;
    let order = q.coeffs.len();
    if order >= x.len() {
        return None;
    }
    let residual = lpc::residual(x, &[], &q);
    let cost: u64 = residual[order..].iter().map(|&v| v.unsigned_abs() as u64).sum();
    Some(Choice { mode: Mode::Lpc { order }, warm: order, lpc: Some(q), residual, cost })
}

fn fitting_window(x: &[i32]) -> Vec<i32> {
    let mut w = x.to_vec();
    w.resize(w.len().max(BLOCK_SIZE), 0);
    w
}

fn zigzag_owned(x: Vec<i32>) -> Vec<u32> {
    x.into_iter().map(zigzag).collect()
}


/// Quantizer steps the side may span, anywhere in the block, before it is
/// dropped and both channels are rebuilt from one.
///
/// A bound on its largest sample rather than its power: a side that stays
/// inside a few steps is inaudible against them wherever it sits, while one
/// with a handful of peaks is not, whatever its mean says.
pub const SIDE_STEPS: i32 = 3;

pub fn frame_lead_in(rate: u32) -> usize {
    usize::from(rate > 48000) * 7
}

/// Bits the rate leaves for each input sample of each channel.
pub fn bits_per_sample(rate: u32, kbps: u32) -> f64 {
    kbps as f64 * 1000.0 / (rate as f64 * 2.0)
}

/// At or below this many bits a sample, a 96 kHz stream is carried as half as
/// many samples rather than whole.
pub const HALVE_AT_OR_BELOW: f64 = 3.125;

/// Halving puts the anti-alias filter's delay into the stream, so this has to
/// answer the same for the whole of one: were it to change part way through,
/// the output would step by [`lpf::GROUP_DELAY`] samples where it did. A rate
/// that adapts has to stay one side of this or compensate the delay.
pub fn halved(rate: u32, kbps: u32) -> bool {
    rate == 96000 && bits_per_sample(rate, kbps) <= HALVE_AT_OR_BELOW
}

pub fn rice_parameter(n: usize, sum: u64) -> u32 {
    if n == 0 {
        return 1;
    }
    let mean = sum / n as u64;
    (u64::BITS - mean.leading_zeros()).max(1)
}

/// Parameters below the one the mean asks for that are still tried.
pub const SHIFT_SEARCH: u32 = 4;

pub fn select_shift(values: &[u32]) -> u32 {
    let sum: u64 = values.iter().map(|&v| v as u64).sum();
    let start = rice_parameter(values.len(), sum);
    let floor = start.saturating_sub(SHIFT_SEARCH);
    let mut k = start;
    let mut best = u32::MAX;
    let mut shift = 0;
    loop {
        let bits: u32 = values.iter().map(|&v| code_len(v, k)).sum();
        if bits >= 1 {
            let bytes = (bits + 7) >> 3;
            if bytes >= best {
                break;
            }
            best = bytes;
            shift = k;
        }
        if k == floor {
            break;
        }
        k -= 1;
    }
    shift
}

#[derive(Clone, Debug, Default)]
pub struct Trace {
    pub offset: i32,
    pub divisor: [i32; 2],
    pub resizes: u32,
    pub probes: u32,
    pub len: usize,
    pub budget: usize,
}

#[derive(Copy, Clone, Debug)]
pub struct Config {
    pub sample_rate: u32,
    pub bits_per_sample: u32,
    pub bitrate_kbps: u32,
}

impl Config {
    pub fn frame_budget(&self) -> usize {
        self.budget_for(self.bitrate_kbps)
    }

    fn budget_for(&self, kbps: u32) -> usize {
        kbps as usize * 125 * BLOCK_SIZE / self.sample_rate as usize
    }

    pub fn divisor_ceiling(&self) -> i32 {
        1i32 << self.bits_per_sample.min(frame::MAX_LEVEL_COARSE + 2).saturating_sub(2)
    }

    fn sample_bytes(&self) -> usize {
        if self.bits_per_sample > 16 {
            3
        } else {
            2
        }
    }
}

pub struct Encoder {
    config: Config,
    /// Steps of the grid the last frame settled above the finest step that
    /// block could be coded at, which is where the next one starts looking.
    offset: i32,
    pub trace: Trace,
    started: bool,
    /// Match finder for the escape coder. The table is never cleared, so every
    /// attempt of every block leaves it where it was.
    lz: lz::Table,
    lpf: [lpf::Filter; 2],
    lz_seen: u32,
    /// Scratch the escape coder packs each candidate into, kept so a block
    /// does not allocate one per candidate.
    packed: Vec<u8>,
    escapes: EscapeMemo,
    memo_misses: u32,
    memo_rest: u32,
    /// The channel field the last frame carried, and the blocks left before
    /// the choice between the left channel and the difference is looked at
    /// again rather than taken from it.
    last_field: u8,
    field_rest: u32,
    /// Blocks left before the linear predictor is weighed again, per signal.
    lpc_rest: [u32; 3],
    /// The same for the escape coder, decided for a whole block so that every
    /// attempt at the frame weighs the same candidates.
    lz_rest: [u32; 2],
}

/// Misses in a row before the memo stops being consulted for a while.
const MEMO_PATIENCE: u32 = 4;

/// Split searches that go unremembered after a run of misses. Hashing the
/// residual is worth nothing on material that never repeats, and the material
/// that does repeat goes on repeating long after this is over.
const MEMO_REST: u32 = 32;

/// Slots the weigher remembers split searches in. Holding the table inside a
/// level of cache is worth more than holding every search it ever ran.
const ESCAPE_MEMO: usize = 8192;

/// The split the escape coder settled on for a residual, and what it cost.
///
/// Searching for it packs the residual and runs the match finder once per
/// candidate split, and the step search asks for the same residual again and
/// again on material that repeats - which is the material it spends longest
/// on. Direct mapped, keyed by a hash of the residual with the width and the
/// Rice cost it has to beat, and wrong only where a whole 64 bit key collides.
struct EscapeMemo {
    key: [u64; ESCAPE_MEMO],
    total: [i32; ESCAPE_MEMO],
    shift: [u32; ESCAPE_MEMO],
}

impl Default for EscapeMemo {
    fn default() -> Self {
        EscapeMemo { key: [0; ESCAPE_MEMO], total: [0; ESCAPE_MEMO], shift: [0; ESCAPE_MEMO] }
    }
}

impl EscapeMemo {
    fn get(&self, key: u64) -> Option<Option<(usize, u32)>> {
        let at = (key as usize) & (ESCAPE_MEMO - 1);
        if self.key[at] != key {
            return None;
        }
        Some(usize::try_from(self.total[at]).ok().map(|t| (t, self.shift[at])))
    }

    fn put(&mut self, key: u64, best: Option<(usize, u32)>) {
        let at = (key as usize) & (ESCAPE_MEMO - 1);
        self.key[at] = key;
        match best {
            Some((total, shift)) => {
                self.total[at] = i32::try_from(total).unwrap_or(i32::MAX);
                self.shift[at] = shift;
            }
            None => self.total[at] = -1,
        }
    }
}

const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;

fn mix(h: u64, v: u64) -> u64 {
    (h ^ v).wrapping_mul(FNV_PRIME)
}

/// The residual the split search is about to be run over, four lanes at a time
/// so that the multiplies do not queue behind one another.
fn hash_values(values: &[u32]) -> u64 {
    let mut lanes = [FNV_OFFSET, FNV_OFFSET ^ 1, FNV_OFFSET ^ 2, FNV_OFFSET ^ 3];
    let mut rest = values;
    while let Some((four, tail)) = rest.split_first_chunk::<4>() {
        for (lane, &v) in lanes.iter_mut().zip(four) {
            *lane = mix(*lane, u64::from(v));
        }
        rest = tail;
    }
    let mut h = mix(mix(lanes[0], lanes[1]), mix(lanes[2], lanes[3]));
    for &v in rest {
        h = mix(h, u64::from(v));
    }
    mix(h, values.len() as u64)
}

/// Splits tried past the best one before the search for it gives up.
const LZ_SPLIT_PATIENCE: u32 = 2;

/// Slots the escape coder sits out after a block it could not beat the Rice
/// code on.
const LZ_BACKOFF: u32 = 6;

#[derive(Clone, Debug)]
struct Escaped {
    shift: u32,
    width: usize,
    stream: Vec<u8>,
}

/// Bytes at the front of a frame before either channel's parameters: the sync,
/// the length, the mode nibbles and the two coding levels.
const FIXED_HEADER: usize = 6;

/// One slot of the payload: a sub-block of a channel, and the values it
/// carries. Both the writer and the weigher walk these, so neither can take a
/// different view of the layout than the other.
struct Slot<'a> {
    /// The first sub-block, which is where the warm-up and any escape stream
    /// go ahead of the values.
    first: bool,
    ch: usize,
    plan: &'a Plan,
    span: std::ops::Range<usize>,
}

/// A frame worked out but not yet written, and how long it would be.
struct Built {
    plans: [Plan; 2],
    chosen: [Signal; 2],
    field: u8,
    divisor: [i32; 2],
    heads: [Vec<i32>; 2],
    span: usize,
    len: usize,
}

impl Built {
    fn slots(&self) -> impl Iterator<Item = Slot<'_>> {
        let len = sub_len(self.span);
        (0..SUBBLOCKS).flat_map(move |sub| {
            self.plans.iter().enumerate().filter_map(move |(ch, p)| {
                (p.mode != Mode::Constant).then(|| Slot {
                    first: sub == 0,
                    ch,
                    plan: p,
                    span: (sub * len).max(p.warm)..(sub + 1) * len,
                })
            })
        })
    }
}

#[derive(Clone, Debug)]
pub struct Frame {
    pub bytes: Vec<u8>,
    pub field: u8,
    pub divisor: [i32; 2],
    pub modes: [Mode; 2],
    pub shifts: [u32; 2],
}

impl Encoder {
    pub fn new(config: Config) -> Encoder {
        Encoder {
            config,
            offset: 0,
            trace: Trace::default(),
            started: false,
            lpf: Default::default(),
            lz: lz::Table::default(),
            lz_seen: LZ_BACKOFF,
            packed: Vec::new(),
            escapes: EscapeMemo::default(),
            memo_misses: 0,
            memo_rest: 0,
            last_field: 0,
            field_rest: 0,
            lpc_rest: [0; 3],
            lz_rest: [0; 2],
        }
    }

    fn weigh_escape(&mut self, p: &mut Plan, slot: usize) {
        if self.lz_rest[slot.min(1)] > 0 || p.mode == Mode::Constant || p.values.len() <= p.warm {
            return;
        }
        let values = &p.values[p.warm..];
        let rice = rice_bytes(values, p.shift);
        if self.lz_seen < LZ_BACKOFF {
            self.lz_seen += 1;
            return;
        }
        self.lz_seen = 0;
        let key = if self.memo_rest > 0 {
            self.memo_rest -= 1;
            None
        } else {
            Some(mix(mix(hash_values(values), rice as u64), u64::from(p.width)))
        };
        if let Some(known) = key.and_then(|k| self.escapes.get(k)) {
            self.memo_misses = 0;
            return self.take_escape(p, known, rice);
        }
        let mut best: Option<(usize, u32)> = None;
        // The low bits go out raw and the high bits through the match finder,
        // so as the split moves down the raw half shrinks and the other grows.
        // Once the pair of them stops shrinking it does not start again.
        let floor = p.width.saturating_sub(24);
        let mut rising = 0u32;
        for shift in (floor..p.width.saturating_sub(1)).rev() {
            let low = (shift as usize * values.len()).div_ceil(8);
            if low >= rice {
                continue;
            }
            if rising >= LZ_SPLIT_PATIENCE {
                break;
            }
            pack_into(&mut self.packed, values, shift, pack_width(p.width, shift));
            let buf = if slot == 0 { lz::Buffer::First } else { lz::Buffer::Second };
            let Some(n) = lz::cost(&mut self.lz, &self.packed, rice - low, buf) else {
                continue;
            };
            match best {
                Some((b, _)) if n + low >= b => rising += 1,
                _ => {
                    rising = 0;
                    best = Some((n + low, shift));
                }
            }
        }
        if let Some(key) = key {
            self.escapes.put(key, best);
            self.memo_misses += 1;
            if self.memo_misses >= MEMO_PATIENCE {
                self.memo_misses = 0;
                self.memo_rest = MEMO_REST;
            }
        }
        self.take_escape(p, best, rice)
    }

    fn take_escape(&mut self, p: &mut Plan, best: Option<(usize, u32)>, rice: usize) {
        let Some((total, shift)) = best else { return };
        if total > rice {
            return;
        }
        let values = &p.values[p.warm..];
        let width = pack_width(p.width, shift);
        let packed = pack(values, shift, width);
        let room = frame::MAX_FRAME_LEN;
        let Some(stream) = lz::compress(&mut self.lz, &packed, room) else { return };
        self.lz_seen = LZ_BACKOFF;
        p.escaped = Some(Escaped { shift, width, stream });
    }

    pub fn encode_at(&mut self, left: &[i32], right: &[i32], divisor: [i32; 2]) -> Frame {
        let built = self.build(
            left,
            right,
            divisor,
            common_step(left, right, self.config.divisor_ceiling()),
            [true; 3],
            None,
        );
        self.write(&built)
    }

    fn write(&self, b: &Built) -> Frame {
        Frame {
            field: b.field,
            divisor: b.divisor,
            modes: [b.plans[0].mode, b.plans[1].mode],
            shifts: [b.plans[0].shift, b.plans[1].shift],
            bytes: {
                let (len, bytes) = self.assemble(b);
                debug_assert_eq!(len, b.len, "measured length is not what was written");
                bytes
            },
        }
    }

    pub fn set_bitrate(&mut self, kbps: u32) {
        self.config.bitrate_kbps = kbps;
    }

    pub fn config_budget(&self) -> usize {
        self.config.frame_budget()
    }

    pub fn encode_block(&mut self, left: &[i32], right: &[i32]) -> Frame {
        assert!(left.len() >= BLOCK_SIZE);
        assert!(right.len() >= BLOCK_SIZE);
        let block = (&left[..BLOCK_SIZE], &right[..BLOCK_SIZE]);
        if halved(self.config.sample_rate, self.config.bitrate_kbps) {
            let bits = self.config.bits_per_sample;
            let thinned = lpf::run_pair(&mut self.lpf, [block.0, block.1], bits, 2);
            return self.code(&thinned[0], &thinned[1]);
        }
        let lead = frame_lead_in(self.config.sample_rate);
        if self.started || lead == 0 {
            return self.code(block.0, block.1);
        }
        // The first block has nothing before it, so it opens on silence.
        let lead_in = |x: &[i32]| -> Vec<i32> {
            let mut v = vec![0i32; lead];
            v.extend_from_slice(&x[..BLOCK_SIZE - lead]);
            v
        };
        let (held_l, held_r) = (lead_in(left), lead_in(right));
        self.code(&held_l, &held_r)
    }

    /// Codes one block at the finest step of the grid that keeps the frame
    /// inside its budget.
    ///
    /// The step the last frame settled on is the seed, since neighbouring
    /// blocks want much the same step. From there the search weighs a frame,
    /// reads how far it missed the budget by, and moves by what
    /// [`BYTES_PER_OCTAVE`] says that miss is worth, keeping the finest step
    /// it has seen fit and the coarsest it has seen overrun. A hint outside
    /// that bracket is replaced by halving it.
    ///
    /// Frame length does not always fall as the step coarsens: quantizing a
    /// signal the predictor follows closely can cost more than it saves. A
    /// coarser step that fails to shorten the frame says the block is one of
    /// those, and the search restarts from the finest step instead.
    fn code(&mut self, left: &[i32], right: &[i32]) -> Frame {
        let cap = self.config.frame_budget().min(frame::MAX_FRAME_LEN);
        let ceiling = self.config.divisor_ceiling();
        let top = grid_index(ceiling);
        let common = common_step(left, right, ceiling);
        // Settled before the search so that every attempt at the frame weighs
        // the same candidates.
        let lpc = self.lpc_rest.map(|rest| rest == 0);
        let lz = self.lz_rest.map(|rest| rest == 0);
        let assume = (self.field_rest > 0).then_some(self.last_field);
        let base = grid_index(common);
        if std::env::var_os("V3_CHECK").is_some() {
            let d = common_step(left, right, ceiling);
            let bad = left.iter().chain(right).filter(|v| **v % d != 0).count();
            if bad > 0 {
                eprintln!("common_step {d} does not divide {bad} samples");
            }
        }
        let seat = (base + self.offset).clamp(base, top);
        let step = |k: i32| {
            let d = grid_divisor((seat + k).clamp(base, top));
            [d, d]
        };

        let mut k = 0;
        let mut fit: Option<(Built, i32)> = None;
        let mut over: Option<i32> = None;
        let mut overran = None;
        let mut turned = false;
        let mut probes = 0;
        let mut fitted = 0;
        let mut resizes = 0;
        let entry = LZ_BACKOFF;
        let mut kept_seen = entry;
        self.lz_seen = entry;
        loop {
            let divisor = step(k);
            let frame = self.build(left, right, divisor, common, lpc, assume);
            let leaves = std::mem::replace(&mut self.lz_seen, entry);
            probes += 1;
            let pinned = divisor.iter().all(|&c| c >= ceiling);
            let fits = frame.len <= cap || pinned;

            let miss = frame.len as f64 - cap as f64;
            let here = grid_divisor(seat + k);
            let octaves = (miss / BYTES_PER_OCTAVE).min(COARSEN_REACH);
            let there = (here as f64 * (octaves * std::f64::consts::LN_2).exp())
                .clamp(1.0, ceiling as f64) as i32;
            let mut want = k + grid_index(there) - grid_index(here);
            if want == k {
                want += if fits { -1 } else { 1 };
            }

            if fits {
                kept_seen = leaves;
                fitted += 1;
                // Being pinned at the coarsest step only ends the search
                // when the frame still overruns there, since then there is
                // nothing left to try.
                // One step of the grid is worth about this many bytes, so
                // with less room than that left there is no finer step that
                // would fit and nothing to look for.
                let done = frame.len > cap
                    || seat + k <= base
                    || fitted > REFINEMENTS
                    || -miss < BYTES_PER_OCTAVE / STEPS_PER_OCTAVE as f64;
                fit = Some((frame, k));
                if done {
                    break;
                }
            } else {
                resizes += 1;
                if !turned && overran.is_some_and(|(at, len)| at < k && frame.len >= len) {
                    turned = true;
                    (over, fit) = (None, None);
                    k = base - seat;
                    continue;
                }
                overran = Some((k, frame.len));
                over = Some(k);
            }

            let next = match (over, &fit) {
                (Some(l), Some((_, at))) if l + 1 >= *at => break,
                (Some(l), Some((_, at))) => {
                    if want <= l || want >= *at {
                        (l + *at) / 2
                    } else {
                        want
                    }
                }
                (Some(l), None) => want.clamp(l + 1, top - seat),
                (None, Some((_, at))) => want.clamp(base - seat, *at - 1),
                (None, None) => unreachable!("a probe is one of the two"),
            };
            if next == k {
                break;
            }
            k = next;
        }
        let (out, settled) = fit.expect("the search keeps every frame that fits");
        // A signal the frame does not carry is left where it was; one it
        // carries either keeps the predictor or sends it away for a while.
        for (slot, signal) in out.chosen.iter().enumerate() {
            let at = match signal {
                Signal::Left => 0,
                Signal::Right => 1,
                Signal::Difference => 2,
            };
            if lpc[at] {
                self.lpc_rest[at] =
                    if matches!(out.plans[slot].mode, Mode::Lpc { .. }) { 0 } else { LPC_BACKOFF };
            }
        }
        for (at, rest) in self.lpc_rest.iter_mut().enumerate() {
            if !lpc[at] {
                *rest -= 1;
            }
        }
        for (slot, rest) in self.lz_rest.iter_mut().enumerate() {
            if lz[slot] {
                *rest = if out.plans[slot].escaped.is_some() { 0 } else { LZ_REST };
            } else {
                *rest -= 1;
            }
        }
        self.lz_seen = kept_seen;
        self.offset = (seat + settled - base).clamp(0, top);

        self.last_field = out.field;
        self.field_rest = if self.field_rest > 0 { self.field_rest - 1 } else { FIELD_BACKOFF };
        self.started = true;
        self.trace = Trace {
            offset: self.offset,
            divisor: out.divisor,
            resizes,
            probes,
            len: out.len,
            budget: cap,
        };
        self.write(&out)
    }

    fn build(
        &mut self,
        left: &[i32],
        right: &[i32],
        pair: [i32; 2],
        common: i32,
        lpc: [bool; 3],
        assume: Option<u8>,
    ) -> Built {
        let keep = pair[0].min(pair[1]);
        let l = quantize_all(left, pair[0]);
        let r = quantize_all(right, pair[1]);
        // The difference is formed at the smaller of the two divisors, which
        // is the one the channel it leaves standing was already divided by.
        let held;
        let (kl, kr): (&[i32], &[i32]) = if pair[0] == pair[1] {
            (&l, &r)
        } else if keep == pair[0] {
            held = quantize_all(right, keep);
            (&l, &held)
        } else {
            held = quantize_all(left, keep);
            (&held, &r)
        };
        // Which of the left channel and the difference the frame carries
        // rarely changes from one block to the next, so only the one it
        // carried last time is formed and weighed until the choice is looked
        // at again.
        let (weigh_left, weigh_side) = match assume {
            Some(0) => (true, false),
            Some(_) => (false, true),
            None => (true, true),
        };
        let mut d: Vec<i32> = Vec::new();
        if weigh_side {
            d.extend(kl.iter().zip(kr).map(|(&a, &b)| b - a));
            // Only where the step is already losing something: a step that
            // divides every sample of the block leaves the side as the one
            // thing the frame would get wrong.
            if keep > common && d.iter().all(|&v| (-SIDE_STEPS..=SIDE_STEPS).contains(&v)) {
                d.fill(0);
            }
        }

        let sample_bytes = self.config.sample_bytes();
        let plan_r = plan(&r, sample_bytes, warmup_bytes(Signal::Right, sample_bytes), lpc[1]);
        let plan_l = weigh_left
            .then(|| plan(&l, sample_bytes, warmup_bytes(Signal::Left, sample_bytes), lpc[0]));
        let plan_d = weigh_side.then(|| {
            plan(&d, sample_bytes, warmup_bytes(Signal::Difference, sample_bytes), lpc[2])
        });

        // The difference stands in for whichever channel asked for the coarser
        // divisor, and only when it costs less than that channel does. A frame
        // that carries it writes both slots at the divisor it left standing.
        let takes_second_slot = pair[0] < pair[1];
        let field = match (&plan_l, &plan_d) {
            (Some(l), Some(d)) => {
                let replaced = if takes_second_slot { plan_r.cost } else { l.cost };
                match (d.cost < replaced, takes_second_slot) {
                    (false, _) => 0,
                    (true, true) => 1,
                    (true, false) => 2,
                }
            }
            (Some(_), None) => 0,
            _ => 2,
        };
        let divisor = if field == 0 { pair } else { [keep; 2] };

        let chosen = signals(field);
        let (first, second) = match field {
            0 => (plan_l.unwrap(), plan_r),
            1 => (plan_l.unwrap(), plan_d.unwrap()),
            _ => (plan_d.unwrap(), plan_r),
        };
        let (sig_first, sig_second): (&[i32], &[i32]) = match field {
            0 => (&l, &r),
            1 => (&l, &d),
            _ => (&d, &r),
        };
        let mut plans = [first.into_plan(), second.into_plan()];
        for (slot, p) in plans.iter_mut().enumerate() {
            self.weigh_escape(p, slot);
        }
        // All assembly wants of the signals is what each slot sends raw, and
        // how long the first one is; carrying that instead of the slices lets
        // a frame be weighed now and written later, or not at all.
        let head = |x: &[i32], warm: usize| x[..warm.max(1).min(x.len())].to_vec();
        let mut built = Built {
            span: sig_first.len(),
            heads: [head(sig_first, plans[0].warm), head(sig_second, plans[1].warm)],
            plans,
            chosen,
            field,
            divisor,
            len: 0,
        };
        built.len = self.measure(&built);
        built
    }

    /// How long the frame would be, counted rather than written: the codes are
    /// weighed where `assemble` would pack them. `write` checks the two agree
    /// on every frame it puts out, so this cannot drift from it unnoticed.
    fn slot_bytes(p: &Plan, sample_bytes: usize) -> usize {
        match p.mode {
            Mode::Constant => sample_bytes,
            _ => {
                let lpc = p.lpc.as_ref().map_or(0, |q| 1 + 2 * q.coeffs.len());
                1 + usize::from(p.escaped.is_some()) + lpc
            }
        }
    }

    fn measure(&self, b: &Built) -> usize {
        let sample_bytes = self.config.sample_bytes();
        let mut bytes = FIXED_HEADER
            + b.plans.iter().map(|p| Self::slot_bytes(p, sample_bytes)).sum::<usize>();
        // Each slot is aligned on its own, so each rounds up on its own too.
        for s in b.slots() {
            let p = s.plan;
            let mut bits = if s.first {
                p.warm * warmup_bytes(b.chosen[s.ch], sample_bytes) * 8
            } else {
                0
            };
            bits += match &p.escaped {
                Some(e) => {
                    let head = if s.first { e.stream.len() * 8 } else { 0 };
                    head + s.span.len() * e.shift as usize
                }
                None => s.span.map(|i| code_len(p.values[i], p.shift) as usize).sum(),
            };
            bytes += bits.div_ceil(8);
        }
        bytes + (frame::LEN_GRANULE - bytes % frame::LEN_GRANULE) % frame::LEN_GRANULE
    }

    fn assemble(&self, b: &Built) -> (usize, Vec<u8>) {
        let (plans, chosen, field, divisor) = (&b.plans, &b.chosen, b.field, b.divisor);
        let sig: [&[i32]; 2] = [&b.heads[0], &b.heads[1]];
        let sample_bytes = self.config.sample_bytes();
        let mut mem = vec![0u8; FIXED_HEADER];
        mem[0] = SYNC;
        mem[3] = (plans[0].mode.nibble() << 4) | plans[1].mode.nibble();
        mem[4] = Level::encode(divisor[0]);
        mem[5] = Level::encode(divisor[1]);

        for (ch, p) in plans.iter().enumerate() {
            match p.mode {
                Mode::Constant => push_be(&mut mem, sig[ch][0], sample_bytes),
                _ => {
                    let tag = if p.warm >= 2 { 0x80 } else { 0x00 };
                    match &p.escaped {
                        Some(e) => {
                            mem.push(e.shift as u8 | tag | ESCAPE);
                            mem.push(e.width as u8);
                        }
                        None => mem.push(p.shift as u8 | tag),
                    }
                    if let Some(q) = &p.lpc {
                        let order = q.coeffs.len() as u8;
                        let packed = (q.shift as u8 & 0x1f) | (order << 5);
                        mem.push(packed.wrapping_add(0xe0));
                        for &c in &q.coeffs {
                            mem.extend_from_slice(&(c as i16).to_be_bytes());
                        }
                    }
                }
            }
        }

        let mut w = BitWriter::with_capacity(frame::MAX_FRAME_LEN);
        for s in b.slots() {
            let p = s.plan;
            if s.first {
                let n = warmup_bytes(chosen[s.ch], sample_bytes);
                for &v in &sig[s.ch][..p.warm] {
                    for k in (0..n).rev() {
                        w.write((v >> (8 * k)) as u32 & 0xff, 8);
                    }
                }
            }
            match &p.escaped {
                Some(e) => {
                    if s.first {
                        for &byte in &e.stream {
                            w.write(byte as u32, 8);
                        }
                    }
                    for i in s.span {
                        w.write(p.values[i], e.shift);
                    }
                }
                None => {
                    for i in s.span {
                        write_value(&mut w, p.values[i], p.shift);
                    }
                }
            }
            w.align();
        }

        mem.extend_from_slice(&w.finish());
        let mut len = mem.len();
        len += (frame::LEN_GRANULE - len % frame::LEN_GRANULE) % frame::LEN_GRANULE;
        while mem.len() < len {
            mem.push(0);
        }
        let whole = u8::from(b.span == BLOCK_SIZE) << 7;
        mem[1] = whole | (field << 5) | ((len >> 8) as u8 & frame::LEN_HIGH_MASK);
        mem[2] = len as u8;

        let mut out = Vec::with_capacity(len);
        for word in mem.chunks(frame::LEN_GRANULE) {
            out.extend(word.iter().rev());
        }
        (len, out)
    }
}

fn push_be(out: &mut Vec<u8>, value: i32, bytes: usize) {
    for k in (0..bytes).rev() {
        out.push((value >> (8 * k)) as u8);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lhdcv3_format::value::unzigzag;
    use lhdcv3_format::{decoder, frame};

    fn cfg() -> Config {
        Config { sample_rate: 48000, bits_per_sample: 16, bitrate_kbps: 900 }
    }

    #[test]
    fn the_rice_parameter_is_the_bit_length_of_the_mean() {
        let reference = |mean: u64| {
            let mut k = 1u32;
            let mut m = mean;
            while m >= 2 {
                let next = m >> 1;
                k += 1;
                if m <= 3 {
                    break;
                }
                m = next;
            }
            k
        };
        for mean in (0..300).chain([1000, 65535, 1 << 20, u32::MAX as u64]) {
            assert_eq!(rice_parameter(1, mean), reference(mean), "mean={mean}");
        }
    }

    fn divided(x: &[i32], divisor: i32) -> Vec<i32> {
        if divisor <= 1 {
            return x.to_vec();
        }
        let bias = i64::from(rounding_bias(x, divisor));
        let d = i64::from(divisor);
        x.iter()
            .map(|&v| {
                let a = (i64::from(v).abs() + bias) / d;
                (if v < 0 { -a } else { a }) as i32
            })
            .collect()
    }

    #[test]
    fn every_step_of_the_grid_divides_the_way_the_format_says() {
        let steps: Vec<i32> = divisor_grid().map(|(d, _)| d).take(120).collect();
        for &d in &steps {
            for &v in &[0i32, 1, -1, 7, -7, 12345, -12345, 8_388_607, -8_388_608, 65_535] {
                assert_eq!(quantize_all(&[v], d), divided(&[v], d), "{v} over {d}");
            }
        }
    }

    #[test]
    fn dividing_a_signal_matches_dividing_each_sample() {
        let x: Vec<i32> = (-600..600).chain([i32::MIN / 2, i32::MAX / 2]).collect();
        for divisor in [0, 1, 2, 3, 4, 7, 8, 64, 73, 1024, 4095, 1 << 22] {
            assert_eq!(quantize_all(&x, divisor), divided(&x, divisor), "divisor={divisor}");
        }
    }

    #[test]
    fn the_divisor_stops_two_below_the_sample_depth() {
        let deep = Config { sample_rate: 96000, bits_per_sample: 24, bitrate_kbps: 128 };
        let shallow = Config { bits_per_sample: 16, ..deep };
        assert_eq!(deep.divisor_ceiling(), 1 << 22);
        assert_eq!(shallow.divisor_ceiling(), 1 << 14);
    }

    #[test]
    fn the_first_block_carries_its_lead_in_as_silence() {
        let mut e = Encoder::new(Config {
            sample_rate: 96000,
            bits_per_sample: 16,
            bitrate_kbps: 900,
        });
        let lead = frame_lead_in(96000);
        let x: Vec<i32> = (0..BLOCK_SIZE + lead).map(|i| 1000 + i as i32).collect();
        let f = e.encode_block(&x, &x);
        let d = decoder::decode(&f.bytes, 2).unwrap();
        assert_eq!(&d.left[..lead], &vec![0; lead][..]);
        assert_eq!(d.left[lead], 1000);
    }

    #[test]
    fn zigzag_round_trips() {
        for v in [0i32, 1, -1, 2, -2, 100, -100, i32::MAX / 2, i32::MIN / 2] {
            assert_eq!(unzigzag(zigzag(v)), v, "v={v}");
        }
        assert_eq!(zigzag(0), 0);
        assert_eq!(zigzag(-1), 1);
        assert_eq!(zigzag(1), 2);
        assert_eq!(zigzag(-8), 15);
    }

    #[test]
    fn the_channel_field_picks_the_signals() {
        assert_eq!(signals(0), [Signal::Left, Signal::Right]);
        assert_eq!(signals(1), [Signal::Left, Signal::Difference]);
        assert_eq!(signals(2), [Signal::Difference, Signal::Right]);
    }

    #[test]
    fn a_silent_block_gives_the_captured_minimal_frame() {
        let mut e = Encoder::new(cfg());
        let f = e.encode_block(&[0; BLOCK_SIZE], &[0; BLOCK_SIZE]);
        assert_eq!(f.bytes, vec![0x00, 0x0c, 0x80, 0x4c, 0x00, 0x00, 0x01, 0x01, 0, 0, 0, 0]);
    }

    #[test]
    fn a_constant_block_carries_both_levels_as_warm_up() {
        let mut e = Encoder::new(cfg());
        let f = e.encode_block(&[100; BLOCK_SIZE], &[200; BLOCK_SIZE]);
        // Four divides both levels, so the step costs the block nothing.
        assert_eq!(
            f.bytes,
            vec![0x00, 0x0c, 0x80, 0x4c, 0x19, 0x00, 0x04, 0x04, 0x00, 0x00, 0x32, 0x00]
        );
        let back = lhdcv3_format::decoder::decode(&f.bytes, 2).unwrap();
        assert_eq!(back.left[0], 100);
        assert_eq!(back.right[0], 200);
    }

    #[test]
    fn every_frame_is_a_whole_number_of_granules() {
        let mut e = Encoder::new(cfg());
        let l: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 % 7) - 3).collect();
        let r: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 % 5) - 2).collect();
        let f = e.encode_block(&l, &r);
        assert_eq!(f.bytes.len() % frame::LEN_GRANULE, 0);
    }

    #[test]
    fn what_is_emitted_parses_back() {
        let mut e = Encoder::new(cfg());
        let l: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 * 37) % 4001 - 2000).collect();
        let r: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 * 11) % 1301 - 650).collect();
        let f = e.encode_block(&l, &r);
        let h = frame::Header::parse(&f.bytes).unwrap();
        assert_eq!(h.len, f.bytes.len());
        assert_eq!(h.channel_field(), f.field);
    }

    #[test]
    fn a_constant_signal_codes_nothing() {
        let p = plan(&[500; BLOCK_SIZE], 2, 2, true).into_plan();
        assert_eq!(p.mode, Mode::Constant);
        assert!(p.values.is_empty());
    }

    #[test]
    fn a_block_divides_to_the_nearest_level_only_where_it_spans_enough_of_them() {
        let narrow = [7i32, -7];
        assert_eq!(quantize_all(&narrow, 2), vec![3, -3]);
        assert_ne!(quantize_all(&narrow, 2)[1], -7 >> 1);
        let wide = [1857i32, -1857];
        assert_eq!(quantize_all(&wide, 2), vec![929, -929]);
        assert_eq!(quantize_all(&wide, 4), vec![464, -464]);
        assert_eq!(quantize_all(&[-1i32, 1], 32), vec![0, 0]);
    }

    #[test]
    fn the_derived_parameter_is_the_bit_length_of_the_mean() {
        assert_eq!(rice_parameter(4, 0), 1);
        assert_eq!(rice_parameter(4, 4), 1);
        assert_eq!(rice_parameter(4, 8), 2);
        assert_eq!(rice_parameter(4, 16), 3);
        let values = [5u32, 3, 1, 0, 2, 4, 6];
        let n = values.len();
        let sum: u64 = values.iter().map(|&v| v as u64).sum();
        let best = (0..8)
            .min_by_key(|&k| values.iter().map(|&v| code_len(v, k) as usize).sum::<usize>())
            .unwrap();
        assert_ne!(rice_parameter(n, sum), best);
    }

    #[test]
    fn linear_prediction_has_to_pay_for_its_coefficients() {
        // Two choices whose residuals cost the same: the one carrying
        // coefficients is the more expensive to write out.
        let residual: Vec<i32> = (0..BLOCK_SIZE).map(|i| ((i % 7) as i32) - 3).collect();
        let fixed = Choice {
            mode: Mode::Coded { order: 2 },
            warm: 1,
            lpc: None,
            residual: residual.clone(),
            cost: 0,
        };
        let lpc = Choice { mode: Mode::Lpc { order: 8 }, warm: 8, lpc: None, residual, cost: 0 };
        let (a, b) = (estimated_bytes(&fixed, 2), estimated_bytes(&lpc, 2));
        assert!(b > a + 16, "{b} should clear {a} by the coefficients and warm-up");
    }

    #[test]
    fn a_ramp_sends_a_full_order_of_history_raw() {
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| -25600 + i as i32 * 200).collect();
        let p = plan(&x, 2, 2, true);
        assert_eq!(p.mode, Mode::Coded { order: 2 });
        assert_eq!(p.warm, 2);
    }

    #[test]
    fn a_block_whose_warm_up_is_not_the_cost_sends_one_sample_raw() {
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 * 37) % 4001 - 2000).collect();
        let p = plan(&x, 2, 2, true);
        assert_eq!(p.warm, 1);
    }

    #[test]
    fn a_ramp_is_predicted_flat() {
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| i as i32 * 7).collect();
        let p = plan(&x, 2, 2, true).into_plan();
        assert_eq!(p.mode, Mode::Coded { order: 2 });
        assert_eq!(p.shift, 0);
        assert_eq!(p.values[1], zigzag(7));
        assert!(p.values[2..].iter().all(|&v| v == 0));
    }

    #[test]
    fn a_step_is_predicted_with_one_order() {
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| i32::from(i >= 10)).collect();
        let p = plan(&x, 2, 2, true);
        assert_eq!(p.mode, Mode::Coded { order: 1 });
    }

    #[test]
    fn the_mode_nibble_round_trips() {
        for m in [Mode::Constant, Mode::Coded { order: 0 }, Mode::Coded { order: 4 }] {
            assert_eq!(Mode::from_nibble(m.nibble()), Some(m));
        }
        assert_eq!(Mode::from_nibble(1), None);
        assert_eq!(Mode::from_nibble(15), None);
    }

    #[test]
    fn no_frame_outruns_the_length_field() {
        let l: Vec<i32> = (0..BLOCK_SIZE).map(|i| if i % 2 == 0 { -32768 } else { 32767 }).collect();
        let r: Vec<i32> = (0..BLOCK_SIZE).map(|i| if i % 3 == 0 { -32768 } else { 32767 }).collect();
        let mut e = Encoder::new(cfg());
        let f = e.encode_block(&l, &r);
        assert!(f.bytes.len() <= frame::MAX_FRAME_LEN, "len {}", f.bytes.len());
    }

    #[test]
    fn a_mono_block_codes_the_difference_as_constant() {
        let mut e = Encoder::new(cfg());
        let x: Vec<i32> = (0..BLOCK_SIZE).map(|i| (i as i32 * 37) % 4001 - 2000).collect();
        let f = e.encode_block(&x, &x);
        assert_eq!(f.field, 2);
        assert_eq!(f.modes[0], Mode::Constant);
    }

    #[test]
    fn only_ninety_six_kilohertz_halves_and_only_when_the_rate_is_thin() {
        use crate::tables::{kbps_for_index, BITRATE_KBPS};
        for rate in [44100u32, 48000] {
            for i in 0..=8 {
                assert!(!halved(rate, kbps_for_index(i)), "{rate} index {i}");
            }
        }
        // 600 kbps over two channels of 96 kHz is the 3.125 bits a sample the
        // decision is made at, so everything up to it halves and nothing above.
        assert!(halved(96000, 600));
        assert!(!halved(96000, 601));
        for kbps in BITRATE_KBPS {
            assert_eq!(halved(96000, kbps), kbps <= 600, "{kbps} kbps");
        }
        assert_eq!(bits_per_sample(96000, 600), HALVE_AT_OR_BELOW);
        assert_eq!(bits_per_sample(48000, 500), 5.208333333333333);
    }

    #[test]
    fn the_side_goes_before_the_step_coarsens_and_only_when_it_has_to() {
        let l: Vec<i32> =
            (0..BLOCK_SIZE * 8).map(|i| (9000.0 * (i as f64 * 0.2).sin()) as i32).collect();
        let r: Vec<i32> =
            (0..BLOCK_SIZE * 8).map(|i| (7000.0 * (i as f64 * 0.31).sin()) as i32).collect();
        let decode = |f: &Frame| lhdcv3_format::decoder::decode(&f.bytes, 2).unwrap();
        let cfg = |kbps| Config { sample_rate: 48000, bits_per_sample: 16, bitrate_kbps: kbps };

        // Room to spare: the side is worth carrying.
        let mut wide = Encoder::new(cfg(900));
        let mut kept = 0;
        for b in 0..8 {
            let at = b * BLOCK_SIZE;
            let d = decode(&wide.encode_block(&l[at..], &r[at..]));
            kept += usize::from(d.left != d.right);
        }
        assert!(kept >= 6, "a wide budget dropped the side on {} of 8 blocks", 8 - kept);

        // No room: both channels rebuilt from one rather than made coarser.
        let mut tight = Encoder::new(cfg(64));
        let mut mono = 0;
        let mut step = 0;
        for b in 0..8 {
            let at = b * BLOCK_SIZE;
            let f = tight.encode_block(&l[at..], &r[at..]);
            let d = decode(&f);
            mono += usize::from(d.left == d.right);
            step = step.max(f.divisor[0]);
        }
        assert!(mono >= 4, "a tight budget kept the side on {} of 8 blocks", 8 - mono);
        assert!(step > 1, "a tight budget should still have had to divide");
    }

    #[test]
    fn a_tighter_budget_raises_the_divisor() {
        let l: Vec<i32> = (0..BLOCK_SIZE)
            .map(|i| (20000.0 * (i as f64 * 0.3).sin()) as i32)
            .collect();
        let r: Vec<i32> = (0..BLOCK_SIZE)
            .map(|i| (15000.0 * (i as f64 * 0.11).sin()) as i32)
            .collect();
        let mut wide = Encoder::new(Config { bitrate_kbps: 900, ..cfg() });
        let mut tight = Encoder::new(Config { bitrate_kbps: 64, ..cfg() });
        let (mut a, mut b) = (wide.encode_block(&l, &r), tight.encode_block(&l, &r));
        assert!(b.divisor[0] > a.divisor[0]);
        for _ in 0..40 {
            a = wide.encode_block(&l, &r);
            b = tight.encode_block(&l, &r);
        }
        assert!(b.divisor[0] > a.divisor[0], "divisor {:?} vs {:?}", b.divisor, a.divisor);
        assert!(b.bytes.len() < a.bytes.len());
    }
}

#[cfg(test)]
mod index_tests {
    use super::*;

    #[test]
    fn the_index_and_the_grid_are_inverses() {
        let grid: Vec<i32> = divisor_grid().map(|(d, _)| d).collect();
        for (i, &d) in grid.iter().enumerate() {
            assert_eq!(grid_index(d), i as i32, "index of {d}");
            assert_eq!(grid_divisor(i as i32), d, "divisor at {i}");
        }
        for pair in grid.windows(2) {
            assert_eq!(next_divisor(pair[0]), pair[1], "after {}", pair[0]);
            assert_eq!(prev_divisor(pair[1]), pair[0], "before {}", pair[1]);
        }
    }
}
