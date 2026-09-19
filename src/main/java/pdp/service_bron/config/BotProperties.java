package pdp.service_bron.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Application settings. Values come from environment variables or a local {@code .env} file
 * (see application.properties).
 */
@ConfigurationProperties(prefix = "app")
public record BotProperties(
        @DefaultValue Telegram telegram,
        @DefaultValue List<Long> superAdminIds,
        @DefaultValue("Asia/Tashkent") String defaultTimezone) {

    public record Telegram(
            @DefaultValue("") String token,
            @DefaultValue("") String username,
            @DefaultValue("true") boolean enabled) {
    }

    public boolean isSuperAdmin(long telegramId) {
        return superAdminIds.contains(telegramId);
    }
}
