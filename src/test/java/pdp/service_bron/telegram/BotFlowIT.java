package pdp.service_bron.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.location.Location;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import pdp.service_bron.IntegrationTest;
import pdp.service_bron.TestDb;
import pdp.service_bron.domain.Booking;
import pdp.service_bron.domain.BookingStatus;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.BookingRepository;
import pdp.service_bron.repository.PriceItemRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.repository.WorkingHoursRepository;
import pdp.service_bron.service.I18nService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * End-to-end scenario through the real dispatcher, handlers, services and database. Only the Telegram network
 * client is replaced by a recorder, so the test presses the very buttons the bot rendered
 * (this also proves every callback fits into 64 bytes and every message key exists).
 */
@IntegrationTest
class BotFlowIT {

    private static final long ADMIN = 111;   // super admin in the test profile
    private static final long OWNER = 200;   // owner and barber of the shop
    private static final long CLIENT = 400;

    @MockitoBean TelegramClient telegram;

    @Autowired UpdateDispatcher dispatcher;
    @Autowired I18nService i18n;
    @Autowired JdbcClient jdbc;
    @Autowired ShopRepository shops;
    @Autowired BarberRepository barbers;
    @Autowired WorkingHoursRepository workingHours;
    @Autowired PriceItemRepository prices;
    @Autowired BookingRepository bookings;

    /** Everything the bot sent or edited, in order. */
    record Sent(long chatId, int messageId, String text, InlineKeyboardMarkup inline) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger messageIds = new AtomicInteger(1000);
    private final AtomicInteger updateIds = new AtomicInteger(1);

    @BeforeEach
    void setUp() throws Exception {
        TestDb.clean(jdbc);
        sent.clear();
        doAnswer(inv -> {
            BotApiMethod<?> method = inv.getArgument(0);
            if (method instanceof SendMessage m) {
                int id = messageIds.incrementAndGet();
                sent.add(new Sent(Long.parseLong(m.getChatId()), id, m.getText(),
                        m.getReplyMarkup() instanceof InlineKeyboardMarkup kb ? kb : null));
                return message(id);
            }
            if (method instanceof EditMessageText e) {
                sent.add(new Sent(Long.parseLong(e.getChatId()), e.getMessageId(), e.getText(),
                        e.getReplyMarkup() instanceof InlineKeyboardMarkup kb ? kb : e.getReplyMarkup()));
                return Boolean.TRUE;
            }
            return Boolean.TRUE;
        }).when(telegram).execute(any(BotApiMethod.class));
        doAnswer(inv -> {
            SendPhoto photo = inv.getArgument(0);
            int id = messageIds.incrementAndGet();
            sent.add(new Sent(Long.parseLong(photo.getChatId()), id, photo.getCaption(), photo.getReplyMarkup() instanceof InlineKeyboardMarkup kb ? kb : null));
            return message(id);
        }).when(telegram).execute(any(SendPhoto.class));
        doAnswer(inv -> {
            SendDocument doc = inv.getArgument(0);
            int id = messageIds.incrementAndGet();
            sent.add(new Sent(Long.parseLong(doc.getChatId()), id, doc.getCaption(), null));
            return message(id);
        }).when(telegram).execute(any(SendDocument.class));
    }

    private static Message message(int id) {
        Message m = new Message();
        m.setMessageId(id);
        return m;
    }

    // ------------------------------------------------------------------ driving the bot

    private void sendText(long tg, String text) {
        Message m = message(messageIds.incrementAndGet());
        m.setFrom(new User(tg, "User" + tg, false));
        m.setChat(new Chat(tg, "private"));
        m.setText(text);
        dispatch(m);
    }

    private void sendContact(long tg, String phone) {
        Message m = message(messageIds.incrementAndGet());
        m.setFrom(new User(tg, "User" + tg, false));
        m.setChat(new Chat(tg, "private"));
        Contact contact = new Contact();
        contact.setPhoneNumber(phone);
        contact.setUserId(tg);
        contact.setFirstName("User" + tg);
        m.setContact(contact);
        dispatch(m);
    }

    private void sendLocation(long tg, double latitude, double longitude) {
        Message m = message(messageIds.incrementAndGet());
        m.setFrom(new User(tg, "User" + tg, false));
        m.setChat(new Chat(tg, "private"));
        m.setLocation(Location.builder().latitude(latitude).longitude(longitude).build());
        dispatch(m);
    }

