package pdp.service_bron.handler.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Contact;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.handler.UpdateHandler;
import pdp.service_bron.handler.admin.AdminHandler;
import pdp.service_bron.handler.barber.BarberHandler;
import pdp.service_bron.handler.client.ClientHandler;
import pdp.service_bron.handler.owner.OwnerHandler;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.UserService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.PhoneFormatter;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Commands, first-start onboarding (language, phone), deep links and client/staff mode switching.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommonHandler implements UpdateHandler {

    static final Pattern PAYLOAD = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final String KEY_PAYLOAD = "payload";

    private final UserService users;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final MainMenuService mainMenu;
    private final AdminHandler adminHandler;
    private final OwnerHandler ownerHandler;
    private final BarberHandler barberHandler;
    private final ClientHandler clientHandler;

    @Override
    public String domain() {
        return "x";
    }

    // ------------------------------------------------------------------ commands

    public void onCommand(BotContext ctx, String text) {
        String[] parts = text.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }
        String argument = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/start" -> onStart(ctx, argument);
            case "/lang" -> {
                sessions.clear(ctx.telegramId());
                sender.send(ctx.chatId(), i18n.t(ctx.lang(), "start.choose_language"), keyboards.languageChoice());
            }
            case "/help" -> sender.send(ctx.chatId(), i18n.t(ctx.lang(), "help.text"));
            case "/bookings" -> {
                if (ctx.user().getPhone() == null) {
                    onStart(ctx, "");
                } else {
                    sessions.clear(ctx.telegramId());
                    clientHandler.showMyBookings(ctx, null);
                }
            }
            case "/admin" -> {
                if (!adminHandler.open(ctx)) {
                    showHome(ctx); // not a super admin: behave like an unknown command
                }
            }
            default -> {
                // /menu, unknown commands
                sessions.clear(ctx.telegramId());
                showHome(ctx);
            }
        }
    }

    /** {@code /start} with an optional deep-link payload. Users without a phone number go through onboarding first. */
    void onStart(BotContext ctx, String rawPayload) {
        String payload = PAYLOAD.matcher(rawPayload).matches() ? rawPayload : null;
        if (ctx.user().getPhone() == null) {
            sessions.set(ctx.telegramId(), BotState.IDLE, payload == null ? Map.of() : Map.of(KEY_PAYLOAD, payload));
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "start.choose_language"), keyboards.languageChoice());
            return;
        }
        sessions.clear(ctx.telegramId());
        processStart(ctx, payload);
    }

    /** Routes a deep link (or the plain start) once the user is onboarded. */
    void processStart(BotContext ctx, String payload) {
        if (payload == null || payload.length() < 3 || payload.charAt(1) != '_') {
            startHome(ctx);
            return;
        }
        String token = payload.substring(2);
        switch (payload.charAt(0)) {
            case 'o' -> ownerHandler.redeemInvite(ctx, token);
            case 'b' -> barberHandler.redeemInvite(ctx, token);
            case 's' -> {
                // Opening a shop link means acting as a client, even for staff.
                sessions.setClientMode(ctx.telegramId(), true);
                BotContext fresh = new BotContext(ctx.update(), ctx.user(), sessions.load(ctx.telegramId()));
                mainMenu.show(fresh);
                clientHandler.openShopBySlug(fresh, token);
            }
            default -> startHome(ctx);
        }
    }

    /** Plain {@code /start}: staff get their work menu, clients get the menu and their shop. */
    void startHome(BotContext ctx) {
        mainMenu.show(ctx);
        if (!mainMenu.isStaffMode(ctx)) {
            clientHandler.openHome(ctx);
        }
    }

    /** The main menu only (used by /menu and as the fallback for unknown input). */
    public void showHome(BotContext ctx) {
        mainMenu.show(ctx);
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public void onCallback(BotContext ctx, CallbackData data) {
        if (data.is("lang")) {
            onLanguageChosen(ctx, Lang.fromCode(data.arg(0)));
        }
    }

    private void onLanguageChosen(BotContext ctx, Lang lang) {
        AppUser user = users.setLanguage(ctx.user(), lang);
        Integer messageId = ctx.callbackMessageId();
        if (messageId != null) {
            sender.edit(ctx.chatId(), messageId, i18n.t(lang, "lang.changed"), null);
        }
        if (user.getPhone() == null) {
            String payload = ctx.session().get(KEY_PAYLOAD);
            sessions.set(ctx.telegramId(), BotState.AWAIT_PHONE, payload == null ? Map.of() : Map.of(KEY_PAYLOAD, payload));
            sender.send(ctx.chatId(), i18n.t(lang, "phone.ask"), keyboards.contactRequest(lang));
        } else {
            sessions.clear(ctx.telegramId());
            showHome(ctx);
        }
    }

    // ------------------------------------------------------------------ reply keyboard

    @Override
    public void onMenu(BotContext ctx, String menuKey) {
        switch (menuKey) {
            case "menu.x.clientmode" -> {
                sessions.setClientMode(ctx.telegramId(), true);
                reloadAndShowHome(ctx);
            }
            case "menu.x.staffmode" -> {
                sessions.setClientMode(ctx.telegramId(), false);
                reloadAndShowHome(ctx);
            }
            default -> showHome(ctx);
        }
    }

    /** The session changed (mode switch), so the context must see the fresh state before rendering. */
    private void reloadAndShowHome(BotContext ctx) {
        showHome(new BotContext(ctx.update(), ctx.user(), sessions.load(ctx.telegramId())));
    }

    // ------------------------------------------------------------------ free-form input

    @Override
    public void onInput(BotContext ctx) {
        if (ctx.state() == BotState.AWAIT_PHONE) {
            onPhoneInput(ctx);
        } else {
            showHome(ctx);
        }
    }

    private void onPhoneInput(BotContext ctx) {
        Lang lang = ctx.lang();
        Contact contact = ctx.message().hasContact() ? ctx.message().getContact() : null;
        if (contact == null) {
            sender.send(ctx.chatId(), i18n.t(lang, "phone.use_button"), keyboards.contactRequest(lang));
            return;
        }
        if (contact.getUserId() == null || contact.getUserId() != ctx.telegramId()) {
            sender.send(ctx.chatId(), i18n.t(lang, "phone.not_yours"), keyboards.contactRequest(lang));
            return;
        }
        String phone = PhoneFormatter.normalize(contact.getPhoneNumber()).orElse(null);
        if (phone == null) {
            sender.send(ctx.chatId(), i18n.t(lang, "phone.invalid"), keyboards.contactRequest(lang));
            return;
        }
        users.setPhone(ctx.user(), phone);
        log.info("User {} shared phone {}", ctx.telegramId(), PhoneFormatter.mask(phone));
        String payload = ctx.session().get(KEY_PAYLOAD);
        sessions.clear(ctx.telegramId());
        sender.send(ctx.chatId(), i18n.t(lang, "phone.saved"), keyboards.removeKeyboard());
        processStart(new BotContext(ctx.update(), ctx.user(), sessions.load(ctx.telegramId())), payload);
    }
}
