package pdp.service_bron.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a map position from text typed or pasted by the shop owner: plain coordinates
 * ({@code 41.2856, 69.2036}) or a link from Google Maps, Yandex Maps or 2GIS. Short links such as
 * {@code maps.app.goo.gl/...} cannot be resolved without a network call and are not supported.
 */
public final class LocationParser {

    /** A point on the map. */
    public record Coordinates(double latitude, double longitude) {
    }

    private static final String NUM = "(-?\\d{1,3}(?:\\.\\d+)?)";
    private static final Pattern PLAIN = Pattern.compile("^\\s*" + NUM + "\\s*[,;\\s]\\s*" + NUM + "\\s*$");
    private static final Pattern GOOGLE_AT = Pattern.compile("@" + NUM + "," + NUM);
    private static final Pattern GOOGLE_DATA = Pattern.compile("!3d" + NUM + "!4d" + NUM);
    private static final Pattern PAIR_PARAM = Pattern.compile("[?&](?:q|query|ll|center|destination|pt|m)=" + NUM + "," + NUM);

    private LocationParser() {
    }

    public static Optional<Coordinates> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        Matcher plain = PLAIN.matcher(text);
        if (plain.matches()) {
            return valid(Double.parseDouble(plain.group(1)), Double.parseDouble(plain.group(2)));
        }
        if (!text.toLowerCase(Locale.ROOT).startsWith("http")) {
            return Optional.empty();
        }
        String url = URLDecoder.decode(text, StandardCharsets.UTF_8);
        String lower = url.toLowerCase(Locale.ROOT);
        // Yandex and 2GIS write "longitude,latitude"; Google writes "latitude,longitude".
        boolean lonFirst = lower.contains("yandex.") || lower.contains("2gis.");

        Matcher data = GOOGLE_DATA.matcher(url);
        if (data.find()) {
            return valid(Double.parseDouble(data.group(1)), Double.parseDouble(data.group(2)));
        }
        Matcher at = GOOGLE_AT.matcher(url);
        if (at.find()) {
            return valid(Double.parseDouble(at.group(1)), Double.parseDouble(at.group(2)));
        }
        Matcher pair = PAIR_PARAM.matcher(url);
        if (pair.find()) {
            double first = Double.parseDouble(pair.group(1));
            double second = Double.parseDouble(pair.group(2));
            return lonFirst ? valid(second, first) : valid(first, second);
        }
        return Optional.empty();
    }

    private static Optional<Coordinates> valid(double latitude, double longitude) {
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            return Optional.empty();
        }
        return Optional.of(new Coordinates(latitude, longitude));
    }
}
