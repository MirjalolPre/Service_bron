package pdp.service_bron.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for every rule of the slot generation. "Now" is Wednesday 2025-09-17 10:00 in Tashkent (UTC+5).
 */
class SlotServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    private static final LocalDate TODAY = LocalDate.of(2025, 9, 17);      // Wednesday
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    private BarberRepository barbers;
    private ShopRepository shops;
    private WorkingHoursRepository workingHours;
    private TimeOffRepository timeOff;
    private BookingRepository bookings;

    private Shop shop;
    private Barber barber;
    private WorkingHours hours;
    private List<TimeOff> timeOffs;
    private List<Booking> booked;
    private Instant now;

    @BeforeEach
    void setUp() {
        barbers = mock(BarberRepository.class);
        shops = mock(ShopRepository.class);
        workingHours = mock(WorkingHoursRepository.class);
        timeOff = mock(TimeOffRepository.class);
        bookings = mock(BookingRepository.class);

        shop = new Shop();
        shop.setId(1L);
        shop.setTimezone(ZONE.getId());
        shop.setBookingHorizonDays(7);
        shop.setMinLeadMinutes(30);
        shop.setActive(true);

        barber = new Barber();
        barber.setId(10L);
        barber.setShopId(1L);
        barber.setSlotMinutes(30);
        barber.setActive(true);
        barber.setAcceptingBookings(true);

        hours = hours(LocalTime.of(9, 0), LocalTime.of(18, 0), LocalTime.of(13, 0), LocalTime.of(14, 0));
        timeOffs = new ArrayList<>();
        booked = new ArrayList<>();
        now = at(TODAY, 10, 0);

        when(barbers.findById(10L)).thenReturn(Optional.of(barber));
        when(shops.findById(1L)).thenReturn(Optional.of(shop));
        when(workingHours.findByBarberIdAndDayOfWeek(anyLong(), anyInt())).thenAnswer(inv -> Optional.ofNullable(hours));
        when(timeOff.findOverlapping(anyLong(), any(), any())).thenAnswer(inv -> timeOffs);
        when(bookings.findBookedOverlapping(anyLong(), any(), any())).thenAnswer(inv -> booked);
    }

    private SlotService service() {
        return new SlotService(barbers, shops, workingHours, timeOff, bookings, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static WorkingHours hours(LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {
        WorkingHours h = new WorkingHours();
        h.setBarberId(10L);
        h.setDayOff(false);
        h.setStartTime(start);
        h.setEndTime(end);
        h.setBreakStart(breakStart);
        h.setBreakEnd(breakEnd);
        return h;
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private static LocalTime t(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    private Booking booking(LocalDate date, LocalTime start, LocalTime end, BookingStatus status) {
        Booking b = new Booking();
        b.setBarberId(10L);
        b.setStartAt(date.atTime(start).atZone(ZONE).toInstant());
        b.setEndAt(date.atTime(end).atZone(ZONE).toInstant());
        b.setStatus(status);
        return b;
    }

    private List<LocalTime> slots(LocalDate date) {
        return service().freeSlots(10L, date);
    }

    // ---------------------------------------------------------------- rule 1: availability of shop / barber / date

    @Test
    void emptyWhenShopInactive() {
        shop.setActive(false);
        assertThat(slots(TOMORROW)).isEmpty();
    }

    @Test
    void emptyWhenBarberInactive() {
        barber.setActive(false);
        assertThat(slots(TOMORROW)).isEmpty();
    }

    @Test
    void emptyWhenBarberNotAcceptingBookings() {
        barber.setAcceptingBookings(false);
        assertThat(slots(TOMORROW)).isEmpty();
    }

    @Test
    void emptyForUnknownBarber() {
        assertThat(service().freeSlots(999L, TOMORROW)).isEmpty();
    }

    @Test
    void emptyForDatesBeforeToday() {
        assertThat(slots(TODAY.minusDays(1))).isEmpty();
    }

    @Test
    void horizonIncludesTheLastDayButNotTheNext() {
        shop.setBookingHorizonDays(7);

        assertThat(slots(TODAY.plusDays(6))).isNotEmpty();
        assertThat(slots(TODAY.plusDays(7))).isEmpty();
    }

    @Test
    void horizonOfOneDayMeansTodayOnly() {
        shop.setBookingHorizonDays(1);
        now = at(TODAY, 8, 0);

        assertThat(slots(TODAY)).isNotEmpty();
        assertThat(slots(TOMORROW)).isEmpty();
    }

    // ---------------------------------------------------------------- rule 2: working hours

    @Test
    void emptyWithoutWorkingHours() {
        hours = null;
        assertThat(slots(TOMORROW)).isEmpty();
    }

    @Test
    void emptyOnDayOff() {
        hours.setDayOff(true);
        assertThat(slots(TOMORROW)).isEmpty();
    }

    @Test
    void emptyWhenWorkingHoursAreIncomplete() {
        hours.setStartTime(null);
        assertThat(slots(TOMORROW)).isEmpty();
    }

    @Test
    void workingDayThatCrossesMidnightIsNotSupported() {
        hours = hours(t(22, 0), t(2, 0), null, null);
        assertThat(slots(TOMORROW)).isEmpty();

        hours = hours(t(10, 0), t(10, 0), null, null);
        assertThat(slots(TOMORROW)).isEmpty();
    }

    // ---------------------------------------------------------------- rule 3: candidates and fitting

    @Test
    void generatesSlotsInSlotMinuteSteps() {
        hours = hours(t(9, 0), t(11, 0), null, null);

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 30), t(10, 0), t(10, 30));
    }

    @Test
    void slotEndingExactlyAtEndTimeIsAllowed() {
        hours = hours(t(9, 0), t(10, 0), null, null);

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 30));
    }

    @Test
    void slotThatDoesNotFitBeforeEndTimeIsDropped() {
        hours = hours(t(9, 0), t(10, 45), null, null);

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 30), t(10, 0));
    }

    @Test
    void respectsOtherSlotLengths() {
        barber.setSlotMinutes(45);
        hours = hours(t(9, 0), t(11, 15), null, null);

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 45), t(10, 30));
    }

    @Test
    void slotsAreSortedAscending() {
        assertThat(slots(TOMORROW)).isSorted();
    }

    // ---------------------------------------------------------------- rule 4: break

    @Test
    void slotsOverlappingTheBreakAreRemoved() {
        List<LocalTime> result = slots(TOMORROW);

        assertThat(result).doesNotContain(t(13, 0), t(13, 30));
        assertThat(result).contains(t(12, 30), t(14, 0));
    }

    @Test
    void slotTouchingTheBreakEdgeIsAllowed() {
        // 12:30-13:00 ends exactly when the break starts; 14:00-14:30 starts exactly when it ends.
        assertThat(slots(TOMORROW)).contains(t(12, 30), t(14, 0));
    }

    @Test
    void breakNotAlignedToTheGridRemovesEveryOverlappingSlot() {
        hours = hours(t(9, 0), t(18, 0), t(13, 15), t(13, 45));

        List<LocalTime> result = slots(TOMORROW);

        assertThat(result).doesNotContain(t(13, 0), t(13, 30));
        assertThat(result).contains(t(12, 30), t(14, 0));
    }

    @Test
    void noBreakMeansNoGap() {
        hours = hours(t(9, 0), t(18, 0), null, null);

        assertThat(slots(TOMORROW)).contains(t(13, 0), t(13, 30));
    }

    // ---------------------------------------------------------------- rule 5: time off

    @Test
    void slotsOverlappingTimeOffAreRemoved() {
        hours = hours(t(9, 0), t(13, 0), null, null);
        timeOff(TOMORROW, t(10, 0), t(11, 0));

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 30), t(11, 0), t(11, 30), t(12, 0), t(12, 30));
    }

    @Test
    void timeOffInTheMiddleOfASlotRemovesIt() {
        hours = hours(t(9, 0), t(11, 0), null, null);
        timeOff(TOMORROW, t(9, 40), t(9, 50));

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(10, 0), t(10, 30));
    }

    @Test
    void wholeDayTimeOffRemovesEverything() {
        timeOff(TOMORROW, t(0, 0), t(23, 59));

        assertThat(slots(TOMORROW)).isEmpty();
    }

    private void timeOff(LocalDate date, LocalTime from, LocalTime to) {
        TimeOff off = new TimeOff();
        off.setBarberId(10L);
        off.setStartAt(date.atTime(from).atZone(ZONE).toInstant());
        off.setEndAt(date.atTime(to).atZone(ZONE).toInstant());
        timeOffs.add(off);
    }

    // ---------------------------------------------------------------- rule 6: bookings

    @Test
    void bookedSlotsAreRemoved() {
        hours = hours(t(9, 0), t(11, 0), null, null);
        booked.add(booking(TOMORROW, t(9, 30), t(10, 0), BookingStatus.BOOKED));

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(10, 0), t(10, 30));
    }

    @Test
    void bookingWithADifferentLengthBlocksEveryOverlappingSlot() {
        hours = hours(t(9, 0), t(12, 0), null, null);
        // A 45-minute booking made when slots were longer: 10:00-10:45 overlaps the 10:00 and the 10:30 slot.
        booked.add(booking(TOMORROW, t(10, 0), t(10, 45), BookingStatus.BOOKED));

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 30), t(11, 0), t(11, 30));
    }

    @Test
    void longBookingSpanningSeveralSlotsBlocksAllOfThem() {
        hours = hours(t(9, 0), t(12, 0), null, null);
        booked.add(booking(TOMORROW, t(9, 15), t(11, 15), BookingStatus.BOOKED));

        assertThat(slots(TOMORROW)).containsExactly(t(11, 30));
    }

    @Test
    void bookingTouchingASlotEdgeDoesNotBlockIt() {
        hours = hours(t(9, 0), t(11, 0), null, null);
        booked.add(booking(TOMORROW, t(9, 30), t(10, 0), BookingStatus.BOOKED));

        assertThat(slots(TOMORROW)).contains(t(9, 0), t(10, 0));
    }

    // ---------------------------------------------------------------- rule 7: lead time and the past

    @Test
    void todayHidesSlotsBeforeNowPlusLeadTime() {
        // now 10:00, lead 30 -> the first allowed start is 10:30
        hours = hours(t(9, 0), t(12, 0), null, null);

        assertThat(slots(TODAY)).containsExactly(t(10, 30), t(11, 0), t(11, 30));
    }

    @Test
    void leadTimeIsRoundedUpToTheNextSlot() {
        now = at(TODAY, 10, 10);
        shop.setMinLeadMinutes(30);
        hours = hours(t(9, 0), t(12, 0), null, null);

        // 10:10 + 30 = 10:40 -> the 10:30 slot is too early, 11:00 is the first
        assertThat(slots(TODAY)).containsExactly(t(11, 0), t(11, 30));
    }

    @Test
    void zeroLeadTimeStillHidesPastSlots() {
        shop.setMinLeadMinutes(0);
        hours = hours(t(9, 0), t(11, 0), null, null);

        // 10:00 is exactly now: allowed. 9:00 and 9:30 are in the past.
        assertThat(slots(TODAY)).containsExactly(t(10, 0), t(10, 30));
    }

    @Test
    void leadTimeDoesNotAffectFutureDays() {
        shop.setMinLeadMinutes(60);
        hours = hours(t(9, 0), t(10, 0), null, null);

        assertThat(slots(TOMORROW)).containsExactly(t(9, 0), t(9, 30));
    }

    @Test
    void manualBookingsIgnoreTheLeadTimeButNotThePast() {
        now = at(TODAY, 10, 10);
        hours = hours(t(9, 0), t(12, 0), null, null);

        List<LocalTime> manual = service().freeSlots(barber, shop, TODAY, true);

        assertThat(manual).containsExactly(t(10, 30), t(11, 0), t(11, 30));
    }

    @Test
    void closedForTheDayWhenNowIsAfterTheLastSlot() {
        now = at(TODAY, 17, 45);

        assertThat(slots(TODAY)).isEmpty();
    }

    // ---------------------------------------------------------------- time zones

    @Test
    void todayIsDecidedInTheShopTimeZoneNotUtc() {
        // 20:30 UTC on the 17th is already 01:30 on the 18th in Tashkent.
        now = Instant.parse("2025-09-17T20:30:00Z");
        hours = hours(t(9, 0), t(10, 0), null, null);

        assertThat(slots(LocalDate.of(2025, 9, 17))).isEmpty();          // yesterday for the shop
        assertThat(slots(LocalDate.of(2025, 9, 18))).containsExactly(t(9, 0), t(9, 30)); // today for the shop
    }

    // ---------------------------------------------------------------- available dates

    @Test
    void availableDatesAreDatesWithFreeSlots() {
        shop.setBookingHorizonDays(7);
        // Sundays are days off.
        when(workingHours.findByBarberIdAndDayOfWeek(anyLong(), anyInt())).thenAnswer(inv -> {
            int dow = inv.getArgument(1);
            if (dow == 7) {
                WorkingHours off = new WorkingHours();
                off.setDayOff(true);
                return Optional.of(off);
            }
            return Optional.of(hours);
        });

        List<LocalDate> dates = service().availableDates(10L);

        // Wed 17 .. Tue 23; Sunday 21 is skipped
        assertThat(dates).containsExactly(
                LocalDate.of(2025, 9, 17), LocalDate.of(2025, 9, 18), LocalDate.of(2025, 9, 19),
                LocalDate.of(2025, 9, 20), LocalDate.of(2025, 9, 22), LocalDate.of(2025, 9, 23));
    }

    @Test
    void todayIsNotOfferedWhenNoSlotsAreLeft() {
        now = at(TODAY, 17, 45);

        List<LocalDate> dates = service().availableDates(10L);

        assertThat(dates).doesNotContain(TODAY).contains(TOMORROW);
    }

    @Test
    void availableDatesAreLimitedTo14Buttons() {
        shop.setBookingHorizonDays(30);

        assertThat(service().availableDates(10L)).hasSize(14);
    }

    @Test
    void noAvailableDatesForAnInactiveShop() {
        shop.setActive(false);

        assertThat(service().availableDates(10L)).isEmpty();
    }
}
