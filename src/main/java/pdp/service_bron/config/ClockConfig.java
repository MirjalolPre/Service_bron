package pdp.service_bron.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Single time source for the whole app. Services never call {@code now()} directly. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
