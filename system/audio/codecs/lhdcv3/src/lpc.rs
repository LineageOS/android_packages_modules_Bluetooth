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
//
// The analysis in this file is derived from libFLAC, whose notice follows.
//
// libFLAC - Free Lossless Audio Codec library
// Copyright (C) 2000-2009  Josh Coalson
// Copyright (C) 2011-2025  Xiph.Org Foundation
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//
// - Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//
// - Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
//
// - Neither the name of the Xiph.org Foundation nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
// EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
// PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
// NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

//! Linear prediction, the coding mode the frame marks with a nibble of fifteen.
//!
//! Written from libFLAC function for function: a Tukey window, an unnormalized
//! autocorrelation, Levinson-Durbin, an order search over the prediction error
//! and coefficient quantization with error feedback. The coefficients travel in
//! the frame, which is what separates this from the fixed predictors.
//!
//! The window is Tukey with a taper fraction of one half.

use lhdcv3_format::BLOCK_SIZE;
use std::borrow::Cow;
use std::sync::OnceLock;

pub const MAX_LPC_ORDER: usize = 8;

pub const COEFF_PRECISION: u32 = 15;

pub const MAX_COEFF_SHIFT: i32 = 15;

pub const TUKEY_P: f64 = 0.5;

pub fn window(len: usize) -> Cow<'static, [f64]> {
    static BLOCK: OnceLock<Vec<f64>> = OnceLock::new();
    if len == BLOCK_SIZE {
        return Cow::Borrowed(BLOCK.get_or_init(|| tukey(BLOCK_SIZE)));
    }
    Cow::Owned(tukey(len))
}

fn tukey(len: usize) -> Vec<f64> {
    let mut w = vec![1.0; len];
    let np = (TUKEY_P / 2.0 * len as f64) as usize;
    if np < 2 {
        return w;
    }
    let np = np - 1;
    for i in 0..=np {
        let a = 0.5 - 0.5 * (core::f64::consts::PI * i as f64 / np as f64).cos();
        w[i] = a;
        w[len - np - 1 + i] =
            0.5 - 0.5 * (core::f64::consts::PI * (i + np) as f64 / np as f64).cos();
    }
    w
}

pub fn autocorrelation(x: &[i32], w: &[f64], max_lag: usize) -> [f64; MAX_LPC_ORDER + 1] {
    let n = x.len().min(w.len());
    assert!(n <= BLOCK_SIZE && max_lag <= MAX_LPC_ORDER);
    let mut d = [0f64; BLOCK_SIZE];
    for (slot, (&v, &g)) in d.iter_mut().zip(x.iter().zip(w)) {
        *slot = g * v as f64;
    }
    // One pass over the samples accumulating every lag at once. A lag per pass
    // makes each sum wait on the one before it, and an addition is several
    // cycles deep; separate accumulators have nothing to wait for.
    let mut out = [0f64; MAX_LPC_ORDER + 1];
    let bulk = n.saturating_sub(max_lag);
    if max_lag == MAX_LPC_ORDER {
        // The lag count is what the format can name, so the inner loop is a
        // fixed width and unrolls into one accumulator per lag.
        for at in 0..bulk {
            let v = d[at];
            let win: &[f64; MAX_LPC_ORDER + 1] =
                d[at..at + MAX_LPC_ORDER + 1].try_into().unwrap();
            for (slot, &g) in out.iter_mut().zip(win.iter()) {
                *slot += v * g;
            }
        }
    } else {
        for at in 0..bulk {
            let v = d[at];
            for (c, slot) in out[..max_lag + 1].iter_mut().enumerate() {
                *slot += v * d[at + c];
            }
        }
    }
    for at in bulk..n {
        let v = d[at];
        for (c, slot) in out[..max_lag + 1].iter_mut().take(n - at).enumerate() {
            *slot += v * d[at + c];
        }
    }
    out
}

pub const ORDER_OVERHEAD: usize = 15;

pub struct Levinson {
    pub coeffs: Vec<Vec<f64>>,
    pub error: Vec<f64>,
}

