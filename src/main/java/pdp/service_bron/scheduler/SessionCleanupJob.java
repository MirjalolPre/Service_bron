package pdp.service_bron.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pdp.service_bron.session.SessionService;

import java.time.Duration;

/** Hourly: deletes conversation sessions that were not touched for 24 hours. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupJob {

    static final Duration MAX_AGE = Duration.ofHours(24);

    private final SessionService sessions;

    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        int deleted = sessions.deleteOlderThan(MAX_AGE);
        if (deleted > 0) {
            log.info("Deleted {} stale bot sessions", deleted);
        }
    }
}
