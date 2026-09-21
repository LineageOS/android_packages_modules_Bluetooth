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

//! The A2DP media codec capability carried in AVDTP for LHDC V3.
//!
//! ```text
//! octet 0-3  vendor id, little endian
//! octet 4-5  codec id, little endian
//! octet 6    bits 0-3 sample rates, 4-5 bit depth, 6 JAS, 7 AR
//! octet 7    bits 0-3 version, 4-5 max bitrate, 6 low latency, 7 LLAC
//! octet 8    bits 0-3 channel split, 4 META, 5 MIN_BR, 6 LARC, 7 LHDC V4
//! ```

pub const VENDOR_ID: u32 = 0x0000_053a;
pub const CODEC_ID_V2: u16 = 0x4c32;
pub const CODEC_ID_V3: u16 = 0x4c33;
pub const CODEC_ID_V5: u16 = 0x4c35;

pub const CODEC_LEN_V3: usize = 11;

pub mod sample_rate {
    pub const MASK: u8 = 0x0f;
    pub const R44100: u8 = 0x08;
    pub const R48000: u8 = 0x04;
    pub const R88200: u8 = 0x02;
    pub const R96000: u8 = 0x01;
}

pub mod bit_depth {
    pub const MASK: u8 = 0x30;
    pub const D24: u8 = 0x10;
    pub const D16: u8 = 0x20;
}

pub mod max_bitrate {
    pub const MASK: u8 = 0x30;
    pub const R900K: u8 = 0x00;
    pub const R500K: u8 = 0x10;
    pub const R400K: u8 = 0x20;
}

pub const VERSION_MASK: u8 = 0x0f;
pub const VERSION_V3: u8 = 0x01;
pub const VERSION_V4: u8 = 0x02;

pub const FEATURE_JAS: u8 = 0x40;
pub const FEATURE_AR: u8 = 0x80;
pub const FEATURE_LLAC: u8 = 0x80;
pub const LOW_LATENCY: u8 = 0x40;

pub const CH_SPLIT_MASK: u8 = 0x0f;
pub const CH_SPLIT_NONE: u8 = 0x01;
pub const CH_SPLIT_TWS: u8 = 0x02;

pub const FEATURE_META: u8 = 0x10;
pub const FEATURE_MIN_BITRATE: u8 = 0x20;
pub const FEATURE_LARC: u8 = 0x40;
pub const FEATURE_V4: u8 = 0x80;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct CodecInfo {
    pub codec_id: u16,
    pub sample_rates: u8,
    pub bit_depths: u8,
    pub version: u8,
    pub max_bitrate: u8,
    pub low_latency: bool,
    pub llac: bool,
    pub jas: bool,
    pub ar: bool,
    pub channel_split: u8,
    pub meta: bool,
    pub min_bitrate: bool,
    pub larc: bool,
    pub v4: bool,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Error {
    TooShort,
    WrongVendor(u32),
    UnsupportedCodec(u16),
}

impl CodecInfo {
    pub fn parse(buf: &[u8]) -> Result<CodecInfo, Error> {
        if buf.len() < CODEC_LEN_V3 - 2 {
            return Err(Error::TooShort);
        }
        let vendor = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        if vendor != VENDOR_ID {
            return Err(Error::WrongVendor(vendor));
        }
        let codec_id = u16::from_le_bytes([buf[4], buf[5]]);
        if codec_id != CODEC_ID_V3 {
            return Err(Error::UnsupportedCodec(codec_id));
        }
        Ok(CodecInfo {
            codec_id,
            sample_rates: buf[6] & sample_rate::MASK,
            bit_depths: buf[6] & bit_depth::MASK,
            jas: buf[6] & FEATURE_JAS != 0,
            ar: buf[6] & FEATURE_AR != 0,
            version: buf[7] & VERSION_MASK,
            max_bitrate: buf[7] & max_bitrate::MASK,
            low_latency: buf[7] & LOW_LATENCY != 0,
            llac: buf[7] & FEATURE_LLAC != 0,
            channel_split: buf[8] & CH_SPLIT_MASK,
            meta: buf[8] & FEATURE_META != 0,
            min_bitrate: buf[8] & FEATURE_MIN_BITRATE != 0,
            larc: buf[8] & FEATURE_LARC != 0,
            v4: buf[8] & FEATURE_V4 != 0,
        })
    }

    pub fn supports_rate(&self, hz: u32) -> bool {
        let bit = match hz {
            44100 => sample_rate::R44100,
            48000 => sample_rate::R48000,
            88200 => sample_rate::R88200,
            96000 => sample_rate::R96000,
            _ => return false,
        };
        self.sample_rates & bit != 0
    }

    pub fn max_bitrate_kbps(&self) -> u32 {
        match self.max_bitrate {
            max_bitrate::R400K => 400,
            max_bitrate::R500K => 500,
            _ => 900,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const UTWS5: [u8; 9] = [0x3a, 0x05, 0x00, 0x00, 0x33, 0x4c, 0x3f, 0x11, 0x01];

    #[test]
    fn parses_a_captured_capability() {
        let c = CodecInfo::parse(&UTWS5).unwrap();
        assert_eq!(c.codec_id, CODEC_ID_V3);
        assert_eq!(c.version, VERSION_V3);
        for hz in [44100, 48000, 88200, 96000] {
            assert!(c.supports_rate(hz), "{hz}");
        }
        assert_eq!(c.bit_depths, bit_depth::D16 | bit_depth::D24);
        assert_eq!(c.max_bitrate_kbps(), 500);
        assert_eq!(c.channel_split, CH_SPLIT_NONE);
        assert!(!c.jas && !c.ar && !c.llac && !c.low_latency);
        assert!(!c.meta && !c.min_bitrate && !c.larc && !c.v4);
    }

    #[test]
    fn rejects_other_codecs() {
        let mut buf = UTWS5;
        buf[4] = 0x35; // LHDC V5
        assert_eq!(CodecInfo::parse(&buf), Err(Error::UnsupportedCodec(0x4c35)));
    }

    #[test]
    fn rejects_other_vendors() {
        let mut buf = UTWS5;
        buf[0] = 0x2d;
        buf[1] = 0x01; // Sony
        assert!(matches!(CodecInfo::parse(&buf), Err(Error::WrongVendor(_))));
    }
}
