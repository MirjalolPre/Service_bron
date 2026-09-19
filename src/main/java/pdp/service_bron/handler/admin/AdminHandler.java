package pdp.service_bron.handler.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import pdp.service_bron.config.BotInfo;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Invite;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.handler.UpdateHandler;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.InviteService;
import pdp.service_bron.service.ShopService;
import pdp.service_bron.service.StatsService;
import pdp.service_bron.service.StatsService.GlobalStats;
import pdp.service_bron.service.UserService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;

import java.util.ArrayList;
import java.util.List;

import static pdp.service_bron.telegram.HtmlEscaper.esc;

/** Super admin panel ({@code /admin}): create shops, turn them on/off, owner invites, global stats. */
@Component
@RequiredArgsConstructor
public class AdminHandler implements UpdateHandler {

    private final UserService users;
    private final ShopService shops;
    private final InviteService invites;
    private final StatsService stats;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;
    private final BotInfo botInfo;

    @Override
    public String domain() {
        return "a";
    }

    private static String cb(String action, Object... args) {
        return CallbackData.encode("a", action, args);
    }

    private boolean isAdmin(BotContext ctx) {
        return users.isSuperAdmin(ctx.user());
    }

    /** Entry point of {@code /admin}. Returns false (and shows nothing) for everybody who is not a super admin. */
    public boolean open(BotContext ctx) {
        if (!isAdmin(ctx)) {
            return false;
        }
        sessions.clear(ctx.telegramId());
        showMenu(ctx, null);
        return true;
    }

