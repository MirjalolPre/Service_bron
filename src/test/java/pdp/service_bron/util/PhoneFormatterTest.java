package pdp.service_bron.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneFormatterTest {

    @Test
    void normalizesUzbekNumbers() {
        assertThat(PhoneFormatter.normalize("+998901234567")).contains("+998901234567");
        assertThat(PhoneFormatter.normalize("998901234567")).contains("+998901234567");
        assertThat(PhoneFormatter.normalize("90 123 45 67")).contains("+998901234567");
        assertThat(PhoneFormatter.normalize("+998 (90) 123-45-67")).contains("+998901234567");
        assertThat(PhoneFormatter.normalize("0901234567")).contains("+998901234567");
    }

    @Test
    void keepsForeignNumbers() {
        assertThat(PhoneFormatter.normalize("+7 916 123 45 67")).contains("+79161234567");
    }

    @Test
    void rejectsGarbage() {
        assertThat(PhoneFormatter.normalize(null)).isEmpty();
        assertThat(PhoneFormatter.normalize("")).isEmpty();
        assertThat(PhoneFormatter.normalize("abc")).isEmpty();
        assertThat(PhoneFormatter.normalize("12345")).isEmpty();
        assertThat(PhoneFormatter.normalize("+998 90 123")).isEmpty();
        assertThat(PhoneFormatter.normalize("1234567890123456")).isEmpty();
    }

    @Test
    void formatsPrettyAndMasks() {
        assertThat(PhoneFormatter.pretty("+998901234567")).isEqualTo("+998 90 123 45 67");
        assertThat(PhoneFormatter.pretty("+79161234567")).isEqualTo("+79161234567");
        assertThat(PhoneFormatter.pretty(null)).isEmpty();
        assertThat(PhoneFormatter.mask("+998901234567")).isEqualTo("+99890***4567");
        assertThat(PhoneFormatter.mask(null)).isEqualTo("***");
        assertThat(PhoneFormatter.mask("123")).isEqualTo("***");
    }
}
