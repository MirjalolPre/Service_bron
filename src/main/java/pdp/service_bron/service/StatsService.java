package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.config.BotProperties;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.AppUserRepository;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    /** Platform-wide numbers for the super admin. */
    public record GlobalStats(long activeShops, long inactiveShops, long barbers, long clients,
                              long bookingsToday, long bookingsWeek, long bookingsMonth) {
    }

    /** Reporting period of the barber statistics. */
    public enum Period { WEEK, MONTH }

    /** Numbers for one barber and period. {@code busiestDay} is null when there are no bookings. */
    public record BarberStats(long total, long booked, long completed, long noShow, long cancelledByClient,
                              long cancelledByBarber, DayOfWeek busiestDay, long busiestDayCount) {
    }

    private final ShopRepository shops;
    private final BarberRepository barbers;
    private final AppUserRepository users;
    private final BookingRepository bookings;
    private final BotProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public GlobalStats global() {
        ZoneId zone = ZoneId.of(properties.defaultTimezone());
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monthStart = today.withDayOfMonth(1);
        return new GlobalStats(
                shops.countByActive(true),
                shops.countByActive(false),
                barbers.countByActive(true),
                users.count(),
                bookings.countScheduled(start(today, zone), start(today.plusDays(1), zone)),
                bookings.countScheduled(start(weekStart, zone), start(weekStart.plusDays(7), zone)),
                bookings.countScheduled(start(monthStart, zone), start(monthStart.plusMonths(1), zone)));
    }

    private static Instant start(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** Bookings of a barber this week (Monday to Sunday) or this month, in the shop's time zone. */
    @Transactional(readOnly = true)
    public BarberStats barberStats(Barber barber, Shop shop, Period period) {
        ZoneId zone = shop.zone();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate from;
        LocalDate to;
        if (period == Period.WEEK) {
            from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            to = from.plusDays(7);
        } else {
            from = today.withDayOfMonth(1);
            to = from.plusMonths(1);
        }
        List<Booking> all = bookings.findForBarber(barber.getId(), start(from, zone), start(to, zone),
                EnumSet.allOf(BookingStatus.class));
        Map<BookingStatus, Long> byStatus = new EnumMap<>(BookingStatus.class);
        Map<DayOfWeek, Long> byDay = new EnumMap<>(DayOfWeek.class);
        for (Booking booking : all) {
            byStatus.merge(booking.getStatus(), 1L, Long::sum);
            boolean held = booking.getStatus() == BookingStatus.BOOKED || booking.getStatus() == BookingStatus.COMPLETED
                    || booking.getStatus() == BookingStatus.NO_SHOW;
            if (held) {
                byDay.merge(booking.getStartAt().atZone(zone).getDayOfWeek(), 1L, Long::sum);
            }
        }
        DayOfWeek busiest = null;
        long busiestCount = 0;
        for (Map.Entry<DayOfWeek, Long> entry : byDay.entrySet()) {
            if (entry.getValue() > busiestCount) {
                busiest = entry.getKey();
                busiestCount = entry.getValue();
            }
        }
        return new BarberStats(all.size(),
                byStatus.getOrDefault(BookingStatus.BOOKED, 0L),
                byStatus.getOrDefault(BookingStatus.COMPLETED, 0L),
                byStatus.getOrDefault(BookingStatus.NO_SHOW, 0L),
                byStatus.getOrDefault(BookingStatus.CANCELLED_BY_CLIENT, 0L),
                byStatus.getOrDefault(BookingStatus.CANCELLED_BY_BARBER, 0L),
                busiest, busiestCount);
    }
}
