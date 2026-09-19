# SPEC — "Navbat" Barbershop Booking Telegram Bot

> **For Claude Code:** Read this entire file before writing any code. Build the project **phase by phase** (see §12).
> **The project already exists** (created with Spring Initializr in IntelliJ). Work in the current repo root. Do NOT create a new folder or a new project, and do NOT convert it to Maven.
> After every phase: `./gradlew build` must pass. Fix all errors before moving on.
> At the start, create a short `CLAUDE.md` that summarizes the conventions from this file and says "Full spec: SPEC.md".

---

## 1. Product summary

A **multi-tenant** Telegram bot (one bot, many barbershops) that lets clients book a barber.

- **Client:** `/start` → picks a barber → sees the barber's **price list (information only)** → sees **free time slots** → books.
- **Payment:** **cash only, paid to the barber on site.** No online payment. Prices are shown for information only, and the client does NOT choose a service.
- **Barber:** gets notified about bookings and manages their price list, working hours, breaks, days off, blocked time, and manual (walk-in/phone) bookings. Marks each client as came / no-show.
- **Shop owner:** manages shop info and barbers and gets the **client link + QR code**. That link is added to Google Maps / Yandex Maps / 2GIS / Instagram as the "Book" link.
- **Super admin (platform owner):** creates shops, turns shops on/off (subscription), and views global stats.

Bot languages: **Uzbek (Latin)** and **Russian**. The user picks one on first `/start`.

Out of scope for the MVP: online payment, web admin panel, SMS, a service selection step, and multiple branches per shop.

---

## 2. Tech stack (mandatory)

**Existing project, keep these (except the Java version change below):**
- **Gradle** (Groovy DSL `build.gradle`, wrapper `./gradlew`), project name `Service_bron`
- **Spring Boot 4.1.1**
- **Java 21**: change the toolchain in `build.gradle` from 17 to `JavaLanguageVersion.of(21)` (the user installs JDK 21; verify with `./gradlew -q javaToolchains`). Use **virtual threads** (`spring.threads.virtual.enabled=true`) and modern Java 21 features (records, pattern matching for `switch`) where they make the code clearer.
- Base package **`pdp.service_bron`**, main class `ServiceBronApplication`
- Existing dependencies stay: `spring-boot-starter`, `spring-boot-devtools`, `spring-boot-docker-compose`, `spring-boot-configuration-processor`, `spring-boot-starter-test`

**Add:**
- Spring Data JPA (Hibernate) + **PostgreSQL 16** driver
- **Flyway** migrations (`spring.jpa.hibernate.ddl-auto=validate`). Spring Boot 4 split auto-configuration into modules, so Flyway needs **`spring-boot-starter-flyway`** plus `org.flywaydb:flyway-database-postgresql`. **Verify every artifact name against the Spring Boot 4.1 docs** before adding it.
- Spring Boot 4 uses **Jackson 3** (package `tools.jackson.databind`). Use it for the `jsonb` session data.
- **TelegramBots** by rubenlagus, **latest stable (v7+ API)**: `org.telegram:telegrambots-longpolling` + `org.telegram:telegrambots-client` (check the latest version on Maven Central). Use `OkHttpTelegramClient` and `LongPollingSingleThreadUpdateConsumer`.
  **Do NOT use `telegrambots-springboot-longpolling-starter`** (it may not support Spring Boot 4). Register the bot yourself in a `@Configuration`: create one `TelegramBotsLongPollingApplication`, call `registerBot(token, consumer)` on `ApplicationReadyEvent`, and close it on shutdown. Do NOT use the deprecated `TelegramLongPollingBot` class.
- Long polling (no domain/HTTPS needed)
- **ZXing** (`com.google.zxing:core` + `javase`) for QR code PNGs
- Lombok is allowed.
- Tests: JUnit 5, AssertJ, Mockito, **Testcontainers PostgreSQL** via `spring-boot-testcontainers` + `@ServiceConnection` (check the Testcontainers artifact names for the version Boot 4.1 manages)

