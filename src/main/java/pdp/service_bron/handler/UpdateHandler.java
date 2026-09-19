package pdp.service_bron.handler;

import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;

/**
 * A handler owns one domain. Handlers only parse the update, call services and render messages;
 * business rules live in services.
 */
public interface UpdateHandler {

    /** Callback prefix and state owner: {@code c}, {@code b}, {@code o}, {@code a} or {@code x}. */
    String domain();

    /** A reply-keyboard button of this domain was pressed. {@code menuKey} looks like {@code menu.c.book}. */
    default void onMenu(BotContext ctx, String menuKey) {
    }

    /** Free-form input (text, contact, location, photo) while the session is in a state owned by this domain. */
    default void onInput(BotContext ctx) {
    }

    /** An inline button of this domain was pressed. Permissions must be re-checked here. */
    default void onCallback(BotContext ctx, CallbackData data) {
    }
}
