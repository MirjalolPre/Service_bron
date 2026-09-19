package pdp.service_bron.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "barber")
@Getter
@Setter
@NoArgsConstructor
public class Barber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shopId;

    private Long userId;

    @Column(nullable = false)
    private String displayName;

    private String bio;
    private String photoFileId;

    @Column(nullable = false)
    private int slotMinutes = 30;

    @Column(nullable = false)
    private boolean acceptingBookings = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
