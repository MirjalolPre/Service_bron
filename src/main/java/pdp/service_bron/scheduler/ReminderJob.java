package pdp.service_bron.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingSource;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.service.NotificationService;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reminds clients before their booking (every minute). Idempotent: {@code reminder_sent} is set right after
 * handling a booking, so a booking is reminded at most once even when the job runs again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderJob {

    private final BookingRepository bookings;
    private final ShopRepository shops;
    private final NotificationService notifications;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")
    public void run() {
        Instant now = clock.instant();
        Map<Long, Shop> shopCache = new HashMap<>();
        List<Booking> pending = bookings.findPendingReminders(now);
        for (Booking booking : pending) {
            try {
                handle(booking, shopCache, now);
            } catch (RuntimeException e) {
                log.error("Reminder failed for booking {}", booking.getId(), e);
            }
        }
    }

    private void handle(Booking booking, Map<Long, Shop> shopCache, Instant now) {
        Shop shop = shopCache.computeIfAbsent(booking.getShopId(), id -> shops.findById(id).orElse(null));
        if (shop == null || shop.getReminderMinutesBefore() <= 0) {
            return; // reminders are switched off for this shop
        }
        Instant remindAt = booking.getStartAt().minusSeconds(shop.getReminderMinutesBefore() * 60L);
        if (remindAt.isAfter(now)) {
            return; // not yet
        }
        boolean createdInsideWindow = booking.getCreatedAt().isAfter(remindAt);
        if (booking.getSource() == BookingSource.BOT && !createdInsideWindow) {
            notifications.reminder(booking);
        }
        // Manual bookings have no Telegram client; bookings created inside the window need no reminder.
        bookings.markReminderSent(booking.getId());
    }
}
