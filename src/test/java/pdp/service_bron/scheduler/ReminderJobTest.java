package pdp.service_bron.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingSource;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.service.NotificationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReminderJobTest {

    private static final Instant NOW = Instant.parse("2025-09-18T05:00:00Z");

    private BookingRepository bookings;
    private ShopRepository shops;
    private NotificationService notifications;
    private Shop shop;
    private ReminderJob job;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        shops = mock(ShopRepository.class);
        notifications = mock(NotificationService.class);
        shop = new Shop();
        shop.setId(1L);
        shop.setReminderMinutesBefore(120);
        when(shops.findById(1L)).thenReturn(Optional.of(shop));
        job = new ReminderJob(bookings, shops, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Booking booking(long id, Instant start, Instant createdAt, BookingSource source) {
        Booking b = new Booking();
        b.setId(id);
        b.setShopId(1L);
        b.setStartAt(start);
        b.setEndAt(start.plusSeconds(1800));
        b.setCreatedAt(createdAt);
        b.setSource(source);
        return b;
    }

    private void pending(Booking... list) {
        when(bookings.findPendingReminders(NOW)).thenReturn(List.of(list));
    }

    @Test
    void sendsTheReminderWhenTheTimeHasCome() {
        // starts in 90 minutes, reminder is 120 minutes before -> due since 30 minutes; booked yesterday
        Booking b = booking(1, NOW.plusSeconds(90 * 60), NOW.minusSeconds(86400), BookingSource.BOT);
        pending(b);

        job.run();

        verify(notifications).reminder(b);
        verify(bookings).markReminderSent(1L);
    }

    @Test
    void sendsExactlyAtTheReminderMoment() {
        Booking b = booking(1, NOW.plusSeconds(120 * 60), NOW.minusSeconds(86400), BookingSource.BOT);
        pending(b);

        job.run();

        verify(notifications).reminder(b);
    }

    @Test
    void doesNothingBeforeTheReminderMoment() {
        Booking b = booking(1, NOW.plusSeconds(180 * 60), NOW.minusSeconds(86400), BookingSource.BOT);
        pending(b);

        job.run();

        verify(notifications, never()).reminder(any());
        verify(bookings, never()).markReminderSent(any());
    }

    @Test
    void bookingCreatedInsideTheReminderWindowGetsNoReminderButIsMarked() {
        // starts in 60 min, window began 60 min ago, booked 10 minutes ago
        Booking b = booking(1, NOW.plusSeconds(60 * 60), NOW.minusSeconds(10 * 60), BookingSource.BOT);
        pending(b);

        job.run();

        verify(notifications, never()).reminder(any());
        verify(bookings).markReminderSent(1L);
    }

    @Test
    void manualBookingsAreNeverRemindedButAreMarked() {
        Booking b = booking(1, NOW.plusSeconds(60 * 60), NOW.minusSeconds(86400), BookingSource.MANUAL);
        pending(b);

        job.run();

        verify(notifications, never()).reminder(any());
        verify(bookings).markReminderSent(1L);
    }

    @Test
    void shopsWithRemindersOffAreSkipped() {
        shop.setReminderMinutesBefore(0);
        Booking b = booking(1, NOW.plusSeconds(60), NOW.minusSeconds(86400), BookingSource.BOT);
        pending(b);

        job.run();

        verify(notifications, never()).reminder(any());
        verify(bookings, never()).markReminderSent(any());
    }

    @Test
    void oneFailingBookingDoesNotStopTheOthers() {
        Booking first = booking(1, NOW.plusSeconds(60 * 60), NOW.minusSeconds(86400), BookingSource.BOT);
        Booking second = booking(2, NOW.plusSeconds(60 * 60), NOW.minusSeconds(86400), BookingSource.BOT);
        pending(first, second);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(notifications).reminder(first);

        job.run();

        verify(notifications).reminder(second);
        verify(bookings).markReminderSent(2L);
        verify(bookings, never()).markReminderSent(1L);
    }
}
