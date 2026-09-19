package pdp.service_bron.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;
import pdp.service_bron.config.BotProperties;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Lang;
import pdp.service_bron.repository.AppUserRepository;

import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository users;
    private final BotProperties properties;

    /** Finds the user for a Telegram account, creating it on first contact and refreshing names. */
    @Transactional
    public AppUser touch(User tg) {
        AppUser user = users.findByTelegramId(tg.getId()).orElseGet(() -> {
            AppUser created = new AppUser();
            created.setTelegramId(tg.getId());
            return created;
        });
        boolean changed = user.getId() == null;
        if (!Objects.equals(user.getFirstName(), tg.getFirstName())) {
            user.setFirstName(tg.getFirstName());
            changed = true;
        }
        if (!Objects.equals(user.getLastName(), tg.getLastName())) {
            user.setLastName(tg.getLastName());
            changed = true;
        }
        if (!Objects.equals(user.getUsername(), tg.getUserName())) {
            user.setUsername(tg.getUserName());
            changed = true;
        }
        return changed ? users.save(user) : user;
    }

    @Transactional
    public AppUser setLanguage(AppUser user, Lang lang) {
        user.setLanguage(lang.code());
        return users.save(user);
    }

    @Transactional
    public AppUser setPhone(AppUser user, String phone) {
        user.setPhone(phone);
        return users.save(user);
    }

    @Transactional
    public AppUser setLastShop(AppUser user, Long shopId) {
        user.setLastShopId(shopId);
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findById(Long id) {
        return id == null ? Optional.empty() : users.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findByTelegramId(long telegramId) {
        return users.findByTelegramId(telegramId);
    }

    public boolean isSuperAdmin(AppUser user) {
        return properties.isSuperAdmin(user.getTelegramId());
    }

    @Transactional(readOnly = true)
    public long count() {
        return users.count();
    }
}
