package pdp.service_bron.handler.owner;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pdp.service_bron.config.BotInfo;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Invite;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.handler.barber.BarberHandler;
import pdp.service_bron.service.AccessService;
import pdp.service_bron.service.BarberService;
import pdp.service_bron.service.BookingService;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.InviteService;
import pdp.service_bron.service.NotificationService;
import pdp.service_bron.service.UserService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.TimeFormatter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * Owner's barber management: list with status, one-time invite links, rename, reorder,
 * deactivate/activate (with the option to cancel future bookings first), "I am a barber too".
 */
@Component
@RequiredArgsConstructor
class OwnerBarbersView {

    private static final String KEY_BARBER = "barber";

    private final AccessService access;
    private final BarberService barbers;
    private final BookingService bookings;
    private final InviteService invites;
    private final NotificationService notifications;
    private final UserService users;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TimeFormatter time;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final BotInfo botInfo;
    private final BarberHandler barberHandler;
    private final Clock clock;

    private static String cb(String action, Object... args) {
        return CallbackData.encode("o", action, args);
    }

    void show(BotContext ctx, Shop shop, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        List<Barber> all = barbers.allInShop(shop.getId());
        StringBuilder text = new StringBuilder(i18n.t(lang, "owner.barbers.title"));
        var rows = keyboards.rows();
        if (all.isEmpty()) {
            text.append("\n\n").append(i18n.t(lang, "owner.barbers.empty"));
        }
        int n = 0;
        for (Barber barber : all) {
            n++;
            text.append("\n").append(n).append(". ").append(esc(barber.getDisplayName())).append(" — ")
                    .append(status(lang, barber));
            rows.row(keyboards.btn(n + ". " + barber.getDisplayName(), cb("bt", barber.getId())));
        }
        rows.row(keyboards.btn(i18n.t(lang, "owner.btn.add_barber"), cb("badd")));
        if (access.barberOf(ctx.user()).isEmpty()) {
            rows.row(keyboards.btn(i18n.t(lang, "owner.btn.i_am_barber"), cb("bme")));
        }
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    /** @return true when the callback belongs to this view. */
    boolean onCallback(BotContext ctx, Shop shop, CallbackData data) {
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            case "bl" -> show(ctx, shop, messageId);
            case "badd" -> sendInvite(ctx, shop);
            case "bme" -> becomeBarber(ctx, shop, messageId);
            case "bt" -> showBarber(ctx, requireInShop(shop, data.longArg(0)), messageId);
            case "bup" -> {
                barbers.move(ctx.user(), requireInShop(shop, data.longArg(0)).getId(), -1);
                show(ctx, shop, messageId);
            }
            case "bdn" -> {
                barbers.move(ctx.user(), requireInShop(shop, data.longArg(0)).getId(), 1);
                show(ctx, shop, messageId);
            }
            case "bren" -> askName(ctx, requireInShop(shop, data.longArg(0)), messageId);
            case "bact" -> toggle(ctx, shop, requireInShop(shop, data.longArg(0)), messageId);
            case "bdeact" -> deactivateWithCancel(ctx, shop, requireInShop(shop, data.longArg(0)), messageId);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** @return true when the state belongs to this view. */
    boolean onInput(BotContext ctx, Shop shop) {
        if (ctx.state() != BotState.O_BARBER_NAME) {
            return false;
        }
        String text = ctx.text();
        if (text == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_text"));
            return true;
        }
        Barber barber = requireInShop(shop, Long.parseLong(ctx.session().get(KEY_BARBER)));
        try {
            barbers.rename(ctx.user(), barber.getId(), text);
        } catch (BusinessException e) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), e.key(), e.args()));
            return true;
        }
        sessions.clear(ctx.telegramId());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.saved"));
        showBarber(ctx, requireInShop(shop, barber.getId()), null);
        return true;
    }

    // ------------------------------------------------------------------ screens and actions

    private void showBarber(BotContext ctx, Barber barber, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        boolean hasHours = barbers.hasWorkingHours(barber.getId());
        String text = i18n.t(lang, "owner.barber.details", esc(barber.getDisplayName()), status(lang, barber),
                i18n.t(lang, "unit.minutes", barber.getSlotMinutes()),
                i18n.t(lang, hasHours ? "icon.on" : "icon.off"));
        var rows = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "btn.up"), cb("bup", barber.getId())),
                        keyboards.btn(i18n.t(lang, "btn.down"), cb("bdn", barber.getId())),
                        keyboards.btn(i18n.t(lang, "owner.btn.rename"), cb("bren", barber.getId())))
                .row(keyboards.btn(i18n.t(lang, barber.isActive() ? "owner.btn.deactivate" : "owner.btn.activate"),
                        cb("bact", barber.getId())))
                .row(keyboards.back(lang, cb("bl")));
        sender.editOrSend(ctx.chatId(), messageId, text, rows.build());
    }

    private void askName(BotContext ctx, Barber barber, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.O_BARBER_NAME, Map.of(KEY_BARBER, String.valueOf(barber.getId())));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.settings.ask_name"),
                keyboards.rows().row(keyboards.back(lang, cb("bt", barber.getId()))).build());
    }

    private void sendInvite(BotContext ctx, Shop shop) {
        Invite invite = invites.createBarberInvite(ctx.user(), shop.getId());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.barber_invite", botInfo.link(InviteService.payload(invite))));
    }

    private void becomeBarber(BotContext ctx, Shop shop, Integer messageId) {
        Lang lang = ctx.lang();
        if (access.barberOf(ctx.user()).isPresent()) {
            show(ctx, shop, messageId);
            return;
        }
        Barber barber = barbers.create(shop, ctx.user(), ctx.user().getFirstName());
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "owner.wiz.barber_created"), null);
        barberHandler.startWizard(ctx, barber);
    }

    /** Deactivation is direct when nothing is booked; otherwise the future bookings are listed first. */
    private void toggle(BotContext ctx, Shop shop, Barber barber, Integer messageId) {
        Lang lang = ctx.lang();
        if (!barber.isActive()) {
            barbers.setActive(ctx.user(), barber.getId(), true);
            showBarber(ctx, requireInShop(shop, barber.getId()), messageId);
            return;
        }
        List<Booking> future = futureBookings(barber);
        if (future.isEmpty()) {
            barbers.setActive(ctx.user(), barber.getId(), false);
            notifyDeactivated(barber, shop);
            showBarber(ctx, requireInShop(shop, barber.getId()), messageId);
            return;
        }
        ZoneId zone = shop.zone();
        StringBuilder list = new StringBuilder();
        for (Booking booking : future) {
            list.append("\n• ").append(time.shortDateTime(lang, booking.getStartAt(), zone)).append(" — ")
                    .append(esc(booking.getClientName()));
        }
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "owner.barber.deactivate_conflicts",
                        esc(barber.getDisplayName()), future.size(), list),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "owner.btn.cancel_and_deactivate"), cb("bdeact", barber.getId())))
                        .row(keyboards.back(lang, cb("bt", barber.getId()))).build());
    }

    private void deactivateWithCancel(BotContext ctx, Shop shop, Barber barber, Integer messageId) {
        Instant now = clock.instant();
        List<Booking> cancelled = bookings.cancelOverlappingByBarber(barber.getId(), ctx.user(), now,
                now.plusSeconds(FAR_FUTURE_SECONDS), null);
        barbers.setActive(ctx.user(), barber.getId(), false);
        notifications.bookingsCancelledByBarber(cancelled);
        notifyDeactivated(barber, shop);
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.barber.deactivated_cancelled", cancelled.size()));
        showBarber(ctx, requireInShop(shop, barber.getId()), null);
    }

    private static final long FAR_FUTURE_SECONDS = 400L * 24 * 3600;

    private List<Booking> futureBookings(Barber barber) {
        Instant now = clock.instant();
        return new ArrayList<>(bookings.bookedOverlapping(barber.getId(), now, now.plusSeconds(FAR_FUTURE_SECONDS)));
    }

    private void notifyDeactivated(Barber barber, Shop shop) {
        if (barber.getUserId() != null) {
            notifications.notice(users.findById(barber.getUserId()).orElse(null), "notify.barber.deactivated",
                    esc(shop.getName()));
        }
    }

    private String status(Lang lang, Barber barber) {
        if (!barber.isActive()) {
            return i18n.t(lang, "owner.barber.status_inactive");
        }
        return i18n.t(lang, barber.isAcceptingBookings() ? "owner.barber.status_active" : "owner.barber.status_paused");
    }

    /** The barber must belong to the caller's own shop. */
    private Barber requireInShop(Shop shop, long barberId) {
        Barber barber = barbers.find(barberId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!barber.getShopId().equals(shop.getId())) {
            throw new BusinessException("error.forbidden");
        }
        return barber;
    }
}
