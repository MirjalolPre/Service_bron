package pdp.service_bron;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Cleans the shared test database between tests. */
public final class TestDb {

    private TestDb() {
    }

    public static void clean(JdbcClient jdbc) {
        jdbc.sql("truncate booking, invite, time_off, working_hours, price_item, barber, bot_session, "
                + "app_user, shop restart identity cascade").update();
    }
}
