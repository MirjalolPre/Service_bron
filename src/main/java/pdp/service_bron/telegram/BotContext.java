package pdp.service_bron.telegram;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.Session;

/** Everything a handler needs to know about the update being processed. */
public final class BotContext {

    private final Update update;
    private final AppUser user;
    private final Session session;
    private boolean callbackAnswered;

    public BotContext(Update update, AppUser user, Session session) {
        this.update = update;
        this.user = user;
        this.session = session;
    }

    public Update update() {
        return update;
    }

    public AppUser user() {
        return user;
    }

    public Session session() {
        return session;
    }

    public BotState state() {
        return session.state();
    }

    public Lang lang() {
        return user.lang();
    }

    /** Telegram user id. In private chats it is also the chat id. */
    public long telegramId() {
        return user.getTelegramId();
    }

    public long chatId() {
        return user.getTelegramId();
    }

    public boolean isCallback() {
        return update.hasCallbackQuery();
    }

    public CallbackQuery callback() {
        return update.getCallbackQuery();
    }

    public String callbackId() {
        return update.hasCallbackQuery() ? update.getCallbackQuery().getId() : null;
    }

    /** Id of the message the pressed inline button belongs to. */
    public Integer callbackMessageId() {
        if (!update.hasCallbackQuery() || update.getCallbackQuery().getMessage() == null) {
            return null;
        }
        return update.getCallbackQuery().getMessage().getMessageId();
    }

    public Message message() {
        return update.getMessage();
    }

    /** Message text, or null when the update is not a text message. */
    public String text() {
        return update.hasMessage() && update.getMessage().hasText() ? update.getMessage().getText().trim() : null;
    }

    public boolean callbackAnswered() {
        return callbackAnswered;
    }

    public void markCallbackAnswered() {
        this.callbackAnswered = true;
    }
}
