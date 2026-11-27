package com.ozo.contentforge.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initializeSchema() {
        log.info("Running database schema validation and healing...");

        // 1. Ensure existing columns have TEXT type to prevent varchar(255) overflows in PostgreSQL
        String[] alterStatements = new String[] {
                "ALTER TABLE generated_assets ALTER COLUMN storage_url TYPE TEXT",
                "ALTER TABLE generated_assets ALTER COLUMN storage_path TYPE TEXT",
                "ALTER TABLE generated_assets ALTER COLUMN s3_key TYPE TEXT",
                "ALTER TABLE generated_assets ALTER COLUMN metadata_json TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN caption TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN hashtags TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN thumbnail_url TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN video_url TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN voice_audio_url TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN avatar_video_url TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN final_render_url TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN presenter_script TYPE TEXT",
                "ALTER TABLE content_posts ALTER COLUMN voiceover_script TYPE TEXT",
                "ALTER TABLE content_scenes ALTER COLUMN media_url TYPE TEXT",
                "ALTER TABLE content_scenes ALTER COLUMN prompt TYPE TEXT",
                "ALTER TABLE content_scenes ALTER COLUMN error_message TYPE TEXT",
                "ALTER TABLE video_renders ALTER COLUMN storage_url TYPE TEXT",
                "ALTER TABLE video_renders ALTER COLUMN s3_key TYPE TEXT",
                "CREATE TABLE IF NOT EXISTS video_avatars (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "description TEXT, " +
                        "video_url TEXT, " +
                        "thumbnail_url TEXT, " +
                        "avatar_id VARCHAR(255), " +
                        "asset_id VARCHAR(255), " +
                        "group_id VARCHAR(255), " +
                        "look_id VARCHAR(255), " +
                        "status VARCHAR(50), " +
                        "status_message TEXT, " +
                        "is_default BOOLEAN DEFAULT FALSE, " +
                        "duration_seconds INTEGER, " +
                        "created_at TIMESTAMP, " +
                        "updated_at TIMESTAMP" +
                        ")",
                "ALTER TABLE video_avatars ALTER COLUMN video_url TYPE TEXT",
                "ALTER TABLE video_avatars ALTER COLUMN thumbnail_url TYPE TEXT",
                "ALTER TABLE video_avatars ALTER COLUMN description TYPE TEXT",
                "ALTER TABLE video_avatars ALTER COLUMN status_message TYPE TEXT",
                "ALTER TABLE video_avatars ADD COLUMN IF NOT EXISTS consent_status VARCHAR(50)",
                "ALTER TABLE video_avatars ADD COLUMN IF NOT EXISTS consent_url TEXT",
                // Reel enhance (render service): which engine made the current video, and the enhance status
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS render_engine VARCHAR(30)",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS render_status VARCHAR(30)",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS render_note TEXT",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS render_job_id VARCHAR(64)",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS enhance_requested BOOLEAN",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS enhanced_render_url TEXT",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS audio_samples_json TEXT",
                "ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS selected_audio_tone VARCHAR(100)",
                // Hibernate's enum check constraint would refuse the new RENDER_SERVICE provider or new status/approval enums on existing databases
                "ALTER TABLE media_jobs DROP CONSTRAINT IF EXISTS media_jobs_provider_check",
                "ALTER TABLE content_posts DROP CONSTRAINT IF EXISTS content_posts_status_check",
                "ALTER TABLE content_approvals DROP CONSTRAINT IF EXISTS content_approvals_approval_type_check"
        };

        for (String sql : alterStatements) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                // Table or column may not exist yet or already TEXT; safe to ignore
                log.debug("Schema migration notice for statement [{}]: {}", sql, e.getMessage());
            }
        }

        // Data fixes for the old single-brand database were removed: with brand workspaces they would touch every brand.

        log.info("Database schema validation completed.");
    }
}
