/** Shared message value. The constructor copies the payload supplied by the caller. */
public final class Message {
    public final int messageType;
    public final long clientId;
    public final long requestId;
    public final int operationCode;
    public final int statusCode;
    public final byte[] payload;

    public Message(int messageType, long clientId, long requestId,
                   int operationCode, int statusCode, byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("Null payload");
        this.messageType = messageType;
        this.clientId = clientId;
        this.requestId = requestId;
        this.operationCode = operationCode;
        this.statusCode = statusCode;
        this.payload = payload.clone();
    }
}
