# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Class to hold the GATT service/characteristic/descriptor object."""

import uuid


class Service:
    """Class represents Bluetooth GATT service."""

    def __init__(self,
                 instance_id=None,
                 service_type=None,
                 uuid=None,
                 characteristics=None,
                 included_services=None,
                 value=None):
        self.instance_id = instance_id
        self.service_type = service_type
        self.uuid = uuid
        self.characteristics = characteristics
        self.included_services = included_services
        self.value = value

    def to_dict(self):
        """Converts service object to dictionary.

        Returns:
            GATT service as dict.
        """
        return {
            'instance_id': self.instance_id,
            'service_type': self.service_type,
            'uuid': self.uuid,
            'included_services': [service.to_dict() for service in self.included_services],
            'characteristics': [characteristic.to_dict() for characteristic in self.characteristics],
            'value': self.value
        }


class Characteristic:
    """Class represents Bluetooth GATT characteristic."""

    def __init__(self,
                 instance_id=None,
                 permissions=None,
                 write_type=None,
                 descriptors=None,
                 uuid=None,
                 key_size=None,
                 properties=None,
                 value=None):
        self.instance_id = instance_id
        self.permissions = permissions
        self.write_type = write_type
        self.descriptors = descriptors
        self.uuid = uuid
        self.key_size = key_size
        self.properties = properties
        self.value = value

    def to_dict(self):
        """Converts characteristic object to dictionary.

        Returns:
            GATT characteristic as dict.
        """
        return {
            'properties': self.properties,
            'permissions': self.permissions,
            'uuid': self.uuid,
            'instance_id': self.instance_id,
            'descriptors': [descriptor.to_dict() for descriptor in self.descriptors],
            'key_size': self.key_size,
            'write_type': self.write_type,
            'value': self.value
        }


class Descriptor:
    """Class represents Bluetooth GATT descriptor."""

    def __init__(self, permissions=None, uuid=None, instance_id=None, value=None):
        self.permissions = permissions
        self.uuid = uuid
        self.instance_id = instance_id
        self.value = value

    def to_dict(self):
        """Converts descriptor object to dictionary.

        Returns:
            GATT descriptor as dict.
        """
        return {
            'instance_id': self.instance_id,
            'permissions': self.permissions,
            'uuid': self.uuid,
            'value': self.value
        }


def create_gatt_service(service):
    """Creates GATT service from a dictionary.

    Args:
        service: Bluetooth GATT service as a dictionary.

    Returns:
        Bluetooth GATT service object.
    """
    return Service(
        instance_id=service['instance_id'],
        service_type=service['service_type'],
        uuid=str(uuid.UUID(bytes=bytes(service['uuid']))).upper(),
        included_services=[create_gatt_service(included_service) for included_service in service['included_services']],
        characteristics=[create_gatt_characteristic(characteristic) for characteristic in service['characteristics']],
        value=service.get('value'))


def create_gatt_characteristic(characteristic):
    """Creates GATT characteristic from a dictionary.

    Args:
        characteristic: Bluetooth GATT characteristic as a dictionary.

    Returns:
        Bluetooth GATT characteristic object.
    """
    return Characteristic(
        properties=characteristic['properties'],
        permissions=characteristic['permissions'],
        uuid=str(uuid.UUID(bytes=bytes(characteristic['uuid']))).upper(),
        instance_id=characteristic['instance_id'],
        descriptors=[create_gatt_characteristic_descriptor(descriptor) for descriptor in characteristic['descriptors']],
        key_size=characteristic['key_size'],
        write_type=characteristic['write_type'],
        value=characteristic.get('value'))


def create_gatt_characteristic_descriptor(descriptor):
    """Creates GATT descriptor from a dictionary.

    Args:
        descriptor: Bluetooth GATT descriptor as a dictionary.

    Returns:
        Bluetooth GATT descriptor object.
    """
    return Descriptor(instance_id=descriptor['instance_id'],
                      permissions=descriptor['permissions'],
                      uuid=str(uuid.UUID(bytes=bytes(descriptor['uuid']))).upper(),
                      value=descriptor.get('value'))


def convert_object_to_dict(obj):
    """Coverts object to dictionary.

    Args:
        obj: Service/Characteristic/Descriptor object.

    Returns:
        A dictionary represents the object.
    """
    if isinstance(obj, (Descriptor, Characteristic, Service)):
        return obj.to_dict()
    elif isinstance(obj, list):
        return [convert_object_to_dict(item) for item in obj]
    else:
        return obj
