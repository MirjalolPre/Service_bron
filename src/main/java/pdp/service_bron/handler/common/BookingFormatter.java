package pdp.service_bron.handler.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingSource;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.UserService;
import pdp.service_bron.telegram.HtmlEscaper;
import pdp.service_bron.util.PhoneFormatter;

/** Renders how a booking's client is shown to staff. All user text is HTML-escaped. */
@Component
@RequiredArgsConstructor
public class BookingFormatter {

    private final UserService users;
    private final I18nService i18n;

    /**
     * A clickable mention with the phone number for bot bookings (so the barber can message the client);
     * the typed name with a "(manual)" tag for walk-in and phone bookings.
     */
    public String clientLabel(Lang lang, Booking booking) {
        String phone = booking.getClientPhone() == null ? "" : ", " + PhoneFormatter.pretty(booking.getClientPhone());
        if (booking.getSource() == BookingSource.BOT && booking.getClientUserId() != null) {
            AppUser client = users.findById(booking.getClientUserId()).orElse(null);
            if (client != null) {
                return HtmlEscaper.mention(client.getTelegramId(), booking.getClientName()) + HtmlEscaper.esc(phone);
            }
        }
        return HtmlEscaper.esc(booking.getClientName()) + " " + i18n.t(lang, "barber.manual_tag") + HtmlEscaper.esc(phone);
    }
}
