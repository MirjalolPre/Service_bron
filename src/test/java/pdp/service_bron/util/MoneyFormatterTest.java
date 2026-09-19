package pdp.service_bron.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyFormatterTest {

    @Test
    void groupsThousandsWithSpaces() {
        assertThat(MoneyFormatter.group(0)).isEqualTo("0");
        assertThat(MoneyFormatter.group(500)).isEqualTo("500");
        assertThat(MoneyFormatter.group(60000)).isEqualTo("60 000");
        assertThat(MoneyFormatter.group(1250000)).isEqualTo("1 250 000");
    }

    @ParameterizedTest
    @CsvSource({"60000,60000", "'60 000',60000", "60k,60000", "60K,60000", "60к,60000", "60.000,60000", "'60,000',60000", "0,0"})
    void parsesCommonInputs(String input, long expected) {
        assertThat(MoneyFormatter.parse(input)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"abc", "-5", "k", "12x", "9999999999"})
    void rejectsInvalidInputs(String input) {
        assertThat(MoneyFormatter.parse(input)).isEmpty();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(MoneyFormatter.parse(null)).isEmpty();
        assertThat(MoneyFormatter.parse("  ")).isEmpty();
    }
}
