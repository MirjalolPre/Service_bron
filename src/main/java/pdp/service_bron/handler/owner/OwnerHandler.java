package pdp.service_bron.handler.owner;

import lombok.RequiredArgsConstructor;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.config.BotInfo;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.handler.UpdateHandler;
import pdp.service_bron.handler.barber.BarberHandler;
import pdp.service_bron.handler.common.BookingFormatter;
import pdp.service_bron.handler.common.MainMenuService;
import pdp.service_bron.service.AccessService;
import pdp.service_bron.service.BarberService;
import pdp.service_bron.service.BookingService;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.InviteService;
import pdp.service_bron.service.NotificationService;
import pdp.service_bron.service.QrService;
import pdp.service_bron.service.ShopService;
import pdp.service_bron.service.ShopService.Setting;
import pdp.service_bron.service.ShopService.TextField;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;

import pdp.service_bron.util.LocationParser;
import pdp.service_bron.util.TimeFormatter;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * Shop owner: onboarding wizard after opening an owner invite, shop card editing and shop settings.
 * The shop is always taken from the caller's ownership, never from callback data.
 */
@Component
@RequiredArgsConstructor
public class OwnerHandler implements UpdateHandler {

    /** Onboarding wizard steps, in order. */
    enum Step { NAME, ADDRESS, LANDMARK, LOCATION, PHONE, PHOTO, BARBER }

    private static final String WIZ = "wiz";
    private static final String FIELD = "field";

    private final AccessService access;
    private final ShopService shops;
    private final BarberService barbers;
    private final InviteService invites;
    private final NotificationService notifications;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final MainMenuService mainMenu;
    private final BarberHandler barberHandler;
    private final OwnerBarbersView barbersView;
    private final QrService qr;
    private final BotInfo botInfo;
    private final BookingService bookings;
    private final BookingFormatter formatter;
    private final TimeFormatter time;
    private final Clock clock;

    @Override
    public String domain() {
        return "o";
    }

    private static String cb(String action, Object... args) {
        return CallbackData.encode("o", action, args);
    }

    // ------------------------------------------------------------------ deep link o_<token>

    /** The user opened {@code /start o_<token>}: becomes the shop owner and starts the setup wizard. */
    public void redeemInvite(BotContext ctx, String token) {
        Shop shop = invites.redeemOwner(token, ctx.user());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.welcome", esc(shop.getName())));
        askStep(ctx, Step.NAME);
    }

    // ------------------------------------------------------------------ reply keyboard

    @Override
    public void onMenu(BotContext ctx, String menuKey) {
        Shop shop = requireShop(ctx);
        switch (menuKey) {
            case "menu.o.shop" -> showShop(ctx, null, shop);
            case "menu.o.barbers" -> barbersView.show(ctx, shop, null);
            case "menu.o.link" -> sendLinkAndQr(ctx, shop);
            case "menu.o.today" -> showToday(ctx, shop);
            default -> mainMenu.show(ctx);
        }
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public void onCallback(BotContext ctx, CallbackData data) {
        Shop shop = requireShop(ctx);
        Integer messageId = ctx.callbackMessageId();
        if (barbersView.onCallback(ctx, shop, data)) {
            return;
        }
        switch (data.action()) {
            // --- wizard
            case "wkeep" -> advance(ctx, Step.NAME);
            case "wskip" -> skip(ctx);
            case "wphone" -> useOwnPhone(ctx, shop);
            case "wbarber" -> ownerAsBarber(ctx, shop, "1".equals(data.arg(0)), messageId);
            // --- shop card
            case "shop" -> {
                sessions.clear(ctx.telegramId());
                showShop(ctx, messageId, shop);
            }
            case "ed" -> askFieldEdit(ctx, shop, TextField.valueOf(data.arg(0)), messageId);
            case "edloc" -> askLocationEdit(ctx, shop);
            case "edphoto" -> askPhotoEdit(ctx, messageId);
            case "st" -> showSetting(ctx, shop, Setting.valueOf(data.arg(0)), messageId);
            case "sv" -> {
                Setting setting = Setting.valueOf(data.arg(0));
                shops.updateSetting(ctx.user(), shop.getId(), setting, data.intArg(1));
                showShop(ctx, messageId, requireShop(ctx));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ free-form input

    @Override
    public void onInput(BotContext ctx) {
        Shop shop = requireShop(ctx);
        if (barbersView.onInput(ctx, shop)) {
            return;
        }
        boolean wizard = "1".equals(ctx.session().get(WIZ));
        switch (ctx.state()) {
            case O_TEXT -> onText(ctx, shop, wizard);
            case O_LOCATION -> onLocation(ctx, shop, wizard);
            case O_PHOTO -> onPhoto(ctx, shop, wizard);
            default -> mainMenu.show(ctx);
        }
    }

    private void onText(BotContext ctx, Shop shop, boolean wizard) {
        String text = ctx.text();
        TextField field = TextField.valueOf(ctx.session().get(FIELD));
        if (text == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_text"));
            return;
        }
        // "-" clears an optional field
        String value = "-".equals(text) && field != TextField.NAME ? "" : text;
        try {
            shops.updateText(ctx.user(), shop.getId(), field, value);
        } catch (BusinessException e) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), e.key(), e.args()));
            return; // stay in the same state and let the user retry
        }
        if (wizard) {
            advance(ctx, stepOf(field));
        } else {
            sessions.clear(ctx.telegramId());
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.saved"));
            showShop(ctx, null, requireShop(ctx));
        }
    }

