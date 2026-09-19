package pdp.service_bron.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingSource;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.AppUserRepository;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Creates and changes bookings. Double booking is impossible: besides the re-validation inside the transaction,
 * the database has an exclusion constraint on (barber, time range) for BOOKED rows. When two clients press the
 * same slot at the same moment, exactly one insert wins and the other is reported as {@link Reason#SLOT_TAKEN}.
 */
@Slf4j
@Service
public class BookingService {

    private static final int MAX_NAME_LENGTH = 100;

    /** Why a booking was refused. */
    public enum Reason {
        NOT_FOUND, SHOP_INACTIVE, BARBER_UNAVAILABLE, PHONE_REQUIRED, LIMIT_REACHED, SLOT_TAKEN
    }

    public sealed interface BookResult permits BookResult.Ok, BookResult.Rejected {
        record Ok(Booking booking) implements BookResult {
        }

        record Rejected(Reason reason) implements BookResult {
        }
    }

    private final BookingRepository bookings;
    private final BarberRepository barbers;
    private final ShopRepository shops;
    private final AppUserRepository users;
    private final SlotService slots;
    private final AccessService access;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BookingService(BookingRepository bookings, BarberRepository barbers, ShopRepository shops,
                          AppUserRepository users, SlotService slots, AccessService access, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.bookings = bookings;
        this.barbers = barbers;
        this.shops = shops;
        this.users = users;
        this.slots = slots;
        this.access = access;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ creating

    /** A client books a free slot. Everything is re-validated inside one transaction. */
    public BookResult book(Long clientUserId, Long barberId, LocalDate date, LocalTime time) {
        try {
            return tx.execute(status -> doBook(clientUserId, barberId, date, time));
        } catch (DataIntegrityViolationException e) {
            if (isExclusionViolation(e)) {
                log.info("Slot taken concurrently: barber {} {} {}", barberId, date, time);
                return new BookResult.Rejected(Reason.SLOT_TAKEN);
            }
            throw e;
        }
    }

    private BookResult doBook(Long clientUserId, Long barberId, LocalDate date, LocalTime time) {
        Barber barber = barbers.findById(barberId).orElse(null);
        AppUser client = users.findById(clientUserId).orElse(null);
        if (barber == null || client == null) {
            return new BookResult.Rejected(Reason.NOT_FOUND);
        }
        Shop shop = shops.findById(barber.getShopId()).orElse(null);
        if (shop == null) {
            return new BookResult.Rejected(Reason.NOT_FOUND);
        }
        if (!shop.isActive()) {
            return new BookResult.Rejected(Reason.SHOP_INACTIVE);
        }
        if (!barber.isActive() || !barber.isAcceptingBookings()) {
            return new BookResult.Rejected(Reason.BARBER_UNAVAILABLE);
        }
        if (client.getPhone() == null || client.getPhone().isBlank()) {
            return new BookResult.Rejected(Reason.PHONE_REQUIRED);
        }
        Instant now = clock.instant();
        if (bookings.countActiveForClientInShop(client.getId(), shop.getId(), now) >= shop.getMaxActiveBookingsPerClient()) {
            return new BookResult.Rejected(Reason.LIMIT_REACHED);
        }
        if (!slots.isFree(barber, shop, date, time, false)) {
            return new BookResult.Rejected(Reason.SLOT_TAKEN);
        }
        Booking booking = newBooking(barber, shop, date, time, BookingSource.BOT);
        booking.setClientUserId(client.getId());
        booking.setClientName(client.displayName());
        booking.setClientPhone(client.getPhone());
        return new BookResult.Ok(bookings.saveAndFlush(booking));
    }

    /**
     * The barber (or the shop owner) adds a walk-in or phone booking. It blocks the slot like any other booking.
     * Unlike client bookings, the minimum lead time does not apply.
     */
    public BookResult bookManual(AppUser actor, Long barberId, LocalDate date, LocalTime time,
                                 String clientName, String clientPhone) {
        if (!access.canManageBarber(actor, barberId)) {
            throw new BusinessException("error.forbidden");
        }
        String name = clientName == null ? "" : clientName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("manual.name_invalid");
        }
        try {
            return tx.execute(status -> {
                Barber barber = barbers.findById(barberId).orElse(null);
                Shop shop = barber == null ? null : shops.findById(barber.getShopId()).orElse(null);
                if (barber == null || shop == null) {
                    return new BookResult.Rejected(Reason.NOT_FOUND);
                }
                if (!slots.isFree(barber, shop, date, time, true)) {
                    return new BookResult.Rejected(Reason.SLOT_TAKEN);
                }
                Booking booking = newBooking(barber, shop, date, time, BookingSource.MANUAL);
                booking.setClientName(name);
                booking.setClientPhone(clientPhone);
                return new BookResult.Ok(bookings.saveAndFlush(booking));
            });
        } catch (DataIntegrityViolationException e) {
            if (isExclusionViolation(e)) {
                return new BookResult.Rejected(Reason.SLOT_TAKEN);
            }
            throw e;
        }
    }

    private Booking newBooking(Barber barber, Shop shop, LocalDate date, LocalTime time, BookingSource source) {
        ZoneId zone = shop.zone();
        Instant start = date.atTime(time).atZone(zone).toInstant();
        Booking booking = new Booking();
        booking.setShopId(shop.getId());
        booking.setBarberId(barber.getId());
        booking.setStartAt(start);
        booking.setEndAt(start.plusSeconds(barber.getSlotMinutes() * 60L));
        booking.setStatus(BookingStatus.BOOKED);
        booking.setSource(source);
        booking.setCreatedAt(clock.instant());
        return booking;
    }

    /** True when the failure is the exclusion constraint on overlapping BOOKED rows (SQLSTATE 23P01). */
    static boolean isExclusionViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && "23P01".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("booking_no_overlap")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ changing

    /** The client cancels their own future booking. */
    @Transactional
    public Booking cancelByClient(Long bookingId, AppUser actor) {
        Booking booking = require(bookingId);
        if (!actor.getId().equals(booking.getClientUserId())) {
            throw new BusinessException("error.forbidden");
        }
        requireBooked(booking);
        if (!booking.getStartAt().isAfter(clock.instant())) {
            throw new BusinessException("booking.already_started");
        }
        booking.setStatus(BookingStatus.CANCELLED_BY_CLIENT);
        booking.setCancelledAt(clock.instant());
        return bookings.save(booking);
    }

    /** The barber (or the shop owner) cancels a booking. The reason is optional. */
    @Transactional
    public Booking cancelByBarber(Long bookingId, AppUser actor, String reason) {
        Booking booking = require(bookingId);
        if (!access.canManageBarber(actor, booking.getBarberId())) {
            throw new BusinessException("error.forbidden");
        }
        requireBooked(booking);
        booking.setStatus(BookingStatus.CANCELLED_BY_BARBER);
        booking.setCancelledAt(clock.instant());
        booking.setCancelReason(reason == null || reason.isBlank() ? null : reason.trim());
        return bookings.save(booking);
    }

    /** Cancels every BOOKED booking that overlaps the range (used when closing time or deactivating a barber). */
    @Transactional
    public List<Booking> cancelOverlappingByBarber(Long barberId, AppUser actor, Instant start, Instant end, String reason) {
        if (!access.canManageBarber(actor, barberId)) {
            throw new BusinessException("error.forbidden");
        }
        List<Booking> overlapping = bookings.findBookedOverlapping(barberId, start, end);
        Instant now = clock.instant();
        for (Booking booking : overlapping) {
            booking.setStatus(BookingStatus.CANCELLED_BY_BARBER);
            booking.setCancelledAt(now);
            booking.setCancelReason(reason);
        }
        return bookings.saveAll(overlapping);
    }

    /** Marks a started booking as COMPLETED (client came) or NO_SHOW. */
    @Transactional
    public Booking markAttendance(Long bookingId, AppUser actor, boolean came) {
        Booking booking = require(bookingId);
        if (!access.canManageBarber(actor, booking.getBarberId())) {
            throw new BusinessException("error.forbidden");
        }
        requireBooked(booking);
        if (booking.getStartAt().isAfter(clock.instant())) {
            throw new BusinessException("booking.not_started");
        }
        booking.setStatus(came ? BookingStatus.COMPLETED : BookingStatus.NO_SHOW);
        return bookings.save(booking);
    }

    /** The client pressed "Boraman" after the reminder. Idempotent. */
    @Transactional
    public Booking confirmByClient(Long bookingId, AppUser actor) {
        Booking booking = require(bookingId);
        if (!actor.getId().equals(booking.getClientUserId())) {
            throw new BusinessException("error.forbidden");
        }
        requireBooked(booking);
        if (!booking.getStartAt().isAfter(clock.instant())) {
            throw new BusinessException("booking.already_started");
        }
        if (!booking.isClientConfirmed()) {
            booking.setClientConfirmed(true);
            return bookings.save(booking);
        }
        return booking;
    }

    private Booking require(Long id) {
        return bookings.findById(id).orElseThrow(() -> new BusinessException("error.not_found"));
    }

    private void requireBooked(Booking booking) {
        if (booking.getStatus() != BookingStatus.BOOKED) {
            throw new BusinessException("booking.not_active");
        }
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Optional<Booking> find(Long id) {
        return bookings.findById(id);
    }

    /** Future BOOKED bookings of a client, soonest first. */
    @Transactional(readOnly = true)
    public List<Booking> activeForClient(Long clientUserId) {
        return bookings.findActiveForClient(clientUserId, clock.instant());
    }

    /** Bookings of a barber on a local date: BOOKED, COMPLETED and NO_SHOW, in time order. */
    @Transactional(readOnly = true)
    public List<Booking> forBarberOnDate(Barber barber, Shop shop, LocalDate date) {
        Instant from = date.atStartOfDay(shop.zone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(shop.zone()).toInstant();
        return bookings.findForBarber(barber.getId(), from, to,
                EnumSet.of(BookingStatus.BOOKED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW));
    }

    /** BOOKED bookings of the whole shop on a local date, ordered by barber and time. */
    @Transactional(readOnly = true)
    public List<Booking> bookedForShopOnDate(Shop shop, LocalDate date) {
        return bookings.findBookedForShop(shop.getId(),
                date.atStartOfDay(shop.zone()).toInstant(), date.plusDays(1).atStartOfDay(shop.zone()).toInstant());
    }

    /** BOOKED bookings of a barber that overlap the range. */
    @Transactional(readOnly = true)
    public List<Booking> bookedOverlapping(Long barberId, Instant start, Instant end) {
        return bookings.findBookedOverlapping(barberId, start, end);
    }

    /** How many times this client did not show up in this shop. */
    @Transactional(readOnly = true)
    public long noShowCount(Long clientUserId, Long shopId) {
        if (clientUserId == null) {
            return 0;
        }
        return bookings.countByClientUserIdAndShopIdAndStatus(clientUserId, shopId, BookingStatus.NO_SHOW);
    }
}
