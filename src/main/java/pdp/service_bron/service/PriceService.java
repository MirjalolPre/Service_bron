package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.PriceItem;
import pdp.service_bron.repository.PriceItemRepository;
import pdp.service_bron.util.MoneyFormatter;

import java.util.List;
import java.util.Optional;

/** A barber's price list. It is shown to clients for information only; clients never choose a service. */
@Service
@RequiredArgsConstructor
public class PriceService {

    public static final int MAX_ITEMS = 20;
    public static final int MAX_NAME = 60;

    private final PriceItemRepository items;
    private final AccessService access;

    @Transactional(readOnly = true)
    public List<PriceItem> list(Long barberId) {
        return items.findByBarberIdAndActiveTrueOrderBySortOrderAscIdAsc(barberId);
    }

    @Transactional(readOnly = true)
    public Optional<PriceItem> find(Long itemId) {
        return items.findById(itemId).filter(PriceItem::isActive);
    }

    @Transactional
    public PriceItem add(AppUser actor, Long barberId, String name, long price) {
        requireManageable(actor, barberId);
        String cleanName = cleanName(name);
        checkPrice(price);
        List<PriceItem> current = list(barberId);
        if (current.size() >= MAX_ITEMS) {
            throw new BusinessException("price.limit", MAX_ITEMS);
        }
        PriceItem item = new PriceItem();
        item.setBarberId(barberId);
        item.setName(cleanName);
        item.setPrice(price);
        item.setSortOrder(current.stream().mapToInt(PriceItem::getSortOrder).max().orElse(0) + 1);
        return items.save(item);
    }

    @Transactional
    public PriceItem rename(AppUser actor, Long itemId, String name) {
        PriceItem item = requireItem(actor, itemId);
        item.setName(cleanName(name));
        return items.save(item);
    }

    @Transactional
    public PriceItem changePrice(AppUser actor, Long itemId, long price) {
        PriceItem item = requireItem(actor, itemId);
        checkPrice(price);
        item.setPrice(price);
        return items.save(item);
    }

    @Transactional
    public void delete(AppUser actor, Long itemId) {
        PriceItem item = requireItem(actor, itemId);
        item.setActive(false);
        items.save(item);
    }

    /** Moves an item up (-1) or down (+1). */
    @Transactional
    public void move(AppUser actor, Long itemId, int direction) {
        PriceItem item = requireItem(actor, itemId);
        List<PriceItem> all = new java.util.ArrayList<>(list(item.getBarberId()));
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(itemId)) {
                index = i;
            }
        }
        int target = index + (direction < 0 ? -1 : 1);
        if (index < 0 || target < 0 || target >= all.size()) {
            return;
        }
        PriceItem moved = all.remove(index);
        all.add(target, moved);
        for (int i = 0; i < all.size(); i++) {
            all.get(i).setSortOrder(i + 1);
        }
        items.saveAll(all);
    }

    private PriceItem requireItem(AppUser actor, Long itemId) {
        PriceItem item = find(itemId).orElseThrow(() -> new BusinessException("error.not_found"));
        requireManageable(actor, item.getBarberId());
        return item;
    }

    private void requireManageable(AppUser actor, Long barberId) {
        if (!access.canManageBarber(actor, barberId)) {
            throw new BusinessException("error.forbidden");
        }
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new BusinessException("price.name_invalid", MAX_NAME);
        }
        return trimmed;
    }

    private static void checkPrice(long price) {
        if (price < 0 || price > MoneyFormatter.MAX_PRICE) {
            throw new BusinessException("price.value_invalid");
        }
    }
}
