package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.domain.TimeOff;
import pdp.service_bron.domain.WorkingHours;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.TimeOffRepository;
import pdp.service_bron.repository.WorkingHoursRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Working hours, breaks and blocked time. A working day never crosses midnight (end must be after start). */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** How the schedule of one weekday should look. */
    public record DaySchedule(boolean dayOff, LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {

        public static DaySchedule off() {
            return new DaySchedule(true, null, null, null, null);
        }

        public static DaySchedule work(LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {
            return new DaySchedule(false, start, end, breakStart, breakEnd);
        }

        public boolean hasBreak() {
            return breakStart != null && breakEnd != null;
        }

        /** Rejects end &lt;= start (no shifts across midnight) and a break that is not inside the working hours. */
        public void validate() {
            if (dayOff) {
                return;
            }
            if (start == null || end == null || !end.isAfter(start)) {
                throw new BusinessException("hours.invalid_range");
            }
            if ((breakStart == null) != (breakEnd == null)) {
                throw new BusinessException("hours.invalid_break");
            }
            if (hasBreak() && (!breakEnd.isAfter(breakStart) || breakStart.isBefore(start) || breakEnd.isAfter(end))) {
                throw new BusinessException("hours.invalid_break");
            }
        }
    }

    /** A blocked period as instants. */
    public record BlockedRange(Instant start, Instant end) {
    }

    /** Quick presets for the setup wizard and the hours screen. */
    public static final int PRESET_MON_SAT = 1;
    public static final int PRESET_EVERY_DAY = 2;

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(20, 0);

    private final WorkingHoursRepository workingHours;
    private final TimeOffRepository timeOffs;
    private final BookingRepository bookings;
    private final BarberRepository barbers;
    private final ShopRepository shops;
    private final BookingService bookingService;
    private final AccessService access;
    private final Clock clock;

    // ------------------------------------------------------------------ working hours

    /** The stored schedule by ISO weekday (missing weekdays are not configured yet). */
    @Transactional(readOnly = true)
    public Map<Integer, WorkingHours> week(Long barberId) {
        Map<Integer, WorkingHours> result = new TreeMap<>();
        for (WorkingHours hours : workingHours.findByBarberIdOrderByDayOfWeekAsc(barberId)) {
            result.put(hours.getDayOfWeek(), hours);
        }
        return result;
    }

    /** Preset schedules: 1 = 09:00-20:00 Monday-Saturday with Sunday off; 2 = 10:00-21:00 every day. */
    public Map<Integer, DaySchedule> preset(int preset) {
        Map<Integer, DaySchedule> plan = new TreeMap<>();
        for (int dow = 1; dow <= 7; dow++) {
            if (preset == PRESET_EVERY_DAY) {
                plan.put(dow, DaySchedule.work(LocalTime.of(10, 0), LocalTime.of(21, 0), null, null));
            } else {
                plan.put(dow, dow == 7 ? DaySchedule.off() : DaySchedule.work(DEFAULT_START, DEFAULT_END, null, null));
            }
        }
        return plan;
    }

    /** The current schedule of one weekday as a {@link DaySchedule} (day off when nothing is stored). */
    @Transactional(readOnly = true)
    public DaySchedule day(Long barberId, int dayOfWeek) {
        return workingHours.findByBarberIdAndDayOfWeek(barberId, dayOfWeek)
                .map(ScheduleService::toSchedule)
                .orElse(DaySchedule.off());
    }

    /** Plan that puts one break on every day that is currently a working day. */
    @Transactional(readOnly = true)
    public Map<Integer, DaySchedule> withBreakOnWorkdays(Long barberId, LocalTime breakStart, LocalTime breakEnd) {
        Map<Integer, DaySchedule> plan = new TreeMap<>();
        for (WorkingHours hours : workingHours.findByBarberIdOrderByDayOfWeekAsc(barberId)) {
            if (!hours.isDayOff()) {
                plan.put(hours.getDayOfWeek(), DaySchedule.work(hours.getStartTime(), hours.getEndTime(), breakStart, breakEnd));
            }
        }
        return plan;
    }

    /** Plan that copies the schedule of {@code sourceDay} onto every working day. */
    @Transactional(readOnly = true)
    public Map<Integer, DaySchedule> copyToWorkdays(Long barberId, int sourceDay) {
        DaySchedule source = day(barberId, sourceDay);
        if (source.dayOff()) {
            throw new BusinessException("hours.source_is_off");
        }
        Map<Integer, DaySchedule> plan = new TreeMap<>();
        for (WorkingHours hours : workingHours.findByBarberIdOrderByDayOfWeekAsc(barberId)) {
            if (!hours.isDayOff()) {
                plan.put(hours.getDayOfWeek(), source);
            }
        }
        plan.put(sourceDay, source);
        return plan;
    }

    /**
     * Future BOOKED bookings that would end up outside the working hours (or inside the break, or on a day off)
     * if {@code plan} were applied. Such bookings are never cancelled automatically; the barber is only warned.
     */
    @Transactional(readOnly = true)
    public List<Booking> conflicts(Long barberId, Map<Integer, DaySchedule> plan) {
        Barber barber = barbers.findById(barberId).orElse(null);
        Shop shop = barber == null ? null : shops.findById(barber.getShopId()).orElse(null);
        if (shop == null) {
            return List.of();
        }
        ZoneId zone = shop.zone();
        Instant now = clock.instant();
        List<Booking> future = bookings.findForBarber(barberId, now, now.plusSeconds(400L * 24 * 3600),
                EnumSet.of(BookingStatus.BOOKED));
        List<Booking> result = new ArrayList<>();
        for (Booking booking : future) {
            ZonedDateTime start = booking.getStartAt().atZone(zone);
            ZonedDateTime end = booking.getEndAt().atZone(zone);
            DaySchedule schedule = plan.get(start.getDayOfWeek().getValue());
            if (schedule == null) {
                continue;
            }
            if (schedule.dayOff()) {
                result.add(booking);
                continue;
            }
            LocalTime s = start.toLocalTime();
            LocalTime e = end.toLocalDate().equals(start.toLocalDate()) ? end.toLocalTime() : LocalTime.MAX;
            boolean outside = s.isBefore(schedule.start()) || e.isAfter(schedule.end());
            boolean inBreak = schedule.hasBreak() && s.isBefore(schedule.breakEnd()) && e.isAfter(schedule.breakStart());
            if (outside || inBreak) {
                result.add(booking);
            }
        }
        return result;
    }

    /**
     * Saves the given weekday schedules. Existing bookings are kept. The first time working hours appear, the
     * barber automatically starts accepting bookings.
     */
    @Transactional
    public void apply(AppUser actor, Long barberId, Map<Integer, DaySchedule> plan) {
        if (!access.canManageBarber(actor, barberId)) {
            throw new BusinessException("error.forbidden");
        }
        Barber barber = barbers.findById(barberId).orElseThrow(() -> new BusinessException("error.not_found"));
        boolean hadHours = workingHours.countByBarberId(barberId) > 0;
        plan.forEach((dow, schedule) -> {
            if (dow < 1 || dow > 7) {
                throw new BusinessException("error.invalid_value");
            }
            schedule.validate();
        });
        for (Map.Entry<Integer, DaySchedule> entry : plan.entrySet()) {
            DaySchedule schedule = entry.getValue();
            WorkingHours row = workingHours.findByBarberIdAndDayOfWeek(barberId, entry.getKey()).orElseGet(() -> {
                WorkingHours created = new WorkingHours();
                created.setBarberId(barberId);
                created.setDayOfWeek(entry.getKey());
                return created;
            });
            row.setDayOff(schedule.dayOff());
            row.setStartTime(schedule.dayOff() ? row.getStartTime() : schedule.start());
            row.setEndTime(schedule.dayOff() ? row.getEndTime() : schedule.end());
            row.setBreakStart(schedule.dayOff() ? row.getBreakStart() : schedule.breakStart());
            row.setBreakEnd(schedule.dayOff() ? row.getBreakEnd() : schedule.breakEnd());
            workingHours.save(row);
        }
        boolean anyWorkday = workingHours.findByBarberIdOrderByDayOfWeekAsc(barberId).stream().anyMatch(h -> !h.isDayOff());
        if (!hadHours && anyWorkday && !barber.isAcceptingBookings()) {
            barber.setAcceptingBookings(true);
            barbers.save(barber);
        }
    }

    /** True when the barber has at least one working day. */
    @Transactional(readOnly = true)
    public boolean hasWorkingDay(Long barberId) {
        return workingHours.findByBarberIdOrderByDayOfWeekAsc(barberId).stream().anyMatch(h -> !h.isDayOff());
    }

    /** Default times used when a day off is turned back into a working day without stored times. */
    public DaySchedule defaultWorkday() {
        return DaySchedule.work(DEFAULT_START, DEFAULT_END, null, null);
    }

    private static DaySchedule toSchedule(WorkingHours hours) {
        if (hours.isDayOff() || hours.getStartTime() == null || hours.getEndTime() == null) {
            return DaySchedule.off();
        }
        return DaySchedule.work(hours.getStartTime(), hours.getEndTime(), hours.getBreakStart(), hours.getBreakEnd());
    }

    // ------------------------------------------------------------------ blocked time

    /** Converts a local date and optional times (null = the whole day) into instants in the shop's time zone. */
    @Transactional(readOnly = true)
    public BlockedRange range(Barber barber, LocalDate date, LocalTime from, LocalTime to) {
        Shop shop = shops.findById(barber.getShopId()).orElseThrow(() -> new BusinessException("error.not_found"));
        ZoneId zone = shop.zone();
        if (from == null || to == null) {
            return new BlockedRange(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
        }
        if (!to.isAfter(from)) {
            throw new BusinessException("hours.invalid_range");
        }
        return new BlockedRange(date.atTime(from).atZone(zone).toInstant(), date.atTime(to).atZone(zone).toInstant());
    }

    /** BOOKED bookings inside the range that closing the time would collide with. */
    @Transactional(readOnly = true)
    public List<Booking> bookingsInside(Barber barber, LocalDate date, LocalTime from, LocalTime to) {
        BlockedRange range = range(barber, date, from, to);
        return bookingService.bookedOverlapping(barber.getId(), range.start(), range.end());
    }

    /**
     * Blocks time so nobody can book it. When BOOKED bookings overlap, {@code cancelBookings} must be true:
     * they are cancelled (the caller notifies the clients). Returns the cancelled bookings.
     */
    @Transactional
    public List<Booking> closeTime(AppUser actor, Long barberId, LocalDate date, LocalTime from, LocalTime to,
                                   boolean cancelBookings) {
        if (!access.canManageBarber(actor, barberId)) {
            throw new BusinessException("error.forbidden");
        }
        Barber barber = barbers.findById(barberId).orElseThrow(() -> new BusinessException("error.not_found"));
        BlockedRange range = range(barber, date, from, to);
        if (!range.end().isAfter(clock.instant())) {
            throw new BusinessException("timeoff.in_past");
        }
        List<Booking> cancelled = List.of();
        if (!bookingService.bookedOverlapping(barberId, range.start(), range.end()).isEmpty()) {
            if (!cancelBookings) {
                throw new BusinessException("timeoff.has_bookings");
            }
            cancelled = bookingService.cancelOverlappingByBarber(barberId, actor, range.start(), range.end(), null);
        }
        TimeOff off = new TimeOff();
        off.setBarberId(barberId);
        off.setStartAt(range.start());
        off.setEndAt(range.end());
        timeOffs.save(off);
        return cancelled;
    }

    /** Upcoming (not yet ended) blocked periods, soonest first. */
    @Transactional(readOnly = true)
    public List<TimeOff> upcomingTimeOff(Long barberId) {
        return timeOffs.findByBarberIdAndEndAtAfterOrderByStartAtAsc(barberId, clock.instant());
    }

    @Transactional
    public void deleteTimeOff(AppUser actor, Long timeOffId) {
        TimeOff off = timeOffs.findById(timeOffId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!access.canManageBarber(actor, off.getBarberId())) {
            throw new BusinessException("error.forbidden");
        }
        timeOffs.delete(off);
    }
}
