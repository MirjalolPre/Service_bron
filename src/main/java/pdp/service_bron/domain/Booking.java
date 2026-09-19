package pdp.service_bron.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "booking")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shopId;

    @Column(nullable = false)
    private Long barberId;

    /** Null for manual (walk-in / phone) bookings. */
    private Long clientUserId;

    private String clientName;
    private String clientPhone;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BookingStatus status = BookingStatus.BOOKED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private BookingSource source = BookingSource.BOT;

    private String note;

    @Column(nullable = false)
    private boolean reminderSent = false;

    @Column(nullable = false)
    private boolean attendanceAsked = false;

    @Column(nullable = false)
    private boolean clientConfirmed = false;

    private String cancelReason;
    private Instant cancelledAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public boolean isBooked() {
        return status == BookingStatus.BOOKED;
    }
}
