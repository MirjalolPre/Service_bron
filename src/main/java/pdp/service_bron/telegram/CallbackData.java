package pdp.service_bron.telegram;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Compact callback data: {@code domain:action:arg1:arg2...}, for example {@code c:bar:12} or
 * {@code c:slot:12:202609181530}. Telegram limits callback_data to 64 bytes.
 *
 * <p>Domains: {@code c} client, {@code b} barber, {@code o} owner, {@code a} admin, {@code x} common.
 * Arguments must never contain {@code ':'}. Ids in callback data are never trusted: handlers re-check permissions.
 */
public record CallbackData(String domain, String action, List<String> args) {

    public static final int MAX_BYTES = 64;
    private static final String SEPARATOR = ":";

    public CallbackData {
        args = List.copyOf(args);
    }

    /** Encodes the parts into a callback string. Throws when the result is longer than 64 bytes. */
    public static String encode(String domain, String action, Object... args) {
        StringBuilder sb = new StringBuilder(domain).append(SEPARATOR).append(action);
        for (Object arg : args) {
            String value = String.valueOf(arg);
            if (value.contains(SEPARATOR)) {
                throw new IllegalArgumentException("Callback argument must not contain ':' -> " + value);
            }
            sb.append(SEPARATOR).append(value);
        }
        String result = sb.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Callback data is longer than 64 bytes: " + result);
        }
        return result;
    }

    /** Parses a callback string. Returns {@code null} for malformed data. */
    public static CallbackData parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(SEPARATOR, -1);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return new CallbackData(parts[0], parts[1], Arrays.asList(parts).subList(2, parts.length));
    }

    public boolean is(String action) {
        return this.action.equals(action);
    }

    public String arg(int index) {
        if (index < 0 || index >= args.size()) {
            throw new IllegalArgumentException("Missing callback argument #" + index + " in " + this);
        }
        return args.get(index);
    }

    public long longArg(int index) {
        return Long.parseLong(arg(index));
    }

    public int intArg(int index) {
        return Integer.parseInt(arg(index));
    }

    public boolean hasArg(int index) {
        return index >= 0 && index < args.size();
    }

    @Override
    public String toString() {
        return domain + SEPARATOR + action + (args.isEmpty() ? "" : SEPARATOR + String.join(SEPARATOR, args));
    }
}
