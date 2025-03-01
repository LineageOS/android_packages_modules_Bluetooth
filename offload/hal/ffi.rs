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

use core::ffi::c_void;
use core::slice;
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
    client_died: unsafe extern "C" fn(handle: *mut c_void),
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

pub(crate) trait Callbacks: DataCallbacks {
    fn initialization_complete(&self, status: CStatus);
}

pub(crate) trait DataCallbacks: Send + Sync {
    fn event_received(&self, data: &[u8]);
    fn acl_received(&self, data: &[u8]);
    fn sco_received(&self, data: &[u8]);
    fn iso_received(&self, data: &[u8]);
}

pub(crate) struct Ffi<T: Callbacks> {
    intf: Mutex<CInterface>,
    wrapper: RwLock<Option<T>>,
}

impl<T: Callbacks> Ffi<T> {
    pub(crate) fn new(intf: CInterface) -> Self {
        Self { intf: Mutex::new(intf), wrapper: RwLock::new(None) }
    }

    pub(crate) fn initialize(&self, client: T) {
        let intf = self.intf.lock().unwrap();
        self.set_client(client);

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer.
        unsafe {
            (intf.initialize)(intf.handle, &CCallbacks::new(&self.wrapper));
        }
    }

    pub(crate) fn send_command(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_command)(intf.handle, data.as_ptr(), data.len());
        }
    }

    pub(crate) fn send_acl(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_acl)(intf.handle, data.as_ptr(), data.len());
        }
    }

    pub(crate) fn send_iso(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_iso)(intf.handle, data.as_ptr(), data.len());
        }
    }

    pub(crate) fn send_sco(&self, data: &[u8]) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.send_sco)(intf.handle, data.as_ptr(), data.len());
        }
    }

    pub(crate) fn close(&self) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.close)(intf.handle);
        }
        self.remove_client();
    }

    pub(crate) fn client_died(&self) {
        let intf = self.intf.lock().unwrap();

        // SAFETY: The C Code has initialized the `CInterface` with a valid
        //         function pointer and an initialized `handle`.
        unsafe {
            (intf.client_died)(intf.handle);
        }
        self.remove_client();
    }

    fn set_client(&self, client: T) {
        *self.wrapper.write().unwrap() = Some(client);
    }

    fn remove_client(&self) {
        *self.wrapper.write().unwrap() = None;
    }
}

impl CCallbacks {
    fn new<T: Callbacks>(wrapper: &RwLock<Option<T>>) -> Self {
        Self {
            handle: (wrapper as *const RwLock<Option<T>>).cast(),
            initialization_complete: Self::initialization_complete::<T>,
            event_received: Self::event_received::<T>,
            acl_received: Self::acl_received::<T>,
            sco_received: Self::sco_received::<T>,
            iso_received: Self::iso_received::<T>,
        }
    }

    /// #Safety
    ///
    /// `handle` must be a valid pointer previously passed to the corresponding `initialize()`,
    /// and not yet destroyed (this is in fact an `RwLock<Option<T>>`).
    unsafe fn unwrap_client<T: Callbacks, F: FnOnce(&T)>(handle: *mut c_void, f: F) {
        let wrapper: *const RwLock<Option<T>> = handle.cast();

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
    unsafe extern "C" fn initialization_complete<T: Callbacks>(
        handle: *mut c_void,
        status: CStatus,
    ) {
        // SAFETY: The vendor HAL returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance.
        unsafe {
            Self::unwrap_client(handle, |client: &T| client.initialization_complete(status));
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn event_received<T: Callbacks>(
        handle: *mut c_void,
        data: *const u8,
        len: usize,
    ) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &T| {
                client.event_received(slice::from_raw_parts(data, len))
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn acl_received<T: Callbacks>(
        handle: *mut c_void,
        data: *const u8,
        len: usize,
    ) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &T| {
                client.acl_received(slice::from_raw_parts(data, len))
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn sco_received<T: Callbacks>(
        handle: *mut c_void,
        data: *const u8,
        len: usize,
    ) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &T| {
                client.sco_received(slice::from_raw_parts(data, len))
            });
        }
    }

    /// #Safety
    ///
    /// The C Interface requires that `handle` is a copy of the value given in `CCallbacks.handle`.
    /// `data` must be a valid pointer to at least `len` bytes of memory, which remains valid and
    /// is not mutated for the duration of this call.
    unsafe extern "C" fn iso_received<T: Callbacks>(
        handle: *mut c_void,
        data: *const u8,
        len: usize,
    ) {
        // SAFETY: The C code returns `handle` pointing `wrapper` object which has
        //         the same lifetime as the base `Ffi` instance. `data` points to a buffer
        //         of `len` bytes valid until the function returns.
        unsafe {
            Self::unwrap_client(handle, |client: &T| {
                client.iso_received(slice::from_raw_parts(data, len))
            });
        }
    }
}
