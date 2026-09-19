package pdp.service_bron;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ServiceBronApplicationTests {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndFlywayCreatedTheSchema() {
        Integer tables = jdbc.sql("""
                        select count(*) from information_schema.tables
                        where table_schema = 'public'
                          and table_name in ('app_user','shop','barber','price_item','working_hours',
                                             'time_off','booking','invite','bot_session')""")
                .query(Integer.class).single();
        assertThat(tables).isEqualTo(9);
    }
}
