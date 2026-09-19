package pdp.service_bron.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pdp.service_bron.IntegrationTest;
import pdp.service_bron.TestDb;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Invite;
import pdp.service_bron.domain.InviteType;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.AppUserRepository;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.InviteRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.service.InviteService.BarberJoined;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class InviteServiceIT {

    /** Telegram id 111 is the super admin in the test profile. */
    private static final long SUPER_ADMIN_TG = 111;

    @Autowired InviteService invites;
    @Autowired ShopService shops;
    @Autowired InviteRepository inviteRepository;
    @Autowired ShopRepository shopRepository;
    @Autowired BarberRepository barbers;
    @Autowired AppUserRepository users;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    AppUser admin;
    AppUser owner;
    AppUser barberUser;
    Shop shop;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        admin = user(SUPER_ADMIN_TG, "Admin");
        owner = user(2, "Owner");
        barberUser = user(3, "Aziz");
        shop = shops.create(admin, "Barber House");
    }

    private AppUser user(long telegramId, String name) {
        AppUser u = new AppUser();
        u.setTelegramId(telegramId);
        u.setFirstName(name);
        u.setPhone("+998901234567");
        return users.save(u);
    }

    @Test
    void ownerInviteMakesTheUserTheShopOwner() {
        Invite invite = invites.createOwnerInvite(admin, shop.getId());

        Shop result = invites.redeemOwner(invite.getToken(), owner);

        assertThat(result.getId()).isEqualTo(shop.getId());
        assertThat(shopRepository.findById(shop.getId()).orElseThrow().getOwnerUserId()).isEqualTo(owner.getId());
        Invite used = inviteRepository.findById(invite.getId()).orElseThrow();
        assertThat(used.getUsedBy()).isEqualTo(owner.getId());
        assertThat(used.getUsedAt()).isNotNull();
    }

    @Test
    void invitesAreValidForSevenDays() {
        Invite invite = invites.createOwnerInvite(admin, shop.getId());

        Duration validity = Duration.between(clock.instant(), invite.getExpiresAt());

        assertThat(validity).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1));
    }

    @Test
    void tokensAreUrlSafeAndFitIntoTheDeepLinkLimit() {
        Invite invite = invites.createOwnerInvite(admin, shop.getId());

        assertThat(invite.getToken()).matches("[A-Za-z0-9_-]{22}");
        String payload = InviteService.payload(invite);
        assertThat(payload).matches("o_[A-Za-z0-9_-]{22}");
        assertThat(payload.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void tokensAreUnique() {
        Invite first = invites.createOwnerInvite(admin, shop.getId());
        Invite second = invites.createOwnerInvite(admin, shop.getId());

        assertThat(first.getToken()).isNotEqualTo(second.getToken());
    }

    @Test
    void onlyASuperAdminCreatesOwnerInvites() {
        assertThatThrownBy(() -> invites.createOwnerInvite(owner, shop.getId()))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
    }

    @Test
    void anInviteWorksOnlyOnce() {
        Invite invite = invites.createOwnerInvite(admin, shop.getId());
        invites.redeemOwner(invite.getToken(), owner);

        assertThatThrownBy(() -> invites.redeemOwner(invite.getToken(), user(9, "Other")))
                .isInstanceOf(BusinessException.class).hasMessage("invite.used");
    }

    @Test
    void anExpiredInviteIsRejected() {
        Invite invite = invites.createOwnerInvite(admin, shop.getId());
        invite.setExpiresAt(clock.instant().minusSeconds(1));
        inviteRepository.save(invite);

        assertThatThrownBy(() -> invites.redeemOwner(invite.getToken(), owner))
                .isInstanceOf(BusinessException.class).hasMessage("invite.expired");
    }

    @Test
    void unknownTokensAndWrongTypesAreInvalid() {
        assertThatThrownBy(() -> invites.redeemOwner("does-not-exist", owner))
                .isInstanceOf(BusinessException.class).hasMessage("invite.invalid");

        Invite ownerInvite = invites.createOwnerInvite(admin, shop.getId());
        assertThatThrownBy(() -> invites.redeemBarber(ownerInvite.getToken(), barberUser))
                .isInstanceOf(BusinessException.class).hasMessage("invite.invalid");
    }

    @Test
    void ownerCreatesBarberInviteAndTheBarberJoins() {
        invites.redeemOwner(invites.createOwnerInvite(admin, shop.getId()).getToken(), owner);
        Invite invite = invites.createBarberInvite(owner, shop.getId());
        assertThat(invite.getType()).isEqualTo(InviteType.BARBER);
        assertThat(InviteService.payload(invite)).startsWith("b_");

        BarberJoined joined = invites.redeemBarber(invite.getToken(), barberUser);

        Barber barber = barbers.findById(joined.barber().getId()).orElseThrow();
        assertThat(barber.getShopId()).isEqualTo(shop.getId());
        assertThat(barber.getUserId()).isEqualTo(barberUser.getId());
        assertThat(barber.getDisplayName()).isEqualTo("Aziz");
        assertThat(barber.isAcceptingBookings()).as("no bookings until working hours exist").isFalse();
        assertThat(joined.owner().getId()).isEqualTo(owner.getId());
    }

    @Test
    void onlyTheOwnerOfTheShopCreatesBarberInvites() {
        assertThatThrownBy(() -> invites.createBarberInvite(barberUser, shop.getId()))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
    }

    @Test
    void barberInvitesNeedAnActiveShop() {
        invites.redeemOwner(invites.createOwnerInvite(admin, shop.getId()).getToken(), owner);
        Invite invite = invites.createBarberInvite(owner, shop.getId());
        shops.setActive(admin, shop.getId(), false);

        assertThatThrownBy(() -> invites.redeemBarber(invite.getToken(), barberUser))
                .isInstanceOf(BusinessException.class).hasMessage("invite.shop_inactive");
    }

    @Test
    void aBarberCannotJoinTheSameShopTwice() {
        invites.redeemOwner(invites.createOwnerInvite(admin, shop.getId()).getToken(), owner);
        invites.redeemBarber(invites.createBarberInvite(owner, shop.getId()).getToken(), barberUser);
        Invite second = invites.createBarberInvite(owner, shop.getId());

        assertThatThrownBy(() -> invites.redeemBarber(second.getToken(), barberUser))
                .isInstanceOf(BusinessException.class).hasMessage("invite.already_barber");
        assertThat(inviteRepository.findById(second.getId()).orElseThrow().getUsedAt())
                .as("a failed redeem must not burn the invite").isNull();
    }
}
