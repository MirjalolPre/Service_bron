package pdp.service_bron.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pdp.service_bron.domain.Shop;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Shop> findByActiveTrue(Pageable pageable);

    Page<Shop> findByActiveTrueAndNameContainingIgnoreCase(String name, Pageable pageable);

    List<Shop> findByOwnerUserIdOrderByIdAsc(Long ownerUserId);

    long countByActive(boolean active);
}
