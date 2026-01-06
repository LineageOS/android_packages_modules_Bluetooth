// Copyright (C) 2025, The Android Open Source Project
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

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use thiserror::Error;
use zerocopy::{FromBytes, Immutable, IntoBytes};

pub struct WavFile {
    pub f: File,
    pub data_size: libc::c_uint,
}

#[derive(Copy, Clone, IntoBytes, FromBytes, Immutable)]
#[repr(C, packed)]
pub struct wav_chunk_fmt_t {
    pub type_0: u16,
    pub channel: u16,
    pub sample_rate: u32,
    pub bytes_per_sec: u32,
    pub block_align: u16,
    pub bits_per_sample: u16,
}

#[derive(Copy, Clone, FromBytes, IntoBytes, Immutable)]
#[repr(C, packed)]
pub struct wav_chunk_hdr_t {
    pub id: u32,
    pub size: u32,
}

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Error, Debug)]
pub enum Error {
    #[error("Not a WAV file (bad magic)")]
    BadMagic,
    #[error(transparent)]
    Io(#[from] std::io::Error),
}

impl WavFile {
    pub fn open(fmt: &mut wav_chunk_fmt_t, fname: &str) -> Result<Self> {
        let mut u32: libc::c_uint = 0;
        let mut chunk_hdr: wav_chunk_hdr_t = wav_chunk_hdr_t { id: 0, size: 0 };
        let mut file = std::fs::File::open(fname)?;
        file.read_exact(u32.as_mut_bytes())?;
        if u32 == 0x46464952 {
            file.read_exact(u32.as_mut_bytes())?;
            file.read_exact(u32.as_mut_bytes())?;
            file.read_exact(chunk_hdr.as_mut_bytes())?;
            if chunk_hdr.id == 0x20746d66 {
                file.read_exact(fmt.as_mut_bytes())?;
                if chunk_hdr.size as libc::c_ulong
                    > ::core::mem::size_of::<wav_chunk_fmt_t>() as libc::c_ulong
                {
                    file.seek(SeekFrom::Current(chunk_hdr.size as _))?;
                }
                loop {
                    file.read_exact(chunk_hdr.as_mut_bytes())?;
                    if chunk_hdr.id == 0x61746164 {
                        break;
                    }
                    file.seek(SeekFrom::Current(chunk_hdr.size as _))?;
                }
                return Ok(WavFile { data_size: chunk_hdr.size, f: file });
            }
        }
        Err(Error::BadMagic)
    }

    pub fn read(&mut self, dat: &mut [libc::c_uchar], len: libc::c_int) -> libc::c_int {
        self.f.read(&mut dat[..len as usize]).unwrap() as _
    }
}
