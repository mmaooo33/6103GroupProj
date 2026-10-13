/**
 * Backward-compatible launcher retained for existing experiment commands.
 * The single authoritative implementation now lives in FlightServer.
 */
@Deprecated
public final class DFlightServer {
    private DFlightServer() { }

    public static void main(String[] args) throws Exception {
        FlightServer.main(args);
    }
}
