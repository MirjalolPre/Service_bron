package pdp.service_bron.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    private String firstName;
    private String lastName;
    private String username;
    private String phone;

    @Column(nullable = false, length = 2)
    private String language = "uz";

    private Long lastShopId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Lang lang() {
        return Lang.fromCode(language);
    }

    /** Best human-readable name of this user. */
    public String displayName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return String.valueOf(telegramId);
    }
}