**Database in development:** the project uses **Spring Boot Docker Compose support**. Put a `postgres:16` service (db `navbat`, user/password `navbat`, named volume, healthcheck) into the existing **`compose.yaml`**. When the app runs from IntelliJ, Spring Boot starts that container and configures the datasource automatically. Docker Desktop must be running. Set `spring.docker.compose.lifecycle-management=start-only` so the database is not stopped on every restart.
`compose.yaml` must contain **only the database** (otherwise Spring Boot would also try to start the app container).

**Configuration** comes from environment variables or a local `.env` file. Load it with
`spring.config.import=optional:file:.env[.properties]` in `application.properties`. Commit a `.env.example`:

```
TELEGRAM_BOT_TOKEN=
TELEGRAM_BOT_USERNAME=          # without @
SUPER_ADMIN_IDS=123456789,987654321   # Telegram user ids, comma separated
APP_DEFAULT_TIMEZONE=Asia/Tashkent
# Production only (in dev, Docker Compose support configures the DB):
# SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/navbat
# SPRING_DATASOURCE_USERNAME=navbat
# SPRING_DATASOURCE_PASSWORD=navbat
```

Never commit real tokens. Add `.env` to `.gitignore`. At startup, fail fast with a clear message if `TELEGRAM_BOT_TOKEN` is empty.

**Production / deploy:** a multi-stage `Dockerfile` (Gradle build → JRE runtime image) and a separate **`compose.prod.yaml`** (app + postgres). CI: GitHub Actions running `./gradlew build`.

---

## 3. Architecture

Base package: `pdp.service_bron` (keep `ServiceBronApplication` where it is)

```
config/        BotProperties (@ConfigurationProperties), ClockConfig (Clock bean), TelegramConfig
telegram/      UpdateDispatcher, TelegramSender (wrapper over TelegramClient),
               KeyboardFactory, CallbackData (encode/decode), HtmlEscaper
handler/       UpdateHandler interface + packages: client/, barber/, owner/, admin/, common/
session/       SessionService, BotState enum (conversation state stored in DB)
domain/        JPA entities + enums
repository/    Spring Data repositories
service/       UserService, ShopService, BarberService, PriceService, ScheduleService,
               SlotService, BookingService, InviteService, NotificationService,
               QrService, StatsService, I18nService
scheduler/     ReminderJob, AttendanceJob, DailySummaryJob, AutoCompleteJob
util/          TimeFormatter (uz/ru dates), PhoneFormatter, SlugUtil (transliteration)
```

Rules:
- **Handlers contain no business logic.** They parse the update, call services, and render messages.
- Services are unit-testable and use an injected `java.time.Clock` (never call `LocalDateTime.now()` directly).
- **Every callback must re-check permissions** (for example, is this user really the barber of this booking?). Never trust ids from callback data.
- All user-provided text shown in messages must be **HTML-escaped** (parse mode = HTML).
- One `try/catch` per update in the dispatcher: log the error and send the user a friendly "Xatolik yuz berdi, qaytadan urinib ko'ring" message.
- Always call `answerCallbackQuery`. Ignore Telegram's "message is not modified" error silently.
- For navigation inside inline menus, **edit the existing message** (`editMessageText`) instead of sending new messages, so the chat does not fill with spam.
- **`callback_data` is at most 64 bytes.** Use a compact format such as `c:bar:12`, `c:day:12:20260918`, `c:slot:12:202609181530`, `b:cxl:345`. Implement `CallbackData` with unit tests.

---

## 4. Roles and identification

- **app_user** is created on first contact (Telegram id, names, username, language).
- **Super admin:** Telegram id is in `SUPER_ADMIN_IDS`.
- **Owner:** `shop.owner_user_id`.
- **Barber:** row in `barber` with `user_id`.
- One person can be client + barber + owner at the same time.
- **Mode switching:** staff (barber/owner) get the staff menu on plain `/start`, plus a "👤 Mijoz rejimi" button. In client mode, staff see an extra "💼 Ish rejimi" button.

### Deep links (`/start <payload>`; allowed chars `A-Za-z0-9_-`, max 64)
| Payload | Meaning |
|---|---|
| `s_<slug>` | Client opens a specific shop. Save it as the user's `last_shop_id`. |
| `o_<token>` | One-time **owner** invite (created by super admin) |
| `b_<token>` | One-time **barber** invite (created by owner), valid 7 days |

