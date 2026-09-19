package pdp.service_bron.handler.barber;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.domain.WorkingHours;
import pdp.service_bron.service.BookingService;
import pdp.service_bron.service.BookingService.BookResult;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.NotificationService;
import pdp.service_bron.service.ScheduleService;
import pdp.service_bron.service.SlotService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.PhoneFormatter;
import pdp.service_bron.util.TimeFormatter;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static pdp.service_bron.handler.barber.BarberSupport.cb;
import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * Barber's day timeline (today / tomorrow / any date), booking details, cancel and attendance actions,
 * and manual (walk-in / phone) bookings.
 */
@Component
@RequiredArgsConstructor
class BarberDayView {

    private static final int DATE_BUTTONS = 14;
    private static final String KEY_BOOKING = "bid";
    private static final String KEY_DATETIME = "dt";
    private static final String KEY_NAME = "name";

    private final BookingService bookings;
    private final SlotService slots;
    private final ScheduleService schedule;
    private final NotificationService notifications;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TimeFormatter time;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final BarberSupport support;
    private final Clock clock;

    // ------------------------------------------------------------------ entry points

    void onMenu(BotContext ctx, Barber barber, String menuKey) {
        Shop shop = support.shop(barber);
        LocalDate today = today(shop);
        switch (menuKey) {
            case "menu.b.today" -> showDay(ctx, barber, today, null);
            case "menu.b.tomorrow" -> showDay(ctx, barber, today.plusDays(1), null);
            case "menu.b.schedule" -> showDatePicker(ctx, barber, null);
            case "menu.b.manual" -> startManual(ctx, barber, null);
            default -> {
            }
        }
    }

