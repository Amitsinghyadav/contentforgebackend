package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.DailyGenerationBatchEntity;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.service.ContentBatchService;
import com.ozo.contentforge.service.ContentPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/generation-batches")
@CrossOrigin
public class GenerationBatchController {

    private static final Logger log = LoggerFactory.getLogger(GenerationBatchController.class);

    private final ContentBatchService batchService;
    private final ContentPostService postService;

    public GenerationBatchController(ContentBatchService batchService, ContentPostService postService) {
        this.batchService = batchService;
        this.postService = postService;
    }

    @GetMapping
    public ResponseEntity<?> getBatchByDate(@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return batchService.getBatchForDate(date)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createBatchForDate(@RequestBody(required = false) Map<String, Object> body) {
        LocalDate date = (body != null && body.containsKey("date"))
                ? LocalDate.parse(body.get("date").toString())
                : LocalDate.now();
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));

        ContentType requestedType = null;
        if (body != null && body.containsKey("contentType") && body.get("contentType") != null) {
            String ctStr = body.get("contentType").toString().trim();
            if (!ctStr.isBlank()) {
                try {
                    requestedType = ContentType.valueOf(ctStr.toUpperCase());
                } catch (Exception ignored) {}
            }
        }

        String topic = null;
        if (body != null && body.containsKey("topic") && body.get("topic") != null) {
            String tStr = body.get("topic").toString().trim();
            if (!tStr.isBlank()) {
                topic = tStr;
            }
        }

        try {
            DailyGenerationBatchEntity batch = batchService.planAndCreateBatch(date, force, requestedType, topic);
            if ("NO_SCHEDULED_CONTENT".equals(batch.getStatus())) {
                return ResponseEntity.status(HttpStatus.OK).body(Map.of(
                        "status", "NO_SCHEDULED_CONTENT",
                        "message", "No content scheduled on the Reel Planner for " + date + ". Please select what to generate."
                ));
            }
            return ResponseEntity.ok(batch);
        } catch (Exception e) {
            log.error("Failed to generate batch for date {}: {}", date, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Batch generation failed",
                    "status", "FAILED"
            ));
        }
    }

    @DeleteMapping("/date/{date}/drafts")
    public ResponseEntity<?> purgeDraftsForDate(@PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int count = postService.purgeUnpublishedPostsForDate(date);
        return ResponseEntity.ok(Map.of("purgedCount", count, "message", "Cleared " + count + " draft posts for " + date));
    }
}
