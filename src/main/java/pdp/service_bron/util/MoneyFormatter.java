package pdp.service_bron.util;

import java.util.Optional;

/** Money helpers. Amounts are whole so'm. */
public final class MoneyFormatter {

    public static final long MAX_PRICE = 100_000_000L;

    private MoneyFormatter() {
    }

    /** {@code 60000} becomes {@code "60 000"}. */
    public static String group(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + sb;
    }

    /** Parses {@code 60000}, {@code 60 000}, {@code 60k}, {@code 60К}, {@code 60.000}, {@code 60,000}. */
    public static Optional<Long> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase().replace(" ", " ").replace(" ", "");
        long multiplier = 1;
        if (text.endsWith("k") || text.endsWith("к")) {
            multiplier = 1000;
            text = text.substring(0, text.length() - 1);
        }
        text = text.replace(".", "").replace(",", "");
        if (text.isEmpty() || !text.matches("\\d{1,9}")) {
            return Optional.empty();
        }
        long value = Long.parseLong(text) * multiplier;
        if (value > MAX_PRICE) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
