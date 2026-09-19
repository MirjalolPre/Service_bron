package pdp.service_bron.util;

import java.util.Optional;

/** Phone number helpers: normalization to {@code +998XXXXXXXXX}, pretty printing and log masking. */
public final class PhoneFormatter {

    private PhoneFormatter() {
    }

    /**
     * Normalizes user input or a Telegram contact number.
     * <ul>
     *   <li>9 digits are treated as a local Uzbek number ({@code 901234567} becomes {@code +998901234567})</li>
     *   <li>numbers with a country code keep it ({@code +7...}); 10-15 digits are accepted</li>
     * </ul>
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 9) {
            digits = "998" + digits;
        } else if (digits.length() == 10 && digits.startsWith("0")) {
            digits = "998" + digits.substring(1);
        }
        if (digits.length() < 10 || digits.length() > 15) {
            return Optional.empty();
        }
        if (digits.startsWith("998") && digits.length() != 12) {
            return Optional.empty();
        }
        return Optional.of("+" + digits);
    }

    /** {@code +998901234567} becomes {@code +998 90 123 45 67}. Other numbers are returned unchanged. */
    public static String pretty(String phone) {
        if (phone == null) {
            return "";
        }
        if (phone.matches("\\+998\\d{9}")) {
            return "+998 " + phone.substring(4, 6) + " " + phone.substring(6, 9) + " "
                    + phone.substring(9, 11) + " " + phone.substring(11, 13);
        }
        return phone;
    }

    /** Masks a phone for logs: {@code +99890***4567}. */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 10) {
            return "***";
        }
        return phone.substring(0, 6) + "***" + phone.substring(phone.length() - 4);
    }
}
