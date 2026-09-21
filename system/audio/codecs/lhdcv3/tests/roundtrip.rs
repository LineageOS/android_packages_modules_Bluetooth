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


//! Encode then decode, checking the samples survive the coding.

use lhdcv3::encoder::{quantize_all, Config, Encoder};
use lhdcv3_format::decoder::{decode, Decoded};
use lhdcv3_format::BLOCK_SIZE;

fn roundtrip(l: &[i32], r: &[i32], kbps: u32) -> (Decoded, Vec<i32>, Vec<i32>) {
    let mut e = Encoder::new(Config {
        sample_rate: 48000,
        bits_per_sample: 16,
        bitrate_kbps: kbps,
    });
    let mut f = e.encode_block(l, r);
    for _ in 0..40 {
        f = e.encode_block(l, r);
    }
    let d = decode(&f.bytes, 2).unwrap();
    let scale = [d.divisor[0].max(1), d.divisor[1].max(1)];
    let n = d.valid;
    let rebuilt = |x: &[i32], step: i32| -> Vec<i32> {
        quantize_all(x, step).iter().take(n).map(|&v| v * step).collect()
    };
    let (want_l, want_r) = (rebuilt(l, scale[0]), rebuilt(r, scale[1]));
    (d, want_l, want_r)
}

#[test]
fn silence_round_trips() {
    let (d, wl, wr) = roundtrip(&[0; BLOCK_SIZE], &[0; BLOCK_SIZE], 900);
    assert_eq!(d.left[..d.valid], wl[..]);
    assert_eq!(d.right[..d.valid], wr[..]);
}

#[test]
fn a_constant_block_round_trips() {
    let (d, wl, wr) = roundtrip(&[100; BLOCK_SIZE], &[200; BLOCK_SIZE], 900);
    assert_eq!(d.left[..d.valid], wl[..]);
    assert_eq!(d.right[..d.valid], wr[..]);
}

#[test]
fn a_ramp_round_trips() {
    let l: Vec<i32> = (0..BLOCK_SIZE).map(|i| i as i32 * 7).collect();
    let r: Vec<i32> = (0..BLOCK_SIZE).map(|i| 1000 - i as i32 * 3).collect();
    let (d, wl, wr) = roundtrip(&l, &r, 900);
    assert_eq!(d.left[..d.valid], wl[..]);
    assert_eq!(d.right[..d.valid], wr[..]);
}

#[test]
fn a_tone_round_trips_losslessly_at_the_top_rate() {
    let l: Vec<i32> = (0..BLOCK_SIZE)
        .map(|i| (6000.0 * (i as f64 * 0.11).sin()) as i32)
        .collect();
    let r: Vec<i32> = (0..BLOCK_SIZE)
        .map(|i| (4000.0 * (i as f64 * 0.07).cos()) as i32)
        .collect();
    let (d, wl, wr) = roundtrip(&l, &r, 900);
    assert_eq!(d.divisor, [1, 1], "the top rate should not need to quantize");
    assert_eq!(d.left[..d.valid], wl[..]);
    assert_eq!(d.right[..d.valid], wr[..]);
}

#[test]
fn a_quantized_block_round_trips_to_the_quantized_input() {
    let l: Vec<i32> = (0..BLOCK_SIZE)
        .map(|i| (20000.0 * (i as f64 * 0.3).sin()) as i32)
        .collect();
    let r: Vec<i32> = (0..BLOCK_SIZE)
        .map(|i| (15000.0 * (i as f64 * 0.11).sin()) as i32)
        .collect();
    let (d, wl, wr) = roundtrip(&l, &r, 320);
    assert!(d.divisor[0] > 1);
    assert_eq!(d.left[..d.valid], wl[..]);
    assert_eq!(d.right[..d.valid], wr[..]);
}

#[test]
fn noise_round_trips() {
    let mut s = 12345u32;
    let mut next = || {
        s = s.wrapping_mul(1103515245).wrapping_add(12345);
        ((s >> 16) as i32 & 0x3fff) - 8192
    };
    let l: Vec<i32> = (0..BLOCK_SIZE).map(|_| next()).collect();
    let r: Vec<i32> = (0..BLOCK_SIZE).map(|_| next()).collect();
    let (d, wl, wr) = roundtrip(&l, &r, 900);
    assert_eq!(d.left[..d.valid], wl[..]);
    assert_eq!(d.right[..d.valid], wr[..]);
}
