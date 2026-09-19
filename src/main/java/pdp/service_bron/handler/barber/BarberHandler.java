package pdp.service_bron.handler.barber;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.handler.UpdateHandler;
import pdp.service_bron.handler.common.MainMenuService;
import pdp.service_bron.service.AccessService;
import pdp.service_bron.service.BarberService;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.InviteService;
import pdp.service_bron.service.InviteService.BarberJoined;
import pdp.service_bron.service.NotificationService;
import pdp.service_bron.service.PriceService;
import pdp.service_bron.service.ScheduleService;
import pdp.service_bron.service.ScheduleService.DaySchedule;
import pdp.service_bron.service.StatsService;
import pdp.service_bron.service.StatsService.BarberStats;
import pdp.service_bron.service.StatsService.Period;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.TimeFormatter;
import pdp.service_bron.util.TimeParser;
import pdp.service_bron.util.TimeParser.TimeRange;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static pdp.service_bron.handler.barber.BarberSupport.cb;
import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * Barber: joining through an invite, the setup wizard, statistics and settings. The daily tools live in
 * {@link BarberDayView}, {@link BarberPricesView} and {@link BarberHoursView}.
 *
 * <p>Every action works on the caller's own barber row; ids in callback data are re-checked by the services.
 */
@Component
@RequiredArgsConstructor
public class BarberHandler implements UpdateHandler {

    private static final String WIZ = "wiz";

    private final AccessService access;
    private final BarberService barbers;
    private final InviteService invites;
    private final ScheduleService schedule;
    private final PriceService prices;
    private final StatsService stats;
    private final NotificationService notifications;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TimeFormatter time;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final MainMenuService mainMenu;
    private final BarberSupport support;
    private final BarberDayView days;
    private final BarberPricesView priceView;
    private final BarberHoursView hours;

    @Override
    public String domain() {
        return "b";
    }

    // ------------------------------------------------------------------ deep link b_<token>

