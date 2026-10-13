import java.net.InetAddress;
import java.util.List;

/** Lightweight checks that need no external test framework or live server. */
public final class ProjectSelfTest {
    private static int checks;

    private ProjectSelfTest() { }

    public static void main(String[] args) throws Exception {
        testPayloadAndMessageCodec();
        testCallbackCodec();
        testMalformedMessageRejection();
        testFlightService();
        testReplyHistory();
        testFaultInjector();
        testMonitorRegistry();
        System.out.println("SELF-TEST PASSED (" + checks + " checks)");
    }

    private static void testPayloadAndMessageCodec() {
        PayloadWriter payload = new PayloadWriter();
        payload.writeString("Singapore");
        payload.writeString("东京");
        payload.writeInt(123456789);

        Message original = new Message(
                Protocol.REQUEST, 99L, 7L,
                Protocol.SEARCH_FLIGHTS, Protocol.OK,
                payload.toByteArray());
        byte[] encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded, 0, encoded.length);

        check(decoded.messageType == Protocol.REQUEST, "request type");
        check(decoded.clientId == 99L, "client ID");
        check(decoded.requestId == 7L, "request ID");
        check(decoded.operationCode == Protocol.SEARCH_FLIGHTS, "operation");

        PayloadReader reader = new PayloadReader(decoded.payload);
        check("Singapore".equals(reader.readString()), "ASCII string round trip");
        check("东京".equals(reader.readString()), "UTF-8 string round trip");
        check(reader.readInt() == 123456789, "integer round trip");
        reader.requireEnd();
    }

    private static void testCallbackCodec() {
        PayloadWriter payload = new PayloadWriter();
        payload.writeInt(101);
        payload.writeInt(18);
        Message callback = new Message(
                Protocol.CALLBACK, 123L, 0L,
                Protocol.MONITOR_FLIGHT, Protocol.OK,
                payload.toByteArray());

        byte[] encoded = MessageCodec.encode(callback);
        Message decoded = MessageCodec.decode(encoded, 0, encoded.length);
        PayloadReader reader = new PayloadReader(decoded.payload);
        check(reader.readInt() == 101, "callback flight ID");
        check(reader.readInt() == 18, "callback seat count");
        reader.requireEnd();
    }

    private static void testMalformedMessageRejection() {
        boolean rejected = false;
        try {
            MessageCodec.decode(new byte[Protocol.HEADER_LENGTH - 1],
                    0, Protocol.HEADER_LENGTH - 1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "truncated datagram rejection");
    }

    private static void testFlightService() {
        FlightService service = new FlightService();
        List<Integer> ids = service.searchFlights(" singapore ", "TOKYO");
        check(ids.equals(List.of(101, 102)), "route search returns all sorted IDs");

        check(service.reserveSeats(101, 2) == 18, "reservation changes seats");
        check(service.setAirfare(101, 750.0) == 750.0, "set airfare");
        check(service.setAirfare(101, 750.0) == 750.0,
                "set airfare is idempotent");
        check(service.addSeats(101, 3) == 21, "first add-seats execution");
        check(service.addSeats(101, 3) == 24,
                "add-seats is non-idempotent");
    }

    private static void testReplyHistory() {
        ReplyHistory history = new ReplyHistory();
        byte[] reply = {1, 2, 3};
        history.remember(8L, 9L, reply);
        reply[0] = 99;

        byte[] stored = history.find(8L, 9L);
        check(stored != null && stored[0] == 1, "history stores a defensive copy");
        stored[1] = 99;
        check(history.find(8L, 9L)[1] == 2, "history returns a defensive copy");
        check(history.find(8L, 10L) == null, "history key uses request ID");
    }

    private static void testFaultInjector() {
        FaultInjector injector = new FaultInjector(Protocol.ADD_SEATS);
        check(!injector.shouldDrop(Protocol.SET_AIRFARE),
                "unmatched operation is not dropped");
        check(injector.shouldDrop(Protocol.ADD_SEATS),
                "first matching operation is dropped");
        check(!injector.shouldDrop(Protocol.ADD_SEATS),
                "second matching operation is delivered");
    }

    private static void testMonitorRegistry() throws Exception {
        MonitorRegistry registry = new MonitorRegistry();
        registry.register(1L, 101, InetAddress.getLoopbackAddress(), 50000, 30L);
        registry.register(2L, 101, InetAddress.getLoopbackAddress(), 50001, 30L);
        check(registry.activeForFlight(101).size() == 2,
                "multiple concurrent monitoring registrations");

        registry.register(1L, 101, InetAddress.getLoopbackAddress(), 50002, 30L);
        List<MonitorRegistry.Registration> active = registry.activeForFlight(101);
        check(active.size() == 2, "same client registration is replaced");
        int replacementPort = active.stream()
                .filter(registration -> registration.clientId == 1L)
                .findFirst()
                .orElseThrow()
                .port;
        check(replacementPort == 50002, "replacement address is retained");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + description);
    }
}
