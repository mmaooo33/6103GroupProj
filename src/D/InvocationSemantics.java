/** Process configuration; deliberately not an extra wire-protocol field. */
public enum InvocationSemantics {
    AT_LEAST_ONCE("at-least-once"), AT_MOST_ONCE("at-most-once");
    private final String option;
    InvocationSemantics(String option) { this.option = option; }
    public static InvocationSemantics parse(String value) {
        for (InvocationSemantics mode : values()) if (mode.option.equals(value)) return mode;
        throw new IllegalArgumentException("Mode must be at-least-once or at-most-once");
    }
    @Override public String toString() { return option; }
}
