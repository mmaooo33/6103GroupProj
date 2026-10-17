import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * UDP flight server.
 * Uses the shared codec interfaces defined in interfaces.md.
 *
 * The final server integrates the flight services, monitoring callbacks,
 * selectable invocation semantics, duplicate filtering and deterministic
 * reply-loss simulation.
 */
public class FlightServer {
    private final FlightService service = new FlightService();
    private final MonitorRegistry monitors = new MonitorRegistry();
    private final ReplyHistory history = new ReplyHistory();
    private final InvocationSemantics semantics;
    private final FaultInjector replyLoss;
    private final int perRequestReplyLossOperation;
    // Each logical request is identified by the pair (clientId, requestId).
    private record RequestKey(long clientId, long requestId) { }
    private final Set<RequestKey> repliesDroppedForRequests = new HashSet<>();

    public FlightServer() {
        this(InvocationSemantics.AT_MOST_ONCE, 0);
    }

    public FlightServer(InvocationSemantics semantics,
                        int dropFirstReplyOperation) {
        this(semantics, dropFirstReplyOperation, 0);
    }

    private FlightServer(InvocationSemantics semantics,
                         int dropFirstReplyOperation,
                         int perRequestReplyLossOperation) {
        this.semantics = java.util.Objects.requireNonNull(semantics);
        this.replyLoss = new FaultInjector(dropFirstReplyOperation);
        this.perRequestReplyLossOperation = perRequestReplyLossOperation;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            System.out.println("Usage: java FlightServer <port>"
                    + " [at-least-once|at-most-once]"
                    + " [--drop-first-reply-op=1..6|"
                    + "--drop-first-reply-per-request-op=1..6]");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Port must be between 1 and 65535");
            return;
        }

        InvocationSemantics mode;
        int dropFirstReplyOperation = 0;
        int perRequestReplyLossOperation = 0;
        try {
            mode = args.length >= 2
                    ? InvocationSemantics.parse(args[1])
                    : InvocationSemantics.AT_MOST_ONCE;
            if (args.length == 3) {
                String oncePrefix = "--drop-first-reply-op=";
                String perRequestPrefix = "--drop-first-reply-per-request-op=";
                if (args[2].startsWith(oncePrefix)) {
                    dropFirstReplyOperation = Integer.parseInt(
                            args[2].substring(oncePrefix.length()));
                    if (dropFirstReplyOperation < 1
                            || dropFirstReplyOperation > Protocol.ADD_SEATS) {
                        throw new IllegalArgumentException("Drop operation must be 1..6");
                    }
                } else if (args[2].startsWith(perRequestPrefix)) {
                    perRequestReplyLossOperation = Integer.parseInt(
                            args[2].substring(perRequestPrefix.length()));
                    if (perRequestReplyLossOperation < 1
                            || perRequestReplyLossOperation > Protocol.ADD_SEATS) {
                        throw new IllegalArgumentException("Drop operation must be 1..6");
                    }
                } else {
                    throw new IllegalArgumentException("Unknown option: " + args[2]);
                }
            }
        } catch (NumberFormatException e) {
            System.out.println("Drop operation must be an integer from 1 to 6");
            return;
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }

