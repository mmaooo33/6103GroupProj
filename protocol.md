# SC6103 Distributed Flight Information System — UDP Message Protocol

**Protocol version:** 1.0 (team proposal — review with B/C/D before implementation)  
**Date:** 12 October 2026  
**Language:** Java (client and server)  
**Transport:** UDP (`java.net.DatagramSocket` / `DatagramPacket`)  
**Owner:** Member A (protocol design); Member D (codec and reliability implementation); B/C (server/client integration)  
**Assignment source:** *SC6103 Course Project for 2026–2027 Trimester 1*, pp. 1–4; see `requirements.md`.

> **Scope of this document.** The assignment **requires** UDP, custom request/reply messages, manual marshalling/unmarshalling, callbacks, at-least-once and at-most-once semantics, and loss experiments. The exact operation codes, field sizes, time format, message layout, timeout values, and error codes **below are our team's proposed choices**, not professor-specified values. Agree on them once, then implement consistently on both sides.

## 1. System architecture

```text
Client A (Java console) -- UDP request --> Server (Java / flight data in memory)
Client A (Java console) <-- UDP reply ----- Server
Client B (monitoring)  <-- UDP callback -- Server (on successful reservation)
```

- Clients know the server IP address and UDP port from command-line arguments. The server learns the client's IP address and UDP port from a received `DatagramPacket`.
- One client uses the **same UDP socket** for requests, replies, and callbacks. Keep this socket open while monitoring.
- The server accepts requests from multiple clients. It handles one request at a time; **no thread per request** is necessary under the assignment's simplified timing assumption.
- The client can use a console menu, and may block further user input while waiting during a monitoring interval. A GUI is not required.
- The server stores each flight's ID, source, destination, departure time, airfare, and available seats in memory. Disk persistence is optional, not required.

## 2. Message format (binary, version 1)

**Every UDP datagram is one complete message:** a 20-byte header followed by a variable-length payload. There is no Java object serialization, `DataInputStream`, `DataOutputStream`, or any other Java I/O stream-based message encoding.

### 2.1 Common header: exactly 20 bytes

All multi-byte numeric fields use **network byte order (big-endian)**.

| Offset (bytes) | Size | Field | Encoding and meaning |
|---|---:|---|---|
| 0–1 | 2 | `magic` | Literal ASCII bytes `F`, `I` (`0x46 0x49`); rejects unrelated packets |
| 2 | 1 | `version` | Unsigned byte; `1` |
| 3 | 1 | `messageType` | Unsigned byte: `1=REQUEST`, `2=REPLY`, `3=CALLBACK` |
| 4–11 | 8 | `clientId` | 64-bit identifier (random bit pattern generated once when a client starts) |
| 12–15 | 4 | `requestId` | Unsigned 32-bit value, increments for **new** client requests; retries reuse the **same** value |
| 16 | 1 | `operationCode` | Unsigned byte; see Section 3 |
| 17 | 1 | `statusCode` | Unsigned byte; `0` in requests/callbacks; replies use Section 4 |
| 18–19 | 2 | `payloadLength` | Unsigned 16-bit number of bytes following the header |
| 20 onward | Variable | `payload` | Bytes defined by operation + message type |

**Validation:** Packet length must equal `20 + payloadLength`; reject invalid magic, version, type, truncated fields, extra bytes, or malformed payload. Never trust a length unless enough bytes remain in the received datagram. The maximum UDP datagram size and practical packet-size considerations still apply; use a small demonstration flight dataset.

**Identifiers:**
- A Java `long` holds the `clientId` bit pattern; generate it once per client process (for example, with `SecureRandom`). The pair **(`clientId`, `requestId`)** identifies a logical request.
- `requestId` begins at 1, increments **only for a new menu action**, and is unchanged for retries. Stop or regenerate a new `clientId` before the 32-bit request counter wraps.
- A `REPLY` echoes the request's `clientId`, `requestId`, and `operationCode`, so the client can ignore unrelated or stale packets.
- A `CALLBACK` uses the monitoring client's `clientId`, `operationCode=4`, `requestId=0`, `statusCode=0`. The callback has **no reply requirement** in this team design.

### 2.2 Primitive value encoding

