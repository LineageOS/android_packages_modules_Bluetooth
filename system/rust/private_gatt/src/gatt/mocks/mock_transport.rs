//! Mocked implementation of AttTransport for use in test

use crate::gatt::channel::AttTransport;
use crate::gatt::ids::TransportIndex;
use crate::packets::att;
use pdl_runtime::{EncodeError, Packet};
use tokio::sync::mpsc::{self, unbounded_channel, UnboundedReceiver};

/// Routes calls to AttTransport into a channel containing AttBuilders
pub struct MockAttTransport(mpsc::UnboundedSender<(TransportIndex, att::Att)>);

impl MockAttTransport {
    /// Constructor. Returns Self and the RX side of a channel containing
    /// AttBuilders sent on TransportIndices
    pub fn new() -> (Self, UnboundedReceiver<(TransportIndex, att::Att)>) {
        let (tx, rx) = unbounded_channel();
        (Self(tx), rx)
    }
}

impl AttTransport for MockAttTransport {
    fn send_packet(&self, tcb_idx: TransportIndex, packet: att::Att) -> Result<(), EncodeError> {
        packet.encode_to_vec()?; // trigger SerializeError if needed
        self.0.send((tcb_idx, packet)).unwrap();
        Ok(())
    }
}
