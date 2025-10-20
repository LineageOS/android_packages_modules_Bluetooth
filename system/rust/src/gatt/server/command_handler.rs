use log::warn;

use crate::gatt::opcode_types::AttCommand;
use crate::gatt::server::att_client::WeakAttClient;
use crate::packets::att;

/// This struct handles all ATT commands.
pub struct AttCommandHandler {
    client: WeakAttClient,
}

impl AttCommandHandler {
    pub fn new(client: WeakAttClient) -> Self {
        Self { client }
    }

    pub fn process_packet(&self, packet: AttCommand) {
        match packet.opcode {
            att::AttOpcode::WriteCommand => {
                let Ok(packet) = att::AttWriteCommand::try_from(&*packet) else {
                    warn!("failed to parse WRITE_COMMAND packet");
                    return;
                };
                self.client.write_no_response_attribute(packet.handle.into(), &packet.value);
            }
            _ => {
                warn!("Dropping unsupported opcode {:?}", packet.opcode);
            }
        }
    }
}

#[cfg(test)]
mod test {
    use crate::core::uuid::Uuid;
    use crate::gatt::ids::{AttHandle, TransportIndex};
    use crate::gatt::opcode_types::AttCommand;
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::AttAttribute;
    use crate::gatt::server::command_handler::AttCommandHandler;
    use crate::gatt::server::gatt_database::AttPermissions;
    use crate::gatt::server::test::test_att_db::new_test_database;
    use crate::packets::att;
    use crate::utils::task::block_on_locally;

    const TCB_IDX: TransportIndex = TransportIndex(1);

    #[test]
    fn test_write_command() {
        // arrange
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITHOUT_RESPONSE,
            },
            vec![1, 2, 3],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let handler = AttCommandHandler::new(client.downgrade());
        let data = [1, 2];

        // act: send write command
        let att_view = AttCommand::new(att::AttWriteCommand {
            handle: AttHandle(3).into(),
            value: data.to_vec(),
        })
        .unwrap();
        handler.process_packet(att_view);

        // assert: the db has been updated
        assert_eq!(block_on_locally(client.read_attribute(AttHandle(3))).unwrap(), data);
    }

    #[test]
    fn test_unsupported_command() {
        // arrange
        let db = new_test_database(vec![]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let handler = AttCommandHandler::new(client.downgrade());

        // act: send a packet that should not be handled here
        let att_view = AttCommand::new(att::AttSignedWriteCommand {
            handle: AttHandle(3).into(),
            value: vec![1, 2, 3],
            signature: [0; 12],
        })
        .unwrap();
        handler.process_packet(att_view);

        // assert: nothing happens (we crash if anything is unhandled within a mock)
    }

    #[test]
    fn test_malformed_write_command() {
        // arrange
        let db = new_test_database(vec![]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let handler = AttCommandHandler::new(client.downgrade());

        // act: send a write command with a payload that can't be parsed
        let att = att::Att { opcode: att::AttOpcode::WriteCommand, payload: vec![0] };
        let att_view = AttCommand::new(att).unwrap();
        handler.process_packet(att_view);

        // assert: nothing happens (we crash if anything is unhandled within a mock)
    }
}