    /**
     * The shop position: a Telegram location (the "current position" button or a pin picked on the map through
     * the attachment menu), a pasted map link, or typed coordinates. "-" or the remove button clears it.
     */
    private void onLocation(BotContext ctx, Shop shop, boolean wizard) {
        Lang lang = ctx.lang();
        Double latitude = null;
        Double longitude = null;
        if (ctx.message().hasLocation()) {
            Location location = ctx.message().getLocation();
            latitude = location.getLatitude();
            longitude = location.getLongitude();
        } else {
            String text = ctx.text();
            if (text != null && wizard && isSkipText(ctx)) {
                advance(ctx, Step.LOCATION);
                return;
            }
            if (text != null && text.equals(i18n.t(lang, "btn.back"))) {
                sessions.clear(ctx.telegramId());
                sender.send(ctx.chatId(), i18n.t(lang, "owner.location.cancelled"), keyboards.removeKeyboard());
                showShop(ctx, null, shop);
                return;
            }
            if (!wizard && text != null && (text.equals("-") || text.equals(i18n.t(lang, "btn.remove_location")))) {
                shops.updateLocation(ctx.user(), shop.getId(), null, null);
                sessions.clear(ctx.telegramId());
                sender.send(ctx.chatId(), i18n.t(lang, "owner.location.removed"), keyboards.removeKeyboard());
                showShop(ctx, null, requireShop(ctx));
                return;
            }
            var parsed = LocationParser.parse(text);
            if (parsed.isEmpty()) {
                sender.send(ctx.chatId(), i18n.t(lang, "owner.location.bad"));
                return;
            }
            latitude = parsed.get().latitude();
            longitude = parsed.get().longitude();
        }
        shops.updateLocation(ctx.user(), shop.getId(), latitude, longitude);
        sender.send(ctx.chatId(), i18n.t(lang, "owner.location.saved"), keyboards.removeKeyboard());
        sender.sendLocation(ctx.chatId(), latitude, longitude); // the pin lets the owner check the point on the map
        if (wizard) {
            advance(ctx, Step.LOCATION);
        } else {
            sessions.clear(ctx.telegramId());
            showShop(ctx, null, requireShop(ctx));
        }
    }

