# PC-Based DKM Simulator — Requirements Document

## 0. Scope of this document — read this first

This repo contains two different things, and it's important not to conflate them:

- **`mock_r`, `bin_gen`, `c_sim`** — internal testing scaffolding built in *this* repo, in C++, to develop and validate against without needing the real target hardware. `mock_r` stands in for the DKM ("module under test"); `bin_gen` generates custom input binaries for it; `c_sim` replays those binaries at mock_r and prints/validates whatever comes back. None of this is the deliverable — it exists so the real simulator (below) has something correct to test against, and so interface/protocol questions could be answered empirically instead of guessed at.
- **"The simulator"** — the actual product this document specifies requirements for. It is being built by a other party, is not in this repo, and (unlike `c_sim`) cannot simply `#include` `mock_r/inc` — it needs its own way of knowing the message structs, and comes with a full UI, a message database, and live visualization, none of which `c_sim` has or needs.

Several facts in this document (wire format, connection topology, framing) were originally open questions and are now confirmed, because `mock_r`/`bin_gen`/`c_sim` were built and exercised against each other. Those sections are marked accordingly.

## 1. Purpose

The DKM (Downloadable Kernel Module) running on the VxWorks target receives multiple types of input messages, processes them, and produces output messages. Historically, the only way to exercise the DKM was to write full binary files of input messages, load them onto the target, and inspect the resulting output binaries in a separate analysis application. This makes it impractical to:

- Hand-craft or tweak individual input messages for targeted testing
- Construct edge-case / unusual inputs that don't exist in captured binaries
- Iterate quickly (each test cycle requires binary editing + a full DKM run)

This document defines requirements for a **PC-based simulator** that connects to the DKM (or, during development, to `mock_r` standing in for it) over TCP, lets an engineer view and edit input messages in a human-readable form, sends them to the DKM, and captures/displays/visualizes the DKM's output messages in real time.

## 2. Goals

- **G1 — Struct independence:** The simulator's core logic must not need to change when a message struct is added or modified. Only a schema description needs to change.
- **G2 — Human-editable messages:** Input messages (including dynamically-sized fields) must be viewable and editable field-by-field in a UI, not as raw bytes — including messages already loaded from a file, newly added from scratch, or pulled from the message library (G7).
- **G3 — Live stimulation:** The simulator must be able to send messages to the DKM over TCP, replacing (or supplementing) the binary-file workflow, with interactive playback control (G6).
- **G4 — Output capture:** The simulator must listen for and decode DKM output over TCP, displaying messages the same way input messages are shown.
- **G5 — Binary file compatibility:** The simulator must still be able to load and parse the existing input/output binary files, for continuity with current data and workflows.
- **G6 — Playback control:** Start / stop / pause a run, adjust send speed live, and treat a paused run's not-yet-sent messages as fully editable — re-deriving timing on every start/resume rather than baking it in once.
- **G7 — Message library:** Frequently-used messages can be saved to and loaded from a persistent local store, independent of any one input file, for fast reuse across runs.
- **G8 — Visualization:** Every message type has a simulator-defined visual representation (none, rectangular area, circular area, line, point, connected points/track), applied to both input and output messages. This is entirely the simulator's concern — the DKM and its interface headers carry no visualization metadata.
- **G9 — Track/object memory:** Messages that carry a correlation ID (see `Prediction.track_id`, §4) and recur over time are understood as observations of the same object and rendered as a connected track, not independent points.
- **G10 — Near-real-time output visualization:** The visualization surface (not the message lists) reflects incoming output with minimal latency; the receive path must never block on rendering.

## 3. Non-Goals / Out of Scope (proposed — confirm)

- Replacing the existing analysis application's deeper analytics features (this tool is for stimulation and quick inspection, not the full analysis suite).
- Modifying the DKM itself.
- Automated test-case generation / fuzzing (could be a future extension, not a v1 requirement).
- Heuristic (inferred) object correlation — see §4's track-ID decision; correlation is by explicit ID, not position/velocity guessing.

