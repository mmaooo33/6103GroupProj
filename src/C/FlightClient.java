import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Scanner;

/**
 * Console client for the distributed flight information system.
 *
 * <p>This class deliberately has no package declaration because the shared
 * classes supplied in src/D currently use the default package. One
 * ReliableUdpClient owns the UDP socket used for requests, replies and
 * monitoring callbacks.</p>
 */
public final class FlightClient {
    private static final long MAX_U32 = 0xffff_ffffL;

    private FlightClient() { }

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            printUsage();
            return;
        }

        try {
            InetAddress serverAddress = InetAddress.getByName(args[0]);
            int serverPort = parsePort(args[1]);
            InvocationSemantics semantics = InvocationSemantics.parse(args[2]);
            int dropFirstRequestOperation = parseFaultOption(args);

            try (Scanner scanner = new Scanner(System.in);
                 ReliableUdpClient client = new ReliableUdpClient(
                         serverAddress,
                         serverPort,
                         semantics,
                         1000,
                         4,
                         dropFirstRequestOperation)) {

                client.setCallbackHandler(FlightClient::printCallback);
                runMenu(scanner, client);
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Configuration error: " + e.getMessage());
            printUsage();
        } catch (IOException e) {
            System.out.println("Unable to start client: " + e.getMessage());
        }
    }

    private static void runMenu(Scanner scanner, ReliableUdpClient client) {
        while (true) {
            printMenu();
            try {
                int choice = promptInt(scanner, "Choose an option: ");
                switch (choice) {
                    case 0:
                        System.out.println("Client terminated.");
                        return;
                    case 1:
                        searchFlights(scanner, client);
                        break;
                    case 2:
                        getFlightDetails(scanner, client);
                        break;
                    case 3:
                        reserveSeats(scanner, client);
                        break;
                    case 4:
                        monitorFlight(scanner, client);
                        break;
                    case 5:
                        setAirfare(scanner, client);
                        break;
                    case 6:
                        addSeats(scanner, client);
                        break;
                    default:
                        System.out.println("Please enter a number from 0 to 6.");
                }
            } catch (EndOfInputException e) {
                System.out.println("\nInput closed; client terminated.");
                return;
            } catch (SocketTimeoutException e) {
                System.out.println("TIMEOUT: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("Network error: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid data: " + e.getMessage());
            }
        }
    }

    private static void searchFlights(Scanner scanner, ReliableUdpClient client)
            throws IOException {
        String source = promptNonEmpty(scanner, "Source: ");
        String destination = promptNonEmpty(scanner, "Destination: ");

        PayloadWriter payload = new PayloadWriter();
        payload.writeString(source);
        payload.writeString(destination);

        Message reply = invokeForSuccess(
                client, Protocol.SEARCH_FLIGHTS, payload.toByteArray());
        if (reply == null) return;

        PayloadReader reader = new PayloadReader(reply.payload);
        int count = reader.readUnsignedShort();
        System.out.println("Matching flight IDs (" + count + "):");
        for (int i = 0; i < count; i++) {
            System.out.println("  " + reader.readInt());
        }
        reader.requireEnd();
    }

    private static void getFlightDetails(Scanner scanner, ReliableUdpClient client)
            throws IOException {
        int flightId = promptInt(scanner, "Flight ID: ");

        PayloadWriter payload = new PayloadWriter();
        payload.writeInt(flightId);
        Message reply = invokeForSuccess(
                client, Protocol.GET_FLIGHT_DETAILS, payload.toByteArray());
        if (reply == null) return;

        PayloadReader reader = new PayloadReader(reply.payload);
        int year = reader.readUnsignedShort();
        int month = reader.readUnsignedByte();
        int day = reader.readUnsignedByte();
        int hour = reader.readUnsignedByte();
        int minute = reader.readUnsignedByte();
        double airfare = reader.readDouble();
        int seats = reader.readInt();
        reader.requireEnd();

        System.out.println("Flight " + flightId);
        System.out.printf("  Departure (UTC): %04d-%02d-%02d %02d:%02d%n",
                year, month, day, hour, minute);
        System.out.printf("  Airfare: %.2f%n", airfare);
        System.out.println("  Available seats: " + seats);
    }

    private static void reserveSeats(Scanner scanner, ReliableUdpClient client)
            throws IOException {
        int flightId = promptInt(scanner, "Flight ID: ");
        int count = promptPositiveInt(scanner, "Number of seats to reserve: ");

        PayloadWriter payload = new PayloadWriter();
        payload.writeInt(flightId);
        payload.writeInt(count);
        Message reply = invokeForSuccess(
                client, Protocol.RESERVE_SEATS, payload.toByteArray());
        if (reply == null) return;

        PayloadReader reader = new PayloadReader(reply.payload);
        int remaining = reader.readInt();
        reader.requireEnd();
        System.out.println("Reservation successful. Remaining seats: " + remaining);
    }

    private static void monitorFlight(Scanner scanner, ReliableUdpClient client)
            throws IOException {
        int flightId = promptInt(scanner, "Flight ID to monitor: ");
        long requestedSeconds = promptPositiveLong(
                scanner, "Monitoring interval in seconds: ", MAX_U32);

        PayloadWriter payload = new PayloadWriter();
        payload.writeInt(flightId);
        payload.writeUnsignedInt(requestedSeconds);
        Message reply = invokeForSuccess(
                client, Protocol.MONITOR_FLIGHT, payload.toByteArray());
        if (reply == null) return;

        PayloadReader reader = new PayloadReader(reply.payload);
        long acceptedSeconds = reader.readUnsignedInt();
        reader.requireEnd();

        System.out.println("Monitoring flight " + flightId + " for "
                + acceptedSeconds + " seconds.");
        System.out.println("The menu is blocked while callbacks are received...");
        client.listenForCallbacks(acceptedSeconds * 1000L);
        System.out.println("Monitoring interval finished.");
    }

    private static void setAirfare(Scanner scanner, ReliableUdpClient client)
            throws IOException {
        int flightId = promptInt(scanner, "Flight ID: ");
        double newAirfare = promptNonNegativeFiniteDouble(scanner, "New airfare: ");

        PayloadWriter payload = new PayloadWriter();
        payload.writeInt(flightId);
        payload.writeDouble(newAirfare);
        Message reply = invokeForSuccess(
                client, Protocol.SET_AIRFARE, payload.toByteArray());
        if (reply == null) return;

        PayloadReader reader = new PayloadReader(reply.payload);
        double currentAirfare = reader.readDouble();
        reader.requireEnd();
        System.out.printf("Airfare updated to %.2f.%n", currentAirfare);
    }

    private static void addSeats(Scanner scanner, ReliableUdpClient client)
            throws IOException {
        int flightId = promptInt(scanner, "Flight ID: ");
        int amount = promptPositiveInt(scanner, "Number of seats to add: ");

        PayloadWriter payload = new PayloadWriter();
        payload.writeInt(flightId);
        payload.writeInt(amount);
        Message reply = invokeForSuccess(
                client, Protocol.ADD_SEATS, payload.toByteArray());
        if (reply == null) return;

        PayloadReader reader = new PayloadReader(reply.payload);
        int remaining = reader.readInt();
        reader.requireEnd();
        System.out.println("Seats added. Available seats: " + remaining);
    }

    private static Message invokeForSuccess(
            ReliableUdpClient client, int operation, byte[] payload)
            throws IOException {
        Message reply = client.invoke(operation, payload);
        if (reply.statusCode == Protocol.OK) return reply;

        PayloadReader reader = new PayloadReader(reply.payload);
        String errorMessage = reader.readString();
        reader.requireEnd();
        System.out.println("Server error (status=" + reply.statusCode
                + "): " + errorMessage);
        return null;
    }

    private static void printCallback(Message callback) {
        PayloadReader reader = new PayloadReader(callback.payload);
        int flightId = reader.readInt();
        int availableSeats = reader.readInt();
        reader.requireEnd();
        System.out.println();
        System.out.println("CALLBACK: flight " + flightId
                + " now has " + availableSeats + " available seats.");
    }

    private static int parsePort(String text) {
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be an integer");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }

    private static int parseFaultOption(String[] args) {
        if (args.length == 3) return 0;
        String prefix = "--drop-first-request-op=";
        if (!args[3].startsWith(prefix)) {
            throw new IllegalArgumentException("Unknown option: " + args[3]);
        }
        try {
            int operation = Integer.parseInt(args[3].substring(prefix.length()));
            if (operation < 1 || operation > Protocol.ADD_SEATS) {
                throw new IllegalArgumentException("Drop operation must be 1..6");
            }
            return operation;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Drop operation must be an integer from 1 to 6");
        }
    }

    private static String promptNonEmpty(Scanner scanner, String prompt) {
        while (true) {
            String value = promptLine(scanner, prompt);
            if (!value.isEmpty()) return value;
            System.out.println("Value cannot be empty.");
        }
    }

    private static int promptInt(Scanner scanner, String prompt) {
        while (true) {
            String value = promptLine(scanner, prompt);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid 32-bit integer.");
            }
        }
    }

    private static int promptPositiveInt(Scanner scanner, String prompt) {
        while (true) {
            int value = promptInt(scanner, prompt);
            if (value > 0) return value;
            System.out.println("Value must be positive.");
        }
    }

    private static long promptPositiveLong(
            Scanner scanner, String prompt, long maximum) {
        while (true) {
            String value = promptLine(scanner, prompt);
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0 && parsed <= maximum) return parsed;
            } catch (NumberFormatException ignored) {
                // Print the same concise range message below.
            }
            System.out.println("Value must be between 1 and " + maximum + ".");
        }
    }

    private static double promptNonNegativeFiniteDouble(
            Scanner scanner, String prompt) {
        while (true) {
            String value = promptLine(scanner, prompt);
            try {
                double parsed = Double.parseDouble(value);
                if (Double.isFinite(parsed) && parsed >= 0) return parsed;
            } catch (NumberFormatException ignored) {
                // Print the same concise validation message below.
            }
            System.out.println("Value must be a finite non-negative number.");
        }
    }

    private static String promptLine(Scanner scanner, String prompt) {
        System.out.print(prompt);
        System.out.flush();
        if (!scanner.hasNextLine()) throw new EndOfInputException();
        return scanner.nextLine().trim();
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("=== Distributed Flight Information System ===");
        System.out.println("1. Search flights by route");
        System.out.println("2. Get flight details");
        System.out.println("3. Reserve seats");
        System.out.println("4. Monitor seat availability");
        System.out.println("5. Set airfare (idempotent)");
        System.out.println("6. Add seats (non-idempotent)");
        System.out.println("0. Exit");
    }

    private static void printUsage() {
        System.out.println("Usage: java FlightClient <serverIp> <serverPort>"
                + " <at-least-once|at-most-once>"
                + " [--drop-first-request-op=1..6]");
    }

    private static final class EndOfInputException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