Plain `/start` for a client:
- if `last_shop_id` exists → open that shop
- else → a paginated list of active shops (5 per page) + a "🔎 Qidirish" option (search by name)

---

## 5. Data model (Flyway `V1__init.sql`)

Use `timestamptz` for moments and `time` for daily hours. Money is `bigint` in so'm.

```sql
create extension if not exists btree_gist;

app_user(id bigserial pk, telegram_id bigint unique not null, first_name text, last_name text,
         username text, phone text, language varchar(2) not null default 'uz',
         last_shop_id bigint null, created_at timestamptz not null default now())

shop(id bigserial pk, slug text unique not null, name text not null, address text, landmark text,
     latitude double precision, longitude double precision, phone text, photo_file_id text,
     description text, timezone text not null default 'Asia/Tashkent',
     booking_horizon_days int not null default 7,        -- 7 | 14 | 30
     min_lead_minutes int not null default 30,           -- 0 | 15 | 30 | 60
     reminder_minutes_before int not null default 120,   -- 0 (off) | 60 | 120 | 180
     max_active_bookings_per_client int not null default 2,
     active boolean not null default true,
     owner_user_id bigint references app_user(id),
     created_at timestamptz not null default now())

barber(id bigserial pk, shop_id bigint not null references shop(id), user_id bigint references app_user(id),
       display_name text not null, bio text, photo_file_id text,
       slot_minutes int not null default 30,              -- 15|20|30|45|60|90
       accepting_bookings boolean not null default true,
       active boolean not null default true, sort_order int not null default 0,
       created_at timestamptz not null default now(),
       unique(shop_id, user_id))

price_item(id bigserial pk, barber_id bigint not null references barber(id),
           name text not null, price bigint not null check (price >= 0),
           sort_order int not null default 0, active boolean not null default true)

working_hours(id bigserial pk, barber_id bigint not null references barber(id),
              day_of_week smallint not null check (day_of_week between 1 and 7),  -- ISO: 1=Mon
              day_off boolean not null default false,
              start_time time, end_time time,
              break_start time, break_end time,
              unique(barber_id, day_of_week))

time_off(id bigserial pk, barber_id bigint not null references barber(id),
         start_at timestamptz not null, end_at timestamptz not null, reason text,
         check (end_at > start_at))

booking(id bigserial pk, shop_id bigint not null references shop(id),
        barber_id bigint not null references barber(id),
        client_user_id bigint references app_user(id),     -- null for manual bookings
        client_name text, client_phone text,
        start_at timestamptz not null, end_at timestamptz not null,
        status varchar(24) not null,   -- BOOKED, CANCELLED_BY_CLIENT, CANCELLED_BY_BARBER, COMPLETED, NO_SHOW
        source varchar(12) not null,   -- BOT, MANUAL
        note text,
        reminder_sent boolean not null default false,
        attendance_asked boolean not null default false,
        client_confirmed boolean not null default false,
        cancel_reason text, cancelled_at timestamptz,
        created_at timestamptz not null default now(),
        check (end_at > start_at),
        exclude using gist (barber_id with =, tstzrange(start_at, end_at) with &&) where (status = 'BOOKED'))

invite(id bigserial pk, token text unique not null, type varchar(10) not null, -- OWNER, BARBER
       shop_id bigint not null references shop(id), created_by bigint references app_user(id),
       expires_at timestamptz not null, used_by bigint references app_user(id), used_at timestamptz)

bot_session(telegram_id bigint pk, state varchar(64) not null, data jsonb not null default '{}',
            updated_at timestamptz not null default now())
```

Add indexes for `booking(barber_id, start_at)`, `booking(client_user_id, status)`, `booking(status, start_at)`.

The **exclusion constraint prevents double booking** even when two clients press the button at the same moment. The service must catch the constraint violation and show: "Afsuski, bu vaqt hozirgina band bo'ldi. Boshqa vaqtni tanlang." Then it re-shows fresh slots.

---

