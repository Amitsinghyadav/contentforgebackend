package com.ozo.contentforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.repository.PlanRepository;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.security.UserRole;
import com.ozo.contentforge.service.security.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Startup data setup, safe to run on every start:
 * <ol>
 *   <li>moves a single-brand database to brand workspaces (existing content goes into one "legacy" brand),</li>
 *   <li>turns old client logins into team members and makes sure an admin exists,</li>
 *   <li>seeds the Standard and Premium plans.</li>
 * </ol>
 * Runs after Hibernate has updated the schema.
 */
@Service
public class DataInitializerService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializerService.class);

    /** Tables of {@link com.ozo.contentforge.tenancy.BrandOwnedEntity} entities. */
    private static final List<String> BRAND_TABLES = List.of(
            "brand_profiles", "content_calendar_events", "content_approvals", "generated_assets",
            "daily_generation_batches", "instagram_publications", "generation_usage", "presenter_profiles",
            "voice_library", "video_renders", "carousel_slides", "content_posts", "master_settings",
            "content_scenes", "media_jobs", "video_avatars", "story_frames");

    /** Unique columns that are now unique per brand. */
    private static final Map<String, String> PER_BRAND_UNIQUE = Map.of(
            "master_settings", "setting_key",
            "daily_generation_batches", "target_date",
            "content_posts", "idempotency_key");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final PasswordService passwordService;

    @Value("${contentforge.bootstrap.admin-email:}")
    private String bootstrapAdminEmail;

    @Value("${contentforge.bootstrap.admin-password:}")
    private String bootstrapAdminPassword;

    public DataInitializerService(JdbcTemplate jdbc, ObjectMapper objectMapper, PlanRepository planRepository,
                                  UserRepository userRepository, PasswordService passwordService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.passwordService = passwordService;
    }

    @Override
    public void run(String... args) {
        migrateToBrandWorkspaces();
        dropOutdatedEnumChecks();
        migrateUsers();
        migratePlans();
    }

    // =========================================================================
    // Brand workspaces
    // =========================================================================

    private void migrateToBrandWorkspaces() {
        jdbc.update("UPDATE master_settings SET setting_key = ? WHERE setting_key = 'GLOBAL_CONFIG'",
                MasterSettingsService.SETTINGS_KEY);

        List<String> tables = BRAND_TABLES.stream().filter(this::tableExists).toList();
        for (String table : tables) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS brand_id BIGINT");
        }

        long orphanRows = tables.stream()
                .mapToLong(t -> count("SELECT COUNT(*) FROM " + t + " WHERE brand_id IS NULL"))
                .sum();
        if (orphanRows > 0) {
            long legacyBrandId = createLegacyBrand();
            for (String table : tables) {
                int moved = jdbc.update("UPDATE " + table + " SET brand_id = ? WHERE brand_id IS NULL", legacyBrandId);
                if (moved > 0) log.info("Moved {} {} rows into the legacy brand", moved, table);
            }
        }

        for (String table : tables) {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN brand_id SET NOT NULL");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_brand ON " + table + " (brand_id)");
            // content saved outside a brand workspace gets brand 0: refuse it instead of storing orphans
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS ck_" + table + "_brand");
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT ck_" + table + "_brand CHECK (brand_id > 0)");
        }

        PER_BRAND_UNIQUE.forEach((table, column) -> {
            if (!tableExists(table)) return;
            dropSingleColumnUniqueConstraints(table, column);
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + table + "_brand_" + column
                    + " ON " + table + " (brand_id, " + column + ")");
        });
    }

    /** Creates the brand that receives a pre-workspace database's content, named after its settings. */
    private long createLegacyBrand() {
        String name = "Legacy workspace";
        String industry = null;
        try {
            List<String> json = jdbc.queryForList(
                    "SELECT setting_value_json FROM master_settings WHERE setting_key = ? ORDER BY id LIMIT 1",
                    String.class, MasterSettingsService.SETTINGS_KEY);
            if (!json.isEmpty()) {
                Map<String, Object> s = objectMapper.readValue(json.get(0), new TypeReference<>() {});
                Object brandName = s.get("brandName");
                if (brandName instanceof String b && !b.isBlank()) name = b.trim();
                Object ind = s.get("industry");
                if (ind instanceof String i && !i.isBlank()) industry = i.trim();
            }
        } catch (Exception e) {
            log.warn("Could not read the old settings for the legacy brand name: {}", e.getMessage());
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO cf_brands (name, industry, active, created_at, updated_at) VALUES (?, ?, true, now(), now()) RETURNING id",
                Long.class, name, industry);
        log.info("Created legacy brand '{}' (id {}) for content made before brand workspaces", name, id);
        return id;
    }

    private void dropSingleColumnUniqueConstraints(String table, String column) {
        List<String> names = jdbc.queryForList("""
                SELECT c.conname FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
                WHERE t.relname = ? AND c.contype = 'u' AND array_length(c.conkey, 1) = 1 AND a.attname = ?
                """, String.class, table, column);
        for (String n : names) {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + n + "\"");
            log.info("Dropped global unique constraint {} on {}.{} (now unique per brand)", n, table, column);
        }
    }

    /** Hibernate's enum check constraints would refuse new values (STORY, DOCUMENT, new statuses). */
    private void dropOutdatedEnumChecks() {
        List<Map<String, Object>> checks = jdbc.queryForList("""
                SELECT t.relname AS tbl, c.conname AS name FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND c.contype = 'c'
                  AND c.conname ~ '_(content_type|asset_type|status|provider|approval_type|frame_type|publish_status)_check$'
                """);
        for (Map<String, Object> row : checks) {
            jdbc.execute("ALTER TABLE " + row.get("tbl") + " DROP CONSTRAINT IF EXISTS \"" + row.get("name") + "\"");
        }
    }

    // =========================================================================
    // Users
    // =========================================================================

    private void migrateUsers() {
        int converted = jdbc.update("UPDATE cf_users SET role = ? WHERE role IS NULL OR role NOT IN (?, ?)",
                UserRole.TEAM_MEMBER, UserRole.ADMIN, UserRole.TEAM_MEMBER);
        if (converted > 0) log.info("Turned {} old client logins into team members", converted);

        boolean hasAdmin = userRepository.findAll().stream().anyMatch(u -> UserRole.ADMIN.equals(u.getRole()));
        if (hasAdmin) return;

        if (bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank()
                && bootstrapAdminPassword != null && !bootstrapAdminPassword.isBlank()) {
            userRepository.findByEmailIgnoreCase(bootstrapAdminEmail.trim()).ifPresentOrElse(u -> {
                u.setRole(UserRole.ADMIN);
                userRepository.save(u);
            }, () -> userRepository.save(UserEntity.builder()
                    .email(bootstrapAdminEmail.trim().toLowerCase())
                    .adminName("Admin")
                    .role(UserRole.ADMIN)
                    .passwordHash(passwordService.hashPassword(bootstrapAdminPassword))
                    .active(true)
                    .build()));
            log.info("Admin account ensured for {}", bootstrapAdminEmail.trim());
            return;
        }

        userRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .min((a, b) -> Long.compare(a.getId(), b.getId()))
                .ifPresentOrElse(first -> {
                    first.setRole(UserRole.ADMIN);
                    userRepository.save(first);
                    log.warn("No admin existed: made the oldest account ({}) an admin", first.getEmail());
                }, () -> log.warn("No team accounts exist. Set contentforge.bootstrap.admin-email and "
                        + "contentforge.bootstrap.admin-password to create the first admin."));
    }

    // =========================================================================
    // Plans
    // =========================================================================

    private void migratePlans() {
        for (String column : List.of("event_reels_quota", "event_posts_quota", "premium_support", "reels_quota",
                "carousel_quota", "image_posts_quota", "price_inr", "billing_cycle", "active")) {
            try {
                jdbc.execute("ALTER TABLE cf_plans ALTER COLUMN " + column + " DROP NOT NULL");
            } catch (Exception ignored) {
                // column doesn't exist on a fresh database
            }
        }
        // plans from before the Standard/Premium/Custom tiers are retired, not deleted
        jdbc.update("UPDATE cf_plans SET active = false WHERE tier IS NULL");

        boolean seeded = planRepository.findAll().stream().anyMatch(p -> p.getTier() != null);
        if (seeded) return;

        planRepository.save(PlanEntity.builder()
                .name("Standard")
                .tier("STANDARD")
                .description("Consistent presence: about one feed post a day plus stories four days a week.")
                .billingCycle("MONTHLY")
                .priceInr(25000.0)
                .avatarReelsQuota(9)
                .explainerQuota(4)
                .carouselQuota(6)
                .imagePostsQuota(4)
                .storyFramesQuota(45)
                .minFaceVideos(8)
                .featuresJson(json(List.of(
                        "Brand onboarding and strategy document", "Profile setup", "Weekly plan with one-link client approval",
                        "Auto-publish at the best times", "Facebook cross-post", "Monthly results report",
                        "Quarterly strategy call", "1 language", "Basic compliance check", "Comment report")))
                .active(true)
                .build());
        planRepository.save(PlanEntity.builder()
                .name("Premium")
                .tier("PREMIUM")
                .description("Fast growth: five videos a week, daily stories and hands-on extras.")
                .billingCycle("MONTHLY")
                .priceInr(40000.0)
                .avatarReelsQuota(14)
                .explainerQuota(7)
                .carouselQuota(10)
                .imagePostsQuota(4)
                .storyFramesQuota(100)
                .minFaceVideos(13)
                .featuresJson(json(List.of(
                        "Everything in Standard", "Weekly and monthly results reports", "Monthly strategy call",
                        "2 languages", "Full compliance review by a person", "2 same-day fast reels a month",
                        "4 edits of the client's own clips a month", "AI-drafted comment replies, team approved",
                        "Lead DMs flagged to the client", "Monthly competitor summary", "LinkedIn cross-post",
                        "Story highlights kept up to date")))
                .active(true)
                .build());
        log.info("Seeded the Standard and Premium plans");
    }

    // =========================================================================

    private boolean tableExists(String table) {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '" + table + "'") > 0;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n != null ? n : 0;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
