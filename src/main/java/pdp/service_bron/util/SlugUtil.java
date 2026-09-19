package pdp.service_bron.util;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Builds URL-safe shop slugs from names (Latin, Uzbek Latin and Cyrillic are transliterated). */
public final class SlugUtil {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random RANDOM = new SecureRandom();
    private static final int MAX_BASE_LENGTH = 40;

    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "yo"), Map.entry('ж', "j"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "x"), Map.entry('ц', "ts"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sh"), Map.entry('ъ', ""),
            Map.entry('ы', "i"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"), Map.entry('ў', "o"), Map.entry('қ', "q"), Map.entry('ғ', "g"),
            Map.entry('ҳ', "h"));

    private SlugUtil() {
    }

    /** {@code "Barber House"} becomes {@code "barber-house"}. Never returns an empty string. */
    public static String slugify(String name) {
        if (name == null) {
            return "shop";
        }
        // Uzbek Latin apostrophes: o' g' -> o g ; and typographic quotes
        String text = name.toLowerCase(Locale.ROOT)
                .replace("o'", "o").replace("g'", "g")
                .replace("oʻ", "o").replace("gʻ", "g")
                .replace("o‘", "o").replace("g‘", "g")
                .replace("o`", "o").replace("g`", "g");
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            String mapped = CYRILLIC.get(c);
            if (mapped != null) {
                sb.append(mapped);
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String slug = sb.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.length() > MAX_BASE_LENGTH) {
            slug = slug.substring(0, MAX_BASE_LENGTH).replaceAll("-$", "");
        }
        return slug.isEmpty() ? "shop" : slug;
    }

    public static String randomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** {@code "Barber House"} becomes something like {@code "barber-house-k3x9"}. */
    public static String withSuffix(String name) {
        return slugify(name) + "-" + randomSuffix(4);
    }
}
