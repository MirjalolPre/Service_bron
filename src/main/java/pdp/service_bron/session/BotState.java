package pdp.service_bron.session;

/**
 * Conversation state, stored in the database (bot_session) so a restart never loses it.
 * Each state belongs to a handler domain ({@code c} client, {@code b} barber, {@code o} owner,
 * {@code a} admin, {@code x} common); free-form input is routed to the owner of the state.
 */
public enum BotState {
    IDLE("x"),

    // common
    AWAIT_PHONE("x"),

    // client
    C_SEARCH("c"),

    // barber
    B_NAME("b"),
    B_BIO("b"),
    B_PHOTO("b"),
    B_HOURS_TIME("b"),
    B_HOURS_BREAK("b"),
    B_WIZ_HOURS("b"),
    B_WIZ_BREAK("b"),
    B_PRICE_NAME("b"),
    B_PRICE_VALUE("b"),
    B_PRICE_EDIT_NAME("b"),
    B_PRICE_EDIT_VALUE("b"),
    B_TIMEOFF_RANGE("b"),
    B_MANUAL_NAME("b"),
    B_MANUAL_PHONE("b"),
    B_CANCEL_REASON("b"),

    // owner
    O_TEXT("o"),
    O_LOCATION("o"),
    O_PHOTO("o"),
    O_BARBER_NAME("o"),

    // super admin
    A_SHOP_NAME("a");

    private final String domain;

    BotState(String domain) {
        this.domain = domain;
    }

    public String domain() {
        return domain;
    }
}
