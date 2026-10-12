/** Drops only the first matching packet over this injector's entire lifetime. */
public final class FaultInjector {
    private final int operation;
    private boolean dropped;

    public FaultInjector(int operation) {
        if (operation < 0 || operation > Protocol.ADD_SEATS)
            throw new IllegalArgumentException("Drop operation must be 0 (disabled) or 1..6");
        this.operation = operation;
    }

    public boolean shouldDrop(int operationCode) {
        if (operation != 0 && !dropped && operationCode == operation) {
            dropped = true;
            return true;
        }
        return false;
    }
}
