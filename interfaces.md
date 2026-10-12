\# Shared Java Interfaces — Proposal v1



\## General rules



\- Java 17.

\- All Java source files are placed in src/.

\- No package declaration for this project version.

\- Wire format follows protocol.md.

\- Member D implements the shared classes below.

\- Members B and C use the same shared implementation.

\- Malformed data or invalid encoding arguments throw

&#x20; IllegalArgumentException.

\- Decoding must validate lengths before reading data.



\## 1. Message.java



Public final fields:



&#x20;   int messageType

&#x20;   long clientId

&#x20;   long requestId

&#x20;   int operationCode

&#x20;   int statusCode

&#x20;   byte\[] payload



Constructor:



&#x20;   public Message(int messageType, long clientId, long requestId,

&#x20;                  int operationCode, int statusCode, byte\[] payload)



requestId uses long to represent unsigned 32-bit values:

0 to 4294967295.



\## 2. MessageCodec.java



&#x20;   public static byte\[] encode(Message message)



&#x20;   public static Message decode(byte\[] data, int offset, int length)



encode returns the complete message: header plus payload.



decode reads only the supplied offset/length range, validates

the header and payload length, and returns a Message.



\## 3. PayloadReader.java



Constructor:



&#x20;   public PayloadReader(byte\[] payload)



Methods:



&#x20;   public int readUnsignedByte()

&#x20;   public int readUnsignedShort()

&#x20;   public int readInt()

&#x20;   public long readUnsignedInt()

&#x20;   public double readDouble()

&#x20;   public String readString()

&#x20;   public void requireEnd()



Reads advance an internal position.

All numeric values use big-endian byte order.

Strings use a u16 UTF-8 byte-length prefix.

requireEnd throws if unread bytes remain.



Departure time is read in this order:

unsigned short year, unsigned byte month, day, hour, minute.



\## 4. PayloadWriter.java



Constructor:



&#x20;   public PayloadWriter()



Methods:



&#x20;   public void writeUnsignedByte(int value)

&#x20;   public void writeUnsignedShort(int value)

&#x20;   public void writeInt(int value)

&#x20;   public void writeUnsignedInt(long value)

&#x20;   public void writeDouble(double value)

&#x20;   public void writeString(String value)

&#x20;   public byte\[] toByteArray()



Writes append values in call order.

All numeric values use big-endian byte order.

Strings use a u16 UTF-8 byte-length prefix.

Reject values outside the specified unsigned ranges.



Departure time is written in this order:

unsigned short year, unsigned byte month, day, hour, minute.



\## 5. Protocol.java



Public static final int constants:



&#x20;   REQUEST = 1

&#x20;   REPLY = 2

&#x20;   CALLBACK = 3



&#x20;   SEARCH\_FLIGHTS = 1

&#x20;   GET\_FLIGHT\_DETAILS = 2

&#x20;   RESERVE\_SEATS = 3

&#x20;   MONITOR\_FLIGHT = 4

&#x20;   SET\_AIRFARE = 5

&#x20;   ADD\_SEATS = 6



&#x20;   OK = 0

&#x20;   BAD\_REQUEST = 1

&#x20;   FLIGHT\_NOT\_FOUND = 2

&#x20;   ROUTE\_NOT\_FOUND = 3

&#x20;   INSUFFICIENT\_SEATS = 4

&#x20;   INVALID\_ARGUMENT = 5

&#x20;   UNKNOWN\_OPERATION = 6

&#x20;   INTERNAL\_ERROR = 7

&#x20;   PROTOCOL\_ERROR = 8



&#x20;   HEADER\_LENGTH = 20

&#x20;   VERSION = 1



\## 6. Reliability integration — B and D



D provides the reliability implementation.

B integrates it into the server receive loop.



In at-most-once mode:



1\. Check history using (clientId, requestId) before executing.

2\. If found, resend the cached reply without executing again.

3\. Otherwise, execute the operation and encode its reply.

4\. Cache the encoded reply before reply-loss simulation.

5\. Duplicate reservations must not trigger another callback.



In at-least-once mode:

Execute each received request, including retries.



The exact reliability class and method signatures still need

to be agreed by B and D.



\## Confirmation



B, C and D should confirm this proposal before relying on it.

Any interface change must be communicated to both B and C.

