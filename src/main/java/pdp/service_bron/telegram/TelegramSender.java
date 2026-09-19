package pdp.service_bron.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendLocation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.ByteArrayInputStream;

/**
 * Thin wrapper over {@link TelegramClient}. All messages use HTML parse mode. Errors are handled here:
 * "message is not modified" is ignored silently and "bot was blocked by the user" (403) is logged.
 * Send methods return the sent message id, or {@code null} when nothing could be sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSender {

    static final int MAX_TEXT = 4096;
    static final int MAX_CAPTION = 1024;

    private final TelegramClient client;

    public Integer send(long chatId, String html) {
        return send(chatId, html, null);
    }

    public Integer send(long chatId, String html, ReplyKeyboard keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(limit(html, MAX_TEXT))
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard)
                .build();
        message.disableWebPagePreview();
        try {
            Message sent = client.execute(message);
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            handle(chatId, "send message", e);
            return null;
        }
    }

    /** Edits the text of an existing message. Returns false when the message could not be edited. */
    public boolean edit(long chatId, int messageId, String html, InlineKeyboardMarkup keyboard) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(limit(html, MAX_TEXT))
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard)
                .build();
        edit.disableWebPagePreview();
        try {
            client.execute(edit);
            return true;
        } catch (TelegramApiException e) {
            if (isNotModified(e)) {
                return true;
            }
            if (isCannotEdit(e)) {
                return false;
            }
            handle(chatId, "edit message", e);
            return true;
        }
    }

    /**
     * Edits the message when possible; falls back to deleting it and sending a new one (for example when the
     * old message is a photo, which has no text to edit).
     */
    public Integer editOrSend(long chatId, Integer messageId, String html, InlineKeyboardMarkup keyboard) {
        if (messageId != null && edit(chatId, messageId, html, keyboard)) {
            return messageId;
        }
        if (messageId != null) {
            delete(chatId, messageId);
        }
        return send(chatId, html, keyboard);
    }

    public void answer(String callbackQueryId) {
        answer(callbackQueryId, null, false);
    }

    public void answer(String callbackQueryId, String text, boolean alert) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .showAlert(alert)
                    .build());
        } catch (TelegramApiException e) {
            // Old callback queries expire; nothing useful to do.
            log.debug("answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    /** Sends a photo by Telegram file id. Falls back to a text message when the file id is not usable. */
    public Integer sendPhoto(long chatId, String fileId, String captionHtml, InlineKeyboardMarkup keyboard) {
        try {
            Message sent = client.execute(SendPhoto.builder()
                    .chatId(String.valueOf(chatId))
                    .photo(new InputFile(fileId))
                    .caption(limit(captionHtml, MAX_CAPTION))
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(keyboard)
                    .build());
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            handle(chatId, "send photo", e);
            return send(chatId, captionHtml, keyboard);
        }
    }

    public Integer sendPhotoBytes(long chatId, byte[] png, String fileName, String captionHtml) {
        try {
            Message sent = client.execute(SendPhoto.builder()
                    .chatId(String.valueOf(chatId))
                    .photo(new InputFile(new ByteArrayInputStream(png), fileName))
                    .caption(limit(captionHtml, MAX_CAPTION))
                    .parseMode(ParseMode.HTML)
                    .build());
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            handle(chatId, "send photo", e);
            return null;
        }
    }

    /** Sends a file so the QR code can be downloaded in full quality. */
    public Integer sendDocumentBytes(long chatId, byte[] data, String fileName, String captionHtml) {
        try {
            Message sent = client.execute(SendDocument.builder()
                    .chatId(String.valueOf(chatId))
                    .document(new InputFile(new ByteArrayInputStream(data), fileName))
                    .caption(limit(captionHtml, MAX_CAPTION))
                    .parseMode(ParseMode.HTML)
                    .build());
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            handle(chatId, "send document", e);
            return null;
        }
    }

    public void sendLocation(long chatId, double latitude, double longitude) {
        try {
            client.execute(SendLocation.builder()
                    .chatId(String.valueOf(chatId))
                    .latitude(latitude)
                    .longitude(longitude)
                    .build());
        } catch (TelegramApiException e) {
            handle(chatId, "send location", e);
        }
    }

    public void delete(long chatId, int messageId) {
        try {
            client.execute(DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("deleteMessage failed: {}", e.getMessage());
        }
    }

    private void handle(long chatId, String action, TelegramApiException e) {
        if (isBlocked(e)) {
            log.info("Cannot {} to chat {}: the bot was blocked by the user (or the chat was deleted)", action, chatId);
        } else if (isNotModified(e)) {
            log.debug("Ignoring 'message is not modified' while trying to {}", action);
        } else {
            log.warn("Failed to {} in chat {}: {}", action, chatId, e.getMessage());
        }
    }

    static boolean isBlocked(TelegramApiException e) {
        if (e instanceof TelegramApiRequestException r && r.getErrorCode() != null && r.getErrorCode() == 403) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("bot was blocked by the user") || msg.contains("user is deactivated")
                || msg.contains("chat not found"));
    }

    static boolean isNotModified(TelegramApiException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("message is not modified");
    }

    private static boolean isCannotEdit(TelegramApiException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("there is no text in the message to edit")
                || msg.contains("message can't be edited")
                || msg.contains("message to edit not found"));
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }
}
