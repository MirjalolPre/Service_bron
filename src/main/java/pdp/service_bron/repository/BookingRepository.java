package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** BOOKED bookings of a barber that overlap [start, end). */
    @Query("""
            select b from Booking b
            where b.barberId = :barberId and b.status = pdp.service_bron.domain.BookingStatus.BOOKED
              and b.startAt < :end and b.endAt > :start
            order by b.startAt""")
    List<Booking> findBookedOverlapping(@Param("barberId") Long barberId,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end);

    /** Bookings of a barber (any of the given statuses) starting in [from, to). */
    @Query("""
            select b from Booking b
            where b.barberId = :barberId and b.status in :statuses
              and b.startAt >= :from and b.startAt < :to
            order by b.startAt""")
    List<Booking> findForBarber(@Param("barberId") Long barberId,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                @Param("statuses") Collection<BookingStatus> statuses);

    @Query("""
            select b from Booking b
            where b.shopId = :shopId and b.status = pdp.service_bron.domain.BookingStatus.BOOKED
              and b.startAt >= :from and b.startAt < :to
            order by b.barberId, b.startAt""")
    List<Booking> findBookedForShop(@Param("shopId") Long shopId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    /** Future BOOKED bookings of a client (not yet ended). */
    @Query("""
            select b from Booking b
            where b.clientUserId = :clientId and b.status = pdp.service_bron.domain.BookingStatus.BOOKED
              and b.endAt > :now
            order by b.startAt""")
    List<Booking> findActiveForClient(@Param("clientId") Long clientId, @Param("now") Instant now);

    @Query("""
            select count(b) from Booking b
            where b.clientUserId = :clientId and b.shopId = :shopId
              and b.status = pdp.service_bron.domain.BookingStatus.BOOKED and b.endAt > :now""")
    long countActiveForClientInShop(@Param("clientId") Long clientId,
                                    @Param("shopId") Long shopId,
                                    @Param("now") Instant now);

    long countByClientUserIdAndShopIdAndStatus(Long clientUserId, Long shopId, BookingStatus status);

    /** BOOKED bookings whose reminder is not yet handled and that start in the future. */
    @Query("""
            select b from Booking b
            where b.status = pdp.service_bron.domain.BookingStatus.BOOKED
              and b.reminderSent = false and b.startAt > :now
            order by b.startAt""")
    List<Booking> findPendingReminders(@Param("now") Instant now);

    @Query("""
            select b from Booking b
            where b.status = pdp.service_bron.domain.BookingStatus.BOOKED
              and b.attendanceAsked = false and b.endAt <= :threshold
            order by b.startAt""")
    List<Booking> findPendingAttendance(@Param("threshold") Instant threshold);

    /** Marks the reminder as handled without touching any other column (safe against concurrent changes). */
    @Transactional
    @Modifying
    @Query("update Booking b set b.reminderSent = true where b.id = :id")
    int markReminderSent(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("update Booking b set b.attendanceAsked = true where b.id = :id")
    int markAttendanceAsked(@Param("id") Long id);

    /** Past bookings of a shop that are still BOOKED become COMPLETED. Returns how many were changed. */
    @Transactional
    @Modifying
    @Query("""
            update Booking b set b.status = pdp.service_bron.domain.BookingStatus.COMPLETED
            where b.shopId = :shopId and b.status = pdp.service_bron.domain.BookingStatus.BOOKED and b.endAt <= :now""")
    int completePast(@Param("shopId") Long shopId, @Param("now") Instant now);

    @Query("""
            select count(b) from Booking b
            where b.barberId = :barberId and b.status = :status
              and b.startAt >= :from and b.startAt < :to""")
    long countForBarber(@Param("barberId") Long barberId,
                        @Param("status") BookingStatus status,
                        @Param("from") Instant from,
                        @Param("to") Instant to);

    /** Bookings that count as "scheduled" (booked, completed or no-show) in [from, to), for global stats. */
    @Query("""
            select count(b) from Booking b
            where b.status in (pdp.service_bron.domain.BookingStatus.BOOKED,
                               pdp.service_bron.domain.BookingStatus.COMPLETED,
                               pdp.service_bron.domain.BookingStatus.NO_SHOW)
              and b.startAt >= :from and b.startAt < :to""")
    long countScheduled(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(distinct b.clientUserId) from Booking b where b.clientUserId is not null")
    long countDistinctClients();
}
