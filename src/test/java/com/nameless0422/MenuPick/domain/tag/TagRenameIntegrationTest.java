package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.pick.*;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.user.*;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TagService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TagRenameIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TagService service;
    @Autowired private TagRepository tags;
    @Autowired private UserRepository users;
    @Autowired private MenuRepository menus;
    @Autowired private PickPresetRepository presets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;
    @Autowired private PlatformTransactionManager transactionManager;
    private User user;

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = users.save(User.builder().email("rename-" + suffix + "@test.com").nickname("rename" + suffix).build());
    }

    @AfterEach void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM pick_presets WHERE user_id=?", user.getId());
            jdbc.update("DELETE FROM user_default_excluded_tags WHERE user_id=?", user.getId());
            jdbc.update("DELETE mt FROM menu_tags mt JOIN menus m ON m.id=mt.menu_id WHERE m.user_id=?", user.getId());
            menus.deleteAllByUserId(user.getId());
            tags.deleteAllByUserId(user.getId());
            users.deleteById(user.getId());
        });
    }

    private Tag tag(String name) { return tags.save(Tag.builder().user(user).name(name).build()); }

    @Test void renamePreservesIdentityAndEveryFilterReference() {
        Tag original = tag("견과류오타");
        var createdAt = tags.findById(original.getId()).orElseThrow().getCreatedAt();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Menu menu = Menu.builder().user(user).name("국수").build();
            menu.addTag(tags.findById(original.getId()).orElseThrow());
            menus.saveAndFlush(menu);
            tags.insertDefaultExcludedTag(user.getId(), original.getId());
            PickPreset preset = PickPreset.builder().user(user).name("점심").build();
            preset.replaceConditions("점심", null, Set.of(), Set.of(PickPresetTag.exclude(original.getId())));
            presets.saveAndFlush(preset);
        });
        var renamed = service.renameTag(user.getId(), original.getId(), new TagRequest.Rename("  견과류  ", 0L));
        assertThat(renamed.id()).isEqualTo(original.getId());
        assertThat(renamed.name()).isEqualTo("견과류");
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(renamed.createdAt()).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_tags WHERE tag_id=?", Integer.class, original.getId())).isEqualTo(1);
        assertThat(tags.findDefaultExcludedTagIds(user.getId())).containsExactly(original.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pick_preset_tags WHERE tag_id=?", Integer.class, original.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT needs_review FROM pick_presets WHERE user_id=?", Boolean.class, user.getId())).isFalse();
        assertThat(service.searchTags(user.getId(), "견과류")).extracting(t -> t.name()).containsExactly("견과류");
        assertThat(service.renameTag(user.getId(), original.getId(), new TagRequest.Rename("견과류", 1L)).version()).isEqualTo(1);
        assertThat(service.renameTag(user.getId(), original.getId(), new TagRequest.Rename("기피 재료", 1L)).version()).isEqualTo(2);
    }

    @Test void duplicateNameIncludingDatabaseCollationDoesNotMergeTags() {
        Tag first = tag("first"), second = tag("second");
        assertThatThrownBy(() -> service.renameTag(user.getId(), first.getId(), new TagRequest.Rename("SECOND", 0L)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.TAG_DUPLICATE);
        assertThat(tags.findById(first.getId()).orElseThrow().getName()).isEqualTo("first");
        assertThat(tags.findById(second.getId())).isPresent();
    }

    @Test void ownerScopeAndStaleVersionAreEnforced() {
        Tag original = tag("원래 이름");
        assertThatThrownBy(() -> service.renameTag(-1L, original.getId(), new TagRequest.Rename("타인 변경", 0L)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);
        assertThatThrownBy(() -> service.renameTag(user.getId(), Long.MAX_VALUE, new TagRequest.Rename("없는 태그", 0L)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);
        service.renameTag(user.getId(), original.getId(), new TagRequest.Rename("다른 탭 변경", 0L));
        assertThatThrownBy(() -> service.renameTag(user.getId(), original.getId(), new TagRequest.Rename("오래된 화면", 0L)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(tags.findById(original.getId()).orElseThrow().getName()).isEqualTo("다른 탭 변경");
    }

    @Test void simultaneousUpdatesHaveOneWinner() throws Exception {
        Tag original = tag("동시 수정 전");
        CyclicBarrier bothRead = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String name : List.of("첫 수정", "두 번째 수정")) {
                results.add(workers.submit(() -> {
                    var em = emf.createEntityManager();
                    try {
                        em.getTransaction().begin();
                        Tag loaded = em.find(Tag.class, original.getId());
                        bothRead.await(10, TimeUnit.SECONDS);
                        loaded.rename(name);
                        em.getTransaction().commit();
                        return true;
                    } catch (jakarta.persistence.OptimisticLockException | jakarta.persistence.RollbackException e) {
                        if (em.getTransaction().isActive()) em.getTransaction().rollback();
                        assertThat(hasOptimisticCause(e)).isTrue();
                        return false;
                    } finally { em.close(); }
                }));
            }
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(tags.findById(original.getId()).orElseThrow().getVersion()).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }

    private static boolean hasOptimisticCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof jakarta.persistence.OptimisticLockException) return true;
        }
        return false;
    }

    @Test void migrationPreservesExistingRowsWithVersionZero() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/V20__tag_version.sql")) {
            migration = new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("ALTER TABLE tags", "ALTER TABLE tag_version_legacy");
        }
        String sql = migration;
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE tag_version_legacy (id BIGINT PRIMARY KEY, name VARCHAR(50)) ENGINE=InnoDB");
                try {
                    statement.executeUpdate("INSERT INTO tag_version_legacy VALUES (1, '기존 태그')");
                    statement.execute(sql);
                    try (var rows = statement.executeQuery("SELECT name, version FROM tag_version_legacy")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("기존 태그");
                        assertThat(rows.getLong(2)).isZero();
                    }
                } finally { statement.execute("DROP TABLE tag_version_legacy"); }
            }
            return null;
        });
    }
}