## 6. Slot generation (`SlotService`), the core logic

`List<LocalTime> freeSlots(barberId, LocalDate date)`, evaluated in the shop's timezone:

1. Empty if the shop is inactive, the barber is inactive, `accepting_bookings = false`, the date is before today, or the date is later than today + `booking_horizon_days - 1`.
2. Load `working_hours` for the weekday. Empty if missing or `day_off`.
3. Candidates run from `start_time` in steps of `slot_minutes`. A slot `[t, t+slot)` is valid only if it **fits fully** before `end_time`.
4. Remove slots that overlap the break (`break_start`–`break_end`).
5. Remove slots that overlap any `time_off` range.
6. Remove slots that overlap any booking with `status = BOOKED`. Check real overlap, because existing bookings may have a different length after `slot_minutes` changed.
7. For today, remove slots with `start < now + min_lead_minutes`.
8. Return the slots sorted.

`List<LocalDate> availableDates(barberId)` = the dates in the horizon that have at least one free slot. Show at most 14 date buttons.

**Unit tests required** for every rule above, plus: a slot that ends exactly at `end_time` is allowed, a slot that touches the break edge is allowed, and a working day that crosses midnight is not supported (validation rejects `end <= start`).

---

## 7. Client flow (detailed)

### 7.1 First start
1. Language choice: `🇺🇿 O'zbekcha` / `🇷🇺 Русский`.
2. Ask for the phone number with a **request_contact** reply button: "📱 Raqamni yuborish". Accept only a contact whose `user_id` equals the sender's id. Normalize to `+998XXXXXXXXX`. The client cannot book without a phone number.
3. Show the shop (from the deep link) or the shop list.

### 7.2 Client main menu (reply keyboard)
| uz | ru |
|---|---|
| ✂️ Navbatga yozilish | ✂️ Записаться |
| 📋 Mening bronlarim | 📋 Мои записи |
| 📍 Sartaroshxona | 📍 Барбершоп |
| 🏪 Boshqa sartaroshxona | 🏪 Другой барбершоп |
| 🌐 Til | 🌐 Язык |

### 7.3 Booking steps (one inline message, edited on each step)
1. **Choose barber:** one button per active barber in the shop (`display_name`). Barbers with `accepting_bookings=false` are shown with "⛔" and can't be selected. If there is only one barber, skip this step.
2. **Barber card** (with photo, if any, via sendPhoto + caption):
   ```
   💈 Aziz
   5 yillik tajriba            ← bio (optional)

   💰 Narxlar:
   • Soch olish — 60 000 so'm
   • Soqol — 30 000 so'm
   • Soch + soqol — 80 000 so'm

   💵 To'lov: naqd, joyida sartaroshga
   ```
   Buttons: `[📅 Bo'sh vaqtlarni ko'rish]` `[⬅️ Orqaga]`
   Price format: thousands separated by a space, e.g. `60 000 so'm` / `60 000 сум`.
3. **Choose date:** buttons, 2 per row, e.g. `Bugun, 16-sen` / `Ertaga, 17-sen` / `Pa, 18-sen` …
   - uz weekdays: Du, Se, Ch, Pa, Ju, Sh, Ya · uz months: yan, fev, mar, apr, may, iyn, iyl, avg, sen, okt, noy, dek
   - ru weekdays: Пн, Вт, Ср, Чт, Пт, Сб, Вс · ru: `Сегодня`, `Завтра`, `18 сен`
   - If no dates are available: "Afsuski, yaqin kunlarda bo'sh vaqt yo'q."
4. **Choose time:** free slots, 4 per row (`10:00` `10:30` …), plus `[⬅️ Kunlar]`.
5. **Confirm:**
   ```
   Bronni tasdiqlaysizmi?

   🏪 Barber House
   📍 Chilonzor 9-kvartal (mo'ljal: metro)
   💈 Sartarosh: Aziz
   📅 18-sentabr, payshanba
   🕒 15:30
   💵 To'lov: naqd, joyida
   ```
   `[✅ Tasdiqlash]` `[❌ Bekor qilish]`
