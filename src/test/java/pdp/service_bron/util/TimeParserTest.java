package pdp.service_bron.util;

import org.junit.jupiter.api.Test;
import pdp.service_bron.util.TimeParser.TimeRange;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TimeParserTest {

    @Test
    void parsesRanges() {
        assertThat(TimeParser.parseRange("09:00-20:00"))
                .contains(new TimeRange(LocalTime.of(9, 0), LocalTime.of(20, 0)));
        assertThat(TimeParser.parseRange(" 9:30 – 18:15 "))
                .contains(new TimeRange(LocalTime.of(9, 30), LocalTime.of(18, 15)));
        assertThat(TimeParser.parseRange("13.00-14.00"))
                .contains(new TimeRange(LocalTime.of(13, 0), LocalTime.of(14, 0)));
    }

    @Test
    void rejectsBadFormats() {
        assertThat(TimeParser.parseRange("9-18")).isEmpty();
        assertThat(TimeParser.parseRange("09:00")).isEmpty();
        assertThat(TimeParser.parseRange("25:00-26:00")).isEmpty();
        assertThat(TimeParser.parseRange("09:60-10:00")).isEmpty();
        assertThat(TimeParser.parseRange("")).isEmpty();
        assertThat(TimeParser.parseRange(null)).isEmpty();
    }

    @Test
    void rangeThatCrossesMidnightIsNotValid() {
        assertThat(TimeParser.parseRange("22:00-02:00").orElseThrow().isValid()).isFalse();
        assertThat(TimeParser.parseRange("10:00-10:00").orElseThrow().isValid()).isFalse();
        assertThat(TimeParser.parseRange("10:00-10:01").orElseThrow().isValid()).isTrue();
    }
}
