//! Shared data-types and utility methods go here.

pub mod address;
mod ffi;
pub mod shared_box;
pub mod shared_mutex;
pub mod uuid;

use std::pin::Pin;

use cxx::UniquePtr;

use crate::gatt::ffi::{AttTransportImpl, GattCallbacksImpl};
use crate::RustModuleRunner;

use self::ffi::{future_ready, Future, GattServerCallbacks};

fn start(
    gatt_server_callbacks: UniquePtr<GattServerCallbacks>,
    on_started: Pin<&'static mut Future>,
) {
    RustModuleRunner::start(GattCallbacksImpl(gatt_server_callbacks), AttTransportImpl(), || {
        future_ready(on_started);
    });
}

fn stop() {
    RustModuleRunner::stop();
}

fn set_disabled_in_test() {
    RustModuleRunner::set_disabled_in_test();
}
