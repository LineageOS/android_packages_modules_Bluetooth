// Copyright 2024, The Android Open Source Project
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

pub(crate) trait Read {
    fn read(r: &mut Reader) -> Option<Self>
    where
        Self: Sized;
}

pub(crate) struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub(crate) fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    pub(crate) fn get(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.pos + n > self.data.len() {
            return None;
        }
        let old_pos = self.pos;
        self.pos += n;
        Some(&self.data[old_pos..self.pos])
    }

    pub(crate) fn read<T: Read>(&mut self) -> Option<T> {
        T::read(self)
    }

    pub(crate) fn read_u8(&mut self) -> Option<u8> {
        Some(self.read_u32::<1>()? as u8)
    }

    pub(crate) fn read_u16(&mut self) -> Option<u16> {
        Some(self.read_u32::<2>()? as u16)
    }

    pub(crate) fn read_u32<const N: usize>(&mut self) -> Option<u32> {
        let data_it = self.get(N)?.iter().enumerate();
        Some(data_it.fold(0u32, |v, (i, byte)| v | ((*byte as u32) << (i * 8))))
    }

    pub(crate) fn read_bytes<const N: usize>(&mut self) -> Option<[u8; N]> {
        Some(<[u8; N]>::try_from(self.get(N)?).unwrap())
    }
}

impl Read for Vec<u8> {
    fn read(r: &mut Reader) -> Option<Self> {
        let len = r.read_u8()? as usize;
        Some(Vec::from(r.get(len)?))
    }
}

impl Read for Vec<u16> {
    fn read(r: &mut Reader) -> Option<Self> {
        let len = r.read_u8()? as usize;
        let vec: Vec<_> = (0..len).map_while(|_| r.read_u16()).collect();
        Some(vec).take_if(|v| v.len() == len)
    }
}

impl<T: Read> Read for Vec<T> {
    fn read(r: &mut Reader) -> Option<Self> {
        let len = r.read_u8()? as usize;
        let vec: Vec<_> = (0..len).map_while(|_| r.read()).collect();
        Some(vec).take_if(|v| v.len() == len)
    }
}

macro_rules! unpack {
    ($v:expr, ($( $n:expr ),*)) => {
        {
            let mut _x = $v;
            ($({
                let y = _x & ((1 << $n) - 1);
                _x >>= $n;
                y
            }),*)
        }
    };
    ($v:expr, $n:expr) => { unpack!($v, ($n)) };
}

pub(crate) use unpack;
