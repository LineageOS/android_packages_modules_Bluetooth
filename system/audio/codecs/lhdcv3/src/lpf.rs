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

//! The filter a 96 kHz stream is halved through when the frame is too small to
//! carry it whole.
//!
//! A Kaiser windowed sinc, computed from the design below rather than stored.
//! Decimating by two folds everything above 48 kHz back over 24 kHz, so what
//! lands inside the 20 kHz passband comes from above 28 kHz and that is the
//! band the stopband depth is chosen for. The 24 to 28 kHz shoulder folds to
//! 20 to 24 kHz, outside the passband.
//!
//! The samples are scaled up to 24 bits going in and shifted back down coming
//! out, and the delay line runs on from block to block, so what a block
//! filters to depends on the stream before it.

use std::sync::OnceLock;

pub const TAPS: usize = 137;

pub const DESIGN_RATE: f64 = 96000.0;

pub const CUTOFF_HZ: f64 = 22000.0;

pub const KAISER_BETA: f64 = 10.06;

/// Delay the filter adds, in samples of the stream going in.
pub const GROUP_DELAY: usize = (TAPS - 1) / 2;

fn bessel_i0(x: f64) -> f64 {
    let (mut term, mut sum, mut k) = (1f64, 1f64, 1f64);
    while term > sum * 1e-18 {
        let r = x / (2.0 * k);
        term *= r * r;
        sum += term;
        k += 1.0;
    }
    sum
}

fn sinc(x: f64) -> f64 {
    if x == 0.0 {
        return 1.0;
    }
    let p = std::f64::consts::PI * x;
    p.sin() / p
}

pub fn coefficients() -> &'static [f32; TAPS] {
    static H: OnceLock<[f32; TAPS]> = OnceLock::new();
    H.get_or_init(|| {
        let middle = (TAPS - 1) as f64 / 2.0;
        let cut = CUTOFF_HZ / (DESIGN_RATE / 2.0);
        let norm = bessel_i0(KAISER_BETA);
        let mut h = [0f64; TAPS];
        for (i, slot) in h.iter_mut().enumerate() {
            let m = i as f64 - middle;
            let t = m / middle;
            let window = bessel_i0(KAISER_BETA * (1.0 - t * t).max(0.0).sqrt()) / norm;
            *slot = cut * sinc(cut * m) * window;
        }
        let gain: f64 = h.iter().sum();
        let mut out = [0f32; TAPS];
        for (slot, v) in out.iter_mut().zip(h) {
            *slot = (v / gain) as f32;
        }
        out
    })
}

/// The delay line is held twice over so the window is always contiguous.
#[derive(Clone)]
pub struct Filter {
    history: [f32; 2 * TAPS],
    at: usize,
}

impl Default for Filter {
    fn default() -> Filter {
        Filter { history: [0f32; 2 * TAPS], at: 0 }
    }
}

const INNER_BITS: u32 = 24;

const CEILING: i32 = (1 << (INNER_BITS - 1)) - 1;

impl Filter {
    #[inline]
    fn push(&mut self, x: i32, up: u32) {
        let v = x as f32 * (1i32 << up) as f32;
        self.history[self.at] = v;
        self.history[self.at + TAPS] = v;
        self.at = if self.at + 1 == TAPS { 0 } else { self.at + 1 };
    }

    #[inline]
    fn output_with(&self, h: &[f32; TAPS], up: u32) -> i32 {
        let w = &self.history[self.at..self.at + TAPS];
        let (front, back) = w.split_at(GROUP_DELAY);
        let back = &back[1..];
        // Four running sums rather than one: the pairs are independent, and a
        // single accumulator chains every multiply behind the last add.
        let mut part = [0f32; 4];
        let mut i = 0;
        while i + 4 <= GROUP_DELAY {
            for j in 0..4 {
                part[j] += (front[i + j] + back[GROUP_DELAY - 1 - i - j]) * h[i + j];
            }
            i += 4;
        }
        let mut acc = w[GROUP_DELAY] * h[GROUP_DELAY] + (part[0] + part[1]) + (part[2] + part[3]);
        while i < GROUP_DELAY {
            acc += (front[i] + back[GROUP_DELAY - 1 - i]) * h[i];
            i += 1;
        }
        (acc as i32).clamp(-CEILING - 1, CEILING) >> up
    }

    #[inline]
    fn output(&self, up: u32) -> i32 {
        self.output_with(coefficients(), up)
    }

    #[inline]
    pub fn step(&mut self, x: i32, bits: u32) -> i32 {
        let up = INNER_BITS - bits.min(INNER_BITS);
        self.push(x, up);
        self.output(up)
    }

    pub fn run(&mut self, x: &[i32], bits: u32) -> Vec<i32> {
        x.iter().map(|&v| self.step(v, bits)).collect()
    }
}