    private void dispatch(Message m) {
        Update update = new Update();
        update.setUpdateId(updateIds.incrementAndGet());
        update.setMessage(m);
        dispatcher.consume(update);
    }

    private void pressCallback(long tg, String data, int messageId) {
        CallbackQuery q = new CallbackQuery();
        q.setId("cb" + updateIds.incrementAndGet());
        q.setFrom(new User(tg, "User" + tg, false));
        q.setData(data);
        Message m = message(messageId);
        m.setChat(new Chat(tg, "private"));
        q.setMessage(m);
        Update update = new Update();
        update.setUpdateId(updateIds.incrementAndGet());
        update.setCallbackQuery(q);
        dispatcher.consume(update);
    }

    /** Presses the newest inline button whose label contains {@code label} (in any recent message of that chat). */
    private void pressLabel(long tg, String label) {
        pressWhere(tg, b -> b.getText().contains(label), "label '" + label + "'");
    }

    /** Presses the newest inline button whose callback data starts with {@code prefix}. */
    private void pressData(long tg, String prefix) {
        pressWhere(tg, b -> b.getCallbackData() != null && b.getCallbackData().startsWith(prefix), "data '" + prefix + "'");
    }

    private void pressWhere(long tg, java.util.function.Predicate<InlineKeyboardButton> match, String what) {
        List<Sent> mine = new ArrayList<>(sent.stream().filter(s -> s.chatId() == tg && s.inline() != null).toList());
        for (int i = mine.size() - 1; i >= 0; i--) {
            Sent s = mine.get(i);
            for (var row : s.inline().getKeyboard()) {
                for (InlineKeyboardButton button : row) {
                    if (button.getCallbackData() != null && match.test(button)) {
                        assertThat(button.getCallbackData().getBytes()).hasSizeLessThanOrEqualTo(64);
                        pressCallback(tg, button.getCallbackData(), s.messageId());
                        return;
                    }
                }
            }
        }
        throw new AssertionError("No button with " + what + " for user " + tg + ". Texts sent: " + textsFor(tg));
    }

    private List<String> textsFor(long tg) {
        return sent.stream().filter(s -> s.chatId() == tg).map(Sent::text).toList();
    }

    private String lastTextFor(long tg) {
        List<String> texts = textsFor(tg);
        return texts.isEmpty() ? "" : texts.get(texts.size() - 1);
    }

    private boolean anyTextContains(long tg, String needle) {
        return textsFor(tg).stream().anyMatch(t -> t != null && t.contains(needle));
    }

    private String t(String key, Object... args) {
        return i18n.t(Lang.UZ, key, args);
    }

    /** /start [payload], choose Uzbek, share the phone number. */
    private void onboard(long tg, String payload) {
        sendText(tg, payload == null ? "/start" : "/start " + payload);
        pressLabel(tg, t("btn.lang.uz"));
        sendContact(tg, "+99890" + (1000000 + tg));
    }

    // ------------------------------------------------------------------ the scenario

