use log::warn;
use pdl_runtime::{DecodeError, EncodeError};

use crate::gatt::ids::AttHandle;
use crate::gatt::opcode_types::AttRequest;
use crate::gatt::server::att_client::WeakAttClient;
use crate::packets::att::{self, AttErrorCode};

use super::transactions::find_by_type_value::handle_find_by_type_value_request;
use super::transactions::find_information_request::handle_find_information_request;
use super::transactions::read_by_group_type_request::handle_read_by_group_type_request;
use super::transactions::read_by_type_request::handle_read_by_type_request;
use super::transactions::read_request::{
    handle_read_blob_request, handle_read_multiple_variable_request, handle_read_request,
};
use super::transactions::write_request::{
    handle_execute_write_request, handle_prepare_write_request, handle_write_request,
};

/// This struct handles all requests needing ACKs. Only ONE should exist per
/// bearer per database, to ensure serialization.
pub struct AttRequestHandler {
    client: WeakAttClient,
}

/// Type of errors raised by request handlers.
#[allow(dead_code)]
enum ProcessingError {
    DecodeError(DecodeError),
    EncodeError(EncodeError),
    RequestNotSupported(att::AttOpcode),
}

impl From<DecodeError> for ProcessingError {
    fn from(err: DecodeError) -> Self {
        Self::DecodeError(err)
    }
}

impl From<EncodeError> for ProcessingError {
    fn from(err: EncodeError) -> Self {
        Self::EncodeError(err)
    }
}

impl AttRequestHandler {
    pub fn new(client: WeakAttClient) -> Self {
        Self { client }
    }

    // Runs a task to process an incoming *request* packet. There should be only one instance of
    // AttRequestHandler per client to ensure that only one request is outstanding at a time
    // (notifications + commands should take a different path).
    pub async fn process_packet(&mut self, packet: AttRequest, mtu: usize) -> att::Att {
        match self.try_parse_and_process_packet(&packet, mtu).await {
            Ok(result) => result,
            Err(_) => {
                // parse error, assume it's an unsupported request
                // TODO(aryarahul): distinguish between REQUEST_NOT_SUPPORTED and INVALID_PDU
                att::AttErrorResponse {
                    opcode_in_error: packet.opcode,
                    handle_in_error: AttHandle(0).into(),
                    error_code: AttErrorCode::RequestNotSupported,
                }
                .try_into()
                .unwrap()
            }
        }
    }

    async fn try_parse_and_process_packet(
        &mut self,
        packet: &AttRequest,
        mtu: usize,
    ) -> Result<att::Att, ProcessingError> {
        let packet = &**packet;
        match packet.opcode {
            att::AttOpcode::ReadRequest => {
                Ok(handle_read_request(packet.try_into()?, mtu, &self.client).await?)
            }
            att::AttOpcode::ReadBlobRequest => {
                Ok(handle_read_blob_request(packet.try_into()?, mtu, &self.client).await?)
            }
            att::AttOpcode::ReadMultipleVariableRequest => {
                Ok(handle_read_multiple_variable_request(packet.try_into()?, mtu, &self.client)
                    .await?)
            }
            att::AttOpcode::ReadByGroupTypeRequest => {
                Ok(handle_read_by_group_type_request(packet.try_into()?, mtu, &self.client).await?)
            }
            att::AttOpcode::ReadByTypeRequest => {
                Ok(handle_read_by_type_request(packet.try_into()?, mtu, &self.client).await?)
            }
            att::AttOpcode::FindInformationRequest => {
                Ok(handle_find_information_request(packet.try_into()?, mtu, &self.client)?)
            }
            att::AttOpcode::FindByTypeValueRequest => {
                Ok(handle_find_by_type_value_request(packet.try_into()?, mtu, &self.client).await?)
            }
            att::AttOpcode::WriteRequest => {
                Ok(handle_write_request(packet.try_into()?, &self.client).await?)
            }
            att::AttOpcode::PrepareWriteRequest => {
                Ok(handle_prepare_write_request(packet.try_into()?, &self.client).await?)
            }
            att::AttOpcode::ExecuteWriteRequest => {
                Ok(handle_execute_write_request(packet.try_into()?, &self.client).await?)
            }
            _ => {
                warn!("Dropping unsupported opcode {:?}", packet.opcode);
                Err(ProcessingError::RequestNotSupported(packet.opcode))
            }
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::core::shared_box::SharedBox;
    use crate::core::uuid::Uuid;
    use crate::gatt::ids::TransportIndex;
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::{AttAttribute, AttPermissions};
    use crate::gatt::server::gatt_database::{
        GattCharacteristicWithHandle, GattDatabase, GattServiceWithHandle,
    };
    use crate::gatt::server::request_handler::AttRequestHandler;
    use crate::gatt::server::test::test_att_db::{new_test_database, TestDatastore};
    use crate::packets::att;
    use pdl_runtime::Packet;

    const TCB_IDX: TransportIndex = TransportIndex(1);

    #[test]
    fn test_read_request() {
        // arrange
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![1, 2, 3],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());
        let att_view =
            AttRequest::new(att::AttReadRequest { attribute_handle: AttHandle(3).into() }).unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(Ok(response), att::AttReadResponse { value: vec![1, 2, 3] }.try_into());
    }

    #[test]
    fn test_read_blob_request() {
        // arrange
        let data: Vec<u8> = (0..255).collect();
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());
        const MTU: usize = 31;

        // Returns the expected part of `data` for the `offset`.
        let get_expected_value =
            |offset| &data[offset..std::cmp::min(offset + MTU - 1, data.len())];

        for offset in [0, 13, 50, 250, 255] {
            let att_view = AttRequest::new(att::AttReadBlobRequest {
                attribute_handle: AttHandle(3).into(),
                offset: offset as u16,
            })
            .unwrap();

            // act
            let response = tokio_test::block_on(handler.process_packet(att_view, MTU));

            // assert
            assert_eq!(
                Ok(response),
                att::AttReadBlobResponse { value: get_expected_value(offset).into() }.try_into()
            );
        }
    }