## 4. Confirmed Architecture Facts

These were open questions in earlier drafts of this document. They're now confirmed, because `mock_r`, `bin_gen`, and `c_sim` were built and run against each other end-to-end.

- **Wire format:** every message is a fixed `MsgHeader` (`sender_id`, `receiver_id`, `msg_id`, `timestamp`, `msg_length` — all `std::size_t`, native byte order, no explicit serialization step) immediately followed by that message type's payload fields, with no padding/alignment tricks beyond normal C++ struct layout. `msg_length` is the *total* size of the message including the header, and is authoritative for framing: a reader can split a byte stream into messages using only `msg_length`, without needing to already know the payload's type-specific size.
- **Binary file format:** identical to the wire format. A file (e.g. `input.bin`) is simply messages concatenated back to back, no extra delimiter or outer framing.
- **Connection topology — corrected from earlier drafts:** it is **not** one bidirectional link. The DKM has three separate TCP links, one per peer module — RSP, RSM, CRM — mirroring three separate PCIe links on the real target. Each is independently bidirectional (a module can both receive from and send to its peer on the same socket). Message routing between structs and links is by the well-known `ModuleId` on each end, not by any in-band tag beyond `msg_id`.
- **The DKM is the TCP client, not the server.** It connects out to each peer's host:port. This is the reverse of what earlier drafts of this document assumed ("simulator connects to the DKM"). Concretely: the simulator must be listening on all three ports *before* the DKM process starts. The DKM's connection attempt happens exactly once per link at startup and does **not** retry on failure — if the simulator isn't already listening, that link simply never comes up for the rest of that DKM run.
- **Cross-link message dependencies exist and must be respected by playback timing.** Some message types reference state established by an earlier message on a *different* link — e.g. RSP's `DetectionReport.beam_id` must already have been announced via RSM's `BeamReport` before the DKM can produce a result from it. A simulator that paces each link purely off "time since the last message *on that link*" can silently let a fast link race ahead of a slow one and break this ordering — this was reproduced and fixed in `c_sim` during development. All three links must share **one global replay clock**; see G6/§5.3.
- **Interface sync is still an open mechanism** (§8) — but it is real and still needed for the actual simulator, precisely because it's a separate codebase from `mock_r`. This is different from `mock_r`/`bin_gen`/`c_sim`, which sidestep the problem entirely by being C++ and compiling directly against `mock_r/inc` — that shortcut is only available to code living in this repo, not to the other party's simulator.
- **`Prediction` now carries a `track_id` field** (`mock_r/inc/interface/crm.h`), added specifically so G9 has ground truth to correlate against instead of needing heuristic (position/velocity nearest-neighbor) matching.

## 5. Functional Requirements

### 5.1 Message Schema / Interface Sync Engine
- FR-1: Load a schema description of all known message types, their fixed fields, and any dynamic/repeating field groups.
- FR-2: Given a schema and a raw byte buffer, decode it into a structured, named-field representation.
- FR-3: Given a schema and a structured, named-field representation (including user edits), re-encode it into the correct raw byte layout (correct sizes, byte order, and repeat counts).
- FR-4: Support the field types actually used by the DKM (int8/16/32/64, uint variants, float, double, fixed-size arrays/strings, etc.).
- FR-5: Validate edited values against field constraints (type range, array length vs. declared count field) before allowing a message to be sent or saved.
- FR-5a: The schema's source-of-truth mechanism is still undecided (hand-maintained vs. a header-parsing codegen tool — see §8), but this repo's `mock_r/inc` headers, `bin_gen`-produced binaries, and `c_sim`'s working decoder are available as ground truth to validate whatever mechanism is chosen against.

