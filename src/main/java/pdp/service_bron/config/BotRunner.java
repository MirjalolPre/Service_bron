package pdp.service_bron.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import pdp.service_bron.telegram.UpdateDispatcher;

/**
 * Registers the bot for long polling once the application is ready. The long-polling application itself is
 * closed by Spring on shutdown (see {@link TelegramConfig}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BotRunner {

    private final BotProperties properties;
    private final BotInfo botInfo;
    private final TelegramClient client;
    private final TelegramBotsLongPollingApplication longPolling;
    private final UpdateDispatcher dispatcher;
    private final BotCommandsSetup commandsSetup;

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws TelegramApiException {
        User me;
        try {
            me = client.execute(new GetMe());
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Cannot authorize the Telegram bot. Check TELEGRAM_BOT_TOKEN in .env "
                    + "(and that this machine can reach api.telegram.org): " + e.getMessage(), e);
        }
        if (botInfo.username() == null || botInfo.username().isBlank()) {
            botInfo.setUsername(me.getUserName());
        }
        log.info("Telegram bot @{} authorized", me.getUserName());

        // Long polling does not work while a webhook is set.
        client.execute(new DeleteWebhook());

        commandsSetup.install();

        longPolling.registerBot(properties.telegram().token(), dispatcher);
        log.info("Long polling started");
    }
}
