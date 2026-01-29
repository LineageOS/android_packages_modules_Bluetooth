#  Copyright 2025 Google LLC
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
"""AICS with workarounds."""

from bumble import gatt
from bumble import gatt_adapters
from bumble.profiles import aics

Characteristic = gatt.Characteristic


class AudioInputControlService(gatt.TemplateService):
    """Audio Input Control Service with workarounds."""

    UUID = gatt.GATT_AUDIO_INPUT_CONTROL_SERVICE

    audio_input_state_characteristic: Characteristic[aics.AudioInputState]
    audio_input_type_characteristic: Characteristic[bytes]
    audio_input_status_characteristic: Characteristic[bytes]
    audio_input_control_point_characteristic: Characteristic
    gain_settings_properties_characteristic: Characteristic[aics.GainSettingsProperties]

    def __init__(
        self,
        audio_input_state: aics.AudioInputState | None = None,
        gain_settings_properties: (aics.GainSettingsProperties | None) = None,
        audio_input_type: str = 'unspecified',
        audio_input_status: aics.AudioInputStatus = aics.AudioInputStatus.ACTIVE,
        audio_input_description: (aics.AudioInputDescription | None) = None,
    ):
        if gain_settings_properties is None:
            gain_settings_properties = aics.GainSettingsProperties(
                gain_settings_unit=1,
                gain_settings_minimum=0,
                gain_settings_maximum=127,
            )

        self.audio_input_state = (aics.AudioInputState()
                                  if audio_input_state is None else audio_input_state)
        self.gain_settings_properties = gain_settings_properties
        self.audio_input_status = audio_input_status
        self.audio_input_description = (aics.AudioInputDescription() if audio_input_description
                                        is None else audio_input_description)

        self.audio_input_control_point = aics.AudioInputControlPoint(self.audio_input_state,
                                                                     self.gain_settings_properties)

        self.audio_input_state_characteristic = (gatt_adapters.SerializableCharacteristicAdapter(
            Characteristic(
                uuid=gatt.GATT_AUDIO_INPUT_STATE_CHARACTERISTIC,
                properties=(Characteristic.Properties.READ | Characteristic.Properties.NOTIFY),
                permissions=Characteristic.Permissions.READ_REQUIRES_ENCRYPTION,
                value=self.audio_input_state,
            ),
            aics.AudioInputState,
        ))
        self.audio_input_state.attribute = self.audio_input_state_characteristic

        self.gain_settings_properties_characteristic = (
            gatt_adapters.SerializableCharacteristicAdapter(
                Characteristic(
                    uuid=gatt.GATT_GAIN_SETTINGS_ATTRIBUTE_CHARACTERISTIC,
                    properties=Characteristic.Properties.READ,
                    permissions=Characteristic.Permissions.READ_REQUIRES_ENCRYPTION,
                    value=self.gain_settings_properties,
                ),
                aics.GainSettingsProperties,
            ))

        self.audio_input_type_characteristic = Characteristic(
            uuid=gatt.GATT_AUDIO_INPUT_TYPE_CHARACTERISTIC,
            properties=Characteristic.Properties.READ,
            permissions=Characteristic.Permissions.READ_REQUIRES_ENCRYPTION,
            value=bytes(audio_input_type, 'utf-8'),
        )

        self.audio_input_status_characteristic = Characteristic(
            uuid=gatt.GATT_AUDIO_INPUT_STATUS_CHARACTERISTIC,
            properties=(Characteristic.Properties.READ | Characteristic.Properties.NOTIFY),
            permissions=Characteristic.Permissions.READ_REQUIRES_ENCRYPTION,
            value=bytes([self.audio_input_status]),
        )

        self.audio_input_control_point_characteristic = Characteristic(
            uuid=gatt.GATT_AUDIO_INPUT_CONTROL_POINT_CHARACTERISTIC,
            properties=Characteristic.Properties.WRITE,
            permissions=Characteristic.Permissions.WRITE_REQUIRES_ENCRYPTION,
            value=gatt.CharacteristicValue(write=self.audio_input_control_point.on_write),
        )

        self.audio_input_description_characteristic = (gatt_adapters.UTF8CharacteristicAdapter(
            Characteristic(
                uuid=gatt.GATT_AUDIO_INPUT_DESCRIPTION_CHARACTERISTIC,
                properties=(Characteristic.Properties.READ | Characteristic.Properties.NOTIFY |
                            Characteristic.Properties.WRITE_WITHOUT_RESPONSE),
                permissions=(Characteristic.Permissions.READ_REQUIRES_ENCRYPTION |
                             Characteristic.Permissions.WRITE_REQUIRES_ENCRYPTION),
                value=gatt.CharacteristicValue(
                    write=self.audio_input_description.on_write,
                    read=self.audio_input_description.on_read,
                ),
            ),))
        self.audio_input_description.attribute = (self.audio_input_description_characteristic)

        super().__init__(
            characteristics=[
                self.audio_input_state_characteristic,
                self.gain_settings_properties_characteristic,
                self.audio_input_type_characteristic,
                self.audio_input_status_characteristic,
                self.audio_input_control_point_characteristic,
                self.audio_input_description_characteristic,
            ],
            primary=False,
        )
