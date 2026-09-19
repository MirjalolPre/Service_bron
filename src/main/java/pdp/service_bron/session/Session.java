package pdp.service_bron.session;

import java.util.HashMap;
import java.util.Map;

/** Snapshot of one user's conversation: the current state plus small string data. */
public final class Session {

    /** Keys starting with this prefix survive state changes (for example the client/staff mode). */
    public static final String STICKY_PREFIX = "_";
    public static final String KEY_MODE = "_mode";
    public static final String MODE_CLIENT = "client";

    private final long telegramId;
    private BotState state;
    private final Map<String, String> data;

    public Session(long telegramId, BotState state, Map<String, String> data) {
        this.telegramId = telegramId;
        this.state = state;
        this.data = new HashMap<>(data);
    }

    public long telegramId() {
        return telegramId;
    }

    public BotState state() {
        return state;
    }

    public Map<String, String> data() {
        return data;
    }

    public String get(String key) {
        return data.get(key);
    }

    public String get(String key, String defaultValue) {
        return data.getOrDefault(key, defaultValue);
    }

    public Long getLong(String key) {
        String value = data.get(key);
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public boolean isClientMode() {
        return MODE_CLIENT.equals(data.get(KEY_MODE));
    }
}
