package pdp.service_bron.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class TelegramConfig {

    @Bean
    public TelegramClient telegramClient(BotProperties properties) {
        String token = properties.telegram().token();
        if (properties.telegram().enabled() && (token == null || token.isBlank())) {
            throw new IllegalStateException(
                    "TELEGRAM_BOT_TOKEN is empty. Get a token from @BotFather and put it into the .env file "
                            + "(TELEGRAM_BOT_TOKEN=123456:ABC...) or into the environment variables.");
        }
        return new OkHttpTelegramClient(token == null || token.isBlank() ? "disabled" : token);
    }

    /** One long-polling application for the whole process. Closed automatically on shutdown. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TelegramBotsLongPollingApplication longPollingApplication() {
        return new TelegramBotsLongPollingApplication();
    }
}
