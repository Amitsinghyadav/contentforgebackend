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
                // Hibernate's enum check constraint would refuse the new RENDER_SERVICE provider on existing databases
                "ALTER TABLE media_jobs DROP CONSTRAINT IF EXISTS media_jobs_provider_check"
        };

        for (String sql : alterStatements) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                // Table or column may not exist yet or already TEXT; safe to ignore
                log.debug("Schema migration notice for statement [{}]: {}", sql, e.getMessage());
            }
        }

        // Restore Sneha's original uploaded photo likeness in presenter_profiles
        try {
            jdbcTemplate.update("UPDATE presenter_profiles SET " +
                    "avatar_image_url = '/api/v1/media/s3?key=media/photos/4ca4556d-ab39-4501-8804-9ed92b050d6e.png', " +
                    "avatar_id = '22f2ac4816674ad396a1bb603ffe09a2', " +
                    "name = 'Sneha', " +
                    "role_title = 'Brand Influencer', " +
                    "voice_provider = 'ELEVENLABS', " +
                    "voice_id = 'rRAzMEZ6U57ivzmuPBhL' " +
                    "WHERE id = 3 OR name = 'Sneha'");
            log.info("Verified and restored Sneha photo presenter profile.");
        } catch (Exception e) {
            log.debug("Notice on restoring presenter profile: {}", e.getMessage());
        }



        // Clean up failed custom video avatars whose HeyGen groups were deleted
        try {
            jdbcTemplate.update("DELETE FROM video_avatars WHERE id IN (5, 6, 7, 8) OR asset_id IN ('f2447cc8e667419e96c7d9cd664e925d', '778c33873d3c4b29b4ec2080e3c1070e', 'c4f558e28f494807b45f77328073bce9', 'b3ae4caffcab4e61a37c0a5edd7cf16e')");
            jdbcTemplate.update("UPDATE video_avatars SET is_default = true WHERE avatar_id = 'Aditya_public_1'");
            log.info("Cleaned up deleted HeyGen avatar records and reset default video avatar.");
        } catch (Exception e) {
            log.debug("Notice on video_avatars cleanup: {}", e.getMessage());
        }

        // Clean up invalid HeyGen avatar look IDs from video_avatars table
        try {
            jdbcTemplate.update("DELETE FROM video_avatars WHERE avatar_id IN ('Eileen_public', 'Wayne_20240711', 'Daisy-inskirt-20220818', 'josh_lite3_20230714', 'Tyler-incasualsuit-20220721', 'Monica_public')");
            log.info("Cleaned up invalid avatar look IDs in video_avatars.");
        } catch (Exception e) {
            log.debug("Notice on video_avatars cleanup: {}", e.getMessage());
        }

        // Clean up legacy default SVG media URLs so they are never returned or preserved
        try {
            jdbcTemplate.update("UPDATE content_posts SET thumbnail_url = NULL WHERE thumbnail_url LIKE '%.svg'");
            jdbcTemplate.update("UPDATE content_posts SET final_render_url = NULL WHERE final_render_url LIKE '%.svg'");
            jdbcTemplate.update("UPDATE carousel_slides SET image_url = NULL, asset_id = NULL, status = 'QUEUED' WHERE image_url LIKE '%.svg'");
            log.info("Cleaned up legacy default SVG media URLs from content_posts and carousel_slides.");
        } catch (Exception e) {
            log.debug("Notice on cleaning legacy SVG media: {}", e.getMessage());
        }

        log.info("Database schema validation completed.");
    }
}
