package com.ozo.contentforge.service.publishing;

import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.repository.ContentPostRepository;
import com.ozo.contentforge.tenancy.BrandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Every minute: publishes every brand's approved posts whose time has come, each inside its own brand. */
@Component
public class ScheduledPublisher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPublisher.class);

    private final ContentPostRepository postRepository;
    private final PublishingService publishingService;

    public ScheduledPublisher(ContentPostRepository postRepository, PublishingService publishingService) {
        this.postRepository = postRepository;
        this.publishingService = publishingService;
    }

    @Scheduled(fixedDelayString = "${contentforge.publish.poll-ms:60000}", initialDelayString = "${contentforge.publish.initial-delay-ms:30000}")
    public void publishDuePosts() {
        List<ContentPostEntity> due = BrandContext.callAsSystem(() -> postRepository.findDueForPublishing(LocalDateTime.now()));
        for (ContentPostEntity post : due) {
            try {
                BrandContext.runAs(post.getBrandId(), () -> publishingService.publish(post.getId()));
            } catch (Exception e) {
                // e.g. another publisher claimed it first; the post keeps its own status and error
                log.warn("Scheduled publish of post {} skipped: {}", post.getId(), e.getMessage());
            }
        }
    }
}
