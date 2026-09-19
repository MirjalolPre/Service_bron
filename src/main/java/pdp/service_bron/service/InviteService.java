package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Invite;
import pdp.service_bron.domain.InviteType;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.InviteRepository;
import pdp.service_bron.repository.ShopRepository;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** One-time owner and barber invites, valid for 7 days. */
@Service
@RequiredArgsConstructor
public class InviteService {

    public static final Duration VALIDITY = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Result of redeeming a barber invite. */
    public record BarberJoined(Barber barber, Shop shop, AppUser owner) {
    }

    private final InviteRepository invites;
    private final ShopRepository shops;
    private final BarberService barberService;
    private final ShopService shopService;
    private final AccessService access;
    private final UserService users;
    private final Clock clock;

    /** Deep-link payload for an invite, e.g. {@code o_Ab3...}. */
    public static String payload(Invite invite) {
        return invite.getType().prefix() + "_" + invite.getToken();
    }

    /** Super admin creates the one-time owner invite of a shop. */
    @Transactional
    public Invite createOwnerInvite(AppUser actor, Long shopId) {
        if (!access.isSuperAdmin(actor)) {
            throw new BusinessException("error.forbidden");
        }
        return create(InviteType.OWNER, requireShop(shopId), actor);
    }

    /** The shop owner creates a one-time barber invite. */
    @Transactional
    public Invite createBarberInvite(AppUser actor, Long shopId) {
        if (!access.isOwnerOfShop(actor, shopId)) {
            throw new BusinessException("error.forbidden");
        }
        return create(InviteType.BARBER, requireShop(shopId), actor);
    }

    /** Makes the user the owner of the invite's shop. */
    @Transactional
    public Shop redeemOwner(String token, AppUser user) {
        Invite invite = requireUsable(token, InviteType.OWNER);
        Shop shop = requireShop(invite.getShopId());
        shopService.assignOwner(shop.getId(), user.getId());
        markUsed(invite, user);
        return shop;
    }

    /** Creates the barber row for the user in the invite's shop. */
    @Transactional
    public BarberJoined redeemBarber(String token, AppUser user) {
        Invite invite = requireUsable(token, InviteType.BARBER);
        Shop shop = requireShop(invite.getShopId());
        if (!shop.isActive()) {
            throw new BusinessException("invite.shop_inactive");
        }
        Barber barber = barberService.create(shop, user, user.getFirstName());
        markUsed(invite, user);
        AppUser owner = users.findById(shop.getOwnerUserId()).orElse(null);
        return new BarberJoined(barber, shop, owner);
    }

    private Invite create(InviteType type, Shop shop, AppUser creator) {
        Invite invite = new Invite();
        invite.setToken(newToken());
        invite.setType(type);
        invite.setShopId(shop.getId());
        invite.setCreatedBy(creator.getId());
        invite.setExpiresAt(clock.instant().plus(VALIDITY));
        return invites.save(invite);
    }

    private Invite requireUsable(String token, InviteType type) {
        Invite invite = invites.findByToken(token).orElseThrow(() -> new BusinessException("invite.invalid"));
        if (invite.getType() != type) {
            throw new BusinessException("invite.invalid");
        }
        if (invite.getUsedAt() != null) {
            throw new BusinessException("invite.used");
        }
        Instant now = clock.instant();
        if (!invite.getExpiresAt().isAfter(now)) {
            throw new BusinessException("invite.expired");
        }
        return invite;
    }

    private void markUsed(Invite invite, AppUser user) {
        invite.setUsedBy(user.getId());
        invite.setUsedAt(clock.instant());
        invites.save(invite);
    }

    private Shop requireShop(Long id) {
        return shops.findById(id).orElseThrow(() -> new BusinessException("error.not_found"));
    }

    /** 16 random bytes as URL-safe base64 (22 characters, only A-Za-z0-9_-). */
    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
