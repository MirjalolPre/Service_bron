package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pdp.service_bron.domain.AppUser;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTelegramId(Long telegramId);
}
