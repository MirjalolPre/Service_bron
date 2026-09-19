package pdp.service_bron.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScope;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.service.I18nService;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the command menu (the "/" list) in Uzbek and Russian. Everybody gets the common commands;
 * {@code /admin} is added only in the private chats of the super admins.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BotCommandsSetup {

    static final List<String> COMMON_COMMANDS = List.of("start", "menu", "bookings", "lang", "help");

    private final TelegramClient client;
    private final I18nService i18n;
    private final BotProperties properties;

    /** Never fails the application: a missing command menu is not worth stopping the bot for. */
    public void install() {
        BotCommandScope everybody = BotCommandScopeDefault.builder().build();
        for (Lang lang : Lang.values()) {
            set(commands(lang, false), everybody, lang.code());
        }
        set(commands(Lang.UZ, false), everybody, null); // any other language falls back to Uzbek

        for (Long adminId : properties.superAdminIds()) {
            BotCommandScope adminChat = BotCommandScopeChat.builder().chatId(adminId).build();
            for (Lang lang : Lang.values()) {
                set(commands(lang, true), adminChat, lang.code());
            }
            set(commands(Lang.UZ, true), adminChat, null);
        }
    }

    List<BotCommand> commands(Lang lang, boolean withAdmin) {
        List<BotCommand> list = new ArrayList<>();
        for (String name : COMMON_COMMANDS) {
            list.add(command(lang, name));
        }
        if (withAdmin) {
            list.add(command(lang, "admin"));
        }
        return list;
    }

    private BotCommand command(Lang lang, String name) {
        return BotCommand.builder().command(name).description(i18n.t(lang, "command." + name)).build();
    }

    private void set(List<BotCommand> commands, BotCommandScope scope, String languageCode) {
        try {
            client.execute(SetMyCommands.builder().commands(commands).scope(scope).languageCode(languageCode).build());
        } catch (TelegramApiException e) {
            // Typical for admins who never opened the bot yet ("chat not found").
            log.debug("setMyCommands skipped ({}): {}", scope.getType(), e.getMessage());
        }
    }
}
