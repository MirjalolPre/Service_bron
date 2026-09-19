package pdp.service_bron.util;

import org.junit.jupiter.api.Test;
import pdp.service_bron.telegram.CallbackData;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class CompactTimeTest {

    @Test
    void datesRoundTrip() {
        LocalDate date = LocalDate.of(2026, 9, 18);

        assertThat(CompactTime.date(date)).isEqualTo("20260918");
        assertThat(CompactTime.parseDate("20260918")).isEqualTo(date);
    }

    @Test
    void dateTimesRoundTrip() {
        LocalDateTime value = LocalDateTime.of(2026, 9, 18, 15, 30);

        assertThat(CompactTime.dateTime(value)).isEqualTo("202609181530");
        assertThat(CompactTime.parseDateTime("202609181530")).isEqualTo(value);
    }

    @Test
    void timesRoundTripAndNoneIsADash() {
        assertThat(CompactTime.hm(LocalTime.of(9, 5))).isEqualTo("0905");
        assertThat(CompactTime.parseHm("0905")).isEqualTo(LocalTime.of(9, 5));
        assertThat(CompactTime.hm(null)).isEqualTo("-");
        assertThat(CompactTime.parseHm("-")).isNull();
    }

    @Test
    void theLongestCallbacksStayWithin64Bytes() {
        // Worst cases used by the handlers: large ids plus a full date-time.
        String slot = CallbackData.encode("c", "slot", 9_999_999_999L, CompactTime.dateTime(LocalDateTime.of(2026, 12, 31, 23, 30)));
        String hours = CallbackData.encode("b", "hgo", 7, "0900", "2000", "1300", "1400");
        String force = CallbackData.encode("b", "tforce", "20261231", "1400", "1600");

        assertThat(slot.getBytes().length).isLessThanOrEqualTo(64);
        assertThat(hours.getBytes().length).isLessThanOrEqualTo(64);
        assertThat(force.getBytes().length).isLessThanOrEqualTo(64);
    }
}