/// Filters both channels, keeping one output sample in `step`.
///
/// Every input passes through, since the state each sample leaves is what the
/// next one is filtered against, but only a sample that is kept is summed.
pub fn run_pair(filters: &mut [Filter; 2], x: [&[i32]; 2], bits: u32, step: usize) -> [Vec<i32>; 2] {
    let n = x[0].len().min(x[1].len());
    let up = INNER_BITS - bits.min(INNER_BITS);
    let kept = n.div_ceil(step);
    let (mut left, mut right) = (Vec::with_capacity(kept), Vec::with_capacity(kept));
    let h = coefficients();
    for i in 0..n {
        filters[0].push(x[0][i], up);
        filters[1].push(x[1][i], up);
        if i % step == 0 {
            left.push(filters[0].output_with(h, up));
            right.push(filters[1].output_with(h, up));
        }
    }
    [left, right]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response_db(hz: f64) -> f64 {
        let h = coefficients();
        let w = 2.0 * std::f64::consts::PI * hz / DESIGN_RATE;
        let (mut re, mut im) = (0f64, 0f64);
        for (n, &c) in h.iter().enumerate() {
            re += c as f64 * (w * n as f64).cos();
            im -= c as f64 * (w * n as f64).sin();
        }
        10.0 * (re * re + im * im).log10()
    }

    fn worst_over(from: f64, to: f64, steps: usize) -> f64 {
        (0..=steps)
            .map(|i| response_db(from + (to - from) * i as f64 / steps as f64))
            .fold(f64::NEG_INFINITY, f64::max)
    }

    #[test]
    fn the_window_is_symmetric_and_unity_at_dc() {
        let h = coefficients();
        for i in 0..TAPS {
            assert_eq!(h[i], h[TAPS - 1 - i], "tap {i}");
        }
        let sum: f64 = h.iter().map(|&v| v as f64).sum();
        assert!((sum - 1.0).abs() < 1e-6, "dc gain {sum}");
    }

    #[test]
    fn the_passband_is_flat_to_twenty_kilohertz() {
        let mut lo = f64::INFINITY;
        let mut hi = f64::NEG_INFINITY;
        for i in 0..=2000 {
            let d = response_db(20000.0 * i as f64 / 2000.0);
            lo = lo.min(d);
            hi = hi.max(d);
        }
        assert!(hi - lo < 0.02, "ripple {} dB", hi - lo);
        assert!(hi < 0.01 && lo > -0.02, "{lo} to {hi}");
    }

    #[test]
    fn what_folds_into_the_passband_is_rejected() {
        // Decimating by two folds f over 24 kHz, so above 28 kHz lands under
        // 20 kHz and has to be gone; 24 to 28 kHz lands outside the passband.
        assert!(worst_over(28000.0, 48000.0, 4000) < -100.0, "{}", worst_over(28000.0, 48000.0, 4000));
        assert!(worst_over(24000.0, 28000.0, 2000) < -60.0, "{}", worst_over(24000.0, 28000.0, 2000));
    }

    #[test]
    fn silence_stays_silent() {
        let mut f = Filter::default();
        assert!(f.run(&[0; 300], 16).iter().all(|&v| v == 0));
    }

    #[test]
    fn a_constant_comes_back_at_unity() {
        let mut f = Filter::default();
        let out = f.run(&[10_000; 600], 16);
        assert!((out[599] - 10_000).abs() <= 1, "settled to {}", out[599]);
    }

    #[test]
    fn a_tone_inside_the_passband_survives_and_one_above_it_does_not() {
        for (hz, keep) in [(1000.0, true), (10_000.0, true), (35_000.0, false)] {
            let x: Vec<i32> = (0..4096)
                .map(|i| (12_000.0 * (2.0 * std::f64::consts::PI * hz * i as f64 / DESIGN_RATE).sin()) as i32)
                .collect();
            let out = Filter::default().run(&x, 16);
            let peak = out[TAPS..].iter().map(|v| v.abs()).max().unwrap();
            if keep {
                assert!(peak > 11_000, "{hz} Hz fell to {peak}");
            } else {
                assert!(peak < 200, "{hz} Hz survived at {peak}");
            }
        }
    }

    #[test]
    fn the_delay_is_the_design_delay() {
        let mut x = vec![0i32; 600];
        x[100] = 20_000;
        let out = Filter::default().run(&x, 16);
        let peak = out.iter().enumerate().max_by_key(|(_, v)| v.abs()).unwrap().0;
        assert_eq!(peak, 100 + GROUP_DELAY);
    }

    #[test]
    fn the_state_runs_on_between_calls() {
        let x: Vec<i32> = (0..600).map(|i: i32| (i % 17) * 300).collect();
        let a = Filter::default().run(&x, 16);
        let mut split = Filter::default();
        let mut b = split.run(&x[..250], 16);
        b.extend(split.run(&x[250..], 16));
        assert_eq!(a, b);
    }

    #[test]
    fn keeping_one_in_two_matches_filtering_them_all() {
        let x: Vec<i32> = (0..800).map(|i: i32| (i % 23) * 400 - 4000).collect();
        let y: Vec<i32> = (0..800).map(|i: i32| (i % 11) * 700 - 3000).collect();
        let whole = [Filter::default().run(&x, 16), Filter::default().run(&y, 16)];
        let mut f = [Filter::default(), Filter::default()];
        let thinned = run_pair(&mut f, [&x, &y], 16, 2);
        for c in 0..2 {
            let want: Vec<i32> = whole[c].iter().step_by(2).copied().collect();
            assert_eq!(thinned[c], want, "channel {c}");
        }
    }
}
