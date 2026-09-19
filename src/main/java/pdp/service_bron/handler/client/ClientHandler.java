package pdp.service_bron.handler.client;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.PriceItem;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.handler.UpdateHandler;
import pdp.service_bron.handler.common.MainMenuService;
import pdp.service_bron.service.BarberService;
import pdp.service_bron.service.BookingService;
import pdp.service_bron.service.BookingService.BookResult;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.NotificationService;
import pdp.service_bron.service.PriceService;
import pdp.service_bron.service.ShopService;
import pdp.service_bron.service.SlotService;
import pdp.service_bron.service.UserService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.CompactTime;
import pdp.service_bron.util.PhoneFormatter;
import pdp.service_bron.util.TimeFormatter;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * The client side: shop discovery, the booking wizard (barber, price list, date, time, confirm), "my bookings"
 * and shop info. The booking wizard is one inline message that is edited on every step.
 *
 * <p>Clients never choose a service and never pay online: prices are shown for information only and are
 * paid in cash to the barber on site.
 */
@Component
@RequiredArgsConstructor
public class ClientHandler implements UpdateHandler {

    private static final int DATE_BUTTONS_PER_ROW = 2;
    private static final int SLOT_BUTTONS_PER_ROW = 4;

    private final ShopService shops;
    private final BarberService barbers;
    private final PriceService prices;
    private final SlotService slots;
    private final BookingService bookings;
    private final NotificationService notifications;
    private final UserService users;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TimeFormatter time;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final MainMenuService mainMenu;
    private final Clock clock;

    @Override
    public String domain() {
        return "c";
    }

    private static String cb(String action, Object... args) {
        return CallbackData.encode("c", action, args);
    }

    // ------------------------------------------------------------------ entry points

    /** Plain {@code /start} for a client: the last shop, or the list of shops. */
    public void openHome(BotContext ctx) {
        Shop last = shops.find(ctx.user().getLastShopId()).orElse(null);
        if (last != null) {
            openShop(ctx, last, null);
        } else {
            showShopList(ctx, 0, null);
        }
    }

