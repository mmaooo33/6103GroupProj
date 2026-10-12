/** Constants for protocol.md, version 1. All multibyte numbers are big-endian. */
public final class Protocol {
    private Protocol() { }
    public static final int REQUEST = 1, REPLY = 2, CALLBACK = 3;
    public static final int SEARCH_FLIGHTS = 1, GET_FLIGHT_DETAILS = 2,
            RESERVE_SEATS = 3, MONITOR_FLIGHT = 4, SET_AIRFARE = 5, ADD_SEATS = 6;
    public static final int OK = 0, BAD_REQUEST = 1, FLIGHT_NOT_FOUND = 2,
            ROUTE_NOT_FOUND = 3, INSUFFICIENT_SEATS = 4, INVALID_ARGUMENT = 5,
            UNKNOWN_OPERATION = 6, INTERNAL_ERROR = 7, PROTOCOL_ERROR = 8;
    public static final int HEADER_LENGTH = 20, VERSION = 1;
    public static final int MAX_DATAGRAM_LENGTH = 65507;
    public static final int MAX_PAYLOAD_LENGTH = MAX_DATAGRAM_LENGTH - HEADER_LENGTH;
    public static final long MAX_REQUEST_ID = 0xffff_ffffL;
}
