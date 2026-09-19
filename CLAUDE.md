# CLAUDE.md — Navbat (barbershop booking Telegram bot)

Full spec: SPEC.md

## Commands
- Build + tests: `./gradlew build` (must stay green; needs Docker running for Testcontainers)
- Run in dev: `./gradlew bootRun` or run `ServiceBronApplication` in IntelliJ. Docker Compose support starts Postgres from `compose.yaml` (DB only).
- Config: environment variables or `.env` (see `.env.example`). Never commit real tokens.

## Stack
Gradle (Groovy), Spring Boot 4.1, Java 21 (virtual threads), PostgreSQL 16 + Flyway (`ddl-auto=validate`),
Jackson 3 (`tools.jackson`), TelegramBots 10.x long polling (own registration, no Spring starter), ZXing, Lombok, Testcontainers.

## Conventions
- Base package `pdp.service_bron`: config, telegram, handler (client/barber/owner/admin/common), session, domain, repository, service, scheduler, util.
- Handlers only parse updates, call services and render messages. **No business logic in handlers.**
- Services use the injected `java.time.Clock`; never call `now()` directly. Shop times use the shop's time zone.
- **No hard-coded user-facing strings in Java.** Everything goes through `I18nService` (`messages_uz.properties`, `messages_ru.properties`, UTF-8, `{0}` placeholders). Keep both files in sync.
- All user-provided text shown in messages must be HTML-escaped (`HtmlEscaper.esc`). Parse mode is HTML.
- Every callback re-checks permissions (`AccessService`); never trust ids from callback data.
- `callback_data` <= 64 bytes, format `domain:action:arg...` via `CallbackData`. Always answer callback queries; edit the existing message for menu navigation.
- Conversation state lives in the DB (`bot_session`, `BotState`); menu buttons reset it.
- Double booking is prevented by the Postgres exclusion constraint; `BookingService` catches the violation.
- Never log full phone numbers: use `PhoneFormatter.mask`.
- Business errors: throw `BusinessException(i18nKey)`; the dispatcher shows the translated message.
