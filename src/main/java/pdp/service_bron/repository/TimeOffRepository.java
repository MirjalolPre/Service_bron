package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pdp.service_bron.domain.TimeOff;

import java.time.Instant;
import java.util.List;

public interface TimeOffRepository extends JpaRepository<TimeOff, Long> {

    /** Time-off ranges that overlap [start, end). */
    @Query("select t from TimeOff t where t.barberId = :barberId and t.startAt < :end and t.endAt > :start order by t.startAt")
    List<TimeOff> findOverlapping(@Param("barberId") Long barberId,
                                  @Param("start") Instant start,
                                  @Param("end") Instant end);

    List<TimeOff> findByBarberIdAndEndAtAfterOrderByStartAtAsc(Long barberId, Instant after);
}
