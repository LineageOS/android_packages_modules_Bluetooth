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

//! Fixed polynomial prediction.
//!
//! Each block is predicted with the fixed polynomial predictors of order one
//! through five and the residual of each is kept, so a later stage can pick
//! whichever codes smallest.
//!
//! The rows are built by differencing the previous row rather than by applying
//! the k-th finite difference to the signal, and each row starts at zero, so
//! the two agree only from sample `k` on. Samples below that are sent raw.

pub const MAX_ORDER: usize = 12;

pub const COEFFS: [&[i64]; MAX_ORDER + 1] = [
    &[1],
    &[1, -1],
    &[1, -2, 1],
    &[1, -3, 3, -1],
    &[1, -4, 6, -4, 1],
    &[1, -5, 10, -10, 5, -1],
    &[1, -6, 15, -20, 15, -6, 1],
    &[1, -7, 21, -35, 35, -21, 7, -1],
    &[1, -8, 28, -56, 70, -56, 28, -8, 1],
    &[1, -9, 36, -84, 126, -126, 84, -36, 9, -1],
    &[1, -10, 45, -120, 210, -252, 210, -120, 45, -10, 1],
    &[1, -11, 55, -165, 330, -462, 462, -330, 165, -55, 11, -1],
    &[1, -12, 66, -220, 495, -792, 924, -792, 495, -220, 66, -12, 1],
];

pub fn residual_at(x: &[i32], i: usize, order: usize) -> i64 {
    debug_assert!(order <= MAX_ORDER);
    debug_assert!(i >= order);
    COEFFS[order]
        .iter()
        .enumerate()
        .map(|(j, c)| c * x[i - j] as i64)
        .sum()
}

pub fn difference(row: &[i32]) -> Vec<i32> {
    let mut out = vec![0i32; row.len()];
    for i in 1..row.len() {
        out[i] = row[i].wrapping_sub(row[i - 1]);
    }
    out
}

pub const MAX_ROWS: usize = 13;

/// The difference rows of a signal, built only as far as they are asked for.
/// A sample's magnitude folded onto the unsigned range, which is what the
/// order search weighs a row by.
#[inline]
pub fn folded(v: i32) -> u64 {
    (v ^ (v >> 31)) as u32 as u64
}

pub struct Rows<'a> {
    base: &'a [i32],
    rows: Vec<Vec<i32>>,
    /// Each row's folded sum past its first sample, taken as the row is
    /// built rather than in a second pass over it.
    sums: Vec<u64>,
}

