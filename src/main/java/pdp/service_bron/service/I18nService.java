package pdp.service_bron.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pdp.service_bron.domain.Lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Message catalog. All user-facing text lives in {@code messages_uz.properties} and
 * {@code messages_ru.properties} (UTF-8). Placeholders are {@code {0}}, {@code {1}}...
 *
 * <p>Plain string substitution is used instead of {@link java.text.MessageFormat}, so apostrophes in
 * Uzbek text ({@code o'zbek}, {@code so'm}) need no escaping.
 */
@Slf4j
@Service
public class I18nService {

    public static final String MENU_PREFIX = "menu.";

    private final Map<Lang, Properties> bundles = new EnumMap<>(Lang.class);
    /** Reply-keyboard button label (any language) to its menu key, e.g. "✂️ Navbatga yozilish" -> menu.c.book */
    private final Map<String, String> menuIndex = new HashMap<>();

    public I18nService() {
        for (Lang lang : Lang.values()) {
            bundles.put(lang, load(lang));
        }
        buildMenuIndex();
    }

    private Properties load(Lang lang) {
        String path = "/messages_" + lang.code() + ".properties";
        Properties props = new Properties();
        try (InputStream in = I18nService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing message bundle: " + path);
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read message bundle " + path, e);
        }
        return props;
    }

    private void buildMenuIndex() {
        for (Lang lang : Lang.values()) {
            for (String key : bundles.get(lang).stringPropertyNames()) {
                if (key.startsWith(MENU_PREFIX)) {
                    String label = bundles.get(lang).getProperty(key).trim();
                    String previous = menuIndex.put(label, key);
                    if (previous != null && !previous.equals(key)) {
                        log.warn("Menu label '{}' is used by both {} and {}", label, previous, key);
                    }
                }
            }
        }
    }

    /** Translates a key. Missing keys return {@code !key!} and are logged. */
    public String t(Lang lang, String key, Object... args) {
        String template = bundles.get(lang).getProperty(key);
        if (template == null) {
            log.warn("Missing message key '{}' for language {}", key, lang);
            return "!" + key + "!";
        }
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    public boolean has(Lang lang, String key) {
        return bundles.get(lang).containsKey(key);
    }

    /** Returns the menu key ({@code menu.c.book}) when the text is a reply-keyboard button label. */
    public String menuKey(String text) {
        return text == null ? null : menuIndex.get(text.trim());
    }

    /** {@code 60000} becomes {@code "60 000 so'm"}. */
    public String money(Lang lang, long amount) {
        return t(lang, "money.format", pdp.service_bron.util.MoneyFormatter.group(amount));
    }

    public Set<String> keys(Lang lang) {
        return new TreeSet<>(bundles.get(lang).stringPropertyNames());
    }
}
