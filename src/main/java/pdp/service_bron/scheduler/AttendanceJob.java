package pdp.service_bron.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.service.NotificationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 15 minutes after a booking ended, asks the barber whether the client came (every minute, idempotent). */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceJob {

    static final Duration GRACE = Duration.ofMinutes(15);

    private final BookingRepository bookings;
    private final NotificationService notifications;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")
    public void run() {
        Instant threshold = clock.instant().minus(GRACE);
        for (Booking booking : bookings.findPendingAttendance(threshold)) {
            try {
                notifications.attendanceQuestion(booking);
                bookings.markAttendanceAsked(booking.getId());
            } catch (RuntimeException e) {
                log.error("Attendance question failed for booking {}", booking.getId(), e);
            }
        }
    }
}
