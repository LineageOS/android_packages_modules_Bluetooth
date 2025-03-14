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

use crate::codec::{self, CodecConfig, Encode, PcmFrame};
use crate::ffi::{CAudioConfig, CIsoStream};
use std::cmp::min;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, sleep, JoinHandle};
use std::time::{Duration, Instant};

pub struct Streamer<Cb: Callbacks> {
    state: Mutex<State>,
    iso: Arc<Mutex<IsoState>>,
    audio: AudioConfig,
    callbacks: Arc<Cb>,
}

pub trait Callbacks: Send + Sync + 'static {
    fn start(&self);
    fn stop(&self);
    fn send(&self, handle: u16, sequence_number: u16, data: &[u8]);
}

#[derive(Clone, Copy)]
pub(crate) struct AudioConfig {
    pub bitdepth: usize,
    pub sample_rate: u32,
    pub frame_duration_us: u32,
    pub codec: CodecConfig,
}

#[derive(Clone, Copy, Default)]
struct IsoState {
    streams: [IsoStream; 2],
    count: usize,
}

#[derive(Clone, Copy, Default)]
struct IsoStream {
    handle: u16,
    channels: usize,
    active: bool,
}

enum State {
    Idle,
    Running { fifo: Arc<Fifo>, _worker: Worker },
}

impl<Cb: Callbacks> Streamer<Cb> {
    pub fn new(iso: &[CIsoStream], audio: &CAudioConfig, callbacks: Cb) -> Result<Self, String> {
        let iso = IsoState::from(iso)?;
        let channels_by_stream = match iso.streams().count() {
            1 => iso.streams[0].channels,
            _ => 1,
        };

        let audio = AudioConfig::from(audio);
        if !matches!(audio.bitdepth, 16 | 24 | 32) {
            return Err(format!("Invalid bitdepth: {}", audio.bitdepth));
        }

        codec::validate_encoder(&audio, channels_by_stream)?;

        Ok(Self {
            state: Mutex::new(State::Idle),
            iso: Arc::new(Mutex::new(iso)),
            callbacks: Arc::new(callbacks),
            audio,
        })
    }

    pub fn enable(&self, handle: u16, max_sdu_size: usize, sdu_interval_us: u32) {
        {
            let mut iso = self.iso.lock().unwrap();
            iso.enable(handle);
        }

        let mut state = self.state.lock().unwrap();
        if matches!(*state, State::Idle) {
            if self.audio.frame_duration_us != sdu_interval_us {
                log::error!(
                    "Unframed SDU is not supported, \n\
                    SDU interval MUST match the audio frame duration ({} != {})",
                    sdu_interval_us,
                    self.audio.frame_duration_us
                );
                return;
            }

            let sample_rate = self.audio.sample_rate;
            let frame_duration_us = self.audio.frame_duration_us;
            let frame_len = ((sample_rate as u64 * frame_duration_us as u64) / 1_000_000) as usize;
            let fifo = Arc::new(Fifo::new(self.audio.bitdepth, frame_len));

            let cb_clone = self.callbacks.clone();
            *state = State::Running {
                fifo: fifo.clone(),
                _worker: Worker::new(
                    self.iso.clone(),
                    fifo,
                    max_sdu_size,
                    self.audio,
                    move |hdl, sn, data| cb_clone.send(hdl, sn, data),
                ),
            };
            self.callbacks.start();
        }
    }

    pub fn disable(&self, handle: u16) {
        let active = {
            let mut iso = self.iso.lock().unwrap();
            iso.disable(handle);
            iso.active()
        };

        if !active {
            let mut state = self.state.lock().unwrap();
            self.callbacks.stop();
            *state = State::Idle;
        }
    }

    pub fn write(&self, chunk: &[u8]) -> Result<usize, String> {
        match *self.state.lock().unwrap() {
            State::Running { ref fifo, .. } => Ok(fifo.write(chunk)),
            _ => Err("ISO stream(s) is not running".to_string()),
        }
    }
}

