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
                "ALTER TABLE video_renders ALTER COLUMN s3_key TYPE TEXT"
        };

        for (String sql : alterStatements) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                // Table or column may not exist yet or already TEXT; safe to ignore
                log.debug("Schema migration notice for statement [{}]: {}", sql, e.getMessage());
            }
        }

        log.info("Database schema validation completed.");
    }
}