### 5.2 Input Binary File Handling & Message List
- FR-6: Load an existing input binary file and split it into its constituent messages using `msg_length` (§4) — no per-type size table required.
- FR-7: Display the parsed messages as a list (by type, sequence, timestamp).
- FR-8: Allow selecting a message and editing every field, including add/remove of repeated elements. A message that has already been sent during the current run becomes read-only history (see FR-13); only not-yet-sent messages are editable, at any point during a paused run.
- FR-9: Allow creating a brand-new message from scratch and inserting it into the list at a chosen position, with an explicit timing offset relative to its neighbors (it does not implicitly inherit timing from wherever it's dropped).
- FR-10: Allow saving the (possibly edited) message set back out as a binary file, in the original format.

### 5.3 Playback Control
- FR-11: Start, stop, and pause a run. Stop and pause are distinct: pause suspends sending only (connections stay open, state is retained); stop's exact semantics (full reset to message 0 vs. abort-in-place) need to be pinned down with stakeholders — see §8.
- FR-12: Adjust send speed as a live multiplier during a run, not just at startup.
- FR-13: All three links (RSP/RSM/CRM) are paced from **one shared replay clock**, derived from each message's recorded timestamp offset from the run's earliest message — never three independently-paced streams (§4).
- FR-14: On every start or resume, recompute each not-yet-sent message's absolute send time from (a) its recorded relative offset and (b) a freshly-captured "replay start" reference instant at the current speed. This is what makes "pause, edit the remaining messages, resume" behave correctly without needing to touch or renumber anything already sent.
- FR-15: Surface connection state and send errors in the UI.

### 5.4 TCP Communication — Stimulus (Simulator → DKM)
- FR-16: Listen on all three configured host/ports (RSP, RSM, CRM) as a TCP **server** — see §4 for why this is the server side, not the client.
- FR-17: Must be listening on all three ports before the DKM is started, since the DKM does not retry a failed connection.
- FR-18: Send messages per the playback model in §5.3, encoded per the wire format in §4.

### 5.5 TCP Communication — Output Capture (DKM → Simulator)
- FR-19: On the same three links, listen for and decode messages the DKM sends back, using the same schema engine as input (§5.1).
- FR-20: Display captured output messages live, appended to a growing list, in the same field-exposed format as input messages.
- FR-21: Allow saving captured output to a binary file compatible with the existing output-binary format.

### 5.6 Message Library
- FR-22: Save any message (as currently edited) to a persistent local store, independent of the run it came from, for reuse across future runs.
- FR-23: Browse/search the library and insert a saved message into the current run's list (subject to FR-9's positioning/timing rules).
- FR-24: Each saved entry records the schema/interface version it was saved against, so a later interface change can flag it as stale instead of silently sending a now-incorrect layout.

### 5.7 Visualization
- FR-25: Each message type is mapped to a visualization technique — none, rectangular area, circular area, line, point, or connected points ("track") — defined entirely within the simulator (G8). Neither `mock_r` nor the interface headers carry this mapping.
- FR-26: Visualization math must reproduce the DKM's actual conventions exactly, not a simulator-invented approximation — e.g. `GateAreaMsg` is polar (distance/heading) while `ReportingAreaMsg` is Cartesian (x/y), and `mock_r/src/core/processing.cpp` converts between them assuming heading in radians (`x = distance·cos(heading)`, `y = distance·sin(heading)`). A visualization using a different convention would misrepresent what the DKM actually computes as inside/outside a given area.
- FR-27: Messages sharing a correlation ID (e.g. `Prediction.track_id`) are rendered as one connected-points track across time, not independent unconnected points (G9).
- FR-28: Output messages are visualized the same way input messages are (FR-25–27), as they arrive.

### 5.8 UI
- FR-29: List view of loaded/captured messages (input and output), filterable/sortable by type.
- FR-30: Detail/edit view showing all fields of a selected message, with appropriate input controls per field type.
- FR-31: Clear visual distinction between input (editable / already-sent history) and output (read-only) messages.
- FR-32: Basic session/log view showing what was sent and received, in order, with timestamps.
- FR-33: A visualization surface, separate from the list views, meeting the near-real-time requirement in §6.

## 6. Non-Functional Requirements

