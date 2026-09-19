package pdp.service_bron.scheduler;

import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.service.NotificationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttendanceJobTest {

    private static final Instant NOW = Instant.parse("2025-09-18T12:00:00Z");

    @Test
    void asksTheBarberAboutBookingsThatEndedMoreThan15MinutesAgo() {
        BookingRepository bookings = mock(BookingRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        Booking b = new Booking();
        b.setId(5L);
        // The repository query selects end_at <= now - 15 minutes; the job must pass exactly that threshold.
        when(bookings.findPendingAttendance(NOW.minusSeconds(15 * 60))).thenReturn(List.of(b));

        new AttendanceJob(bookings, notifications, Clock.fixed(NOW, ZoneOffset.UTC)).run();

        verify(notifications).attendanceQuestion(b);
        verify(bookings).markAttendanceAsked(5L);
    }

    @Test
    void doesNothingWhenNothingIsPending() {
        BookingRepository bookings = mock(BookingRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(bookings.findPendingAttendance(any())).thenReturn(List.of());

        new AttendanceJob(bookings, notifications, Clock.fixed(NOW, ZoneOffset.UTC)).run();

        verify(notifications, never()).attendanceQuestion(any());
    }

    @Test
    void aFailureIsNotMarkedAsAskedSoItIsRetried() {
        BookingRepository bookings = mock(BookingRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        Booking b = new Booking();
        b.setId(7L);
        when(bookings.findPendingAttendance(any())).thenReturn(List.of(b));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(notifications).attendanceQuestion(b);

        new AttendanceJob(bookings, notifications, Clock.fixed(NOW, ZoneOffset.UTC)).run();

        verify(bookings, never()).markAttendanceAsked(any());
    }
}
