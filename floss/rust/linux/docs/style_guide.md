# Bluetooth Documentation Style Guide
This style guide is designed to encourage documentation that maximizes knowledge
share and minimizes mental overhead. While this is technically only a
documentation style guide, good code is self-documenting so there are many
naming conventions stipulated that may result in code changes.

## Acronyms, Abbreviations, and other shortenings

Acronyms can often be confusing for those unfamiliar with them. At the same
time, effective use of acronyms (and abbreviations) can help to quickly convey
information compared to lengthy technical terms
(e.g. `logicalLinkControlAndAdaptationLayerProtocol` takes substantially longer
to read than `l2cap` and offers no additional clarity if the reader is familiar
with the spec).  To ensure effective use of these shorthands, follow the
guidelines below.

1. Acronyms defined by the Bluetooth specification (e.g. `L2CAP`) may be used
   anywhere, but the expanded version (e.g. "Logical Link Control and Adaptation
   Layer") must be added to the [glossary](glossary.md) with an indication of
   which specification document the reader can consult for more information.
   * These acronyms may not be shortened in any context (i.e. `RFCOMM` may not
     be shortened to `rfc`)
2. General computing shorthands (e.g. `tx` instead of `transmit`) are generally
   allowed and should be added to the [glossary](glossary.md) depending on
   obscurity at the discretion of code authors and reviewers.
   * Recommended guideline for ascertaining obscurity: if the definition shows
     up in the first five Google search results without any additional context
     it is likely safe to leave it undocumented. Examples:
     * `mux` is a very common shortening of “multiplexer” and shows up high on
       search results so documenting is optional
     * `fsm` is a common shortening of “finite state machine” but does not show
       up high in search results and should be documented (not to be confused
       with the Flying Spaghetti Monster which is the first result)
3. Acronyms and abbreviations specific to the codebase are generally
   discouraged, but permitted and must be documented at the appropriate level of
   specificity.
   * Terms used across the codebase should be documented in the
     [glossary](glossary.md)
   * Terms used within specific files or directories only may be documented in
     that file or directory
   * Names of enums, structs, traits, files, etc. <b>must<b> be completely
     spelled out (except for Bluetooth Specification acronyms)
4. In documentation files the first occurrence of ALL acronyms should be spelled
   out.
   * Example: you can refer to Three Letter Acronyms (TLAs) as simply TLAs in
     the rest of the document

## Citations

Much of the logic within Floss is dictated by the Bluetooth
Specification. Citing the individual sections of the specification that informed
decisions makes it easier for the reader to understand why code is structured
the way it is and aids debugging efforts. In general, any comments or
explanations should be limited to the code’s structure (“the spec requires that
this field be of length X”) and *not* why the specification makes certain
requirements (“This field has a length of X to accommodate Y and Z with a parity
bit as per the specification”) unless the code is directly related to those
details (a parser for a field may benefit from having an explanation of the
structure of the data it is meant to manipulate).

In code:

```cpp
// This is the maximum allowed size for this field.
// Bluetooth Core Specification Version 2.8, Volume 57, Part Z, Section 15
```

In documentation (markdown):

```md
The order of these operations is required by the Bluetooth Core Spec[^ordering]

[^ordering]: Bluetooth Core Specification Version 2.8, Volume 57, Part Z, Section 15
```

## Top-of-file documentation

All files should have a comment block preceding and code (except includes) which
explains the purpose of the file and covers other meta-information that may be
useful to a reader. There are no strict requirements for how this should be
structured, but the following information should be covered.

1. A short summary of what is included in the file
2. Any relevant parts of the specification that the reader should be familiar
   with
   * It is not necessary to enumerate everything that might be relevant
   * If the file primarily contains the implementation of a profile, it is
     helpful to the reader to have a link to that specification
3. If the file is a “main” file as part of an executable it should contain
   high-level information about:
   * The purpose of the executable
   * Usage information (unless the usage is self-documenting)
   * Architectural overviews such as threading models and state machines
4. It is not necessary to go into details beyond what is needed for a reader to
   be able to navigate the code. If something is necessary but is itself very
   complex it is permissible to link to the documentation for that piece of
   code.
   * Example: an executable relies heavily on a complex state machine to
     function, to the extent that anyone trying to read the code should be
     familiar with it. The executable’s main file should have a short (less than
     one paragraph) explanation of the state machine’s role and then link to the
     file that contains the state machine (which should also be documented).

## Dependency Documentation

Whenever documentation depends on the reader understanding how a different
system or code functions, prefer to write a brief description (1-2 sentences) of
why that system is necessary or how it is being used, and leave details of the
function of that system out. It is heavily encouraged to update a dependency's
documentation as needed.

## System Documentation

Developers expect to find README files that explain the purpose of a given
project and various meta-details about it. This can include build instructions
or usage instructions, as well as information about how to contribute, and
more. All of Floss’s binaries should have such information documented in a
README file. Documentation authors have discretion to decide how much of this
information belongs in a binary’s main file, a binary’s top-level directory, or
in a project-level README.
