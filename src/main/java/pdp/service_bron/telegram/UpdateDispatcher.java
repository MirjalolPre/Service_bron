package pdp.service_bron.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.handler.UpdateHandler;
import pdp.service_bron.handler.common.CommonHandler;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.UserService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.Session;
import pdp.service_bron.session.SessionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for every Telegram update. Updates are processed one at a time (single-thread consumer), which
 * keeps each user's conversation state consistent. One try/catch per update: errors are logged and the user
 * gets a friendly message.
 */
@Slf4j
@Component
public class UpdateDispatcher implements LongPollingSingleThreadUpdateConsumer {

    private final UserService users;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TelegramSender sender;
    private final CommonHandler common;
    private final Map<String, UpdateHandler> handlers = new HashMap<>();

    public UpdateDispatcher(UserService users, SessionService sessions, I18nService i18n, TelegramSender sender,
                            CommonHandler common, List<UpdateHandler> handlerList) {
        this.users = users;
        this.sessions = sessions;
        this.i18n = i18n;
        this.sender = sender;
        this.common = common;
        for (UpdateHandler handler : handlerList) {
            handlers.put(handler.domain(), handler);
        }
    }

    @Override
    public void consume(Update update) {
        BotContext ctx = null;
        Long chatId = null;
        Lang lang = Lang.UZ;
        try {
            User from = sender(update);
            if (from == null) {
                return;
            }
            chatId = from.getId();
            AppUser user = users.touch(from);
            lang = user.lang();
            Session session = sessions.load(from.getId());
            ctx = new BotContext(update, user, session);
            route(ctx);
        } catch (BusinessException e) {
            if (chatId != null) {
                sender.send(chatId, i18n.t(lang, e.key(), e.args()));
            }
        } catch (Exception e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
            if (chatId != null) {
                sender.send(chatId, i18n.t(lang, "error.generic"));
            }
        } finally {
            if (update.hasCallbackQuery() && (ctx == null || !ctx.callbackAnswered())) {
                sender.answer(update.getCallbackQuery().getId());
            }
        }
    }

    /** The sender of a private message or callback; null for everything the bot ignores. */
    private User sender(Update update) {
        User from = null;
        if (update.hasCallbackQuery()) {
            from = update.getCallbackQuery().getFrom();
        } else if (update.hasMessage()) {
            Message message = update.getMessage();
            if (message.getChat() != null && "private".equals(message.getChat().getType())) {
                from = message.getFrom();
            }
        }
        if (from == null || Boolean.TRUE.equals(from.getIsBot())) {
            return null;
        }
        return from;
    }

    private void route(BotContext ctx) {
        if (ctx.isCallback()) {
            routeCallback(ctx);
        } else if (ctx.message() != null) {
            routeMessage(ctx);
        }
    }

    private void routeCallback(BotContext ctx) {
        CallbackData data = CallbackData.parse(ctx.callback().getData());
        if (data == null) {
            log.warn("Malformed callback data from {}: {}", ctx.telegramId(), ctx.callback().getData());
            return;
        }
        UpdateHandler handler = handlers.get(data.domain());
        if (handler == null) {
            log.warn("No handler for callback domain '{}'", data.domain());
            return;
        }
        handler.onCallback(ctx, data);
    }

    private void routeMessage(BotContext ctx) {
        String text = ctx.text();
        if (text != null && text.startsWith("/")) {
            common.onCommand(ctx, text);
            return;
        }
        String menuKey = i18n.menuKey(text);
        if (menuKey != null) {
            // Pressing a menu button always abandons the current conversation step.
            sessions.clear(ctx.telegramId());
            BotContext fresh = new BotContext(ctx.update(), ctx.user(), sessions.load(ctx.telegramId()));
            UpdateHandler handler = handlers.get(menuDomain(menuKey));
            (handler != null ? handler : common).onMenu(fresh, menuKey);
            return;
        }
        BotState state = ctx.state();
        if (state != BotState.IDLE) {
            UpdateHandler handler = handlers.get(state.domain());
            if (handler != null) {
                handler.onInput(ctx);
                return;
            }
        }
        common.showHome(ctx);
    }

    /** {@code menu.c.book} belongs to domain {@code c}. */
    private static String menuDomain(String menuKey) {
        String[] parts = menuKey.split("\\.");
        return parts.length > 1 ? parts[1] : "x";
    }
}
