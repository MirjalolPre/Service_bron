package pdp.service_bron.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.service.I18nService;

import java.util.ArrayList;
import java.util.List;

/** Builds inline and reply keyboards. */
@Component
@RequiredArgsConstructor
public class KeyboardFactory {

    private final I18nService i18n;

    // ---------- inline keyboards ----------

    public InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    public InlineKeyboardButton urlBtn(String text, String url) {
        return InlineKeyboardButton.builder().text(text).url(url).build();
    }

    /** "⬅️ Orqaga" button. */
    public InlineKeyboardButton back(Lang lang, String callbackData) {
        return btn(i18n.t(lang, "btn.back"), callbackData);
    }

    public Rows rows() {
        return new Rows();
    }

    /** Fluent builder for inline keyboards. */
    public static final class Rows {
        private final List<InlineKeyboardRow> rows = new ArrayList<>();

        public Rows row(InlineKeyboardButton... buttons) {
            rows.add(new InlineKeyboardRow(List.of(buttons)));
            return this;
        }

        public Rows row(List<InlineKeyboardButton> buttons) {
            if (!buttons.isEmpty()) {
                rows.add(new InlineKeyboardRow(buttons));
            }
            return this;
        }

        /** Lays out buttons {@code perRow} per row. */
        public Rows grid(List<InlineKeyboardButton> buttons, int perRow) {
            for (int i = 0; i < buttons.size(); i += perRow) {
                rows.add(new InlineKeyboardRow(buttons.subList(i, Math.min(i + perRow, buttons.size()))));
            }
            return this;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public InlineKeyboardMarkup build() {
            return InlineKeyboardMarkup.builder().keyboard(rows).build();
        }
    }

    public InlineKeyboardMarkup languageChoice() {
        return rows().row(
                btn(i18n.t(Lang.UZ, "btn.lang.uz"), CallbackData.encode("x", "lang", "uz")),
                btn(i18n.t(Lang.RU, "btn.lang.ru"), CallbackData.encode("x", "lang", "ru"))).build();
    }

    // ---------- reply keyboards ----------

    /** Persistent reply keyboard; {@code rows} contains button labels. */
    public ReplyKeyboardMarkup replyMenu(List<List<String>> rows) {
        List<KeyboardRow> keyboardRows = new ArrayList<>();
        for (List<String> row : rows) {
            KeyboardRow keyboardRow = new KeyboardRow();
            for (String label : row) {
                keyboardRow.add(KeyboardButton.builder().text(label).build());
            }
            keyboardRows.add(keyboardRow);
        }
        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboardRows)
                .resizeKeyboard(true)
                .isPersistent(true)
                .build();
    }

    /** One-time keyboard with a "share my phone number" button. */
    public ReplyKeyboardMarkup contactRequest(Lang lang) {
        KeyboardRow row = new KeyboardRow();
        row.add(KeyboardButton.builder().text(i18n.t(lang, "btn.share_phone")).requestContact(true).build());
        return ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
    }

    /** One-time keyboard with a "share my location" button, optionally with a "skip" button below it. */
    public ReplyKeyboardMarkup locationRequest(Lang lang, boolean withSkip) {
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow locationRow = new KeyboardRow();
        locationRow.add(KeyboardButton.builder().text(i18n.t(lang, "btn.share_location")).requestLocation(true).build());
        rows.add(locationRow);
        if (withSkip) {
            KeyboardRow skipRow = new KeyboardRow();
            skipRow.add(KeyboardButton.builder().text(i18n.t(lang, "btn.skip")).build());
            rows.add(skipRow);
        }
        return ReplyKeyboardMarkup.builder()
                .keyboard(rows)
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
    }

    public ReplyKeyboardRemove removeKeyboard() {
        return ReplyKeyboardRemove.builder().removeKeyboard(true).build();
    }
}
