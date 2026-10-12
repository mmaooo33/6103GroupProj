import java.util.Arrays;

/** Common-header codec. Operation payloads are interpreted by their handlers. */
public final class MessageCodec {
    private MessageCodec() { }

    public static byte[] encode(Message message) {
        if (message == null) throw new IllegalArgumentException("Null message");
        validate(message);
        PayloadWriter writer = new PayloadWriter();
        writer.writeUnsignedByte('F');
        writer.writeUnsignedByte('I');
        writer.writeUnsignedByte(Protocol.VERSION);
        writer.writeUnsignedByte(message.messageType);
        writer.writeLong(message.clientId);
        writer.writeUnsignedInt(message.requestId);
        writer.writeUnsignedByte(message.operationCode);
        writer.writeUnsignedByte(message.statusCode);
        writer.writeUnsignedShort(message.payload.length);
        writer.writeBytes(message.payload);
        return writer.toByteArray();
    }

    public static Message decode(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < Protocol.HEADER_LENGTH
                || length > Protocol.MAX_DATAGRAM_LENGTH || offset > data.length
                || length > data.length - offset)
            throw new IllegalArgumentException("Invalid datagram range/length");
        PayloadReader reader = new PayloadReader(Arrays.copyOfRange(data, offset, offset + length));
        if (reader.readUnsignedByte() != 'F' || reader.readUnsignedByte() != 'I')
            throw new IllegalArgumentException("Invalid magic");
        if (reader.readUnsignedByte() != Protocol.VERSION)
            throw new IllegalArgumentException("Unsupported version");
        int type = reader.readUnsignedByte();
        long clientId = reader.readLong();
        long requestId = reader.readUnsignedInt();
        int operation = reader.readUnsignedByte();
        int status = reader.readUnsignedByte();
        int payloadLength = reader.readUnsignedShort();
        if (payloadLength != length - Protocol.HEADER_LENGTH)
            throw new IllegalArgumentException("Payload length mismatch");
        Message message = new Message(type, clientId, requestId, operation, status,
                reader.readBytes(payloadLength));
        reader.requireEnd();
        validate(message);
        return message;
    }

    private static void validate(Message m) {
        if (m.messageType < Protocol.REQUEST || m.messageType > Protocol.CALLBACK
                || m.requestId < 0 || m.requestId > Protocol.MAX_REQUEST_ID
                || m.operationCode < 0 || m.operationCode > 255
                || m.statusCode < 0 || m.statusCode > Protocol.PROTOCOL_ERROR
                || m.payload.length > Protocol.MAX_PAYLOAD_LENGTH)
            throw new IllegalArgumentException("Invalid message header/size");
        if (m.messageType == Protocol.CALLBACK) {
            if (m.requestId != 0 || m.operationCode != Protocol.MONITOR_FLIGHT
                    || m.statusCode != Protocol.OK || m.payload.length != 8)
                throw new IllegalArgumentException("Invalid callback header/payload");
        } else if (m.requestId == 0 || (m.messageType == Protocol.REQUEST && m.statusCode != 0)) {
            throw new IllegalArgumentException("Invalid request/reply header");
        }
        // Unknown u8 operation codes remain decodable so the server can return UNKNOWN_OPERATION.
    }
}