impl<'a> Rows<'a> {
    pub fn new(base: &'a [i32]) -> Rows<'a> {
        Rows { base, rows: Vec::new(), sums: Vec::new() }
    }

    pub fn row(&mut self, k: usize) -> &[i32] {
        self.build(k);
        match k {
            0 => self.base,
            _ => &self.rows[k - 1],
        }
    }

    /// The folded sum of row `k` past its first sample.
    pub fn sum(&mut self, k: usize) -> u64 {
        if k == 0 {
            return self.base[1..].iter().copied().map(folded).sum();
        }
        self.build(k);
        self.sums[k - 1]
    }

    fn build(&mut self, k: usize) {
        while self.rows.len() < k {
            let prev: &[i32] = match self.rows.last() {
                Some(last) => last,
                None => self.base,
            };
            let mut out = vec![0i32; prev.len()];
            let mut sum = 0u64;
            for i in 1..prev.len() {
                let v = prev[i].wrapping_sub(prev[i - 1]);
                out[i] = v;
                sum += folded(v);
            }
            self.rows.push(out);
            self.sums.push(sum);
        }
    }
}

pub fn rows_upto(x: &[i32], count: usize) -> Vec<Vec<i32>> {
    let mut out: Vec<Vec<i32>> = Vec::with_capacity(count);
    for k in 0..count {
        let prev: &[i32] = if k == 0 { x } else { out[k - 1].as_slice() };
        out.push(difference(prev));
    }
    out
}

pub fn rows(x: &[i32]) -> Vec<Vec<i32>> {
    rows_upto(x, 5)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_order_the_mode_nibble_can_name_has_coefficients() {
        // The nibble is four bits: 0 is constant, 15 is linear prediction and
        // 2 through 14 are fixed orders 0 through 12, so the table has to
        // reach that far or a frame carrying one cannot be read back.
        assert_eq!(COEFFS.len(), MAX_ORDER + 1);
        assert_eq!(MAX_ORDER, 14 - 2);
        for (order, row) in COEFFS.iter().enumerate() {
            assert_eq!(row.len(), order + 1, "order {order}");
            assert_eq!(row[0], 1, "order {order}");
            assert_eq!(row.iter().sum::<i64>(), i64::from(order == 0), "order {order}");
        }
    }

    const INPUT: [i32; 16] = [
        0, 1151, 2298, 3438, 4567, 5680, 6774, 7846, 8892, 9909, 10892, 11840, 12748, 13614,
        14435, 15208,
    ];
    const ORDER1: [i64; 16] = [
        0, 1151, 1147, 1140, 1129, 1113, 1094, 1072, 1046, 1017, 983, 948, 908, 866, 821, 773,
    ];
    const ORDER2: [i64; 16] = [
        0, 1151, -4, -7, -11, -16, -19, -22, -26, -29, -34, -35, -40, -42, -45, -48,
    ];
    const ORDER3: [i64; 16] = [
        0, 1151, -1155, -3, -4, -5, -3, -3, -4, -3, -5, -1, -5, -2, -3, -3,
    ];
    const ORDER5: [i64; 16] = [
        0, 1151, -3457, 3458, -1153, 0, 3, -2, -1, 2, -3, 6, -8, 7, -4, 1,
    ];

    fn check(order: usize, want: &[i64; 16]) {
        for i in order..INPUT.len() {
            assert_eq!(residual_at(&INPUT, i, order), want[i], "order {order} sample {i}");
        }
    }

    #[test]
    fn matches_the_encoder_for_every_order() {
        check(1, &ORDER1);
        check(2, &ORDER2);
        check(3, &ORDER3);
        check(5, &ORDER5);
    }

    #[test]
    fn order_one_is_the_first_difference() {
        let r = rows(&INPUT);
        for i in 1..INPUT.len() {
            assert_eq!(r[0][i], INPUT[i] - INPUT[i - 1]);
        }
        assert_eq!(r[0][0], 0);
    }

    #[test]
    fn the_rows_match_the_ones_dumped_from_the_encoder() {
        let x: [i32; 8] = [-1857, -1811, -1761, -1709, -1655, -1601, -1548, -1497];
        let r = rows(&x);
        assert_eq!(r[0][..8], [0, 46, 50, 52, 54, 54, 53, 51]);
        assert_eq!(r[1][..8], [0, 46, 4, 2, 2, 0, -1, -2]);
        assert_eq!(r[2][..8], [0, 46, -42, -2, 0, -2, -1, -1]);
        assert_eq!(r[3][..8], [0, 46, -88, 40, 2, -2, 1, 0]);
    }

    #[test]
    fn a_row_agrees_with_the_finite_difference_from_its_own_order_on() {
        let r = rows(&INPUT);
        for order in 1..=5 {
            for i in order..INPUT.len() {
                assert_eq!(
                    r[order - 1][i] as i64,
                    residual_at(&INPUT, i, order),
                    "order {order} sample {i}"
                );
            }
        }
    }

    #[test]
    fn one_sample_recovers_the_whole_block() {
        let r = rows(&INPUT);
        for order in 1..=5 {
            let mut cur = r[order - 1].clone();
            for _ in 1..order {
                let mut acc = 0i32;
                cur = cur.iter().map(|v| { acc += v; acc }).collect();
            }
            let mut acc = INPUT[0];
            let back: Vec<i32> = std::iter::once(INPUT[0])
                .chain(cur[1..].iter().map(|v| { acc += v; acc }))
                .collect();
            assert_eq!(back, INPUT, "order {order}");
        }
    }

    #[test]
    fn a_constant_signal_predicts_to_zero() {
        let x = [1234i32; 16];
        for order in 1..=5 {
            for i in order..x.len() {
                assert_eq!(residual_at(&x, i, order), 0, "order {order}");
            }
        }
    }

    #[test]
    fn a_ramp_predicts_to_zero_above_order_one() {
        let x: Vec<i32> = (0..16).map(|i| 7 * i).collect();
        for order in 2..=5 {
            for i in order..x.len() {
                assert_eq!(residual_at(&x, i, order), 0, "order {order}");
            }
        }
    }
}
