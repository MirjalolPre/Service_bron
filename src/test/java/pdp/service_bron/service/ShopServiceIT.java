package pdp.service_bron.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import pdp.service_bron.IntegrationTest;
import pdp.service_bron.TestDb;
import pdp.service_bron.domain.AppUser;
import pdp.service_bron.domain.Shop;
import pdp.service_bron.repository.AppUserRepository;
import pdp.service_bron.service.ShopService.Setting;
import pdp.service_bron.service.ShopService.TextField;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class ShopServiceIT {

    @Autowired ShopService shops;
    @Autowired AppUserRepository users;
    @Autowired JdbcClient jdbc;

    AppUser admin;
    AppUser owner;
    AppUser stranger;
    Shop shop;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        admin = user(111, "Admin");      // super admin in the test profile
        owner = user(2, "Owner");
        stranger = user(3, "Stranger");
        shop = shops.create(admin, "Barber House");
        shops.assignOwner(shop.getId(), owner.getId());
    }

    private AppUser user(long telegramId, String name) {
        AppUser u = new AppUser();
        u.setTelegramId(telegramId);
        u.setFirstName(name);
        return users.save(u);
    }

    @Test
    void creatingAShopBuildsASlugWithARandomSuffix() {
        assertThat(shop.getSlug()).matches("barber-house-[a-z0-9]{4}");
        assertThat(shop.isActive()).isTrue();
        assertThat(shop.getTimezone()).isEqualTo("Asia/Tashkent");
        assertThat(shops.create(admin, "Barber House").getSlug()).isNotEqualTo(shop.getSlug());
    }

    @Test
    void onlyASuperAdminCreatesOrSwitchesShops() {
        assertThatThrownBy(() -> shops.create(owner, "My Shop"))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
        assertThatThrownBy(() -> shops.setActive(owner, shop.getId(), false))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
    }

    @Test
    void shopNamesMustNotBeBlank() {
        assertThatThrownBy(() -> shops.create(admin, "   ")).isInstanceOf(BusinessException.class);
    }

    @Test
    void switchingTheSubscriptionOffHidesTheShopFromClients() {
        assertThat(shops.listActive(0).getContent()).extracting(Shop::getId).contains(shop.getId());

        shops.setActive(admin, shop.getId(), false);

        assertThat(shops.listActive(0).getContent()).isEmpty();
        assertThat(shops.find(shop.getId()).orElseThrow().isActive()).isFalse();
        assertThat(shops.listAll(0).getContent()).hasSize(1);
    }

    @Test
    void shopListsHaveFiveShopsPerPageAndSearchWorks() {
        for (int i = 0; i < 6; i++) {
            shops.create(admin, "Salon " + i);
        }

        Page<Shop> first = shops.listActive(0);
        Page<Shop> second = shops.listActive(1);

        assertThat(first.getContent()).hasSize(5);
        assertThat(second.getContent()).hasSize(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(shops.search("salon", 0).getTotalElements()).isEqualTo(6);
        assertThat(shops.search("BARBER", 0).getContent()).extracting(Shop::getName).containsExactly("Barber House");
        assertThat(shops.search("nothing", 0).isEmpty()).isTrue();
    }

    @Test
    void theOwnerEditsTheirShopButStrangersCannot() {
        shops.updateText(owner, shop.getId(), TextField.ADDRESS, "  Chilonzor 9  ");

        assertThat(shops.find(shop.getId()).orElseThrow().getAddress()).isEqualTo("Chilonzor 9");
        assertThatThrownBy(() -> shops.updateText(stranger, shop.getId(), TextField.ADDRESS, "Hacked"))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
    }

    @Test
    void optionalFieldsCanBeCleared() {
        shops.updateText(owner, shop.getId(), TextField.LANDMARK, "near metro");
        shops.updateText(owner, shop.getId(), TextField.LANDMARK, "");

        assertThat(shops.find(shop.getId()).orElseThrow().getLandmark()).isNull();
    }

    @Test
    void theNameCannotBeCleared() {
        assertThatThrownBy(() -> shops.updateText(owner, shop.getId(), TextField.NAME, " "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void phoneNumbersAreNormalized() {
        shops.updateText(owner, shop.getId(), TextField.PHONE, "90 123 45 67");

        assertThat(shops.find(shop.getId()).orElseThrow().getPhone()).isEqualTo("+998901234567");
        assertThatThrownBy(() -> shops.updateText(owner, shop.getId(), TextField.PHONE, "abc"))
                .isInstanceOf(BusinessException.class).hasMessage("phone.invalid_typed");
    }

    @Test
    void overlyLongTextIsRejected() {
        assertThatThrownBy(() -> shops.updateText(owner, shop.getId(), TextField.ADDRESS, "x".repeat(300)))
                .isInstanceOf(BusinessException.class).hasMessage("shop.text_too_long");
    }

    @Test
    void settingsAcceptOnlyTheAllowedValues() {
        shops.updateSetting(owner, shop.getId(), Setting.HORIZON, 14);
        shops.updateSetting(owner, shop.getId(), Setting.LEAD, 60);
        shops.updateSetting(owner, shop.getId(), Setting.REMINDER, 0);
        shops.updateSetting(owner, shop.getId(), Setting.MAX_ACTIVE, 3);

        Shop saved = shops.find(shop.getId()).orElseThrow();
        assertThat(saved.getBookingHorizonDays()).isEqualTo(14);
        assertThat(saved.getMinLeadMinutes()).isEqualTo(60);
        assertThat(saved.getReminderMinutesBefore()).isZero();
        assertThat(saved.getMaxActiveBookingsPerClient()).isEqualTo(3);

        assertThatThrownBy(() -> shops.updateSetting(owner, shop.getId(), Setting.HORIZON, 10))
                .isInstanceOf(BusinessException.class).hasMessage("error.invalid_value");
        assertThatThrownBy(() -> shops.updateSetting(owner, shop.getId(), Setting.MAX_ACTIVE, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> shops.updateSetting(stranger, shop.getId(), Setting.HORIZON, 7))
                .isInstanceOf(BusinessException.class).hasMessage("error.forbidden");
    }

    @Test
    void locationAndPhotoCanBeSet() {
        shops.updateLocation(owner, shop.getId(), 41.311, 69.279);
        shops.updatePhoto(owner, shop.getId(), "file-id-1");

        Shop saved = shops.find(shop.getId()).orElseThrow();
        assertThat(saved.hasLocation()).isTrue();
        assertThat(saved.getPhotoFileId()).isEqualTo("file-id-1");
    }
}