    @Override
    public void onCallback(BotContext ctx, CallbackData data) {
        if (!isAdmin(ctx)) {
            return; // every callback re-checks the role
        }
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            case "menu" -> {
                sessions.clear(ctx.telegramId());
                showMenu(ctx, messageId);
            }
            case "new" -> {
                sessions.set(ctx.telegramId(), BotState.A_SHOP_NAME);
                Lang lang = ctx.lang();
                sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "admin.ask_shop_name"),
                        keyboards.rows().row(keyboards.back(lang, cb("menu"))).build());
            }
            case "list" -> showShops(ctx, messageId, data.intArg(0));
            case "shop" -> showShop(ctx, messageId, data.longArg(0), data.intArg(1));
            case "tog" -> toggle(ctx, messageId, data.longArg(0), data.intArg(1));
            case "inv" -> sendOwnerInvite(ctx, data.longArg(0));
            case "link" -> sender.send(ctx.chatId(), i18n.t(ctx.lang(), "admin.client_link",
                    botInfo.link("s_" + requireShop(data.longArg(0)).getSlug())));
            case "stats" -> showStats(ctx, messageId);
            default -> {
            }
        }
    }

    @Override
    public void onInput(BotContext ctx) {
        if (!isAdmin(ctx) || ctx.state() != BotState.A_SHOP_NAME) {
            return;
        }
        String text = ctx.text();
        if (text == null) {
            sender.send(ctx.chatId(), i18n.t(ctx.lang(), "admin.ask_shop_name"));
            return;
        }
        Shop shop = shops.create(ctx.user(), text);
        sessions.clear(ctx.telegramId());
        Invite invite = invites.createOwnerInvite(ctx.user(), shop.getId());
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "admin.shop_created", esc(shop.getName()),
                botInfo.link(InviteService.payload(invite))));
        showMenu(ctx, null);
    }

    // ------------------------------------------------------------------ screens

    private void showMenu(BotContext ctx, Integer messageId) {
        Lang lang = ctx.lang();
        var keyboard = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "admin.btn.new"), cb("new")))
                .row(keyboards.btn(i18n.t(lang, "admin.btn.shops"), cb("list", 0)))
                .row(keyboards.btn(i18n.t(lang, "admin.btn.stats"), cb("stats")))
                .build();
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "admin.title"), keyboard);
    }

    private void showShops(BotContext ctx, Integer messageId, int requestedPage) {
        Lang lang = ctx.lang();
        Page<Shop> page = shops.listAll(requestedPage);
        int current = page.getNumber();
        StringBuilder text = new StringBuilder(i18n.t(lang, "admin.shops_title", current + 1, Math.max(page.getTotalPages(), 1)));
        var rows = keyboards.rows();
        if (page.isEmpty()) {
            text.append("\n\n").append(i18n.t(lang, "admin.no_shops"));
        }
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton> buttons = new ArrayList<>();
        int number = current * ShopService.PAGE_SIZE;
        for (Shop shop : page) {
            number++;
            text.append("\n\n").append(number).append(". ").append(statusIcon(shop, lang)).append(" <b>")
                    .append(esc(shop.getName())).append("</b>\n   ")
                    .append(i18n.t(lang, "admin.shop_line", ownerName(shop, lang), shops.barberCount(shop.getId())));
            buttons.add(keyboards.btn(number + ". " + statusIcon(shop, lang) + " " + shop.getName(), cb("shop", shop.getId(), current)));
        }
        rows.grid(buttons, 1);
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton> nav = new ArrayList<>();
        if (page.hasPrevious()) {
            nav.add(keyboards.btn(i18n.t(lang, "btn.prev"), cb("list", current - 1)));
        }
        if (page.hasNext()) {
            nav.add(keyboards.btn(i18n.t(lang, "btn.next"), cb("list", current + 1)));
        }
        rows.row(nav);
        rows.row(keyboards.back(lang, cb("menu")));
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private void showShop(BotContext ctx, Integer messageId, long shopId, int page) {
        Lang lang = ctx.lang();
        Shop shop = requireShop(shopId);
        String text = i18n.t(lang, "admin.shop_details", esc(shop.getName()), esc(shop.getSlug()),
                ownerName(shop, lang), shops.barberCount(shop.getId()),
                i18n.t(lang, shop.isActive() ? "admin.status_on" : "admin.status_off"),
                botInfo.link("s_" + shop.getSlug()));
        var keyboard = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, shop.isActive() ? "admin.btn.turn_off" : "admin.btn.turn_on"),
                        cb("tog", shop.getId(), page)))
                .row(keyboards.btn(i18n.t(lang, "admin.btn.owner_invite"), cb("inv", shop.getId())))
                .row(keyboards.btn(i18n.t(lang, "admin.btn.client_link"), cb("link", shop.getId())))
                .row(keyboards.back(lang, cb("list", page)))
                .build();
        sender.editOrSend(ctx.chatId(), messageId, text, keyboard);
    }

    private void toggle(BotContext ctx, Integer messageId, long shopId, int page) {
        Shop shop = requireShop(shopId);
        shops.setActive(ctx.user(), shopId, !shop.isActive());
        showShop(ctx, messageId, shopId, page);
    }

    private void sendOwnerInvite(BotContext ctx, long shopId) {
        Invite invite = invites.createOwnerInvite(ctx.user(), shopId);
        sender.send(ctx.chatId(), i18n.t(ctx.lang(), "admin.owner_invite", botInfo.link(InviteService.payload(invite))));
    }

    private void showStats(BotContext ctx, Integer messageId) {
        Lang lang = ctx.lang();
        GlobalStats s = stats.global();
        String text = i18n.t(lang, "admin.stats", s.activeShops() + s.inactiveShops(), s.activeShops(),
                s.inactiveShops(), s.barbers(), s.clients(), s.bookingsToday(), s.bookingsWeek(), s.bookingsMonth());
        sender.editOrSend(ctx.chatId(), messageId, text, keyboards.rows().row(keyboards.back(lang, cb("menu"))).build());
    }

    // ------------------------------------------------------------------ helpers

    private Shop requireShop(long id) {
        return shops.find(id).orElseThrow(() -> new pdp.service_bron.service.BusinessException("error.not_found"));
    }

    private String statusIcon(Shop shop, Lang lang) {
        return i18n.t(lang, shop.isActive() ? "icon.on" : "icon.off");
    }

    private String ownerName(Shop shop, Lang lang) {
        AppUser owner = users.findById(shop.getOwnerUserId()).orElse(null);
        return owner == null ? i18n.t(lang, "admin.no_owner") : esc(owner.displayName());
    }
}
