package pdp.service_bron.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conversation state stored in the {@code bot_session} table (jsonb). Uses plain SQL so the JSON handling
 * stays under our control (Jackson 3).
 */
@Slf4j
@Service
public class SessionService {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public SessionService(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public Session load(long telegramId) {
        return jdbc.sql("select state, data::text as data from bot_session where telegram_id = :id")
                .param("id", telegramId)
                .query((rs, n) -> new Session(telegramId, parseState(rs.getString("state")), parseData(rs.getString("data"))))
                .optional()
                .orElseGet(() -> new Session(telegramId, BotState.IDLE, Map.of()));
    }

    /** Replaces state and data. Sticky ({@code _}-prefixed) keys of the previous data are kept. */
    public void set(long telegramId, BotState state, Map<String, String> newData) {
        Session current = load(telegramId);
        Map<String, String> merged = new LinkedHashMap<>();
        current.data().forEach((k, v) -> {
            if (k.startsWith(Session.STICKY_PREFIX)) {
                merged.put(k, v);
            }
        });
        merged.putAll(newData);
        save(telegramId, state, merged);
    }

    public void set(long telegramId, BotState state) {
        set(telegramId, state, Map.of());
    }

    /** Resets to {@link BotState#IDLE} and drops all non-sticky data. */
    public void clear(long telegramId) {
        set(telegramId, BotState.IDLE, Map.of());
    }

    /** Adds or replaces one data entry, keeping the state. */
    public void put(long telegramId, String key, String value) {
        Session current = load(telegramId);
        Map<String, String> merged = new HashMap<>(current.data());
        if (value == null) {
            merged.remove(key);
        } else {
            merged.put(key, value);
        }
        save(telegramId, current.state(), merged);
    }

    /** Switches between client mode and staff mode (sticky). */
    public void setClientMode(long telegramId, boolean clientMode) {
        put(telegramId, Session.KEY_MODE, clientMode ? Session.MODE_CLIENT : null);
    }

    /** Deletes sessions that were not touched for the given time. Returns the number of deleted rows. */
    public int deleteOlderThan(Duration age) {
        OffsetDateTime threshold = OffsetDateTime.ofInstant(clock.instant().minus(age), ZoneOffset.UTC);
        return jdbc.sql("delete from bot_session where updated_at < :threshold")
                .param("threshold", threshold)
                .update();
    }

    private void save(long telegramId, BotState state, Map<String, String> data) {
        jdbc.sql("""
                        insert into bot_session (telegram_id, state, data, updated_at)
                        values (:id, :state, cast(:data as jsonb), :now)
                        on conflict (telegram_id) do update
                        set state = excluded.state, data = excluded.data, updated_at = excluded.updated_at""")
                .param("id", telegramId)
                .param("state", state.name())
                .param("data", json.writeValueAsString(data))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private BotState parseState(String value) {
        try {
            return BotState.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown bot state '{}', resetting to IDLE", value);
            return BotState.IDLE;
        }
    }

    private Map<String, String> parseData(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return json.readValue(value, MAP_TYPE);
    }
}
