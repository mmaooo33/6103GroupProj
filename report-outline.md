# SC6103 Course Project Report Outline

This file is a working outline. Replace every bracketed placeholder with the
group's real information and observed results before submission.

## Cover Page

- Project title: Design and Implementation of a Distributed Flight Information System
- Group members: [names exactly as on matriculation cards]
- Contribution percentages: [percentages totalling 100%]

## 1. Introduction and Architecture

Explain the UDP client-server architecture, in-memory server state and division
of responsibilities among the client, server, codec and reliability components.

## 2. Flight Data and Operations

Describe the six operations. Explain why `SET_AIRFARE` is idempotent and why
`ADD_SEATS` is non-idempotent.

## 3. Message Protocol and Marshalling

Summarize the 20-byte header, operation payloads, big-endian numeric encoding,
UTF-8 length-prefixed strings, error replies and callback layout. Refer to the
final `protocol.md` tables.

## 4. Client, Server and Monitoring Implementation

Describe the console client, server request loop, flight service, registration
replacement/expiration and reservation-triggered callbacks. Include the
different-computer demonstration setup.

## 5. Invocation Semantics and Fault Tolerance

Explain timeout/retry behavior, preservation of request bytes, the
`(clientId, requestId)` history key, reply caching before send/drop, and why
at-most-once suppresses duplicate mutations and callbacks.

## 6. Experiments and Observed Results

Record actual commands, initial state, client/server logs and final state for:

1. No loss under both modes.
2. First request lost under both modes.
3. First reply lost after `ADD_SEATS` executes under at-least-once.
4. First reply lost after `ADD_SEATS` executes under at-most-once.
5. Repeated `SET_AIRFARE` under reply loss.
6. One monitor, two monitors, and an expired monitor.

Do not present expected values as observations. Attach screenshots or copied
logs from the runs actually performed by the group.

## 7. Limitations

Discuss UDP callback delivery, bounded client retries, in-memory reply history,
loss of history after server restart, and the single-threaded server assumption.

## 8. Conclusion

Summarize whether all required functions and experiments were demonstrated.
