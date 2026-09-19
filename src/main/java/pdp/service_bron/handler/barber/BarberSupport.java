package pdp.service_bron.handler.barber;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.handler.common.BookingFormatter;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.ShopService;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.util.CompactTime;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Small helpers shared by the barber screens: compact callback encoding of dates/times and client labels. */
@Component
@RequiredArgsConstructor
class BarberSupport {

    private final ShopService shops;
    private final BookingFormatter formatter;

    static String cb(String action, Object... args) {
        return CallbackData.encode("b", action, args);
    }

    static String date(LocalDate date) {
        return CompactTime.date(date);
    }

    static LocalDate parseDate(String value) {
        return CompactTime.parseDate(value);
    }

    static String dateTime(LocalDateTime value) {
        return CompactTime.dateTime(value);
    }

    static LocalDateTime parseDateTime(String value) {
        return CompactTime.parseDateTime(value);
    }

    static String hm(LocalTime time) {
        return CompactTime.hm(time);
    }

    static LocalTime parseHm(String value) {
        return CompactTime.parseHm(value);
    }

    Shop shop(Barber barber) {
        return shops.find(barber.getShopId()).orElseThrow(() -> new BusinessException("error.not_found"));
    }

    /** How a booking's client is shown to the barber (see {@link BookingFormatter}). */
    String clientLabel(Lang lang, Booking booking) {
        return formatter.clientLabel(lang, booking);
    }
}