impl AudioConfig {
    fn from(config: &CAudioConfig) -> Self {
        Self {
            bitdepth: config.bitdepth as usize,
            sample_rate: config.sample_rate as u32,
            frame_duration_us: config.frame_duration_us as u32,
            codec: CodecConfig::from(&config.codec_type, &config.codec_config),
        }
    }
}

impl IsoState {
    fn from(src: &[CIsoStream]) -> Result<Self, String> {
        if !matches!(src.len(), 1..=2) {
            return Err(format!("Invalid stream count: {}", src.len()));
        }

        let mut streams = [Default::default(); 2];
        let mut channels_mask = 0;

        for s in src.iter() {
            let idx = s.channel_allocation.trailing_zeros() as usize;
            if idx >= 2 || channels_mask & s.channel_allocation != 0 {
                return Err("Invalid channel allocation definition".to_string());
            }
            channels_mask |= s.channel_allocation;

            streams[idx] = IsoStream {
                handle: s.handle,
                channels: s.channel_allocation.count_ones() as usize,
                active: false,
            }
        }

        if channels_mask != 0b11 {
            return Err("Missing allocation for Left or Right channel".to_string());
        }

        Ok(Self { streams, count: src.len() })
    }

    fn streams(&self) -> impl Iterator<Item = &'_ IsoStream> {
        self.streams.iter().take(self.count)
    }

    fn active(&self) -> bool {
        self.streams.iter().any(|s| s.active)
    }

    fn enable(&mut self, handle: u16) {
        if let Some(s) = self.streams.iter_mut().find(|s| s.handle == handle) {
            s.active = true;
        };
    }

    fn disable(&mut self, handle: u16) {
        if let Some(s) = self.streams.iter_mut().find(|s| s.handle == handle) {
            s.active = false;
        };
    }
}

struct Worker {
    thread: Option<JoinHandle<()>>,
    halt: Arc<AtomicBool>,
}

struct WorkerThread<F> {
    fifo: Arc<Fifo>,
    underrun: usize,
    audio: AudioConfig,
    frame_len: usize,
    max_sdu_size: usize,
    streams: [Option<WorkerStream>; 2],
    send: F,
}

struct WorkerStream {
    sn0: u64,
    encoder: Box<dyn Encode>,
}

impl Worker {
    fn new<F>(
        iso: Arc<Mutex<IsoState>>,
        fifo: Arc<Fifo>,
        max_sdu_size: usize,
        audio: AudioConfig,
        send: F,
    ) -> Self
    where
        F: Fn(u16, u16, &[u8]) + Send + 'static,
    {
        let halt = Arc::new(AtomicBool::new(false));

        let halt_clone = halt.clone();
        let thread = thread::spawn(move || {
            let mut worker = WorkerThread::new(fifo, audio, max_sdu_size, send);
            let mut clocker = Clocker::new(audio.frame_duration_us);

            loop {
                let now = Instant::now();
                let (deadline, sequence_number) = clocker.deadline(now);
                sleep(deadline - now);

                if halt_clone.load(Ordering::Relaxed) {
                    break;
                }

                let iso_snapshot = { *iso.lock().unwrap() };
                worker.schedule(iso_snapshot, sequence_number);
            }
        });

        Self { thread: Some(thread), halt }
    }
}

impl<F> WorkerThread<F>
where
    F: Fn(u16, u16, &[u8]),
{
    fn new(fifo: Arc<Fifo>, audio: AudioConfig, max_sdu_size: usize, send: F) -> Self {
        let sample_rate = audio.sample_rate;
        let frame_duration_us = audio.frame_duration_us;
        let frame_len = ((sample_rate as u64 * frame_duration_us as u64) / 1_000_000) as usize;
        Self { fifo, underrun: 0, audio, frame_len, max_sdu_size, streams: [None, None], send }
    }

    fn schedule(&mut self, iso: IsoState, sequence_number: u64) {
        let Some(frame) = self.fifo.get(self.frame_len) else {
            if self.underrun == 0 {
                log::warn!("PCM underrun starts");
            }
            self.underrun += 1;
            return;
        };
        if self.underrun > 0 {
            log::warn!("PCM underrun ends: {} SDU starved", self.underrun);
            self.underrun = 0;
        }

        for (i, iso) in iso.streams.iter().enumerate() {
            if !iso.active {
                self.streams[i] = None;
                continue;
            }
            let stream = self.streams[i].get_or_insert_with(|| WorkerStream {
                sn0: sequence_number,
                encoder: codec::new_encoder(&self.audio, iso.channels, self.max_sdu_size),
            });

            let pcm = match iso.channels {
                0 => continue,
                1 => PcmFrame::from_fifo(&frame).channel(i),
                2.. => PcmFrame::from_fifo(&frame),
            };

            (self.send)(
                iso.handle,
                (sequence_number - stream.sn0) as u16,
                &stream.encoder.encode(&pcm),
            );
        }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.halt.store(true, Ordering::Relaxed);
        let thread = self.thread.take().unwrap();
        thread.join().expect("End of thread loop");
    }
}

