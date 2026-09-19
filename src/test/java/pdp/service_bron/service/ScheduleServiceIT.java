package pdp.service_bron.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pdp.service_bron.IntegrationTest;
import pdp.service_bron.TestDb;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.domain.WorkingHours;
import pdp.service_bron.repository.AppUserRepository;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.TimeOffRepository;
import pdp.service_bron.service.BookingService.BookResult;
import pdp.service_bron.service.ScheduleService.DaySchedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class ScheduleServiceIT {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired ScheduleService schedule;
    @Autowired BookingService bookingService;
    @Autowired SlotService slots;
    @Autowired BarberService barberService;
    @Autowired ShopRepository shops;
    @Autowired BarberRepository barbers;
    @Autowired AppUserRepository users;
    @Autowired BookingRepository bookings;
    @Autowired TimeOffRepository timeOffs;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    Shop shop;
    Barber barber;
    AppUser barberUser;
    AppUser client;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        shop = new Shop();
        shop.setSlug("sched-shop");
        shop.setName("Sched Shop");
        shop = shops.save(shop);
        barberUser = user(1, "Barber");
        client = user(2, "Client");
        barber = new Barber();
        barber.setShopId(shop.getId());
        barber.setUserId(barberUser.getId());
        barber.setDisplayName("Barber");
        barber.setAcceptingBookings(false); // like a freshly invited barber
        barber = barbers.save(barber);
        tomorrow = clock.instant().atZone(ZoneId.of(shop.getTimezone())).toLocalDate().plusDays(1);
    }

    private AppUser user(long telegramId, String name) {
        AppUser u = new AppUser();
        u.setTelegramId(telegramId);
        u.setFirstName(name);
        u.setPhone("+998901234567");
        return users.save(u);
    }

    private Map<Integer, DaySchedule> everyDay(LocalTime start, LocalTime end) {
        Map<Integer, DaySchedule> plan = new java.util.TreeMap<>();
        for (int dow = 1; dow <= 7; dow++) {
            plan.put(dow, DaySchedule.work(start, end, null, null));
        }
        return plan;
    }

    @Test
    void firstWorkingHoursMakeTheBarberAcceptBookings() {
        assertThat(barbers.findById(barber.getId()).orElseThrow().isAcceptingBookings()).isFalse();

        schedule.apply(barberUser, barber.getId(), schedule.preset(ScheduleService.PRESET_MON_SAT));

        assertThat(barbers.findById(barber.getId()).orElseThrow().isAcceptingBookings()).isTrue();
        Map<Integer, WorkingHours> week = schedule.week(barber.getId());
        assertThat(week).hasSize(7);
        assertThat(week.get(1).getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(week.get(1).getEndTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(week.get(7).isDayOff()).isTrue();
    }

    @Test
    void everyDayPresetWorksSevenDays() {
        schedule.apply(barberUser, barber.getId(), schedule.preset(ScheduleService.PRESET_EVERY_DAY));

        assertThat(schedule.week(barber.getId()).values()).allMatch(h -> !h.isDayOff());
        assertThat(schedule.week(barber.getId()).get(7).getStartTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void invalidHoursAreRejectedAndNothingIsSaved() {
        assertThatThrownBy(() -> schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(20, 0), LocalTime.of(9, 0))))
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_range");

        assertThat(schedule.week(barber.getId())).isEmpty();
    }

    @Test
    void strangerCannotChangeHours() {
        AppUser stranger = user(99, "Stranger");

        assertThatThrownBy(() -> schedule.apply(stranger, barber.getId(), schedule.preset(1)))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
    }

    @Test
    void bookingsOutsideTheNewHoursAreReportedButKept() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();
        int dow = tomorrow.getDayOfWeek().getValue();

        Map<Integer, DaySchedule> shorter = Map.of(dow, DaySchedule.work(LocalTime.of(9, 0), LocalTime.of(11, 0), null, null));
        List<Booking> conflicts = schedule.conflicts(barber.getId(), shorter);
        assertThat(conflicts).extracting(Booking::getId).containsExactly(booking.getId());

        schedule.apply(barberUser, barber.getId(), shorter);

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.BOOKED);
        assertThat(slots.freeSlots(barber.getId(), tomorrow)).doesNotContain(NOON);
    }

    @Test
    void newHoursThatStillContainTheBookingHaveNoConflicts() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        bookingService.book(client.getId(), barber.getId(), tomorrow, NOON);
        int dow = tomorrow.getDayOfWeek().getValue();

        List<Booking> conflicts = schedule.conflicts(barber.getId(),
                Map.of(dow, DaySchedule.work(LocalTime.of(10, 0), LocalTime.of(18, 0), null, null)));

        assertThat(conflicts).isEmpty();
    }

    @Test
    void aBreakOverAnExistingBookingIsAConflict() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        bookingService.book(client.getId(), barber.getId(), tomorrow, NOON);
        int dow = tomorrow.getDayOfWeek().getValue();

        List<Booking> conflicts = schedule.conflicts(barber.getId(),
                Map.of(dow, DaySchedule.work(LocalTime.of(9, 0), LocalTime.of(20, 0), LocalTime.of(12, 0), LocalTime.of(13, 0))));

        assertThat(conflicts).hasSize(1);
    }

    @Test
    void makingADayOffConflictsWithItsBookings() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        bookingService.book(client.getId(), barber.getId(), tomorrow, NOON);
        int dow = tomorrow.getDayOfWeek().getValue();

        assertThat(schedule.conflicts(barber.getId(), Map.of(dow, DaySchedule.off()))).hasSize(1);
    }

    @Test
    void copyToWorkdaysSkipsDaysOff() {
        schedule.apply(barberUser, barber.getId(), schedule.preset(ScheduleService.PRESET_MON_SAT));
        schedule.apply(barberUser, barber.getId(),
                Map.of(1, DaySchedule.work(LocalTime.of(8, 0), LocalTime.of(16, 0), LocalTime.of(12, 0), LocalTime.of(13, 0))));

        Map<Integer, DaySchedule> plan = schedule.copyToWorkdays(barber.getId(), 1);
        schedule.apply(barberUser, barber.getId(), plan);

        assertThat(plan).doesNotContainKey(7);
        assertThat(schedule.week(barber.getId()).get(3).getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(schedule.week(barber.getId()).get(3).getBreakStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(schedule.week(barber.getId()).get(7).isDayOff()).isTrue();
    }

    @Test
    void breakCanBeAppliedToAllWorkdays() {
        schedule.apply(barberUser, barber.getId(), schedule.preset(ScheduleService.PRESET_MON_SAT));

        schedule.apply(barberUser, barber.getId(),
                schedule.withBreakOnWorkdays(barber.getId(), LocalTime.of(13, 0), LocalTime.of(14, 0)));

        assertThat(schedule.week(barber.getId()).get(2).getBreakStart()).isEqualTo(LocalTime.of(13, 0));
        assertThat(slots.freeSlots(barber.getId(), tomorrow)).doesNotContain(LocalTime.of(13, 0));
    }

    @Test
    void closingTimeBlocksTheSlots() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));

        List<Booking> cancelled = schedule.closeTime(barberUser, barber.getId(), tomorrow,
                LocalTime.of(14, 0), LocalTime.of(16, 0), false);

        assertThat(cancelled).isEmpty();
        assertThat(slots.freeSlots(barber.getId(), tomorrow))
                .doesNotContain(LocalTime.of(14, 0), LocalTime.of(14, 30), LocalTime.of(15, 0), LocalTime.of(15, 30))
                .contains(LocalTime.of(13, 30), LocalTime.of(16, 0));
        assertThat(schedule.upcomingTimeOff(barber.getId())).hasSize(1);
    }

    @Test
    void closingTheWholeDayLeavesNoSlots() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));

        schedule.closeTime(barberUser, barber.getId(), tomorrow, null, null, false);

        assertThat(slots.freeSlots(barber.getId(), tomorrow)).isEmpty();
    }

    @Test
    void closingOverBookingsNeedsExplicitCancellation() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThatThrownBy(() -> schedule.closeTime(barberUser, barber.getId(), tomorrow, null, null, false))
                .isInstanceOf(BusinessException.class).hasMessage("timeoff.has_bookings");
        assertThat(timeOffs.findAll()).isEmpty();

        List<Booking> cancelled = schedule.closeTime(barberUser, barber.getId(), tomorrow, null, null, true);

        assertThat(cancelled).extracting(Booking::getId).containsExactly(booking.getId());
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_BARBER);
        assertThat(timeOffs.findAll()).hasSize(1);
    }

    @Test
    void timeOffCanBeDeletedAndSlotsReturn() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        schedule.closeTime(barberUser, barber.getId(), tomorrow, null, null, false);
        Long id = schedule.upcomingTimeOff(barber.getId()).getFirst().getId();

        schedule.deleteTimeOff(barberUser, id);

        assertThat(slots.freeSlots(barber.getId(), tomorrow)).isNotEmpty();
    }

    @Test
    void invalidTimeOffRangeIsRejected() {
        schedule.apply(barberUser, barber.getId(), everyDay(LocalTime.of(9, 0), LocalTime.of(20, 0)));

        assertThatThrownBy(() -> schedule.closeTime(barberUser, barber.getId(), tomorrow,
                LocalTime.of(16, 0), LocalTime.of(14, 0), false))
                .isInstanceOf(BusinessException.class).hasMessage("hours.invalid_range");
    }

    @Test
    void cannotCloseTimeInThePast() {
        LocalDate yesterday = tomorrow.minusDays(2);

        assertThatThrownBy(() -> schedule.closeTime(barberUser, barber.getId(), yesterday, null, null, false))
                .isInstanceOf(BusinessException.class).hasMessage("timeoff.in_past");
    }

    @Test
    void barberCannotBeSwitchedOnWithoutHours() {
        assertThatThrownBy(() -> barberService.setAccepting(barberUser, barber.getId(), true))
                .isInstanceOf(BusinessException.class).hasMessage("barber.hours_required");

        schedule.apply(barberUser, barber.getId(), schedule.preset(1));
        barberService.setAccepting(barberUser, barber.getId(), false);
        assertThat(barbers.findById(barber.getId()).orElseThrow().isAcceptingBookings()).isFalse();
        barberService.setAccepting(barberUser, barber.getId(), true);
        assertThat(barbers.findById(barber.getId()).orElseThrow().isAcceptingBookings()).isTrue();
    }
}
