package pdp.service_bron.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "invite")
@Getter
@Setter
@NoArgsConstructor
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InviteType type;

    @Column(nullable = false)
    private Long shopId;

    private Long createdBy;

    @Column(nullable = false)
    private Instant expiresAt;

    private Long usedBy;
    private Instant usedAt;
}
