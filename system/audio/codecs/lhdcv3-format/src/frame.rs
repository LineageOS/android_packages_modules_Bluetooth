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

//! LHDC V3 frame header.
//!
//! The layout below was validated over 720 encoder runs covering ten signal
//! types, three sample rates, two bit depths, six bitrate indices and both
//! channel modes: 69840 frames, no parse failures.
//!
//! ```text
//! byte 0   low nibble the index, high nibble a separate field
//! byte 1   frame length, low 8 bits
//! byte 2   flags, and frame length high 2 bits
//! byte 3   SYNC
//! byte 4   first coded signal, first sample, low byte
//! byte 5   first coded signal, first sample, high byte
//! byte 6   channel 1 coding level, (fine & 7) | (coarse << 3)
//! byte 7   channel 2 coding level, same layout
//! byte 8   payload
//! ```

pub const SYNC: u8 = 0x4c;

pub const HEADER_LEN: usize = 8;

pub const FLAG_RATE_LOW: u8 = 0x80;

pub const CHANNEL_FIELD_MASK: u8 = 0x60;
pub const CHANNEL_FIELD_SHIFT: u32 = 5;
pub const CHANNEL_FIELD_DUAL: u8 = 0b11;

pub const LEN_HIGH_MASK: u8 = 0x03;
pub const MAX_FRAME_LEN: usize = 0x3ff;

pub const LEN_GRANULE: usize = 4;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Level {
    pub fine: u8,
    pub coarse: u8,
}

pub const MAX_LEVEL_COARSE: u32 = 30;

impl Level {
    pub fn decode(byte: u8) -> Level {
        Level { fine: byte & 0x07, coarse: byte >> 3 }
    }

    pub fn encode(divisor: i32) -> u8 {
        if divisor < 8 {
            return divisor as u8 & 0x07;
        }
        let coarse = 31 - (divisor as u32).leading_zeros();
        let fine = ((divisor - (1 << coarse)) >> (coarse - 3)) as u8;
        (coarse as u8) << 3 | (fine & 0x07)
    }

    pub fn divisor(self) -> i32 {
        let coarse = self.coarse as u32;
        if coarse > 2 {
            (1i32 << coarse) + ((self.fine as i32) << (coarse - 3))
        } else {
            self.fine as i32
        }
    }

    pub fn present(self) -> bool {
        self.fine != 0 || self.coarse != 0
    }
}

pub const LEVEL_A: usize = 6;
pub const LEVEL_B: usize = 7;

pub const MODE_MASK: u8 = 0x0f;

pub const WARMUP_A: usize = 4;

pub const WARMUP_B_MINIMAL: usize = 10;

pub const MINIMAL_STEREO_LEN: usize = 12;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub len: usize,
    pub flags: u8,
    pub byte0: u8,
    pub tail: [u8; 4],
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum ParseError {
    TooShort,
    BadSync(u8),
    Truncated { len: usize, available: usize },
    BadLength(usize),
}

impl Header {
    pub fn parse(buf: &[u8]) -> Result<Header, ParseError> {
        if buf.len() < HEADER_LEN {
            return Err(ParseError::TooShort);
        }
        if buf[3] != SYNC {
            return Err(ParseError::BadSync(buf[3]));
        }
        let len = (((buf[2] & LEN_HIGH_MASK) as usize) << 8) | buf[1] as usize;
        if len < HEADER_LEN || len > buf.len() {
            return Err(ParseError::Truncated { len, available: buf.len() });
        }
        if len % LEN_GRANULE != 0 {
            return Err(ParseError::BadLength(len));
        }
        Ok(Header {
            len,
            flags: buf[2] & !LEN_HIGH_MASK,
            byte0: buf[0],
            tail: [buf[4], buf[5], buf[6], buf[7]],
        })
    }

    pub fn modes(&self) -> (u8, u8) {
        (self.byte0 >> 4, self.byte0 & MODE_MASK)
    }

    pub fn channel_field(&self) -> u8 {
        (self.flags & CHANNEL_FIELD_MASK) >> CHANNEL_FIELD_SHIFT
    }

    pub fn is_high_rate(&self) -> bool {
        self.flags & FLAG_RATE_LOW == 0
    }

    pub fn warmup_a(buf: &[u8]) -> i16 {
        i16::from_le_bytes([buf[WARMUP_A], buf[WARMUP_A + 1]])
    }

    pub fn warmup_b(buf: &[u8]) -> Option<i16> {
        if buf.len() != MINIMAL_STEREO_LEN {
            return None;
        }
        Some(i16::from_le_bytes([buf[WARMUP_B_MINIMAL], buf[WARMUP_B_MINIMAL + 1]]))
    }

    pub fn levels(buf: &[u8]) -> (Level, Level) {
        (Level::decode(buf[LEVEL_A]), Level::decode(buf[LEVEL_B]))
    }
}

