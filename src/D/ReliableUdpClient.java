import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * D's transport for C's console UI. One outstanding invocation at a time.
 * Owns one socket for both replies and callbacks; do not receive from it elsewhere.
 * Both modes retry identical bytes; the configured SERVER implements deduplication.
 */
public final class ReliableUdpClient implements AutoCloseable {
    private final DatagramSocket socket;
    private final InetAddress server;
    private final int port;
    private final long clientId = new SecureRandom().nextLong();
    private long nextRequestId = 1;
    private final int timeoutMillis;
    private final int maxAttempts;
    private final FaultInjector requestLoss;
    private Consumer<Message> callbackHandler = message -> { };

    public ReliableUdpClient(InetAddress server, int port, InvocationSemantics semantics)
            throws IOException {
        this(server, port, semantics, 1000, 4, 0);
    }

    public ReliableUdpClient(InetAddress server, int port, InvocationSemantics semantics,
                             int timeoutMillis, int maxAttempts, int dropFirstRequestOperation)
            throws IOException {
        if (port < 1 || port > 65535 || timeoutMillis <= 0 || maxAttempts <= 0)
            throw new IllegalArgumentException("Invalid port/timeout/attempt count");
        this.server = Objects.requireNonNull(server);
        Objects.requireNonNull(semantics);
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        this.maxAttempts = maxAttempts;
        this.requestLoss = new FaultInjector(dropFirstRequestOperation);
        socket = new DatagramSocket();
        System.out.println("CLIENT id=" + clientId + " mode=" + semantics
                + " timeoutMs=" + timeoutMillis + " maxSends=" + maxAttempts);
    }

    public long getClientId() { return clientId; }

    /** Called also while invoke() awaits an acknowledgement; register this FIRST. */
    public void setCallbackHandler(Consumer<Message> handler) {
        callbackHandler = Objects.requireNonNull(handler);
    }

    public Message invoke(int operation, byte[] payload) throws IOException {
        if (nextRequestId > Protocol.MAX_REQUEST_ID)
            throw new IllegalStateException("Request IDs exhausted; create a new client");
        Message request = new Message(Protocol.REQUEST, clientId, nextRequestId,
                operation, Protocol.OK, payload);
        byte[] bytes = MessageCodec.encode(request);
        nextRequestId++; // Even a timed-out invocation consumes its ID.
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (requestLoss.shouldDrop(operation)) {
                System.out.println("SIMULATED REQUEST LOSS request=" + request.requestId);
            } else {
                socket.send(new DatagramPacket(bytes, bytes.length, server, port));
                System.out.println("SEND request=" + request.requestId + " operation=" + operation
                        + " attempt=" + attempt);
            }
            // Unrelated packets and callbacks must NOT reset this attempt's deadline.
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Message received = receiveUntil(deadline);
                if (received == null) continue;
                if (received.messageType == Protocol.CALLBACK) {
                    callbackHandler.accept(received);
                } else if (received.messageType == Protocol.REPLY
                        && received.requestId == request.requestId
                        && received.operationCode == operation) {
                    System.out.println("RECEIVED REPLY request=" + received.requestId
                            + " status=" + received.statusCode);
                    return received;
                }
            }
            System.out.println("TIMEOUT request=" + request.requestId
                    + (attempt < maxAttempts ? "; RETRY same bytes" : "; attempts exhausted"));
        }
        throw new SocketTimeoutException("No reply after " + maxAttempts
                + " attempts; operation outcome is unknown (request=" + request.requestId + ")");
    }

    /** Wait on the same socket after MONITOR_FLIGHT acknowledgement. */
    public void listenForCallbacks(long durationMillis) throws IOException {
        if (durationMillis < 0 || durationMillis > 0xffff_ffffL * 1000)
            throw new IllegalArgumentException("Invalid monitoring duration");
        long deadline = System.nanoTime() + durationMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Message received = receiveUntil(deadline);
            if (received != null && received.messageType == Protocol.CALLBACK)
                callbackHandler.accept(received);
        }
    }

    private Message receiveUntil(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return null;
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
                Math.max(1, (remaining + 999_999) / 1_000_000)));
        // Full UDP buffer ensures an oversized packet cannot be silently truncated into validity.
        byte[] buffer = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            return null;
        }
        if (!server.equals(packet.getAddress()) || port != packet.getPort()) return null;
        try {
            Message message = MessageCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
            if (message.clientId != clientId) return null;
            validateResponse(message);
            return message;
        } catch (IllegalArgumentException e) {
            System.out.println("IGNORE malformed response: " + e.getMessage());
            return null;
        }
    }

    private static void validateResponse(Message message) {
        PayloadReader reader = new PayloadReader(message.payload);
        if (message.messageType == Protocol.CALLBACK) {
            reader.readInt();
            nonNegative(reader.readInt());
        } else if (message.messageType != Protocol.REPLY) {
            throw new IllegalArgumentException("Not a response");
        } else if (message.statusCode != Protocol.OK) {
            reader.readString();
        } else {
            switch (message.operationCode) {
                case Protocol.SEARCH_FLIGHTS:
                    int count = reader.readUnsignedShort();
                    for (int i = 0; i < count; i++) reader.readInt();
                    break;
                case Protocol.GET_FLIGHT_DETAILS:
                    int year = reader.readUnsignedShort();
                    if (year == 0) throw new IllegalArgumentException("Invalid year");
                    try {
                        LocalDateTime.of(year, reader.readUnsignedByte(), reader.readUnsignedByte(),
                                reader.readUnsignedByte(), reader.readUnsignedByte());
                    } catch (DateTimeException e) {
                        throw new IllegalArgumentException("Invalid departure time", e);
                    }
                    validAirfare(reader.readDouble());
                    nonNegative(reader.readInt());
                    break;
                case Protocol.RESERVE_SEATS:
                case Protocol.ADD_SEATS:
                    nonNegative(reader.readInt());
                    break;
                case Protocol.MONITOR_FLIGHT:
                    if (reader.readUnsignedInt() == 0) throw new IllegalArgumentException("Zero interval");
                    break;
                case Protocol.SET_AIRFARE:
                    validAirfare(reader.readDouble());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown successful reply operation");
            }
        }
        reader.requireEnd();
    }

    private static void nonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative seats");
    }
    private static void validAirfare(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid airfare");
    }
    @Override public void close() { socket.close(); }
}
