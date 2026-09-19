package pdp.service_bron.telegram;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackDataTest {

    @Test
    void encodesCompactFormat() {
        assertThat(CallbackData.encode("c", "bar", 12)).isEqualTo("c:bar:12");
        assertThat(CallbackData.encode("c", "day", 12, "20260918")).isEqualTo("c:day:12:20260918");
        assertThat(CallbackData.encode("c", "slot", 12, "202609181530")).isEqualTo("c:slot:12:202609181530");
        assertThat(CallbackData.encode("b", "cxl", 345)).isEqualTo("b:cxl:345");
    }

    @Test
    void encodesActionWithoutArguments() {
        assertThat(CallbackData.encode("x", "noop")).isEqualTo("x:noop");
    }

    @Test
    void parsesWhatItEncodes() {
        CallbackData data = CallbackData.parse(CallbackData.encode("c", "slot", 12, "202609181530"));

        assertThat(data).isNotNull();
        assertThat(data.domain()).isEqualTo("c");
        assertThat(data.action()).isEqualTo("slot");
        assertThat(data.args()).containsExactly("12", "202609181530");
        assertThat(data.longArg(0)).isEqualTo(12L);
        assertThat(data.arg(1)).isEqualTo("202609181530");
        assertThat(data.is("slot")).isTrue();
        assertThat(data.toString()).isEqualTo("c:slot:12:202609181530");
    }

    @Test
    void parsesActionWithoutArguments() {
        CallbackData data = CallbackData.parse("x:noop");

        assertThat(data).isNotNull();
        assertThat(data.args()).isEmpty();
        assertThat(data.hasArg(0)).isFalse();
    }

    @Test
    void malformedDataParsesToNull() {
        assertThat(CallbackData.parse(null)).isNull();
        assertThat(CallbackData.parse("")).isNull();
        assertThat(CallbackData.parse("   ")).isNull();
        assertThat(CallbackData.parse("justone")).isNull();
        assertThat(CallbackData.parse(":action")).isNull();
        assertThat(CallbackData.parse("c:")).isNull();
    }

    @Test
    void rejectsDataLongerThan64Bytes() {
        String longArg = "x".repeat(70);
        assertThatThrownBy(() -> CallbackData.encode("c", "act", longArg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bytes");
    }

    @Test
    void acceptsExactly64Bytes() {
        String arg = "x".repeat(64 - "c:a:".length());
        String encoded = CallbackData.encode("c", "a", arg);

        assertThat(encoded.getBytes(StandardCharsets.UTF_8)).hasSize(64);
    }

    @Test
    void countsBytesNotCharacters() {
        // 'ў' takes 2 bytes in UTF-8, so 30 of them are 60 bytes plus the prefix.
        String arg = "ў".repeat(30);
        assertThatThrownBy(() -> CallbackData.encode("c", "act", arg)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSeparatorInsideArguments() {
        assertThatThrownBy(() -> CallbackData.encode("c", "act", "a:b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingArgumentIsAnError() {
        CallbackData data = CallbackData.parse("c:bar:12");

        assertThat(data).isNotNull();
        assertThatThrownBy(() -> data.arg(1)).isInstanceOf(IllegalArgumentException.class);
    }
}