| Type | Number of bytes | Encoding |
|---|---:|---|
| `u8` | 1 | Unsigned integer, range 0–255 |
| `u16` | 2 | Unsigned integer, big-endian |
| `u32` | 4 | Unsigned integer, big-endian (use a Java `long` if needed for its full unsigned range) |
| `i32` | 4 | Signed integer, two's-complement, big-endian |
| `u64` / identifier | 8 | 64 raw bits, big-endian; represented with Java `long` |
| `f64` | 8 | IEEE-754 double bits, big-endian; use `Double.doubleToLongBits()` and `Double.longBitsToDouble()` |
| `string` | `2 + N` | `u16` UTF-8 byte length `N`, followed by exactly `N` UTF-8 bytes. No null terminator. |
| `DepartureTime` | 6 | `u16 year`, `u8 month`, `u8 day`, `u8 hour`, `u8 minute` in **UTC** |

**Manual codec rule (D):** Encode/decode integers using byte-array indexing, shifts, masks (`& 0xff`) and big-endian ordering. For double, manually encode its 64-bit IEEE-754 bits into 8 bytes. `String.getBytes(StandardCharsets.UTF_8)` / `new String(bytes, StandardCharsets.UTF_8)` may be used to handle text, but D must explicitly write/read the UTF-8 **byte-length prefix**. Do not use Java `ObjectInputStream` / `ObjectOutputStream`, `DataInputStream` / `DataOutputStream`, RMI, RPC, CORBA, or object serialization. `ByteBuffer` is not needed for this simple manual codec.

**Validation:** Reject strings with invalid length/truncated bytes; reject invalid dates/times, non-finite or negative airfares, non-positive seat requests, negative seat increases, and integer overflow. These validation choices are team design decisions.

### 2.3 Byte-level example: `GET_FLIGHT_DETAILS(101)`

For an illustrative client ID `0x1122334455667788` and request ID `1`, the **complete 24-byte request** is:

```text
46 49 | 01 | 01 | 11 22 33 44 55 66 77 88 | 00 00 00 01 | 02 | 00 | 00 04 | 00 00 00 65
magic |ver |req |          clientId           |   requestId   | op |status| length|  flightId=101
```

The `payloadLength` is `4` bytes because the operation-2 request contains one `i32 flightId`. In this example, the 4-byte integer `101` is `00 00 00 65` in big-endian order.

## 3. Operation codes and payload schemas

`REQUEST` and successful `REPLY` payloads are listed below, **in exact byte order**. All field encodings follow Section 2.2. In the case of errors, use the standard error payload in Section 4 instead of the success payload.

| Op code | Name | REQUEST payload | Successful REPLY payload |
|---:|---|---|---|
| **1** | `SEARCH_FLIGHTS` | `string source` + `string destination` | `u16 count` + `count × i32 flightId` (all matching IDs, ascending) |
| **2** | `GET_FLIGHT_DETAILS` | `i32 flightId` | `DepartureTime departure` + `f64 airfare` + `i32 availableSeats` |
| **3** | `RESERVE_SEATS` | `i32 flightId` + `i32 seatCount` | `i32 remainingSeats` |
| **4** | `MONITOR_FLIGHT` | `i32 flightId` + `u32 intervalSeconds` | `u32 acceptedIntervalSeconds` |
| **5** | `SET_AIRFARE` | `i32 flightId` + `f64 newAirfare` | `f64 currentAirfare` |
| **6** | `ADD_SEATS` | `i32 flightId` + `i32 amountToAdd` | `i32 remainingSeats` |

**Important: Operations 5 and 6 are team proposals and must be explicitly approved by B/C/D.**

### 3.1 Required business rules

