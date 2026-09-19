package pdp.service_bron.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalTime;

@Entity
@Table(name = "working_hours")
@Getter
@Setter
@NoArgsConstructor
public class WorkingHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long barberId;

    /** ISO day of week, 1 = Monday ... 7 = Sunday. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false)
    private int dayOfWeek;

    @Column(nullable = false)
    private boolean dayOff = false;

    private LocalTime startTime;
    private LocalTime endTime;
    private LocalTime breakStart;
    private LocalTime breakEnd;

    public boolean hasBreak() {
        return breakStart != null && breakEnd != null;
    }
}
