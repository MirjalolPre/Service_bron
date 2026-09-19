package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pdp.service_bron.domain.Barber;

import java.util.List;
import java.util.Optional;

public interface BarberRepository extends JpaRepository<Barber, Long> {

    List<Barber> findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(Long shopId);

    List<Barber> findByShopIdOrderBySortOrderAscIdAsc(Long shopId);

    List<Barber> findByUserIdAndActiveTrueOrderByIdAsc(Long userId);

    Optional<Barber> findByShopIdAndUserId(Long shopId, Long userId);

    long countByShopId(Long shopId);

    long countByActive(boolean active);
}
