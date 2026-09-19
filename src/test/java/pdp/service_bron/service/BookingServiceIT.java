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
import pdp.service_bron.repository.WorkingHoursRepository;
import pdp.service_bron.service.BookingService.BookResult;
import pdp.service_bron.service.BookingService.Reason;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class BookingServiceIT {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookings;
    @Autowired ShopRepository shops;
    @Autowired BarberRepository barbers;
    @Autowired AppUserRepository users;
    @Autowired WorkingHoursRepository workingHours;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    Shop shop;
    Barber barber;
    AppUser barberUser;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        shop = new Shop();
        shop.setSlug("test-shop");
        shop.setName("Test Shop");
        shop.setMaxActiveBookingsPerClient(2);
        shop = shops.save(shop);

        barberUser = newUser(1000, "Aziz");
        barber = new Barber();
        barber.setShopId(shop.getId());
        barber.setUserId(barberUser.getId());
        barber.setDisplayName("Aziz");
        barber.setSlotMinutes(30);
        barber = barbers.save(barber);

        for (int dow = 1; dow <= 7; dow++) {
            WorkingHours h = new WorkingHours();
            h.setBarberId(barber.getId());
            h.setDayOfWeek(dow);
            h.setStartTime(LocalTime.of(9, 0));
            h.setEndTime(LocalTime.of(20, 0));
            workingHours.save(h);
        }
        tomorrow = clock.instant().atZone(ZoneId.of(shop.getTimezone())).toLocalDate().plusDays(1);
    }

    private AppUser newUser(long telegramId, String name) {
        AppUser user = new AppUser();
        user.setTelegramId(telegramId);
        user.setFirstName(name);
        user.setPhone("+99890" + (1000000 + telegramId));
        return users.save(user);
    }

    @Test
    void tenClientsBookTheSameSlotAtOnceAndExactlyOneWins() throws Exception {
        int threads = 10;
        List<AppUser> clients = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            clients.add(newUser(2000 + i, "Client" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BookResult>> futures = new ArrayList<>();
        for (AppUser client : clients) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return bookingService.book(client.getId(), barber.getId(), tomorrow, NOON);
            }));
        }
        ready.await();
        go.countDown();

        int ok = 0;
        int taken = 0;
        for (Future<BookResult> future : futures) {
            BookResult result = future.get(30, TimeUnit.SECONDS);
            switch (result) {
                case BookResult.Ok ignored -> ok++;
                case BookResult.Rejected rejected -> {
                    assertThat(rejected.reason()).isEqualTo(Reason.SLOT_TAKEN);
                    taken++;
                }
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(taken).isEqualTo(threads - 1);
        assertThat(bookings.findAll()).hasSize(1);
        assertThat(bookings.findAll().getFirst().getStatus()).isEqualTo(BookingStatus.BOOKED);
    }

    @Test
    void bookingStoresTimesInTheShopTimeZoneAndSlotLength() {
        AppUser client = newUser(3000, "Ali");

        BookResult result = bookingService.book(client.getId(), barber.getId(), tomorrow, NOON);

        assertThat(result).isInstanceOf(BookResult.Ok.class);
        Booking booking = ((BookResult.Ok) result).booking();
        ZoneId zone = ZoneId.of(shop.getTimezone());
        assertThat(booking.getStartAt().atZone(zone).toLocalTime()).isEqualTo(NOON);
        assertThat(booking.getEndAt()).isEqualTo(booking.getStartAt().plusSeconds(30 * 60));
        assertThat(booking.getClientName()).isEqualTo("Ali");
        assertThat(booking.getClientPhone()).isEqualTo(client.getPhone());
    }

    @Test
    void clientCannotExceedTheActiveBookingLimit() {
        AppUser client = newUser(3001, "Ali");

        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, LocalTime.of(10, 0)))
                .isInstanceOf(BookResult.Ok.class);
        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, LocalTime.of(11, 0)))
                .isInstanceOf(BookResult.Ok.class);

        BookResult third = bookingService.book(client.getId(), barber.getId(), tomorrow, LocalTime.of(12, 0));

        assertThat(third).isEqualTo(new BookResult.Rejected(Reason.LIMIT_REACHED));
    }

    @Test
    void clientWithoutPhoneCannotBook() {
        AppUser client = newUser(3002, "NoPhone");
        client.setPhone(null);
        users.save(client);

        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, NOON))
                .isEqualTo(new BookResult.Rejected(Reason.PHONE_REQUIRED));
    }

    @Test
    void inactiveShopRefusesBookings() {
        AppUser client = newUser(3003, "Ali");
        shop.setActive(false);
        shops.save(shop);

        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, NOON))
                .isEqualTo(new BookResult.Rejected(Reason.SHOP_INACTIVE));
    }

    @Test
    void barberNotAcceptingBookingsIsRefused() {
        AppUser client = newUser(3004, "Ali");
        barber.setAcceptingBookings(false);
        barbers.save(barber);

        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, NOON))
                .isEqualTo(new BookResult.Rejected(Reason.BARBER_UNAVAILABLE));
    }

    @Test
    void slotOutsideWorkingHoursIsRefused() {
        AppUser client = newUser(3005, "Ali");

        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, LocalTime.of(23, 0)))
                .isEqualTo(new BookResult.Rejected(Reason.SLOT_TAKEN));
    }

    @Test
    void cancellingFreesTheSlotAgain() {
        AppUser first = newUser(3006, "First");
        AppUser second = newUser(3007, "Second");
        Booking booking = ((BookResult.Ok) bookingService.book(first.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThat(bookingService.book(second.getId(), barber.getId(), tomorrow, NOON))
                .isEqualTo(new BookResult.Rejected(Reason.SLOT_TAKEN));

        Booking cancelled = bookingService.cancelByClient(booking.getId(), first);
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_CLIENT);
        assertThat(cancelled.getCancelledAt()).isNotNull();

        assertThat(bookingService.book(second.getId(), barber.getId(), tomorrow, NOON))
                .isInstanceOf(BookResult.Ok.class);
    }

    @Test
    void anotherClientCannotCancelMyBooking() {
        AppUser owner = newUser(3008, "Owner");
        AppUser stranger = newUser(3009, "Stranger");
        Booking booking = ((BookResult.Ok) bookingService.book(owner.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThatThrownBy(() -> bookingService.cancelByClient(booking.getId(), stranger))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.forbidden");
    }

    @Test
    void onlyTheBarberOrOwnerCanCancelAsBarber() {
        AppUser client = newUser(3010, "Client");
        AppUser stranger = newUser(3011, "Stranger");
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThatThrownBy(() -> bookingService.cancelByBarber(booking.getId(), stranger, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.forbidden");

        Booking cancelled = bookingService.cancelByBarber(booking.getId(), barberUser, "  sick  ");
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_BARBER);
        assertThat(cancelled.getCancelReason()).isEqualTo("sick");
    }

    @Test
    void shopOwnerMayManageBookingsOfTheirBarbers() {
        AppUser owner = newUser(3012, "Owner");
        shop.setOwnerUserId(owner.getId());
        shops.save(shop);
        AppUser client = newUser(3013, "Client");
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThat(bookingService.cancelByBarber(booking.getId(), owner, null).getStatus())
                .isEqualTo(BookingStatus.CANCELLED_BY_BARBER);
    }

    @Test
    void cancelledBookingCannotBeCancelledTwice() {
        AppUser client = newUser(3014, "Client");
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();
        bookingService.cancelByClient(booking.getId(), client);

        assertThatThrownBy(() -> bookingService.cancelByClient(booking.getId(), client))
                .isInstanceOf(BusinessException.class)
                .hasMessage("booking.not_active");
    }

    @Test
    void manualBookingBlocksTheSlotForClients() {
        AppUser client = newUser(3015, "Client");

        BookResult manual = bookingService.bookManual(barberUser, barber.getId(), tomorrow, NOON, "Vali", null);
        assertThat(manual).isInstanceOf(BookResult.Ok.class);
        Booking booking = ((BookResult.Ok) manual).booking();
        assertThat(booking.getClientUserId()).isNull();
        assertThat(booking.getClientName()).isEqualTo("Vali");

        assertThat(bookingService.book(client.getId(), barber.getId(), tomorrow, NOON))
                .isEqualTo(new BookResult.Rejected(Reason.SLOT_TAKEN));
    }

    @Test
    void manualBookingRequiresPermissionAndAName() {
        AppUser stranger = newUser(3016, "Stranger");

        assertThatThrownBy(() -> bookingService.bookManual(stranger, barber.getId(), tomorrow, NOON, "Vali", null))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
        assertThatThrownBy(() -> bookingService.bookManual(barberUser, barber.getId(), tomorrow, NOON, "  ", null))
                .isInstanceOf(BusinessException.class).hasMessage("manual.name_invalid");
    }

    @Test
    void attendanceCannotBeMarkedBeforeTheStart() {
        AppUser client = newUser(3017, "Client");
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThatThrownBy(() -> bookingService.markAttendance(booking.getId(), barberUser, true))
                .isInstanceOf(BusinessException.class).hasMessage("booking.not_started");
    }

    @Test
    void confirmingAttendanceIsIdempotent() {
        AppUser client = newUser(3018, "Client");
        Booking booking = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();

        assertThat(bookingService.confirmByClient(booking.getId(), client).isClientConfirmed()).isTrue();
        assertThat(bookingService.confirmByClient(booking.getId(), client).isClientConfirmed()).isTrue();
    }

    @Test
    void databaseConstraintRejectsOverlappingBookedRowsEvenWithoutTheService() {
        AppUser client = newUser(3019, "Client");
        Booking first = ((BookResult.Ok) bookingService.book(client.getId(), barber.getId(), tomorrow, NOON)).booking();

        Booking overlapping = new Booking();
        overlapping.setShopId(shop.getId());
        overlapping.setBarberId(barber.getId());
        overlapping.setStartAt(first.getStartAt().plusSeconds(600));
        overlapping.setEndAt(first.getEndAt().plusSeconds(600));

        assertThatThrownBy(() -> bookings.saveAndFlush(overlapping))
                .satisfies(e -> assertThat(BookingService.isExclusionViolation(e)).isTrue());
    }
}
