package pdp.service_bron.util;

import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses times typed by barbers, e.g. {@code 09:00-20:00}. */
public final class TimeParser {

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})[:.](\\d{2})$");
    private static final Pattern RANGE = Pattern.compile("^\\s*(\\d{1,2}[:.]\\d{2})\\s*[-–—]\\s*(\\d{1,2}[:.]\\d{2})\\s*$");

    private TimeParser() {
    }

    public record TimeRange(LocalTime start, LocalTime end) {
        /** A working day never crosses midnight, so the end must be after the start. */
        public boolean isValid() {
            return end.isAfter(start);
        }
    }

    public static Optional<LocalTime> parseTime(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = TIME.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.of(h, min));
    }

    /** Parses {@code HH:mm-HH:mm}. Returns empty when the format is wrong; validity of the order is separate. */
    public static Optional<TimeRange> parseRange(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = RANGE.matcher(raw);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<LocalTime> start = parseTime(m.group(1));
        Optional<LocalTime> end = parseTime(m.group(2));
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TimeRange(start.get(), end.get()));
    }
}
