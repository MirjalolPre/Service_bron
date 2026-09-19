package pdp.service_bron.handler.barber;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.domain.PriceItem;
import pdp.service_bron.service.BusinessException;
import pdp.service_bron.service.I18nService;
import pdp.service_bron.service.PriceService;
import pdp.service_bron.session.BotState;
import pdp.service_bron.session.SessionService;
import pdp.service_bron.telegram.BotContext;
import pdp.service_bron.telegram.CallbackData;
import pdp.service_bron.telegram.KeyboardFactory;
import pdp.service_bron.telegram.TelegramSender;
import pdp.service_bron.util.MoneyFormatter;

import java.util.List;
import java.util.Map;

import static pdp.service_bron.handler.barber.BarberSupport.cb;
import static pdp.service_bron.telegram.HtmlEscaper.esc;

/** The barber's price list: add, edit, delete, reorder. Prices are shown to clients for information only. */
@Component
@RequiredArgsConstructor
class BarberPricesView {

    private static final String WIZ = "wiz";
    private static final String KEY_ITEM = "item";
    private static final String KEY_NAME = "name";

    private final PriceService prices;
    private final SessionService sessions;
    private final I18nService i18n;
    private final TelegramSender sender;
    private final KeyboardFactory keyboards;

    void onMenu(BotContext ctx, Barber barber) {
        showList(ctx, barber, null);
    }

