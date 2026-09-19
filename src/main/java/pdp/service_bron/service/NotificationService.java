package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.HtmlEscaper;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.PhoneFormatter;
import pdp.service_bron.util.TimeFormatter;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * Sends messages to users who are not the ones currently talking to the bot. Blocked bots (403) are
 * handled inside {@link TelegramSender}: they are logged and never break the caller.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final TelegramSender sender;
    private final I18nService i18n;
    private final TimeFormatter time;
    private final UserService users;
    private final BarberRepository barbers;
    private final ShopRepository shops;
    private final BookingRepository bookings;
    private final KeyboardFactory keyboards;
    private final Clock clock;

    /** Clients with at least this many past no-shows in a shop are flagged to the barber. */
    public static final long NO_SHOW_WARNING_THRESHOLD = 2;

    /** Plain translated notice. Arguments must already be HTML-escaped. */
    public void notice(AppUser to, String key, Object... args) {
        if (to == null) {
            return;
        }
        sender.send(to.getTelegramId(), i18n.t(to.lang(), key, args));
    }

    /** "Aziz joined as a barber" for the shop owner. */
    public void barberJoined(AppUser owner, Barber barber) {
        notice(owner, "notify.barber_joined", esc(barber.getDisplayName()));
    }

    /** Tells the client that the barber cancelled their booking. Manual bookings have no Telegram client. */
    public void bookingCancelledByBarber(Booking booking) {
        AppUser client = users.findById(booking.getClientUserId()).orElse(null);
        Barber barber = barbers.findById(booking.getBarberId()).orElse(null);
        Shop shop = shops.findById(booking.getShopId()).orElse(null);
        if (client == null || barber == null || shop == null) {
            return;
        }
        Lang lang = client.lang();
        String text = i18n.t(lang, "notify.client.cancelled_by_barber", esc(barber.getDisplayName()),
                esc(shop.getName()), time.shortDateTime(lang, booking.getStartAt(), shop.zone()));
        if (booking.getCancelReason() != null) {
            text += "\n" + i18n.t(lang, "notify.reason", esc(booking.getCancelReason()));
        }
        sender.send(client.getTelegramId(), text);
    }

    public void bookingsCancelledByBarber(List<Booking> cancelled) {
        cancelled.forEach(this::bookingCancelledByBarber);
    }

    /** New booking: tells the barber who booked, with a cancel button. Flags repeat no-shows. */
    public void newBooking(Booking booking) {
        Barber barber = barbers.findById(booking.getBarberId()).orElse(null);
        Shop shop = shops.findById(booking.getShopId()).orElse(null);
        AppUser barberUser = barber == null ? null : users.findById(barber.getUserId()).orElse(null);
        if (barberUser == null || shop == null) {
            return;
        }
        Lang lang = barberUser.lang();
        StringBuilder text = new StringBuilder(i18n.t(lang, "notify.barber.new_booking", clientLabel(booking),
                time.longDateTime(lang, booking.getStartAt(), shop.zone())));
        long noShows = bookings.countByClientUserIdAndShopIdAndStatus(booking.getClientUserId(), shop.getId(),
                BookingStatus.NO_SHOW);
        if (noShows >= NO_SHOW_WARNING_THRESHOLD) {
            text.append("\n\n").append(i18n.t(lang, "notify.barber.no_show_warning", noShows));
        }
        sender.send(barberUser.getTelegramId(), text.toString(), keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "barber.btn.cancel_booking"), CallbackData.encode("b", "cxl", booking.getId())))
                .build());
    }

    /** The client cancelled: "Ali cancelled the booking on 18-sentabr 15:30". */
    public void clientCancelled(Booking booking) {
        Barber barber = barbers.findById(booking.getBarberId()).orElse(null);
        Shop shop = shops.findById(booking.getShopId()).orElse(null);
        AppUser barberUser = barber == null ? null : users.findById(barber.getUserId()).orElse(null);
        if (barberUser == null || shop == null) {
            return;
        }
        Lang lang = barberUser.lang();
        sender.send(barberUser.getTelegramId(), i18n.t(lang, "notify.barber.client_cancelled",
                esc(booking.getClientName()), time.shortDateTime(lang, booking.getStartAt(), shop.zone())));
    }

    /** The client pressed "Boraman" after the reminder. */
    public void clientConfirmed(Booking booking) {
        Barber barber = barbers.findById(booking.getBarberId()).orElse(null);
        Shop shop = shops.findById(booking.getShopId()).orElse(null);
        AppUser barberUser = barber == null ? null : users.findById(barber.getUserId()).orElse(null);
        if (barberUser == null || shop == null) {
            return;
        }
        Lang lang = barberUser.lang();
        sender.send(barberUser.getTelegramId(), i18n.t(lang, "notify.barber.client_confirmed",
                esc(booking.getClientName()), time.time(booking.getStartAt(), shop.zone())));
    }

    /** Clickable client name with the phone number. */
    private String clientLabel(Booking booking) {
        String phone = booking.getClientPhone() == null ? "" : " (" + PhoneFormatter.pretty(booking.getClientPhone()) + ")";
        AppUser client = users.findById(booking.getClientUserId()).orElse(null);
        String name = client == null ? esc(booking.getClientName())
                : HtmlEscaper.mention(client.getTelegramId(), booking.getClientName());
        return name + esc(phone);
    }

    // ------------------------------------------------------------------ reminders and jobs

    /** Reminder to the client: "Eslatma: bugun soat 15:30 da Aziz sizni kutadi (Barber House)." */
    public void reminder(Booking booking) {
        AppUser client = users.findById(booking.getClientUserId()).orElse(null);
        Barber barber = barbers.findById(booking.getBarberId()).orElse(null);
        Shop shop = shops.findById(booking.getShopId()).orElse(null);
        if (client == null || barber == null || shop == null) {
            return;
        }
        Lang lang = client.lang();
        LocalDate today = clock.instant().atZone(shop.zone()).toLocalDate();
        LocalDate date = booking.getStartAt().atZone(shop.zone()).toLocalDate();
        String text = i18n.t(lang, "notify.client.reminder", time.dayWord(lang, date, today),
                time.time(booking.getStartAt(), shop.zone()), esc(barber.getDisplayName()), esc(shop.getName()));
        sender.send(client.getTelegramId(), text, keyboards.rows().row(
                keyboards.btn(i18n.t(lang, "notify.client.btn_coming"), CallbackData.encode("c", "confirm", booking.getId())),
                keyboards.btn(i18n.t(lang, "client.btn.cancel"), CallbackData.encode("c", "mycxl", booking.getId()))).build());
    }

    /** "Ali (15:30) keldimi?" with came / no-show buttons for the barber. */
    public void attendanceQuestion(Booking booking) {
        Barber barber = barbers.findById(booking.getBarberId()).orElse(null);
        Shop shop = shops.findById(booking.getShopId()).orElse(null);
        AppUser barberUser = barber == null ? null : users.findById(barber.getUserId()).orElse(null);
        if (barberUser == null || shop == null) {
            return;
        }
        Lang lang = barberUser.lang();
        sender.send(barberUser.getTelegramId(), i18n.t(lang, "notify.barber.attendance_question",
                        esc(booking.getClientName()), time.time(booking.getStartAt(), shop.zone())),
                keyboards.rows().row(
                        keyboards.btn(i18n.t(lang, "barber.btn.came"), CallbackData.encode("b", "came", booking.getId())),
                        keyboards.btn(i18n.t(lang, "barber.btn.no_show"), CallbackData.encode("b", "no", booking.getId()))).build());
    }

    /** Morning summary for a barber: how many bookings today and the timeline. */
    public void dailySummary(Barber barber, Shop shop, List<Booking> todays) {
        AppUser barberUser = users.findById(barber.getUserId()).orElse(null);
        if (barberUser == null || todays.isEmpty()) {
            return;
        }
        Lang lang = barberUser.lang();
        StringBuilder lines = new StringBuilder();
        for (Booking booking : todays) {
            lines.append("\n").append(time.time(booking.getStartAt(), shop.zone())).append(" — ").append(clientLabel(booking));
        }
        sender.send(barberUser.getTelegramId(), i18n.t(lang, "notify.barber.daily_summary", todays.size(), lines));
    }
}