pub fn levinson(autoc: &[f64], max_order: usize) -> Levinson {
    let mut lpc = vec![0f64; max_order];
    let mut coeffs = Vec::with_capacity(max_order);
    let mut error = Vec::with_capacity(max_order);
    let mut err = autoc[0];

    for i in 0..max_order {
        let mut r = -autoc[i + 1];
        for j in 0..i {
            r -= lpc[j] * autoc[i - j];
        }
        if err == 0.0 {
            coeffs.push(vec![0.0; i + 1]);
            error.push(0.0);
            continue;
        }
        r /= err;
        lpc[i] = r;
        for j in 0..i / 2 {
            let tmp = lpc[j];
            lpc[j] += r * lpc[i - 1 - j];
            lpc[i - 1 - j] += r * tmp;
        }
        if i % 2 != 0 {
            lpc[i / 2] += lpc[i / 2] * r;
        }
        err *= 1.0 - r * r;
        coeffs.push(lpc[..=i].iter().map(|&v| -v).collect());
        error.push(err);
    }
    Levinson { coeffs, error }
}

pub fn expected_bits(error: f64, n: usize) -> f64 {
    let scale = 0.5 / n as f64;
    if error > 0.0 {
        (0.5 * (scale * error).ln() / core::f64::consts::LN_2).max(0.0)
    } else if error < 0.0 {
        1e32
    } else {
        0.0
    }
}

