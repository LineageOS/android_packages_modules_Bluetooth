//! This module lets us classify AttOpcodes to determine how to handle them

use crate::packets::att::{Att, AttOpcode};
use std::ops::Deref;

/// The type of ATT operation performed by the packet
/// (see Core Spec 5.3 Vol 3F 3.3 Attribute PDU for details)
#[derive(Debug, Eq, PartialEq)]
pub enum OperationType {
    /// Client -> server, no response expected
    Command,
    /// Client -> server, response expected
    Request,
    /// Server -> client, response to a request
    Response,
    /// Server -> client, no response expected
    Notification,
    /// Server -> client, response expected
    Indication,
    /// Client -> server, response to an indication
    Confirmation,
}

/// Classify an opcode by its operation type. Note that this could be done using bitmasking, but is
/// done explicitly for clarity.
impl AttOpcode {
    pub fn operation_type(&self) -> OperationType {
        match self {
            AttOpcode::ErrorResponse => OperationType::Response,
            AttOpcode::ExchangeMtuResponse => OperationType::Response,
            AttOpcode::FindInformationResponse => OperationType::Response,
            AttOpcode::FindByTypeValueResponse => OperationType::Response,
            AttOpcode::ReadByTypeResponse => OperationType::Response,
            AttOpcode::ReadResponse => OperationType::Response,
            AttOpcode::ReadBlobResponse => OperationType::Response,
            AttOpcode::ReadMultipleResponse => OperationType::Response,
            AttOpcode::ReadByGroupTypeResponse => OperationType::Response,
            AttOpcode::WriteResponse => OperationType::Response,
            AttOpcode::PrepareWriteResponse => OperationType::Response,
            AttOpcode::ExecuteWriteResponse => OperationType::Response,
            AttOpcode::ReadMultipleVariableResponse => OperationType::Response,

            AttOpcode::ExchangeMtuRequest => OperationType::Request,
            AttOpcode::FindInformationRequest => OperationType::Request,
            AttOpcode::FindByTypeValueRequest => OperationType::Request,
            AttOpcode::ReadByTypeRequest => OperationType::Request,
            AttOpcode::ReadRequest => OperationType::Request,
            AttOpcode::ReadBlobRequest => OperationType::Request,
            AttOpcode::ReadMultipleRequest => OperationType::Request,
            AttOpcode::ReadByGroupTypeRequest => OperationType::Request,
            AttOpcode::WriteRequest => OperationType::Request,
            AttOpcode::PrepareWriteRequest => OperationType::Request,
            AttOpcode::ExecuteWriteRequest => OperationType::Request,
            AttOpcode::ReadMultipleVariableRequest => OperationType::Request,

            AttOpcode::WriteCommand => OperationType::Command,
            AttOpcode::SignedWriteCommand => OperationType::Command,

            AttOpcode::HandleValueNotification => OperationType::Notification,

            AttOpcode::HandleValueIndication => OperationType::Indication,

            AttOpcode::HandleValueConfirmation => OperationType::Confirmation,
        }
    }
}

/// A packet classified by its type.
#[allow(dead_code)]
pub enum AttType {
    Command(AttCommand),
    Request(AttRequest),
    Response(AttResponse),
    Notification(AttNotification),
    Indication(AttIndication),
    Confirmation(AttConfirmation),
}

macro_rules! att_type {
    ($att_type:ident, $op_type:expr) => {
        pub struct $att_type(Att);

        impl $att_type {
            #[allow(dead_code)]
            pub fn new<T: TryInto<Att>>(value: T) -> Result<Self, &'static str> {
                let att = value.try_into().map_err(|_| "Encode error")?;
                if att.opcode.operation_type() != $op_type {
                    Err("Operation type mismatch")
                } else {
                    Ok(Self(att))
                }
            }
        }

        impl Deref for $att_type {
            type Target = Att;
            fn deref(&self) -> &Self::Target {
                &self.0
            }
        }
    };
}

att_type!(AttCommand, OperationType::Command);
att_type!(AttRequest, OperationType::Request);
att_type!(AttResponse, OperationType::Response);
att_type!(AttNotification, OperationType::Notification);
att_type!(AttIndication, OperationType::Indication);
att_type!(AttConfirmation, OperationType::Confirmation);

impl From<Att> for AttType {
    fn from(value: Att) -> Self {
        match value.opcode.operation_type() {
            OperationType::Command => AttType::Command(AttCommand(value)),
            OperationType::Request => AttType::Request(AttRequest(value)),
            OperationType::Response => AttType::Response(AttResponse(value)),
            OperationType::Notification => AttType::Notification(AttNotification(value)),
            OperationType::Indication => AttType::Indication(AttIndication(value)),
            OperationType::Confirmation => AttType::Confirmation(AttConfirmation(value)),
        }
    }
}