        new FlightServer(mode, dropFirstReplyOperation,
                perRequestReplyLossOperation).run(port);
    }

    private void run(int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(500);
            System.out.println("Flight server listening on UDP port " + port);
            System.out.println("Invocation semantics: " + semantics);

            byte[] buffer = new byte[65535];

            while (true) {
                monitors.removeExpired();

                DatagramPacket packet =
                        new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                Message request;
                try {
                    request = MessageCodec.decode(
                            packet.getData(),
                            packet.getOffset(),
                            packet.getLength());
                } catch (IllegalArgumentException e) {
                    System.out.println("Discard malformed packet: "
                            + e.getMessage());
                    continue;
                }

                if (request.messageType != Protocol.REQUEST) {
                    System.out.println("Discard non-request packet");
                    continue;
                }

                if (request.statusCode != 0 || request.requestId == 0) {
                    send(socket, packet,
                            error(request, Protocol.BAD_REQUEST,
                                    "Invalid request header"));
                    continue;
                }

                System.out.println("REQUEST client=" + request.clientId
                        + " request=" + request.requestId
                        + " operation=" + request.operationCode
                        + " from=" + packet.getAddress().getHostAddress()
                        + ":" + packet.getPort());

                byte[] encoded = semantics == InvocationSemantics.AT_MOST_ONCE
                        ? history.find(request.clientId, request.requestId)
                        : null;

                if (encoded != null) {
                    System.out.println("DUPLICATE DETECTED client="
                            + request.clientId + " request=" + request.requestId
                            + "; REPLAY CACHED REPLY");
                } else {
                    Message reply = handle(request, packet, socket);
                    encoded = MessageCodec.encode(reply);

                    // Cache before simulated reply loss. A duplicate then replays
                    // the same response without repeating mutations or callbacks.
                    if (semantics == InvocationSemantics.AT_MOST_ONCE) {
                        history.remember(
                                request.clientId, request.requestId, encoded);
                    }
                    System.out.println("GENERATED REPLY request="
                            + request.requestId
                            + " operation=" + request.operationCode
                            + " status=" + reply.statusCode);
                }

                boolean dropThisReply;
                if (perRequestReplyLossOperation != 0) {
                    RequestKey key = new RequestKey(request.clientId, request.requestId);
                    dropThisReply = request.operationCode == perRequestReplyLossOperation
                            && repliesDroppedForRequests.add(key);
                } else {
                    dropThisReply = replyLoss.shouldDrop(request.operationCode);
                }

                if (dropThisReply) {
                    System.out.println("SIMULATED REPLY LOSS request="
                            + request.requestId
                            + " operation=" + request.operationCode);
                } else {
                    sendBytes(socket, packet, encoded);
                }
            }
        }
    }

    private Message handle(Message request, DatagramPacket packet,
                           DatagramSocket socket) {
        PayloadReader reader = new PayloadReader(request.payload);
        PayloadWriter writer = new PayloadWriter();

        try {
            switch (request.operationCode) {
                case Protocol.SEARCH_FLIGHTS: {
                    String source = reader.readString();
                    String destination = reader.readString();
                    reader.requireEnd();

                    List<Integer> ids =
                            service.searchFlights(source, destination);

                    if (ids.isEmpty()) {
                        return error(request, Protocol.ROUTE_NOT_FOUND,
                                "No matching flights");
                    }

                    // IPv4 UDP payload maximum is 65507 bytes.
                    if (ids.size() > (65507 - 20 - 2) / 4) {
                        return error(request, Protocol.INTERNAL_ERROR,
                                "Too many results for one UDP reply");
                    }

                    writer.writeUnsignedShort(ids.size());
                    for (int id : ids) {
                        writer.writeInt(id);
                    }
                    break;
                }

                case Protocol.GET_FLIGHT_DETAILS: {
                    int flightId = reader.readInt();
                    reader.requireEnd();

                    Flight flight = findFlight(flightId);
                    if (flight == null) {
                        return missingFlight(request);
                    }

                    writer.writeUnsignedShort(flight.getYear());
                    writer.writeUnsignedByte(flight.getMonth());
                    writer.writeUnsignedByte(flight.getDay());
                    writer.writeUnsignedByte(flight.getHour());
                    writer.writeUnsignedByte(flight.getMinute());
                    writer.writeDouble(flight.getAirfare());
                    writer.writeInt(flight.getAvailableSeats());
                    break;
                }

                case Protocol.RESERVE_SEATS: {
                    int flightId = reader.readInt();
                    int count = reader.readInt();
                    reader.requireEnd();

                    Flight flight = findFlight(flightId);
                    if (flight == null) {
                        return missingFlight(request);
                    }
                    if (count <= 0) {
                        return error(request, Protocol.INVALID_ARGUMENT,
                                "Seat count must be positive");
                    }
                    if (count > flight.getAvailableSeats()) {
                        return error(request, Protocol.INSUFFICIENT_SEATS,
                                "Not enough seats");
                    }

                    int remaining = service.reserveSeats(flightId, count);
                    writer.writeInt(remaining);

                    System.out.println("RESERVATION flight=" + flightId
                            + " reserved=" + count
                            + " remaining=" + remaining);

                    notifyMonitors(socket, flightId, remaining);
                    break;
                }

                case Protocol.MONITOR_FLIGHT: {
                    int flightId = reader.readInt();
                    long intervalSeconds = reader.readUnsignedInt();
                    reader.requireEnd();

                    if (findFlight(flightId) == null) {
                        return missingFlight(request);
                    }
                    if (intervalSeconds == 0) {
                        return error(request, Protocol.INVALID_ARGUMENT,
                                "Monitor interval must be positive");
                    }

                    monitors.register(
                            request.clientId,
                            flightId,
                            packet.getAddress(),
                            packet.getPort(),
                            intervalSeconds);

                    writer.writeUnsignedInt(intervalSeconds);
                    System.out.println("MONITOR flight=" + flightId
                            + " client=" + request.clientId
                            + " seconds=" + intervalSeconds);
                    break;
                }

                case Protocol.SET_AIRFARE: {
                    int flightId = reader.readInt();
                    double price = reader.readDouble();
                    reader.requireEnd();

                    if (findFlight(flightId) == null) {
                        return missingFlight(request);
                    }
                    if (!Double.isFinite(price) || price < 0) {
                        return error(request, Protocol.INVALID_ARGUMENT,
                                "Airfare must be finite and non-negative");
                    }

                    writer.writeDouble(service.setAirfare(flightId, price));
                    break;
                }

                case Protocol.ADD_SEATS: {
                    int flightId = reader.readInt();
                    int amount = reader.readInt();
                    reader.requireEnd();

                    Flight flight = findFlight(flightId);
                    if (flight == null) {
                        return missingFlight(request);
                    }
                    if (amount <= 0
                            || amount > Integer.MAX_VALUE
                            - flight.getAvailableSeats()) {
                        return error(request, Protocol.INVALID_ARGUMENT,
                                "Invalid seat increase");
                    }

                    writer.writeInt(service.addSeats(flightId, amount));
                    break;
                }

                default:
                    return error(request, Protocol.UNKNOWN_OPERATION,
                            "Unknown operation");
            }

            return new Message(
                    Protocol.REPLY,
                    request.clientId,
                    request.requestId,
                    request.operationCode,
                    Protocol.OK,
                    writer.toByteArray());

        } catch (IllegalArgumentException e) {
            return error(request, Protocol.BAD_REQUEST,
                    "Malformed payload or invalid argument");
        } catch (Exception e) {
            System.out.println("Operation failed: " + e);
            return error(request, Protocol.INTERNAL_ERROR,
                    "Internal server error");
        }
    }

    private Flight findFlight(int flightId) {
        try {
            return service.getFlight(flightId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Message missingFlight(Message request) {
        return error(request, Protocol.FLIGHT_NOT_FOUND,
                "Flight not found");
    }

    private Message error(Message request, int status, String text) {
        PayloadWriter writer = new PayloadWriter();
        writer.writeString(text);

        return new Message(
                Protocol.REPLY,
                request.clientId,
                request.requestId,
                request.operationCode,
                status,
                writer.toByteArray());
    }

    private void send(DatagramSocket socket, DatagramPacket received,
                      Message reply) {
        sendBytes(socket, received, MessageCodec.encode(reply));
    }

    private void sendBytes(DatagramSocket socket, DatagramPacket received,
                           byte[] data) {
        try {
            socket.send(new DatagramPacket(
                    data, data.length,
                    received.getAddress(), received.getPort()));

            Message reply = MessageCodec.decode(data, 0, data.length);
            System.out.println("REPLY request=" + reply.requestId
                    + " operation=" + reply.operationCode
                    + " status=" + reply.statusCode);
        } catch (Exception e) {
            System.out.println("Reply send failed: " + e.getMessage());
        }
    }

    private void notifyMonitors(DatagramSocket socket,
                                int flightId, int remainingSeats) {
        for (MonitorRegistry.Registration registration
                : monitors.activeForFlight(flightId)) {
            // Recheck expiry immediately before sending.
            if (registration.expiresAtMillis
                    <= System.currentTimeMillis()) {
                continue;
            }

            try {
                PayloadWriter writer = new PayloadWriter();
                writer.writeInt(flightId);
                writer.writeInt(remainingSeats);

                Message callback = new Message(
                        Protocol.CALLBACK,
                        registration.clientId,
                        0L,
                        Protocol.MONITOR_FLIGHT,
                        Protocol.OK,
                        writer.toByteArray());

                byte[] data = MessageCodec.encode(callback);
                socket.send(new DatagramPacket(
                        data, data.length,
                        registration.address, registration.port));

                System.out.println("CALLBACK flight=" + flightId
                        + " seats=" + remainingSeats
                        + " client=" + registration.clientId);
            } catch (Exception e) {
                System.out.println("Callback send failed: "
                        + e.getMessage());
            }
        }
    }
}
