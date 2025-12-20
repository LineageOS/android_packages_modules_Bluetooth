//! Mocked implementation of GattDatastore for use in test

use crate::gatt::callbacks::GattDatastore;
use crate::gatt::ffi::AttributeBackingType;
use crate::gatt::ids::{AttHandle, TransportIndex};
use crate::packets::att::AttErrorCode;
use async_trait::async_trait;
use log::info;
use tokio::sync::mpsc::{self, unbounded_channel, UnboundedReceiver};
use tokio::sync::oneshot;

/// Routes calls to GattDatastore into a channel of MockDatastoreEvents
pub struct MockDatastore(mpsc::UnboundedSender<MockDatastoreEvents>);

impl MockDatastore {
    /// Constructor. Returns self and the RX side of the associated channel.
    pub fn new() -> (Self, UnboundedReceiver<MockDatastoreEvents>) {
        let (tx, rx) = unbounded_channel();
        (Self(tx), rx)
    }
}

/// Events representing calls to GattDatastore
#[derive(Debug)]
pub enum MockDatastoreEvents {
    /// A characteristic was read on a given handle. The oneshot is used to
    /// return the value read.
    Read(
        TransportIndex,
        AttHandle,
        AttributeBackingType,
        oneshot::Sender<Result<Vec<u8>, AttErrorCode>>,
    ),
    /// A characteristic was written to on a given handle. The oneshot is used
    /// to return whether the write succeeded.
    Write(
        TransportIndex,
        AttHandle,
        AttributeBackingType,
        Vec<u8>,
        oneshot::Sender<Result<(), AttErrorCode>>,
    ),
}

#[async_trait(?Send)]
impl GattDatastore for MockDatastore {
    async fn read(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
    ) -> Result<Vec<u8>, AttErrorCode> {
        let (tx, rx) = oneshot::channel();
        self.0.send(MockDatastoreEvents::Read(tcb_idx, handle, attr_type, tx)).unwrap();
        let resp = rx.await.unwrap();
        info!("sending {resp:?} down from upper tester");
        resp
    }

    async fn write(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
        data: &[u8],
    ) -> Result<(), AttErrorCode> {
        let (tx, rx) = oneshot::channel();
        self.0
            .send(MockDatastoreEvents::Write(tcb_idx, handle, attr_type, data.to_vec(), tx))
            .unwrap();
        rx.await.unwrap()
    }
}
