package pdp.service_bron.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.service.I18nService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TimeFormatterTest {

    // 2025-09-16 is a Tuesday; 2025-09-18 is a Thursday (as in the spec examples).
    private static final LocalDate TODAY = LocalDate.of(2025, 9, 16);
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private TimeFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new TimeFormatter(new I18nService());
    }

    @Test
    void dayButtonsInUzbek() {
        assertThat(formatter.dayButton(Lang.UZ, TODAY, TODAY)).isEqualTo("Bugun, 16-sen");
        assertThat(formatter.dayButton(Lang.UZ, TODAY.plusDays(1), TODAY)).isEqualTo("Ertaga, 17-sen");
        assertThat(formatter.dayButton(Lang.UZ, TODAY.plusDays(2), TODAY)).isEqualTo("Pa, 18-sen");
    }

    @Test
    void dayButtonsInRussian() {
        assertThat(formatter.dayButton(Lang.RU, TODAY, TODAY)).isEqualTo("Сегодня, 16 сен");
        assertThat(formatter.dayButton(Lang.RU, TODAY.plusDays(1), TODAY)).isEqualTo("Завтра, 17 сен");
        assertThat(formatter.dayButton(Lang.RU, TODAY.plusDays(2), TODAY)).isEqualTo("Чт, 18 сен");
    }

    @Test
    void longDates() {
        assertThat(formatter.longDate(Lang.UZ, LocalDate.of(2025, 9, 18))).isEqualTo("18-sentabr, payshanba");
        assertThat(formatter.longDate(Lang.RU, LocalDate.of(2025, 9, 18))).isEqualTo("18 сентября, четверг");
    }

    @Test
    void weekdayAndMonthNamesCoverEverything() {
        for (Lang lang : Lang.values()) {
            for (int d = 1; d <= 7; d++) {
                assertThat(formatter.weekdayShort(lang, java.time.DayOfWeek.of(d))).doesNotStartWith("!");
                assertThat(formatter.weekdayLong(lang, java.time.DayOfWeek.of(d))).doesNotStartWith("!");
            }
            for (int m = 1; m <= 12; m++) {
                assertThat(formatter.monthShort(lang, java.time.Month.of(m))).doesNotStartWith("!");
                assertThat(formatter.monthLong(lang, java.time.Month.of(m))).doesNotStartWith("!");
            }
        }
    }

    @Test
    void uzbekWeekdaysMatchTheSpec() {
        String[] expected = {"Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya"};
        for (int d = 1; d <= 7; d++) {
            assertThat(formatter.weekdayShort(Lang.UZ, java.time.DayOfWeek.of(d))).isEqualTo(expected[d - 1]);
        }
    }

    @Test
    void timesAreInTheShopTimeZone() {
        Instant instant = Instant.parse("2025-09-18T10:30:00Z"); // 15:30 in Tashkent (UTC+5)

        assertThat(formatter.time(instant, TASHKENT)).isEqualTo("15:30");
        assertThat(formatter.time(LocalTime.of(9, 5))).isEqualTo("09:05");
        assertThat(formatter.longDateTime(Lang.UZ, instant, TASHKENT)).isEqualTo("18-sentabr, payshanba, 15:30");
        assertThat(formatter.shortDateTime(Lang.UZ, instant, TASHKENT)).isEqualTo("18-sentabr 15:30");
        assertThat(formatter.shortDateTime(Lang.RU, instant, TASHKENT)).isEqualTo("18 сентября 15:30");
    }

    @Test
    void midnightShiftsTheDateAcrossTimeZones() {
        Instant instant = Instant.parse("2025-09-18T20:30:00Z"); // already 19th in Tashkent

        assertThat(formatter.shortDateTime(Lang.UZ, instant, TASHKENT)).isEqualTo("19-sentabr 01:30");
    }
}
