package pdp.service_bron.service;

import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Lang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safety net for "no hard-coded strings": every message key used in the Java sources must exist in both bundles.
 */
class MessageKeysUsageTest {

    private static final Pattern KEY_LITERAL = Pattern.compile(
            "(?:\\bt\\([^\"\\n;]*?|\\bBusinessException\\(|\\bnotice\\(|\\bkey\\()\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    @Test
    void everyKeyUsedInJavaExistsInBothBundles() throws IOException {
        I18nService i18n = new I18nService();
        Set<String> missing = new TreeSet<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KEY_LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    String key = m.group(1);
                    for (Lang lang : Lang.values()) {
                        if (!i18n.has(lang, key)) {
                            missing.add(file.getFileName() + ": " + key + " (" + lang + ")");
                        }
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }
}