    /** @return true when the callback belongs to this view. */
    boolean onCallback(BotContext ctx, Barber barber, CallbackData data) {
        Integer messageId = ctx.callbackMessageId();
        switch (data.action()) {
            case "pl" -> showList(ctx, barber, messageId);
            case "padd" -> askName(ctx, barber, false, messageId);
            case "wpadd" -> askName(ctx, barber, true, messageId);
            case "pe" -> showItem(ctx, barber, data.longArg(0), messageId);
            case "pen" -> askEditName(ctx, barber, data.longArg(0), messageId);
            case "pev" -> askEditValue(ctx, barber, data.longArg(0), messageId);
            case "pd" -> askDelete(ctx, barber, data.longArg(0), messageId);
            case "pdok" -> {
                prices.delete(ctx.user(), ownItem(barber, data.longArg(0)).getId());
                showList(ctx, barber, messageId);
            }
            case "pu" -> {
                prices.move(ctx.user(), ownItem(barber, data.longArg(0)).getId(), -1);
                showList(ctx, barber, messageId);
            }
            case "pdn" -> {
                prices.move(ctx.user(), ownItem(barber, data.longArg(0)).getId(), 1);
                showList(ctx, barber, messageId);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** @return true when the state belongs to this view. */
    boolean onInput(BotContext ctx, Barber barber) {
        Lang lang = ctx.lang();
        String text = ctx.text();
        BotState state = ctx.state();
        if (state != BotState.B_PRICE_NAME && state != BotState.B_PRICE_VALUE
                && state != BotState.B_PRICE_EDIT_NAME && state != BotState.B_PRICE_EDIT_VALUE) {
            return false;
        }
        if (text == null) {
            sender.send(ctx.chatId(), i18n.t(lang, "owner.send_text"));
            return true;
        }
        boolean wizard = "1".equals(ctx.session().get(WIZ));
        try {
            switch (state) {
                case B_PRICE_NAME -> {
                    String name = text.trim();
                    if (name.isEmpty() || name.length() > PriceService.MAX_NAME) {
                        throw new BusinessException("price.name_invalid", PriceService.MAX_NAME);
                    }
                    Map<String, String> data = wizard ? Map.of(WIZ, "1", KEY_NAME, name) : Map.of(KEY_NAME, name);
                    sessions.set(ctx.telegramId(), BotState.B_PRICE_VALUE, data);
                    sender.send(ctx.chatId(), i18n.t(lang, "price.ask_value", esc(name)));
                }
                case B_PRICE_VALUE -> {
                    long price = MoneyFormatter.parse(text).orElseThrow(() -> new BusinessException("price.value_invalid"));
                    PriceItem item = prices.add(ctx.user(), barber.getId(), ctx.session().get(KEY_NAME), price);
                    sessions.clear(ctx.telegramId());
                    if (wizard) {
                        sender.send(ctx.chatId(), i18n.t(lang, "barber.wiz.price_added", esc(item.getName()),
                                        i18n.money(lang, item.getPrice())),
                                keyboards.rows()
                                        .row(keyboards.btn(i18n.t(lang, "barber.btn.add_more"), cb("wpadd")))
                                        .row(keyboards.btn(i18n.t(lang, "barber.btn.finish"), cb("wfinish"))).build());
                    } else {
                        sender.send(ctx.chatId(), i18n.t(lang, "price.added"));
                        showList(ctx, barber, null);
                    }
                }
                case B_PRICE_EDIT_NAME -> {
                    prices.rename(ctx.user(), ownItem(barber, itemId(ctx)).getId(), text);
                    sessions.clear(ctx.telegramId());
                    showItem(ctx, barber, itemId(ctx), null);
                }
                case B_PRICE_EDIT_VALUE -> {
                    long price = MoneyFormatter.parse(text).orElseThrow(() -> new BusinessException("price.value_invalid"));
                    prices.changePrice(ctx.user(), ownItem(barber, itemId(ctx)).getId(), price);
                    sessions.clear(ctx.telegramId());
                    showItem(ctx, barber, itemId(ctx), null);
                }
                default -> {
                }
            }
        } catch (BusinessException e) {
            // Stay in the same state so the barber can simply type again.
            sender.send(ctx.chatId(), i18n.t(lang, e.key(), e.args()));
        }
        return true;
    }

    // ------------------------------------------------------------------ screens

    void showList(BotContext ctx, Barber barber, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        List<PriceItem> items = prices.list(barber.getId());
        StringBuilder text = new StringBuilder(i18n.t(lang, "price.title"));
        var rows = keyboards.rows();
        if (items.isEmpty()) {
            text.append("\n\n").append(i18n.t(lang, "price.empty"));
        }
        int n = 0;
        for (PriceItem item : items) {
            n++;
            text.append("\n").append(n).append(". ").append(esc(item.getName())).append(" — ")
                    .append(i18n.money(lang, item.getPrice()));
            rows.row(keyboards.btn(n + ". " + item.getName(), cb("pe", item.getId())),
                    keyboards.btn(i18n.t(lang, "btn.up"), cb("pu", item.getId())),
                    keyboards.btn(i18n.t(lang, "btn.down"), cb("pdn", item.getId())),
                    keyboards.btn(i18n.t(lang, "btn.delete"), cb("pd", item.getId())));
        }
        rows.row(keyboards.btn(i18n.t(lang, "price.btn.add"), cb("padd")));
        sender.editOrSend(ctx.chatId(), messageId, text.toString(), rows.build());
    }

    private void showItem(BotContext ctx, Barber barber, long itemId, Integer messageId) {
        sessions.clear(ctx.telegramId());
        Lang lang = ctx.lang();
        PriceItem item = ownItem(barber, itemId);
        InlineKeyboardMarkup keyboard = keyboards.rows()
                .row(keyboards.btn(i18n.t(lang, "price.btn.edit_name"), cb("pen", itemId)),
                        keyboards.btn(i18n.t(lang, "price.btn.edit_price"), cb("pev", itemId)))
                .row(keyboards.back(lang, cb("pl"))).build();
        sender.editOrSend(ctx.chatId(), messageId,
                i18n.t(lang, "price.item", esc(item.getName()), i18n.money(lang, item.getPrice())), keyboard);
    }

    private void askName(BotContext ctx, Barber barber, boolean wizard, Integer messageId) {
        Lang lang = ctx.lang();
        if (prices.list(barber.getId()).size() >= PriceService.MAX_ITEMS) {
            throw new BusinessException("price.limit", PriceService.MAX_ITEMS);
        }
        sessions.set(ctx.telegramId(), BotState.B_PRICE_NAME, wizard ? Map.of(WIZ, "1") : Map.of());
        String key = wizard ? "barber.wiz.price" : "price.ask_name";
        InlineKeyboardMarkup keyboard = wizard ? null : keyboards.rows().row(keyboards.back(lang, cb("pl"))).build();
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, key), keyboard);
    }

    private void askEditName(BotContext ctx, Barber barber, long itemId, Integer messageId) {
        Lang lang = ctx.lang();
        ownItem(barber, itemId);
        sessions.set(ctx.telegramId(), BotState.B_PRICE_EDIT_NAME, Map.of(KEY_ITEM, String.valueOf(itemId)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "price.ask_name"),
                keyboards.rows().row(keyboards.back(lang, cb("pe", itemId))).build());
    }

    private void askEditValue(BotContext ctx, Barber barber, long itemId, Integer messageId) {
        Lang lang = ctx.lang();
        PriceItem item = ownItem(barber, itemId);
        sessions.set(ctx.telegramId(), BotState.B_PRICE_EDIT_VALUE, Map.of(KEY_ITEM, String.valueOf(itemId)));
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "price.ask_value", esc(item.getName())),
                keyboards.rows().row(keyboards.back(lang, cb("pe", itemId))).build());
    }

    private void askDelete(BotContext ctx, Barber barber, long itemId, Integer messageId) {
        Lang lang = ctx.lang();
        PriceItem item = ownItem(barber, itemId);
        sender.editOrSend(ctx.chatId(), messageId, i18n.t(lang, "price.confirm_delete", esc(item.getName())),
                keyboards.rows()
                        .row(keyboards.btn(i18n.t(lang, "btn.yes"), cb("pdok", itemId)),
                                keyboards.btn(i18n.t(lang, "btn.no"), cb("pl"))).build());
    }

    private long itemId(BotContext ctx) {
        return Long.parseLong(ctx.session().get(KEY_ITEM));
    }

    /** The item must belong to this barber's own price list. */
    private PriceItem ownItem(Barber barber, long itemId) {
        PriceItem item = prices.find(itemId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!item.getBarberId().equals(barber.getId())) {
            throw new BusinessException("error.forbidden");
        }
        return item;
    }
}
