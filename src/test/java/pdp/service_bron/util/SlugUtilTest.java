package pdp.service_bron.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugUtilTest {

    @Test
    void slugifiesLatinNames() {
        assertThat(SlugUtil.slugify("Barber House")).isEqualTo("barber-house");
        assertThat(SlugUtil.slugify("  Top   Barber!!  ")).isEqualTo("top-barber");
    }

    @Test
    void transliteratesCyrillic() {
        assertThat(SlugUtil.slugify("Барбер Хаус")).isEqualTo("barber-xaus");
        assertThat(SlugUtil.slugify("Юность")).isEqualTo("yunost");
    }

    @Test
    void handlesUzbekApostrophes() {
        assertThat(SlugUtil.slugify("O'zbek Go'zal")).isEqualTo("ozbek-gozal");
        assertThat(SlugUtil.slugify("Oʻzbek")).isEqualTo("ozbek");
    }

    @Test
    void neverReturnsEmpty() {
        assertThat(SlugUtil.slugify("!!!")).isEqualTo("shop");
        assertThat(SlugUtil.slugify(null)).isEqualTo("shop");
    }

    @Test
    void limitsLength() {
        assertThat(SlugUtil.slugify("a".repeat(100))).hasSize(40);
    }

    @Test
    void addsRandomFourCharacterSuffix() {
        String slug = SlugUtil.withSuffix("Barber House");

        assertThat(slug).matches("barber-house-[a-z0-9]{4}");
        assertThat(slug.length()).isLessThanOrEqualTo(64 - 2);
    }
}