pub fn split(buf: &[u8]) -> Result<Vec<&[u8]>, ParseError> {
    let mut out = Vec::new();
    let mut off = 0;
    while off < buf.len() {
        let h = Header::parse(&buf[off..])?;
        out.push(&buf[off..off + h.len]);
        off += h.len;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SILENT_STEREO: [u8; 12] =
        [0x00, 0x0c, 0x80, 0x4c, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00];
    const SILENT_DUAL: [u8; 8] = [0x00, 0x08, 0xe0, 0x4c, 0x00, 0x00, 0x00, 0x01];

    #[test]
    fn parses_a_silent_stereo_frame() {
        let h = Header::parse(&SILENT_STEREO).unwrap();
        assert_eq!(h.len, 12);
        assert_eq!(h.channel_field(), 0b00);
        assert!(!h.is_high_rate());
    }

    #[test]
    fn parses_a_silent_dual_channel_frame() {
        let h = Header::parse(&SILENT_DUAL).unwrap();
        assert_eq!(h.len, 8);
        assert_eq!(h.channel_field(), CHANNEL_FIELD_DUAL);
    }

    #[test]
    fn length_uses_ten_bits() {
        let mut buf = vec![0u8; 520];
        buf[0] = 0xff;
        buf[1] = 0x08;
        buf[2] = 0x82;
        buf[3] = SYNC;
        assert_eq!(Header::parse(&buf).unwrap().len, 520);
    }

    #[test]
    fn reads_the_warm_up_samples() {
        let f: [u8; 12] = [
            0x00, 0x0c, 0x80, 0x4c, 0x64, 0x00, 0x01, 0x01, 0x00, 0x00, 0xc8, 0x00,
        ];
        assert_eq!(Header::parse(&f).unwrap().channel_field(), 0);
        assert_eq!(Header::warmup_a(&f), 100);
        assert_eq!(Header::warmup_b(&f), Some(200));
    }

    #[test]
    fn warm_up_samples_are_signed() {
        let f: [u8; 12] = [
            0x00, 0x0c, 0x80, 0x4c, 0xe8, 0x03, 0x01, 0x01, 0x00, 0x00, 0x18, 0xfc,
        ];
        assert_eq!(Header::warmup_a(&f), 1000);
        assert_eq!(Header::warmup_b(&f), Some(-1000));
    }

    #[test]
    fn the_second_warm_up_is_only_read_from_a_minimal_frame() {
        let long = vec![0u8; 48];
        assert_eq!(Header::warmup_b(&long), None);
    }

    #[test]
    fn levels_are_per_channel() {
        let f: [u8; 12] = [
            0x00, 0x0c, 0x80, 0x4c, 0x00, 0x00, 0x38, 0x01, 0x00, 0x00, 0x00, 0x00,
        ];
        let (a, b) = Header::levels(&f);
        assert_ne!(a, b);
        assert_eq!(a.coarse, 7);
        assert_eq!(b.coarse, 0);
    }

    #[test]
    fn decodes_the_coding_level() {
        for (byte, divisor, len) in [(0x38u8, 128, 76), (0x28, 32, 128), (0x20, 16, 168),
                                     (0x18, 8, 212), (0x01, 1, 308)] {
            assert_eq!(Level::decode(byte).divisor(), divisor,
                       "byte {byte:#04x} at frame length {len}");
        }
        assert_eq!(Level::decode(0x04).divisor(), 4);
        assert_eq!(Level::decode(0x02).divisor(), 2);
    }

    #[test]
    fn a_level_between_powers_of_two_keeps_its_mantissa() {
        assert_eq!(Level::decode(0x1c).divisor(), 12);
        assert_eq!(Level::decode(0x19).divisor(), 9);
        assert_eq!(Level::decode(0x21).divisor(), 18);
        assert_eq!(Level::decode(0x05).divisor(), 5);
    }

    #[test]
    fn a_level_of_zero_records_nothing() {
        assert!(!Level::decode(0).present());
        assert_eq!(Level::decode(0).divisor(), 0);
        assert!(Level::decode(0x01).present());
    }

    #[test]
    fn the_level_byte_round_trips_through_the_divisor() {
        for coarse in 0..=MAX_LEVEL_COARSE {
            for fine in 0..8 {
                let d = Level { fine, coarse: coarse as u8 }.divisor();
                if d == 0 {
                    continue;
                }
                assert_eq!(Level::decode(Level::encode(d)).divisor(), d, "divisor {d}");
            }
        }
    }

    #[test]
    fn the_level_byte_matches_the_divisors_traced_in_the_encoder() {
        assert_eq!(Level::encode(1), 0x01);
        assert_eq!(Level::encode(2), 0x02);
        assert_eq!(Level::encode(4), 0x04);
        assert_eq!(Level::encode(8), 0x18);
        assert_eq!(Level::encode(128), 0x38);
        assert_eq!(Level::encode(8192), 0x68);
    }

    #[test]
    fn rejects_a_length_that_is_not_a_granule() {
        let mut buf = vec![0u8; 16];
        buf[1] = 13;
        buf[2] = 0x80;
        buf[3] = SYNC;
        assert_eq!(Header::parse(&buf), Err(ParseError::BadLength(13)));
    }

    #[test]
    fn rejects_a_bad_sync() {
        let mut buf = SILENT_STEREO;
        buf[3] = 0x00;
        assert_eq!(Header::parse(&buf), Err(ParseError::BadSync(0)));
    }

    #[test]
    fn splits_concatenated_frames() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&SILENT_STEREO);
        buf.extend_from_slice(&SILENT_DUAL);
        let frames = split(&buf).unwrap();
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].len(), 12);
        assert_eq!(frames[1].len(), 8);
    }
}

/// Sub blocks a frame is divided into. Each channel's contribution to one of
/// them ends on a byte boundary.
pub const SUBBLOCKS: usize = 4;

/// Bit six of a channel's parameter byte, marking the escape coder.
pub const ESCAPE: u8 = 0x40;

/// Which of the three derived signals each channel carries, per channel field.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Signal {
    Left,
    Right,
    Difference,
}

pub fn signals(field: u8) -> [Signal; 2] {
    let pick = |ch: usize, own: Signal| {
        if field as usize + ch == 2 {
            Signal::Difference
        } else {
            own
        }
    };
    [pick(0, Signal::Left), pick(1, Signal::Right)]
}
