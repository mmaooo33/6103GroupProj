# SC6103 Distributed Systems — Course Project Requirements

**Project:** Design and Implementation of a Distributed Flight Information System  
**Academic term:** 2026–2027 Trimester 1  
**Team:** 4 members (A, B, C, D)  
**Requirements document version:** 0.1 — working draft (12 October 2026)  
**Source:** *Course.Project.for2026a.pdf*, pp. 1–4

> **Document status:** This document separates **assignment requirements** from **team proposals / pending decisions**. The original course-project brief remains authoritative. Detailed byte-level protocol specifications will be recorded separately in `protocol.md` (Step 2).

## 1. Project Objective and Scope

Design and implement a **distributed flight information system** using **client–server architecture and UDP sockets (not TCP)**. The server holds flight information and performs requested flight operations. Clients accept user requests, send them to the server through UDP, receive replies, and display results in a console interface. The project also demonstrates callback-based monitoring, manual message marshalling, and fault-tolerant remote invocation.

### 1.1 Required Flight Data

Each flight must contain:

| Field | Required representation / meaning |
|---|---|
| Flight identifier | Integer |
| Source (departure place) | Variable-length string |
| Destination (arrival place) | Variable-length string |
| Departure time | Team-defined time data structure |
| Airfare | Floating-point value |
| Seat availability | Integer: number of seats currently available |

The server must keep flight information **at least in memory** while running. Persistent disk storage is **not required**.

## 2. Functional Requirements

### FR-01 — Search Flights by Route

- **Input:** Source place and destination place.
- **Behavior:** Search flights with matching source and destination.
- **Success:** Return **all** matching flight identifiers (not just the first match).
- **Error:** Return an appropriate message if no flights match.
- **Acceptance check:** Seed two flights with the same route; the client shows both IDs. Search an unknown route; the client shows an error.

### FR-02 — Retrieve Flight Details

- **Input:** Flight identifier.
- **Success:** Return the departure time, airfare, and current available-seat count.
- **Error:** Return an appropriate message if the flight ID does not exist.
- **Acceptance check:** Existing ID returns correct details; unknown ID returns an error.

### FR-03 — Reserve Seats

- **Input:** Flight identifier and number of seats requested.
- **Success:** Return an acknowledgement and update the available-seat count **on the server**.
- **Error:** Return an appropriate message for invalid input, including a nonexistent flight ID or insufficient seats.
- **Acceptance check:** A successful reservation reduces server-side availability by the requested amount; a failed reservation does not incorrectly reduce availability. A subsequent details request reflects the updated count.

### FR-04 — Monitor Seat Availability via Callback

- **Input:** Flight identifier and requested monitoring interval length.
- **Registration:** The server records the monitoring client's IP address and port number.
- **During the interval:** Every successful seat reservation on that flight by **any client** triggers a callback containing updated seat availability to **all currently registered clients** monitoring that flight.
- **On expiration:** The server removes the expired registration and stops sending updates to that client.
- **Concurrency requirement:** Multiple clients must be able to monitor flight updates concurrently.
- **Allowed simplification:** A monitoring client may stop accepting new user requests until its monitoring interval expires; client-side multithreading is therefore not required.
- **Acceptance check:** While one client monitors, another reserves seats and the monitor receives the update. Test two concurrent monitors. After a monitoring interval expires, that client no longer receives callbacks.

### FR-05 — Additional Idempotent Operation (Team-Defined)

- **Assignment requirement:** Implement **one additional operation on flights** whose repeated execution with the same arguments has the same resulting state as one execution.
- **Team proposal — NOT YET APPROVED:** `SET_AIRFARE(flightId, newAirfare)`. Repeating the same request sets the airfare to the same value.
- **Acceptance check:** Two identical executions lead to the same final flight state as one execution; show the behavior through client–server communication.
- **Documentation:** Describe the chosen operation and why it is idempotent in the lab report.

### FR-06 — Additional Non-Idempotent Operation (Team-Defined)

- **Assignment requirement:** Implement **one additional operation on flights** for which repeated execution with the same arguments can change the resulting state again.
- **Team proposal — NOT YET APPROVED:** `ADD_SEATS(flightId, amount)`. Each execution adds the specified number of available seats.
- **Acceptance check:** Two identical executions can produce a different final count from one execution. Use this operation in invocation-semantics experiments to demonstrate the risk of duplicate execution.
- **Documentation:** Describe the chosen operation and why it is non-idempotent in the lab report.

> **Decision needed:** B, C, and D must confirm the exact two additional operations before the operation codes and wire format are finalized. The two examples above are recommendations from the team plan, **not specified by the professor**.

## 3. Technical and System Requirements

### TR-01 — UDP Communication

