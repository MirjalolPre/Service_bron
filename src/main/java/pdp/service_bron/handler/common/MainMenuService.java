package pdp.service_bron.handler.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.service.AccessService;
import pdp.service_bron.service.AccessService.Roles;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.ShopService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;

import java.util.ArrayList;
import java.util.List;

import static pdp.service_bron.telegram.HtmlEscaper.esc;

/**
 * Renders the persistent reply-keyboard main menu: the client menu, or the staff menu for barbers and owners.
 * Staff switch between the two with the "Mijoz rejimi" / "Ish rejimi" buttons.
 */
@Component
@RequiredArgsConstructor
public class MainMenuService {

    private final AccessService access;
    private final ShopService shops;
    private final I18nService i18n;
    private final KeyboardFactory keyboards;
    private final TelegramSender sender;

    /** Whether this user currently sees the staff menu (staff and not in client mode). */
    public boolean isStaffMode(BotContext ctx) {
        return access.roles(ctx.user()).isStaff() && !ctx.session().isClientMode();
    }

    /** Sends the main menu with the default greeting. */
    public void show(BotContext ctx) {
        Roles roles = access.roles(ctx.user());
        boolean staffMode = roles.isStaff() && !ctx.session().isClientMode();
        Lang lang = ctx.lang();
        String text = staffMode
                ? i18n.t(lang, "home.staff", esc(ctx.user().displayName())) + subscriptionBanner(roles, lang)
                : i18n.t(lang, "home.client", esc(ctx.user().displayName()));
        show(ctx, text);
    }

    /** Sends the main menu keyboard together with the given HTML text. */
    public void show(BotContext ctx, String htmlText) {
        Roles roles = access.roles(ctx.user());
        boolean staffMode = roles.isStaff() && !ctx.session().isClientMode();
        sender.send(ctx.chatId(), htmlText, keyboards.replyMenu(staffMode ? staffRows(roles, ctx.lang()) : clientRows(roles, ctx.lang())));
    }

    private List<List<String>> clientRows(Roles roles, Lang lang) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(i18n.t(lang, "menu.c.book")));
        rows.add(List.of(i18n.t(lang, "menu.c.mybookings"), i18n.t(lang, "menu.c.shop")));
        rows.add(List.of(i18n.t(lang, "menu.c.othershop"), i18n.t(lang, "menu.c.lang")));
        if (roles.isStaff()) {
            rows.add(List.of(i18n.t(lang, "menu.x.staffmode")));
        }
        return rows;
    }

    private List<List<String>> staffRows(Roles roles, Lang lang) {
        List<List<String>> rows = new ArrayList<>();
        if (roles.isBarber()) {
            rows.add(List.of(i18n.t(lang, "menu.b.today"), i18n.t(lang, "menu.b.tomorrow")));
            rows.add(List.of(i18n.t(lang, "menu.b.schedule"), i18n.t(lang, "menu.b.manual")));
            rows.add(List.of(i18n.t(lang, "menu.b.prices"), i18n.t(lang, "menu.b.hours")));
            rows.add(List.of(i18n.t(lang, "menu.b.timeoff"), i18n.t(lang, "menu.b.stats")));
            rows.add(List.of(i18n.t(lang, "menu.b.settings")));
        }
        if (roles.isOwner()) {
            rows.add(List.of(i18n.t(lang, "menu.o.shop"), i18n.t(lang, "menu.o.barbers")));
            rows.add(List.of(i18n.t(lang, "menu.o.link"), i18n.t(lang, "menu.o.today")));
        }
        rows.add(List.of(i18n.t(lang, "menu.x.clientmode")));
        return rows;
    }

    /** Banner shown to staff when their shop's subscription is switched off. */
    private String subscriptionBanner(Roles roles, Lang lang) {
        Shop shop = roles.ownedShop();
        if (shop == null && roles.barber() != null) {
            shop = shops.find(roles.barber().getShopId()).orElse(null);
        }
        if (shop != null && !shop.isActive()) {
            return "\n\n" + i18n.t(lang, "banner.subscription_off");
        }
        return "";
    }
}
