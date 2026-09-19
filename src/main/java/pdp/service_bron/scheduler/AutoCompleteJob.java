package pdp.service_bron.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;

/**
 * Every night at 23:55 shop time, bookings that are still BOOKED in the past are marked COMPLETED (nobody
 * marked them as no-show, so the client is assumed to have come). Runs every 5 minutes and acts only inside
 * the 23:55 window of each shop's own time zone; the update is naturally idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoCompleteJob {

    static final LocalTime RUN_AT = LocalTime.of(23, 55);

    private final ShopRepository shops;
    private final BookingRepository bookings;
    private final Clock clock;

    @Scheduled(cron = "0 */5 * * * *")
    public void run() {
        Instant now = clock.instant();
        for (Shop shop : shops.findAll()) {
            try {
                if (!now.atZone(shop.zone()).toLocalTime().isBefore(RUN_AT)) {
                    int completed = bookings.completePast(shop.getId(), now);
                    if (completed > 0) {
                        log.info("Auto-completed {} past bookings in shop {}", completed, shop.getId());
                    }
                }
            } catch (RuntimeException e) {
                log.error("Auto-complete failed for shop {}", shop.getId(), e);
            }
        }
    }
}