    /** The user opened {@code /start b_<token>}: joins the shop as a barber and starts the setup wizard. */
    public void redeemInvite(BotContext ctx, String token) {
        BarberJoined joined = invites.redeemBarber(token, ctx.user());
        notifications.barberJoined(joined.owner(), joined.barber());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "barber.welcome", esc(joined.shop().getName())));
        startWizard(ctx, joined.barber());
    }

    // ------------------------------------------------------------------ reply keyboard

    @Override
    public void onMenu(BotContext ctx, String menuKey) {
        Barber barber = requireBarber(ctx);
        switch (menuKey) {
            case "menu.b.today", "menu.b.tomorrow", "menu.b.schedule", "menu.b.manual" -> days.onMenu(ctx, barber, menuKey);
            case "menu.b.prices" -> priceView.onMenu(ctx, barber);
            case "menu.b.hours", "menu.b.timeoff" -> hours.onMenu(ctx, barber, menuKey);
            case "menu.b.stats" -> showStats(ctx, barber, Period.WEEK, null);
            case "menu.b.settings" -> showSettings(ctx, barber, null);
            default -> mainMenu.show(ctx);
        }
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public void onCallback(BotContext ctx, CallbackData data) {
        Barber barber = requireBarber(ctx);
        if (days.onCallback(ctx, barber, data) || priceView.onCallback(ctx, barber, data)
                || hours.onCallback(ctx, barber, data)) {
            return;
        }
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            // --- wizard
            case "wname" -> askSlotLength(ctx);
            case "wslot" -> {
                barbers.setSlotMinutes(ctx.user(), barber.getId(), data.intArg(0));
                askHours(ctx);
            }
            case "wpre" -> wizardPreset(ctx, barber, data.intArg(0));
            case "wdone" -> wizardHoursDone(ctx, barber);
            case "wbrk" -> wizardBreak(ctx, barber, BarberSupport.parseHm(data.arg(0)), BarberSupport.parseHm(data.arg(1)));
            case "wfinish" -> finishWizard(ctx, barber, messageId);
            // --- statistics
            case "st" -> showStats(ctx, barber, "m".equals(data.arg(0)) ? Period.MONTH : Period.WEEK, messageId);
            // --- settings
            case "set" -> showSettings(ctx, barber, messageId);
            case "sname" -> askText(ctx, BotState.B_NAME, "barber.settings.ask_name", messageId);
            case "sbio" -> askText(ctx, BotState.B_BIO, "barber.settings.ask_bio", messageId);
            case "sphoto" -> askText(ctx, BotState.B_PHOTO, "barber.settings.ask_photo", messageId);
            case "sslot" -> showSlotPicker(ctx, messageId);
            case "sslotv" -> {
                barbers.setSlotMinutes(ctx.user(), barber.getId(), data.intArg(0));
                showSettings(ctx, requireBarber(ctx), messageId);
            }
            case "sacc" -> {
                barbers.setAccepting(ctx.user(), barber.getId(), !barber.isAcceptingBookings());
                showSettings(ctx, requireBarber(ctx), messageId);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ free-form input

    @Override
    public void onInput(BotContext ctx) {
        Barber barber = requireBarber(ctx);
        if (days.onInput(ctx, barber) || priceView.onInput(ctx, barber) || hours.onInput(ctx, barber)) {
            return;
        }
        try {
            switch (ctx.state()) {
                case B_NAME -> onName(ctx, barber);
                case B_BIO -> onBio(ctx, barber);
                case B_PHOTO -> onPhoto(ctx, barber);
                case B_WIZ_BREAK -> onWizardBreakText(ctx, barber);
                default -> mainMenu.show(ctx);
            }
        } catch (BusinessException e) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), e.key(), e.args())); // stay in the state and let the user retype
        }
    }

    private void onName(BotContext ctx, Barber barber) {
        String text = ctx.text();
        if (text == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_text"));
            return;
        }
        barbers.rename(ctx.user(), barber.getId(), text);
        if ("1".equals(ctx.session().get(WIZ))) {
            askSlotLength(ctx);
        } else {
            sessions.clear(ctx.telegramId());
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.saved"));
            showSettings(ctx, requireBarber(ctx), null);
        }
    }

    private void onBio(BotContext ctx, Barber barber) {
        String text = ctx.text();
        if (text == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_text"));
            return;
        }
        barbers.setBio(ctx.user(), barber.getId(), "-".equals(text) ? "" : text);
        sessions.clear(ctx.telegramId());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.saved"));
        showSettings(ctx, requireBarber(ctx), null);
    }

    private void onPhoto(BotContext ctx, Barber barber) {
        if (!ctx.message().hasPhoto()) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.send_photo"));
            return;
        }
        List<PhotoSize> sizes = ctx.message().getPhoto();
        String fileId = sizes.stream().max(Comparator.comparing(PhotoSize::getFileSize,
                Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow().getFileId();
        barbers.setPhoto(ctx.user(), barber.getId(), fileId);
        sessions.clear(ctx.telegramId());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "owner.saved"));
        showSettings(ctx, requireBarber(ctx), null);
    }

    // ------------------------------------------------------------------ setup wizard

    /** Wizard step 1: display name. Steps: name, slot length, working hours, break, prices. */
    public void startWizard(BotContext ctx, Barber barber) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_NAME, Map.of(WIZ, "1"));
        sender.send(ctx.chatId(), i18n.t(lang, "barber.wiz.name", esc(barber.getDisplayName())),
                keyboards.rows().row(keyboards.btn(i18n.t(lang, "owner.btn.keep_name"), cb("wname"))).build());
    }

    /** Step 2: slot length. */
    private void askSlotLength(BotContext ctx) {
        Lang lang = ctx.lang();
        sessions.clear(ctx.telegramId());
        var options = BarberService.SLOT_OPTIONS.stream()
                .map(minutes -> keyboards.btn(i18n.t(lang, "unit.minutes", minutes), cb("wslot", minutes)))
                .toList();
        sender.send(ctx.chatId(), i18n.t(lang, "barber.wiz.slot"), keyboards.rows().grid(options, 3).build());
    }

    /** Step 3: working hours presets. */
    private void askHours(BotContext ctx) {
        Lang lang = ctx.lang();
        sender.send(ctx.chatId(), i18n.t(lang, "barber.wiz.hours"), keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "hours.btn.preset1"), cb("wpre", ScheduleService.PRESET_MON_SAT)))
                .row(keyboards.btn(i18n.t(lang, "hours.btn.preset2"), cb("wpre", ScheduleService.PRESET_EVERY_DAY)))
                .row(keyboards.btn(i18n.t(lang, "barber.btn.manual_setup"), cb("wpre", 0))).build());
    }

    private void wizardPreset(BotContext ctx, Barber barber, int preset) {
        if (preset == 0) {
            // manual: the barber edits the days one by one and continues with "Davom etish"
            hours.showWeek(ctx, barber, null, true);
            return;
        }
        schedule.apply(ctx.user(), barber.getId(), schedule.preset(preset));
        askBreak(ctx);
    }

    private void wizardHoursDone(BotContext ctx, Barber barber) {
        if (!schedule.hasWorkingDay(barber.getId())) {
            sender.answer(ctx.callbackId(), i18n.t(ctx.lang(), "barber.wiz.hours_required"), true);
            ctx.markCallbackAnswered();
            return;
        }
        askPrices(ctx);
    }

    /** Step 4: one break for all working days. */
    private void askBreak(BotContext ctx) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_WIZ_BREAK, Map.of(WIZ, "1"));
        sender.send(ctx.chatId(), i18n.t(lang, "barber.wiz.break"), keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "barber.btn.break_1300"), cb("wbrk", "1300", "1400")))
                .row(keyboards.btn(i18n.t(lang, "hours.btn.no_break"), cb("wbrk", "-", "-"))).build());
    }

    private void onWizardBreakText(BotContext ctx, Barber barber) {
        TimeRange range = ctx.text() == null ? null : TimeParser.parseRange(ctx.text()).orElse(null);
        if (range == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "hours.bad_format"));
            return;
        }
        wizardBreak(ctx, barber, range.start(), range.end());
    }

    private void wizardBreak(BotContext ctx, Barber barber, java.time.LocalTime breakStart, java.time.LocalTime breakEnd) {
        if (breakStart != null) {
            Map<Integer, DaySchedule> plan = schedule.withBreakOnWorkdays(barber.getId(), breakStart, breakEnd);
            schedule.apply(ctx.user(), barber.getId(), plan); // validates that the break fits into every day
        }
        askPrices(ctx);
    }

    /** Step 5: at least one price item. */
    private void askPrices(BotContext ctx) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), BotState.B_PRICE_NAME, Map.of(WIZ, "1"));
        sender.send(ctx.chatId(), i18n.t(lang, "barber.wiz.price"));
    }

    private void finishWizard(BotContext ctx, Barber barber, Integer messageId) {
        Lang lang = ctx.lang();
        if (prices.list(barber.getId()).isEmpty()) {
            sender.answer(ctx.callbackId(), i18n.t(lang, "barber.wiz.price_required"), true);
            ctx.markCallbackAnswered();
            return;
        }
        sessions.clear(ctx.telegramId());
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.wiz.finished"), null);
        mainMenu.show(new BotContext(ctx.update(), ctx.user(), sessions.load(ctx.telegramId())),
                i18n.t(lang, "barber.wiz.finished_menu"));
    }

    // ------------------------------------------------------------------ statistics

    private void showStats(BotContext ctx, Barber barber, Period period, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        Shop shop = support.shop(barber);
        BarberStats s = stats.barberStats(barber, shop, period);
        StringBuilder text = new StringBuilder(i18n.t(lang,
                period == Period.WEEK ? "stats.title_week" : "stats.title_month"));
        text.append("\n\n").append(i18n.t(lang, "stats.body", s.total(), s.completed(), s.noShow(),
                s.cancelledByClient(), s.cancelledByBarber(), s.booked()));
        if (s.busiestDay() != null) {
            text.append("\n\n").append(i18n.t(lang, "stats.busiest", time.weekdayLong(lang, s.busiestDay()), s.busiestDayCount()));
        }
        var keyboard = keyboards.rows().row(
                keyboards.btn((period == Period.WEEK ? i18n.t(lang, "icon.on") + " " : "") + i18n.t(lang, "stats.btn.week"), cb("st", "w")),
                keyboards.btn((period == Period.MONTH ? i18n.t(lang, "icon.on") + " " : "") + i18n.t(lang, "stats.btn.month"), cb("st", "m"))).build();
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), keyboard);
    }

    // ------------------------------------------------------------------ settings

    private void showSettings(BotContext ctx, Barber barber, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        String bio = barber.getBio() == null ? "—" : esc(barber.getBio());
        String text = i18n.t(lang, "barber.settings.title", esc(barber.getDisplayName()), bio,
                i18n.t(lang, barber.getPhotoFileId() != null ? "icon.on" : "icon.off"),
                i18n.t(lang, "unit.minutes", barber.getSlotMinutes()),
                i18n.t(lang, barber.isAcceptingBookings() ? "icon.on" : "icon.off"));
        var keyboard = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "barber.settings.btn.name"), cb("sname")),
                        keyboards.btn(i18n.t(lang, "barber.settings.btn.bio"), cb("sbio")))
                .row(keyboards.btn(i18n.t(lang, "barber.settings.btn.photo"), cb("sphoto")),
                        keyboards.btn(i18n.t(lang, "barber.settings.btn.slot", i18n.t(lang, "unit.minutes", barber.getSlotMinutes())), cb("sslot")))
                .row(keyboards.btn(i18n.t(lang, "barber.settings.btn.accepting",
                        i18n.t(lang, barber.isAcceptingBookings() ? "icon.on" : "icon.off")), cb("sacc")))
                .build();
        sender.editOrSend(ctx.chatId(), messageId, text, keyboard);
    }

    private void askText(BotContext ctx, BotState state, String promptKey, Integer messageId) {
        Lang lang = ctx.lang();
        sessions.set(ctx.telegramId(), state);
        String hint = state == BotState.B_BIO ? "\n\n" + i18n.t(lang, "owner.clear_hint") : "";
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, promptKey) + hint,
                keyboards.rows().row(keyboards.back(lang, cb("set"))).build());
    }

    private void showSlotPicker(BotContext ctx, Integer messageId) {
        Lang lang = ctx.lang();
        var options = BarberService.SLOT_OPTIONS.stream()
                .map(minutes -> keyboards.btn(i18n.t(lang, "unit.minutes", minutes), cb("sslotv", minutes))).toList();
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "barber.settings.slot_note"),
                keyboards.rows().grid(options, 3).row(keyboards.back(lang, cb("set"))).build());
    }

    private Barber requireBarber(BotContext ctx) {
        return access.barberOf(ctx.user()).orElseThrow(() -> new BusinessException("error.forbidden"));
    }
}
