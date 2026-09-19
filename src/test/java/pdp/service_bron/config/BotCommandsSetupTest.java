package pdp.service_bron.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.service.I18nService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BotCommandsSetupTest {

    private final I18nService i18n = new I18nService();
    private final TelegramClient client = mock(TelegramClient.class);

    private BotCommandsSetup setup(Long... admins) {
        BotProperties properties = new BotProperties(new BotProperties.Telegram("t", "bot", true), List.of(admins), "Asia/Tashkent");
        return new BotCommandsSetup(client, i18n, properties);
    }

    private List<SetMyCommands> installed(BotCommandsSetup setup) throws Exception {
        setup.install();
        ArgumentCaptor<SetMyCommands> captor = ArgumentCaptor.forClass(SetMyCommands.class);
        verify(client, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void registersTheFiveCommonCommandsInBothLanguagesAndAFallback() throws Exception {
        List<SetMyCommands> calls = installed(setup());

        assertThat(calls).extracting(SetMyCommands::getLanguageCode).containsExactlyInAnyOrder("uz", "ru", null);
        for (SetMyCommands call : calls) {
            assertThat(call.getCommands()).extracting(BotCommand::getCommand)
                    .containsExactly("start", "menu", "bookings", "lang", "help");
            assertThat(call.getCommands()).allSatisfy(c -> assertThat(c.getDescription()).doesNotStartWith("!").isNotBlank());
        }
    }

    @Test
    void adminCommandExistsOnlyInTheAdminChats() throws Exception {
        List<SetMyCommands> calls = installed(setup(111L));

        List<SetMyCommands> forAdmin = calls.stream().filter(c -> "chat".equals(c.getScope().getType())).toList();
        List<SetMyCommands> forEveryone = calls.stream().filter(c -> "default".equals(c.getScope().getType())).toList();

        assertThat(forAdmin).hasSize(3);
        assertThat(forAdmin).allSatisfy(c -> assertThat(c.getCommands()).extracting(BotCommand::getCommand).contains("admin"));
        assertThat(forEveryone).allSatisfy(c -> assertThat(c.getCommands()).extracting(BotCommand::getCommand).doesNotContain("admin"));
    }

    @Test
    void descriptionsAreTranslated() {
        BotCommandsSetup setup = setup();

        assertThat(setup.commands(Lang.UZ, false).getFirst().getDescription())
                .isNotEqualTo(setup.commands(Lang.RU, false).getFirst().getDescription());
    }

    @Test
    void telegramTextLimitsAreRespected() {
        for (Lang lang : Lang.values()) {
            for (BotCommand c : setup(1L).commands(lang, true)) {
                assertThat(c.getDescription().length()).isBetween(3, 256);
                assertThat(c.getCommand()).matches("[a-z0-9_]{1,32}");
            }
        }
    }
}