- NFR-1: **Extensibility** — adding a new message type must require only a schema update (§5.1), not simulator code changes, in the common case.
- NFR-2: **Platform** — target stack for the other-party simulator is currently unconfirmed (an earlier draft assumed Java; that should be reconfirmed rather than relied on).
- NFR-3: **Performance** — must handle the message volumes seen in real capture files/sessions without UI lag (volume/rate numbers still TBD — see §8).
- NFR-4: **Data fidelity** — encode/decode round-trip must be byte-exact for unmodified fields, so re-saved binaries remain valid for existing downstream tools.
- NFR-5: **Robustness** — malformed/unknown messages (e.g. from a schema mismatch) should be reported clearly rather than silently corrupted or crashing the tool.
- NFR-6: **Visualization latency** — output should render with minimal, bounded delay from wire receipt. "As fast as possible" isn't a testable target; a concrete number (e.g. sub-100ms wire-to-pixel) should be agreed with stakeholders (see §8). The receive path must hand off to rendering asynchronously (mirroring `mock_r`'s own internal receive-thread → `MessageQueue` → processing-thread pattern, see `mock_r/src/core/message_queue.hpp`) so a slow render frame can never stall reading the next incoming message.

## 7. Assumptions

- A1: Message type can be identified from a message's header/leading bytes without needing full context (confirmed — `msg_id` in `MsgHeader`, §4).
- A2: The dynamic-count fields (e.g. `detection_count` in `DetectionReport`) are always explicit fields within the message itself.
- A3: The DKM's wire protocol and the binary file format are the same encoding (confirmed, §4).
- A4: `mock_r` is representative enough of the real DKM's connection behavior (client-initiated, single connection attempt, three separate links) that building against it transfers to the real target. This should be reconfirmed against the real DKM if/when access is available.

## 8. Open Questions / Risks

- **Interface sync mechanism:** hand-maintained schema file vs. a header-parsing codegen tool against `mock_r/inc` (§4, §5.1) — still undecided.
- **Other party's tech stack:** unconfirmed; determines how "interface sync" is actually delivered (schema file format, codegen target language, etc.).
- **Stop vs. pause semantics (FR-11):** does "stop" reset the run to message 0 (fully re-editable) or abort in place (connections torn down, done)? Needs a decision.
- **Editing scope while paused:** FR-8/14 cover field-value edits and (for new messages) position/timing. Should the operator also be able to reorder pending messages or change an existing pending message's timing gap while paused, not just its field values?
- **Message library storage:** flat files with metadata vs. an embedded database (e.g. SQLite) — needs a decision informed by whatever the other party's stack turns out to be.
- **Track correlation beyond `Prediction`:** `track_id` was added there specifically for G9. Do `DetectionReport` or `MeasurementReport` also need a correlation ID for the same object as it moves through earlier pipeline stages, or is `Prediction` the only stage where this matters?
- **Visualization latency target (NFR-6):** needs an actual number from stakeholders.
- **Message volume/performance targets (NFR-3):** still unknown.

## 9. Suggested Phasing (proposed)

1. **Phase 0 — done:** `mock_r` (DKM stand-in), `bin_gen` (input binary generation), `c_sim` (headless C++ replay/decode reference) — validated end-to-end, and used to confirm §4's facts empirically rather than guess at them. Not the deliverable, but the reference implementation the real simulator's schema/decode logic can be checked against.
2. **Phase 1 — Core engine:** interface-sync mechanism decided and built; schema/decode/encode engine proven against real message types, including cross-checking against `c_sim`'s known-correct decode.
3. **Phase 2 — File UI:** load/view/edit/save binary files through the UI (§5.2, §5.8).
4. **Phase 3 — Playback & TCP stimulus:** connect (as server — §4) and send to the DKM, with full start/stop/pause/speed control (§5.3, §5.4).
5. **Phase 4 — TCP capture & visualization:** listen for and display + visualize output (§5.5, §5.7, §5.8), meeting the near-real-time requirement (§6).
6. **Phase 5 — Message library & polish:** save/load frequently-used messages (§5.6), session logging, validation refinements.
