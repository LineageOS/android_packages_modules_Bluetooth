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

use crate::{HciHal, HciHalStatus, HciProxyCallbacks};

use core::ffi::{c_int, c_void, CStr};
use core::slice;
use std::fs::File;
use std::io::Write;
use std::os::fd::AsRawFd;
use std::sync::{Mutex, RwLock};

/// Callbacks from C to Rust
/// `handle` is allocated as an `Option<T: Callbacks>`; It must be valid from the
/// `CInterface.initialize()` call to the `CInterface.close()` call. This value
/// is returned as the first parameter to all other functions.
/// To prevent scheduling issues from the HAL Implementer, we enforce the validity
/// until the end of `Ffi<T>` instance; aka until the end of process life.
#[repr(C)]
#[allow(dead_code)]
pub struct CCallbacks {
    handle: *const c_void,
    initialization_complete: unsafe extern "C" fn(*mut c_void, CStatus),
    event_received: unsafe extern "C" fn(*mut c_void, *const u8, usize),
    acl_received: unsafe extern "C" fn(*mut c_void, *const u8, usize),
    sco_received: unsafe extern "C" fn(*mut c_void, *const u8, usize),
    iso_received: unsafe extern "C" fn(*mut c_void, *const u8, usize),
}

/// C Interface called from Rust
/// `handle` is a pointer initialized by the C code and passed to all other functions.
/// `callbacks` is only valid during the `initialize()` call.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CInterface {
    handle: *mut c_void,
    initialize: unsafe extern "C" fn(handle: *mut c_void, callbacks: *const CCallbacks),
    close: unsafe extern "C" fn(handle: *mut c_void),
    send_command: unsafe extern "C" fn(handle: *mut c_void, data: *const u8, len: usize),
    send_acl: unsafe extern "C" fn(handle: *mut c_void, data: *const u8, len: usize),
    send_sco: unsafe extern "C" fn(handle: *mut c_void, data: *const u8, len: usize),
    send_iso: unsafe extern "C" fn(handle: *mut c_void, data: *const u8, len: usize),
    client_died: Option<unsafe extern "C" fn(handle: *mut c_void)>,
    dump: Option<unsafe extern "C" fn(handle: *mut c_void, fd: c_int)>,
}

//SAFETY: CInterface is safe to send between threads because we require the C code
//        which initialises it to only use pointers to functions which are safe
//        to call from any thread.
unsafe impl Send for CInterface {}

#[repr(C)]
#[allow(dead_code)]
#[derive(Debug, PartialEq)]
pub(crate) enum CStatus {
    Success,
    AlreadyInitialized,
    UnableToOpenInterface,
    HardwareInitializationError,
    Unknown,
}

pub(crate) struct Ffi {
    intf: Mutex<CInterface>,
    wrapper: RwLock<Option<HciProxyCallbacks>>,
}

impl Ffi {
    pub(crate) fn new(intf: CInterface) -> Self {
        Self { intf: Mutex::new(intf), wrapper: RwLock::new(None) }
    }

    fn set_client(&self, client: HciProxyCallbacks) {
        *self.wrapper.write().unwrap() = Some(client);
    }

    fn remove_client(&self) {
        *self.wrapper.write().unwrap() = None;
    }
}

impl HciHal for Ffi {
    fn initialize(&self, client: HciProxyCallbacks) {
        log::debug!("Ffi::initialize");
        let intf = self.intf.lock().unwrap();
        self.set_client(client);

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer.
        unsafe {
            (intf.initialize)(intf.handle, &CCallbacks::new(&self.wrapper));
        }
    }

