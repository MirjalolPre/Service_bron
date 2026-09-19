package pdp.service_bron.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.service.NotificationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailySummaryJobTest {

    private ShopRepository shops;
    private BarberRepository barbers;
    private BookingRepository bookings;
    private NotificationService notifications;
    private Shop shop;
    private Barber barber;

    @BeforeEach
    void setUp() {
        shops = mock(ShopRepository.class);
        barbers = mock(BarberRepository.class);
        bookings = mock(BookingRepository.class);
        notifications = mock(NotificationService.class);
        shop = new Shop();
        shop.setId(1L);
        shop.setTimezone("Asia/Tashkent"); // UTC+5
        shop.setActive(true);
        barber = new Barber();
        barber.setId(10L);
        barber.setUserId(100L);
        barber.setShopId(1L);
        when(shops.findAll()).thenReturn(List.of(shop));
        when(barbers.findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(barber));
    }

    private DailySummaryJob jobAt(String instant) {
        return new DailySummaryJob(shops, barbers, bookings, notifications, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private void bookingsToday(int count) {
        List<Booking> list = java.util.stream.IntStream.range(0, count).mapToObj(i -> new Booking()).toList();
        when(bookings.findForBarber(anyLong(), any(), any(), any())).thenReturn(list);
    }

    @Test
    void sendsTheSummaryAtEightShopTime() {
        bookingsToday(2);

        jobAt("2025-09-18T03:00:30Z").run(); // 08:00 Tashkent

        verify(notifications).dailySummary(any(), any(), any());
    }

    @Test
    void skipsBarbersWithoutBookings() {
        bookingsToday(0);

        jobAt("2025-09-18T03:01:00Z").run();

        verify(notifications, never()).dailySummary(any(), any(), any());
    }

    @Test
    void doesNothingOutsideTheMorningWindow() {
        bookingsToday(3);

        jobAt("2025-09-18T02:59:00Z").run(); // 07:59
        jobAt("2025-09-18T03:05:00Z").run(); // 08:05, window closed
        jobAt("2025-09-18T10:00:00Z").run(); // 15:00

        verify(notifications, never()).dailySummary(any(), any(), any());
    }

    @Test
    void sendsOnlyOncePerBarberPerDay() {
        bookingsToday(1);
        DailySummaryJob job = jobAt("2025-09-18T03:01:00Z");

        job.run();
        job.run();
        job.run();

        verify(notifications, times(1)).dailySummary(any(), any(), any());
    }

    @Test
    void inactiveShopsGetNothing() {
        shop.setActive(false);
        bookingsToday(2);

        jobAt("2025-09-18T03:01:00Z").run();

        verify(notifications, never()).dailySummary(any(), any(), any());
    }
}