- Implement both a server and a client using **UDP socket programming**.
- Do **not** replace UDP with TCP.
- The client can be given the server IP address and port number when launched.
- The server learns each client's address from the received request; it does not need to know client addresses beforehand.
- The system must support clients running on different computers.

### TR-02 — Custom Request/Reply Protocol

- Design request and reply message structures, including operation-specific arguments and results.
- All transmitted messages must be **sequences of bytes**.
- Include callback and error replies in the team-defined message protocol.
- Document the complete message formats in the lab report.
- **Step 2 deliverable:** `protocol.md` must define message types, operation codes, fields, field sizes, serialization rules, and error handling before client/server implementations diverge.

### TR-03 — Manual Marshalling / Unmarshalling

- Implement message encoding and decoding **ourselves** for integers, floating-point numbers, strings, and other transmitted values.
- Handle variable-length strings explicitly (e.g., length-prefixed encoding).
- Define and consistently use an agreed byte order and encoding format.
- **Prohibited by the assignment:** existing RMI, RPC, CORBA, Java object serialization facilities, and Java input/output stream classes.

### TR-04 — Two Remote-Invocation Semantics

Implement **both** of the following configurations:

1. **At-least-once:** A request may be retransmitted after timeout and consequently may be executed more than once.
2. **At-most-once:** Duplicate requests must not cause the same operation to execute more than once (e.g., by duplicate detection and a request/reply history).

Requirements for the reliability design:

- Implement relevant timeout and retransmission behavior.
- Provide duplicate-request filtering and historical tracking as needed for at-most-once behavior.
- Make the chosen invocation semantics selectable when running the system; the course brief suggests a client/server command-line argument as one possible approach.
- Clearly describe the implementation and limitations in the report.
- **Team design proposal:** Use client identifiers together with request identifiers to distinguish retries from new requests. Exact fields and history behavior will be fixed in `protocol.md`.

### TR-05 — Request-Loss and Reply-Loss Simulation

- Simulate **both request-message loss and reply-message loss** inside the program.
- Design experiments comparing the two invocation semantics under these faults.
- Demonstrate that **at-least-once can produce an incorrect result for a non-idempotent operation** due to duplicate execution, whereas **at-most-once prevents the duplicate side effect**.
- Record the setup, steps, observed client/server outputs, and resulting flight state; discuss actual results in the report.
- **Important distinction:** At-most-once prevents duplicate execution; it does not promise a reply or eventual execution if all messages are lost.

### TR-06 — Console Interaction and Server Logging

- The client repeatedly presents a way to enter requests, sends them, and prints each result or error on screen.
- The client provides an **Exit** option.
- The server repeatedly receives requests, performs operations, and prints received requests and generated replies.
- A text-based console interface is sufficient; **GUI is not required**.

### TR-07 — Concurrency and Permitted Simplifications

- Multiple clients may run concurrently on different machines.
- Multiple monitoring registrations must work concurrently.
- The assignment allows requests to be sufficiently separated in time so that the server finishes processing one request before receiving the next. **A thread per server request is not required**.
- A monitoring client may block further user input until its monitoring interval expires. **A thread per client is not required**.
- These simplifications must **not** prevent the required multi-client callback demonstration.

### TR-08 — Programming Languages

- C, C++, Java, or any other language supporting inter-process communication is acceptable.
- Using **different languages** for the client and server is **optional**.
- **Current team proposal:** Java for both client and server, subject to group agreement.

## 4. Integration Requirements

The team should verify these end-to-end paths, rather than relying only on individual module tests:

1. Client request → UDP datagram → server decoding → flight operation → reply encoding → client output.
2. Reservation → updated server seat count → reply → subsequent flight-details query.
3. Client A monitors → Client B reserves → server callback → Client A receives updated availability.
4. Two clients monitor the same flight and both receive callback messages.
5. Request-loss and reply-loss scenarios → timeout/retry behavior → observable differences between at-least-once and at-most-once.
6. Client and server communicate on **different physical computers** for the demonstration.

## 5. Team Responsibilities (Proposed Allocation — Confirm with Group)

| Role | Primary responsibilities | Main outputs / dependencies |
|---|---|---|
| **A — Requirements & Coordination** | Requirements, message-protocol design, interface alignment, integration coordination, report structure/consolidation | `requirements.md`; `protocol.md` specification; report; integration checklist. Confirm B/C/D feedback before freezing the protocol. |
| **B — Server** | Flight data; search/details/reservation; the two additional operations; monitoring registrations and triggering callbacks with C | Server code and server-side test evidence. Depends on A's protocol and D's codec/reliability interfaces. |
| **C — Client** | Console menu; requests/replies; client monitoring and callback receiving | Client code and screenshots/logs. Depends on A's protocol and D's codec/reliability interfaces. |
| **D — Serialization & Reliability** | Shared marshalling/unmarshalling implementation; request IDs, retries, at-least-once/at-most-once behavior; fault simulation and experiments | Shared protocol/reliability code; fault-experiment results and logs. Must integrate early with B/C, not at the end. |

