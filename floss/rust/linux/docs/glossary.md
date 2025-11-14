# Bluetooth Glossary

This glossary is for consolidating documentation of terms used throughout
Bluetooth codebases. When adding an entry there are a few important
considerations to keep in mind:

1. The glossary is not exhaustive - it is sufficient to add a few sentences and
   link to a full description
2. Readers may be unfamiliar with this domain - keep entries readable to
   newcomers and if you must use other Bluetooth-specific terminology make sure
   it is also added in the glossary

## Bluetooth Specification

Details of a given profile or protocol may change over time, so descriptions
included here are high level to avoid including innaccurate information. As
such, specific documents are not linked, but all Bluetooth specification
documents can be found on the [Bluetooth website](https://www.bluetooth.com).

### Advacned Audio Distribution Profile
Common aliases: A2DP

Audio streaming profile for music and other audio.

References: A2DP Specification

### Attribute Profile
Common aliases: ATT

The protocol for describing Bluetooth LE data.

References: Core Specification, GATT

### Audio/Video Remote Control Profile
Common aliases: AVRCP

A profile for allowing a Bluetooth device to control media devices.

References: AVRCP Specification

### Generic Access Profile
Common aliases: GAP

The profile that determines how two Bluetooth devices discover and connect to
each other.

References: Core Specification

### Generic Attribute Profile
Common aliases: GATT

Profile for organizing attributes and providing access to them.

References: Core Specification

### Hands-Free Profile
Common aliases: HFP

A superset of HSP and some additional features useful to hands-free interaction
with a device.

References: HFP Specification, HSP

### Human Interface Device Profile
Common aliases: HID, HIDP

A wrapper around the USB Human Interface Device (HID) specification used in
Bluetooth Classic.

References: HID Profile Specification, USB HID Specification

### HID over GATT Profile
Common aliases: HOGP

Profile for supporting HID devices via the GATT protocol for Bluetooth LE
devices.

References: HOGP Specification, USB HID Specification

### Headset Profile
Common aliases: HSP

Profile for enabling headsets that provide audio output and input.

References: HSP Specification

### Logical Link Control and Adaptation Layer Protocol
Common aliases: L2CAP

The communication protocol that most of the Bluetooth stack is built on, similar
to UDP.

References: Core Specification

### Radio Frequency Communication Protocol
common aliases: RFCOMM, rfc[^1]

A communication protocol built on top of L2CAP that provides emulated RS-232
communication.

References: RFCOMM Specification

## Project Shorthands

### Bluetooth Device Address
Common aliases: bda[^1], bdaddr[^1]

The unique identification for a Bluetooth device. It is a 48-bit identifier
typically expressed in hexadecimal.

Notes: It is rarely helpful to refer to these addresses as a Bluetooth Device
Address in Bluetooth code as the Bluetooth portion is implied, and "Device" is
usually less descriptive than "host", "local", "remote", or a similar term.

## Computing Terminology

### Finite State Machine
Common aliases: FSM[^1], State Machine

A mechanism for describing the logical flow of a system based on states and
transitions.

[^1]: These aliases are in use but heavily discouraged as they negatively impact
    readability