6. On confirm, `BookingService.book(...)` re-validates everything in a transaction: the slot is still free, the client has fewer than `max_active_bookings_per_client` future BOOKED bookings in this shop, and the shop and barber are active.
   Success message: "✅ Siz navbatga yozildingiz! Vaqtidan {N} soat oldin eslatib qo'yamiz."
   If the shop has a location, also send the location (`sendLocation`) and a `[📍 Xaritada ochish]` button.
7. Notify the barber (§8.3).

### 7.4 My bookings
List of future BOOKED bookings (shop, barber, date, time), each with `[❌ Bekor qilish]`. Cancelling asks for confirmation, then sets `CANCELLED_BY_CLIENT` and notifies the barber: "❌ Ali 18-sentabr 15:30 dagi bronni bekor qildi." The slot becomes free again automatically. A client may cancel any time before the start.

### 7.5 Shop info
Name, address, landmark, phone, description, photo, location.

---

## 8. Barber flow

### 8.1 Joining
Opening a `b_<token>` link: validate the token (exists, not used, not expired, shop active). Ask for the phone if it is missing. Create the `barber` row (`display_name` = Telegram first name, editable later), mark the invite as used, and notify the owner: "✅ Aziz sartarosh sifatida qo'shildi."
Then run a **setup wizard**:
1. display name (keep or type a new one)
2. slot length (15/20/30/45/60/90)
3. working hours (quick presets: `09:00–20:00 Du–Sh, Ya dam olish` / `10:00–21:00 har kuni` / "Qo'lda sozlash")
4. break (`13:00-14:00` / "Tanaffus yo'q")
5. at least one price item
The barber cannot accept bookings until working hours exist.

### 8.2 Barber menu (reply keyboard)
| uz | ru |
|---|---|
| 📅 Bugun | 📅 Сегодня |
| 📆 Ertaga | 📆 Завтра |
| 🗓 Jadval | 🗓 Расписание |
| ➕ Qo'lda bron | ➕ Ручная запись |
| 💰 Narxlar | 💰 Цены |
| ⏰ Ish vaqti | ⏰ Рабочее время |
| 🚫 Vaqtni yopish | 🚫 Закрыть время |
| 📊 Statistika | 📊 Статистика |
| ⚙️ Sozlamalar | ⚙️ Настройки |
| 👤 Mijoz rejimi | 👤 Режим клиента |

- **Bugun / Ertaga / Jadval:** a timeline for the chosen day (Jadval = date picker), e.g.:
  ```
  📅 18-sentabr, payshanba   (09:00–20:00, tanaffus 13:00–14:00)
  10:00 — Ali, +998 90 123 45 67
  11:30 — Vali (qo'lda)
  15:30 — Sardor, +998 93 …
  Bo'sh: 09:00, 09:30, 10:30, …
  ```
  Each booking gets a button to open it → `[❌ Bekor qilish]` (asks for an optional reason and notifies the client) and, after the start time, `[✅ Keldi]` / `[🚫 Kelmadi]`.
  Show the client name as an HTML mention `<a href="tg://user?id=...">Ali</a>` so the barber can message the client.
- **Qo'lda bron** (walk-in or phone booking): date → free slot → client name (required) → phone (optional, or "O'tkazib yuborish") → saved with `source=MANUAL`. This blocks the slot.
- **Narxlar:** a list with `[✏️]` `[🗑]` for each item and `[➕ Qo'shish]`. Adding asks for the name, then the price (accept `60000`, `60 000`, `60k`). Maximum 20 items. Items can be reordered with ⬆️ ⬇️.
- **Ish vaqti:** a week overview; each day is a button (`Du 09:00–20:00`, `Ya — dam olish`). Tapping a day offers: "Vaqtni o'zgartirish" (text `HH:mm-HH:mm`), "Tanaffusni o'zgartirish", "Dam olish kuni", "Ish kuni", "Barcha ish kunlariga qo'llash". Validate the format and require `end > start`, with the break inside working hours.
  If a change would leave existing BOOKED bookings outside the new hours, list them and ask: "Bu bronlar saqlanib qoladi. Davom etasizmi?" The bookings are kept.
