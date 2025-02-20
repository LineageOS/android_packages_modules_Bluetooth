use log::warn;

use crate::gatt::opcode_types::AttCommand;
use crate::packets::att;

use super::att_database::AttDatabase;

/// This struct handles all ATT commands.
pub struct AttCommandHandler<Db: AttDatabase> {
    db: Db,
}

impl<Db: AttDatabase> AttCommandHandler<Db> {
    pub fn new(db: Db) -> Self {
        Self { db }
    }

    pub fn process_packet(&self, packet: AttCommand) {
        let snapshotted_db = self.db.snapshot();
        match packet.opcode {
            att::AttOpcode::WriteCommand => {
                let Ok(packet) = att::AttWriteCommand::try_from(&*packet) else {
                    warn!("failed to parse WRITE_COMMAND packet");
                    return;
                };
                snapshotted_db.write_no_response_attribute(packet.handle.into(), &packet.value);
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
    use crate::gatt::ids::AttHandle;
    use crate::gatt::opcode_types::AttCommand;
    use crate::gatt::server::att_database::{AttAttribute, AttDatabase};
    use crate::gatt::server::command_handler::AttCommandHandler;
    use crate::gatt::server::gatt_database::AttPermissions;
    use crate::gatt::server::test::test_att_db::TestAttDatabase;
    use crate::packets::att;
    use crate::utils::task::block_on_locally;

    #[test]
    fn test_write_command() {
        // arrange
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITHOUT_RESPONSE,
            },
            vec![1, 2, 3],
        )]);
        let handler = AttCommandHandler { db: db.clone() };
        let data = [1, 2];

        // act: send write command
        let att_view = AttCommand::new(att::AttWriteCommand {
            handle: AttHandle(3).into(),
            value: data.to_vec(),
        })
        .unwrap();
        handler.process_packet(att_view);

        // assert: the db has been updated
        assert_eq!(block_on_locally(db.read_attribute(AttHandle(3))).unwrap(), data);
    }

    #[test]
    fn test_unsupported_command() {
        // arrange
        let db = TestAttDatabase::new(vec![]);
        let handler = AttCommandHandler { db };

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
}