1. **Search:** Return **every** matching ID; if none match, return `ROUTE_NOT_FOUND`. Define route matching as trimmed, case-insensitive exact match (**team choice**, applied consistently). The reply list is sorted to make test output predictable.
2. **Details:** Return time, airfare and available seats; unknown ID yields `FLIGHT_NOT_FOUND`.
3. **Reserve:** Only a positive `seatCount` is valid. When enough seats exist, update server state **once per execution**, reply with remaining seats, and invoke callbacks for active monitors of that flight. Never update seats when the request is invalid or insufficient seats remain.
4. **Monitor:** Register (`clientId`, flightId, source IP, source UDP port, server-side expiry). A monitoring interval is measured in **seconds** by the server from accepted registration. Use `intervalSeconds > 0`; 10–30 seconds is convenient for demos. The client may wait for notifications without showing a menu until the interval completes. Allow **multiple different clients** to monitor the same flight simultaneously. A new logical registration from the same client for the same flight may replace its previous expiry (team choice).
5. **Set airfare (idempotent):** Set a non-negative finite airfare to the given value; invoking it repeatedly has the **same final price**.
6. **Add seats (non-idempotent):** Increase available seats by a strictly positive amount; invoking it twice increases seats **twice**, making it suitable for the invocation-semantics comparison.

### 3.2 Callback message (not a reply)

When a **successful `RESERVE_SEATS` execution** changes a flight's seat availability, the server immediately sends **one UDP `CALLBACK` datagram per active monitor** of that flight:

| Field | Value |
|---|---|
| Header `messageType` | `3` (`CALLBACK`) |
| Header `clientId` | ID of the monitoring client |
| Header `requestId` | `0` |
| Header `operationCode` | `4` (`MONITOR_FLIGHT`) |
| Header `statusCode` | `0` |
| Payload | `i32 flightId` + `i32 availableSeats` (**8 bytes**) |

- The callback is sent to the **source IP/port recorded at registration**, **not** to an IP/port invented from a message payload.
- The server deletes expired monitoring registrations; no callback is sent after their server-side expiry. A periodic socket timeout (e.g. `setSoTimeout(500)`) allows expiration cleanup even without new incoming requests.
- Callbacks occur for **successful seat reservations**, which is what the assignment specifies. **`ADD_SEATS` and `SET_AIRFARE` do not generate monitoring callbacks** in this protocol.
- Because UDP is unreliable, sending each callback does not guarantee delivery. Log callback attempts and test on the same network; guaranteed callback delivery is **not specified** by the assignment.
- The client must recognize `CALLBACK` separately from `REPLY`; only a valid `REPLY` with matching IDs completes a request.

## 4. Status codes and errors

All successful replies carry `statusCode=0` and the operation-specific success payload. Errors have `messageType=2` and a **standard error payload:** `string errorMessage` (UTF-8, length-prefixed as in Section 2.2).

| Status code | Name | Typical use |
|---:|---|---|
| 0 | `OK` | Success |
| 1 | `BAD_REQUEST` | Malformed or incomplete operation payload |
| 2 | `FLIGHT_NOT_FOUND` | Invalid flight ID |
| 3 | `ROUTE_NOT_FOUND` | No matching source/destination route |
| 4 | `INSUFFICIENT_SEATS` | Reservation exceeds availability |
| 5 | `INVALID_ARGUMENT` | Zero/negative seat count or interval; invalid amount/price/date |
| 6 | `UNKNOWN_OPERATION` | Unsupported operation code |
| 7 | `INTERNAL_ERROR` | Unexpected server failure |
| 8 | `PROTOCOL_ERROR` | Invalid magic/version/header/length (if enough header information exists to reply) |

**Example:** `RESERVE_SEATS(flightId=101, seatCount=50)` with only 20 seats left → `REPLY`, `op=3`, `status=4`, payload=`string "Not enough seats"`. The client prints the error. Valid but nonexistent IDs return `FLIGHT_NOT_FOUND`.

If the common header is too broken to identify a legitimate client/request safely, the server may log and discard the datagram instead of attempting a reply.

## 5. Invocation semantics and duplicate handling

Both modes are **mandatory** in the assignment and must be selectable when starting the program. The options below are a proposed CLI convention; B/C/D can adjust the spelling, but **must agree on compatible behavior**.

### 5.1 Client behavior (member C with D)

- Client builds one encoded request with (`clientId`, `requestId`) and remembers its **exact packet bytes**.
- Send packet; wait up to **1,000 ms** (team-default timeout) for a matching `REPLY`. Ignore unrelated/stale reply packets; handle callbacks separately.
- On timeout, resend the **exact same packet**, preserving the identifiers and arguments.
- Try up to **4 sends total** (1 initial send + 3 retries); then print a timeout/error and return to the menu. This is a **team-chosen bound**, not a professor requirement.
- For monitoring, first receive the `MONITOR_FLIGHT` acknowledgement, then listen for callbacks until the monitoring period ends. Set a short socket timeout and check the deadline repeatedly; do not confuse a monitoring callback with the registration reply.

