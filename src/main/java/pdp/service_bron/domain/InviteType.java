package pdp.service_bron.domain;

public enum InviteType {
    OWNER("o"),
    BARBER("b");

    private final String prefix;

    InviteType(String prefix) {
        this.prefix = prefix;
    }

    /** Deep link prefix: {@code o_<token>} or {@code b_<token>}. */
    public String prefix() {
        return prefix;
    }
}
