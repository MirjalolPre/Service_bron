package pdp.service_bron.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneId;

@Entity
@Table(name = "shop")
@Getter
@Setter
@NoArgsConstructor
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    private String address;
    private String landmark;
    private Double latitude;
    private Double longitude;
    private String phone;
    private String photoFileId;
    private String description;

    @Column(nullable = false)
    private String timezone = "Asia/Tashkent";

    @Column(nullable = false)
    private int bookingHorizonDays = 7;

    @Column(nullable = false)
    private int minLeadMinutes = 30;

    @Column(nullable = false)
    private int reminderMinutesBefore = 120;

    @Column(nullable = false)
    private int maxActiveBookingsPerClient = 2;

    @Column(nullable = false)
    private boolean active = true;

    private Long ownerUserId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }
}
