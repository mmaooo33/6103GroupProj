import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * UDP flight server.
 * Uses the shared codec interfaces defined in interfaces.md.
 *
 * This version implements functional services only.
 * Member D's duplicate detection and fault simulation must be
 * integrated into the receive loop before reliability experiments.
 */
public class FlightServer {
    private final FlightService service = new FlightService();
    private final MonitorRegistry monitors = new MonitorRegistry();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: java FlightServer <port>");
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

        new FlightServer().run(port);
    }

    private void run(int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(500);
            System.out.println("Flight server listening on UDP port " + port);

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

                /*
                 * D integration point:
                 * In at-most-once mode, check reply history here.
                 * If found, resend cached bytes and skip handle().
                 */
                Message reply = handle(request, packet, socket);

                /*
                 * D integration point:
                 * Encode and cache the reply before reply-loss simulation.
                 */
                send(socket, packet, reply);
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
        try {
            byte[] data = MessageCodec.encode(reply);
            socket.send(new DatagramPacket(
                    data, data.length,
                    received.getAddress(), received.getPort()));

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