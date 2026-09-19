package pdp.service_bron.service;

import org.junit.jupiter.api.Test;
import pdp.service_bron.domain.Lang;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class I18nServiceTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private final I18nService i18n = new I18nService();

    @Test
    void bothLanguagesHaveTheSameKeys() {
        Set<String> uz = i18n.keys(Lang.UZ);
        Set<String> ru = i18n.keys(Lang.RU);

        Set<String> onlyUz = new TreeSet<>(uz);
        onlyUz.removeAll(ru);
        Set<String> onlyRu = new TreeSet<>(ru);
        onlyRu.removeAll(uz);

        assertThat(onlyUz).as("keys missing in messages_ru.properties").isEmpty();
        assertThat(onlyRu).as("keys missing in messages_uz.properties").isEmpty();
    }

    @Test
    void noMessageIsBlank() {
        for (Lang lang : Lang.values()) {
            for (String key : i18n.keys(lang)) {
                assertThat(i18n.t(lang, key)).as("%s / %s", lang, key).isNotBlank();
            }
        }
    }

    @Test
    void placeholdersMatchBetweenLanguages() {
        for (String key : i18n.keys(Lang.UZ)) {
            assertThat(placeholders(i18n.t(Lang.RU, key)))
                    .as("placeholders of '%s' must be the same in uz and ru", key)
                    .isEqualTo(placeholders(i18n.t(Lang.UZ, key)));
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    void menuLabelsAreUniqueSoButtonsAreNeverAmbiguous() {
        Map<String, String> seen = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (Lang lang : Lang.values()) {
            for (String key : i18n.keys(lang)) {
                if (!key.startsWith(I18nService.MENU_PREFIX)) {
                    continue;
                }
                String label = i18n.t(lang, key).trim();
                String previous = seen.put(label, key);
                if (previous != null && !previous.equals(key)) {
                    duplicates.add(label + " -> " + previous + ", " + key);
                }
            }
        }
        assertThat(duplicates).isEmpty();
    }

    @Test
    void menuButtonsResolveToTheirKeyInBothLanguages() {
        assertThat(i18n.menuKey("✂️ Navbatga yozilish")).isEqualTo("menu.c.book");
        assertThat(i18n.menuKey("✂️ Записаться")).isEqualTo("menu.c.book");
        assertThat(i18n.menuKey("  📅 Bugun ")).isEqualTo("menu.b.today");
        assertThat(i18n.menuKey("hello")).isNull();
        assertThat(i18n.menuKey(null)).isNull();
    }

    @Test
    void substitutesPlaceholdersWithoutTouchingApostrophes() {
        assertThat(i18n.t(Lang.UZ, "money.format", "60 000")).isEqualTo("60 000 so'm");
        assertThat(i18n.t(Lang.RU, "money.format", "60 000")).isEqualTo("60 000 сум");
        assertThat(i18n.money(Lang.UZ, 60000)).isEqualTo("60 000 so'm");
        assertThat(i18n.money(Lang.RU, 1250000)).isEqualTo("1 250 000 сум");
    }

    @Test
    void missingKeyIsVisibleInsteadOfCrashing() {
        assertThat(i18n.t(Lang.UZ, "no.such.key")).isEqualTo("!no.such.key!");
        assertThat(i18n.has(Lang.UZ, "no.such.key")).isFalse();
    }

    @Test
    void mainMenuLabelsFollowTheSpec() {
        assertThat(List.of(
                i18n.t(Lang.UZ, "menu.c.book"), i18n.t(Lang.UZ, "menu.c.mybookings"), i18n.t(Lang.UZ, "menu.c.shop"),
                i18n.t(Lang.UZ, "menu.c.othershop"), i18n.t(Lang.UZ, "menu.c.lang")))
                .containsExactly("✂️ Navbatga yozilish", "📋 Mening bronlarim", "📍 Sartaroshxona",
                        "🏪 Boshqa sartaroshxona", "🌐 Til");
        assertThat(List.of(
                i18n.t(Lang.RU, "menu.c.book"), i18n.t(Lang.RU, "menu.c.mybookings"), i18n.t(Lang.RU, "menu.c.shop"),
                i18n.t(Lang.RU, "menu.c.othershop"), i18n.t(Lang.RU, "menu.c.lang")))
                .containsExactly("✂️ Записаться", "📋 Мои записи", "📍 Барбершоп", "🏪 Другой барбершоп", "🌐 Язык");
    }
}
