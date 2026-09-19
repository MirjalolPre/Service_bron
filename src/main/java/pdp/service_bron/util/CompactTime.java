package pdp.service_bron.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Compact date/time encoding for {@code callback_data} (64 bytes max): {@code 20260918}, {@code 202609181530}, {@code 0900}. */
public final class CompactTime {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HHmm");

    private CompactTime() {
    }

    public static String date(LocalDate date) {
        return date.format(DATE);
    }

    public static LocalDate parseDate(String value) {
        return LocalDate.parse(value, DATE);
    }

    public static String dateTime(LocalDateTime value) {
        return value.format(DATE_TIME);
    }

    public static LocalDateTime parseDateTime(String value) {
        return LocalDateTime.parse(value, DATE_TIME);
    }

    /** {@code 0900}, or {@code -} for "none". */
    public static String hm(LocalTime time) {
        return time == null ? "-" : time.format(HM);
    }

    public static LocalTime parseHm(String value) {
        return "-".equals(value) ? null : LocalTime.parse(value, HM);
    }
}