- **Vaqtni yopish:** pick a date → "Butun kun" or a range `14:00-16:00` → creates `time_off`. If BOOKED bookings overlap, list them and offer `[Bronlarni bekor qilib yopish]` (notifies the clients) or `[Orqaga]`. Also show the list of upcoming time_off entries with a delete option.
- **Statistika:** this week / this month: total bookings, completed, no-show, cancelled by client, cancelled by barber, and the busiest weekday.
- **Sozlamalar:** display name, bio, photo (send a photo; store `file_id`), slot length, and the `Bron qabul qilish ✅/⛔` toggle.

### 8.3 Notifications to the barber
- New booking:
  ```
  🆕 Yangi bron!
  👤 Ali (+998 90 123 45 67)
  📅 18-sentabr, payshanba, 15:30
  ```
  `[❌ Bekor qilish]`. If this client has ≥ 2 NO_SHOW bookings in this shop, add "⚠️ Bu mijoz avval {n} marta kelmagan."
- Client cancelled → message.
- Client confirmed after the reminder → "✅ Ali 15:30 ga kelishini tasdiqladi."
- **DailySummaryJob** at 08:00 shop time: "Bugun sizda {n} ta bron bor" + the timeline. Skip if 0.

---

## 9. Owner flow

### 9.1 Joining
An `o_<token>` link makes the user `shop.owner_user_id`. The setup wizard asks for: shop name (prefilled) → address → landmark (optional) → location (Telegram location button, optional) → phone → photo (optional). Then it asks: "Siz ham shu yerda sartarosh bo'lib ishlaysizmi?" `[Ha]` creates the owner's own barber row and runs the barber wizard. `[Yo'q]` skips it.

### 9.2 Owner menu (added to the staff menu)
| uz | ru |
|---|---|
| 🏪 Sartaroshxona | 🏪 Барбершоп |
| 👥 Sartaroshlar | 👥 Мастера |
| 🔗 Havola va QR | 🔗 Ссылка и QR |
| 📋 Bugungi bronlar | 📋 Записи на сегодня |

- **Sartaroshxona:** edit every shop field + settings (booking horizon 7/14/30, reminder off/1h/2h/3h, min lead time 0/15/30/60, max active bookings per client 1/2/3).
- **Sartaroshlar:** a list of barbers with status. `[➕ Sartarosh qo'shish]` generates a one-time link `https://t.me/<BOT>?start=b_<token>` valid for 7 days, with the text: "Bu havolani sartaroshga yuboring." For each barber: deactivate/activate, reorder, edit name. If the barber has future bookings, deactivation first lists them and offers "Bronlarni bekor qilib o'chirish" (notifies clients). `[Men ham sartaroshman]` appears if the owner has no barber row.
- **Havola va QR:** sends the client link `https://t.me/<BOT>?start=s_<slug>` and a **QR code PNG** (ZXing, 1024px, shop name printed under the code). Text:
  "Bu havolani Google Maps, Yandex Maps, 2GIS va Instagram profilingizga 'Yozilish' havolasi sifatida qo'ying. QR kodni chop etib, zalga osib qo'ying."
- **Bugungi bronlar:** all barbers' bookings for today, grouped by barber.

---

## 10. Super admin flow (`/admin`, only for `SUPER_ADMIN_IDS`)

- `➕ Yangi sartaroshxona`: ask the name → create the shop with a slug made from the transliterated name + a 4-char random suffix (e.g. `barber-house-k3x9`) → generate an owner invite (valid 7 days) → show the link `https://t.me/<BOT>?start=o_<token>`.
- `📋 Sartaroshxonalar`: a paginated list (name, owner, barbers count, active). Details allow: toggle active (subscription), regenerate the owner invite, and show the client link.
- `📊 Statistika`: number of shops (active/inactive), barbers, clients, and bookings today / this week / this month.
- When a shop is inactive, clients see "Bu sartaroshxona hozircha onlayn bron qabul qilmayapti. Telefon: …". Staff menus still work, but a banner says the subscription is off.

---

## 11. Scheduled jobs (`@Scheduled`, every minute unless noted; all idempotent)

- **ReminderJob:** for BOOKED bookings with `reminder_sent=false` and `start_at - reminder_minutes_before <= now`, where `start_at > now` and the source is BOT, send the client:
  "⏰ Eslatma: bugun soat 15:30 da Aziz sizni kutadi (Barber House)." `[✅ Boraman]` `[❌ Bekor qilish]`. Then set `reminder_sent=true`.
  If the booking was created inside the reminder window (created_at > start_at - reminder_minutes_before), do NOT send; just set `reminder_sent=true`.
- **AttendanceJob:** for BOOKED bookings with `end_at + 15 min <= now` and `attendance_asked=false`, ask the barber: "Ali (15:30) keldimi?" `[✅ Keldi]` `[🚫 Kelmadi]`. Then set `attendance_asked=true`.
- **AutoCompleteJob** (daily at 23:55 shop time): mark still-BOOKED past bookings as `COMPLETED`.
- **DailySummaryJob** (see §8.3).
- **SessionCleanupJob** (hourly): delete `bot_session` rows older than 24h.

Handle "bot was blocked by the user" (403) gracefully: log it and continue.

---

## 12. Build plan — do it in this order

1. **Skeleton:** `git init` if needed, update `build.gradle`, `compose.yaml` (postgres only), `application.properties`, `.env.example`, `.gitignore`, config classes, Flyway V1, entities, repositories, `/start` with language choice. Fix the existing `ServiceBronApplicationTests` so it runs with Testcontainers and passes.
2. **Core services + tests:** `SlotService`, `BookingService` (including a concurrency test: 10 threads book the same slot → exactly 1 succeeds), `CallbackData`, `TimeFormatter`, `PhoneFormatter`, `SlugUtil`.
3. **i18n:** `messages_uz.properties`, `messages_ru.properties` (UTF-8), `I18nService`. **No hard-coded user-facing strings in Java.**
4. **Super admin + owner onboarding + barber invite/onboarding** (deep links, wizards).
5. **Barber features:** price list, working hours, breaks, time off, day views, manual booking, settings, stats.
6. **Client booking flow** (§7) end to end.
7. **Notifications + scheduled jobs** (§8.3, §11).
8. **Owner extras:** QR code, today's bookings, barber management.
9. **Polish:** `setMyCommands` for uz and ru (`/start`, `/menu`, `/bookings`, `/lang`, `/help`; `/admin` only in admin scope), error handling, logging (mask phones in logs: `+99890***4567`), README, CI.

---

## 13. Deliverables

- Working source code with tests (`./gradlew build` green)
- `Dockerfile`, `compose.yaml` (dev DB), `compose.prod.yaml` (app + DB), `.env.example`, `.gitignore`
- `.github/workflows/ci.yml`
- `README.md` **in Uzbek** with:
  1. Getting a token from @BotFather
  2. Filling in `.env` (how to find your Telegram id via @userinfobot)
  3. Development: start Docker Desktop, then run `ServiceBronApplication` in IntelliJ (or `./gradlew bootRun`). Production: `docker compose -f compose.prod.yaml up -d --build`
  4. Test scenario: `/admin` → create shop → open the owner link → "Men ham sartaroshman" → set hours and prices → open the client link from **a second Telegram account** → book → check the barber notification
  5. Project structure and architecture diagram (mermaid)
- `CLAUDE.md` (short conventions; build command `./gradlew build`)
- Delete the generated `HELP.md` (replace it with the README)

## 14. Definition of done (manual checklist)

- [ ] Client can book, see and cancel a booking in both uz and ru
- [ ] Two clients cannot book the same slot
- [ ] Break, day off, time off, past times and the lead time are never offered as slots
- [ ] The barber receives new/cancelled/confirmed notifications and the attendance question
- [ ] Manual bookings block slots
- [ ] The reminder arrives at the configured time, and the "Boraman" button works
- [ ] The owner can invite and deactivate barbers and get the link + QR
- [ ] Super admin can create a shop and turn it off; clients of an inactive shop cannot book
- [ ] Restarting the app does not lose conversation state or data
- [ ] No user-facing string is hard-coded in Java; all user input is HTML-escaped
