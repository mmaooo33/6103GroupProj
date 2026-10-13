import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Server-side registrations for seat-availability callbacks. */
public class MonitorRegistry {
    public static class Registration {
        public final long clientId;
        public final int flightId;
        public final InetAddress address;
        public final int port;
        public final long expiresAtMillis;

        private Registration(long clientId, int flightId,
                             InetAddress address, int port, long expiresAtMillis) {
            this.clientId = clientId;
            this.flightId = flightId;
            this.address = address;
            this.port = port;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final List<Registration> registrations = new ArrayList<>();

    public void register(long clientId, int flightId, InetAddress address,
                         int port, long intervalSeconds) {
        if (address == null || port < 1 || port > 65535
                || intervalSeconds <= 0) {
            throw new IllegalArgumentException("Invalid monitor registration");
        }

        long now = System.currentTimeMillis();
        long duration;
        long expiresAt;
        try {
            duration = Math.multiplyExact(intervalSeconds, 1000L);
            expiresAt = Math.addExact(now, duration);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Monitor interval is too large");
        }

        removeExpired();

        // A new registration replaces this client's old registration
        // for the same flight.
        registrations.removeIf(r ->
                r.clientId == clientId && r.flightId == flightId);

        registrations.add(new Registration(
                clientId, flightId, address, port, expiresAt));
    }

    public List<Registration> activeForFlight(int flightId) {
        removeExpired();

        List<Registration> result = new ArrayList<>();
        for (Registration registration : registrations) {
            if (registration.flightId == flightId) {
                result.add(registration);
            }
        }
        return result;
    }

    public void removeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Registration> iterator = registrations.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMillis <= now) {
                iterator.remove();
            }
        }
    }
}