**Shared responsibilities:** Resolve interfaces together, review each other's code, run integration tests, prepare the live demonstration, and confirm each member's actual contribution for the report cover.

> This is the **proposed working allocation**, not a professor-prescribed breakdown. Confirm the roles with all members. The assignment requires clear responsibilities and contribution percentages, but does not prescribe these four roles.

## 6. Lab Report Requirements

**Required by the assignment:** Submit a lab report explaining the design and implementation strategies; describe custom request/reply structures, the two team-defined operations, and the invocation-semantics fault experiments with discussion of results.

- **Cover page:** Include every member's name **as printed on the matriculation card** and the **percentage of work** contributed by each member.
- **Suggested report structure (team organization, not a professor-provided template):**
  1. Project Introduction and Architecture
  2. Flight Data and Operations
  3. Message Protocol and Marshalling / Unmarshalling
  4. Client, Server, and Monitoring Implementation
  5. Invocation Semantics and Fault-Tolerance Design
  6. Experiments, Results, and Discussion
  7. Conclusion
- Record **actual experiment results**, not expected outputs presented as observations.

## 7. Submission and Demonstration

| Item | Requirement |
|---|---|
| **Submission deadline** | **Wednesday, 14 October 2026**, before midnight on the deadline date; **late submissions are not accepted**. |
| **Source code** | Submit well-commented source code electronically through the NTULearn assignment module. Package all source programs into **one file**; only **one representative per group** submits it. The assignment states that resubmission is **not allowed**, so upload the final version only. |
| **Lab report** | One report per group, submitted electronically through the NTULearn assignment module. |
| **Live demonstration** | During class on **Saturday, 17 October 2026**; detailed timing to be arranged later. |
| **Demo computers** | Server and clients run on different computers. Use **one server plus two clients on two different computers**: one reserves seats and the other monitors seat availability to demonstrate callbacks. |
| **Academic integrity** | Copying others' programs or providing programs for copying is prohibited; the brief warns this results in **zero marks for the course project**. |

**Submission clarification for A:** The brief says the code assignment accepts only one file and also requires a report in NTULearn. It does **not** explicitly explain whether the report and code have separate submission slots. Verify the actual NTULearn assignment page rather than assuming one specific upload arrangement.

## 8. Minimum Acceptance Checklist

- [ ] UDP client and UDP server run and communicate on different computers.
- [ ] Each flight stores all six required fields in server memory.
- [ ] FR-01: Route query returns all matching IDs; missing route returns an error.
- [ ] FR-02: ID query returns time, price, seats; missing ID returns an error.
- [ ] FR-03: Successful reservations update seats; invalid/insufficient requests return errors.
- [ ] FR-04: Monitoring callbacks arrive after reservations; two monitors work concurrently; expired monitoring registrations stop receiving updates.
- [ ] FR-05: One additional idempotent operation is implemented and demonstrated.
- [ ] FR-06: One additional non-idempotent operation is implemented and demonstrated.
- [ ] Request/reply/callback packets are manually marshalled into and unmarshalled from byte sequences.
- [ ] Both at-least-once and at-most-once semantics can be demonstrated.
- [ ] Both request loss and reply loss are simulated.
- [ ] Experiments show the non-idempotent duplicate-execution problem under at-least-once, and prevention of duplicate side effects under at-most-once.
- [ ] Client has repeated console menu, visible replies/errors, and an Exit option.
- [ ] Server displays incoming requests and its replies.
- [ ] Report describes design, protocol, operations, fault tests and observed results; cover lists names and contribution percentages.
- [ ] Final source package and lab report are submitted correctly to NTULearn by the deadline; demo is prepared for 17 October.

## 9. Decisions to Confirm Before Implementation (A Coordinates)

| Decision | Status | People to agree |
|---|---|---|
| Programming language (proposed: Java) | **Pending** | A/B/C/D |
| Two additional operations (proposed: `SET_AIRFARE`, `ADD_SEATS`) | **Pending** | A/B/C/D |
| Shared message format: operation codes, field lengths, byte order, string encoding, status codes | **Step 2: pending** | A leads; B/C/D review |
| Request/client identifiers, reply cache behavior, timeout/retry rules, fault simulation switches | **Step 2: pending** | A/D, with B/C |
| Monitoring interval units and timeout/expiry implementation | **Step 2: pending** | A/B/C |
| Build/run commands and test setup for multiple computers | **Pending** | B/C/D |
| NTULearn report vs. code upload arrangement | **Check course site** | A |

**Next action:** Agree on the pending decisions and publish `protocol.md` immediately, so Server, Client, and Reliability work against a single documented interface.