    /** Deep link {@code s_<slug>}. */
    public void openShopBySlug(BotContext ctx, String slug) {
        Shop shop = shops.findBySlug(slug).orElse(null);
        if (shop == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "client.shop_not_found"));
            showShopList(ctx, 0, null);
            return;
        }
        openShop(ctx, shop, null);
    }

    /** Remembers the shop and starts the booking wizard (or explains that the shop is switched off). */
    private void openShop(BotContext ctx, Shop shop, Integer messageId) {
        sessions.clear(ctx.telegramId());
        users.setLastShop(ctx.user(), shop.getId());
        if (!shop.isActive()) {
            sender.editOrSend(ctx.chatId(), messageId, inactiveText(ctx.lang(), shop), otherShopKeyboard(ctx.lang()));
            return;
        }
        showBarbers(ctx, shop, messageId);
    }

    @Override
    public void onMenu(BotContext ctx, String menuKey) {
        switch (menuKey) {
            case "menu.c.book" -> openHome(ctx);
            case "menu.c.mybookings" -> showMyBookings(ctx, null);
            case "menu.c.shop" -> showShopInfo(ctx);
            case "menu.c.othershop" -> showShopList(ctx, 0, null);
            case "menu.c.lang" -> sender.send(ctx.chatId(), i18n.t(ctx.lang(), "start.choose_language"), keyboards.languageChoice());
            default -> mainMenu.show(ctx);
        }
    }

    @Override
    public void onCallback(BotContext ctx, CallbackData data) {
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            case "shops" -> showShopList(ctx, data.intArg(0), messageId);
            case "search" -> {
                sessions.set(ctx.telegramId(), BotState.C_SEARCH);
                sender.editOrSend(ctx.chatId(), messageId, i18n.t(ctx.lang(), "client.search_prompt"),
                        keyboards.rows().row(keyboards.back(ctx.lang(), cb("shops", 0))).build());
            }
            case "shopopen" -> openShop(ctx, requireShop(data.longArg(0)), messageId);
            case "barbers" -> showBarbers(ctx, requireShop(data.longArg(0)), messageId);
            case "na" -> {
                sender.answer(ctx.callbackId(), i18n.t(ctx.lang(), "client.barber_unavailable"), true);
                ctx.markCallbackAnswered();
            }
            case "bar" -> showBarberCard(ctx, requireBarber(data.longArg(0)), messageId);
            case "days" -> showDates(ctx, requireBarber(data.longArg(0)), messageId);
            case "day" -> showTimes(ctx, requireBarber(data.longArg(0)), CompactTime.parseDate(data.arg(1)), messageId);
            case "slot" -> showConfirm(ctx, requireBarber(data.longArg(0)), CompactTime.parseDateTime(data.arg(1)), messageId);
            case "ok" -> book(ctx, requireBarber(data.longArg(0)), CompactTime.parseDateTime(data.arg(1)), messageId);
            case "no" -> {
                Barber barber = barbers.find(data.longArg(0)).orElse(null);
                if (barber == null) {
                    showMyBookings(ctx, messageId);
                } else {
                    showBarbers(ctx, requireShop(barber.getShopId()), messageId);
                }
            }
            case "my" -> showMyBookings(ctx, messageId);
            case "mycxl" -> askCancel(ctx, data.longArg(0), messageId);
            case "mycxlok" -> cancel(ctx, data.longArg(0), messageId);
            case "confirm" -> confirmComing(ctx, data.longArg(0), messageId);
            default -> {
            }
        }
    }

    @Override
    public void onInput(BotContext ctx) {
        if (ctx.state() != BotState.C_SEARCH) {
            mainMenu.show(ctx);
            return;
        }
        String query = ctx.text();
        if (query == null || query.isBlank()) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "client.search_prompt"));
            return;
        }
        sessions.clear(ctx.telegramId());
        showSearchResults(ctx, query);
    }

    // ------------------------------------------------------------------ shops

    private void showShopList(BotContext ctx, int requestedPage, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Page<Shop> page = shops.listActive(requestedPage);
        if (page.isEmpty() && page.getTotalElements() == 0) {
            sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "client.no_shops"), null);
            return;
        }
        int current = page.getNumber();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Shop shop : page) {
            buttons.add(keyboards.btn(i18n.t(lang, "client.shop_btn", shop.getName()), cb("shopopen", shop.getId())));
        }
        var rows = keyboards.rows().grid(buttons, 1);
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page.hasPrevious()) {
            nav.add(keyboards.btn(i18n.t(lang, "btn.prev"), cb("shops", current - 1)));
        }
        if (page.hasNext()) {
            nav.add(keyboards.btn(i18n.t(lang, "btn.next"), cb("shops", current + 1)));
        }
        rows.row(nav);
        rows.row(keyboards.btn(i18n.t(lang, "client.btn.search"), cb("search")));
        sender.editOrSend(ctx.chatId(), messageId,
                i18n.t(lang, "client.choose_shop", current + 1, Math.max(page.getTotalPages(), 1)), rows.build());
    }

    private void showSearchResults(BotContext ctx, String query) {
        Lang lang = ctx.lang();
        Page<Shop> page = shops.search(query, 0);
        if (page.isEmpty()) {
            sender.send(ctx.chatId(), i18n.t(lang, "client.search_none", esc(query)),
                    keyboards.rows().row(keyboards.btn(i18n.t(lang, "client.btn.search"), cb("search")))
                            .row(keyboards.btn(i18n.t(lang, "client.btn.all_shops"), cb("shops", 0))).build());
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Shop shop : page) {
            buttons.add(keyboards.btn(i18n.t(lang, "client.shop_btn", shop.getName()), cb("shopopen", shop.getId())));
        }
        var rows = keyboards.rows().grid(buttons, 1)
                .row(keyboards.btn(i18n.t(lang, "client.btn.search"), cb("search")));
        String hint = page.hasNext() ? "\n" + i18n.t(lang, "client.search_more") : "";
        sender.send(ctx.chatId(), i18n.t(lang, "client.search_results", esc(query)) + hint, rows.build());
    }

    private void showShopInfo(BotContext ctx) {
        Lang lang = ctx.lang();
        Shop shop = shops.find(ctx.user().getLastShopId()).orElse(null);
        if (shop == null) {
            showShopList(ctx, 0, null);
            return;
        }
        StringBuilder text = new StringBuilder(i18n.t(lang, "client.shop.title", esc(shop.getName())));
        if (shop.getAddress() != null) {
            text.append("\n").append(i18n.t(lang, "client.shop.address", esc(shop.getAddress())));
        }
        if (shop.getLandmark() != null) {
            text.append("\n").append(i18n.t(lang, "client.shop.landmark", esc(shop.getLandmark())));
        }
        if (shop.getPhone() != null) {
            text.append("\n").append(i18n.t(lang, "client.shop.phone", esc(PhoneFormatter.pretty(shop.getPhone()))));
        }
        if (shop.getDescription() != null) {
            text.append("\n\n").append(esc(shop.getDescription()));
        }
        InlineKeyboardMarkup keyboard = mapKeyboard(lang, shop);
        if (shop.getPhotoFileId() != null) {
            sender.sendPhoto(ctx.chatId(), shop.getPhotoFileId(), text.toString(), keyboard);
        } else {
            sender.send(ctx.chatId(), text.toString(), keyboard);
        }
        if (shop.hasLocation()) {
            sender.sendLocation(ctx.chatId(), shop.getLatitude(), shop.getLongitude());
        }
    }

    // ------------------------------------------------------------------ booking wizard

    /** Step 1: choose a barber. With a single barber this step is skipped. */
    private void showBarbers(BotContext ctx, Shop shop, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        if (!shop.isActive()) {
            sender.editOrSend(ctx.chatId(), messageId, inactiveText(lang, shop), otherShopKeyboard(lang));
            return;
        }
        List<Barber> list = barbers.activeInShop(shop.getId());
        if (list.isEmpty()) {
            sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "client.no_barbers", esc(shop.getName())),
                    otherShopKeyboard(lang));
            return;
        }
        if (list.size() == 1 && isBookable(list.getFirst())) {
            showBarberCardInternal(ctx, list.getFirst(), shop, messageId, false);
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Barber barber : list) {
            if (isBookable(barber)) {
                buttons.add(keyboards.btn(i18n.t(lang, "client.barber_btn", barber.getDisplayName()), cb("bar", barber.getId())));
            } else {
                buttons.add(keyboards.btn(i18n.t(lang, "icon.off") + " " + barber.getDisplayName(), cb("na")));
            }
        }
        var rows = keyboards.rows().grid(buttons, 1)
                .row(keyboards.btn(i18n.t(lang, "client.btn.other_shop"), cb("shops", 0)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "client.choose_barber", esc(shop.getName())), rows.build());
    }

    /** A barber can be booked only while switched on and with working hours configured. */
    private boolean isBookable(Barber barber) {
        return barber.isAcceptingBookings() && barbers.hasWorkingHours(barber.getId());
    }

    /** Step 2: the barber card with the price list (information only). */
    private void showBarberCard(BotContext ctx, Barber barber, Integer messageId) {
        Shop shop = requireShop(barber.getShopId());
        boolean single = barbers.activeInShop(shop.getId()).size() == 1;
        showBarberCardInternal(ctx, barber, shop, messageId, !single);
    }

    private void showBarberCardInternal(BotContext ctx, Barber barber, Shop shop, Integer messageId, boolean withBack) {
        Lang lang = ctx.lang();
        if (!isBookable(barber)) {
            sender.answer(ctx.callbackId(), i18n.t(lang, "client.barber_unavailable"), true);
            ctx.markCallbackAnswered();
            return;
        }
        StringBuilder text = new StringBuilder(i18n.t(lang, "client.barber_title", esc(barber.getDisplayName())));
        if (barber.getBio() != null) {
            text.append("\n").append(esc(barber.getBio()));
        }
        text.append("\n\n").append(i18n.t(lang, "client.prices_title"));
        List<PriceItem> items = prices.list(barber.getId());
        if (items.isEmpty()) {
            text.append("\n").append(i18n.t(lang, "client.prices_none"));
        }
        for (PriceItem item : items) {
            text.append("\n• ").append(esc(item.getName())).append(" — ").append(i18n.money(lang, item.getPrice()));
        }
        text.append("\n\n").append(i18n.t(lang, "client.payment_note"));
        var rows = keyboards.rows().row(keyboards.btn(i18n.t(lang, "client.btn.see_slots"), cb("days", barber.getId())));
        if (withBack) {
            rows.row(keyboards.back(lang, cb("barbers", shop.getId())));
        }
        if (barber.getPhotoFileId() != null) {
            if (messageId != null) {
                sender.delete(ctx.chatId(), messageId);
            }
            sender.sendPhoto(ctx.chatId(), barber.getPhotoFileId(), text.toString(), rows.build());
        } else {
            sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
        }
    }

    /** Step 3: choose a date. */
    private void showDates(BotContext ctx, Barber barber, Integer messageId) {
        Lang lang = ctx.lang();
        Shop shop = requireShop(barber.getShopId());
        List<LocalDate> dates = slots.availableDates(barber, shop);
        var rows = keyboards.rows();
        if (dates.isEmpty()) {
            rows.row(keyboards.back(lang, cb("bar", barber.getId())));
            sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "client.no_dates"), rows.build());
            return;
        }
        LocalDate today = clock.instant().atZone(shop.zone()).toLocalDate();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (LocalDate date : dates) {
            buttons.add(keyboards.btn(time.dayButton(lang, date, today), cb("day", barber.getId(), CompactTime.date(date))));
        }
        rows.grid(buttons, DATE_BUTTONS_PER_ROW).row(keyboards.back(lang, cb("bar", barber.getId())));
        sender.editOrSend(ctx.chatId(), messageId,
                i18n.t(lang, "client.choose_date", esc(barber.getDisplayName())), rows.build());
    }

    /** Step 4: choose a time. */
    private void showTimes(BotContext ctx, Barber barber, LocalDate date, Integer messageId) {
        Lang lang = ctx.lang();
        Shop shop = requireShop(barber.getShopId());
        List<LocalTime> free = slots.freeSlots(barber, shop, date, false);
        if (free.isEmpty()) {
            sender.answer(ctx.callbackId(), i18n.t(lang, "client.no_times"), true);
            ctx.markCallbackAnswered();
            showDates(ctx, barber, messageId);
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (LocalTime t : free) {
            buttons.add(keyboards.btn(time.time(t), cb("slot", barber.getId(), CompactTime.dateTime(LocalDateTime.of(date, t)))));
        }
        var rows = keyboards.rows().grid(buttons, SLOT_BUTTONS_PER_ROW)
                .row(keyboards.btn(i18n.t(lang, "client.btn.days"), cb("days", barber.getId())));
        sender.editOrSend(ctx.chatId(), messageId,
                i18n.t(lang, "client.choose_time", time.longDate(lang, date)), rows.build());
    }

    /** Step 5: confirm. */
    private void showConfirm(BotContext ctx, Barber barber, LocalDateTime dateTime, Integer messageId) {
        Lang lang = ctx.lang();
        Shop shop = requireShop(barber.getShopId());
        if (!slots.isFree(barber, shop, dateTime.toLocalDate(), dateTime.toLocalTime(), false)) {
            sender.send(ctx.chatId(), i18n.t(lang, "booking.slot_taken"));
            showTimes(ctx, barber, dateTime.toLocalDate(), messageId);
            return;
        }
        String text = i18n.t(lang, "client.confirm", esc(shop.getName()), placeLine(lang, shop),
                esc(barber.getDisplayName()), time.longDate(lang, dateTime.toLocalDate()), time.time(dateTime.toLocalTime()));
        var keyboard = keyboards.rows().row(
                keyboards.btn(i18n.t(lang, "client.btn.confirm"), cb("ok", barber.getId(), CompactTime.dateTime(dateTime))),
                keyboards.btn(i18n.t(lang, "client.btn.cancel"), cb("no", barber.getId()))).build();
        sender.editOrSend(ctx.chatId(), messageId, text, keyboard);
    }

    /** Step 6: the booking itself. Everything is re-validated by the service inside a transaction. */
    private void book(BotContext ctx, Barber barber, LocalDateTime dateTime, Integer messageId) {
        Lang lang = ctx.lang();
        Shop shop = requireShop(barber.getShopId());
        BookResult result = bookings.book(ctx.user().getId(), barber.getId(), dateTime.toLocalDate(), dateTime.toLocalTime());
        switch (result) {
            case BookResult.Ok ok -> onBooked(ctx, ok.booking(), barber, shop, messageId);
            case BookResult.Rejected rejected -> {
                switch (rejected.reason()) {
                    case SLOT_TAKEN -> {
                        // Somebody was faster: tell the client and show fresh slots.
                        sender.send(ctx.chatId(), i18n.t(lang, "booking.slot_taken"));
                        showTimes(ctx, barber, dateTime.toLocalDate(), messageId);
                    }
                    case LIMIT_REACHED -> sender.editOrSend(ctx.chatId(), messageId,
                            i18n.t(lang, "booking.limit_reached", shop.getMaxActiveBookingsPerClient()),
                            keyboards.rows().row(keyboards.btn(i18n.t(lang, "client.btn.my_bookings"), cb("my"))).build());
                    case SHOP_INACTIVE -> sender.editOrSend(ctx.chatId(), messageId, inactiveText(lang, shop), otherShopKeyboard(lang));
                    case BARBER_UNAVAILABLE -> sender.editOrSend(ctx.chatId(), messageId,
                            i18n.t(lang, "client.barber_unavailable"), otherShopKeyboard(lang));
                    case PHONE_REQUIRED -> {
                        sessions.set(ctx.telegramId(), BotState.AWAIT_PHONE);
                        sender.send(ctx.chatId(), i18n.t(lang, "phone.ask"), keyboards.contactRequest(lang));
                    }
                    case NOT_FOUND -> sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "error.not_found"), null);
                }
            }
        }
    }

    private void onBooked(BotContext ctx, Booking booking, Barber barber, Shop shop, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.clear(ctx.telegramId());
        String text;
        if (shop.getReminderMinutesBefore() > 0) {
            text = i18n.t(lang, "booking.success", Math.max(1, shop.getReminderMinutesBefore() / 60));
        } else {
            text = i18n.t(lang, "booking.success_no_reminder");
        }
        text += "\n\n" + i18n.t(lang, "booking.summary", esc(shop.getName()), esc(barber.getDisplayName()),
                time.longDateTime(lang, booking.getStartAt(), shop.zone()));
        sender.editOrSend(ctx.chatId(), messageId, text, mapKeyboard(lang, shop));
        if (shop.hasLocation()) {
            sender.sendLocation(ctx.chatId(), shop.getLatitude(), shop.getLongitude());
        }
        notifications.newBooking(booking);
    }

    // ------------------------------------------------------------------ my bookings

    /** Future BOOKED bookings, each with a cancel button. */
    public void showMyBookings(BotContext ctx, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        List<Booking> mine = bookings.activeForClient(ctx.user().getId());
        if (mine.isEmpty()) {
            sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "client.my.empty"), null);
            return;
        }
        StringBuilder text = new StringBuilder(i18n.t(lang, "client.my.title"));
        var rows = keyboards.rows();
        int n = 0;
        for (Booking booking : mine) {
            n++;
            Shop shop = requireShop(booking.getShopId());
            Barber barber = barbers.find(booking.getBarberId()).orElse(null);
            text.append("\n\n").append(n).append(". ").append(i18n.t(lang, "client.my.item", esc(shop.getName()),
                    barber == null ? "—" : esc(barber.getDisplayName()),
                    time.longDateTime(lang, booking.getStartAt(), shop.zone())));
            rows.row(keyboards.btn(i18n.t(lang, "client.my.btn_cancel", n,
                    time.shortDateTime(lang, booking.getStartAt(), shop.zone())), cb("mycxl", booking.getId())));
        }
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private void askCancel(BotContext ctx, long bookingId, Integer messageId) {
        Lang lang = ctx.lang();
        Booking booking = requireOwnBooking(ctx.user(), bookingId);
        Shop shop = requireShop(booking.getShopId());
        Barber barber = barbers.find(booking.getBarberId()).orElse(null);
        String text = i18n.t(lang, "client.cancel.ask", esc(shop.getName()),
                barber == null ? "—" : esc(barber.getDisplayName()),
                time.longDateTime(lang, booking.getStartAt(), shop.zone()));
        sender.editOrSend(ctx.chatId(), messageId, text, keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "client.cancel.btn_yes"), cb("mycxlok", bookingId)),
                        keyboards.btn(i18n.t(lang, "client.cancel.btn_no"), cb("my"))).build());
    }

    private void cancel(BotContext ctx, long bookingId, Integer messageId) {
        Booking cancelled = bookings.cancelByClient(bookingId, ctx.user());
        notifications.clientCancelled(cancelled);
        sender.answer(ctx.callbackId(), i18n.t(ctx.lang(), "client.cancel.done"), false);
        ctx.markCallbackAnswered();
        showMyBookings(ctx, messageId);
    }

    /** "Boraman" button under the reminder. */
    private void confirmComing(BotContext ctx, long bookingId, Integer messageId) {
        Lang lang = ctx.lang();
        Booking booking = bookings.confirmByClient(bookingId, ctx.user());
        notifications.clientConfirmed(booking);
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "client.confirm.thanks"), null);
    }

    // ------------------------------------------------------------------ helpers

    private Booking requireOwnBooking(AppUser user, long bookingId) {
        Booking booking = bookings.find(bookingId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!user.getId().equals(booking.getClientUserId())) {
            throw new BusinessException("error.forbidden");
        }
        return booking;
    }

    private Shop requireShop(long id) {
        return shops.find(id).orElseThrow(() -> new BusinessException("error.not_found"));
    }

    private Barber requireBarber(long id) {
        return barbers.find(id).filter(Barber::isActive).orElseThrow(() -> new BusinessException("error.not_found"));
    }

    private String inactiveText(Lang lang, Shop shop) {
        String base = i18n.t(lang, "client.shop_inactive");
        if (shop.getPhone() != null) {
            base += " " + i18n.t(lang, "client.shop_inactive_phone", esc(PhoneFormatter.pretty(shop.getPhone())));
        }
        return base;
    }

    private InlineKeyboardMarkup otherShopKeyboard(Lang lang) {
        return keyboards.rows().row(keyboards.btn(i18n.t(lang, "client.btn.other_shop"), cb("shops", 0))).build();
    }

    /** "📍 Chilonzor 9-kvartal (mo'ljal: metro)\n", or an empty string when the shop has no address. */
    private String placeLine(Lang lang, Shop shop) {
        if (shop.getAddress() == null) {
            return "";
        }
        if (shop.getLandmark() == null) {
            return i18n.t(lang, "client.place", esc(shop.getAddress())) + "\n";
        }
        return i18n.t(lang, "client.place_with_landmark", esc(shop.getAddress()), esc(shop.getLandmark())) + "\n";
    }

    /** "[📍 Xaritada ochish]" button when the shop has a location. */
    private InlineKeyboardMarkup mapKeyboard(Lang lang, Shop shop) {
        if (!shop.hasLocation()) {
            return null;
        }
        String url = String.format(Locale.ROOT, "https://www.google.com/maps?q=%.6f,%.6f", shop.getLatitude(), shop.getLongitude());
        return keyboards.rows().row(keyboards.urlBtn(i18n.t(lang, "client.btn.open_map"), url)).build();
    }
}
