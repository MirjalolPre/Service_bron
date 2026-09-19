create extension if not exists btree_gist;

create table app_user (
    id            bigserial primary key,
    telegram_id   bigint not null unique,
    first_name    text,
    last_name     text,
    username      text,
    phone         text,
    language      varchar(2) not null default 'uz',
    last_shop_id  bigint null,
    created_at    timestamptz not null default now()
);

create table shop (
    id                             bigserial primary key,
    slug                           text not null unique,
    name                           text not null,
    address                        text,
    landmark                       text,
    latitude                       double precision,
    longitude                      double precision,
    phone                          text,
    photo_file_id                  text,
    description                    text,
    timezone                       text not null default 'Asia/Tashkent',
    booking_horizon_days           int not null default 7,
    min_lead_minutes               int not null default 30,
    reminder_minutes_before        int not null default 120,
    max_active_bookings_per_client int not null default 2,
    active                         boolean not null default true,
    owner_user_id                  bigint references app_user (id),
    created_at                     timestamptz not null default now()
);

alter table app_user add constraint fk_app_user_last_shop foreign key (last_shop_id) references shop (id);

create table barber (
    id                 bigserial primary key,
    shop_id            bigint not null references shop (id),
    user_id            bigint references app_user (id),
    display_name       text not null,
    bio                text,
    photo_file_id      text,
    slot_minutes       int not null default 30,
    accepting_bookings boolean not null default true,
    active             boolean not null default true,
    sort_order         int not null default 0,
    created_at         timestamptz not null default now(),
    unique (shop_id, user_id)
);

create table price_item (
    id         bigserial primary key,
    barber_id  bigint not null references barber (id),
    name       text not null,
    price      bigint not null check (price >= 0),
    sort_order int not null default 0,
    active     boolean not null default true
);

create table working_hours (
    id          bigserial primary key,
    barber_id   bigint not null references barber (id),
    day_of_week smallint not null check (day_of_week between 1 and 7),
    day_off     boolean not null default false,
    start_time  time,
    end_time    time,
    break_start time,
    break_end   time,
    unique (barber_id, day_of_week)
);

create table time_off (
    id        bigserial primary key,
    barber_id bigint not null references barber (id),
    start_at  timestamptz not null,
    end_at    timestamptz not null,
    reason    text,
    check (end_at > start_at)
);

create table booking (
    id                bigserial primary key,
    shop_id           bigint not null references shop (id),
    barber_id         bigint not null references barber (id),
    client_user_id    bigint references app_user (id),
    client_name       text,
    client_phone      text,
    start_at          timestamptz not null,
    end_at            timestamptz not null,
    status            varchar(24) not null,
    source            varchar(12) not null,
    note              text,
    reminder_sent     boolean not null default false,
    attendance_asked  boolean not null default false,
    client_confirmed  boolean not null default false,
    cancel_reason     text,
    cancelled_at      timestamptz,
    created_at        timestamptz not null default now(),
    check (end_at > start_at),
    -- Prevents double booking even under concurrent requests.
    constraint booking_no_overlap exclude using gist (
        barber_id with =,
        tstzrange(start_at, end_at) with &&
    ) where (status = 'BOOKED')
);

create index idx_booking_barber_start on booking (barber_id, start_at);
create index idx_booking_client_status on booking (client_user_id, status);
create index idx_booking_status_start on booking (status, start_at);
create index idx_time_off_barber on time_off (barber_id, start_at);

create table invite (
    id         bigserial primary key,
    token      text not null unique,
    type       varchar(10) not null,
    shop_id    bigint not null references shop (id),
    created_by bigint references app_user (id),
    expires_at timestamptz not null,
    used_by    bigint references app_user (id),
    used_at    timestamptz
);

create table bot_session (
    telegram_id bigint primary key,
    state       varchar(64) not null,
    data        jsonb not null default '{}'::jsonb,
    updated_at  timestamptz not null default now()
);