    fn send_command(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_command)(intf.handle, data.as_ptr(), data.len());
        }
    }

    fn send_acl(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_acl)(intf.handle, data.as_ptr(), data.len());
        }
    }

    fn send_iso(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_iso)(intf.handle, data.as_ptr(), data.len());
        }
    }

    fn send_sco(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_sco)(intf.handle, data.as_ptr(), data.len());
        }
    }

    fn close(&self) {
        log::debug!("Ffi::close");
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.close)(intf.handle);
        }
        self.remove_client();
    }

    fn client_died(&self) {
        log::debug!("Ffi::client_died");
        let intf = self.intf.lock().unwrap();

        if let Some(client_died) = intf.client_died {
            // SAFETY: The C Code has initialized the `CInterface` with a valid
            //         or null function pointer and an initialized `handle`.
            unsafe {
                client_died(intf.handle);
            }
        }
        self.remove_client();
    }

    /// # Safety
    ///
    /// The `writer` must be a concrete `File` type, as it will be casted to a
    /// `File` pointer to extract the raw file descriptor.
    unsafe fn dump(&self, writer: &mut dyn Write, _args: &[&CStr]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The `writer` is guaranteed to be supported by a concrete `File` type,
        //         by the safety restriction of the function signature.
        let fd = unsafe { &*(writer as *mut _ as *mut File) }.as_raw_fd();

        if let Some(dump) = intf.dump {
            // SAFETY: The C code has initialized the `CInterface` with a valid
            //         or null function pointer and an initialized `handle`.
            unsafe {
                dump(intf.handle, fd);
            }
        }
    }
}

impl CCallbacks {
    fn new(wrapper: &RwLock<Option<HciProxyCallbacks>>) -> Self {
        Self {
            handle: (wrapper as *const RwLock<Option<HciProxyCallbacks>>).cast(),
            initialization_complete: Self::initialization_complete,
            event_received: Self::event_received,
            acl_received: Self::acl_received,
            sco_received: Self::sco_received,
            iso_received: Self::iso_received,
        }
    }

    /// #Safety
    ///
    /// `handle` must be a valid pointer previously passed to the corresponding `initialize()`,
    /// and not yet destroyed (this is in fact an `RwLock<Option<T>>`).
    unsafe fn unwrap_client<F: FnOnce(&HciProxyCallbacks)>(handle: *mut c_void, f: F) {
        let wrapper: *const RwLock<Option<HciProxyCallbacks>> = handle.cast();

        // SAFETY: The `handle` points the `RwLock<Option<T>>` wrapper object; it was allocated
        //         at the creation of the `Ffi` object and remain alive until its destruction.
        if let Some(client) = unsafe { &*(*wrapper).read().unwrap() } {
            f(client);
        } else {
            log::error!("FFI Callback called in bad state");
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`
    unsafe extern "C" fn initialization_complete(handle: *mut c_void, status: CStatus) {
        log::debug!(
            "CCallbacks::initialization_complete: handle: {:?}, status: {:?}",
            handle,
            status
        );
        // SAFETY: The vendor HAL returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance.
        unsafe {
            Self::unwrap_client(handle, |client: &HciProxyCallbacks| {
                client.initialization_complete(status.into())
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn event_received(handle: *mut c_void, data: *const u8, len: usize) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &HciProxyCallbacks| {
                client.event_received(slice::from_raw_parts(data, len))
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn acl_received(handle: *mut c_void, data: *const u8, len: usize) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &HciProxyCallbacks| {
                client.acl_received(slice::from_raw_parts(data, len))
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn sco_received(handle: *mut c_void, data: *const u8, len: usize) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &HciProxyCallbacks| {
                client.sco_received(slice::from_raw_parts(data, len))
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn iso_received(handle: *mut c_void, data: *const u8, len: usize) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &HciProxyCallbacks| {
                client.iso_received(slice::from_raw_parts(data, len))
            });
        }
    }
}

impl From<CStatus> for HciHalStatus {
    fn from(value: CStatus) -> Self {
        match value {
            CStatus::Success => HciHalStatus::Success,
            CStatus::AlreadyInitialized => HciHalStatus::AlreadyInitialized,
            CStatus::UnableToOpenInterface => HciHalStatus::UnableToOpenInterface,
            CStatus::HardwareInitializationError => HciHalStatus::HardwareInitializationError,
            CStatus::Unknown => HciHalStatus::Unknown,
        }
    }
}
