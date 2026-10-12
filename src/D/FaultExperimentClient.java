import java.net.InetAddress;

/** Small D-owned experiment driver; C's interactive FlightClient can use the same transport. */
public final class FaultExperimentClient {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5) {
            System.out.println("Usage: java FaultExperimentClient <serverIp> <port> <mode>"
                    + " [--operation=5|6] [--drop-first-request-op=5|6]");
            return;
        }
        int operation = Protocol.ADD_SEATS;
        int drop = 0;
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("--operation=")) {
                operation = Integer.parseInt(args[i].substring("--operation=".length()));
            } else if (args[i].startsWith("--drop-first-request-op=")) {
                drop = Integer.parseInt(args[i].substring("--drop-first-request-op=".length()));
            } else {
                throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if ((operation != 5 && operation != 6) || (drop != 0 && drop != operation))
            throw new IllegalArgumentException("Use operation 5 or 6 and a matching drop operation");
        try (ReliableUdpClient client = new ReliableUdpClient(InetAddress.getByName(args[0]),
                Integer.parseInt(args[1]), InvocationSemantics.parse(args[2]), 1000, 4, drop)) {
            PayloadWriter payload = new PayloadWriter();
            payload.writeInt(101);
            if (operation == Protocol.ADD_SEATS) payload.writeInt(10);
            else payload.writeDouble(750.0);
            Message reply = client.invoke(operation, payload.toByteArray());
            if (!success(reply)) return;
            PayloadReader result = new PayloadReader(reply.payload);
            if (operation == Protocol.ADD_SEATS) System.out.println("ADD_SEATS reply: " + result.readInt());
            else System.out.println("SET_AIRFARE reply: " + result.readDouble());
            PayloadWriter query = new PayloadWriter();
            query.writeInt(101);
            Message details = client.invoke(Protocol.GET_FLIGHT_DETAILS, query.toByteArray());
            if (!success(details)) return;
            PayloadReader reader = new PayloadReader(details.payload);
            reader.readUnsignedShort();
            for (int i = 0; i < 4; i++) reader.readUnsignedByte();
            System.out.println("Flight 101: airfare=" + reader.readDouble() + " seats=" + reader.readInt());
            reader.requireEnd();
        }
    }

    private static boolean success(Message reply) {
        if (reply.statusCode == Protocol.OK) return true;
        System.out.println("ERROR status=" + reply.statusCode + ": "
                + new PayloadReader(reply.payload).readString());
        return false;
    }
}
