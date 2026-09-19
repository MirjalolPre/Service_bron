package pdp.service_bron.handler.barber;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.domain.TimeOff;
import pdp.service_bron.domain.WorkingHours;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.NotificationService;
import pdp.service_bron.service.ScheduleService;
import pdp.service_bron.service.ScheduleService.BlockedRange;
import pdp.service_bron.service.ScheduleService.DaySchedule;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.TimeFormatter;
import pdp.service_bron.util.TimeParser;
import pdp.service_bron.util.TimeParser.TimeRange;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static pdp.service_bron.handler.barber.BarberSupport.cb;
import static pdp.service_bron.telegram.HtmlEscaper.esc;

/** Working hours, breaks, days off and blocked time ("Vaqtni yopish"). */
@Component
@RequiredArgsConstructor
class BarberHoursView {

    private static final int DATE_BUTTONS = 14;
    private static final String KEY_DOW = "dow";
    private static final String KEY_DATE = "date";

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
        if ("menu.b.hours".equals(menuKey)) {
            showWeek(ctx, barber, null, false);
        } else if ("menu.b.timeoff".equals(menuKey)) {
            showTimeOff(ctx, barber, null);
        }
    }

    /** @return true when the callback belongs to this view. */
    boolean onCallback(BotContext ctx, Barber barber, CallbackData data) {
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            case "hw" -> showWeek(ctx, barber, messageId, false);
            case "hd" -> showDay(ctx, barber, data.intArg(0), messageId);
            case "ht" -> askTime(ctx, data.intArg(0), messageId);
            case "hb" -> askBreak(ctx, barber, data.intArg(0), messageId);
            case "hbn" -> changeBreak(ctx, barber, data.intArg(0), null, null, messageId);
            case "hoff" -> apply(ctx, barber, Map.of(data.intArg(0), DaySchedule.off()), cb("hoffgo", data.intArg(0)), data.intArg(0), messageId);
            case "hoffgo" -> applyConfirmed(ctx, barber, Map.of(data.intArg(0), DaySchedule.off()), data.intArg(0), messageId);
            case "hon" -> turnOn(ctx, barber, data.intArg(0), messageId);
            case "hall" -> {
                Map<Integer, DaySchedule> plan = schedule.copyToWorkdays(barber.getId(), data.intArg(0));
                apply(ctx, barber, plan, cb("hallgo", data.intArg(0)), data.intArg(0), messageId);
            }
            case "hallgo" -> applyConfirmed(ctx, barber, schedule.copyToWorkdays(barber.getId(), data.intArg(0)),
                    data.intArg(0), messageId);
            case "hgo" -> {
                DaySchedule day = DaySchedule.work(BarberSupport.parseHm(data.arg(1)), BarberSupport.parseHm(data.arg(2)),
                        BarberSupport.parseHm(data.arg(3)), BarberSupport.parseHm(data.arg(4)));
                applyConfirmed(ctx, barber, Map.of(data.intArg(0), day), data.intArg(0), messageId);
            }
            case "hpre" -> {
                int preset = data.intArg(0);
                apply(ctx, barber, schedule.preset(preset), cb("hprego", preset), null, messageId);
            }
            case "hprego" -> applyConfirmed(ctx, barber, schedule.preset(data.intArg(0)), null, messageId);
            case "tw" -> showTimeOff(ctx, barber, messageId);
            case "tdate" -> showTimeOffOptions(ctx, BarberSupport.parseDate(data.arg(0)), messageId);
            case "tall" -> tryClose(ctx, barber, BarberSupport.parseDate(data.arg(0)), null, null, messageId);
            case "trange" -> askTimeOffRange(ctx, BarberSupport.parseDate(data.arg(0)), messageId);
            case "tforce" -> forceClose(ctx, barber, BarberSupport.parseDate(data.arg(0)),
                    BarberSupport.parseHm(data.arg(1)), BarberSupport.parseHm(data.arg(2)), messageId);
            case "tdel" -> {
                schedule.deleteTimeOff(ctx.user(), data.longArg(0));
                showTimeOff(ctx, barber, messageId);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** @return true when the state belongs to this view. */
    boolean onInput(BotContext ctx, Barber barber) {
        BotState state = ctx.state();
        if (state != BotState.B_HOURS_TIME && state != BotState.B_HOURS_BREAK && state != BotState.B_TIMEOFF_RANGE) {
            return false;
        }
        Lang lang = ctx.lang();
        String text = ctx.text();
        TimeRange range = text == null ? null : TimeParser.parseRange(text).orElse(null);
        if (range == null) {
            sender.send(ctx.chatId(), i18n.t(lang, "hours.bad_format"));
            return true;
        }
        try {
            switch (state) {
                case B_HOURS_TIME -> changeTime(ctx, barber, Integer.parseInt(ctx.session().get(KEY_DOW)), range);
                case B_HOURS_BREAK -> changeBreak(ctx, barber, Integer.parseInt(ctx.session().get(KEY_DOW)),
                        range.start(), range.end(), null);
                default -> tryClose(ctx, barber, BarberSupport.parseDate(ctx.session().get(KEY_DATE)),
                        range.start(), range.end(), null);
            }
        } catch (BusinessException e) {
            sender.send(ctx.chatId(), i18n.t(lang, e.key(), e.args())); // stay in the state: the barber can retype
        }
        return true;
    }

    // ------------------------------------------------------------------ week and day screens

    /** The weekly overview. In wizard mode a "continue" button is added. */
    void showWeek(BotContext ctx, Barber barber, Integer messageId, boolean wizard) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Map<Integer, WorkingHours> week = schedule.week(barber.getId());
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            buttons.add(keyboards.btn(dayLabel(lang, dow, week.get(dow)), cb("hd", dow)));
        }
        var rows = keyboards.rows().grid(buttons, 2)
                .row(keyboards.btn(i18n.t(lang, "hours.btn.preset1"), cb("hpre", ScheduleService.PRESET_MON_SAT)))
                .row(keyboards.btn(i18n.t(lang, "hours.btn.preset2"), cb("hpre", ScheduleService.PRESET_EVERY_DAY)));
        if (wizard) {
            rows.row(keyboards.btn(i18n.t(lang, "barber.btn.continue"), cb("wdone")));
        }
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "hours.title"), rows.build());
    }

    private String dayLabel(Lang lang, int dow, WorkingHours hours) {
        String name = time.weekdayShort(lang, DayOfWeek.of(dow));
        if (hours == null || hours.getStartTime() == null || hours.getEndTime() == null) {
            return i18n.t(lang, hours != null && hours.isDayOff() ? "hours.day_off" : "hours.unset", name);
        }
        if (hours.isDayOff()) {
            return i18n.t(lang, "hours.day_off", name);
        }
        return i18n.t(lang, "hours.day_label", name, time.time(hours.getStartTime()), time.time(hours.getEndTime()));
    }

    void showDay(BotContext ctx, Barber barber, int dow, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        WorkingHours hours = schedule.week(barber.getId()).get(dow);
        boolean working = hours != null && !hours.isDayOff() && hours.getStartTime() != null;
        String dayName = time.weekdayLong(lang, DayOfWeek.of(dow));
        String text;
        if (!working) {
            text = i18n.t(lang, "hours.day_off_full", dayName);
        } else if (hours.hasBreak()) {
            text = i18n.t(lang, "hours.day_full_break", dayName, time.time(hours.getStartTime()),
                    time.time(hours.getEndTime()), time.time(hours.getBreakStart()), time.time(hours.getBreakEnd()));
        } else {
            text = i18n.t(lang, "hours.day_full", dayName, time.time(hours.getStartTime()), time.time(hours.getEndTime()));
        }
        var rows = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "hours.btn.change_time"), cb("ht", dow)));
        if (working) {
            rows.row(keyboards.btn(i18n.t(lang, "hours.btn.change_break"), cb("hb", dow)));
            rows.row(keyboards.btn(i18n.t(lang, "hours.btn.day_off"), cb("hoff", dow)));
            rows.row(keyboards.btn(i18n.t(lang, "hours.btn.apply_all"), cb("hall", dow)));
        } else {
            rows.row(keyboards.btn(i18n.t(lang, "hours.btn.workday"), cb("hon", dow)));
        }
        rows.row(keyboards.back(lang, cb("hw")));
        sender.editOrSend(ctx.chatId(), messageId, text, rows.build());
    }

    private void askTime(BotContext ctx, int dow, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_HOURS_TIME, Map.of(KEY_DOW, String.valueOf(dow)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "hours.ask_time"),
                keyboards.rows().row(keyboards.back(lang, cb("hd", dow))).build());
    }

    private void askBreak(BotContext ctx, Barber barber, int dow, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_HOURS_BREAK, Map.of(KEY_DOW, String.valueOf(dow)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "hours.ask_break"),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "hours.btn.no_break"), cb("hbn", dow)))
                        .row(keyboards.back(lang, cb("hd", dow))).build());
    }

    // ------------------------------------------------------------------ changes

    private void changeTime(BotContext ctx, Barber barber, int dow, TimeRange range) {
        DaySchedule current = schedule.day(barber.getId(), dow);
        LocalTime breakStart = current.breakStart();
        LocalTime breakEnd = current.breakEnd();
        // Keep the break only when it still fits into the new working hours.
        if (breakStart != null && (breakStart.isBefore(range.start()) || breakEnd.isAfter(range.end()))) {
            breakStart = null;
            breakEnd = null;
        }
        DaySchedule day = DaySchedule.work(range.start(), range.end(), breakStart, breakEnd);
        day.validate();
        String goCallback = cb("hgo", dow, BarberSupport.hm(day.start()), BarberSupport.hm(day.end()),
                BarberSupport.hm(day.breakStart()), BarberSupport.hm(day.breakEnd()));
        apply(ctx, barber, Map.of(dow, day), goCallback, dow, null);
    }

    private void changeBreak(BotContext ctx, Barber barber, int dow, LocalTime breakStart, LocalTime breakEnd, Integer messageId) {
        DaySchedule current = schedule.day(barber.getId(), dow);
        if (current.dayOff()) {
            throw new BusinessException("hours.day_is_off");
        }
        DaySchedule day = DaySchedule.work(current.start(), current.end(), breakStart, breakEnd);
        day.validate();
        String goCallback = cb("hgo", dow, BarberSupport.hm(day.start()), BarberSupport.hm(day.end()),
                BarberSupport.hm(day.breakStart()), BarberSupport.hm(day.breakEnd()));
        apply(ctx, barber, Map.of(dow, day), goCallback, dow, messageId);
    }

    private void turnOn(BotContext ctx, Barber barber, int dow, Integer messageId) {
        WorkingHours stored = schedule.week(barber.getId()).get(dow);
        DaySchedule day = stored != null && stored.getStartTime() != null && stored.getEndTime() != null
                ? DaySchedule.work(stored.getStartTime(), stored.getEndTime(), stored.getBreakStart(), stored.getBreakEnd())
                : schedule.defaultWorkday();
        applyConfirmed(ctx, barber, Map.of(dow, day), dow, messageId);
    }

    /**
     * Applies the plan unless it would leave existing bookings outside the working hours. In that case the
     * bookings are listed and the barber is asked to confirm; the bookings are kept either way.
     */
    private void apply(BotContext ctx, Barber barber, Map<Integer, DaySchedule> plan, String confirmCallback,
                       Integer returnDow, Integer messageId) {
        Lang lang = ctx.lang();
        List<Booking> conflicts = schedule.conflicts(barber.getId(), plan);
        if (conflicts.isEmpty()) {
            applyConfirmed(ctx, barber, plan, returnDow, messageId);
            return;
        }
        Shop shop = support.shop(barber);
        StringBuilder list = new StringBuilder();
        for (Booking booking : conflicts) {
            list.append("\n• ").append(time.shortDateTime(lang, booking.getStartAt(), shop.zone())).append(" — ")
                    .append(esc(booking.getClientName()));
        }
        String back = returnDow == null ? cb("hw") : cb("hd", returnDow);
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "hours.conflicts", list),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "hours.btn.continue"), confirmCallback))
                        .row(keyboards.back(lang, back)).build());
        sessions.clear(ctx.telegramId());
    }

    private void applyConfirmed(BotContext ctx, Barber barber, Map<Integer, DaySchedule> plan, Integer returnDow, Integer messageId) {
        Lang lang = ctx.lang();
        schedule.apply(ctx.user(), barber.getId(), plan);
        if (messageId != null && ctx.isCallback()) {
            sender.answer(ctx.callbackId(), i18n.t(lang, "hours.saved"), false);
            ctx.markCallbackAnswered();
        } else {
            sender.send(ctx.chatId(), i18n.t(lang, "hours.saved"));
            messageId = null;
        }
        if (returnDow == null) {
            showWeek(ctx, barber, messageId, false);
        } else {
            showDay(ctx, barber, returnDow, messageId);
        }
    }

    // ------------------------------------------------------------------ blocked time

    private void showTimeOff(BotContext ctx, Barber barber, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Shop shop = support.shop(barber);
        ZoneId zone = shop.zone();
        StringBuilder text = new StringBuilder(i18n.t(lang, "timeoff.title"));
        var rows = keyboards.rows();
        List<TimeOff> upcoming = schedule.upcomingTimeOff(barber.getId());
        if (upcoming.isEmpty()) {
            text.append("\n\n").append(i18n.t(lang, "timeoff.none"));
        } else {
            text.append("\n\n").append(i18n.t(lang, "timeoff.list_title"));
            for (TimeOff off : upcoming) {
                String label = describe(lang, off, zone);
                text.append("\n• ").append(label);
                rows.row(keyboards.btn(i18n.t(lang, "timeoff.btn.delete", label), cb("tdel", off.getId())));
            }
        }
        text.append("\n\n").append(i18n.t(lang, "timeoff.pick_date"));
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        List<InlineKeyboardButton> dates = new ArrayList<>();
        for (int i = 0; i < DATE_BUTTONS; i++) {
            LocalDate date = today.plusDays(i);
            dates.add(keyboards.btn(time.dayButton(lang, date, today), cb("tdate", BarberSupport.date(date))));
        }
        rows.grid(dates, 2);
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private String describe(Lang lang, TimeOff off, ZoneId zone) {
        var start = off.getStartAt().atZone(zone);
        var end = off.getEndAt().atZone(zone);
        boolean wholeDay = start.toLocalTime().equals(LocalTime.MIDNIGHT) && end.toLocalTime().equals(LocalTime.MIDNIGHT)
                && end.toLocalDate().equals(start.toLocalDate().plusDays(1));
        String date = time.shortDate(lang, start.toLocalDate());
        if (wholeDay) {
            return i18n.t(lang, "timeoff.whole_day", date);
        }
        return i18n.t(lang, "timeoff.range", date, time.time(start.toLocalTime()), time.time(end.toLocalTime()));
    }

    private void showTimeOffOptions(BotContext ctx, LocalDate date, Integer messageId) {
        Lang lang = ctx.lang();
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "timeoff.options", time.longDate(lang, date)),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "timeoff.btn.whole_day"), cb("tall", BarberSupport.date(date))))
                        .row(keyboards.btn(i18n.t(lang, "timeoff.btn.range"), cb("trange", BarberSupport.date(date))))
                        .row(keyboards.back(lang, cb("tw"))).build());
    }

    private void askTimeOffRange(BotContext ctx, LocalDate date, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_TIMEOFF_RANGE, Map.of(KEY_DATE, BarberSupport.date(date)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "timeoff.ask_range"),
                keyboards.rows().row(keyboards.back(lang, cb("tdate", BarberSupport.date(date)))).build());
    }

    /** Closes the time, or lists the bookings that overlap and asks what to do. */
    private void tryClose(BotContext ctx, Barber barber, LocalDate date, LocalTime from, LocalTime to, Integer messageId) {
        Lang lang = ctx.lang();
        List<Booking> inside = schedule.bookingsInside(barber, date, from, to);
        if (inside.isEmpty()) {
            schedule.closeTime(ctx.user(), barber.getId(), date, from, to, false);
            sessions.clear(ctx.telegramId());
            sender.send(ctx.chatId(), i18n.t(lang, "timeoff.closed"));
            showTimeOff(ctx, barber, null);
            return;
        }
        Shop shop = support.shop(barber);
        StringBuilder list = new StringBuilder();
        for (Booking booking : inside) {
            list.append("\n• ").append(time.shortDateTime(lang, booking.getStartAt(), shop.zone())).append(" — ")
                    .append(esc(booking.getClientName()));
        }
        sessions.clear(ctx.telegramId());
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "timeoff.conflicts", list),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "timeoff.btn.cancel_and_close"),
                                cb("tforce", BarberSupport.date(date), BarberSupport.hm(from), BarberSupport.hm(to))))
                        .row(keyboards.back(lang, cb("tw"))).build());
    }

    private void forceClose(BotContext ctx, Barber barber, LocalDate date, LocalTime from, LocalTime to, Integer messageId) {
        List<Booking> cancelled = schedule.closeTime(ctx.user(), barber.getId(), date, from, to, true);
        notifications.bookingsCancelledByBarber(cancelled);
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "timeoff.closed_cancelled", cancelled.size()));
        showTimeOff(ctx, barber, null);
    }
}
