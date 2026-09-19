package pdp.service_bron.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pdp.service_bron.IntegrationTest;
import pdp.service_bron.TestDb;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SessionServiceIT {

    private static final long USER = 555;

    @Autowired SessionService sessions;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
    }

    @Test
    void unknownUsersStartIdle() {
        Session session = sessions.load(USER);

        assertThat(session.state()).isEqualTo(BotState.IDLE);
        assertThat(session.data()).isEmpty();
    }

    @Test
    void stateAndDataSurviveALoad() {
        sessions.set(USER, BotState.B_PRICE_VALUE, Map.of("name", "Soch olish", "wiz", "1"));

        Session session = sessions.load(USER);

        assertThat(session.state()).isEqualTo(BotState.B_PRICE_VALUE);
        assertThat(session.get("name")).isEqualTo("Soch olish");
        assertThat(session.get("wiz")).isEqualTo("1");
    }

    @Test
    void textWithQuotesAndUnicodeIsStoredSafely() {
        String tricky = "O'zbek \"quote\" \\ backslash — Русский 💈";
        sessions.set(USER, BotState.A_SHOP_NAME, Map.of("value", tricky));

        assertThat(sessions.load(USER).get("value")).isEqualTo(tricky);
    }

    @Test
    void setReplacesEarlierDataButKeepsStickyKeys() {
        sessions.setClientMode(USER, true);
        sessions.set(USER, BotState.B_NAME, Map.of("a", "1"));
        sessions.set(USER, BotState.B_BIO, Map.of("b", "2"));

        Session session = sessions.load(USER);

        assertThat(session.state()).isEqualTo(BotState.B_BIO);
        assertThat(session.has("a")).isFalse();
        assertThat(session.get("b")).isEqualTo("2");
        assertThat(session.isClientMode()).isTrue();
    }

    @Test
    void clearResetsTheStateButKeepsTheMode() {
        sessions.setClientMode(USER, true);
        sessions.set(USER, BotState.B_NAME, Map.of("a", "1"));

        sessions.clear(USER);

        Session session = sessions.load(USER);
        assertThat(session.state()).isEqualTo(BotState.IDLE);
        assertThat(session.has("a")).isFalse();
        assertThat(session.isClientMode()).isTrue();
    }

    @Test
    void modeCanBeSwitchedBackToStaff() {
        sessions.setClientMode(USER, true);
        sessions.setClientMode(USER, false);

        assertThat(sessions.load(USER).isClientMode()).isFalse();
    }

    @Test
    void putAddsAndRemovesSingleEntries() {
        sessions.set(USER, BotState.C_SEARCH, Map.of("a", "1"));
        sessions.put(USER, "b", "2");
        assertThat(sessions.load(USER).data()).containsEntry("a", "1").containsEntry("b", "2");

        sessions.put(USER, "a", null);
        assertThat(sessions.load(USER).data()).doesNotContainKey("a").containsEntry("b", "2");
        assertThat(sessions.load(USER).state()).isEqualTo(BotState.C_SEARCH);
    }

    @Test
    void sessionsOfDifferentUsersAreIndependent() {
        sessions.set(1, BotState.B_NAME, Map.of("x", "1"));
        sessions.set(2, BotState.O_TEXT, Map.of("x", "2"));

        assertThat(sessions.load(1).state()).isEqualTo(BotState.B_NAME);
        assertThat(sessions.load(2).get("x")).isEqualTo("2");
    }

    @Test
    void staleSessionsAreDeleted() {
        sessions.set(1, BotState.B_NAME);
        sessions.set(2, BotState.B_NAME);
        jdbc.sql("update bot_session set updated_at = now() - interval '25 hours' where telegram_id = 1").update();

        int deleted = sessions.deleteOlderThan(Duration.ofHours(24));

        assertThat(deleted).isEqualTo(1);
        assertThat(sessions.load(1).state()).isEqualTo(BotState.IDLE);
        assertThat(sessions.load(2).state()).isEqualTo(BotState.B_NAME);
    }

    @Test
    void anUnknownStateInTheDatabaseFallsBackToIdle() {
        jdbc.sql("insert into bot_session (telegram_id, state, data) values (77, 'REMOVED_STATE', '{}'::jsonb)").update();

        assertThat(sessions.load(77).state()).isEqualTo(BotState.IDLE);
    }
}
