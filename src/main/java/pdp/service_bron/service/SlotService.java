package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.domain.TimeOff;
import pdp.service_bron.domain.WorkingHours;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.TimeOffRepository;
import pdp.service_bron.repository.WorkingHoursRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Core availability logic. Everything is evaluated in the shop's time zone.
 *
 * <p>A slot {@code [t, t + slot)} is free when it fits fully inside the working hours, does not overlap the break,
 * any time off or any BOOKED booking, and (for today) starts no earlier than {@code now + min_lead_minutes}.
 */
@Service
@RequiredArgsConstructor
public class SlotService {

    /** Show at most this many date buttons. */
    public static final int MAX_DATE_BUTTONS = 14;

    private final BarberRepository barbers;
    private final ShopRepository shops;
    private final WorkingHoursRepository workingHours;
    private final TimeOffRepository timeOff;
    private final BookingRepository bookings;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<LocalTime> freeSlots(Long barberId, LocalDate date) {
        Barber barber = barbers.findById(barberId).orElse(null);
        if (barber == null) {
            return List.of();
        }
        Shop shop = shops.findById(barber.getShopId()).orElse(null);
        if (shop == null) {
            return List.of();
        }
        return freeSlots(barber, shop, date, false);
    }

    /**
     * @param ignoreLeadTime true for manual bookings by the barber: walk-ins may start right now, only slots that
     *                       already started are hidden
     */
    @Transactional(readOnly = true)
    public List<LocalTime> freeSlots(Barber barber, Shop shop, LocalDate date, boolean ignoreLeadTime) {
        // 1. shop / barber / date range
        if (!shop.isActive() || !barber.isActive() || !barber.isAcceptingBookings()) {
            return List.of();
        }
        ZoneId zone = shop.zone();
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(shop.getBookingHorizonDays() - 1L))) {
            return List.of();
        }

        // 2. working hours of the weekday
        WorkingHours hours = workingHours.findByBarberIdAndDayOfWeek(barber.getId(), date.getDayOfWeek().getValue())
                .orElse(null);
        if (hours == null || hours.isDayOff() || hours.getStartTime() == null || hours.getEndTime() == null
                || !hours.getEndTime().isAfter(hours.getStartTime())) {
            return List.of();
        }

        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<TimeOff> timeOffs = timeOff.findOverlapping(barber.getId(), dayStart, dayEnd);
        List<Booking> booked = bookings.findBookedOverlapping(barber.getId(), dayStart, dayEnd);
        Instant earliest = date.equals(today)
                ? now.plus(Duration.ofMinutes(ignoreLeadTime ? 0 : shop.getMinLeadMinutes()))
                : null;

        int step = Math.max(1, barber.getSlotMinutes());
        List<LocalTime> result = new ArrayList<>();
        LocalTime start = hours.getStartTime();
        while (true) {
            LocalTime end = start.plusMinutes(step);
            // A slot that wraps past midnight, or ends after the working day, does not fit.
            if (!end.isAfter(start) || end.isAfter(hours.getEndTime())) {
                break;
            }
            if (isFree(start, end, date, zone, hours, timeOffs, booked, earliest)) {
                result.add(start);
            }
            start = end;
        }
        return result;
    }

    private boolean isFree(LocalTime start, LocalTime end, LocalDate date, ZoneId zone, WorkingHours hours,
                           List<TimeOff> timeOffs, List<Booking> booked, Instant earliest) {
        // 4. break (touching the edge is allowed)
        if (hours.hasBreak() && start.isBefore(hours.getBreakEnd()) && end.isAfter(hours.getBreakStart())) {
            return false;
        }
        Instant slotStart = date.atTime(start).atZone(zone).toInstant();
        Instant slotEnd = date.atTime(end).atZone(zone).toInstant();
        // 5. time off
        for (TimeOff off : timeOffs) {
            if (off.getStartAt().isBefore(slotEnd) && off.getEndAt().isAfter(slotStart)) {
                return false;
            }
        }
        // 6. real overlap with existing bookings (they may have a different length)
        for (Booking booking : booked) {
            if (booking.getStartAt().isBefore(slotEnd) && booking.getEndAt().isAfter(slotStart)) {
                return false;
            }
        }
        // 7. lead time (today only)
        return earliest == null || !slotStart.isBefore(earliest);
    }

    /** True when the exact slot is currently free. */
    @Transactional(readOnly = true)
    public boolean isFree(Barber barber, Shop shop, LocalDate date, LocalTime time, boolean ignoreLeadTime) {
        return freeSlots(barber, shop, date, ignoreLeadTime).contains(time);
    }

    /** Dates inside the booking horizon that have at least one free slot (at most 14). */
    @Transactional(readOnly = true)
    public List<LocalDate> availableDates(Long barberId) {
        Barber barber = barbers.findById(barberId).orElse(null);
        if (barber == null) {
            return List.of();
        }
        Shop shop = shops.findById(barber.getShopId()).orElse(null);
        if (shop == null) {
            return List.of();
        }
        return availableDates(barber, shop);
    }

    @Transactional(readOnly = true)
    public List<LocalDate> availableDates(Barber barber, Shop shop) {
        return availableDates(barber, shop, false);
    }

    /**
     * @param ignoreLeadTime true for manual bookings by the barber (walk-ins may start right now)
     */
    @Transactional(readOnly = true)
    public List<LocalDate> availableDates(Barber barber, Shop shop, boolean ignoreLeadTime) {
        LocalDate today = clock.instant().atZone(shop.zone()).toLocalDate();
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < shop.getBookingHorizonDays() && dates.size() < MAX_DATE_BUTTONS; i++) {
            LocalDate date = today.plusDays(i);
            if (!freeSlots(barber, shop, date, ignoreLeadTime).isEmpty()) {
                dates.add(date);
            }
        }
        return dates;
    }
}
