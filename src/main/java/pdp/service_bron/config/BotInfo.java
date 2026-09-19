package pdp.service_bron.config;

import org.springframework.stereotype.Component;

/** Holds the bot username (from settings, or resolved from Telegram at startup). */
@Component
public class BotInfo {

    private volatile String username;

    public BotInfo(BotProperties properties) {
        this.username = properties.telegram().username();
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    /** {@code https://t.me/<bot>?start=<payload>} */
    public String link(String payload) {
        return "https://t.me/" + username + "?start=" + payload;
    }
}
