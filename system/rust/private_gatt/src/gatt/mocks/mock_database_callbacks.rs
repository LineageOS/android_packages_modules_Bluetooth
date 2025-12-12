//! Mocked implementation of GattDatabaseCallbacks for use in test

use std::ops::RangeInclusive;

use crate::core::shared_box::SharedBox;
use crate::gatt::ids::{AttHandle, TransportIndex};
use crate::gatt::server::att_client::{AttClient, WeakAttClient};
use crate::gatt::server::gatt_database::GattDatabaseCallbacks;
use tokio::sync::mpsc::{self, unbounded_channel, UnboundedReceiver};

/// Routes calls to GattDatabaseCallbacks into a channel of MockCallbackEvents
pub struct MockCallbacks(mpsc::UnboundedSender<MockCallbackEvents>);

impl MockCallbacks {
    /// Constructor. Returns self and the RX side of the associated channel.
    pub fn new() -> (Self, UnboundedReceiver<MockCallbackEvents>) {
        let (tx, rx) = unbounded_channel();
        (Self(tx), rx)
    }
}

/// Events representing calls to GattCallbacks
#[allow(clippy::enum_variant_names)]
pub enum MockCallbackEvents {
    /// GattDatabaseCallbacks#on_le_connect invoked
    #[allow(dead_code)]
    OnLeConnect(WeakAttClient),
    /// GattDatabaseCallbacks#on_le_disconnect invoked
    OnLeDisconnect(TransportIndex),
    /// GattDatabaseCallbacks#on_service_change invoked
    OnServiceChange(RangeInclusive<AttHandle>),
}

impl GattDatabaseCallbacks for MockCallbacks {
    fn on_le_connect(&self, client: &SharedBox<AttClient>) {
        self.0.send(MockCallbackEvents::OnLeConnect(client.downgrade())).ok().unwrap();
    }

    fn on_le_disconnect(&self, tcb_idx: TransportIndex) {
        self.0.send(MockCallbackEvents::OnLeDisconnect(tcb_idx)).ok().unwrap();
    }

    fn on_service_change(&self, range: RangeInclusive<AttHandle>) {
        self.0.send(MockCallbackEvents::OnServiceChange(range)).ok().unwrap();
    }
}
