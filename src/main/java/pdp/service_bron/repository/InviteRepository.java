package pdp.service_bron.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pdp.service_bron.domain.Invite;

import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, Long> {

    Optional<Invite> findByToken(String token);
}