    @Test
    void adminCreatesShopOwnerSetsItUpAndAClientBooksAndCancels() {
        // 1. Super admin creates a shop and gets the owner link.
        onboard(ADMIN, null);
        sendText(ADMIN, "/admin");
        assertThat(lastTextFor(ADMIN)).isEqualTo(t("admin.title"));
        pressLabel(ADMIN, t("admin.btn.new"));
        sendText(ADMIN, "Barber House");
        Matcher link = Pattern.compile("start=(o_[A-Za-z0-9_-]+)").matcher(lastTextFor(ADMIN) + textsFor(ADMIN));
        assertThat(link.find()).as("owner invite link in: " + textsFor(ADMIN)).isTrue();
        String ownerPayload = link.group(1);
        Shop shop = shops.findAll().getFirst();
        assertThat(shop.getName()).isEqualTo("Barber House");

        // 2. A stranger cannot use /admin.
        onboard(CLIENT + 1, null);
        sendText(CLIENT + 1, "/admin");
        assertThat(anyTextContains(CLIENT + 1, t("admin.title"))).isFalse();

        // 3. The owner opens the link and goes through the shop wizard.
        onboard(OWNER, ownerPayload);
        pressLabel(OWNER, t("owner.btn.keep_name"));
        sendText(OWNER, "Chilonzor 9-kvartal");
        pressLabel(OWNER, t("btn.skip"));                // landmark
        sendText(OWNER, t("btn.skip"));                  // location (reply keyboard "skip")
        pressLabel(OWNER, t("owner.btn.my_phone"));      // phone
        pressLabel(OWNER, t("btn.skip"));                // photo
        pressLabel(OWNER, t("btn.yes"));                 // "I am a barber too"
        Shop configured = shops.findById(shop.getId()).orElseThrow();
        assertThat(configured.getOwnerUserId()).isNotNull();
        assertThat(configured.getAddress()).isEqualTo("Chilonzor 9-kvartal");
        assertThat(configured.getPhone()).isNotNull();

        // 4. Barber setup wizard: name, slot length, hours, break, one price.
        pressLabel(OWNER, t("owner.btn.keep_name"));
        pressLabel(OWNER, t("unit.minutes", 30));
        pressLabel(OWNER, t("hours.btn.preset1"));
        pressData(OWNER, "b:wbrk:1300");
        sendText(OWNER, "Soch olish");
        sendText(OWNER, "60k");
        pressLabel(OWNER, t("barber.btn.finish"));
        var barber = barbers.findAll().getFirst();
        assertThat(barber.isAcceptingBookings()).isTrue();
        assertThat(barber.getSlotMinutes()).isEqualTo(30);
        assertThat(workingHours.findByBarberIdOrderByDayOfWeekAsc(barber.getId())).hasSize(7);
        assertThat(prices.findByBarberIdAndActiveTrueOrderBySortOrderAscIdAsc(barber.getId()))
                .singleElement().satisfies(p -> assertThat(p.getPrice()).isEqualTo(60_000));

        // 5. The owner gets the client link and QR.
        sendText(OWNER, t("menu.o.link"));
        assertThat(anyTextContains(OWNER, "s_" + shop.getSlug())).isTrue();

        // 6. A client opens the shop link: single barber, so the card with prices comes first.
        onboard(CLIENT, "s_" + shop.getSlug());
        String card = lastTextFor(CLIENT);
        assertThat(card).contains("Soch olish").contains("60 000 so'm").contains(t("client.payment_note"));

        // 7. The client walks through dates, times and confirmation.
        pressLabel(CLIENT, t("client.btn.see_slots"));
        pressData(CLIENT, "c:day:");
        pressData(CLIENT, "c:slot:");
        assertThat(lastTextFor(CLIENT)).contains("Barber House").contains("Chilonzor 9-kvartal");
        pressLabel(CLIENT, t("client.btn.confirm"));

        List<Booking> booked = bookings.findAll();
        assertThat(booked).hasSize(1);
        Booking booking = booked.getFirst();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.BOOKED);
        assertThat(booking.getClientPhone()).isNotNull();
        assertThat(anyTextContains(CLIENT, "Siz navbatga yozildingiz!")).isTrue();

        // 8. The barber was notified with a cancel button.
        assertThat(anyTextContains(OWNER, "🆕")).isTrue();
        assertThat(sent.stream().anyMatch(s -> s.chatId() == OWNER && s.inline() != null
                && s.inline().getKeyboard().stream().flatMap(List::stream)
                .anyMatch(b -> ("b:cxl:" + booking.getId()).equals(b.getCallbackData())))).isTrue();

