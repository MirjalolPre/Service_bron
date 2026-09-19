package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.config.BotProperties;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.BarberRepository;
import pdp.service_bron.repository.ShopRepository;
import pdp.service_bron.util.PhoneFormatter;
import pdp.service_bron.util.SlugUtil;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShopService {

    public static final int PAGE_SIZE = 5;

    /** Shop fields that are edited as free text. */
    public enum TextField {
        NAME(100, true), ADDRESS(200, false), LANDMARK(200, false), PHONE(30, false), DESCRIPTION(500, false);

        private final int maxLength;
        private final boolean required;

        TextField(int maxLength, boolean required) {
            this.maxLength = maxLength;
            this.required = required;
        }
    }

    /** Shop settings with a fixed set of allowed values. */
    public enum Setting {
        HORIZON(Set.of(7, 14, 30)),
        LEAD(Set.of(0, 15, 30, 60)),
        REMINDER(Set.of(0, 60, 120, 180)),
        MAX_ACTIVE(Set.of(1, 2, 3));

        private final Set<Integer> allowed;

        Setting(Set<Integer> allowed) {
            this.allowed = allowed;
        }

        public Set<Integer> allowed() {
            return allowed;
        }
    }

    private final ShopRepository shops;
    private final BarberRepository barbers;
    private final BotProperties properties;
    private final AccessService access;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Optional<Shop> find(Long id) {
        return id == null ? Optional.empty() : shops.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Shop> findBySlug(String slug) {
        return shops.findBySlug(slug);
    }

    /** Active shops, 5 per page, sorted by name. */
    @Transactional(readOnly = true)
    public Page<Shop> listActive(int page) {
        return shops.findByActiveTrue(PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("name").ascending()));
    }

    /** Search among active shops by name. */
    @Transactional(readOnly = true)
    public Page<Shop> search(String query, int page) {
        return shops.findByActiveTrueAndNameContainingIgnoreCase(query.trim(),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("name").ascending()));
    }

    /** All shops (for the super admin), 5 per page, newest first. */
    @Transactional(readOnly = true)
    public Page<Shop> listAll(int page) {
        return shops.findAll(PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("id").descending()));
    }

    @Transactional(readOnly = true)
    public long barberCount(Long shopId) {
        return barbers.countByShopId(shopId);
    }

    // ------------------------------------------------------------------ super admin

    /** Creates a shop. The slug is the transliterated name plus a 4-character suffix. Super admin only. */
    @Transactional
    public Shop create(AppUser actor, String name) {
        if (!access.isSuperAdmin(actor)) {
            throw new BusinessException("error.forbidden");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > TextField.NAME.maxLength) {
            throw new BusinessException("shop.name_invalid");
        }
        Shop shop = new Shop();
        shop.setName(trimmed);
        shop.setTimezone(properties.defaultTimezone());
        String slug;
        do {
            slug = SlugUtil.withSuffix(trimmed);
        } while (shops.existsBySlug(slug));
        shop.setSlug(slug);
        return shops.save(shop);
    }

    /** Turns a shop on or off (subscription). Super admin only. */
    @Transactional
    public Shop setActive(AppUser actor, Long shopId, boolean active) {
        if (!access.isSuperAdmin(actor)) {
            throw new BusinessException("error.forbidden");
        }
        Shop shop = require(shopId);
        shop.setActive(active);
        return shops.save(shop);
    }

    // ------------------------------------------------------------------ owner edits

    @Transactional
    public Shop updateText(AppUser actor, Long shopId, TextField field, String rawValue) {
        Shop shop = requireEditable(actor, shopId);
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            if (field.required) {
                throw new BusinessException("shop.name_invalid");
            }
            value = null;
        } else if (value.length() > field.maxLength) {
            throw new BusinessException("shop.text_too_long", field.maxLength);
        }
        if (field == TextField.PHONE && value != null) {
            value = PhoneFormatter.normalize(value).orElseThrow(() -> new BusinessException("phone.invalid_typed"));
        }
        switch (field) {
            case NAME -> shop.setName(value);
            case ADDRESS -> shop.setAddress(value);
            case LANDMARK -> shop.setLandmark(value);
            case PHONE -> shop.setPhone(value);
            case DESCRIPTION -> shop.setDescription(value);
        }
        return shops.save(shop);
    }

    @Transactional
    public Shop updateLocation(AppUser actor, Long shopId, Double latitude, Double longitude) {
        Shop shop = requireEditable(actor, shopId);
        shop.setLatitude(latitude);
        shop.setLongitude(longitude);
        return shops.save(shop);
    }

    @Transactional
    public Shop updatePhoto(AppUser actor, Long shopId, String fileId) {
        Shop shop = requireEditable(actor, shopId);
        shop.setPhotoFileId(fileId);
        return shops.save(shop);
    }

    @Transactional
    public Shop updateSetting(AppUser actor, Long shopId, Setting setting, int value) {
        Shop shop = requireEditable(actor, shopId);
        if (!setting.allowed.contains(value)) {
            throw new BusinessException("error.invalid_value");
        }
        switch (setting) {
            case HORIZON -> shop.setBookingHorizonDays(value);
            case LEAD -> shop.setMinLeadMinutes(value);
            case REMINDER -> shop.setReminderMinutesBefore(value);
            case MAX_ACTIVE -> shop.setMaxActiveBookingsPerClient(value);
        }
        return shops.save(shop);
    }

    /** Links the shop to its owner (used when an owner invite is redeemed). */
    @Transactional
    public Shop assignOwner(Long shopId, Long ownerUserId) {
        Shop shop = require(shopId);
        shop.setOwnerUserId(ownerUserId);
        return shops.save(shop);
    }

    private Shop require(Long id) {
        return shops.findById(id).orElseThrow(() -> new BusinessException("error.not_found"));
    }

    private Shop requireEditable(AppUser actor, Long shopId) {
        if (!access.isOwnerOfShop(actor, shopId) && !access.isSuperAdmin(actor)) {
            throw new BusinessException("error.forbidden");
        }
        return require(shopId);
    }
}
