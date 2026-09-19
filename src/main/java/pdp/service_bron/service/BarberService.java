package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Barber;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.WorkingHoursRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BarberService {

    public static final Set<Integer> SLOT_MINUTES = Set.of(15, 20, 30, 45, 60, 90);
    public static final List<Integer> SLOT_OPTIONS = List.of(15, 20, 30, 45, 60, 90);
    private static final int MAX_NAME = 60;
    private static final int MAX_BIO = 300;

    private final BarberRepository barbers;
    private final WorkingHoursRepository workingHours;
    private final AccessService access;

    @Transactional(readOnly = true)
    public Optional<Barber> find(Long id) {
        return id == null ? Optional.empty() : barbers.findById(id);
    }

    /** Active barbers of a shop in display order. */
    @Transactional(readOnly = true)
    public List<Barber> activeInShop(Long shopId) {
        return barbers.findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(shopId);
    }

    /** All barbers of a shop, including deactivated ones (owner view). */
    @Transactional(readOnly = true)
    public List<Barber> allInShop(Long shopId) {
        return barbers.findByShopIdOrderBySortOrderAscIdAsc(shopId);
    }

    @Transactional(readOnly = true)
    public boolean hasWorkingHours(Long barberId) {
        return workingHours.countByBarberId(barberId) > 0;
    }

    /**
     * Creates (or re-activates) the barber row of a user in a shop. New barbers do not accept bookings until
     * they have working hours.
     */
    @Transactional
    public Barber create(Shop shop, AppUser user, String displayName) {
        Barber existing = barbers.findByShopIdAndUserId(shop.getId(), user.getId()).orElse(null);
        if (existing != null) {
            if (existing.isActive()) {
                throw new BusinessException("invite.already_barber");
            }
            existing.setActive(true);
            return barbers.save(existing);
        }
        Barber barber = new Barber();
        barber.setShopId(shop.getId());
        barber.setUserId(user.getId());
        barber.setDisplayName(cleanName(displayName, user.displayName()));
        barber.setAcceptingBookings(false);
        barber.setSortOrder(barbers.findByShopIdOrderBySortOrderAscIdAsc(shop.getId()).stream()
                .mapToInt(Barber::getSortOrder).max().orElse(0) + 1);
        return barbers.save(barber);
    }

    @Transactional
    public Barber rename(AppUser actor, Long barberId, String name) {
        Barber barber = requireManageable(actor, barberId);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new BusinessException("barber.name_invalid", MAX_NAME);
        }
        barber.setDisplayName(trimmed);
        return barbers.save(barber);
    }

    @Transactional
    public Barber setBio(AppUser actor, Long barberId, String bio) {
        Barber barber = requireManageable(actor, barberId);
        String trimmed = bio == null ? "" : bio.trim();
        if (trimmed.length() > MAX_BIO) {
            throw new BusinessException("barber.bio_too_long", MAX_BIO);
        }
        barber.setBio(trimmed.isEmpty() ? null : trimmed);
        return barbers.save(barber);
    }

    @Transactional
    public Barber setPhoto(AppUser actor, Long barberId, String fileId) {
        Barber barber = requireManageable(actor, barberId);
        barber.setPhotoFileId(fileId);
        return barbers.save(barber);
    }

    @Transactional
    public Barber setSlotMinutes(AppUser actor, Long barberId, int minutes) {
        Barber barber = requireManageable(actor, barberId);
        if (!SLOT_MINUTES.contains(minutes)) {
            throw new BusinessException("error.invalid_value");
        }
        barber.setSlotMinutes(minutes);
        return barbers.save(barber);
    }

    /** Turns booking on or off. It cannot be turned on before working hours exist. */
    @Transactional
    public Barber setAccepting(AppUser actor, Long barberId, boolean accepting) {
        Barber barber = requireManageable(actor, barberId);
        if (accepting && !hasWorkingHours(barberId)) {
            throw new BusinessException("barber.hours_required");
        }
        barber.setAcceptingBookings(accepting);
        return barbers.save(barber);
    }

    /** Owner only. Existing bookings must be handled by the caller before deactivating. */
    @Transactional
    public Barber setActive(AppUser actor, Long barberId, boolean active) {
        Barber barber = requireOwnerOf(actor, barberId);
        barber.setActive(active);
        return barbers.save(barber);
    }

    /** Owner only: moves a barber up (-1) or down (+1) in the shop's list. */
    @Transactional
    public void move(AppUser actor, Long barberId, int direction) {
        Barber barber = requireOwnerOf(actor, barberId);
        List<Barber> all = barbers.findByShopIdOrderBySortOrderAscIdAsc(barber.getShopId());
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(barberId)) {
                index = i;
            }
        }
        int target = index + (direction < 0 ? -1 : 1);
        if (index < 0 || target < 0 || target >= all.size()) {
            return;
        }
        // Re-number everything so equal sort orders can never make a move a no-op.
        Barber other = all.remove(target);
        all.add(index, other);
        for (int i = 0; i < all.size(); i++) {
            all.get(i).setSortOrder(i + 1);
        }
        barbers.saveAll(all);
    }

    private Barber requireManageable(AppUser actor, Long barberId) {
        Barber barber = barbers.findById(barberId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!access.canManageBarber(actor, barberId)) {
            throw new BusinessException("error.forbidden");
        }
        return barber;
    }

    private Barber requireOwnerOf(AppUser actor, Long barberId) {
        Barber barber = barbers.findById(barberId).orElseThrow(() -> new BusinessException("error.not_found"));
        if (!access.isOwnerOfShop(actor, barber.getShopId())) {
            throw new BusinessException("error.forbidden");
        }
        return barber;
    }

    private static String cleanName(String name, String fallback) {
        String trimmed = name == null ? "" : name.trim();
        String result = trimmed.isEmpty() ? fallback : trimmed;
        return result.length() > MAX_NAME ? result.substring(0, MAX_NAME) : result;
    }
}
