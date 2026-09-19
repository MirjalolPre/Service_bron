package pdp.service_bron.demo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pdp.service_bron.config.BotProperties;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingSource;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.PriceItem;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.domain.TimeOff;
import pdp.service_bron.domain.WorkingHours;
import pdp.service_bron.repository.AppUserRepository;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.PriceItemRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.TimeOffRepository;
import pdp.service_bron.repository.WorkingHoursRepository;
import pdp.service_bron.service.ScheduleService.DaySchedule;
import pdp.service_bron.service.SlotService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fills a development database with fake shops, barbers, prices, working hours, blocked periods and bookings,
 * so every screen of the bot can be tried without typing data in by hand.
 *
 * <p>Switched off by default. Enable it with {@code DEMO_DATA=true} in {@code .env}. Everything it creates is
 * recognizable: shop slugs start with {@code demo-} and fake people use Telegram ids from 9000000000 to
 * 9000000999, so they can never be real users and the bot never tries to message them. Fake barbers have no
 * Telegram account. Set {@code DEMO_DATA_RESET=true} to delete the demo data and create it again (dates are
 * relative to today, so a reset refreshes them).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    static final String SLUG_PREFIX = "demo-";
    static final long FAKE_ID_BASE = 9_000_000_000L;
    static final int FAKE_CLIENTS = 8;

    private static final List<String> CLIENT_NAMES = List.of("Ali", "Vali", "Sardor", "Jasur", "Bobur", "Dilshod", "Aziz", "Otabek");

    private record Price(String name, long amount) {
    }

    private record BarberSpec(String name, String bio, int slotMinutes, boolean accepting, Map<Integer, DaySchedule> week,
                              List<Price> prices) {
    }

    private record ShopSpec(String slug, String name, String address, String landmark, String phone, Double lat, Double lon,
                            String description, boolean active, int horizonDays, List<BarberSpec> barbers) {
    }

    private final ShopRepository shops;
    private final BarberRepository barbers;
    private final PriceItemRepository prices;
    private final WorkingHoursRepository workingHours;
    private final TimeOffRepository timeOffs;
    private final BookingRepository bookings;
    private final AppUserRepository users;
    private final SlotService slots;
    private final BotProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;

    @Value("${app.demo-data.reset:false}")
    private boolean reset;

    /** Telegram id of the real person who becomes owner and barber of the first demo shop; "none" disables it. */
    @Value("${app.demo-data.staff-telegram-id:}")
    private String staffTelegramId;

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Creates the demo data. Does nothing when it already exists (unless reset was requested). */
    public void seed() {
        if (reset) {
            deleteDemoData();
        }
        if (shops.findBySlug(SLUG_PREFIX + "barber-house").isPresent()) {
            log.info("Demo data already exists. Set DEMO_DATA_RESET=true to rebuild it with fresh dates.");
            return;
        }
        List<AppUser> clients = createFakeClients();
        AppUser staff = resolveStaffUser();
        int shopCount = 0;
        for (ShopSpec spec : specs()) {
            createShop(spec, clients, shopCount == 0 ? staff : null);
            shopCount++;
        }
        log.info("Demo data created: {} shops (one is switched off), fake clients, blocked periods and bookings.", shopCount);
        if (staff != null) {
            log.info("Telegram user {} is now the owner and a barber of the demo shop 'Barber House'.", staff.getTelegramId());
        }
    }

    // ------------------------------------------------------------------ data definition

    private List<ShopSpec> specs() {
        Map<Integer, DaySchedule> monSat = week(LocalTime.of(9, 0), LocalTime.of(20, 0), LocalTime.of(13, 0), LocalTime.of(14, 0), true);
        Map<Integer, DaySchedule> everyDay = week(LocalTime.of(10, 0), LocalTime.of(21, 0), LocalTime.of(14, 0), LocalTime.of(15, 0), false);
        Map<Integer, DaySchedule> weekdays = week(LocalTime.of(9, 0), LocalTime.of(18, 0), null, null, true);
        weekdays.put(6, DaySchedule.work(LocalTime.of(10, 0), LocalTime.of(15, 0), null, null));

        List<Price> classic = List.of(new Price("Soch olish", 60_000), new Price("Soqol", 30_000),
                new Price("Soch + soqol", 80_000), new Price("Bolalar soch olish", 40_000));
        List<Price> simple = List.of(new Price("Soch olish", 50_000), new Price("Soqol", 25_000));

        return List.of(
                new ShopSpec(SLUG_PREFIX + "barber-house", "Barber House", "Chilonzor 9-kvartal, 12-uy", "metro yonida",
                        "+998901112233", 41.2856, 69.2036, "Zamonaviy sartaroshxona: soch, soqol va parvarish.", true, 14, List.of(
                        new BarberSpec("Aziz", "5 yillik tajriba", 30, true, monSat, classic),
                        new BarberSpec("Sardor", "Soqol bo'yicha mutaxassis", 45, true, everyDay, classic),
                        new BarberSpec("Jasur", "Yangi sartarosh", 30, false, monSat, simple))),
                new ShopSpec(SLUG_PREFIX + "classic-cut", "Classic Cut", "Yunusobod 4-mavze, 7-uy", "Mega Planet yonida",
                        "+998902223344", 41.3651, 69.2870, "Klassik erkaklar soch turmaklari.", true, 7, List.of(
                        new BarberSpec("Bekzod", "10 yillik tajriba", 60, true, weekdays, simple))),
                new ShopSpec(SLUG_PREFIX + "old-town", "Старый город", "Шайхантахурский район, ул. Навои 15", "рядом с рынком",
                        "+998903334455", 41.3267, 69.2400, "Барбершоп в центре старого города.", true, 30, List.of(
                        new BarberSpec("Тимур", "Стрижки и борода", 30, true, monSat, classic),
                        new BarberSpec("Рустам", null, 20, true, everyDay, simple))),
                new ShopSpec(SLUG_PREFIX + "yunusobod-style", "Yunusobod Style", "Yunusobod 11-mavze", null,
                        null, null, null, null, true, 7, List.of(
                        new BarberSpec("Farrux", null, 30, true, monSat, simple))),
                new ShopSpec(SLUG_PREFIX + "toshkent-barber", "Toshkent Barber", "Mirzo Ulug'bek ko'chasi 3", "bank yonida",
                        "+998904445566", null, null, null, true, 7, List.of(
                        new BarberSpec("Nodir", null, 30, true, weekdays, simple))),
                new ShopSpec(SLUG_PREFIX + "sergeli-barber", "Sergeli Barber", "Sergeli 5-mavze", null,
                        "+998906667788", null, null, null, true, 7, List.of(
                        new BarberSpec("Doniyor", null, 30, true, monSat, simple))),
                new ShopSpec(SLUG_PREFIX + "vip-style", "VIP Style (obuna o'chirilgan)", "Amir Temur ko'chasi 1", null,
                        "+998905556677", null, null, "Obuna o'chirilgan sartaroshxona namunasi.", false, 7, List.of(
                        new BarberSpec("Kamol", null, 30, true, monSat, simple))));
    }

    /** Monday to Saturday with the same hours; Sunday is a day off when {@code sundayOff}. */
    private static Map<Integer, DaySchedule> week(LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd,
                                                  boolean sundayOff) {
        Map<Integer, DaySchedule> week = new TreeMap<>();
        for (int dow = 1; dow <= 7; dow++) {
            week.put(dow, dow == 7 && sundayOff ? DaySchedule.off() : DaySchedule.work(start, end, breakStart, breakEnd));
        }
        return week;
    }

    // ------------------------------------------------------------------ creating

    private void createShop(ShopSpec spec, List<AppUser> clients, AppUser staff) {
        Shop shop = new Shop();
        shop.setSlug(spec.slug());
        shop.setName(spec.name());
        shop.setAddress(spec.address());
        shop.setLandmark(spec.landmark());
        shop.setPhone(spec.phone());
        shop.setLatitude(spec.lat());
        shop.setLongitude(spec.lon());
        shop.setDescription(spec.description());
        shop.setTimezone(properties.defaultTimezone());
        shop.setBookingHorizonDays(spec.horizonDays());
        shop.setActive(spec.active());
        shop.setOwnerUserId(staff == null ? null : staff.getId());
        shop = shops.save(shop);

        int order = 0;
        List<Barber> created = new ArrayList<>();
        for (BarberSpec b : spec.barbers()) {
            created.add(createBarber(shop, b, ++order, null));
        }
        if (staff != null) {
            // The real tester becomes a barber too, so the barber menu has something to show.
            BarberSpec own = new BarberSpec(staff.displayName(), "Demo sartarosh (siz)", 30, true,
                    week(LocalTime.of(9, 0), LocalTime.of(20, 0), LocalTime.of(13, 0), LocalTime.of(14, 0), true),
                    List.of(new Price("Soch olish", 60_000), new Price("Soqol", 30_000), new Price("Soch + soqol", 80_000)));
            created.add(createBarber(shop, own, ++order, staff));
        }

        int index = 0;
        for (Barber barber : created) {
            BarberSpec spec1 = index < spec.barbers().size() ? spec.barbers().get(index) : null;
            Map<Integer, DaySchedule> week = spec1 != null ? spec1.week()
                    : week(LocalTime.of(9, 0), LocalTime.of(20, 0), LocalTime.of(13, 0), LocalTime.of(14, 0), true);
            addBlockedPeriods(barber, shop, index);
            addBookings(barber, shop, week, clients, index);
            index++;
        }
    }

    private Barber createBarber(Shop shop, BarberSpec spec, int order, AppUser user) {
        Barber barber = new Barber();
        barber.setShopId(shop.getId());
        barber.setUserId(user == null ? null : user.getId());
        barber.setDisplayName(spec.name());
        barber.setBio(spec.bio());
        barber.setSlotMinutes(spec.slotMinutes());
        barber.setAcceptingBookings(spec.accepting());
        barber.setSortOrder(order);
        barber = barbers.save(barber);

        int sort = 0;
        for (Price price : spec.prices()) {
            PriceItem item = new PriceItem();
            item.setBarberId(barber.getId());
            item.setName(price.name());
            item.setPrice(price.amount());
            item.setSortOrder(++sort);
            prices.save(item);
        }
        for (Map.Entry<Integer, DaySchedule> entry : spec.week().entrySet()) {
            DaySchedule day = entry.getValue();
            WorkingHours row = new WorkingHours();
            row.setBarberId(barber.getId());
            row.setDayOfWeek(entry.getKey());
            row.setDayOff(day.dayOff());
            row.setStartTime(day.dayOff() ? LocalTime.of(9, 0) : day.start());
            row.setEndTime(day.dayOff() ? LocalTime.of(20, 0) : day.end());
            row.setBreakStart(day.breakStart());
            row.setBreakEnd(day.breakEnd());
            workingHours.save(row);
        }
        return barber;
    }

    /** A few blocked periods: part of a day tomorrow, a whole day soon, and an afternoon today or tomorrow. */
    private void addBlockedPeriods(Barber barber, Shop shop, int barberIndex) {
        ZoneId zone = shop.zone();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        if (barberIndex % 3 == 0) {
            block(barber, zone, today.plusDays(1), LocalTime.of(14, 0), LocalTime.of(16, 0), "Fake: shifokor");
            block(barber, zone, today.plusDays(3), null, null, "Fake: dam olish kuni");
        } else if (barberIndex % 3 == 1) {
            block(barber, zone, today, LocalTime.of(15, 0), LocalTime.of(17, 0), "Fake: bank");
            block(barber, zone, today.plusDays(2), LocalTime.of(10, 0), LocalTime.of(12, 0), "Fake: o'quv");
        } else {
            block(barber, zone, today.plusDays(2), null, null, "Fake: ta'til");
        }
    }

    private void block(Barber barber, ZoneId zone, LocalDate date, LocalTime from, LocalTime to, String reason) {
        TimeOff off = new TimeOff();
        off.setBarberId(barber.getId());
        off.setStartAt(from == null ? date.atStartOfDay(zone).toInstant() : date.atTime(from).atZone(zone).toInstant());
        off.setEndAt(to == null ? date.plusDays(1).atStartOfDay(zone).toInstant() : date.atTime(to).atZone(zone).toInstant());
        off.setReason(reason);
        timeOffs.save(off);
    }

    /**
     * Past days get finished bookings (came, no-show, cancelled) so statistics have numbers; today and the next
     * days get BOOKED bookings on free slots, so the timeline and the slot picker both look "lived in".
     */
    private void addBookings(Barber barber, Shop shop, Map<Integer, DaySchedule> week, List<AppUser> clients, int barberIndex) {
        if (!shop.isActive()) {
            return;
        }
        ZoneId zone = shop.zone();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        BookingStatus[] pastStatuses = {BookingStatus.COMPLETED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW,
                BookingStatus.CANCELLED_BY_CLIENT, BookingStatus.COMPLETED, BookingStatus.CANCELLED_BY_BARBER};
        int clientCursor = barberIndex;

        for (int offset = -4; offset <= 0; offset++) {
            LocalDate date = today.plusDays(offset);
            DaySchedule day = week.get(date.getDayOfWeek().getValue());
            if (day == null || day.dayOff()) {
                continue;
            }
            List<LocalTime> starts = slotStarts(day, barber.getSlotMinutes());
            int statusIndex = Math.floorMod(date.getDayOfMonth() + barberIndex, pastStatuses.length);
            for (int i = 1; i < starts.size() && i <= 7; i += 3) {
                Instant start = date.atTime(starts.get(i)).atZone(zone).toInstant();
                if (offset == 0 && start.isAfter(clock.instant())) {
                    continue; // today's future slots are handled below as BOOKED
                }
                BookingStatus status = pastStatuses[statusIndex++ % pastStatuses.length];
                saveBooking(barber, shop, start, status, clients.get(clientCursor++ % clients.size()));
            }
        }
        for (int offset = 0; offset <= 4; offset++) {
            LocalDate date = today.plusDays(offset);
            List<LocalTime> free = slots.freeSlots(barber, shop, date, true);
            List<LocalTime> untouched = new ArrayList<>(free);
            for (int i = 1; i < free.size() && i <= 8; i += 3) {
                Instant start = date.atTime(free.get(i)).atZone(zone).toInstant();
                saveBooking(barber, shop, start, BookingStatus.BOOKED, clients.get(clientCursor++ % clients.size()));
                untouched.remove(free.get(i));
            }
            // A walk-in written by the barber, on a slot that is still free.
            if (untouched.size() > 2) {
                Instant start = date.atTime(untouched.get(untouched.size() - 2)).atZone(zone).toInstant();
                Booking manual = booking(barber, shop, start, BookingStatus.BOOKED);
                manual.setSource(BookingSource.MANUAL);
                manual.setClientName("Yo'ldan kelgan mijoz");
                bookings.save(manual);
            }
        }
    }

    private static List<LocalTime> slotStarts(DaySchedule day, int slotMinutes) {
        List<LocalTime> starts = new ArrayList<>();
        for (LocalTime t = day.start(); !t.plusMinutes(slotMinutes).isAfter(day.end()) && t.plusMinutes(slotMinutes).isAfter(t);
             t = t.plusMinutes(slotMinutes)) {
            boolean inBreak = day.hasBreak() && t.isBefore(day.breakEnd()) && t.plusMinutes(slotMinutes).isAfter(day.breakStart());
            if (!inBreak) {
                starts.add(t);
            }
        }
        return starts;
    }

    private void saveBooking(Barber barber, Shop shop, Instant start, BookingStatus status, AppUser client) {
        Booking booking = booking(barber, shop, start, status);
        booking.setSource(BookingSource.BOT);
        booking.setClientUserId(client.getId());
        booking.setClientName(client.getFirstName());
        booking.setClientPhone(client.getPhone());
        if (status == BookingStatus.CANCELLED_BY_CLIENT || status == BookingStatus.CANCELLED_BY_BARBER) {
            booking.setCancelledAt(start.minusSeconds(3600 * 5L));
        }
        bookings.save(booking);
    }

    private Booking booking(Barber barber, Shop shop, Instant start, BookingStatus status) {
        Booking booking = new Booking();
        booking.setShopId(shop.getId());
        booking.setBarberId(barber.getId());
        booking.setStartAt(start);
        booking.setEndAt(start.plusSeconds(barber.getSlotMinutes() * 60L));
        booking.setStatus(status);
        booking.setSource(BookingSource.BOT);
        booking.setCreatedAt(start.minusSeconds(3600L * 24));
        // Fake clients have no Telegram chat: never remind them or ask about attendance.
        booking.setReminderSent(true);
        booking.setAttendanceAsked(status != BookingStatus.BOOKED);
        booking.setClientConfirmed(status == BookingStatus.COMPLETED);
        return booking;
    }

    // ------------------------------------------------------------------ people

    private List<AppUser> createFakeClients() {
        List<AppUser> clients = new ArrayList<>();
        for (int i = 0; i < FAKE_CLIENTS; i++) {
            long telegramId = FAKE_ID_BASE + 100 + i;
            AppUser user = users.findByTelegramId(telegramId).orElseGet(AppUser::new);
            user.setTelegramId(telegramId);
            user.setFirstName(CLIENT_NAMES.get(i % CLIENT_NAMES.size()));
            user.setPhone("+99890" + String.format("%07d", 5_550_100 + i));
            clients.add(users.save(user));
        }
        return clients;
    }

    /**
     * The real person who tests the bot. Defaults to the first super admin, so the admin's own account sees the
     * owner and barber screens filled with data. {@code DEMO_STAFF_TELEGRAM_ID=none} turns this off.
     */
    private AppUser resolveStaffUser() {
        String configured = staffTelegramId == null ? "" : staffTelegramId.trim();
        if (configured.equalsIgnoreCase("none")) {
            return null;
        }
        Long telegramId = null;
        if (!configured.isEmpty()) {
            telegramId = Long.valueOf(configured);
        } else if (!properties.superAdminIds().isEmpty()) {
            telegramId = properties.superAdminIds().getFirst();
        }
        if (telegramId == null) {
            return null;
        }
        long id = telegramId;
        return users.findByTelegramId(id).orElseGet(() -> {
            AppUser user = new AppUser();
            user.setTelegramId(id); // names and language are filled in when the person first writes to the bot
            return users.save(user);
        });
    }

    // ------------------------------------------------------------------ reset

    private void deleteDemoData() {
        String demoShops = "select id from shop where slug like '" + SLUG_PREFIX + "%'";
        String demoBarbers = "select id from barber where shop_id in (" + demoShops + ")";
        jdbc.sql("delete from booking where shop_id in (" + demoShops + ")").update();
        jdbc.sql("delete from time_off where barber_id in (" + demoBarbers + ")").update();
        jdbc.sql("delete from working_hours where barber_id in (" + demoBarbers + ")").update();
        jdbc.sql("delete from price_item where barber_id in (" + demoBarbers + ")").update();
        jdbc.sql("delete from invite where shop_id in (" + demoShops + ")").update();
        jdbc.sql("update app_user set last_shop_id = null where last_shop_id in (" + demoShops + ")").update();
        jdbc.sql("delete from barber where shop_id in (" + demoShops + ")").update();
        jdbc.sql("delete from shop where slug like '" + SLUG_PREFIX + "%'").update();
        jdbc.sql("delete from app_user where telegram_id between :from and :to")
                .param("from", FAKE_ID_BASE).param("to", FAKE_ID_BASE + 999).update();
        log.info("Old demo data deleted.");
    }
}
