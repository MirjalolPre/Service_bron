package pdp.service_bron.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import pdp.service_bron.IntegrationTest;
import pdp.service_bron.TestDb;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.AppUserRepository;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.TimeOffRepository;
import pdp.service_bron.repository.WorkingHoursRepository;
import pdp.service_bron.service.SlotService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@TestPropertySource(properties = {"app.demo-data.enabled=true", "app.demo-data.staff-telegram-id=555"})
class DemoDataSeederIT {

    @Autowired DemoDataSeeder seeder;
    @Autowired ShopRepository shops;
    @Autowired BarberRepository barbers;
    @Autowired WorkingHoursRepository workingHours;
    @Autowired TimeOffRepository timeOffs;
    @Autowired BookingRepository bookings;
    @Autowired AppUserRepository users;
    @Autowired SlotService slots;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void freshSeed() {
        TestDb.clean(jdbc);
        ReflectionTestUtils.setField(seeder, "reset", false);
        seeder.seed();
    }

    @Test
    void createsSevenShopsAndOneOfThemIsSwitchedOff() {
        List<Shop> all = shops.findAll();

        assertThat(all).hasSize(7).allMatch(s -> s.getSlug().startsWith("demo-"));
        assertThat(all.stream().filter(s -> !s.isActive())).singleElement()
                .satisfies(s -> assertThat(s.getName()).contains("VIP"));
        assertThat(shops.findByActiveTrue(org.springframework.data.domain.PageRequest.of(0, 5)).getTotalPages())
                .as("six active shops give the client list a second page").isEqualTo(2);
        assertThat(shops.findBySlug("demo-old-town").orElseThrow().getName()).isEqualTo("Старый город");
    }

    @Test
    void everyBarberHasAWeeklyScheduleAndPrices() {
        for (Barber barber : barbers.findAll()) {
            assertThat(workingHours.findByBarberIdOrderByDayOfWeekAsc(barber.getId())).hasSize(7);
        }
        Shop house = shops.findBySlug("demo-barber-house").orElseThrow();
        List<Barber> houseBarbers = barbers.findByShopIdOrderBySortOrderAscIdAsc(house.getId());
        assertThat(houseBarbers).extracting(Barber::getDisplayName).contains("Aziz", "Sardor", "Jasur");
        assertThat(houseBarbers.stream().filter(b -> !b.isAcceptingBookings())).as("one barber is switched off")
                .extracting(Barber::getDisplayName).containsExactly("Jasur");
    }

    @Test
    void theConfiguredTesterBecomesOwnerAndBarberOfTheFirstShop() {
        Shop house = shops.findBySlug("demo-barber-house").orElseThrow();
        AppUser staff = users.findByTelegramId(555L).orElseThrow();

        assertThat(house.getOwnerUserId()).isEqualTo(staff.getId());
        assertThat(barbers.findByUserIdAndActiveTrueOrderByIdAsc(staff.getId())).singleElement()
                .satisfies(b -> assertThat(b.getShopId()).isEqualTo(house.getId()));
    }

    @Test
    void fakeBarbersAndClientsCanNeverBeRealTelegramUsers() {
        long fakeBarbers = barbers.findAll().stream().filter(b -> b.getUserId() == null).count();
        assertThat(fakeBarbers).isEqualTo(barbers.count() - 1); // all but the tester's own barber row

        List<AppUser> fakeClients = users.findAll().stream().filter(u -> u.getTelegramId() >= 9_000_000_000L).toList();
        assertThat(fakeClients).hasSize(DemoDataSeeder.FAKE_CLIENTS).allMatch(u -> u.getPhone() != null);
    }

    @Test
    void blockedPeriodsExist() {
        assertThat(timeOffs.count()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void bookingsCoverPastStatusesAndUpcomingSlots() {
        List<Booking> all = bookings.findAll();

        assertThat(all.stream().map(Booking::getStatus).distinct()).contains(BookingStatus.BOOKED, BookingStatus.COMPLETED,
                BookingStatus.NO_SHOW, BookingStatus.CANCELLED_BY_CLIENT);
        assertThat(all.stream().filter(Booking::isBooked).count()).isGreaterThan(10);
        // Fake clients must never receive reminders from the scheduler.
        assertThat(all).allMatch(Booking::isReminderSent);
        assertThat(all.stream().filter(b -> b.getSource().name().equals("MANUAL"))).isNotEmpty();
    }

    @Test
    void demoBarbersStillHaveFreeSlotsToTryBookingWith() {
        Shop house = shops.findBySlug("demo-barber-house").orElseThrow();
        Barber aziz = barbers.findByShopIdOrderBySortOrderAscIdAsc(house.getId()).stream()
                .filter(b -> b.getDisplayName().equals("Aziz")).findFirst().orElseThrow();

        assertThat(slots.availableDates(aziz.getId())).isNotEmpty();
        assertThat(slots.availableDates(aziz.getId()).stream().anyMatch(d -> !slots.freeSlots(aziz.getId(), d).isEmpty())).isTrue();
    }

    @Test
    void inactiveShopHasNoUpcomingBookings() {
        Shop vip = shops.findBySlug("demo-vip-style").orElseThrow();

        assertThat(bookings.findAll().stream().filter(b -> b.getShopId().equals(vip.getId()))).isEmpty();
    }

    @Test
    void seedingTwiceDoesNotDuplicateAnything() {
        long shopCount = shops.count();
        long bookingCount = bookings.count();

        seeder.seed();

        assertThat(shops.count()).isEqualTo(shopCount);
        assertThat(bookings.count()).isEqualTo(bookingCount);
    }

    @Test
    void resetRebuildsTheDemoDataWithoutTouchingOtherShops() {
        Shop own = new Shop();
        own.setSlug("my-real-shop");
        own.setName("My real shop");
        shops.save(own);
        Long oldId = shops.findBySlug("demo-classic-cut").orElseThrow().getId();

        ReflectionTestUtils.setField(seeder, "reset", true);
        seeder.seed();

        assertThat(shops.findBySlug("my-real-shop")).isPresent();
        assertThat(shops.count()).isEqualTo(8);
        assertThat(shops.findBySlug("demo-classic-cut").orElseThrow().getId()).isNotEqualTo(oldId);
        assertThat(users.findAll().stream().filter(u -> u.getTelegramId() >= 9_000_000_000L)).hasSize(DemoDataSeeder.FAKE_CLIENTS);
    }
}