pub fn best_order(error: &[f64], n: usize, bits_per_coeff: usize) -> usize {
    let mut best = (f64::MAX, 1usize);
    for (i, &e) in error.iter().enumerate() {
        let order = i + 1;
        let cost = expected_bits(e, n) * (n - order) as f64
            + (order * (bits_per_coeff + ORDER_OVERHEAD)) as f64;
        if cost < best.0 {
            best = (cost, order);
        }
    }
    best.1
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Quantized {
    pub coeffs: Vec<i32>,
    pub shift: u32,
}

pub fn quantize(lp: &[f64], precision: u32) -> Option<Quantized> {
    let c: Vec<f64> = lp.to_vec();
    let cmax = c.iter().fold(0f64, |m, v| m.max(v.abs()));
    if c.is_empty() || cmax <= 0.0 {
        return None;
    }

    let exp = ((cmax.to_bits() >> 52) & 0x7ff) as i32 - 1022;
    let mut shift = (precision as i32 - 1) - exp;
    if shift > MAX_COEFF_SHIFT {
        shift = MAX_COEFF_SHIFT;
    }
    if shift < -MAX_COEFF_SHIFT - 1 {
        return None;
    }

    let qmax = (1i64 << (precision - 1)) - 1;
    let qmin = -(1i64 << (precision - 1));
    let scale = if shift >= 0 {
        (1i64 << shift) as f64
    } else {
        1.0 / (1i64 << -shift) as f64
    };

    let mut out = Vec::with_capacity(c.len());
    let mut error = 0f64;
    for &v in &c {
        error += v * scale;
        let q = (error + 0.5f64.copysign(error)).trunc() as i64;
        let q = q.clamp(qmin, qmax);
        out.push(q as i32);
        error -= q as f64;
    }
    Some(Quantized { coeffs: out, shift: shift.max(0) as u32 })
}

pub fn residual(x: &[i32], history: &[i32], q: &Quantized) -> Vec<i32> {
    let order = q.coeffs.len();
    assert!(order <= MAX_LPC_ORDER);
    // Held oldest first, against samples held oldest first, so the prediction
    // walks both forward.
    let mut held = [0i32; MAX_LPC_ORDER];
    for (slot, &c) in held.iter_mut().zip(q.coeffs.iter().rev()) {
        *slot = c;
    }
    let taps = &held[..order];

    let head = order.min(x.len());
    let mut held_window = [0i32; 2 * MAX_LPC_ORDER];
    let take = history.len().min(order);
    held_window[order - take..order].copy_from_slice(&history[history.len() - take..]);
    held_window[order..order + head].copy_from_slice(&x[..head]);
    let window = &held_window[..order + head];

    let mut out = vec![0i32; x.len()];
    // The order is a runtime value but only ever one of eight, and a tap loop
    // the compiler cannot see the length of does not unroll.
    macro_rules! walk {
        ($n:literal) => {{
            let t: &[i32; $n] = taps.try_into().unwrap();
            let predict = |w: &[i32]| -> i64 {
                let mut acc = 0i64;
                for k in 0..$n {
                    acc += t[k] as i64 * w[k] as i64;
                }
                acc
            };
            for (i, slot) in out.iter_mut().enumerate().take(head) {
                *slot = (x[i] as i64 - (predict(&window[i..]) >> q.shift)) as i32;
            }
            for (i, slot) in out.iter_mut().enumerate().skip(head) {
                *slot = (x[i] as i64 - (predict(&x[i - order..]) >> q.shift)) as i32;
            }
        }};
    }
    match order {
        1 => walk!(1),
        2 => walk!(2),
        3 => walk!(3),
        4 => walk!(4),
        5 => walk!(5),
        6 => walk!(6),
        7 => walk!(7),
        8 => walk!(8),
        _ => {
            let predict = |w: &[i32]| -> i64 {
                taps.iter().zip(w).map(|(&c, &v)| c as i64 * v as i64).sum()
            };
            for (i, slot) in out.iter_mut().enumerate().take(head) {
                *slot = (x[i] as i64 - (predict(&window[i..]) >> q.shift)) as i32;
            }
            for (i, slot) in out.iter_mut().enumerate().skip(head) {
                *slot = (x[i] as i64 - (predict(&x[i - order..]) >> q.shift)) as i32;
            }
        }
    }
    out
}

pub fn analyse(x: &[i32], carried: usize, bits_per_coeff: usize) -> Option<Quantized> {
    let w = window(x.len().min(BLOCK_SIZE));
    let autoc = autocorrelation(x, &w, MAX_LPC_ORDER);
    if autoc[0] <= 0.0 {
        return None;
    }
    let l = levinson(&autoc, MAX_LPC_ORDER);
    let order = best_order(&l.error, carried, bits_per_coeff);
    quantize(&l.coeffs[order - 1], COEFF_PRECISION)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_order_follows_what_is_carried_not_what_is_windowed() {
        let mut x: Vec<i32> = (0..BLOCK_SIZE / 2)
            .map(|i| (8000.0 * (i as f64 * 0.11).sin() + 300.0 * (i as f64 * 0.7).sin()) as i32)
            .collect();
        let carried = x.len();
        x.resize(BLOCK_SIZE, 0);
        let short = analyse(&x, carried, 16).unwrap();
        let long = analyse(&x, BLOCK_SIZE, 16).unwrap();
        assert!(short.coeffs.len() <= long.coeffs.len());
    }

    #[test]
    fn the_order_does_not_follow_the_sample_depth() {
        let x: Vec<i32> = (0..BLOCK_SIZE)
            .map(|i| (9000.0 * (i as f64 * 0.11).sin() + 2500.0 * (i as f64 * 0.37).cos()) as i32)
            .collect();
        let wide: Vec<i32> = x.iter().map(|&v| v * 256).collect();
        let narrow = analyse(&x, x.len(), 16).unwrap();
        let deep = analyse(&wide, wide.len(), 24).unwrap();
        assert_eq!(narrow.coeffs.len(), deep.coeffs.len());
        assert!(deep.coeffs.len() < MAX_LPC_ORDER);
        assert!(analyse(&wide, wide.len(), 16).unwrap().coeffs.len() >= deep.coeffs.len());
    }

    #[test]
    fn the_window_is_tukey_with_a_half_taper() {
        let w = window(256);
        assert_eq!(w[0], 0.0);
        assert!((w[63] - 1.0).abs() < 1e-12);
        assert!(w[64..192].iter().all(|&v| (v - 1.0).abs() < 1e-12));
        assert!((w[255] - 0.0).abs() < 1e-12);
    }

    #[test]
    fn the_window_is_symmetric() {
        let w = window(256);
        for i in 0..256 {
            assert!((w[i] - w[255 - i]).abs() < 1e-12, "i={i}");
        }
    }

    #[test]
    fn the_autocorrelation_matches_the_encoder() {
        let sig = SIGNAL;
        let w = window(256);
        let got = autocorrelation(&sig, &w, 8);
        let want = [
            7497.43457, 7263.51172, 6903.69531, 6738.99023, 6620.37061, 6424.91357,
            6226.25732, 6033.104, 5776.34814,
        ];
        for (i, &v) in want.iter().enumerate() {
            assert!((got[i] - v).abs() <= v.abs() * 1e-6, "lag {i}: {} vs {v}", got[i]);
        }
    }

    #[test]
    fn the_first_order_coefficient_is_the_ratio_of_the_first_two_lags() {
        let w = window(256);
        let autoc = autocorrelation(&SIGNAL, &w, 8);
        let l = levinson(&autoc, MAX_LPC_ORDER);
        assert!((l.coeffs[0][0] - 0.968799591).abs() < 1e-6, "{}", l.coeffs[0][0]);
        let want = autoc[1] / autoc[0];
        assert!((l.coeffs[0][0] - want).abs() < want.abs() * 1e-6);
    }

    #[test]
    fn quantization_matches_the_encoder() {
        for (c, shift, q) in [
            (0.968799591f64, 14u32, 15873i32),
            (0.961993575, 14, 15761),
            (0.554489136, 14, 9085),
            (0.972005844, 14, 15925),
        ] {
            let got = quantize(&[c], COEFF_PRECISION).unwrap();
            assert_eq!(got.shift, shift, "coefficient {c}");
            assert_eq!(got.coeffs[0], q, "coefficient {c}");
        }
    }

    #[test]
    fn quantization_rounds_away_from_zero() {
        let q = quantize(&[-0.968799591], COEFF_PRECISION).unwrap();
        assert_eq!(q.coeffs[0], -15873);
    }

    #[test]
    fn the_residual_reaches_back_into_history() {
        let all: Vec<i32> = (0..BLOCK_SIZE * 2)
            .map(|i| (9000.0 * (i as f64 * 0.04).sin()) as i32)
            .collect();
        let (prev, x) = all.split_at(BLOCK_SIZE);
        let q = analyse(x, x.len(), 20).unwrap();
        let with = residual(x, prev, &q);
        let without = residual(x, &[], &q);
        let head: i64 = with[..q.coeffs.len()].iter().map(|v| v.abs() as i64).sum();
        let head_zero: i64 = without[..q.coeffs.len()].iter().map(|v| v.abs() as i64).sum();
        assert!(head < head_zero, "{head} against {head_zero}");
        assert_eq!(with[q.coeffs.len()..], without[q.coeffs.len()..]);
    }

    #[test]
    fn a_flat_signal_has_no_predictor() {
        assert!(analyse(&[0i32; BLOCK_SIZE], BLOCK_SIZE, 20).is_none());
    }

    #[test]
    fn the_residual_of_a_predictable_signal_is_small() {
        let x: Vec<i32> = (0..BLOCK_SIZE)
            .map(|i| (8000.0 * (i as f64 * 0.05).sin()) as i32)
            .collect();
        let q = analyse(&x, x.len(), 20).unwrap();
        let r = residual(&x, &[], &q);
        let peak = r[q.coeffs.len()..].iter().map(|v| v.abs()).max().unwrap();
        let level = x.iter().map(|v| v.abs()).max().unwrap();
        assert!(peak * 8 < level, "peak {peak} against {level}");
    }

    const SIGNAL: [i32; 256] = [
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, 0, 0, -1, -1,
        0, -1, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 1, 1, 2, 3, 3, 3, 4, 5, 5, 5, 5, 5, 4,
        4, 4, 5, 5, 5, 6, 5, 3, 3, 3, 4, 4, 3, 1, 0, 1, -1, -2, 0, 1, -2, -6, -3, 1, 1, 0,
        0, -1, 1, 1, -4, -7, -3, 2, 0, -5, -3, 1, -1, -1, 7, 11, 9, 9, 10, 10, 18, 23, 25,
        28, 25, 19, 20, 27, 32, 33, 35, 39, 40, 41, 39, 38, 37, 32, 26, 26, 37, 45, 43, 41,
        30, 22, 23, 17, 21, 37, 29, 3, -4, 0, -7, -4, 18, 22, -6, -27, -5, 28, 27, 25, 29,
    ];
}