### 5.2 At-least-once server (member B with D)

- Each successfully delivered request is **executed independently**, including retries with the same (`clientId`, `requestId`).
- No duplicate filtering prevents re-execution. If the reply is lost, the retry may re-execute `ADD_SEATS` or `RESERVE_SEATS` and change seats again.
- This illustrates **at-least-once attempts** under retry; successful eventual execution is conditional on some datagrams being delivered before the retry limit.

### 5.3 At-most-once server (member B with D)

Maintain a server-side **reply history** keyed by the pair (`clientId`, `requestId`).

```text
On request arrival:
  if key exists in replyHistory:
      do NOT execute the flight operation again
      re-send the saved encoded reply bytes
  else:
      validate and execute the operation at most once
      create the encoded success/error reply
      SAVE THE REPLY TO HISTORY BEFORE ANY SEND/DROP SIMULATION
      send the reply (unless fault injection drops this attempt)
```

- Cache replies for **all operations**, including errors, not only mutations.
- Keep history for the full duration of the demonstration/server process (small project dataset), so retries cannot bypass duplicate detection through early eviction.
- The key must contain **both** IDs. Two different clients may both issue `requestId=1`.
- Suppressing duplicate execution must also suppress duplicate **reservation callbacks** under at-most-once mode.
- This is **at-most-once execution, not exactly-once delivery**. A lost request may never run if all retry attempts are lost; a lost reply may still leave the client uncertain. In-memory reply history does not survive server restart; document that limitation.

### 5.4 Mode selection (proposal)

```text
java Server <port> <at-least-once|at-most-once> [fault options]
java Client <serverIp> <serverPort> <at-least-once|at-most-once> [fault options]
```

Ensure both sides use the same selected mode in each experiment. The mode is a **process configuration**, not a new header field.

## 6. Request/reply loss simulation and experiments

Fault injection must be deterministic and visible in server/client logs; do not rely on random network errors. This section defines **suggested switches**, not assignment-prescribed flag names:

- **Request loss (client-side):** `--drop-first-request-op=6` discards the first outgoing `ADD_SEATS` request *before* the socket send (one time only). The client then times out and retries the same bytes.
- **Reply loss (server-side):** `--drop-first-reply-op=6` fully **executes** the first `ADD_SEATS` request, constructs and (in at-most-once mode) **caches** its reply, then intentionally skips sending that first reply. The client times out and retransmits.
- Do **not** intentionally drop all retries. Print explicit events such as `SIMULATED REQUEST LOSS`, `SIMULATED REPLY LOSS`, `TIMEOUT/RETRY`, `DUPLICATE DETECTED`, `REPLAY CACHED REPLY`.
- Reset flight data and restart server/client between comparisons to ensure identical starting conditions.

### 6.1 Mandatory comparison cases

| Case | Mode | Fault | Expected result for `ADD_SEATS(101, 10)` with 20 seats initially |
|---|---|---|---|
| A | At-least-once | Drop first request | Request reaches server on retry: final seats **30** |
| B | At-most-once | Drop first request | Request reaches server on retry: final seats **30** |
| C | At-least-once | Drop first **reply after execution** | Original operation adds 10, retry adds 10 again: final seats **40** |
| D | At-most-once | Drop first **reply after execution** | Retry hits history; operation is not repeated: final seats **30** |
| E | Both modes | No loss | One execution: final seats **30** |

These are **expected outcomes for a correctly implemented deterministic simulation**, *not actual experimental observations*. During testing, collect logs/screenshots and report the results you actually observe. Repeat the reply-loss case with `SET_AIRFARE` to illustrate why its final state remains unchanged on duplicate execution.

The professor requires showing that **at-least-once can be incorrect for non-idempotent operations while at-most-once prevents duplicate effects**, and requires both request- and reply-loss simulation.

## 7. Shared Java module interfaces (implementation guidance)

These are proposed **Java class responsibilities**, not compulsory names:

