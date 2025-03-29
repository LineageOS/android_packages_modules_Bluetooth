use crate::dbus_arg::DBusArg;

use bt_topshim::syslog::Level;
use btstack::bluetooth_logging::IBluetoothLogging;
use dbus_macros::{dbus_method, generate_dbus_exporter};
use dbus_projection::prelude::*;

use num_traits::cast::{FromPrimitive, ToPrimitive};

use crate::dbus_arg::DBusArgError;
use dbus::nonblock::SyncConnection;
use std::sync::Arc;

#[allow(dead_code)]
struct IBluetoothLoggingDBus {}

impl_dbus_arg_enum!(Level);

#[generate_dbus_exporter(export_bluetooth_logging_dbus_intf, "org.chromium.bluetooth.Logging")]
impl IBluetoothLogging for IBluetoothLoggingDBus {
    #[dbus_method("IsDebugEnabled")]
    fn is_debug_enabled(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("SetDebugLogging")]
    fn set_debug_logging(&mut self, enabled: bool) {
        dbus_generated!()
    }

    #[dbus_method("SetLogLevel")]
    fn set_log_level(&mut self, level: Level) {
        dbus_generated!()
    }

    #[dbus_method("GetLogLevel")]
    fn get_log_level(&self) -> Level {
        dbus_generated!()
    }
}
