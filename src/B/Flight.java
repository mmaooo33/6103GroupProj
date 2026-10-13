/**
 * Represents one flight stored in the server's memory.
 * Departure date and time are interpreted as UTC.
 */
public class Flight {
    private final int flightId;
    private final String source;
    private final String destination;

    private final int year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;

    private double airfare;
    private int availableSeats;

    public Flight(int flightId, String source, String destination,
                  int year, int month, int day, int hour, int minute,
                  double airfare, int availableSeats) {

        if (source == null || source.trim().isEmpty()
                || destination == null || destination.trim().isEmpty()) {
            throw new IllegalArgumentException("Route cannot be empty");
        }
        if (year < 1 || year > 65535) {
            throw new IllegalArgumentException("Invalid year");
        }

        // Checks whether the date and time actually exist.
        java.time.LocalDateTime.of(year, month, day, hour, minute);

        if (!Double.isFinite(airfare) || airfare < 0) {
            throw new IllegalArgumentException("Invalid airfare");
        }
        if (availableSeats < 0) {
            throw new IllegalArgumentException("Seats cannot be negative");
        }

        this.flightId = flightId;
        this.source = source.trim();
        this.destination = destination.trim();
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.airfare = airfare;
        this.availableSeats = availableSeats;
    }

    public int getFlightId() {
        return flightId;
    }

    public String getSource() {
        return source;
    }

    public String getDestination() {
        return destination;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public int getDay() {
        return day;
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public double getAirfare() {
        return airfare;
    }

    public int getAvailableSeats() {
        return availableSeats;
    }

    // Repeating the same price update leaves the same final price.
    public void setAirfare(double newAirfare) {
        if (!Double.isFinite(newAirfare) || newAirfare < 0) {
            throw new IllegalArgumentException("Invalid airfare");
        }
        airfare = newAirfare;
    }

    public void reserveSeats(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Seat count must be positive");
        }
        if (count > availableSeats) {
            throw new IllegalStateException("Not enough seats");
        }
        availableSeats -= count;
    }

    // Each execution adds seats again: this is non-idempotent.
    public void addSeats(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (amount > Integer.MAX_VALUE - availableSeats) {
            throw new IllegalArgumentException("Seat count is too large");
        }
        availableSeats += amount;
    }
}