    #[test]
    fn test_read_blob_request_with_bad_offset() {
        // arrange
        let data: Vec<u8> = (0..255).collect();
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadBlobRequest {
            attribute_handle: AttHandle(3).into(),
            offset: 256,
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadBlobRequest,
                handle_in_error: AttHandle(3).into(),
                error_code: AttErrorCode::InvalidOffset
            }
            .try_into()
        );
    }

    #[test]
    fn test_read_blob_request_with_invalid_handle() {
        // arrange
        let data: Vec<u8> = (0..255).collect();
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadBlobRequest {
            attribute_handle: AttHandle(4).into(),
            offset: 256,
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadBlobRequest,
                handle_in_error: AttHandle(4).into(),
                error_code: AttErrorCode::InvalidHandle
            }
            .try_into()
        );
    }

    #[test]
    fn test_read_multiple_variable_request() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'3'],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x4567),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'4'],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadMultipleVariableRequest {
            attribute_handles: [AttHandle(3).into(), AttHandle(4).into()].into(),
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(response.encoded_len(), 1 + 3 + 3);
        assert_eq!(
            Ok(response),
            att::AttReadMultipleVariableResponse {
                length_values: vec![
                    att::LengthValue { length: 1, value: vec![b'3'] },
                    att::LengthValue { length: 1, value: vec![b'4'] }
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_read_multiple_variable_request_truncated() {
        let data: Vec<u8> = (0..255).collect();
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'3'],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x4567),
                    permissions: AttPermissions::READABLE,
                },
                data.clone(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: Uuid::new(0x89ab),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'5'],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadMultipleVariableRequest {
            attribute_handles: [AttHandle(3).into(), AttHandle(4).into(), AttHandle(5).into()]
                .into(),
        })
        .unwrap();

        // act
        const MTU: usize = 31;
        let response = tokio_test::block_on(handler.process_packet(att_view, MTU));

        // assert
        assert_eq!(response.encoded_len(), MTU);
        assert_eq!(
            Ok(response),
            att::AttReadMultipleVariableResponse {
                length_values: vec![
                    att::LengthValue { length: 1, value: vec![b'3'] },
                    att::LengthValue {
                        length: data.len() as u16,
                        value: data[..MTU - 1 - 3 - 2].into(),
                    }
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_read_multiple_variable_request_truncated_with_no_room_for_length() {
        const MTU: usize = 31;

        // This is chosen so that after encoding the second attribute there is only 1 byte free,
        // which isn't sufficient to store the length for the next attribute.
        let data = vec![0xaf; MTU - 1 - 3 - 2 - 1];

        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'3'],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x4567),
                    permissions: AttPermissions::READABLE,
                },
                data.clone(),
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: Uuid::new(0x89ab),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'5'],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadMultipleVariableRequest {
            attribute_handles: [AttHandle(3).into(), AttHandle(4).into(), AttHandle(5).into()]
                .into(),
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, MTU));

        // assert
        // This checks our math above is correct and there's one byte free.
        assert_eq!(response.encoded_len(), MTU - 1);

        assert_eq!(
            Ok(response),
            att::AttReadMultipleVariableResponse {
                length_values: vec![
                    att::LengthValue { length: 1, value: vec![b'3'] },
                    att::LengthValue { length: data.len() as u16, value: data }
                ]
            }
            .try_into(),
        );
    }

    #[test]
    fn test_read_multiple_variable_request_at_least_two() {
        // arrange
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![b'3'],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadMultipleVariableRequest {
            attribute_handles: [AttHandle(3).into()].into(),
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadMultipleVariableRequest,
                handle_in_error: att::AttHandle { handle: 0 },
                error_code: att::AttErrorCode::InvalidPdu,
            }
            .try_into()
        );
    }

    #[test]
    fn test_read_multiple_variable_request_all_handles_valid() {
        // arrange
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![0xaf; 255],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadMultipleVariableRequest {
            attribute_handles: [AttHandle(3).into(), AttHandle(5).into()].into(),
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadMultipleVariableRequest,
                handle_in_error: AttHandle(5).into(),
                error_code: att::AttErrorCode::InvalidHandle,
            }
            .try_into()
        );
    }

    #[test]
    fn test_read_multiple_variable_request_value_longer_than_65535_bytes() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE,
                },
                vec![b'3'],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE,
                },
                vec![0xaf; 65536],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        let att_view = AttRequest::new(att::AttReadMultipleVariableRequest {
            attribute_handles: [AttHandle(3).into(), AttHandle(4).into()].into(),
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadMultipleVariableRequest,
                handle_in_error: AttHandle(4).into(),
                error_code: att::AttErrorCode::UnlikelyError,
            }
            .try_into()
        );
    }

    #[test]
    fn test_queued_writes() {
        // arrange
        let data = vec![b'3'; 100];
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        // act & assert
        tokio_test::block_on(async {
            let mut expected = data.clone();

            let request = att::AttPrepareWriteRequest {
                handle: AttHandle(3).into(),
                offset: 5,
                value: vec![b'4'; 10],
            };
            expected[5..15].fill(b'4');

            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // Reading the attribute should return the original value.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest {
                            attribute_handle: AttHandle(3).into()
                        })
                        .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: data.clone() }.try_into()
            );

            let request = att::AttPrepareWriteRequest {
                handle: AttHandle(3).into(),
                offset: 30,
                value: vec![b'5'; 7],
            };
            expected[30..37].fill(b'5');

            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // Reading the attribute should return the original value.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest {
                            attribute_handle: AttHandle(3).into()
                        })
                        .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: data.clone() }.try_into()
            );

            // Execute the queued requests.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttExecuteWriteRequest { commit: 1 }).unwrap(),
                        255
                    )
                    .await),
                att::AttExecuteWriteResponse {}.try_into()
            );

            // Reading the attribute should return the new value.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest {
                            attribute_handle: AttHandle(3).into()
                        })
                        .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: expected }.try_into()
            );
        });
    }

    #[test]
    fn test_cancelling_queued_writes() {
        // arrange
        let data = vec![b'3'; 100];
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        // act & assert
        tokio_test::block_on(async {
            let mut expected = data.clone();

            let request = att::AttPrepareWriteRequest {
                handle: AttHandle(3).into(),
                offset: 5,
                value: vec![b'4'; 10],
            };

            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // Cancel the queued request.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttExecuteWriteRequest { commit: 0 }).unwrap(),
                        255
                    )
                    .await),
                att::AttExecuteWriteResponse {}.try_into()
            );

            // Prepare and execute another request and it shouldn't have the canceled request.

            // Reading the attribute should return the original value.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest {
                            attribute_handle: AttHandle(3).into()
                        })
                        .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: data.clone() }.try_into()
            );

            let request = att::AttPrepareWriteRequest {
                handle: AttHandle(3).into(),
                offset: 30,
                value: vec![b'5'; 7],
            };
            expected[30..37].fill(b'5');

            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // Execute the queued requests.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttExecuteWriteRequest { commit: 1 }).unwrap(),
                        255
                    )
                    .await),
                att::AttExecuteWriteResponse {}.try_into()
            );

            // Reading the attribute should return the new value.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest {
                            attribute_handle: AttHandle(3).into()
                        })
                        .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: expected }.try_into()
            );
        });
    }

    #[test]
    fn test_queued_write_with_invalid_offset() {
        // arrange
        let data = vec![b'3'; 100];
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        // act & assert
        tokio_test::block_on(async {
            let request = att::AttPrepareWriteRequest {
                handle: AttHandle(3).into(),
                offset: 110,
                value: vec![b'4'; 10],
            };

            // The prepare should succeed.
            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // Execute the queued requests.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttExecuteWriteRequest { commit: 1 }).unwrap(),
                        255
                    )
                    .await),
                att::AttErrorResponse {
                    opcode_in_error: att::AttOpcode::ExecuteWriteRequest,
                    handle_in_error: AttHandle(0).into(), // Ideally this would be 3.
                    error_code: AttErrorCode::InvalidOffset
                }
                .try_into()
            );
        });
    }

    #[test]
    fn test_queued_write_with_invalid_length() {
        // arrange
        let data = vec![b'3'; 100];
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            data.clone(),
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        // act & assert
        tokio_test::block_on(async {
            let request = att::AttPrepareWriteRequest {
                handle: AttHandle(3).into(),
                offset: 90,
                value: vec![b'4'; 30],
            };

            // The prepare should succeed.
            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // Execute the queued requests.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttExecuteWriteRequest { commit: 1 }).unwrap(),
                        255
                    )
                    .await),
                att::AttErrorResponse {
                    opcode_in_error: att::AttOpcode::ExecuteWriteRequest,
                    handle_in_error: AttHandle(0).into(), // Ideally this would be 3.
                    error_code: AttErrorCode::InvalidAttributeValueLength,
                }
                .try_into()
            );
        });
    }

    #[test]
    fn test_queued_write_to_different_datastores() {
        // arrange
        let handle1 = AttHandle(5);
        let handle1_value = [5];
        let datastore1 = TestDatastore::new([(handle1, handle1_value.into())]);
        let handle2 = AttHandle(8);
        let handle2_value = [8];
        let datastore2 = TestDatastore::new([(handle2, handle2_value.into())]);

        let db = SharedBox::new(GattDatabase::new());

        db.add_service_with_handles(
            GattServiceWithHandle {
                handle: AttHandle(3),
                type_: Uuid::new(1),
                characteristics: vec![GattCharacteristicWithHandle {
                    handle: handle1,
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
                    descriptors: vec![],
                }],
            },
            datastore1,
        )
        .unwrap();
        db.add_service_with_handles(
            GattServiceWithHandle {
                handle: AttHandle(6),
                type_: Uuid::new(1),
                characteristics: vec![GattCharacteristicWithHandle {
                    handle: handle2,
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
                    descriptors: vec![],
                }],
            },
            datastore2,
        )
        .unwrap();

        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());

        // act & assert
        tokio_test::block_on(async {
            let mut expected = handle1_value.to_vec();
            let request =
                att::AttPrepareWriteRequest { handle: handle1.into(), offset: 0, value: vec![15] };
            expected[0] = 15;

            // The prepare should succeed.
            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };

            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into()
            );

            // A prepare to a different datastore should fail.
            let request =
                att::AttPrepareWriteRequest { handle: handle2.into(), offset: 0, value: vec![6] };
            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request.clone()).unwrap(), 255).await),
                att::AttErrorResponse {
                    opcode_in_error: att::AttOpcode::PrepareWriteRequest,
                    handle_in_error: handle2.into(),
                    error_code: AttErrorCode::RequestNotSupported,
                }
                .try_into()
            );

            // Executing the existing queued request should still succeed.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttExecuteWriteRequest { commit: 1 }).unwrap(),
                        255
                    )
                    .await),
                att::AttExecuteWriteResponse {}.try_into()
            );

            // Reading the attribute should return the new value.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest { attribute_handle: handle1.into() })
                            .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: expected }.try_into()
            );

            // Reading attribute 4 should be unchanged.
            assert_eq!(
                Ok(handler
                    .process_packet(
                        AttRequest::new(att::AttReadRequest { attribute_handle: handle2.into() })
                            .unwrap(),
                        255
                    )
                    .await),
                att::AttReadResponse { value: handle2_value.into() }.try_into()
            );

            // A prepare to a different datastore should now succeed.
            let expected_response = att::AttPrepareWriteResponse {
                handle: request.handle.clone(),
                offset: request.offset,
                value: request.value.clone(),
            };
            assert_eq!(
                Ok(handler.process_packet(AttRequest::new(request).unwrap(), 255).await),
                expected_response.try_into(),
            );
        });
    }

    #[test]
    fn test_unsupported_request() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE,
                },
                vec![1, 2, 3],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x5678),
                    permissions: AttPermissions::READABLE,
                },
                vec![1, 2, 3],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let mut handler = AttRequestHandler::new(client.downgrade());
        let att_view = AttRequest::new(att::AttReadMultipleRequest {
            attribute_handles: vec![AttHandle(3).into(), AttHandle(4).into()],
        })
        .unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadMultipleRequest,
                handle_in_error: AttHandle(0).into(),
                error_code: AttErrorCode::RequestNotSupported
            }
            .try_into()
        );
    }
}