        // 9. My bookings lists it; cancelling notifies the barber and frees the slot.
        sendText(CLIENT, t("menu.c.mybookings"));
        assertThat(lastTextFor(CLIENT)).contains("Barber House");
        pressData(CLIENT, "c:mycxl:");
        pressData(CLIENT, "c:mycxlok:");
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_CLIENT);
        assertThat(anyTextContains(OWNER, "❌")).isTrue();

        // 10. The barber sees a manual-booking flow and the day view without errors.
        sendText(OWNER, t("menu.b.today"));
        assertThat(lastTextFor(OWNER)).contains("📅");
        sendText(OWNER, t("menu.b.prices"));
        assertThat(lastTextFor(OWNER)).contains("Soch olish");
        sendText(OWNER, t("menu.b.hours"));
        assertThat(lastTextFor(OWNER)).isEqualTo(t("hours.title"));
        sendText(OWNER, t("menu.b.stats"));
        assertThat(lastTextFor(OWNER)).contains(t("stats.title_week"));

        // 11. Switching the shop off (subscription) stops client bookings.
        pressLabel(ADMIN, t("admin.btn.shops"));
        sendText(ADMIN, "/admin");
        pressLabel(ADMIN, t("admin.btn.shops"));
        pressData(ADMIN, "a:shop:");
        pressLabel(ADMIN, t("admin.btn.turn_off"));
        assertThat(shops.findById(shop.getId()).orElseThrow().isActive()).isFalse();
        sendText(CLIENT, t("menu.c.book"));
        assertThat(lastTextFor(CLIENT)).contains(t("client.shop_inactive"));
    }

    @Test
    void anUnknownDeepLinkOrRandomTextNeverCrashes() {
        onboard(CLIENT, null);
        sendText(CLIENT, "/start s_no-such-shop");
        sendText(CLIENT, "/start o_invalid-token");
        sendText(CLIENT, "/start b_invalid-token");
        sendText(CLIENT, "hello there");
        sendText(CLIENT, "/unknown");

        assertThat(sent.stream().filter(s -> s.chatId() == CLIENT).map(Sent::text))
                .noneMatch(text -> text != null && text.equals(t("error.generic")));
    }

    @Test
    void russianUsersGetRussianMessages() {
        sendText(CLIENT, "/start");
        pressLabel(CLIENT, i18n.t(Lang.RU, "btn.lang.ru"));
        assertThat(lastTextFor(CLIENT)).isEqualTo(i18n.t(Lang.RU, "phone.ask"));
    }

    @Test
    void ownerSetsTheShopLocationFromAMapPinALinkOrCoordinates() {
        onboard(ADMIN, null);
        sendText(ADMIN, "/admin");
        pressLabel(ADMIN, t("admin.btn.new"));
        sendText(ADMIN, "Barber House");
        Matcher link = Pattern.compile("start=(o_[A-Za-z0-9_-]+)").matcher(lastTextFor(ADMIN) + textsFor(ADMIN));
        assertThat(link.find()).isTrue();
        onboard(OWNER, link.group(1));

        // Setup wizard: the location step accepts a pin picked on the map.
        pressLabel(OWNER, t("owner.btn.keep_name"));
        sendText(OWNER, "Chilonzor");
        pressLabel(OWNER, t("btn.skip"));
        assertThat(lastTextFor(OWNER)).isEqualTo(t("owner.wiz.location"));
        sendLocation(OWNER, 41.2856, 69.2036);
        Shop shop = shops.findAll().getFirst();
        assertThat(shop.getLatitude()).isEqualTo(41.2856);
        assertThat(shop.getLongitude()).isEqualTo(69.2036);
        pressLabel(OWNER, t("owner.btn.my_phone"));
        pressLabel(OWNER, t("btn.skip"));
        pressLabel(OWNER, t("btn.no"));

        // Later: change it with a Yandex link (longitude first in the link, still saved correctly).
        sendText(OWNER, t("menu.o.shop"));
        pressData(OWNER, "o:edloc");
        assertThat(anyTextContains(OWNER, t("owner.location.current"))).isTrue();
        sendText(OWNER, "https://yandex.uz/maps/?ll=69.30%2C41.31&z=17");
        shop = shops.findById(shop.getId()).orElseThrow();
        assertThat(shop.getLatitude()).isEqualTo(41.31);
        assertThat(shop.getLongitude()).isEqualTo(69.30);

        // Typed coordinates work, nonsense is refused and keeps waiting.
        pressData(OWNER, "o:edloc");
        sendText(OWNER, "not a place");
        assertThat(lastTextFor(OWNER)).isEqualTo(t("owner.location.bad"));
        sendText(OWNER, "41.2, 69.1");
        assertThat(shops.findById(shop.getId()).orElseThrow().getLatitude()).isEqualTo(41.2);

        // The location can be removed again.
        pressData(OWNER, "o:edloc");
        sendText(OWNER, t("btn.remove_location"));
        assertThat(shops.findById(shop.getId()).orElseThrow().hasLocation()).isFalse();
        assertThat(sent.stream().map(Sent::text)).noneMatch(text -> t("error.generic").equals(text));
    }

    @Test
    void noUserEverSeesAGenericErrorDuringTheMainScenario() {
        adminCreatesShopOwnerSetsItUpAndAClientBooksAndCancels();

        assertThat(sent.stream().map(Sent::text))
                .as("the dispatcher sends error.generic when a handler throws")
                .noneMatch(text -> text != null && (text.equals(t("error.generic")) || text.equals(i18n.t(Lang.RU, "error.generic"))));
    }
}
