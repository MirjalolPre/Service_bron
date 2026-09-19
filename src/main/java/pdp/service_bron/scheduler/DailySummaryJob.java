package pdp.service_bron.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.service.NotificationService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At 08:00 shop time every barber gets "you have N bookings today" with the timeline (skipped when there are
 * none). Runs every minute; acts in the first 5 minutes after 08:00 and remembers whom it already told today.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryJob {

    static final LocalTime SEND_AT = LocalTime.of(8, 0);
    private static final int WINDOW_MINUTES = 5;

    private final ShopRepository shops;
    private final BarberRepository barbers;
    private final BookingRepository bookings;
    private final NotificationService notifications;
    private final Clock clock;

    /** "barberId:date" of summaries already sent (a restart inside the window may repeat one). */
    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    @Scheduled(cron = "0 * * * * *")
    public void run() {
        Instant now = clock.instant();
        for (Shop shop : shops.findAll()) {
            if (!shop.isActive()) {
                continue;
            }
            try {
                runForShop(shop, now);
            } catch (RuntimeException e) {
                log.error("Daily summary failed for shop {}", shop.getId(), e);
            }
        }
    }

    private void runForShop(Shop shop, Instant now) {
        ZoneId zone = shop.zone();
        LocalTime local = now.atZone(zone).toLocalTime();
        if (local.isBefore(SEND_AT) || !local.isBefore(SEND_AT.plusMinutes(WINDOW_MINUTES))) {
            return;
        }
        LocalDate today = now.atZone(zone).toLocalDate();
        sent.removeIf(key -> !key.endsWith(":" + today));
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        for (Barber barber : barbers.findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(shop.getId())) {
            String key = barber.getId() + ":" + today;
            if (barber.getUserId() == null || sent.contains(key)) {
                continue;
            }
            List<Booking> todays = bookings.findForBarber(barber.getId(), from, to, EnumSet.of(BookingStatus.BOOKED));
            sent.add(key);
            if (!todays.isEmpty()) {
                notifications.dailySummary(barber, shop, todays);
            }
        }
    }
}
