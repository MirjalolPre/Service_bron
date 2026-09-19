package pdp.service_bron.domain;

import java.util.Locale;

/** Supported bot languages. */
public enum Lang {
    UZ("uz"),
    RU("ru");

    private final String code;

    Lang(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public Locale locale() {
        return Locale.forLanguageTag(code);
    }

    public static Lang fromCode(String code) {
        if ("ru".equalsIgnoreCase(code)) {
            return RU;
        }
        return UZ;
    }
}
