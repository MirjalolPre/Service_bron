package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pdp.service_bron.domain.WorkingHours;

import java.util.List;
import java.util.Optional;

public interface WorkingHoursRepository extends JpaRepository<WorkingHours, Long> {

    List<WorkingHours> findByBarberIdOrderByDayOfWeekAsc(Long barberId);

    Optional<WorkingHours> findByBarberIdAndDayOfWeek(Long barberId, int dayOfWeek);

    long countByBarberId(Long barberId);
}
