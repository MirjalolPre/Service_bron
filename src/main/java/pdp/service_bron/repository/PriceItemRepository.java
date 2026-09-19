package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pdp.service_bron.domain.PriceItem;

import java.util.List;

public interface PriceItemRepository extends JpaRepository<PriceItem, Long> {

    List<PriceItem> findByBarberIdAndActiveTrueOrderBySortOrderAscIdAsc(Long barberId);

    long countByBarberIdAndActiveTrue(Long barberId);
}
