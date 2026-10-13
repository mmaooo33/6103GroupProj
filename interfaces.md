# Shared Java Interfaces

Implementation baseline for the Java 17 client and server. All source files
currently use the default package and must be compiled together. The byte-level
wire format is defined in `protocol.md`.

## Protocol model and codec

### `Message`

Immutable message value with these public final fields:

```java
int messageType;
long clientId;
long requestId;
int operationCode;
int statusCode;
byte[] payload;
```

The constructor defensively copies `payload`.

### `MessageCodec`

```java
public static byte[] encode(Message message);
public static Message decode(byte[] data, int offset, int length);
```

The codec validates the 20-byte common header, protocol magic and version,
message type, payload length, request ID rules and callback layout.

### `PayloadWriter`

```java
public void writeUnsignedByte(int value);
public void writeUnsignedShort(int value);
public void writeInt(int value);
public void writeUnsignedInt(long value);
public void writeDouble(double value);
public void writeString(String value);
public byte[] toByteArray();
```

### `PayloadReader`

```java
public int readUnsignedByte();
public int readUnsignedShort();
public int readInt();
public long readUnsignedInt();
public double readDouble();
public String readString();
public void requireEnd();
```

All numeric values use big-endian byte order. Strings use a two-byte unsigned
UTF-8 byte-length prefix. `requireEnd()` rejects unexpected trailing data.

## Protocol constants

`Protocol` defines:

- message types: `REQUEST`, `REPLY`, `CALLBACK`;
- operations: `SEARCH_FLIGHTS`, `GET_FLIGHT_DETAILS`, `RESERVE_SEATS`,
  `MONITOR_FLIGHT`, `SET_AIRFARE`, `ADD_SEATS`;
- status codes from `OK` through `PROTOCOL_ERROR`;
- header, payload and UDP size limits.

## Client interface

`FlightClient` owns the console menu and operation-specific payload/result
handling. It delegates transport to one `ReliableUdpClient` instance.

Important `ReliableUdpClient` methods:

```java
public Message invoke(int operation, byte[] payload) throws IOException;
public void setCallbackHandler(Consumer<Message> handler);
public void listenForCallbacks(long durationMillis) throws IOException;
```

The transport owns one UDP socket for requests, replies and callbacks. Client
code must not create another receiver for that socket. Retries reuse the exact
same encoded request and therefore preserve `clientId` and `requestId`.

## Server interface

`FlightServer` is the single authoritative server implementation. It combines:

- `FlightService` for all six operations;
- `MonitorRegistry` for callback registrations and expiration;
- `ReplyHistory` for at-most-once duplicate suppression;
- `FaultInjector` for deterministic first-request/first-reply loss experiments;
- `MessageCodec` and payload readers/writers for the shared wire format.

The compatibility launcher `DFlightServer` delegates to `FlightServer` and
contains no separate server logic.

## Invocation semantics

Both client and server are started with the same mode:

```text
at-least-once
at-most-once
```

At-least-once executes every delivered retry. At-most-once checks
`ReplyHistory` using `(clientId, requestId)`, caches the encoded reply before
reply-loss simulation, and replays that reply without executing the operation
or callback again.

## Integration rules

1. Change the protocol only in coordination with client, server and codec code.
2. Do not implement a second serializer in the client or server.
3. A successful reservation triggers callbacks; `SET_AIRFARE` and `ADD_SEATS`
   do not.
4. Cache success and error replies in at-most-once mode.
5. Run `ProjectSelfTest`, then the end-to-end and multi-computer tests described
   in `README.md` before submission.
