package pdp.service_bron.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.service.I18nService;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formats dates and times in Uzbek or Russian. Names of days and months come from the message bundles. */
@Component
@RequiredArgsConstructor
public class TimeFormatter {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final I18nService i18n;

    public String weekdayShort(Lang lang, DayOfWeek day) {
        return i18n.t(lang, "date.weekday.short." + day.getValue());
    }

    public String weekdayLong(Lang lang, DayOfWeek day) {
        return i18n.t(lang, "date.weekday.long." + day.getValue());
    }

    public String monthShort(Lang lang, Month month) {
        return i18n.t(lang, "date.month.short." + month.getValue());
    }

    public String monthLong(Lang lang, Month month) {
        return i18n.t(lang, "date.month.long." + month.getValue());
    }

    /** Button label for a date picker: {@code Bugun, 16-sen}, {@code Ertaga, 17-sen}, {@code Pa, 18-sen}. */
    public String dayButton(Lang lang, LocalDate date, LocalDate today) {
        String first;
        if (date.equals(today)) {
            first = i18n.t(lang, "date.today");
        } else if (date.equals(today.plusDays(1))) {
            first = i18n.t(lang, "date.tomorrow");
        } else {
            first = weekdayShort(lang, date.getDayOfWeek());
        }
        return i18n.t(lang, "date.button", first, date.getDayOfMonth(), monthShort(lang, date.getMonth()));
    }

    /** "bugun", "ertaga" or {@code 18-sentabr}: for use inside a sentence. */
    public String dayWord(Lang lang, LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return i18n.t(lang, "date.today_lower");
        }
        if (date.equals(today.plusDays(1))) {
            return i18n.t(lang, "date.tomorrow_lower");
        }
        return shortDate(lang, date);
    }

    /** {@code 18-sentabr, payshanba}. */
    public String longDate(Lang lang, LocalDate date) {
        return i18n.t(lang, "date.long", weekdayLong(lang, date.getDayOfWeek()), date.getDayOfMonth(),
                monthLong(lang, date.getMonth()));
    }

    /** {@code 18-sentabr} (no weekday). */
    public String shortDate(Lang lang, LocalDate date) {
        return i18n.t(lang, "date.short", date.getDayOfMonth(), monthLong(lang, date.getMonth()));
    }

    public String time(LocalTime time) {
        return time.format(HH_MM);
    }

    public String time(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalTime().format(HH_MM);
    }

    /** {@code 18-sentabr, payshanba, 15:30}. */
    public String longDateTime(Lang lang, Instant instant, ZoneId zone) {
        return longDate(lang, instant.atZone(zone).toLocalDate()) + ", " + time(instant, zone);
    }

    /** {@code 18-sentabr 15:30}. */
    public String shortDateTime(Lang lang, Instant instant, ZoneId zone) {
        return shortDate(lang, instant.atZone(zone).toLocalDate()) + " " + time(instant, zone);
    }
}
