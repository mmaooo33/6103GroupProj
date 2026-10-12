import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flight operations performed by the server.
 * Flight data is kept in memory while the server is running.
 */
public class FlightService {
    private final Map<Integer, Flight> flights = new HashMap<>();

    public FlightService() {
        // Sample flights for development and demonstration.
        // Date and time fields use UTC, as specified in protocol.md.
        addFlight(new Flight(101, "Singapore", "Tokyo",
                2026, 10, 20, 8, 30, 500.0, 20));
        addFlight(new Flight(102, "Singapore", "Tokyo",
                2026, 10, 20, 13, 45, 620.0, 15));
        addFlight(new Flight(103, "Singapore", "London",
                2026, 10, 21, 9, 0, 900.0, 12));
    }

    private void addFlight(Flight flight) {
        if (flights.containsKey(flight.getFlightId())) {
            throw new IllegalArgumentException("Duplicate flight ID");
        }
        flights.put(flight.getFlightId(), flight);
    }

    // Returns every matching flight ID in ascending order.
    public List<Integer> searchFlights(String source, String destination) {
        if (source == null || source.trim().isEmpty()
                || destination == null || destination.trim().isEmpty()) {
            throw new IllegalArgumentException("Source and destination are required");
        }

        List<Integer> results = new ArrayList<>();
        for (Flight flight : flights.values()) {
            if (flight.getSource().equalsIgnoreCase(source.trim())
                    && flight.getDestination().equalsIgnoreCase(destination.trim())) {
                results.add(flight.getFlightId());
            }
        }
        Collections.sort(results);
        return results;
    }

    public Flight getFlight(int flightId) {
        Flight flight = flights.get(flightId);
        if (flight == null) {
            throw new IllegalArgumentException("Flight not found: " + flightId);
        }
        return flight;
    }

    // Returns the remaining seat count after a successful reservation.
    public int reserveSeats(int flightId, int count) {
        Flight flight = getFlight(flightId);
        flight.reserveSeats(count);
        return flight.getAvailableSeats();
    }

    // Idempotent: setting the same airfare again leaves the same price.
    public double setAirfare(int flightId, double newAirfare) {
        Flight flight = getFlight(flightId);
        flight.setAirfare(newAirfare);
        return flight.getAirfare();
    }

    // Non-idempotent: repeating this operation adds seats again.
    public int addSeats(int flightId, int amount) {
        Flight flight = getFlight(flightId);
        flight.addSeats(amount);
        return flight.getAvailableSeats();
    }
}