    private void onPhoto(BotContext ctx, Shop shop, boolean wizard) {
        if (!ctx.message().hasPhoto()) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_photo"));
            return;
        }
        List<PhotoSize> sizes = ctx.message().getPhoto();
        String fileId = sizes.stream().max(Comparator.comparing(PhotoSize::getFileSize,
                Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow().getFileId();
        shops.updatePhoto(ctx.user(), shop.getId(), fileId);
        if (wizard) {
            advance(ctx, Step.PHOTO);
        } else {
            sessions.clear(ctx.telegramId());
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.saved"));
            showShop(ctx, null, requireShop(ctx));
        }
    }

    // ------------------------------------------------------------------ wizard

    private static Step stepOf(TextField field) {
        return switch (field) {
            case NAME -> Step.NAME;
            case ADDRESS -> Step.ADDRESS;
            case LANDMARK -> Step.LANDMARK;
            case PHONE -> Step.PHONE;
            case DESCRIPTION -> Step.PHOTO; // not part of the wizard; never advanced from
        };
    }

    /** Moves to the step after {@code done}. */
    private void advance(BotContext ctx, Step done) {
        Step[] steps = Step.values();
        if (done.ordinal() + 1 < steps.length) {
            askStep(ctx, steps[done.ordinal() + 1]);
        }
    }

    private void skip(BotContext ctx) {
        BotState state = ctx.state();
        String field = ctx.session().get(FIELD);
        if (state == BotState.O_TEXT && "LANDMARK".equals(field)) {
            advance(ctx, Step.LANDMARK);
        } else if (state == BotState.O_PHOTO) {
            advance(ctx, Step.PHOTO);
        } else if (state == BotState.O_LOCATION) {
            advance(ctx, Step.LOCATION);
        }
    }

    private void askStep(BotContext ctx, Step step) {
        Lang lang = ctx.lang();
        long tg = ctx.telegramId();
        switch (step) {
            case NAME -> {
                sessions.set(tg, BotState.O_TEXT, Map.of(WIZ, "1", FIELD, TextField.NAME.name()));
                Shop shop = requireShop(ctx);
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.name", esc(shop.getName())),
                        keyboards.rows().row(keyboards.btn(i18n.t(lang, "owner.btn.keep_name"), cb("wkeep"))).build());
            }
            case ADDRESS -> {
                sessions.set(tg, BotState.O_TEXT, Map.of(WIZ, "1", FIELD, TextField.ADDRESS.name()));
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.address"));
            }
            case LANDMARK -> {
                sessions.set(tg, BotState.O_TEXT, Map.of(WIZ, "1", FIELD, TextField.LANDMARK.name()));
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.landmark"), skipKeyboard(lang));
            }
            case LOCATION -> {
                sessions.set(tg, BotState.O_LOCATION, Map.of(WIZ, "1"));
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.location"), keyboards.locationRequest(lang, true));
            }
            case PHONE -> {
                sessions.set(tg, BotState.O_TEXT, Map.of(WIZ, "1", FIELD, TextField.PHONE.name()));
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.phone"), keyboards.removeKeyboard());
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.phone_hint"),
                        keyboards.rows().row(keyboards.btn(i18n.t(lang, "owner.btn.my_phone"), cb("wphone"))).build());
            }
            case PHOTO -> {
                sessions.set(tg, BotState.O_PHOTO, Map.of(WIZ, "1"));
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.photo"), skipKeyboard(lang));
            }
            case BARBER -> {
                sessions.clear(tg);
                sender.send(ctx.chatId(), i18n.t(lang, "owner.wiz.barber_question"),
                        keyboards.rows().row(
                                keyboards.btn(i18n.t(lang, "btn.yes"), cb("wbarber", 1)),
                                keyboards.btn(i18n.t(lang, "btn.no"), cb("wbarber", 0))).build());
            }
        }
    }

    private org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup skipKeyboard(Lang lang) {
        return keyboards.rows().row(keyboards.btn(i18n.t(lang, "btn.skip"), cb("wskip"))).build();
    }

    private boolean isSkipText(BotContext ctx) {
        String text = ctx.text();
        return text != null && (text.equals(i18n.t(Lang.UZ, "btn.skip")) || text.equals(i18n.t(Lang.RU, "btn.skip")));
    }

    private void useOwnPhone(BotContext ctx, Shop shop) {
        String phone = ctx.user().getPhone();
        if (phone == null) {
            return;
        }
        shops.updateText(ctx.user(), shop.getId(), TextField.PHONE, phone);
        advance(ctx, Step.PHONE);
    }

    private void ownerAsBarber(BotContext ctx, Shop shop, boolean yes, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.clear(ctx.telegramId());
        if (!yes) {
            sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "owner.wiz.done"), null);
            mainMenu.show(ctx, i18n.t(lang, "owner.wiz.done_menu"));
            return;
        }
        Barber barber = barbers.create(shop, ctx.user(), ctx.user().getFirstName());
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "owner.wiz.barber_created"), null);
        barberHandler.startWizard(ctx, barber);
    }

    // ------------------------------------------------------------------ link, QR and today's bookings

    /** The client link plus a printable QR code (sent as a photo and as a file). */
    private void sendLinkAndQr(BotContext ctx, Shop shop) {
        Lang lang = ctx.lang();
        sessions.clear(ctx.telegramId());
        String link = botInfo.link("s_" + shop.getSlug());
        sender.send(ctx.chatId(), i18n.t(lang, "owner.link.text", link));
        byte[] png = qr.png(link, shop.getName());
        String fileName = "qr-" + shop.getSlug() + ".png";
        sender.sendPhotoBytes(ctx.chatId(), png, fileName, esc(shop.getName()));
        sender.sendDocumentBytes(ctx.chatId(), png, fileName, i18n.t(lang, "owner.link.file_caption"));
    }

    /** All barbers' bookings for today, grouped by barber. */
    private void showToday(BotContext ctx, Shop shop) {
        Lang lang = ctx.lang();
        sessions.clear(ctx.telegramId());
        LocalDate today = clock.instant().atZone(shop.zone()).toLocalDate();
        Map<Long, List<Booking>> byBarber = new LinkedHashMap<>();
        for (Booking booking : bookings.bookedForShopOnDate(shop, today)) {
            byBarber.computeIfAbsent(booking.getBarberId(), id -> new java.util.ArrayList<>()).add(booking);
        }
        StringBuilder text = new StringBuilder(i18n.t(lang, "owner.today.title", time.longDate(lang, today)));
        List<Barber> active = barbers.activeInShop(shop.getId());
        if (active.isEmpty()) {
            text.append("\n\n").append(i18n.t(lang, "owner.barbers.empty"));
        }
        for (Barber barber : active) {
            List<Booking> list = byBarber.getOrDefault(barber.getId(), List.of());
            text.append("\n\n").append(i18n.t(lang, "owner.today.barber", esc(barber.getDisplayName()), list.size()));
            if (list.isEmpty()) {
                text.append("\n").append(i18n.t(lang, "owner.today.none"));
            }
            for (Booking booking : list) {
                text.append("\n").append(time.time(booking.getStartAt(), shop.zone())).append(" — ")
                        .append(formatter.clientLabel(lang, booking));
            }
        }
        sender.send(ctx.chatId(), text.toString());
    }

    // ------------------------------------------------------------------ shop card

    private void showShop(BotContext ctx, Integer messageId, Shop shop) {
        Lang lang = ctx.lang();
        StringBuilder text = new StringBuilder(i18n.t(lang, "owner.shop.title", esc(shop.getName())));
        text.append("\n\n").append(i18n.t(lang, "owner.shop.address", valueOrDash(shop.getAddress())));
        text.append('\n').append(i18n.t(lang, "owner.shop.landmark", valueOrDash(shop.getLandmark())));
        text.append('\n').append(i18n.t(lang, "owner.shop.phone", valueOrDash(shop.getPhone())));
        text.append('\n').append(i18n.t(lang, "owner.shop.description", valueOrDash(shop.getDescription())));
        text.append('\n').append(i18n.t(lang, "owner.shop.location", i18n.t(lang, shop.hasLocation() ? "icon.on" : "icon.off")));
        text.append('\n').append(i18n.t(lang, "owner.shop.photo", i18n.t(lang, shop.getPhotoFileId() != null ? "icon.on" : "icon.off")));
        if (!shop.isActive()) {
            text.append("\n\n").append(i18n.t(lang, "banner.subscription_off"));
        }
        var rows = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "owner.btn.name"), cb("ed", TextField.NAME)),
                        keyboards.btn(i18n.t(lang, "owner.btn.address"), cb("ed", TextField.ADDRESS)))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.landmark"), cb("ed", TextField.LANDMARK)),
                        keyboards.btn(i18n.t(lang, "owner.btn.location"), cb("edloc")))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.phone"), cb("ed", TextField.PHONE)),
                        keyboards.btn(i18n.t(lang, "owner.btn.description"), cb("ed", TextField.DESCRIPTION)))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.photo"), cb("edphoto")))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.horizon", settingValue(lang, Setting.HORIZON, shop.getBookingHorizonDays())), cb("st", Setting.HORIZON)))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.reminder", settingValue(lang, Setting.REMINDER, shop.getReminderMinutesBefore())), cb("st", Setting.REMINDER)))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.lead", settingValue(lang, Setting.LEAD, shop.getMinLeadMinutes())), cb("st", Setting.LEAD)))
                .row(keyboards.btn(i18n.t(lang, "owner.btn.max_active", settingValue(lang, Setting.MAX_ACTIVE, shop.getMaxActiveBookingsPerClient())), cb("st", Setting.MAX_ACTIVE)));
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private void askFieldEdit(BotContext ctx, Shop shop, TextField field, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.O_TEXT, Map.of(FIELD, field.name()));
        String key = "owner.ask." + field.name().toLowerCase();
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, key) + (field == TextField.NAME ? "" : "\n\n" + i18n.t(lang, "owner.clear_hint")),
                keyboards.rows().row(keyboards.back(lang, cb("shop"))).build());
    }

    private void askLocationEdit(BotContext ctx, Shop shop) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.O_LOCATION);
        if (shop.hasLocation()) {
            sender.send(ctx.chatId(), i18n.t(lang, "owner.location.current"));
            sender.sendLocation(ctx.chatId(), shop.getLatitude(), shop.getLongitude());
        }
        sender.send(ctx.chatId(), i18n.t(lang, "owner.location.ask"), keyboards.locationEdit(lang, shop.hasLocation()));
    }

    private void askPhotoEdit(BotContext ctx, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.O_PHOTO);
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "owner.send_photo"),
                keyboards.rows().row(keyboards.back(lang, cb("shop"))).build());
    }

    private void showSetting(BotContext ctx, Shop shop, Setting setting, Integer messageId) {
        Lang lang = ctx.lang();
        int current = switch (setting) {
            case HORIZON -> shop.getBookingHorizonDays();
            case LEAD -> shop.getMinLeadMinutes();
            case REMINDER -> shop.getReminderMinutesBefore();
            case MAX_ACTIVE -> shop.getMaxActiveBookingsPerClient();
        };
        var options = setting.allowed().stream().sorted().map(value -> keyboards.btn(
                (value == current ? i18n.t(lang, "icon.on") + " " : "") + settingValue(lang, setting, value),
                cb("sv", setting, value))).toList();
        var rows = keyboards.rows().grid(options, 2).row(keyboards.back(lang, cb("shop")));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "owner.setting." + setting.name().toLowerCase()), rows.build());
    }

    /** Human-readable value of a setting: "7 kun", "30 daq", "2 soat", "O'chirilgan". */
    private String settingValue(Lang lang, Setting setting, int value) {
        return switch (setting) {
            case HORIZON -> i18n.t(lang, "unit.days", value);
            case LEAD -> value == 0 ? i18n.t(lang, "unit.none") : i18n.t(lang, "unit.minutes", value);
            case REMINDER -> value == 0 ? i18n.t(lang, "unit.off") : i18n.t(lang, "unit.hours", value / 60);
            case MAX_ACTIVE -> String.valueOf(value);
        };
    }

    // ------------------------------------------------------------------ helpers

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : esc(value);
    }

    /** The shop of the caller. Throws when the caller does not own a shop. */
    private Shop requireShop(BotContext ctx) {
        AppUser user = ctx.user();
        return access.ownedShop(user).orElseThrow(() -> new BusinessException("error.forbidden"));
    }
}