    /** @return true when the callback belongs to this view. */
    boolean onCallback(BotContext ctx, Barber barber, CallbackData data) {
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            case "day" -> showDay(ctx, barber, BarberSupport.parseDate(data.arg(0)), messageId);
            case "pick" -> showDatePicker(ctx, barber, messageId);
            case "bk" -> showBooking(ctx, barber, data.longArg(0), messageId);
            case "cxl" -> askCancelReason(ctx, barber, data.longArg(0), messageId);
            case "cxlgo" -> cancel(ctx, barber, data.longArg(0), null, messageId);
            case "came" -> attendance(ctx, barber, data.longArg(0), true, messageId);
            case "no" -> attendance(ctx, barber, data.longArg(0), false, messageId);
            case "man" -> startManual(ctx, barber, messageId);
            case "mdate" -> showManualSlots(ctx, barber, BarberSupport.parseDate(data.arg(0)), messageId);
            case "mslot" -> askManualName(ctx, BarberSupport.parseDateTime(data.arg(0)), messageId);
            case "mskip" -> saveManual(ctx, barber, null);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** @return true when the state belongs to this view. */
    boolean onInput(BotContext ctx, Barber barber) {
        switch (ctx.state()) {
            case B_CANCEL_REASON -> {
                String text = ctx.text();
                if (text == null) {
                    sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_text"));
                } else {
                    cancel(ctx, barber, Long.parseLong(ctx.session().get(KEY_BOOKING)), text, null);
                }
            }
            case B_MANUAL_NAME -> {
                String text = ctx.text();
                if (text == null || text.isBlank()) {
                    sender.send(ctx.chatId(), i18n.t(ctx.lang(), "manual.name_invalid"));
                    return true;
                }
                if (text.trim().length() > 100) {
                    sender.send(ctx.chatId(), i18n.t(ctx.lang(), "manual.name_invalid"));
                    return true;
                }
                sessions.set(ctx.telegramId(), BotState.B_MANUAL_PHONE,
                        Map.of(KEY_DATETIME, ctx.session().get(KEY_DATETIME), KEY_NAME, text.trim()));
                Lang lang = ctx.lang();
                sender.send(ctx.chatId(), i18n.t(lang, "manual.ask_phone"),
                        keyboards.rows().row(keyboards.btn(i18n.t(lang, "btn.skip"), cb("mskip"))).build());
            }
            case B_MANUAL_PHONE -> {
                String text = ctx.text();
                String phone = text == null ? null : PhoneFormatter.normalize(text).orElse(null);
                if (phone == null) {
                    sender.send(ctx.chatId(), i18n.t(ctx.lang(), "phone.invalid_typed"));
                    return true;
                }
                saveManual(ctx, barber, phone);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ day timeline

    void showDay(BotContext ctx, Barber barber, LocalDate date, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Shop shop = support.shop(barber);
        ZoneId zone = shop.zone();
        StringBuilder text = new StringBuilder();
        text.append(i18n.t(lang, "barber.day.title", time.longDate(lang, date))).append(hoursSummary(lang, barber, date));

        List<Booking> day = bookings.forBarberOnDate(barber, shop, date);
        if (day.isEmpty()) {
            text.append("\n\n").append(i18n.t(lang, "barber.day.empty"));
        } else {
            text.append("\n");
            for (Booking booking : day) {
                text.append('\n').append(statusIcon(lang, booking)).append(time.time(booking.getStartAt(), zone))
                        .append(" — ").append(support.clientLabel(lang, booking));
            }
        }
        List<LocalTime> free = slots.freeSlots(barber, shop, date, true);
        text.append("\n\n");
        if (free.isEmpty()) {
            text.append(i18n.t(lang, "barber.day.no_free"));
        } else {
            text.append(i18n.t(lang, "barber.day.free", free.stream().map(time::time).collect(Collectors.joining(", "))));
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Booking booking : day) {
            if (booking.getStatus() == BookingStatus.BOOKED) {
                buttons.add(keyboards.btn(time.time(booking.getStartAt(), zone) + " " + booking.getClientName(),
                        cb("bk", booking.getId())));
            }
        }
        var rows = keyboards.rows().grid(buttons, 2);
        rows.row(keyboards.btn(i18n.t(lang, "barber.btn.pick_date"), cb("pick")),
                keyboards.btn(i18n.t(lang, "barber.btn.add_manual"), cb("man")));
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private String hoursSummary(Lang lang, Barber barber, LocalDate date) {
        WorkingHours hours = schedule.week(barber.getId()).get(date.getDayOfWeek().getValue());
        if (hours == null || hours.isDayOff() || hours.getStartTime() == null) {
            return "   (" + i18n.t(lang, "barber.day.off") + ")";
        }
        String range = time.time(hours.getStartTime()) + "–" + time.time(hours.getEndTime());
        if (hours.hasBreak()) {
            return "   (" + i18n.t(lang, "barber.day.hours_break", range, time.time(hours.getBreakStart()),
                    time.time(hours.getBreakEnd())) + ")";
        }
        return "   (" + range + ")";
    }

    private String statusIcon(Lang lang, Booking booking) {
        return switch (booking.getStatus()) {
            case COMPLETED -> i18n.t(lang, "icon.on") + " ";
            case NO_SHOW -> i18n.t(lang, "icon.no_show") + " ";
            default -> "";
        };
    }

    private void showDatePicker(BotContext ctx, Barber barber, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        LocalDate today = today(support.shop(barber));
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (int i = 0; i < DATE_BUTTONS; i++) {
            LocalDate date = today.plusDays(i);
            buttons.add(keyboards.btn(time.dayButton(lang, date, today), cb("day", BarberSupport.date(date))));
        }
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.pick_date"),
                keyboards.rows().grid(buttons, 2).build());
    }

    // ------------------------------------------------------------------ booking details and actions

    private void showBooking(BotContext ctx, Barber barber, long bookingId, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Booking booking = requireOwnBooking(barber, bookingId);
        Shop shop = support.shop(barber);
        ZoneId zone = shop.zone();
        StringBuilder text = new StringBuilder(i18n.t(lang, "barber.booking.details",
                support.clientLabel(lang, booking), time.longDateTime(lang, booking.getStartAt(), zone),
                i18n.t(lang, "booking.status." + booking.getStatus().name().toLowerCase())));
        if (booking.isClientConfirmed() && booking.getStatus() == BookingStatus.BOOKED) {
            text.append("\n").append(i18n.t(lang, "barber.booking.confirmed"));
        }
        LocalDate date = booking.getStartAt().atZone(zone).toLocalDate();
        var rows = keyboards.rows();
        if (booking.getStatus() == BookingStatus.BOOKED) {
            if (!booking.getStartAt().isAfter(clock.instant())) {
                rows.row(keyboards.btn(i18n.t(lang, "barber.btn.came"), cb("came", booking.getId())),
                        keyboards.btn(i18n.t(lang, "barber.btn.no_show"), cb("no", booking.getId())));
            }
            rows.row(keyboards.btn(i18n.t(lang, "barber.btn.cancel_booking"), cb("cxl", booking.getId())));
        }
        rows.row(keyboards.back(lang, cb("day", BarberSupport.date(date))));
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private void askCancelReason(BotContext ctx, Barber barber, long bookingId, Integer messageId) {
        Lang lang = ctx.lang();
        Booking booking = requireOwnBooking(barber, bookingId);
        if (booking.getStatus() != BookingStatus.BOOKED) {
            throw new BusinessException("booking.not_active");
        }
        sessions.set(ctx.telegramId(), BotState.B_CANCEL_REASON, Map.of(KEY_BOOKING, String.valueOf(bookingId)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.cancel.ask_reason"),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "barber.btn.cancel_no_reason"), cb("cxlgo", bookingId)))
                        .row(keyboards.back(lang, cb("bk", bookingId))).build());
    }

    private void cancel(BotContext ctx, Barber barber, long bookingId, String reason, Integer messageId) {
        requireOwnBooking(barber, bookingId);
        Booking cancelled = bookings.cancelByBarber(bookingId, ctx.user(), reason);
        sessions.clear(ctx.telegramId());
        notifications.bookingCancelledByBarber(cancelled);
        Shop shop = support.shop(barber);
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "barber.cancel.done",
                esc(cancelled.getClientName()), time.shortDateTime(ctx.lang(), cancelled.getStartAt(), shop.zone())));
        showDay(ctx, barber, cancelled.getStartAt().atZone(shop.zone()).toLocalDate(), null);
    }

    private void attendance(BotContext ctx, Barber barber, long bookingId, boolean came, Integer messageId) {
        requireOwnBooking(barber, bookingId);
        Booking booking = bookings.markAttendance(bookingId, ctx.user(), came);
        Lang lang = ctx.lang();
        Shop shop = support.shop(barber);
        LocalDate date = booking.getStartAt().atZone(shop.zone()).toLocalDate();
        String text = i18n.t(lang, came ? "barber.attendance.came" : "barber.attendance.no_show",
                esc(booking.getClientName()), time.time(booking.getStartAt(), shop.zone()));
        sender.editOrSend(ctx.chatId(), messageId, text,
                keyboards.rows().row(keyboards.back(lang, cb("day", BarberSupport.date(date)))).build());
    }

    /** The booking must belong to this barber's own calendar. */
    private Booking requireOwnBooking(Barber barber, long bookingId) {
        Booking booking = bookings.find(bookingId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!booking.getBarberId().equals(barber.getId())) {
            throw new BusinessException("error.forbidden");
        }
        return booking;
    }

    // ------------------------------------------------------------------ manual booking

    void startManual(BotContext ctx, Barber barber, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Shop shop = support.shop(barber);
        LocalDate today = today(shop);
        List<LocalDate> dates = slots.availableDates(barber, shop, true);
        if (dates.isEmpty()) {
            sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.manual.no_dates"), null);
            return;
        }
        List<InlineKeyboardButton> buttons = dates.stream()
                .map(d -> keyboards.btn(time.dayButton(lang, d, today), cb("mdate", BarberSupport.date(d)))).toList();
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.manual.pick_date"),
                keyboards.rows().grid(buttons, 2).build());
    }

    private void showManualSlots(BotContext ctx, Barber barber, LocalDate date, Integer messageId) {
        Lang lang = ctx.lang();
        Shop shop = support.shop(barber);
        List<LocalTime> free = slots.freeSlots(barber, shop, date, true);
        if (free.isEmpty()) {
            startManual(ctx, barber, messageId);
            return;
        }
        List<InlineKeyboardButton> buttons = free.stream().map(t -> keyboards.btn(time.time(t),
                cb("mslot", BarberSupport.dateTime(LocalDateTime.of(date, t))))).toList();
        var rows = keyboards.rows().grid(buttons, 4).row(keyboards.back(lang, cb("man")));
        sender.editOrSend(ctx.chatId(), messageId,
                i18n.t(lang, "barber.manual.pick_time", time.longDate(lang, date)), rows.build());
    }

    private void askManualName(BotContext ctx, LocalDateTime dateTime, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_MANUAL_NAME, Map.of(KEY_DATETIME, BarberSupport.dateTime(dateTime)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "manual.ask_name"),
                keyboards.rows().row(keyboards.back(lang, cb("man"))).build());
    }

    private void saveManual(BotContext ctx, Barber barber, String phone) {
        Lang lang = ctx.lang();
        if (ctx.state() != BotState.B_MANUAL_PHONE) {
            return;
        }
        LocalDateTime dateTime = BarberSupport.parseDateTime(ctx.session().get(KEY_DATETIME));
        String name = ctx.session().get(KEY_NAME);
        BookResult result = bookings.bookManual(ctx.user(), barber.getId(), dateTime.toLocalDate(),
                dateTime.toLocalTime(), name, phone);
        sessions.clear(ctx.telegramId());
        switch (result) {
            case BookResult.Ok ok -> {
                Shop shop = support.shop(barber);
                sender.send(ctx.chatId(), i18n.t(lang, "manual.saved", esc(name),
                        time.longDateTime(lang, ok.booking().getStartAt(), shop.zone())));
                showDay(ctx, barber, dateTime.toLocalDate(), null);
            }
            case BookResult.Rejected rejected -> {
                sender.send(ctx.chatId(), i18n.t(lang, "manual.slot_taken"));
                startManual(ctx, barber, null);
            }
        }
    }

    private LocalDate today(Shop shop) {
        return clock.instant().atZone(shop.zone()).toLocalDate();
    }
}
