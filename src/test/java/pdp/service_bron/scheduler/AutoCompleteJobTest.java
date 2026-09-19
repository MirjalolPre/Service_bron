package pdp.service_bron.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoCompleteJobTest {

    private ShopRepository shops;
    private BookingRepository bookings;
    private Shop tashkent;

    @BeforeEach
    void setUp() {
        shops = mock(ShopRepository.class);
        bookings = mock(BookingRepository.class);
        tashkent = new Shop();
        tashkent.setId(1L);
        tashkent.setTimezone("Asia/Tashkent"); // UTC+5
        when(shops.findAll()).thenReturn(List.of(tashkent));
    }

    private void runAt(String instant) {
        new AutoCompleteJob(shops, bookings, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)).run();
    }

    @Test
    void completesPastBookingsAtTwentyThreeFiftyFiveShopTime() {
        Instant at = Instant.parse("2025-09-18T18:55:00Z"); // 23:55 in Tashkent
        runAt("2025-09-18T18:55:00Z");

        verify(bookings).completePast(1L, at);
    }

    @Test
    void doesNothingDuringTheDay() {
        runAt("2025-09-18T07:00:00Z"); // 12:00 in Tashkent
        runAt("2025-09-18T18:50:00Z"); // 23:50 in Tashkent

        verify(bookings, never()).completePast(anyLong(), any());
    }

    @Test
    void usesEachShopsOwnTimeZone() {
        Shop moscow = new Shop();
        moscow.setId(2L);
        moscow.setTimezone("Europe/Moscow"); // UTC+3
        when(shops.findAll()).thenReturn(List.of(tashkent, moscow));

        runAt("2025-09-18T20:55:00Z"); // 23:55 Moscow, 01:55 next day Tashkent

        verify(bookings).completePast(2L, Instant.parse("2025-09-18T20:55:00Z"));
    }
}
