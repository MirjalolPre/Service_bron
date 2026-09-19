package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.ShopRepository;

import java.util.List;
import java.util.Optional;

/** Role lookups and permission checks. Every callback must go through these; ids in callback data are never trusted. */
@Service
@RequiredArgsConstructor
public class AccessService {

    private final BarberRepository barbers;
    private final ShopRepository shops;
    private final UserService users;

    /** What a person is allowed to be. One person can be client, barber and owner at once. */
    public record Roles(boolean superAdmin, Shop ownedShop, Barber barber) {
        public boolean isOwner() {
            return ownedShop != null;
        }

        public boolean isBarber() {
            return barber != null;
        }

        public boolean isStaff() {
            return isOwner() || isBarber();
        }
    }

    @Transactional(readOnly = true)
    public Roles roles(AppUser user) {
        return new Roles(users.isSuperAdmin(user), ownedShop(user).orElse(null), barberOf(user).orElse(null));
    }

    /** The barber row of this user (the first active one when the user works in several shops). */
    @Transactional(readOnly = true)
    public Optional<Barber> barberOf(AppUser user) {
        List<Barber> rows = barbers.findByUserIdAndActiveTrueOrderByIdAsc(user.getId());
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Shop> ownedShop(AppUser user) {
        return shops.findByOwnerUserIdOrderByIdAsc(user.getId()).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean isOwnerOfShop(AppUser user, Long shopId) {
        return shopId != null && shops.findById(shopId)
                .map(s -> user.getId().equals(s.getOwnerUserId()))
                .orElse(false);
    }

    /** True when the user is this barber, or the owner of the barber's shop. */
    @Transactional(readOnly = true)
    public boolean canManageBarber(AppUser user, Long barberId) {
        return barbers.findById(barberId)
                .map(b -> user.getId().equals(b.getUserId()) || isOwnerOfShop(user, b.getShopId()))
                .orElse(false);
    }

    /** The barber row this user may act as: their own row only. */
    @Transactional(readOnly = true)
    public Barber requireOwnBarber(AppUser user, Long barberId) {
        Barber barber = barbers.findById(barberId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!user.getId().equals(barber.getUserId())) {
            throw new BusinessException("error.forbidden");
        }
        return barber;
    }

    public boolean isSuperAdmin(AppUser user) {
        return users.isSuperAdmin(user);
    }
}