| Java file/class | Primary owner | Required interface/responsibility |
|---|---|---|
| `Protocol.java` | A design / D code | Constants: message types, operation/status codes, header length, version |
| `Message.java` | D | Message object: type, IDs, operation, status, payload bytes |
| `MessageCodec.java` | D | `byte[] encode(Message m)` and `Message decode(byte[] data, int offset, int length)`; manual byte encoding + bounds checks |
| `Flight.java` / `DepartureTime.java` | B | Server-side flight data and custom time representation |
| `FlightService.java` | B | Search, details, reserve, set airfare, add seats; validate inputs |
| `FlightServer.java` | B + D | UDP receive loop, routing, reply creation, duplicate history, monitoring registration/callback, reply-loss injection |
| `FlightClient.java` | C + D | Console menu, request/retry, timeout, monitoring callback reception, request-loss injection |
| `FaultExperiments.md` (or logs) | D | Actual tests, exact commands, logs and observed seat counts for report |
| `Report` | A, with B/C/D evidence | Architecture, protocol, implementation choices, experiments, real results and contribution percentages |

**Who calls the codec?** Both B's Server and C's Client call D's **same** `MessageCodec`. Do not write incompatible serialization logic independently in multiple locations. Agree on Java package names and any final API differences **today**.

### 7.1 Example in-memory server representation

```text
Flight:
  int flightId
  String source
  String destination
  DepartureTime departureTime  // year, month, day, hour, minute (UTC)
  double airfare
  int availableSeats

MonitoringRegistration:
  long clientId
  int flightId
  InetAddress address
  int port
  long expiryTimeMillis  // server-side deadline
```

The server should log every received request, produced reply, simulated drop, reservation update, and callback send, as the assignment requires console visibility of requests and replies.

## 8. Integration and acceptance checklist

### Day 1: Freeze design before parallel coding

- [ ] Confirm Java on both sides; exchange JDK version (e.g. JDK 17 or any version installed by all members).
- [ ] B/C/D approve operations **5=`SET_AIRFARE`** and **6=`ADD_SEATS`**, or change them **before coding**.
- [ ] Confirm this exact 20-byte header and payload schemas; freeze protocol version 1.0.
- [ ] Confirm Java classes/packages, fault flags, build/run commands, and who owns integration.

### Functional tests

- [ ] Route query returns multiple matching IDs; missing route returns meaningful error.
- [ ] Flight-details query returns valid departure time, airfare, and seats; unknown flight ID errors.
- [ ] Reservation reduces seats exactly as expected; insufficient/invalid requests do not change data.
- [ ] `SET_AIRFARE` updates airfare; repeated identical execution leaves same final price.
- [ ] `ADD_SEATS` updates availability; repeated identical execution changes seats again.
- [ ] One monitoring client receives callback after **another** client reserves seats.
- [ ] Two clients can monitor simultaneously and both receive notifications.
- [ ] Expired monitoring client is removed; no new callbacks after expiry.

### Protocol and reliability tests

- [ ] Correct encode/decode of `i32`, `u16`, `u32`, `u64`, `f64`, UTF-8 strings, and departure time.
- [ ] Non-ASCII and different-length route names round-trip correctly.
- [ ] Truncated or malformed messages are safely rejected; error replies are printed.
- [ ] Request loss is simulated and recovers by retry (if a later request arrives).
- [ ] Reply loss is simulated and re-execution differs between invocation modes.
- [ ] At-most-once caches the reply **before** dropping the first response; duplicates replay, without another mutation/callback.
- [ ] Final demo works with **server and clients on different physical computers**.

## 9. Report & submission reminders

The lab report must explain the selected message structures and byte encodings, design/implementation strategies, two extra operations and why one is idempotent, invocation semantics, experiments/results, and limitations. Its cover must show group members' matriculation-card names and percentage contributions. The **source code deadline is Wednesday, 14 October 2026, before midnight; no late submission or resubmission**. A live demo with **one server and two clients on different computers** is scheduled for **Saturday, 17 October 2026**.

> **A's next action:** Put `protocol.md` at the repository root, ask B/C/D to approve the marked team choices immediately, and tell D to implement/test `MessageCodec` **before** server/client teams build against inconsistent packet formats. Begin collecting integration and experiment evidence for the lab report.