struct Clocker {
    t0: Instant,
    sequence_number: u64,
    interval_us: u64,
}

impl Clocker {
    fn new(interval_us: u32) -> Self {
        Self { t0: Instant::now(), sequence_number: 0, interval_us: interval_us as u64 }
    }

    fn deadline(&mut self, now: Instant) -> (Instant, u64) {
        self.sequence_number += 1;

        let mut deadline = self.t0 + Duration::from_micros(self.sequence_number * self.interval_us);
        if deadline < now {
            let gap = ((now - deadline).as_micros() as u64).div_ceil(self.interval_us);
            log::error!("Real-time loss: {} packet(s) skipped", gap);

            self.sequence_number += gap;
            deadline += Duration::from_micros(gap * self.interval_us);
        }

        (deadline, self.sequence_number)
    }
}

struct Fifo {
    channels: usize,
    bitdepth: usize,
    queue: Mutex<VecDeque<u8>>,
    cvar: Condvar,
}

pub struct FifoFrame<'a> {
    pub channels: usize,
    pub bitdepth: usize,
    queue: MutexGuard<'a, VecDeque<u8>>,
    cvar: &'a Condvar,
    length: usize,
}

impl Fifo {
    fn new(bitdepth: usize, length: usize) -> Self {
        let channels = 2;
        let capacity = channels * length * (bitdepth / 8);
        Self {
            channels: 2,
            bitdepth,
            queue: Mutex::new(VecDeque::with_capacity(capacity)),
            cvar: Condvar::new(),
        }
    }

    fn get(&self, length: usize) -> Option<FifoFrame> {
        let mut queue = self.queue.lock().unwrap();

        let size = self.channels * length * (self.bitdepth / 8);
        if queue.len() < size {
            None
        } else {
            queue.make_contiguous();
            Some(FifoFrame {
                channels: self.channels,
                bitdepth: self.bitdepth,
                length,
                queue,
                cvar: &self.cvar,
            })
        }
    }

    pub fn write(&self, mut chunk: &[u8]) -> usize {
        let write_len = chunk.len();
        let mut queue = self.queue.lock().unwrap();
        let cvar = &self.cvar;

        while !chunk.is_empty() {
            queue =
                cvar.wait_while(queue, |q| q.capacity() > 0 && q.len() >= q.capacity()).unwrap();

            if queue.capacity() == 0 {
                break;
            }

            let len = min(chunk.len(), queue.capacity() - queue.len());
            queue.extend(&chunk[..len]);
            chunk = &chunk[len..];
        }

        write_len - chunk.len()
    }
}

impl Drop for Fifo {
    fn drop(&mut self) {
        let mut queue = self.queue.lock().unwrap();
        queue.clear();
        queue.shrink_to_fit();
        self.cvar.notify_one();
    }
}

impl<'a> FifoFrame<'a> {
    pub fn data(&'a self) -> &'a [u8] {
        let size = self.channels * self.length * (self.bitdepth / 8);
        &self.queue.as_slices().0[..size]
    }
}

impl Drop for FifoFrame<'_> {
    fn drop(&mut self) {
        let size = self.data().len();
        self.queue.drain(..size);
        self.cvar.notify_one();
    }
}
